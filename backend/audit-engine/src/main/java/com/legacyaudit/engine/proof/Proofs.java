package com.legacyaudit.engine.proof;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.treesitter.TSLanguage;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterC;
import org.treesitter.TreeSitterCSharp;
import org.treesitter.TreeSitterCpp;
import org.treesitter.TreeSitterTypescript;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 0 proofs: do the chosen parsers read the sample applications as well as the Python
 * tool does? Each proof returns plain numbers; {@link #markdown} turns them into the report in
 * {@code docs/spring/phase-0-proofs.md}.
 */
public final class Proofs {

    /** EXEC SQL ... ; blocks in Pro*C, blanked before the C grammar reads the file (as proc.py does). */
    private static final Pattern EXEC_SQL =
            Pattern.compile("EXEC\\s+SQL\\b.*?;", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Wrappers JSqlParser does not read, rewritten first, as the Python SQL tools do for sqlglot. */
    private static final Pattern CURSOR =
            Pattern.compile("^DECLARE\\s+\\S+\\s+CURSOR\\s+(?:WITH\\s+HOLD\\s+)?FOR\\s+", Pattern.CASE_INSENSITIVE);

    private static final Pattern JDBC_CALL =
            Pattern.compile("^\\{\\s*(?:\\?\\s*=\\s*)?CALL\\s+(.*?)\\s*}$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern EXEC_PROC = Pattern.compile("^EXEC(?:UTE)?\\s+(?!SQL\\b)", Pattern.CASE_INSENSITIVE);

    private static final Pattern BARE_CALL = Pattern.compile("^CALL\\s+[\\w.$#]+$", Pattern.CASE_INSENSITIVE);

    /** A table name left open in dynamic SQL ("DELETE FROM ? WHERE ..."). */
    private static final Pattern TABLE_HOLE =
            Pattern.compile("\\b(FROM|INTO|UPDATE|JOIN|TABLE)\\s+\\?", Pattern.CASE_INSENSITIVE);

    /** Host variables a SELECT reads into (embedded SQL), already turned into ? by the Python scan. */
    private static final Pattern SELECT_INTO =
            Pattern.compile("\\bINTO\\s+\\?(?:\\s*,\\s*\\?)*\\s+(?=FROM\\b)", Pattern.CASE_INSENSITIVE);

    private static final String HOLE = "LEGACY_AUDIT_HOLE";

    private Proofs() {}

    public record JavaResult(int files, int parsed, int types, int methods, List<String> failures) {}

    public record TreeSitterResult(String language, int files, int clean, List<String> withErrors) {}

    public record SqlResult(
            int statements,
            int sqlStatements,
            int sqlglotParsed,
            int jsqlParsed,
            int sameTables,
            int hql,
            List<String> notParsed,
            List<String> differentTables) {}

    public record Report(JavaResult java, List<TreeSitterResult> treeSitter, SqlResult sql) {}

    public static Report run(Path samples) {
        return new Report(java(samples), treeSitter(samples), sql(corpus()));
    }

    /** Parses every .java file under {@code samples} with JavaParser. */
    public static JavaResult java(Path samples) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        int parsed = 0;
        int types = 0;
        int methods = 0;
        List<String> failures = new ArrayList<>();
        List<Path> files = files(samples, Set.of("java"));
        for (Path file : files) {
            ParseResult<CompilationUnit> result = parser.parse(read(file));
            if (result.isSuccessful() && result.getResult().isPresent()) {
                CompilationUnit unit = result.getResult().get();
                parsed++;
                types += unit.findAll(TypeDeclaration.class).size();
                methods += unit.findAll(MethodDeclaration.class).size();
            } else {
                failures.add(rel(samples, file) + ": " + result.getProblems().getFirst().getMessage());
            }
        }
        return new JavaResult(files.size(), parsed, types, methods, failures);
    }

    /** Parses the C, Pro*C, C++, C# and TypeScript files under {@code samples} with tree-sitter. */
    public static List<TreeSitterResult> treeSitter(Path samples) {
        Map<String, Grammar> grammars = new LinkedHashMap<>();
        grammars.put("C and Pro*C", new Grammar(Set.of("c", "h", "pc"), TreeSitterC::new));
        grammars.put("C++", new Grammar(Set.of("cpp", "cc", "cxx", "hpp", "hh"), TreeSitterCpp::new));
        grammars.put("C#", new Grammar(Set.of("cs"), TreeSitterCSharp::new));
        grammars.put("TypeScript", new Grammar(Set.of("ts"), TreeSitterTypescript::new));
        List<TreeSitterResult> out = new ArrayList<>();
        for (Map.Entry<String, Grammar> entry : grammars.entrySet()) {
            TSParser parser = new TSParser();
            parser.setLanguage(entry.getValue().language().get());
            List<Path> files = files(samples, entry.getValue().extensions());
            List<String> withErrors = new ArrayList<>();
            for (Path file : files) {
                String text = read(file);
                if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pc")) {
                    text = blank(text);
                }
                TSTree tree = parser.parseString(null, text);
                if (tree.getRootNode().hasError()) {
                    withErrors.add(rel(samples, file));
                }
            }
            out.add(new TreeSitterResult(
                    entry.getKey(), files.size(), files.size() - withErrors.size(), withErrors));
        }
        return out;
    }

    /** Reads the SQL statements the Python scan found (scripts/export_sql_corpus.py). */
    public static JsonNode corpus() {
        try (InputStream in = Proofs.class.getResourceAsStream("sql-corpus.json")) {
            if (in == null) {
                throw new IllegalStateException("sql-corpus.json is missing from the jar");
            }
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses each statement with JSqlParser and compares its tables with sqlglot's. */
    public static SqlResult sql(JsonNode corpus) {
        int sqlStatements = 0;
        int sqlglotParsed = 0;
        int jsqlParsed = 0;
        int sameTables = 0;
        int hql = 0;
        List<String> notParsed = new ArrayList<>();
        List<String> different = new ArrayList<>();
        for (JsonNode st : corpus) {
            String where = st.get("sample").asString() + "/" + st.get("file").asString() + ":"
                    + st.get("line").asInt();
            if ("hql".equals(st.get("dialect").asString())) {
                hql++;
                continue;
            }
            sqlStatements++;
            if (st.get("sqlglot_parsed").asBoolean()) {
                sqlglotParsed++;
            }
            Set<String> expected = new TreeSet<>();
            st.get("tables").forEach(t -> expected.add(t.asString()));
            Set<String> found;
            try {
                found = tables(st.get("text").asString());
            } catch (JSQLParserException | RuntimeException e) {
                notParsed.add(where + " (" + st.get("type").asString() + ")");
                continue;
            }
            jsqlParsed++;
            if (found.equals(expected)) {
                sameTables++;
            } else {
                different.add(where + ": sqlglot " + expected + ", JSqlParser " + found);
            }
        }
        return new SqlResult(corpus.size(), sqlStatements, sqlglotParsed, jsqlParsed, sameTables, hql,
                notParsed, different);
    }

    static Set<String> tables(String sql) throws JSQLParserException {
        String text = prepare(sql);
        if (CCJSqlParserUtil.parse(text) instanceof Execute) {
            return Set.of();
        }
        Set<String> out = new TreeSet<>();
        for (String name : TablesNamesFinder.findTables(text)) {
            String bare = name.replace("\"", "").toUpperCase(Locale.ROOT);
            bare = bare.substring(bare.lastIndexOf('.') + 1);
            if (!bare.equals(HOLE)) {
                out.add(bare);
            }
        }
        return out;
    }

    /**
     * Rewrites cursor declarations, JDBC call escapes, EXEC calls, SELECT ... INTO host variables and
     * table holes for JSqlParser.
     */
    static String prepare(String sql) {
        String text = sql.strip();
        if (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).strip();
        }
        text = CURSOR.matcher(text).replaceFirst("");
        Matcher call = JDBC_CALL.matcher(text);
        if (call.matches()) {
            text = "CALL " + call.group(1);
        }
        text = EXEC_PROC.matcher(text).replaceFirst("CALL ");
        if (BARE_CALL.matcher(text).matches()) {
            text = text + "()";
        }
        text = SELECT_INTO.matcher(text).replaceFirst("");
        return TABLE_HOLE.matcher(text).replaceAll("$1 " + HOLE);
    }

    /** Replaces each EXEC SQL block with spaces, keeping line breaks so line numbers stay right. */
    static String blank(String text) {
        Matcher m = EXEC_SQL.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(m.group().replaceAll("[^\\r\\n]", " ")));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static String markdown(Report r) {
        StringBuilder md = new StringBuilder();
        JavaResult j = r.java();
        md.append("## Java: JavaParser\n\n")
                .append("| Files | Parsed | Classes, interfaces, enums | Methods |\n| --- | --- | --- | --- |\n")
                .append("| %d | %d | %d | %d |%n".formatted(j.files(), j.parsed(), j.types(), j.methods()));
        list(md, "Not parsed", j.failures());
        md.append("\n## C, Pro*C, C++, C# and TypeScript: tree-sitter\n\n")
                .append("| Language | Files | Read without errors |\n| --- | --- | --- |\n");
        for (TreeSitterResult t : r.treeSitter()) {
            md.append("| %s | %d | %d |%n".formatted(t.language(), t.files(), t.clean()));
        }
        for (TreeSitterResult t : r.treeSitter()) {
            list(md, t.language() + " files with syntax errors", t.withErrors());
        }
        SqlResult s = r.sql();
        md.append("\n## SQL: JSqlParser compared with sqlglot\n\n")
                .append("| Statements | SQL (not HQL) | Parsed by sqlglot | Parsed by JSqlParser | Same tables |\n")
                .append("| --- | --- | --- | --- | --- |\n")
                .append("| %d | %d | %d | %d | %d |%n".formatted(s.statements(), s.sqlStatements(),
                        s.sqlglotParsed(), s.jsqlParsed(), s.sameTables()));
        md.append("\n%d HQL statements are not SQL and stay on the text rules, as in Python.%n".formatted(s.hql()));
        list(md, "Not parsed by JSqlParser", s.notParsed());
        list(md, "Different tables", s.differentTables());
        return md.toString();
    }

    private static void list(StringBuilder md, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        md.append("\n").append(title).append(":\n\n");
        items.forEach(i -> md.append("- ").append(i).append("\n"));
    }

    private record Grammar(Set<String> extensions, Supplier<TSLanguage> language) {}

    private static List<Path> files(Path root, Set<String> extensions) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("audit-output"))
                    .filter(p -> extensions.contains(extension(p)))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String extension(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            try {
                return Files.readString(file, StandardCharsets.ISO_8859_1);
            } catch (IOException e2) {
                throw new UncheckedIOException(e2);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String rel(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
