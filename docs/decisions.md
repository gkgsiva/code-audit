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
