package com.legacyaudit.engine.linker;

import com.legacyaudit.engine.analyzers.PyUrl;
import com.legacyaudit.engine.analyzers.TextUtil;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.Schemas;
import com.legacyaudit.model.json.PyJson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merging per-file facts into one fact set: the same integration, library, tech item, batch job
 * or database object found in several files becomes one fact with all its evidence.
 */
public final class Aggregate {

    static final int MAX_EVIDENCE = 50;

    private Aggregate() {}

    static List<String> union(List<?> a, Iterable<?> b) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object x : a) {
            out.add((String) x);
            seen.add((String) x);
        }
        for (Object x : b) {
            if (seen.add((String) x)) {
                out.add((String) x);
            }
        }
        return out;
    }

    private record Key(String file, Integer line) {}

    static List<Evidence> evidence(List<Evidence> a, List<Evidence> b) {
        Set<Key> keys = new HashSet<>();
        for (Evidence e : a) {
            keys.add(new Key(e.file(), e.line()));
        }
        List<Evidence> out = new ArrayList<>(a);
        for (Evidence e : b) {
            if (out.size() >= MAX_EVIDENCE) {
                break;
            }
            if (keys.add(new Key(e.file(), e.line()))) {
                out.add(e);
            }
        }
        return out;
    }

    static List<Evidence> ev(Doc d) {
        return d.list("evidence");
    }

    static void addEvidence(Doc into, Doc from) {
        into.set("evidence", evidence(ev(into), ev(from)));
    }

    static long num(Doc d, String field) {
        Object v = d.get(field);
        return v == null ? 0 : ((Number) v).longValue();
    }

    /** Keep the first fact per id, adding later evidence to it. */
    public static List<Doc> dedupe(Iterable<Doc> items) {
        Map<String, Doc> byId = new LinkedHashMap<>();
        for (Doc item : items) {
            Doc prev = byId.get(item.id());
            if (prev == null) {
                byId.put(item.id(), item);
            } else {
                addEvidence(prev, item);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * One screen per id. A Windows Forms form is written in two files ({@code MainForm.vb} and
     * {@code MainForm.Designer.vb}): the code file names it, the designer file holds its controls.
     */
    public static List<Doc> mergeScreens(Iterable<Doc> items) {
        Map<String, Doc> byId = new LinkedHashMap<>();
        for (Doc item : items) {
            Doc prev = byId.get(item.id());
            if (prev == null) {
                byId.put(item.id(), item);
                continue;
            }
            long fields = Math.max(num(prev, "fields_count"), num(item, "fields_count"));
            String pf = Objects.requireNonNullElse(prev.str("file"), "").toLowerCase(Locale.ROOT);
            String itf = Objects.requireNonNullElse(item.str("file"), "").toLowerCase(Locale.ROOT);
            if (pf.contains(".designer.") && !itf.contains(".designer.")) {
                item.set("evidence", evidence(ev(item), ev(prev)));
                byId.put(item.id(), item);
                prev = item;
            } else {
                addEvidence(prev, item);
            }
            prev.set("fields_count", fields);
        }
        return new ArrayList<>(byId.values());
    }

    private static void mergeInto(Doc prev, Doc it) {
        prev.set("usages", num(prev, "usages") + num(it, "usages"));
        prev.set("source_files", union(prev.list("source_files"), it.list("source_files")));
        prev.set("producers", union(prev.list("producers"), it.list("producers")));
        prev.set("consumers", union(prev.list("consumers"), it.list("consumers")));
        addEvidence(prev, it);
        if (!IntegrationText.truthy(prev.get("protocol"))) {
            prev.set("protocol", it.get("protocol"));
        }
        Map<String, Object> details = prev.map("details");
        it.<Object>map("details").forEach(details::putIfAbsent);
    }

    public static List<Doc> mergeIntegrations(Iterable<Doc> items) {
        Map<String, Doc> byId = new LinkedHashMap<>();
        Set<String> defined = new HashSet<>();
        for (Doc it : items) {
            if ("defined".equals(it.str("direction"))) {
                defined.add(it.id());
                it = it.copy().set("usages", 0L); // a definition is not a use
            }
            Doc prev = byId.get(it.id());
            if (prev == null) {
                byId.put(it.id(), it.copy());
                continue;
            }
            mergeInto(prev, it);
        }
        List<Doc> out = new ArrayList<>(byId.values());
        for (Doc it : out) {
            if (it.str("type").equals("MQ") || it.str("type").equals("JMS")) {
                queueDirection(it, defined.contains(it.id()));
            }
        }
        List<Doc> merged = consolidate(mergeCft(out));
        for (Doc it : merged) {
            IntegrationText.describeInto(it);
        }
        return merged;
    }

    private static void queueDirection(Doc it, boolean defined) {
        boolean producers = !it.list("producers").isEmpty();
        boolean consumers = !it.list("consumers").isEmpty();
        if (producers && consumers) {
            it.set("direction", "both");
        } else if (producers) {
            it.set("direction", "outbound");
        } else if (consumers) {
            it.set("direction", "inbound");
        } else {
            it.set("direction", defined ? "defined" : "unknown");
        }
    }

    private record Member(Doc it, String display) {}

    /**
     * One row per interface: usages of the same target found with different directions,
     * spellings (host case, default port, trailing slash, {@code queue:///}), or as HTTP and
     * SOAP fold into one integration whose direction is the union (inbound + outbound = both).
     */
    private static List<Doc> consolidate(List<Doc> items) {
        Map<String, List<Member>> groups = new LinkedHashMap<>();
        for (Doc it : items) {
            String[] k = IntegrationText.consolidationKey(it.str("type"), it.str("target"));
            groups.computeIfAbsent(k[0] + "\u0000" + k[1], x -> new ArrayList<>()).add(new Member(it, k[2]));
        }
        List<Doc> out = new ArrayList<>();
        for (List<Member> members : groups.values()) {
            // The most detailed spelling names the row (QM1@host:1414 over QM1); then most used.
            members.sort(Comparator.<Member>comparingInt(m -> -m.display().codePointCount(0, m.display().length()))
                    .thenComparingLong(m -> -num(m.it(), "usages"))
                    .thenComparing(Member::display, PyJson::compareCodePoints)
                    .thenComparing(m -> m.it().id(), PyJson::compareCodePoints));
            Doc merged = members.getFirst().it().copy();
            merged.set("target", members.getFirst().display());
            if (members.size() > 1) {
                Set<String> directions = new LinkedHashSet<>();
                members.forEach(m -> directions.add(m.it().str("direction")));
                for (Member m : members.subList(1, members.size())) {
                    mergeInto(merged, m.it());
                }
                if (members.stream().anyMatch(m -> m.it().str("type").equals("SOAP"))) {
                    merged.set("type", "SOAP");
                }
                String type = merged.str("type");
                if ((type.equals("MQ") || type.equals("JMS"))
                        && (!merged.list("producers").isEmpty() || !merged.list("consumers").isEmpty())) {
                    queueDirection(merged, false);
                } else {
                    merged.set("direction", IntegrationText.mergeDirections(directions));
                }
            }
            out.add(merged);
        }
        return out;
    }

    /**
     * CFT flows from a configuration export (target {@code * /IDF}) fold into the script usages
     * of the same IDF and direction; unused exported flows are kept on their own.
     */
    private static List<Doc> mergeCft(List<Doc> items) {
        Map<String, List<Doc>> used = new LinkedHashMap<>();
        for (Doc it : items) {
            if (it.str("type").equals("CFT") && !it.str("target").startsWith("*/")) {
                String idf = String.valueOf(it.map("details").getOrDefault("idf", ""));
                used.computeIfAbsent(idf + "\u0000" + it.str("direction"), k -> new ArrayList<>()).add(it);
            }
        }
        List<Doc> out = new ArrayList<>();
        for (Doc it : items) {
            if (it.str("type").equals("CFT") && it.str("target").startsWith("*/")) {
                List<Doc> matches = used.get(it.str("target").substring(2) + "\u0000" + it.str("direction"));
                if (matches != null && !matches.isEmpty()) {
                    for (Doc m : matches) {
                        addEvidence(m, it);
                        m.map("details").put("defined_by_export", "true");
                        Object file = it.map("details").get("file");
                        if (IntegrationText.truthy(file) && !m.map("details").containsKey("file")) {
                            m.map("details").put("file", file);
                        }
                    }
                    continue;
                }
                it.set("usages", 0L); // defined only
            }
            out.add(it);
        }
        return out;
    }

    private static String folder(String path) {
        return path.contains("/") ? TextUtil.beforeLast(path, "/") : "";
    }

    private static String major(Doc lib) {
        return TextUtil.beforeFirst(Objects.requireNonNullElse(lib.str("version"), ""), ".");
    }

    /**
     * A package that {@code package.json} declares and a lock file in the same folder pins is one
     * library at the lock file's exact version, declared, with the {@code package.json} scope.
     */
    public static List<Doc> mergeNpmVersions(List<Doc> libs) {
        Map<String, List<Doc>> locked = new LinkedHashMap<>();
        for (Doc lib : libs) {
            if ("npm-lock".equals(lib.str("source"))) {
                for (Object f : lib.list("where_used")) {
                    locked.computeIfAbsent(folder((String) f) + "\u0000" + lib.str("artifact"), k -> new ArrayList<>()).add(lib);
                }
            }
        }
        List<Doc> out = new ArrayList<>();
        for (Doc lib : libs) {
            List<Object> where = lib.list("where_used");
            if ("npm".equals(lib.str("source")) && !where.isEmpty()) {
                List<Doc> cands = locked.getOrDefault(folder((String) where.getFirst()) + "\u0000" + lib.str("artifact"), List.of());
                // Several versions installed (nested copies): the one of the declared major.
                String mj = major(lib);
                Doc hit = cands.stream().filter(c -> major(c).equals(mj)).findFirst().orElse(cands.isEmpty() ? null : cands.getFirst());
                if (hit != null) {
                    hit.set("declared", true);
                    hit.set("source", "npm");
                    hit.set("scope", lib.get("scope"));
                    hit.set("where_used", union(lib.list("where_used"), hit.list("where_used")));
                    hit.set("evidence", evidence(ev(lib), ev(hit)));
                    continue;
                }
            }
            out.add(lib);
        }
        return out;
    }

    public static List<Doc> mergeLibraries(Iterable<Doc> items) {
        Map<String, Doc> byId = new LinkedHashMap<>();
        for (Doc lib : items) {
            Doc prev = byId.get(lib.id());
            if (prev == null) {
                byId.put(lib.id(), lib.copy());
                continue;
            }
            prev.set("declared", prev.bool("declared") || lib.bool("declared"));
            prev.set("where_used", union(prev.list("where_used"), lib.list("where_used")));
            addEvidence(prev, lib);
            if (!IntegrationText.truthy(prev.get("sha1"))) {
                prev.set("sha1", lib.get("sha1"));
            }
            if (!IntegrationText.truthy(prev.get("scope"))) {
                prev.set("scope", lib.get("scope"));
            }
            if (!Objects.equals(prev.str("source"), lib.str("source")) && lib.bool("declared") && !prev.bool("declared")) {
                prev.set("source", lib.get("source"));
            }
        }
        // Jars named only by file (no group) fold into a declared library with the same name.
        Map<String, List<Doc>> named = new LinkedHashMap<>();
        for (Doc lib : byId.values()) {
            if (IntegrationText.truthy(lib.get("group"))) {
                named.computeIfAbsent(libKey(lib), k -> new ArrayList<>()).add(lib);
            }
        }
        List<Doc> out = new ArrayList<>();
        for (Doc lib : byId.values()) {
            if (lib.get("group") == null) {
                List<Doc> targets = named.get(libKey(lib));
                if (targets != null && !targets.isEmpty()) {
                    Doc t = targets.stream()
                            .min((a, b) -> PyJson.compareCodePoints(Objects.requireNonNullElse(a.str("group"), ""),
                                    Objects.requireNonNullElse(b.str("group"), "")))
                            .orElseThrow();
                    t.set("where_used", union(t.list("where_used"), lib.list("where_used")));
                    t.set("evidence", evidence(ev(t), ev(lib)));
                    if (!IntegrationText.truthy(t.get("sha1"))) {
                        t.set("sha1", lib.get("sha1"));
                    }
                    continue;
                }
            }
            out.add(lib);
        }
        return out;
    }

    private static String libKey(Doc lib) {
        return lib.str("language") + "\u0000" + lib.str("artifact") + "\u0000" + lib.str("version");
    }

    private record TechRule(Pattern rx, String name, String category) {}

    private static TechRule t(String rx, String name, String category) {
        return new TechRule(Rx.of(rx), name, category);
    }

    // Framework and product names derived from library coordinates.
    private static final List<TechRule> LIB_TECH = List.of(
            t("^(struts|struts-core)$", "Struts 1", "framework"),
            t("^struts2-core$", "Struts 2", "framework"),
            t("^spring(-(core|context|beans|web|webmvc|jdbc|jms|tx|orm|aop))?$", "Spring Framework", "framework"),
            t("^spring-boot$", "Spring Boot", "framework"),
            t("^spring-batch-core$", "Spring Batch", "framework"),
            t("^spring-security-core$", "Spring Security", "auth"),
            t("^spring-webflow$", "Spring Web Flow", "framework"),
            t("^(hibernate|hibernate3|hibernate-core)$", "Hibernate", "framework"),
            t("^log4j$", "Log4j 1", "library"),
            t("^log4j-core$", "Log4j 2", "library"),
            t("^logback-classic$", "Logback", "library"),
            t("^axis$", "Apache Axis", "framework"),
            t("^axis2(-kernel)?$", "Apache Axis2", "framework"),
            t("^cxf-core$", "Apache CXF", "framework"),
            t("^mybatis$", "MyBatis", "framework"),
            t("^(ibatis|ibatis-sqlmap|ibatis2-sqlmap)$", "iBATIS", "framework"),
            t("^ojdbc\\d*$", "Oracle JDBC driver", "database"),
            t("^db2jcc\\d*$", "DB2 JDBC driver", "database"),
            t("^(com\\.ibm\\.mq(\\.allclient|\\.jakarta\\.client)?|mq|mqjms|com\\.ibm\\.mqjms)$", "IBM MQ client", "middleware"),
            t("^quartz$", "Quartz", "scheduler"),
            t("^(jsf-api|myfaces-api|jakarta\\.faces-api)$", "JSF", "framework"),
            t("^jquery$", "jQuery", "library"),
            t("^jquery[.-]ui$", "jQuery UI", "library"),
            t("^angular$", "AngularJS", "framework"),
            t("^bootstrap$", "Bootstrap", "library"),
            // Embedded or vendor server libraries in a Maven/Gradle build.
            t("^tomcat-embed-core$", "Apache Tomcat (embedded)", "app-server"),
            t("^(jetty-server|jetty-webapp)$", "Eclipse Jetty", "app-server"),
            t("^undertow-core$", "Undertow", "app-server"),
            t("^(weblogic|wlfullclient|wlthint3client|weblogic-server-pom)$", "Oracle WebLogic Server", "app-server"),
            t("^(was_public|com\\.ibm\\.ws\\.webservices\\.thinclient.*|com\\.ibm\\.websphere\\..*)$", "IBM WebSphere Application Server",
                    "app-server"),
            t("^(jboss-as-[a-z-]+|jboss-client)$", "JBoss AS", "app-server"),
            t("^wildfly-[a-z-]+$", "WildFly", "app-server"));

    // npm packages (language javascript): frameworks and runtimes of Angular and Node.js code.
    private static final List<TechRule> NPM_TECH = List.of(
            t("^@angular/core$", "Angular", "framework"),
            t("^angular$", "AngularJS", "framework"),
            t("^@uirouter/angularjs$|^angular-ui-router$", "AngularJS UI-Router", "library"),
            t("^@angular/material$", "Angular Material", "library"),
            t("^rxjs$", "RxJS", "library"),
            t("^@ngrx/store$", "NgRx", "library"),
            t("^typescript$", "TypeScript", "language"),
            t("^react$", "React", "framework"),
            t("^vue$", "Vue.js", "framework"),
            t("^jquery$", "jQuery", "library"),
            t("^bootstrap$", "Bootstrap", "library"),
            t("^express$", "Express", "framework"),
            t("^@nestjs/core$", "NestJS", "framework"),
            t("^fastify$", "Fastify", "framework"),
            t("^koa$", "Koa", "framework"),
            t("^@hapi/hapi$|^hapi$", "hapi", "framework"),
            t("^typeorm$", "TypeORM", "framework"),
            t("^(prisma|@prisma/client)$", "Prisma", "framework"),
            t("^sequelize$", "Sequelize", "framework"),
            t("^knex$", "Knex.js", "library"),
            t("^mongoose$", "Mongoose", "framework"),
            t("^(pg|pg-promise|postgres)$", "PostgreSQL", "database"),
            t("^(oracledb|node-oracledb)$", "Oracle", "database"),
            t("^(mysql|mysql2)$", "MySQL", "database"),
            t("^(mssql|tedious)$", "SQL Server", "database"),
            t("^(ibm_db|ibm_db2)$", "DB2", "database"),
            t("^(mongodb)$", "MongoDB", "database"),
            t("^(redis|ioredis)$", "Redis", "database"),
            t("^node-cron$", "node-cron", "scheduler"),
            t("^cron$", "cron (npm)", "scheduler"),
            t("^node-schedule$", "node-schedule", "scheduler"),
            t("^@nestjs/schedule$", "NestJS Schedule", "scheduler"),
            t("^agenda$", "Agenda", "scheduler"),
            t("^(bull|bullmq)$", "Bull queue", "scheduler"),
            t("^kafkajs$", "Kafka client (kafkajs)", "middleware"),
            t("^amqplib$", "AMQP client (amqplib)", "middleware"),
            t("^ibmmq$", "IBM MQ client", "middleware"),
            t("^passport$", "Passport", "auth"),
            t("^@okta/okta-(auth-js|angular|react)$", "Okta SDK", "auth"),
            t("^keycloak-(js|angular)$", "Keycloak adapter", "auth"),
            t("^angular-oauth2-oidc$", "angular-oauth2-oidc", "auth"),
            t("^@azure/msal-(angular|browser|node)$", "Microsoft MSAL", "auth"),
            t("^jsonwebtoken$", "jsonwebtoken (JWT)", "auth"),
            t("^(soap|strong-soap)$", "SOAP client (Node.js)", "library"),
            t("^(@nrwl/workspace|nx)$", "Nx", "build"),
            t("^webpack$", "webpack", "build"),
            t("^gulp$", "gulp", "build"),
            t("^grunt$", "Grunt", "build"));

    // NuGet packages (language dotnet): .NET frameworks and libraries, with their versions.
    private static final List<TechRule> NUGET_TECH = List.of(
            t("^microsoft\\.entityframeworkcore(\\.(sqlserver|sqlite|relational|cosmos))?$", "Entity Framework Core", "framework"),
            t("^entityframework$", "Entity Framework", "framework"),
            t("^dapper$", "Dapper", "library"),
            t("^nhibernate$", "NHibernate", "framework"),
            t("^microsoft\\.aspnet\\.mvc$", "ASP.NET MVC", "framework"),
            t("^microsoft\\.aspnet\\.webapi(\\.core)?$", "ASP.NET Web API", "framework"),
            t("^hangfire(\\.core)?$", "Hangfire", "scheduler"),
            t("^quartz$", "Quartz.NET", "scheduler"),
            t("^log4net$", "log4net", "library"),
            t("^nlog$", "NLog", "library"),
            t("^serilog$", "Serilog", "library"),
            t("^newtonsoft\\.json$", "Json.NET", "library"),
            t("^automapper$", "AutoMapper", "library"),
            t("^(oracle\\.manageddataaccess(\\.core)?|oracle\\.dataaccess)$", "Oracle .NET driver", "database"),
            t("^(system|microsoft)\\.data\\.sqlclient$", "SQL Server .NET driver", "database"),
            t("^(npgsql|npgsql\\.entityframeworkcore\\.postgresql)$", "PostgreSQL .NET driver (Npgsql)", "database"),
            t("^(mysql\\.data|mysqlconnector)$", "MySQL .NET driver", "database"),
            t("^ibm\\.data\\.db2(\\.core)?$", "DB2 .NET driver", "database"),
            t("^ibmmqdotnet$|^ibmxmsdotnetclient$", "IBM MQ client", "middleware"),
            t("^rabbitmq\\.client$", "RabbitMQ client", "middleware"),
            t("^confluent\\.kafka$", "Kafka client (Confluent)", "middleware"),
            t("^microsoft\\.identity\\.client$", "Microsoft MSAL", "auth"),
            t("^okta\\.", "Okta SDK", "auth"),
            t("^devexpress\\.|^telerik\\.|^infragistics", "Commercial UI controls", "library"),
            t("^crystaldecisions\\.", "Crystal Reports", "library"));

    public static List<Doc> techsFromLibraries(Iterable<Doc> libs) {
        List<Doc> out = new ArrayList<>();
        for (Doc lib : libs) {
            String lang = lib.str("language");
            if ((lang.equals("javascript") || lang.equals("dotnet")) && "transitive".equals(lib.str("scope"))) {
                continue; // what the application uses directly, not what its packages pull in
            }
            if ("com".equals(lib.str("source"))) {
                continue; // VB6 references are mapped by the analyzer
            }
            List<TechRule> rules = lang.equals("javascript") ? NPM_TECH : lang.equals("dotnet") ? NUGET_TECH : LIB_TECH;
            for (TechRule r : rules) {
                if (r.rx().matcher(lib.str("artifact").toLowerCase(Locale.ROOT)).lookingAt()) {
                    String version = lib.str("version");
                    List<Evidence> evs = ev(lib);
                    out.add(Doc.of(Schemas.TECH_ITEM,
                            "id", Fact.makeId("tech", r.name(), version == null ? "" : version),
                            "name", r.name(),
                            "version", version,
                            "category", r.category(),
                            "evidence", new ArrayList<>(evs.subList(0, Math.min(1, evs.size())))));
                    break;
                }
            }
        }
        return out;
    }

    private static final Set<String> FOLD_CATEGORIES = Set.of("language", "runtime", "app-server", "web-server");
    private static final Pattern DIGITS = Rx.of("\\d+");

    private static List<java.math.BigInteger> versionKey(String v) {
        List<java.math.BigInteger> out = new ArrayList<>();
        Matcher m = DIGITS.matcher(v);
        while (m.find() && out.size() < 4) {
            String d = m.group().chars().mapToObj(c -> String.valueOf(Character.digit(c, 10))).reduce("", String::concat);
            out.add(new java.math.BigInteger(d));
        }
        return out;
    }

    private static int compareVersionKeys(List<java.math.BigInteger> a, List<java.math.BigInteger> b) {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = a.get(i).compareTo(b.get(i));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    public static List<Doc> mergeTech(Iterable<Doc> items) {
        Map<String, Doc> byId = new LinkedHashMap<>();
        for (Doc t : items) {
            Doc prev = byId.get(t.id());
            if (prev == null) {
                byId.put(t.id(), t.copy());
                continue;
            }
            prev.set("usages", num(prev, "usages") + num(t, "usages"));
            addEvidence(prev, t);
        }
        Map<String, List<Doc>> versions = new LinkedHashMap<>();
        for (Doc t : byId.values()) {
            if (IntegrationText.truthy(t.get("version"))) {
                versions.computeIfAbsent(t.str("name"), k -> new ArrayList<>()).add(t);
            }
        }
        List<Doc> out = new ArrayList<>();
        for (Doc t : byId.values()) {
            List<Doc> targets = versions.get(t.str("name"));
            if (!IntegrationText.truthy(t.get("version")) && targets != null && !targets.isEmpty()
                    && (targets.size() == 1 || FOLD_CATEGORIES.contains(t.str("category")))) {
                // A mention without a version is the same product: it goes with the version found
                // (with the newest one when several are, for runtimes and front-end frameworks).
                Doc target = targets.getFirst();
                for (Doc x : targets) {
                    if (compareVersionKeys(versionKey(x.str("version")), versionKey(target.str("version"))) > 0) {
                        target = x;
                    }
                }
                target.set("usages", num(target, "usages") + num(t, "usages"));
                addEvidence(target, t);
                continue;
            }
            out.add(t);
        }
        out.sort(Comparator.<Doc, String>comparing(t -> t.str("category"), PyJson::compareCodePoints)
                .thenComparing(t -> t.str("name").toLowerCase(Locale.ROOT), PyJson::compareCodePoints)
                .thenComparing(t -> Objects.requireNonNullElse(t.str("version"), ""), PyJson::compareCodePoints));
        return out;
    }

    /**
     * The same job in several files (e.g. JIL exports): the later definition wins, evidence is
     * kept from all of them.
     */
    public static List<Doc> mergeBatchJobs(Iterable<Doc> items) {
        Map<String, Doc> byId = new LinkedHashMap<>();
        for (Doc job : items) {
            Doc prev = byId.get(job.id());
            if (prev != null) {
                job = job.copy().set("evidence", evidence(ev(prev), ev(job)));
            }
            byId.put(job.id(), job);
        }
        return new ArrayList<>(byId.values());
    }

    /** Package spec and body are one object; definitions found twice keep all evidence. */
    public static List<Doc> mergeDbObjects(Iterable<Doc> items) {
        Map<String, Doc> byId = new LinkedHashMap<>();
        for (Doc obj : items) {
            Doc prev = byId.get(obj.id());
            if (prev == null) {
                byId.put(obj.id(), obj.copy());
                continue;
            }
            TreeSet<String> members = new TreeSet<>(PyJson::compareCodePoints);
            prev.<String>list("members").forEach(members::add);
            obj.<String>list("members").forEach(members::add);
            prev.set("members", new ArrayList<>(members));
            addEvidence(prev, obj);
            if (!IntegrationText.truthy(prev.get("defined_in"))) {
                prev.set("defined_in", obj.get("defined_in"));
            }
        }
        return new ArrayList<>(byId.values());
    }

    // ---- one row per external service (decision 177) ---------------------------------------

    private static String qmgrName(String target) {
        return TextUtil.strip(TextUtil.beforeFirst(target, "@")).toUpperCase(Locale.ROOT);
    }

    /**
     * The external service a usage belongs to: a host (HTTP, SOAP), a queue manager (its queues
     * and channels), a file server or folder. Null: the row is a service on its own.
     */
    private static String[] serviceKey(Doc it, Map<String, String> qmOf) {
        String type = it.str("type");
        String target = it.str("target");
        if (type.equals("HTTP") || type.equals("SOAP")) {
            PyUrl parts = PyUrl.split(target);
            String host = IntegrationText.host(parts);
            if (host.isEmpty()) {
                return null;
            }
            String port = IntegrationText.portText(parts);
            if (port == null || port.equals("80") || port.equals("443")) {
                port = "";
            }
            return new String[] {"HTTP", port.isEmpty() ? host : host + ":" + port};
        }
        if (type.equals("MQ-QMGR")) {
            return new String[] {"MQ", qmgrName(target)};
        }
        if ((type.equals("MQ") || type.equals("MQ-CHANNEL")) && qmOf.containsKey(it.id())) {
            return new String[] {"MQ", qmOf.get(it.id())};
        }
        if (type.equals("FILE")) {
            String path = target.replace("\\", "/");
            if (path.startsWith("//")) {
                String server = TextUtil.beforeFirst(path.substring(2), "/");
                return server.isEmpty() ? null : new String[] {"FILE", "//" + server.toLowerCase(Locale.ROOT)};
            }
            String folder = TextUtil.dirname(path);
            return !folder.isEmpty() && !folder.equals("/") && !folder.equals(".") ? new String[] {"FILE", folder} : null;
        }
        return null;
    }

    /**
     * Queue or channel id to the queue manager it belongs to: the one its MQSC export names (the
     * {@code * ... queue manager QM1} header or the file name), else the only queue manager every
     * component using it connects to.
     */
    private static Map<String, String> queueManagers(List<Doc> items) {
        Set<String> known = new HashSet<>();
        Map<String, Set<String>> byComp = new LinkedHashMap<>();
        for (Doc it : items) {
            if (it.str("type").equals("MQ-QMGR")) {
                known.add(qmgrName(it.str("target")));
                for (Object c : it.list("components")) {
                    byComp.computeIfAbsent((String) c, k -> new LinkedHashSet<>()).add(qmgrName(it.str("target")));
                }
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Doc it : items) {
            String type = it.str("type");
            if (!type.equals("MQ") && !type.equals("MQ-CHANNEL")) {
                continue;
            }
            Map<String, Object> details = it.map("details");
            String named = String.valueOf(details.getOrDefault("qmgr", "")).toUpperCase(Locale.ROOT);
            String fromFile = String.valueOf(details.getOrDefault("qmgr_file", "")).toUpperCase(Locale.ROOT);
            List<Object> comps = it.list("components");
            if (!named.isEmpty()) {
                out.put(it.id(), named);
            } else if (!fromFile.isEmpty() && known.contains(fromFile)) {
                out.put(it.id(), fromFile);
            } else if (!comps.isEmpty()) {
                List<Set<String>> sets = new ArrayList<>();
                for (Object c : comps) {
                    sets.add(byComp.getOrDefault((String) c, Set.of()));
                }
                Set<String> all = new HashSet<>();
                sets.forEach(all::addAll);
                if (sets.stream().noneMatch(Set::isEmpty) && all.size() == 1) {
                    out.put(it.id(), sets.getFirst().iterator().next());
                }
            }
        }
        return out;
    }

    private static String endpoint(Doc it, boolean mixedSchemes) {
        String type = it.str("type");
        String target = it.str("target");
        if (type.equals("HTTP") || type.equals("SOAP")) {
            PyUrl parts = PyUrl.split(target);
            String path = TextUtil.rstrip(parts.path(), "/");
            if (path.isEmpty()) {
                path = "/";
            }
            String label = mixedSchemes ? parts.scheme().toLowerCase(Locale.ROOT) + "://" + parts.netloc().toLowerCase(Locale.ROOT) + path : path;
            return type.equals("SOAP") ? label + " (SOAP)" : label;
        }
        if (type.equals("MQ")) {
            return "queue " + target + " (" + it.str("direction") + ")";
        }
        if (type.equals("MQ-CHANNEL")) {
            return "channel " + target + " (" + it.str("direction") + ")";
        }
        if (type.equals("FILE")) {
            return target;
        }
        return null;
    }

    /** The rows, old id to new id, and old "TYPE target" label to new label. */
    public record Grouped(List<Doc> rows, Map<String, String> ids, Map<String, String> labels) {}

    /**
     * All the usages of one external service as one row (decision 177): the endpoints of one
     * host, the queues and channels of one queue manager, the files of one server or folder. The
     * row lists what was grouped in {@code endpoints}.
     */
    public static Grouped groupByService(List<Doc> items) {
        Map<String, String> qmOf = queueManagers(items);
        Map<String, List<Doc>> groups = new LinkedHashMap<>();
        Map<String, String[]> groupKeys = new LinkedHashMap<>();
        List<Doc> out = new ArrayList<>();
        for (Doc it : items) {
            String[] key = serviceKey(it, qmOf);
            if (key == null) {
                out.add(it);
            } else {
                String k = key[0] + "\u0000" + key[1];
                groups.computeIfAbsent(k, x -> new ArrayList<>()).add(it);
                groupKeys.put(k, key);
            }
        }
        Map<String, String> ids = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (var g : groups.entrySet()) {
            String family = groupKeys.get(g.getKey())[0];
            String skey = groupKeys.get(g.getKey())[1];
            List<Doc> members = g.getValue();
            if (members.size() == 1 && !members.getFirst().str("type").equals("MQ") && !members.getFirst().str("type").equals("MQ-CHANNEL")) {
                out.add(members.getFirst());
                continue;
            }
            members.sort(Comparator.<Doc>comparingInt(m -> m.str("type").equals("MQ-QMGR") ? 0 : 1)
                    .thenComparingLong(m -> -num(m, "usages"))
                    .thenComparing(m -> m.str("target"), PyJson::compareCodePoints)
                    .thenComparing(Doc::id, PyJson::compareCodePoints));
            Doc head = members.getFirst();
            Doc merged = head.copy();
            if (family.equals("MQ") && !head.str("type").equals("MQ-QMGR")) {
                merged.set("id", Fact.makeId("integration", "MQ-QMGR", skey));
                merged.set("type", "MQ-QMGR");
                merged.set("target", skey);
                merged.set("details", new LinkedHashMap<>());
            }
            boolean mixed = false;
            if (family.equals("HTTP")) {
                Set<String> schemes = new LinkedHashSet<>();
                members.forEach(m -> schemes.add(PyUrl.split(m.str("target")).scheme().toLowerCase(Locale.ROOT)));
                String scheme = schemes.contains("https") ? "https" : PyUrl.split(head.str("target")).scheme().toLowerCase(Locale.ROOT);
                merged.set("target", scheme + "://" + skey);
                merged.set("type", members.stream().allMatch(m -> m.str("type").equals("SOAP")) ? "SOAP" : "HTTP");
                mixed = schemes.size() > 1;
            } else if (family.equals("FILE")) {
                merged.set("target", skey);
            }
            TreeSet<String> endpoints = new TreeSet<>(PyJson::compareCodePoints);
            for (Doc m : members) {
                String e = endpoint(m, mixed);
                if (e != null && !e.isEmpty()) {
                    endpoints.add(e);
                }
            }
            merged.set("endpoints", new ArrayList<>(endpoints));
            for (Doc m : members.subList(1, members.size())) {
                merged.set("usages", num(merged, "usages") + num(m, "usages"));
                merged.set("source_files", union(merged.list("source_files"), m.list("source_files")));
                merged.set("producers", union(merged.list("producers"), m.list("producers")));
                merged.set("consumers", union(merged.list("consumers"), m.list("consumers")));
                TreeSet<String> comps = new TreeSet<>(PyJson::compareCodePoints);
                merged.<String>list("components").forEach(comps::add);
                m.<String>list("components").forEach(comps::add);
                merged.set("components", new ArrayList<>(comps));
                addEvidence(merged, m);
                if (!IntegrationText.truthy(merged.get("protocol"))) {
                    merged.set("protocol", m.get("protocol"));
                }
                if (!family.equals("MQ") || m.str("type").equals("MQ-QMGR")) { // a queue's details are not the row's
                    Map<String, Object> details = merged.map("details");
                    m.<Object>map("details").forEach(details::putIfAbsent);
                }
            }
            Set<String> directions = new LinkedHashSet<>();
            members.forEach(m -> directions.add(m.str("direction")));
            merged.set("direction", IntegrationText.mergeDirections(directions));
            TreeSet<String> groupedIds = new TreeSet<>(PyJson::compareCodePoints);
            members.forEach(m -> groupedIds.add(m.id()));
            groupedIds.remove(merged.id());
            merged.set("grouped_ids", new ArrayList<>(groupedIds));
            for (Doc m : members) {
                ids.put(m.id(), merged.id());
                labels.put(m.str("type") + " " + m.str("target"), merged.str("type") + " " + merged.str("target"));
            }
            out.add(merged);
        }
        for (Doc it : out) {
            IntegrationText.describeInto(it);
        }
        return new Grouped(out, ids, labels);
    }
}
