# Decisions

Decisions taken while planning. Newest last. These override `docs/plan.md` where they differ.

| # | Date | Decision | Reason |
| --- | --- | --- | --- |
| 1 | 2026-10-10 | Hybrid architecture: deterministic Python engine + Copilot agent + local GUI | Counts must be reproducible; AI only for interpretation |
| 2 | 2026-10-10 | No external LLM anywhere (no Vertex AI, no API calls). GUI hands off to Copilot Chat only | Client/organisation policy |
| 3 | 2026-10-10 | Autosys (JIL), IBM MQ and CFT are first-class analyzers, built in phase 2 | They appear in most of the team's applications |
| 4 | 2026-10-10 | Supplementary inputs (JIL, MQSC, CFT exports, DDL, crontab) arrive later and go in `audit-inputs/`; the tool must re-run any number of times and refresh reports | Team receives these exports after the code |
| 5 | 2026-10-10 | Branding is a config slot with a neutral default theme; corporate PPT template added later | Template exists but is not available yet |
| 6 | 2026-10-10 | Optional Git source: tool clones repo URLs itself into a cache; local folder stays the default | Analysts may not want to clone by hand |
| 7 | 2026-10-10 | Spring family (core, MVC, Web Flow, Boot 1–3, Batch, data access, Integration/JMS, Security) and Hibernate/MyBatis are in scope | Many legacy Java apps are older Spring |
| 8 | 2026-10-10 | Dependency management section: inventory + CycloneDX SBOM, OWASP Dependency-Check (OSV-Scanner optional), rule-based upgrade recommendations | Requested; security exposure is a migration driver |
| 9 | 2026-10-10 | Target versions: Java 25, Spring Boot 4.1 (Spring Framework 7, Jakarta EE 11), Angular 21 (TypeScript 5.9, Node 22/24), PostgreSQL — all configurable | Boot 4.0 OSS support ends 31 Dec 2026; Angular 21 requested |
| 10 | 2026-10-10 | The tool itself is built with Claude Code; the *audited* teams use GitHub Copilot | Build vs. use are separate |

## Open

- Typical estate size (LOC, number of apps) — needed for phase 6 performance targets. Default target until answered: 2M LOC scanned in under 15 minutes on a laptop.
- Angular 21 fixed, or "latest Angular LTS"? Default: 21, configurable.
- Further frameworks (EJB 2.x, JSF, GWT, Tapestry, .NET)? Default: EJB and JSF covered at the level in the plan; others not in scope.

## Phase 1 decisions

| # | Date | Decision | Reason |
| --- | --- | --- | --- |
| 11 | 2026-10-10 | Runtime dependencies: `typer` (CLI), `rich` (console progress, comes with typer), `pydantic` v2 (models, config validation), `PyYAML` (config and overrides), `charset-normalizer` (encoding detection), `pathspec` (include/exclude in `.gitignore` syntax) | Each replaces code we would otherwise write and test ourselves; all pure Python |
| 12 | 2026-10-10 | Archives (`.jar/.war/.ear/.zip/...`) are always inventoried (hashed, no LOC); other binaries (images, objects, executables, office files) are skipped unless `inventory.include_binaries: true` | Library detection in phase 2 needs the jars; other binaries only add noise |
| 13 | 2026-10-10 | Files in `audit-inputs/` are inventoried with `origin: input` and root `audit-inputs`, and kept out of source totals | Late inputs go through the same cache and analyzers as code, without inflating code counts |
| 14 | 2026-10-10 | `FileRecord.path` is relative to its source root; with several roots it is prefixed with the root name. `Evidence.line` is optional (none = whole file) | Stable, readable paths; a file-level fact has no line |
| 15 | 2026-10-10 | Default components: each folder with a build file (pom.xml, build.xml, build.gradle, Makefile, ...); when the root has no build file, each top-level folder too; loose root files go to a component named after the root. Manual `components` in `audit.yaml` win | Simple, predictable rule; overrides cover the rest |
| 16 | 2026-10-10 | `audit-overrides.yaml`: `files.include/exclude` are applied as walk filters (gitignore syntax, include = negation); `components.assign/rename/type` are applied after analysis. Unused overrides are warnings | Excluding before reading is cheaper and gives the same facts; warnings catch stale overrides |
| 17 | 2026-10-10 | File cache in `audit-output/cache/file-cache.sqlite`, keyed by (root, path); a hit needs the same size and mtime, or else the same SHA-256, and the same analyzer version key (which includes inventory settings) | Skips reading unchanged files at all; a touched-but-unchanged file is still a hit |
| 18 | 2026-10-10 | `audit-output/latest` is a symlink, or a copy of the run folder where symlinks are not allowed (Windows); `runs/LATEST` holds the newest id and `runs/HISTORY` the completion order | Run ids only sort to the second; the tool never depends on the symlink |
| 19 | 2026-10-10 | Git sources: `git init` + `fetch --depth 1 <ref>` + checkout into `<cache>/<host>/<owner>/<repo>@<ref>`; the token goes to git as an `http.extraHeader` through `GIT_CONFIG_*` environment variables, never in argv, the remote URL or `.git/config`; credentials embedded in a URL are stripped | Works for branches, tags and SHAs; no place for the token to leak |
| 20 | 2026-10-10 | LOC: comment markers inside string literals are not recognised; Java inside JSP scriptlets counts as JSP code; Markdown/text files count every non-blank line as comment (no code LOC) | Simple line counter; documented approximation, can only move lines from comment to code |
| 21 | 2026-10-10 | EBCDIC files are recognised by byte statistics and reported as `cp037` | Common mainframe export; other EBCDIC code pages can be configured later if needed |
| 22 | 2026-10-10 | Java parser choice deferred to phase 2 (phase 1 does no Java parsing) | Not needed yet |
| 23 | 2026-10-10 | CI runs lint, mypy and pytest on Ubuntu and Windows, Python 3.11 and 3.13 | The team uses Windows laptops |

## Phase 2 decisions

| # | Date | Decision | Reason |
| --- | --- | --- | --- |
| 24 | 2026-10-10 | Runtime dependency `sqlglot` (pinned to one major) parses SQL for type, tables, procedures and vendor features | Pure Python, many dialects (Oracle, DB2); one major keeps counts reproducible across installs |
| 25 | 2026-10-10 | Runtime dependencies `tree-sitter`, `tree-sitter-java`, `tree-sitter-c`, `tree-sitter-cpp` parse Java and C/C++ (decides the phase 1 open point #22: tree-sitter over `javalang`) | Error-tolerant (a broken file still yields facts plus an unparsed region), one library for Java and C/C++, Java 1.4–21 syntax, prebuilt wheels for Windows; `javalang` stops at Java 8 and fails on the first error |
| 26 | 2026-10-10 | COBOL, BMS, JCL, JIL, MQSC, CFT, shell, SQL scripts, JSP and config files are read with regular expressions over cleaned lines, not full grammars | These formats are line-oriented; the facts we count need statements, not a full parse tree |
| 27 | 2026-10-10 | Every Autosys/JCL/cron job except boxes and file watchers is also a service of kind `batch-job`; boxes are counted separately and never as jobs | A job is an entry point the migration must keep; a box only groups jobs |
| 28 | 2026-10-10 | A COBOL program that is only CALLed or LINKed by other programs is a sub-program, not a service; programs run by JCL or a scheduler, or owning a CICS transaction, stay. Edges to a pruned program point at its file | Counts the things a user or a scheduler can start, as the plan's service definition says |
| 29 | 2026-10-10 | A JSP/HTML page is a screen when navigation targets it (forward, render, submit, link) or a navigated tiles definition is the only one including it; a page that is only included is a partial; `.jspf` and tag files are always partials; an HTML page nothing targets is dropped, a JSP nothing targets stays a screen | Fragments and layouts would otherwise inflate screen counts; unreferenced JSPs are usually reached by URL |
| 30 | 2026-10-10 | Integrations are merged by (type, target). A definition (MQSC, CFT export) is not a usage (`usages: 0`, direction `defined`); MQ direction comes from producers and consumers; CFT export flows `*/IDF` fold into script usages with the same IDF and direction and give them the partner host and NSPART; transmission queues are not integrations; JMS queues are typed `MQ` | One row per real interface, with usages counted only where code uses it |
| 31 | 2026-10-10 | Libraries: internal Maven modules are not libraries; a jar known only by file name folds into the declared library with the same artifact and version; Maven versions are resolved through the parent chain after all poms are read | Avoids counting the same library twice and counting the application itself |
| 32 | 2026-10-10 | Tech-stack items are also derived from library coordinates; a versionless item folds into the single versioned item of the same name (kept separate when there are several versions) | Imports alone carry no version; guessing between versions would be wrong |
| 33 | 2026-10-10 | A service's `tables` and `external_calls` are what it reaches within 4 hops over calls/uses/invokes/runs/triggers/includes edges, from the service and its file | Bounded, deterministic reach; deeper chains are left to the agent with evidence |
| 34 | 2026-10-10 | A reference that cannot be resolved is an unresolved edge to `unresolved:<name>` plus an `unresolved-reference` finding (info); references through variables (`$BIN/x`, called by name in C) are linked if found but never reported | Missing code is a coverage gap to show, not an error; variable paths are too often outside the estate |
| 35 | 2026-10-10 | Component types are refined from what was found: web > online-mainframe > batch > cft; types from overrides or config are kept | Folder rules alone cannot tell a batch module from a web one |
| 36 | 2026-10-10 | The config analyzer leaves `audit-inputs/{autosys,mq,cft,crontab,ddl}` to their own analyzers, does not read hosts or queues from Spring `<beans>` XML (the Spring analyzer does), and treats message bundles, placeholders and values with spaces as non-credentials | Each fact is found once, by the analyzer that understands it; avoids false credential findings |
| 37 | 2026-10-10 | A Java string with no SQL call site counts as SQL only when it has upper-case `SELECT ... FROM` or SQL punctuation/keywords (`*`, `,`, `=`, `?`, `WHERE`, `JOIN`, ...) | UI captions such as "Select a customer from the list" are not SQL |
| 38 | 2026-10-10 | An analyzer that crashes on a file records an `analyzer-error` finding (low) and a coverage gap for that file; the scan continues | One odd file must not stop an audit |
| 39 | 2026-10-10 | `cobol.copybook_paths` is applied by the linker (candidates under those folders win, in order), not by the COBOL analyzer | Changing the setting re-links without re-analysing cached files |
