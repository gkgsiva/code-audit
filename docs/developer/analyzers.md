# Analyzers

Package: `com.legacyaudit.engine.analyzers`. An analyzer reads **one file** and returns what it
found as `Facts`. It never looks at other files, so its result can be cached per file; tying
facts across files together (a call to a class in another file, a queue written here and read
there) is the linker's job (Phase 5).

## The contract

```java
public interface Analyzer {
    String name();       // "java", "sql", ... also the key in the file cache and in run.json
    String version();    // bump it when the output changes: cached files are read again
    boolean supports(FileRecord file);  // by language, extension or path
    Facts analyze(FileRecord file, AnalyzerContext ctx) throws Exception;
}
```

- `AnalyzerContext` gives the config (`ctx.config()`), the absolute path, the decoded text
  (null for binary or too-large files) and the text rules.
- `Facts` holds:
  - `entities`: facts by entity name (`sql_statements`, `services`, `entry_points`,
    `integrations`, `web_services`, `libraries`, `tech_stack`, `findings`, `metrics`, ...).
    Add with `facts.add("sql_statements", doc)`.
  - `symbols`: what the file defines and others can refer to by name (a Java class, a COBOL
    program, a JCL job). `facts.symbol(kind, name, file, line)`.
  - `refs`: by-name references for the linker to resolve (`calls`, `imports`, `uses-table` ...).
    `facts.ref(kind, source, sourceLabel, target, targetKinds, file, line)`.
  - `coverage`: the lines read and the regions that could not be parsed.
  - `units` (methods, paragraphs for complexity), `windows` (duplication) and `hits` (text-rule
    matches), used by the metrics in Phase 5.
- `Analyzers.registered()` lists the analyzers **in the Python tool's order**; the order decides
  which analyzer's fact wins when two produce the same id. `Analyzers.run` calls each one that
  supports the file. A crash is caught: the file gets an `analyzer-error` finding and the scan
  goes on.

## The analyzers

Order and status. "Python" is the module on `office-main` it was ported from.

| # | Name | Class | Reads | Main facts | Phase |
| --- | --- | --- | --- | --- | --- |
| 1 | `build` | `BuildAnalyzer` | Maven, Gradle, Ant/Ivy, Makefiles | libraries, tech stack (Java version, build tool, Spring Boot) | 2, done |
| 2 | `deps` | `DepsAnalyzer` | jars/wars/ears, `package.json`, lock files, `packages.config` ... | libraries not declared in a build file | 2, done |
| 3 | `config` | `ConfigAnalyzer` | properties, XML, YAML, INI, JSON config | integrations (JDBC, hosts, URLs, queues, CFT partners), masked credentials | 2, done |
| 4 | `java` | `JavaAnalyzer` | `.java` | classes, methods, entry points, endpoints, JMS, SQL in strings (JDBC, JdbcTemplate, JPA, Spring Data), web service calls, Swing screens | 2, done |
| 5 | `web` | `WebAnalyzer` | Struts 1/2, `web.xml`, Tiles, JSF, JSP, HTML, Velocity, FreeMarker | screens, actions, forwards, servlets, filters | 2, done |
| 6 | `typescript` | | TypeScript, Angular, AngularJS, Node | | 4 |
| 7 | `dotnet` | | `.csproj`, `web.config`, ASMX/WCF | | 4 |
| 8 | `basic` | | VB6, VB.NET | | 4 |
| 9 | `csharp` | | C# | | 4 |
| 10 | `spring` | `SpringAnalyzer` | Spring XML, MyBatis/iBATIS/Hibernate mappings | beans, URL mappings, JMS/MQ wiring, remoting, SQL from mappings | 2, done |
| 11 | `sql` | `SqlAnalyzer` | `.sql`, PL/SQL packages, DDL | SQL statements, tables, procedures, database objects | 2, done |
| 12 | `shell` | | shell scripts, crontab | | 3 |
| 13 | `c_cpp` | | C, C++ | | 3 |
| 14 | `proc` | | Pro*C | | 3 |
| 15 | `cobol` | | COBOL, copybooks | | 3 |
| 16 | `jcl` | | JCL | | 3 |
| 17 | `autosys` | | Autosys JIL | | 3 |
| 18 | `mq` | | IBM MQ definitions | | 3 |
| 19 | `cft` | | CFT configuration | | 3 |
| 20 | `servers` | `ServersAnalyzer` | app server and web server files | which servers the app is built for or runs on | 2, done |
| 21 | `webservices` | | WSDL, OpenAPI, SOAP/REST definitions | | 4 |
| 22 | `metrics` | | every source file | units, duplication windows | 5 |
| 23 | `patterns` | | every text file | text-rule hits (risk and PostgreSQL rules) | 5 |

## Shared helpers

| Class | What it does |
| --- | --- |
| `SqlTools` | Finding SQL in strings (`looksLikeSql`, `looksLikeHql`), normalising and fingerprinting it, statement type, vendor features, tables (`parseTables`), splitting scripts into statements, and building the `sql_statements` fact (`sqlFact`) |
| `SqlParse` | The tables of one statement with JSqlParser, made to answer like the Python tool's sqlglot (dialect rules, `?` placeholders, `RETURNING`, `INSERT ALL`); caches the last 4096 statements |
| `DynSql` | Rebuilding SQL that code assembles at run time: string concatenation, `StringBuilder.append`, `String.format`/`sprintf`, ternaries and branch-only assignments. A `Built` value tracks the text, the always-present part, the run-time holes (`?`, value or structural) and alternative texts; `fill` copies that onto the statement fact |
| `Integrations` | Integration facts (JDBC URLs, hosts, queues), URL classification, masking of user names and passwords |
| `WebSvc` | Web service facts: services offered and calls to other systems (REST/SOAP) |
| `Xml` | XML read as the Python tool read it: no DTD download, namespaces dropped from tag names, a line number on every element |
| `TextUtil`, `PyUrl`, `PyError`, `PyJsonRead` | Python behaviour for strings, URLs, errors and JSON (see [audit-engine.md](audit-engine.md)) |
| `Coverage`, `Symbol`, `Ref` | Parts of `Facts` |

## tree-sitter (`analyzers.ts`)

Java (and in later phases C, C++, C# and TypeScript) is parsed with tree-sitter, the same
parser and grammar versions as the Python tool, through the `io.github.bonede` bindings, whose
jars contain the native libraries for Windows, Linux and macOS.

- `TsTree.parsers(TreeSitterJava::new)` gives one parser per thread (parsers are not
  thread-safe); `TsTree.parse(parser, text)` returns the root `TsNode`.
- `TsNode` is a plain Java copy of the tree with what the analyzers use: `type`, `named`,
  `children`, `namedChildren()`, `field(name)`, `text()`, `line()`, `parent`, `hasError`,
  `missing`. Byte offsets are UTF-8, as in Python, so text slices are identical.
- `TsNative` parses and copies the tree by calling the tree-sitter C functions directly (Java's
  foreign function API); one call through the binding costs about a microsecond and a file has
  thousands of nodes. The Windows library does not export those functions, so on Windows
  `TsTree` falls back to the binding (same tree, slower). `-Dlegacyaudit.treesitter.binding=true`
  forces the fallback, to test it on Linux.
- The JVM needs `--enable-native-access=ALL-UNNAMED` (set in the jar manifest, the Docker
  entry point and the test configuration).

## The Java analyzer (`JavaAnalyzer`)

A port of `java.py`, function by function. Per file:

1. Parse; files with syntax errors still give facts for the parts that parsed, and the broken
   regions go into the coverage.
2. For each class (nested ones too): annotations, supertypes, class-level facts (Spring
   stereotypes, JAX-RS/JAX-WS, servlets, EJBs, JMS listeners, Swing frames), then fields first
   (constants are used by methods above them), then methods.
3. In each method: entry points (`main`, REST endpoints, scheduled jobs, message listeners),
   string expressions are evaluated (`Expr`: literal text, concatenation, `StringBuilder`,
   `String.format`, constants, ternaries), and calls are matched to known APIs: JDBC
   (`prepareStatement`, `executeQuery` ...), Spring `JdbcTemplate`, JPA/Hibernate queries,
   Spring Data derived queries, JMS, REST clients (`RestTemplate`, `WebClient`, JAX-RS client),
   SOAP clients.
4. SQL found this way becomes `sql_statements` with `DynSql` details; imports and class
   references become refs for the linker.

## Adding or changing an analyzer

1. **Port, do not redesign.** Open the Python module on `office-main` and port it function by
   function, using the helpers above wherever Python behaviour matters (regular expressions,
   strip, sorting, JSON).
2. Create `XxxAnalyzer implements Analyzer` with the same `name()` and `version()` as Python,
   and add it to `Analyzers.registered()` at the Python position.
3. Give the class a Javadoc comment saying what files it reads and what facts it produces.
4. Record cases from Python (see [testing.md](testing.md)) and add sample expectations if a
   sample covers the language. Run task 8 until there are no differences.
5. When you change what an analyzer outputs, bump `version()`: the file cache then re-reads
   the files it supports.
6. Add a decision to `docs/decisions.md` for anything that intentionally differs from Python.
