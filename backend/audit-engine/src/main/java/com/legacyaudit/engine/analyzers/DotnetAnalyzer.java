package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.BuildAnalyzer.tech;
import static com.legacyaudit.engine.analyzers.Integrations.integration;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Microsoft .NET and Windows desktop files that are not code: project files, NuGet package
 * lists, configuration, window layouts and ASP.NET pages (decision 134).
 *
 * <ul>
 *   <li><b>projects</b> ({@code .csproj}, {@code .vbproj}, {@code Directory.Build.props}): the
 *       language (C# or VB.NET, with {@code LangVersion}), the runtime (.NET Framework 2.0 to
 *       4.8.1, .NET Core, .NET 5+, .NET Standard), the application kind, NuGet
 *       {@code PackageReference} libraries, {@code ..\packages\<id>.<version>} hint paths and
 *       project-to-project references;
 *   <li><b>NuGet lists</b>: {@code packages.config}, {@code Directory.Packages.props} and
 *       {@code packages.lock.json};
 *   <li><b>solutions</b> ({@code .sln}): the Visual Studio version;
 *   <li><b>configuration</b> ({@code app.config}, {@code web.config}, {@code *.exe.config},
 *       {@code appsettings*.json}): connection strings as database integrations (credentials
 *       masked, a password is a finding) and WCF client endpoints as SOAP integrations;
 *   <li><b>layouts</b>: WPF / WinUI {@code .xaml} windows, pages and user controls and JavaFX
 *       {@code .fxml} views as screens; the WPF {@code App.xaml} start-up window as an entry point;
 *   <li><b>ASP.NET pages</b>: Web Forms {@code .aspx} ({@code .ascx} controls and
 *       {@code .master} pages are partials) with their {@code SqlDataSource} SQL, Razor views and
 *       Blazor components as screens;
 *   <li><b>Windows Task Scheduler</b>: exported task definitions ({@code .xml}) and
 *       {@code schtasks /create} in batch and PowerShell scripts as batch jobs.
 * </ul>
 *
 * The code itself is read by the {@code csharp} and {@code basic} analyzers; the helpers for
 * connection strings and ADO.NET providers here are shared with them.
 */
public final class DotnetAnalyzer implements Analyzer {

    /** {@code Library.language} of NuGet packages. */
    public static final String DOTNET = "dotnet";

    // ---- connection strings -----------------------------------------------------------------

    private static final Pattern CONN_PAIR = Rx.of("(?:^|;)\\s*([A-Za-z][\\w ]*?)\\s*=\\s*(\\{[^}]*\\}|'[^']*'|\"[^\"]*\"|[^;]*)");
    private static final List<String> SERVER_KEYS = List.of("data source", "server", "address", "addr", "network address", "host", "dsn", "dbq");
    private static final List<String> DB_KEYS = List.of("initial catalog", "database", "dbname");
    private static final List<String> SECRET_KEYS = List.of("password", "pwd");
    private static final List<String> CONN_KEYS = List.of("provider", "driver", "user id", "uid", "integrated security", "trusted_connection",
            "initial catalog", "database", "password", "pwd", "port", "persist security info", "dsn", "dbq");

    private record Provider(Pattern rx, String product) {}

    /** OLE DB providers, ODBC drivers and ADO.NET providers, then the database product. */
    private static final List<Provider> PROVIDERS = List.of(
            new Provider(Rx.of("sqloledb|sqlncli|msoledbsql|sql server|sqlclient", Rx.I), "SQL Server"),
            new Provider(Rx.of("oraoledb|msdaora|oracle", Rx.I), "Oracle"),
            new Provider(Rx.of("jet\\.oledb|ace\\.oledb|access driver|\\.mdb\\b|\\.accdb\\b", Rx.I), "Microsoft Access"),
            new Provider(Rx.of("ibmdadb2|db2oledb|db2|iseries|as400|ibmda400", Rx.I), "DB2"),
            new Provider(Rx.of("mysql|mariadb", Rx.I), "MySQL"),
            new Provider(Rx.of("postgres|npgsql|pgoledb", Rx.I), "PostgreSQL"),
            new Provider(Rx.of("sqlite", Rx.I), "SQLite"),
            new Provider(Rx.of("sybase|ase", Rx.I), "Sybase"),
            new Provider(Rx.of("informix", Rx.I), "Informix"),
            new Provider(Rx.of("teradata", Rx.I), "Teradata"));
    private static final Map<String, String> SCHEMES = Map.of("SQL Server", "sqlserver", "Oracle", "oracle", "Microsoft Access", "access",
            "DB2", "db2", "MySQL", "mysql", "PostgreSQL", "postgresql", "SQLite", "sqlite", "Sybase", "sybase", "Informix", "informix",
            "Teradata", "teradata");

    /** ADO.NET provider namespaces (C# {@code using}, VB.NET {@code Imports}), then the database product. */
    public static final Map<String, String> DB_NAMESPACES = WebAnalyzer.ordered(
            "System.Data.SqlClient", "SQL Server",
            "Microsoft.Data.SqlClient", "SQL Server",
            "System.Data.OracleClient", "Oracle",
            "Oracle.ManagedDataAccess.Client", "Oracle",
            "Oracle.DataAccess.Client", "Oracle",
            "Npgsql", "PostgreSQL",
            "MySql.Data.MySqlClient", "MySQL",
            "MySqlConnector", "MySQL",
            "IBM.Data.DB2", "DB2",
            "IBM.Data.Db2", "DB2",
            "IBM.Data.DB2.iSeries", "DB2",
            "System.Data.SQLite", "SQLite",
            "Microsoft.Data.Sqlite", "SQLite",
            "Sybase.Data.AseClient", "Sybase",
            "Teradata.Client.Provider", "Teradata");

    /** A namespace, then what a C# or VB.NET file using it is built on. */
    public record NamespaceTech(String namespace, String tech, String category) {}

    public static final List<NamespaceTech> NAMESPACE_TECH = List.of(
            new NamespaceTech("System.Windows.Forms", "Windows Forms", "framework"),
            new NamespaceTech("System.Windows.Controls", "WPF", "framework"),
            new NamespaceTech("System.Windows.Markup", "WPF", "framework"),
            new NamespaceTech("Microsoft.UI.Xaml", "WinUI", "framework"),
            new NamespaceTech("System.Web.UI", "ASP.NET Web Forms", "framework"),
            new NamespaceTech("System.Web.Mvc", "ASP.NET MVC", "framework"),
            new NamespaceTech("System.Web.Http", "ASP.NET Web API", "framework"),
            new NamespaceTech("System.Web.Services", "ASP.NET Web Services (ASMX)", "framework"),
            new NamespaceTech("Microsoft.AspNetCore", "ASP.NET Core", "framework"),
            new NamespaceTech("System.ServiceModel", "WCF", "framework"),
            new NamespaceTech("CoreWCF", "CoreWCF", "framework"),
            new NamespaceTech("System.Runtime.Remoting", ".NET Remoting", "middleware"),
            new NamespaceTech("System.Data.Entity", "Entity Framework", "framework"),
            new NamespaceTech("Microsoft.EntityFrameworkCore", "Entity Framework Core", "framework"),
            new NamespaceTech("Dapper", "Dapper", "library"),
            new NamespaceTech("NHibernate", "NHibernate", "framework"),
            new NamespaceTech("System.Data.SqlClient", "ADO.NET", "library"),
            new NamespaceTech("Microsoft.Data.SqlClient", "ADO.NET", "library"),
            new NamespaceTech("System.Data.OleDb", "ADO.NET", "library"),
            new NamespaceTech("System.Data.Odbc", "ADO.NET", "library"),
            new NamespaceTech("Oracle.ManagedDataAccess", "ADO.NET", "library"),
            new NamespaceTech("Oracle.DataAccess", "ADO.NET", "library"),
            new NamespaceTech("System.Messaging", "MSMQ", "middleware"),
            new NamespaceTech("IBM.WMQ", "IBM MQ client", "middleware"),
            new NamespaceTech("IBM.XMS", "IBM MQ client", "middleware"),
            new NamespaceTech("System.ServiceProcess", "Windows Service", "framework"),
            new NamespaceTech("Quartz", "Quartz.NET", "scheduler"),
            new NamespaceTech("Hangfire", "Hangfire", "scheduler"),
            new NamespaceTech("log4net", "log4net", "library"),
            new NamespaceTech("NLog", "NLog", "library"),
            new NamespaceTech("Serilog", "Serilog", "library"),
            new NamespaceTech("CrystalDecisions", "Crystal Reports", "library"),
            new NamespaceTech("Microsoft.Reporting", "SQL Server Reporting Services (ReportViewer)", "library"),
            new NamespaceTech("Microsoft.Office.Interop", "Microsoft Office automation (Interop)", "library"),
            new NamespaceTech("System.DirectoryServices", "Active Directory (System.DirectoryServices)", "auth"));

    private static final Map<String, String> APP_KINDS = new java.util.HashMap<>();

    static {
        APP_KINDS.put("Microsoft.NET.Sdk.Web", "ASP.NET Core");
        APP_KINDS.put("Microsoft.NET.Sdk.WindowsDesktop", null); // Windows Forms or WPF, from the properties
        APP_KINDS.put("Microsoft.NET.Sdk.Worker", "Worker service");
        APP_KINDS.put("Microsoft.NET.Sdk.Razor", "ASP.NET Core");
        APP_KINDS.put("Microsoft.NET.Sdk.BlazorWebAssembly", "Blazor");
    }

    /** Old-style project type GUIDs ({@code ProjectTypeGuids}). */
    private static final Map<String, String> TYPE_GUIDS = Map.of(
            "349c5851-65df-11da-9384-00065b846f21", "ASP.NET Web Forms",
            "e3e379df-f4c6-4180-9b81-6769533abe47", "ASP.NET MVC",
            "e53f8fea-eae0-44a6-8774-ffd645390401", "ASP.NET MVC",
            "f85e285d-a4e0-4152-9332-ab1d724d3325", "ASP.NET MVC",
            "60dc8134-eba5-43b8-bcc9-bb4bc16c2548", "WPF",
            "3d9ad99f-2412-4246-b90b-4eaa41c64699", "WCF");
    private static final Map<String, String> REFERENCE_TECH = Map.of(
            "system.windows.forms", "Windows Forms",
            "presentationframework", "WPF",
            "system.web", "ASP.NET",
            "system.web.mvc", "ASP.NET MVC",
            "system.servicemodel", "WCF",
            "system.serviceprocess", "Windows Service",
            "system.messaging", "MSMQ",
            "system.data.entity", "Entity Framework");
    private static final Map<String, String> TECH_CATEGORY = Map.ofEntries(
            Map.entry("Windows Forms", "framework"), Map.entry("WPF", "framework"), Map.entry("ASP.NET", "framework"),
            Map.entry("ASP.NET Web Forms", "framework"), Map.entry("ASP.NET MVC", "framework"), Map.entry("ASP.NET Core", "framework"),
            Map.entry("Blazor", "framework"), Map.entry("WCF", "framework"), Map.entry("Worker service", "runtime"),
            Map.entry("Windows Service", "framework"), Map.entry("MSMQ", "middleware"), Map.entry("Entity Framework", "framework"));
    private static final Pattern HINT_PACKAGE = Rx.of("packages[\\\\/]+([A-Za-z_][\\w.-]*?)\\.(\\d+(?:\\.\\d+){1,3}(?:-[\\w.]+)?)[\\\\/]");

    /** (database product or null, target, has a password) of a connection string. */
    public record Connection(String product, String target, boolean secret) {}

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    private static String or(String a, String b) {
        return truthy(a) ? a : b;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    private static String productOf(String hint) {
        for (Provider p : PROVIDERS) {
            if (p.rx().matcher(hint).find()) {
                return p.product();
            }
        }
        return null;
    }

    /**
     * The database product, target and password flag of an ADO / ADO.NET / ODBC connection
     * string, or null when the text is not one. The target never carries credentials.
     */
    public static Connection connectionString(String text) {
        Map<String, String> pairs = new LinkedHashMap<>();
        Matcher m = CONN_PAIR.matcher(TextUtil.strip(text));
        while (m.find()) {
            pairs.put(lower(TextUtil.strip(m.group(1))), TextUtil.strip(TextUtil.strip(m.group(2)), "{}'\""));
        }
        String server = null;
        for (String k : SERVER_KEYS) {
            if (truthy(pairs.get(k))) {
                server = pairs.get(k);
                break;
            }
        }
        if (server == null || pairs.size() < 2) {
            return null;
        }
        if (CONN_KEYS.stream().noneMatch(pairs::containsKey)) {
            return null;
        }
        String hint = String.join(" ", pairs.getOrDefault("provider", ""), pairs.getOrDefault("driver", ""), pairs.getOrDefault("dbq", ""));
        String ds = lower(pairs.getOrDefault("data source", ""));
        if (ds.endsWith(".mdb") || ds.endsWith(".accdb")) {
            hint += " .mdb";
        }
        String product = productOf(hint);
        if (product == null && pairs.containsKey("host")) {
            product = "PostgreSQL"; // Npgsql: Host=...;Database=...
        }
        if (product == null && (pairs.containsKey("initial catalog") || pairs.containsKey("server")) && !truthy(pairs.get("dsn"))) {
            product = "SQL Server"; // the ADO.NET SqlConnection form
        }
        String database = null;
        for (String k : DB_KEYS) {
            if (truthy(pairs.get(k))) {
                database = pairs.get(k);
                break;
            }
        }
        String target;
        if (truthy(pairs.get("dsn")) && !truthy(pairs.get("data source"))) {
            target = "odbc://" + pairs.get("dsn");
        } else {
            String scheme = SCHEMES.getOrDefault(product == null ? "" : product, "db");
            String host = TextUtil.strip(TextUtil.beforeFirst(server, ","));
            if ("Microsoft Access".equals(product)) {
                host = TextUtil.afterLast(host.replace("\\", "/"), "/");
            }
            target = scheme + "://" + host + (database != null ? "/" + database : "");
        }
        boolean secret = SECRET_KEYS.stream().anyMatch(k -> truthy(pairs.get(k)));
        return new Connection(product, target, secret);
    }

    /**
     * A connection string found in code or configuration: a DB integration, the database as a
     * technology and, when it holds a password, a credential finding. True when it was one.
     */
    public static boolean addConnection(Facts facts, String text, String path, Integer line, String protocol, String provider) {
        Connection found = connectionString(text);
        if (found == null) {
            return false;
        }
        String product = found.product();
        String target = found.target();
        if (truthy(provider)) {
            String p = productOf(provider);
            product = p != null ? p : product;
            if (product != null && target.startsWith("db://")) {
                target = SCHEMES.getOrDefault(product, "db") + "://" + target.substring(5);
            }
        }
        facts.add("integrations", integration("DB", target, "outbound", path, line, null, protocol, null));
        if (product != null) {
            facts.add("tech_stack", tech(product, null, "database", path, line));
        }
        if (found.secret()) {
            facts.add("findings", Doc.of(Schemas.FINDING,
                    "id", Fact.makeId("finding", "connection-string-password", path, line),
                    "rule_id", "connection-string-password",
                    "severity", "high",
                    "category", "security",
                    "file", path,
                    "line", line,
                    "message", "Password written in a connection string to " + target + " (value masked)",
                    "remediation", "Use Windows authentication or read the password from a secret store (Secret Manager on GCP); rotate it.",
                    "evidence", new ArrayList<>(List.of(new Evidence(path, line, "Password=****")))));
        }
        return true;
    }

    private static final Pattern FW_VERSION = Rx.of("^v?(\\d)\\.(\\d+)(?:\\.(\\d+))?$");
    private static final Pattern FW_NET = Rx.of("^net(\\d)(\\d)(\\d)?$");
    private static final Pattern FW_CORE = Rx.of("^netcoreapp(\\d+\\.\\d+)");
    private static final Pattern FW_STANDARD = Rx.of("^netstandard(\\d+\\.\\d+)");
    private static final Pattern FW_MODERN = Rx.of("^net(\\d+\\.\\d+)");

    private static String joinGroups(Matcher m) {
        List<String> parts = new ArrayList<>();
        for (int i = 1; i <= m.groupCount(); i++) {
            if (truthy(m.group(i))) {
                parts.add(m.group(i));
            }
        }
        return String.join(".", parts);
    }

    /**
     * (runtime, version) of a target framework moniker or {@code TargetFrameworkVersion}:
     * {@code net48}/{@code v4.8} is .NET Framework 4.8, {@code netcoreapp3.1} .NET Core 3.1,
     * {@code net8.0-windows} .NET 8.0, {@code netstandard2.0} .NET Standard 2.0; null if unknown.
     */
    public static String[] frameworkVersion(String tfm) {
        String t = lower(TextUtil.strip(tfm));
        Matcher m = FW_VERSION.matcher(t);
        if (m.lookingAt()) {
            return new String[] {".NET Framework", joinGroups(m)};
        }
        m = FW_NET.matcher(t);
        if (m.lookingAt()) {
            return new String[] {".NET Framework", joinGroups(m)};
        }
        m = FW_CORE.matcher(t);
        if (m.lookingAt()) {
            return new String[] {".NET Core", m.group(1)};
        }
        m = FW_STANDARD.matcher(t);
        if (m.lookingAt()) {
            return new String[] {".NET Standard", m.group(1)};
        }
        m = FW_MODERN.matcher(t);
        if (m.lookingAt()) {
            return new String[] {".NET", m.group(1)};
        }
        return null;
    }

    private static Integer textLine(String text, String needle) {
        int i = text.indexOf(needle);
        return i >= 0 ? TextUtil.lineAt(text, i) : null;
    }

    // ---- the analyzer -----------------------------------------------------------------------

    private static final Pattern CONFIG_NAME = Rx.of("^(app|web)(\\.[\\w-]+)?\\.config$|\\.(exe|dll)\\.config$", Rx.I);
    private static final Pattern APPSETTINGS = Rx.of("^appsettings(\\.[\\w-]+)?\\.json$", Rx.I);
    private static final String TASK_NS = "schemas.microsoft.com/windows/2004/02/mit/task";
    private static final Pattern SCHTASKS = Rx.of("\\bschtasks(?:\\.exe)?\\s+/create\\b(.*)$", Rx.I | Rx.M);
    private static final Pattern SCHTASKS_OPT = Rx.of("/(\\w+)\\s+(\"[^\"]*\"|\\S+)");
    private static final Pattern ASP_INPUT = Rx.of(
            "<asp:(TextBox|DropDownList|CheckBox|CheckBoxList|RadioButton|RadioButtonList|ListBox|FileUpload|Calendar)\\b", Rx.I);
    private static final Pattern RAZOR_INPUT = Rx.of(
            "@Html\\.(TextBox|TextArea|DropDownList|CheckBox|RadioButton|Password|Editor|ListBox)(For)?\\s*\\(|<(input|select|textarea)\\b[^>]*\\basp-for\\s*=",
            Rx.I);
    private static final Pattern XAML_INPUT = Rx.of(
            "<(?:\\w+:)?(TextBox|PasswordBox|ComboBox|CheckBox|RadioButton|DatePicker|ListBox|Slider|RichTextBox|DataGrid|TextField|TextArea|"
                    + "ChoiceBox|DateTimePicker|NumberBox|TableView|ListView|Spinner)\\b");
    private static final Pattern ASP_SQL = Rx.of("\\b(Select|Insert|Update|Delete)Command\\s*=\\s*\"([^\"]+)\"", Rx.I);
    private static final Pattern ASP_NAV = Rx.of("\\b(?:NavigateUrl|PostBackUrl|href|action)\\s*=\\s*\"~?/?([^\"?#<%]+?\\.aspx)", Rx.I);
    private static final Pattern PAGE_DIRECTIVE = Rx.of("<%@\\s*(Page|Control|Master)\\b([^%]*)%>", Rx.I);
    private static final Pattern INHERITS = Rx.of("\\bInherits\\s*=\\s*\"([^\"]+)\"", Rx.I);
    private static final Pattern RAZOR_PAGE = Rx.of("^\\s*@page\\b", Rx.M);
    private static final Pattern GUID = Rx.of("[0-9a-fA-F-]{36}");
    private static final Pattern NUGET_VERSION = Rx.of("\\d+(?:\\.\\d+){0,3}(?:-[\\w.]+)?");
    private static final Pattern WINDOWS_SERVICES = Rx.of("Microsoft\\.Extensions\\.Hosting\\.WindowsServices");
    private static final Pattern STARTS_WITH_DIGIT = Rx.of("^\\d");
    private static final Pattern VS_VERSION = Rx.of("^VisualStudioVersion\\s*=\\s*(\\d+)(?:\\.(\\d+))?", Rx.M);
    private static final Pattern FORMAT_VERSION = Rx.of("Format Version (\\d+\\.\\d+)");
    private static final Pattern NET_TCP = Rx.of("net\\.tcp://([^/]+)", Rx.I);
    private static final Pattern LINE_COMMENT = Rx.of("^\\s*//.*$", Rx.M);

    @Override
    public String name() {
        return "dotnet";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public boolean supports(FileRecord file) {
        if (file.category == Category.BINARY || file.category == Category.TEST) {
            return false;
        }
        String low = lower(file.path);
        String name = TextUtil.afterLast(low, "/");
        return Set.of("msbuild", "xaml", "fxml", "aspx", "razor").contains(file.language)
                || name.equals("packages.config") || name.equals("packages.lock.json")
                || CONFIG_NAME.matcher(name).find() || APPSETTINGS.matcher(name).lookingAt()
                || (file.language.equals("xml") && !low.contains("/obj/"))
                || file.language.equals("batch") || file.language.equals("powershell");
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        facts.coverage.lines = TextUtil.count(text, '\n') + 1;
        String path = file.path;
        String name = lower(TextUtil.afterLast(path, "/"));
        String lang = file.language;
        if (lang.equals("batch") || lang.equals("powershell")) {
            schtasks(path, text, facts);
        } else if (name.endsWith(".csproj") || name.endsWith(".vbproj") || name.endsWith(".props") || name.endsWith(".targets")) {
            project(path, name, text, facts);
        } else if (name.endsWith(".sln")) {
            solution(path, text, facts);
        } else if (name.equals("packages.config")) {
            packagesConfig(path, text, facts);
        } else if (name.equals("packages.lock.json")) {
            nugetLock(path, text, facts);
        } else if (CONFIG_NAME.matcher(name).find()) {
            config(path, text, facts);
        } else if (APPSETTINGS.matcher(name).lookingAt()) {
            appsettings(path, text, facts);
        } else if (lang.equals("xaml") || lang.equals("fxml")) {
            layout(path, lang, text, facts);
        } else if (lang.equals("aspx")) {
            aspx(path, text, facts, ctx.config().sqlDialect());
        } else if (lang.equals("razor")) {
            razor(path, text, facts);
        } else if (lang.equals("xml") && Rx.head(text, 4000).contains(TASK_NS)) {
            taskXml(path, text, facts);
        }
        return facts;
    }

    private static Xml.El parse(String text, Facts facts) {
        try {
            return Xml.parse(text);
        } catch (Xml.ParseError err) {
            facts.coverage.gap(1, TextUtil.count(text, '\n') + 1, "XML parse error: " + err.getMessage());
            return null;
        }
    }

    private record Prop(String value, Integer line) {}

    private static void project(String path, String name, String text, Facts facts) {
        Xml.El root = parse(text, facts);
        if (root == null) {
            return;
        }
        Map<String, Prop> props = new LinkedHashMap<>();
        for (Xml.El el : root.iter()) {
            if (el.text != null && !TextUtil.strip(el.text).isEmpty() && el.children.isEmpty()) {
                props.putIfAbsent(el.tag, new Prop(TextUtil.strip(el.text), el.line));
            }
        }
        boolean isProject = name.endsWith("proj");
        String lang = name.endsWith(".vbproj") ? "VB.NET" : "C#";
        if (isProject) {
            String version = props.containsKey("LangVersion") ? props.get("LangVersion").value() : null;
            if (truthy(version) && !STARTS_WITH_DIGIT.matcher(version).lookingAt()) {
                version = null; // latest, preview, default
            }
            facts.add("tech_stack", tech(lang, version, "language", path, 1));
        }
        for (String key : List.of("TargetFramework", "TargetFrameworks", "TargetFrameworkVersion")) {
            Prop p = props.get(key);
            if (p == null) {
                continue;
            }
            for (String v : p.value().split(";", -1)) {
                if (TextUtil.strip(v).isEmpty()) {
                    continue;
                }
                String[] rv = frameworkVersion(v);
                if (rv != null) {
                    facts.add("tech_stack", tech(rv[0], rv[1], "runtime", path, p.line()));
                }
            }
        }
        Map<String, Integer> kinds = new LinkedHashMap<>();
        String sdk = root.get("Sdk", "");
        if (truthy(APP_KINDS.get(sdk))) {
            kinds.put(APP_KINDS.get(sdk), 1);
        }
        for (String[] kl : new String[][] {{"UseWindowsForms", "Windows Forms"}, {"UseWPF", "WPF"}, {"UseMaui", ".NET MAUI"}}) {
            Prop p = props.get(kl[0]);
            if (p != null && lower(p.value()).equals("true")) {
                kinds.put(kl[1], p.line());
            }
        }
        Prop guids = props.get("ProjectTypeGuids");
        if (guids != null) {
            Matcher g = GUID.matcher(guids.value());
            while (g.find()) {
                String label = TYPE_GUIDS.get(lower(g.group()));
                if (label != null) {
                    kinds.put(label, guids.line());
                }
            }
        }
        Prop myType = props.get("MyType"); // VB.NET application framework
        if (myType != null && lower(myType.value()).equals("windowsforms")) {
            kinds.put("Windows Forms", myType.line());
        }
        for (Xml.El el : root.iter("Reference")) {
            String inc = lower(TextUtil.strip(TextUtil.beforeFirst(el.get("Include", ""), ",")));
            if (REFERENCE_TECH.containsKey(inc)) {
                kinds.putIfAbsent(REFERENCE_TECH.get(inc), el.line);
            }
            String hint = null;
            for (Xml.El c : el.children) {
                if (c.tag.equals("HintPath") && truthy(c.text)) {
                    hint = c.text;
                    break;
                }
            }
            Matcher hm = HINT_PACKAGE.matcher(hint == null ? "" : hint);
            if (hm.find()) {
                nuget(facts, hm.group(1), hm.group(2), path, el.line, true, null);
            }
        }
        if (kinds.containsKey("ASP.NET Web Forms") || kinds.containsKey("ASP.NET MVC")) {
            kinds.remove("ASP.NET");
        }
        kinds.forEach((label, line) -> facts.add("tech_stack", tech(label, null, TECH_CATEGORY.getOrDefault(label, "framework"), path, line)));
        for (Xml.El el : root.iter("PackageReference", "PackageVersion")) {
            String pkg = or(el.get("Include"), el.get("Update"));
            if (!truthy(pkg)) {
                continue;
            }
            String ver = or(el.get("Version"), el.get("VersionOverride"));
            if (ver == null) {
                for (Xml.El c : el.children) {
                    if (c.tag.equals("Version") && truthy(c.text)) {
                        ver = c.text;
                        break;
                    }
                }
            }
            nuget(facts, pkg, nugetVersion(ver), path, el.line, true, null);
        }
        for (Xml.El el : root.iter("ProjectReference")) {
            String inc = el.get("Include", "").replace("\\", "/");
            if (!inc.isEmpty()) {
                String target = TextUtil.normpath(TextUtil.joinPath(TextUtil.dirname(path), inc));
                facts.ref("depends-on", "file:" + path, path, target, List.of("path"), path, el.line);
            }
        }
        if (!isProject) {
            return;
        }
        Prop out = props.getOrDefault("OutputType", new Prop(sdk.isEmpty() ? "" : "Library", null));
        Prop asm = props.get("AssemblyName");
        String assembly = asm != null ? asm.value() : TextUtil.beforeLast(TextUtil.afterLast(path, "/"), ".");
        String outType = lower(out.value());
        boolean ui = kinds.containsKey("Windows Forms") || kinds.containsKey("WPF") || kinds.containsKey("WinUI");
        String kind = null;
        if (kinds.containsKey("Windows Service") && !ui && (outType.equals("winexe") || outType.equals("exe"))) {
            kind = "windows-service";
        } else if (outType.equals("winexe") || (outType.equals("exe") && ui)) {
            kind = "desktop-app";
        } else if (kinds.containsKey("Worker service") || WINDOWS_SERVICES.matcher(text).find()) {
            kind = "windows-service";
        }
        if (kind != null) {
            facts.add("entry_points", Doc.of(Schemas.ENTRY_POINT,
                    "id", Fact.makeId("entry", path, assembly, kind),
                    "name", assembly,
                    "kind", kind,
                    "file", path,
                    "line", out.line(),
                    "evidence", new ArrayList<>(List.of(new Evidence(path, out.line(), "OutputType " + out.value())))));
        }
    }

    /** {@code 1.2.3}, {@code [1.2.3]}, {@code [1.2,2.0)}: the lowest version named. */
    private static String nugetVersion(String spec) {
        if (!truthy(spec)) {
            return null;
        }
        Matcher m = NUGET_VERSION.matcher(spec);
        return m.find() ? m.group() : null;
    }

    private static void nuget(Facts facts, String pkg, String version, String path, Integer line, boolean declared, String scope) {
        facts.add("libraries", BuildAnalyzer.library(null, pkg, version, "nuget", path, line, declared, scope, DOTNET, null));
    }

    private static void packagesConfig(String path, String text, Facts facts) {
        Xml.El root = parse(text, facts);
        if (root == null) {
            return;
        }
        for (Xml.El el : root.iter("package")) {
            if (truthy(el.get("id"))) {
                String scope = "true".equals(el.get("developmentDependency")) ? "development" : null;
                nuget(facts, el.get("id"), el.get("version"), path, el.line, true, scope);
            }
            String tfm = el.get("targetFramework");
            String[] rv = truthy(tfm) ? frameworkVersion(tfm) : null;
            if (rv != null) {
                facts.add("tech_stack", tech(rv[0], rv[1], "runtime", path, el.line));
            }
        }
    }

    private static void nugetLock(String path, String text, Facts facts) {
        JsonNode data;
        try {
            data = PyJsonRead.parse(text);
        } catch (IllegalArgumentException err) {
            facts.coverage.gap(1, facts.coverage.lines, "JSON parse error: " + err.getMessage());
            return;
        }
        JsonNode deps = data.isObject() ? data.get("dependencies") : null;
        if (deps == null || !deps.isObject()) {
            return;
        }
        Set<List<String>> seen = new HashSet<>();
        for (var target : deps.properties()) {
            JsonNode packages = target.getValue();
            if (!packages.isObject()) {
                continue;
            }
            for (var e : packages.properties()) {
                JsonNode info = e.getValue();
                if (!info.isObject()) {
                    continue;
                }
                JsonNode type = info.get("type");
                String typeText = type != null && type.isString() ? type.asString() : null;
                if ("Project".equals(typeText)) {
                    continue;
                }
                JsonNode ver = info.get("resolved");
                if (ver == null || !ver.isString() || !seen.add(List.of(e.getKey(), ver.asString()))) {
                    continue;
                }
                boolean direct = "Direct".equals(typeText);
                nuget(facts, e.getKey(), ver.asString(), path, null, direct, direct ? null : "transitive");
            }
        }
    }

    private static void solution(String path, String text, Facts facts) {
        Matcher m = VS_VERSION.matcher(text);
        Matcher fmt = FORMAT_VERSION.matcher(text);
        String version;
        if (m.find()) {
            version = m.group(1) + "." + (m.group(2) != null ? m.group(2) : "0");
        } else if (fmt.find()) {
            version = Map.of("8.00", "2003", "9.00", "2005", "10.00", "2008", "11.00", "2010").get(fmt.group(1));
        } else {
            version = null;
        }
        Integer line = textLine(text, "VisualStudioVersion");
        facts.add("tech_stack", tech("Visual Studio solution", version, "build", path, line != null ? line : 1));
    }

    private static void config(String path, String text, Facts facts) {
        Xml.El root = parse(text, facts);
        if (root == null) {
            return;
        }
        for (Xml.El el : root.iter("connectionStrings")) {
            for (Xml.El add : el.children) {
                String cs = add.get("connectionString");
                if (add.tag.equals("add") && truthy(cs)) {
                    addConnection(facts, cs, path, add.line, "ado.net", or(add.get("providerName"), ""));
                }
            }
        }
        for (Xml.El client : root.iter("client")) {
            for (Xml.El ep : client.children) {
                String address = or(ep.get("address"), "");
                String binding = or(ep.get("binding"), "");
                if (!ep.tag.equals("endpoint") || address.isEmpty()) {
                    continue;
                }
                Map<String, String> details = new LinkedHashMap<>();
                details.put("binding", binding);
                details.put("contract", or(ep.get("contract"), ""));
                if (lower(address).startsWith("net.tcp://")) {
                    Matcher host = NET_TCP.matcher(address);
                    if (host.lookingAt()) {
                        facts.add("integrations", integration("SOCKET", host.group(1), "outbound", path, ep.line, null, "net.tcp (WCF)", details));
                    }
                    continue;
                }
                String[] c = Integrations.classifyUrl(address, lower(binding).contains("http") && !binding.contains("web"));
                if (c != null) {
                    facts.add("integrations", integration(c[0], c[1], "outbound", path, ep.line, null, "WCF", details));
                }
            }
        }
    }

    private static void appsettings(String path, String text, Facts facts) {
        JsonNode data;
        try {
            data = PyJsonRead.parse(LINE_COMMENT.matcher(text).replaceAll(""));
        } catch (IllegalArgumentException err) {
            return;
        }
        JsonNode cs = data.isObject() ? data.get("ConnectionStrings") : null;
        if (cs != null && cs.isObject()) {
            for (var e : cs.properties()) {
                if (e.getValue().isString()) {
                    addConnection(facts, e.getValue().asString(), path, textLine(text, "\"" + e.getKey() + "\""), "ado.net", "");
                }
            }
        }
    }

    private static int count(Pattern p, String text) {
        int n = 0;
        Matcher m = p.matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** A WPF/WinUI or JavaFX layout: windows and pages are screens, user controls partials. */
    private static void layout(String path, String language, String text, Facts facts) {
        Xml.El root = parse(text, facts);
        if (root == null) {
            return;
        }
        String tag = root.tag;
        String cls = or(root.get("Class"), or(root.get("controller"), ""));
        String stem = TextUtil.beforeLast(TextUtil.afterLast(path, "/"), ".");
        String technology;
        boolean partial;
        if (language.equals("xaml")) {
            if (tag.equals("Application")) {
                String start = root.get("StartupUri");
                String name = or(cls, stem);
                facts.add("entry_points", Doc.of(Schemas.ENTRY_POINT,
                        "id", Fact.makeId("entry", path, name, "desktop-app"),
                        "name", name,
                        "kind", "desktop-app",
                        "file", path,
                        "line", root.line,
                        "evidence", new ArrayList<>(List.of(new Evidence(path, root.line, "WPF Application")))));
                if (truthy(start)) {
                    facts.ref("forwards", "file:" + path, name, TextUtil.normpath(TextUtil.joinPath(TextUtil.dirname(path), start)),
                            List.of("path"), path, root.line);
                }
                facts.add("tech_stack", tech("WPF", null, "framework", path, root.line));
                return;
            }
            if (!Set.of("Window", "Page", "UserControl", "NavigationWindow", "RibbonWindow").contains(tag) && !tag.endsWith("Window")) {
                return; // resource dictionaries, styles
            }
            technology = "wpf";
            partial = tag.equals("UserControl");
            facts.add("tech_stack", tech("WPF", null, "framework", path, root.line));
        } else {
            if (cls.isEmpty()) {
                return; // an included fragment without its own controller
            }
            technology = "javafx";
            partial = false;
            facts.add("tech_stack", tech("JavaFX", null, "framework", path, root.line));
        }
        // a JavaFX view is named after its file (Rates.fxml), its controller is a separate class
        String name = !cls.isEmpty() && language.equals("xaml") ? TextUtil.afterLast(cls, ".") : stem;
        String sid = Fact.makeId("screen", path);
        facts.add("screens", Doc.of(Schemas.SCREEN,
                "id", sid,
                "name", name,
                "technology", technology,
                "file", path,
                "fields_count", (long) count(XAML_INPUT, text),
                "partial", partial,
                "evidence", new ArrayList<>(List.of(new Evidence(path, root.line, TextUtil.strip("<" + tag + "> " + cls))))));
        facts.symbol("desktop-form", name, path, root.line).owner(sid);
        if (!cls.isEmpty() && language.equals("fxml")) {
            facts.symbol("desktop-form", cls, path, root.line).owner(sid);
            facts.ref("uses", "screen:" + sid, name, cls, List.of("java-class"), path, root.line);
        }
    }

    private static void aspx(String path, String text, Facts facts, String dialect) {
        String low = lower(path);
        Matcher d = PAGE_DIRECTIVE.matcher(text);
        boolean found = d.find();
        String kind = found ? lower(d.group(1)) : (low.endsWith(".ascx") ? "control" : "page");
        if (low.endsWith(".master")) {
            kind = "master";
        }
        String directive = found ? d.group(2) : "";
        Matcher inherits = INHERITS.matcher(directive);
        String stem = TextUtil.afterLast(path, "/");
        String sid = Fact.makeId("screen", path);
        TextUtil.LineIndex idx = new TextUtil.LineIndex(text);
        facts.add("screens", Doc.of(Schemas.SCREEN,
                "id", sid,
                "name", stem,
                "technology", "aspx",
                "file", path,
                "fields_count", (long) (count(ASP_INPUT, text) + WebAnalyzer.countFields(text)),
                "partial", !kind.equals("page"),
                "evidence", new ArrayList<>(List.of(new Evidence(path, found ? idx.line(d.start()) : 1, found ? Rx.head(d.group(), 120) : null)))));
        if (inherits.find()) {
            facts.symbol("aspx-page", TextUtil.afterLast(inherits.group(1), "."), path, null).owner(sid);
        }
        facts.add("tech_stack", tech("ASP.NET Web Forms", null, "framework", path, null));
        Matcher m = ASP_SQL.matcher(text);
        while (m.find()) {
            String sql = m.group(2).replace("&#39;", "'").replace("&quot;", "\"");
            if (SqlTools.looksLikeSql(sql)) {
                facts.add("sql_statements", SqlTools.sqlFact(path, idx.line(m.start()), sql, "aspx-datasource", dialect));
            }
        }
        Matcher nav = ASP_NAV.matcher(text);
        while (nav.find()) {
            String target = nav.group(1);
            String base = !nav.group().contains("~/") ? TextUtil.dirname(path) : "";
            facts.ref("links", "screen:" + sid, stem, !base.isEmpty() ? TextUtil.normpath(TextUtil.joinPath(base, target)) : target,
                    List.of("path"), path, idx.line(nav.start())).reportUnresolved(false);
        }
    }

    private static void razor(String path, String text, Facts facts) {
        String low = lower(path);
        String stem = TextUtil.afterLast(path, "/");
        boolean page = RAZOR_PAGE.matcher(text).find();
        boolean partial;
        String what;
        if (low.endsWith(".razor")) {
            partial = !page; // a Blazor component without a route
            what = "Blazor";
        } else {
            partial = stem.startsWith("_") || low.contains("/shared/") || low.contains("/components/");
            what = page ? "ASP.NET Core" : "ASP.NET MVC";
        }
        String sid = Fact.makeId("screen", path);
        facts.add("screens", Doc.of(Schemas.SCREEN,
                "id", sid,
                "name", stem,
                "technology", "razor",
                "file", path,
                "fields_count", (long) (count(RAZOR_INPUT, text) + WebAnalyzer.countFields(text)),
                "partial", partial,
                "evidence", new ArrayList<>(List.of(new Evidence(path, 1, null)))));
        facts.add("tech_stack", tech(what, null, "framework", path, null));
    }

    // ---- Windows Task Scheduler -------------------------------------------------------------

    private static final List<String> DAYS = List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");
    private static final Map<String, String> SCHEDULES = Map.of("daily", "daily", "weekly", "weekly", "monthly", "monthly", "once", "once",
            "minute", "every minute", "hourly", "hourly", "onstart", "at system start-up", "onlogon", "at log-on", "onidle", "when idle");
    private static final Set<String> REPEATING = Set.of("daily", "weekly", "monthly", "minute", "hourly");

    /** The text of the first element with that tag (this one or below) that has text, or null. */
    private static String firstText(Xml.El el, String tag) {
        for (Xml.El e : el.iter(tag)) {
            if (truthy(e.text)) {
                return e.text;
            }
        }
        return null;
    }

    private static void taskXml(String path, String text, Facts facts) {
        Xml.El root = parse(text, facts);
        if (root == null || !root.tag.equals("Task")) {
            return;
        }
        String uri = firstText(root, "URI");
        String desc = firstText(root, "Description");
        String raw = TextUtil.strip(or(uri, TextUtil.beforeLast(TextUtil.afterLast(path, "/"), ".")), "\\/");
        String name = TextUtil.afterLast(raw, "\\");
        List<String> when = new ArrayList<>();
        for (Xml.El trig : root.iter()) {
            if (!trig.tag.endsWith("Trigger")) {
                continue;
            }
            String start = or(firstText(trig, "StartBoundary"), "");
            String at = start.contains("T") ? Rx.head(TextUtil.afterFirst(start, "T"), 5) : "";
            switch (trig.tag) {
                case "CalendarTrigger" -> {
                    List<String> days = DAYS.stream().filter(day -> !trig.iter(day).isEmpty()).toList();
                    String interval = firstText(trig, "DaysInterval");
                    if (!trig.iter("ScheduleByWeek").isEmpty()) {
                        when.add(TextUtil.strip("weekly on " + (days.isEmpty() ? "?" : String.join(", ", days)) + " at " + at));
                    } else if (!trig.iter("ScheduleByMonth").isEmpty()) {
                        when.add("monthly at " + at);
                    } else {
                        String every = truthy(interval) && !interval.equals("1") ? "every " + interval + " days" : "daily";
                        when.add(TextUtil.strip(every + " at " + at));
                    }
                }
                case "TimeTrigger" -> when.add("once at " + start);
                case "BootTrigger" -> when.add("at system start-up");
                case "LogonTrigger" -> when.add("at log-on");
                default -> when.add(trig.tag.replace("Trigger", " trigger").toLowerCase(Locale.ROOT));
            }
            String rep = firstText(trig, "Interval");
            if (truthy(rep)) {
                when.set(when.size() - 1, when.getLast() + ", repeated every " + rep);
            }
        }
        String cmd = firstText(root, "Command");
        String args = firstText(root, "Arguments");
        List<String> parts = new ArrayList<>();
        for (String x : new String[] {cmd, args}) {
            if (truthy(x)) {
                parts.add(x);
            }
        }
        String command = parts.isEmpty() ? null : String.join(" ", parts);
        task(facts, path, root.line, name, when.isEmpty() ? null : String.join("; ", when), command, desc);
    }

    private static void schtasks(String path, String text, Facts facts) {
        TextUtil.LineIndex idx = new TextUtil.LineIndex(text);
        Matcher m = SCHTASKS.matcher(text);
        while (m.find()) {
            Map<String, String> opts = new LinkedHashMap<>();
            Matcher o = SCHTASKS_OPT.matcher(m.group(1));
            while (o.find()) {
                opts.put(lower(o.group(1)), TextUtil.strip(o.group(2), "\""));
            }
            String name = opts.get("tn");
            if (!truthy(name)) {
                continue;
            }
            String sc = lower(opts.getOrDefault("sc", ""));
            String when = SCHEDULES.getOrDefault(sc, sc);
            if (truthy(opts.get("mo")) && REPEATING.contains(sc)) {
                when = when + " (every " + opts.get("mo") + ")";
            }
            if (truthy(opts.get("d"))) {
                when += " on " + opts.get("d");
            }
            if (truthy(opts.get("st"))) {
                when += " at " + opts.get("st");
            }
            task(facts, path, idx.line(m.start()), TextUtil.afterLast(name, "\\"), when.isEmpty() ? null : when, opts.get("tr"), null);
        }
    }

    /** The program a command line runs, without its folder: {@code "C:\x\a b.exe" /q} gives {@code a b.exe}. */
    static String program(String command) {
        String exe;
        if (command.startsWith("\"")) {
            String[] parts = command.split("\"", -1);
            exe = parts[1];
        } else {
            List<String> words = TextUtil.words(command);
            if (words.isEmpty()) {
                throw new PyError("IndexError", "list index out of range");
            }
            exe = words.getFirst();
        }
        return TextUtil.afterLast(exe.replace("\\", "/"), "/");
    }

    private static void task(Facts facts, String path, Integer line, String name, String schedule, String command, String description) {
        String jid = Fact.makeId("batch_job", "windows-task", name);
        String snippet = command == null ? "" : Rx.head(command, 120);
        facts.add("batch_jobs", Doc.of(Schemas.BATCH_JOB,
                "id", jid,
                "name", name,
                "scheduler", "windows-task",
                "description", or(description, "Windows Task Scheduler task"),
                "schedule", schedule,
                "command", command,
                "programs", truthy(command) ? new ArrayList<>(List.of(program(command))) : new ArrayList<>(),
                "evidence", new ArrayList<>(List.of(new Evidence(path, line, snippet.isEmpty() ? null : snippet)))));
        facts.symbol("windows-task", name, path, line).owner(jid);
    }
}
