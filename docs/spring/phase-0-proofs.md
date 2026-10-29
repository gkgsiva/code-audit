# Phase 0: parser proofs

Before porting the analyzers, Phase 0 checks that the chosen Java libraries read the sample
applications as well as the Python tool does. The numbers below come from
`java -jar backend/audit-cli/target/legacy-audit.jar proofs --samples samples` (VS Code task
`audit: 6`), and the test `ProofsTest` runs the same checks in every build, on Linux and on
Windows.

## Verdict

| Library | Used for | Result | Go? |
| --- | --- | --- | --- |
| JavaParser 3.27 | Java | All 47 sample Java files parsed | Go |
| tree-sitter (tree-sitter-ng 0.26) | C, Pro*C, C++, C#, TypeScript | Native libraries load from the jars on Linux and Windows; every file reads the same as with the Python tree-sitter (one Pro*C file has a syntax error in both, after its `EXEC SQL` blocks are blanked) | Go |
| JSqlParser 5.3 | SQL statements | 67 of 67 SQL statements the Python scan found are parsed, with the same tables as sqlglot, once five wrappers are rewritten first (cursor declarations, `{? = CALL ...}`, `EXEC proc`, `SELECT ... INTO :host`, a table left open as `?`) | Go |

HQL statements (10) are not SQL; they stay on the text rules, as in Python. The SQL statements
come from `scripts/export_sql_corpus.py`, which runs the Python scan on every sample and saves what
sqlglot found; run it again when a sample changes.

The PowerPoint library (Apache POI) is no longer needed: the PowerPoint report is out of the
rewrite.

## Detail: Java: JavaParser

| Files | Parsed | Classes, interfaces, enums | Methods |
| --- | --- | --- | --- |
| 47 | 47 | 47 | 81 |

## Detail: C, Pro*C, C++, C# and TypeScript: tree-sitter

| Language | Files | Read without errors |
| --- | --- | --- |
| C and Pro*C | 7 | 6 |
| C++ | 0 | 0 |
| C# | 16 | 16 |
| TypeScript | 25 | 25 |

C and Pro*C files with syntax errors:

- c-proc-batch/settlement/src/settle_load.pc

## Detail: SQL: JSqlParser compared with sqlglot

| Statements | SQL (not HQL) | Parsed by sqlglot | Parsed by JSqlParser | Same tables |
| --- | --- | --- | --- | --- |
| 77 | 67 | 67 | 67 | 67 |

10 HQL statements are not SQL and stay on the text rules, as in Python.
