package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL scripts, PL/SQL packages and DDL ({@code .sql}, {@code .pks}/{@code .pkb}..., DDL exports
 * in {@code audit-inputs/ddl}). DML becomes SQL statements; tables, views, sequences and PL/SQL
 * units become database objects. DML inside PL/SQL bodies is extracted too (source plsql).
 */
public final class SqlAnalyzer implements Analyzer {

    private static final Pattern PLSQL_UNIT = Rx.of(
            "^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:EDITIONABLE\\s+|NONEDITIONABLE\\s+)?"
            + "(PACKAGE\\s+BODY|PACKAGE|PROCEDURE|FUNCTION|TRIGGER|TYPE\\s+BODY|TYPE)\\s+"
            + "(\"?[\\w$#]+\"?(?:\\.\"?[\\w$#]+\"?)?)", Rx.I);
    private static final Pattern DDL_OBJECT = Rx.of(
            "^\\s*CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:GLOBAL\\s+TEMPORARY\\s+|UNIQUE\\s+|BITMAP\\s+|"
            + "FORCE\\s+|NOFORCE\\s+|MATERIALIZED\\s+|PUBLIC\\s+)*"
            + "(TABLE|VIEW|INDEX|SEQUENCE|SYNONYM)\\s+(\"?[\\w$#]+\"?(?:\\.\"?[\\w$#]+\"?)?)", Rx.I);
    private static final Pattern MEMBER = Rx.of("\\b(PROCEDURE|FUNCTION)\\s+([\\w$#]+)", Rx.I);
    private static final Pattern DML = Rx.of("\\b(SELECT|INSERT|UPDATE|DELETE|MERGE)\\b", Rx.I);
    private static final Set<String> STATEMENT_STARTERS = Set.of(";", "BEGIN", "THEN", "ELSE", "LOOP", "(", "IS", "AS", "DECLARE", "", "IN");
    private static final Pattern BLANKABLE = Rx.of("'(?:[^']|'')*'|--[^\\n]*|/\\*[\\s\\S]*?\\*/");
    private static final Pattern NOT_NEWLINE = Rx.of("[^\\n]");
    private static final Pattern PREV_TOKEN = Rx.of("([\\w]+|[;(])$");
    private static final Pattern FROM = Rx.of("\\bFROM\\b", Rx.I);
    private static final Pattern IS_AS = Rx.of("\\b(IS|AS)\\b", Rx.I);
    private static final Set<String> SKIPPED = Set.of("DDL", "GRANT", "REVOKE", "COMMENT", "COMMIT", "ROLLBACK", "SET", "UNKNOWN");

    @Override
    public String name() {
        return "sql";
    }

    @Override
    public String version() {
        return "1";
    }

    /** Same length as the text with string literals and comments replaced by spaces. */
    public static String blankStringsAndComments(String text) {
        return TextUtil.sub(BLANKABLE, text, m -> NOT_NEWLINE.matcher(m.group()).replaceAll(" "));
    }

    /** A DML statement inside a PL/SQL block and its offset. */
    public record Embedded(int offset, String text) {}

    /** DML statements inside a PL/SQL block. */
    public static List<Embedded> embeddedDml(String body) {
        String clean = blankStringsAndComments(body);
        List<Embedded> out = new ArrayList<>();
        int covered = -1;
        Matcher m = DML.matcher(clean);
        while (m.find()) {
            if (m.start() < covered) {
                continue;
            }
            String before = TextUtil.rstrip(clean.substring(0, m.start()));
            Matcher prev = PREV_TOKEN.matcher(before);
            String prevTok = prev.find() ? prev.group(1).toUpperCase(Locale.ROOT) : "";
            if (!STATEMENT_STARTERS.contains(prevTok)) {
                continue;
            }
            int depth = 0;
            int end = clean.length();
            for (int i = m.end(); i < clean.length(); i++) {
                char ch = clean.charAt(i);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    if (depth == 0) { // closes the enclosing cursor/sub-query parenthesis
                        end = i;
                        break;
                    }
                    depth--;
                } else if (ch == ';' && depth == 0) {
                    end = i;
                    break;
                }
            }
            String stmt = TextUtil.strip(body.substring(m.start(), end));
            if (prevTok.equals("(") && stmt.toUpperCase(Locale.ROOT).startsWith("SELECT") && !FROM.matcher(stmt).find()) {
                continue;
            }
            out.add(new Embedded(m.start(), stmt));
            covered = end;
        }
        return out;
    }

    @Override
    public boolean supports(FileRecord file) {
        return "sql".equals(file.language) || "plsql".equals(file.language);
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        facts.coverage.lines = TextUtil.lineCount(text);
        String dialect = ctx.config().sqlDialect();
        String path = file.path;
        for (SqlTools.Statement s : SqlTools.splitStatements(text)) {
            int start = s.line();
            String stmt = s.text();
            Matcher unit = PLSQL_UNIT.matcher(stmt);
            if (unit.lookingAt()) {
                plsqlUnit(path, start, stmt, unit, facts, dialect);
                continue;
            }
            Matcher ddl = DDL_OBJECT.matcher(stmt);
            if (ddl.lookingAt()) {
                dbObject(facts, ddl.group(2).replace("\"", "").toUpperCase(Locale.ROOT), ddl.group(1).toLowerCase(Locale.ROOT), path, start,
                        List.of());
                continue;
            }
            String stype = SqlTools.statementType(stmt);
            if (SKIPPED.contains(stype)) {
                continue;
            }
            if (stype.equals("BEGIN") || stype.equals("DECLARE")) {
                for (Embedded e : embeddedDml(stmt)) {
                    int line = start + TextUtil.count(stmt.substring(0, e.offset()), '\n');
                    facts.add("sql_statements", SqlTools.sqlFact(path, line, e.text(), "plsql", dialect));
                }
                continue;
            }
            Doc fact = SqlTools.sqlFact(path, start, stmt, "sql-file", dialect);
            if (!fact.bool("parsed")) {
                facts.coverage.gap(start, start + TextUtil.count(stmt, '\n'), "SQL not parsed by sqlglot");
            }
            facts.add("sql_statements", fact);
        }
        return facts;
    }

    private static String firstWord(String s) {
        return TextUtil.words(s).getFirst();
    }

    private void dbObject(Facts facts, String name, String otype, String path, int line, List<String> members) {
        String oid = Fact.makeId("db_object", firstWord(otype), name);
        facts.add("db_objects", Doc.of(Schemas.DB_OBJECT,
                "id", oid,
                "name", name,
                "object_type", otype,
                "defined_in", path,
                "members", new ArrayList<>(members),
                "evidence", new ArrayList<>(List.of(new Evidence(path, line, null)))));
        facts.symbol("db-" + firstWord(otype), name, path, line).owner(oid);
    }

    private void plsqlUnit(String path, int start, String stmt, Matcher unit, Facts facts, String dialect) {
        String kind = firstWord(unit.group(1).toUpperCase(Locale.ROOT)).toLowerCase(Locale.ROOT);
        String name = unit.group(2).replace("\"", "").toUpperCase(Locale.ROOT);
        List<String> members = new ArrayList<>();
        if (kind.equals("package")) {
            TreeSet<String> set = new TreeSet<>();
            Matcher m = MEMBER.matcher(blankStringsAndComments(stmt));
            while (m.find()) {
                set.add(m.group(2).toUpperCase(Locale.ROOT));
            }
            members.addAll(set);
        }
        dbObject(facts, name, kind, path, start, members);
        for (String member : members) {
            facts.symbol("db-procedure", name + "." + member, path, start).owner(Fact.makeId("db_object", kind, name));
        }
        Matcher bs = IS_AS.matcher(stmt);
        boolean found = bs.find();
        String body = found ? stmt.substring(bs.end()) : stmt;
        int offset = found ? bs.end() : 0;
        for (Embedded e : embeddedDml(body)) {
            int line = start + TextUtil.count(stmt.substring(0, offset + e.offset()), '\n');
            facts.add("sql_statements", SqlTools.sqlFact(path, line, e.text(), "plsql", dialect));
        }
    }
}
