# Package guide

What each part of `legacy_audit/` is for, its main classes and functions, and where to make a
given kind of change. Signatures and every public name are in the
[module reference](reference/modules.md), generated from the code.

## Top-level modules

| Module | Role | Main names |
| --- | --- | --- |
| `cli.py` | The `legacy-audit` command (Typer). Thin wrappers only. | `app`, one function per command ([reference/cli.md](reference/cli.md)) |
| `__main__.py` | `python -m legacy_audit` | |
| `__init__.py` | Version (`__version__`, read by hatch for the package version) | |
| `config.py` | `audit.yaml` models, loading, validation, counting-rule defaults | `AuditConfig`, `LoadedConfig`, `load_config`, `parse_config`, `ConfigError`, `DEFAULT_COUNTING_RULES`, `INPUT_KINDS` |
| `scan.py` | The scan pipeline | `run_scan`, `ScanResult`, `SecretFilter`, `COPILOT_PATHS`, `INPUTS_ROOT` |
| `assess.py` | After-link stage: PostgreSQL grades, security, rules, effort, target mapping | `assess`, `Assessment`, `team_dir` |
| `overrides.py` | `audit-overrides.yaml` | `load_overrides`, `apply_component_overrides` |
| `target.py` | Migration target profile (`spring-boot-angular`, `wildfly-gke`, `none`) | `info`, `read`, `audit_only`, `resolve`, `fill`, `PROFILES`, `TARGET_STAGES` |
| `workspace.py` | Starter files, config edits that keep comments, GUI component pre-scan | `init_workspace`, `CONFIG_FILE` |
| `doctor.py` | Checks for Python, Node, Mermaid CLI, scanners, JDK, Git | `run_checks` |
| `parallel.py` | Process pool, cancel checks | `ordered_map`, `worker_count`, `check`, `Cancelled` |

## `model/` – the fact model

- `model/facts.py`: every fact is a pydantic model deriving from `Fact` (`id`, `evidence`).
  `ENTITIES` maps entity name (the file stem in `data/`) to model and fixes the export order;
  `SCHEMA_MODELS` lists what is exported as JSON Schema. `make_id(kind, *parts)` gives stable
  ids (a hash of the key fields), so the same thing has the same id in every run.
- `model/schemas.py`: writes `schemas/facts/` and `schemas/enrichment/`
  (`python -m legacy_audit.model.schemas`). `tests/test_schemas.py` fails when they drift.
- All fields of all entities: [reference/entities.md](reference/entities.md).

**Change here when** a new kind of fact or a new field is needed (then regenerate schemas,
update writers/readers that list columns, and the reports that show it).

## `inventory/` – files and components

| Module | Role |
| --- | --- |
| `walker.py` | Walk the roots with include/exclude globs, default excludes, build-output skipping (`Filters`, `walk`, `SkippedPath`) |
| `files.py` | Per-file record: hash, encoding, language, category, LOC (`analyze_file`, `INVENTORY_VERSION`, `skip_by_extension`) |
| `language.py` | Extension and file-name tables (`EXTENSIONS`, `FILENAMES`, `BINARY_EXTENSIONS`, ...), content sniffing, category (`detect_language`, `detect_category`) |
| `loc.py` | Code / comment / blank counts per comment syntax |
| `encoding.py` | UTF-8, EBCDIC, charset-normalizer |
| `cache.py` | `FileCache` (SQLite) |
| `worker.py` | One file's work in a pool process (`setup`, `process`, `run_analyzer`) |
| `components.py` | Component detection from build files and folder layout (`detect_components`, `PART_FOLDERS`, batch folder rules) |
| `grouping.py` | Grouping per deployable or technology (decision 176) |
| `inputs.py` | `audit-inputs/<kind>/` coverage (`input_coverage`, `coverage_line`) |

**Change here when** a file extension is unknown or misclassified (`language.py`), LOC is wrong
for a language (`loc.py`), or components are split or merged wrongly (`components.py`,
`grouping.py`). Bump `INVENTORY_VERSION` when the per-file record changes.

## `analyzers/` – one file in, facts out

Contract in `analyzers/base.py`: a class with `name` and `version` class attributes,
`supports(file: FileRecord) -> bool` and `analyze(file, ctx: AnalyzerContext) -> Facts`. Register
it in `analyzers/__init__.py` (the only registry). All analyzers and helper modules:
[reference/analyzers.md](reference/analyzers.md).

Largest ones: `java.py` (tree-sitter-java; classes, endpoints, SQL in strings, integrations),
`typescript.py` (Angular, AngularJS, Node), `basic.py` (VB6, VB.NET), `csharp.py` (C#/.NET),
`dotnet.py` (.NET project and config files), `spring.py` (Spring XML), `web.py` (Struts,
web.xml, JSP/JSF), `c_cpp.py` + `c_sql.py` (C/C++ and their SQL), `cobol.py` + `cobol_sql.py`,
`shell.py`, `websvc.py` (REST/SOAP services), `servers.py` (application server descriptors).

**Change here when** something in the code is not found or is found wrongly. Bump the
analyzer's `version` so cached results are recomputed.

## `linker/` – across files

| Module | Role |
| --- | --- |
| `link.py` | `link` (class `_Linker`): merge, resolve refs into `CodeEdge`s, cross-link facts, components; `classify_components` (types and roles) |
| `aggregate.py` | Merging duplicates (`merge_integrations`, `merge_libraries`, `merge_batch_jobs`, `merge_db_objects`, `merge_screens`, `merge_tech`, `dedupe`), `group_by_service` |
| `maven.py` | Versions inherited through Maven parents (`resolve_versions`) |
| `websvc.py` | One row per web service (`build_web_services`) |

**Change here when** a relation between files is missing (an edge, a screen not reached, a
service not linked to its tables) or duplicates appear in a list. The linker is never cached, so
no version bump is needed.

## `metrics/`

`compute.py` (`compute`, `MetricsResult`: per-component metrics, bands, fan-in/out, duplication,
dead code), `units.py` (code units and cyclomatic complexity per language), `tokens.py`
(comment stripping and token windows), `effort.py` (`estimate`).

## `rules/`

`packs.py` loads YAML packs (`load_pack`, `text_rules`, `rules_key`), `engine.py` evaluates them
(`evaluate`, `apply_config`), `pg.py` grades SQL for PostgreSQL (`grade_all`). The rule data
itself is in `rules/*.yaml` at the repository root (packaged as `legacy_audit/_rules`); syntax in
[../rules.md](../rules.md).

## `security/`

`vulns.py` (run or import Dependency-Check / OSV-Scanner and merge their results per library),
`eol.py`, `upgrades.py`, `versions.py` (Maven-like version ordering), `jdk.py`
(`jdeps`/`jdeprscan`), `sbom.py` (CycloneDX 1.5).

## `store/`

`writer.py` (`write_facts`, `write_json`, `read_facts`, `drop_duplicate_ids`, `FactSet`),
`runs.py` (run ids, `latest`, `resolve_run`, `list_runs`), `counts.py` (`counts`: **the** headline
counts; reports, summary and sample tests all use it), `summary.py` (`build_summary` for
`audit-summary.json`), `diff.py` (`diff_runs`), `lock.py` (`ScanLock`).

## `source/`

`resolve.py` (`resolve_sources`, `SourceRoot`), `git.py` (shallow clone into a cache,
`purge_cache`), `base.py` (`SourceError`).

## `enrich/` – checking the AI's words

`stages.py` (`STAGES`, `BY_NAME`, `Stage`), `models.py` (one pydantic model per stage file;
`StageFile` base with `meta`), `validate.py` (`validate_run`), `status.py` (staleness,
`active_stages`, `carry_over`, `compute_status`, `sync_from_latest`).

## `ai/` – `legacy-audit enrich`

`providers.py` (`settings`, `Settings`, `Client` for openai / azure-openai / anthropic / ollama,
`extract_json`, `AiError`), `context.py` (`build`: the prompt for one stage with the fact rows
and source excerpts it needs; `repair_prompt`), `runner.py` (`enrich`, `check`, `pick_stages`).
Only the standard library is used for HTTP; the key comes from an environment variable.

## `copilot/` – files installed into audited workspaces (product feature)

`install.py` (`install`, `assets`, `LAYOUTS`) writes `agent.md` (the `legacy-auditor` agent),
`copilot-instructions.md`, `prompts/audit-*.prompt.md`, the enrichment schemas and
`.vscode/mcp.json` into the audited folder. Every front-matter value must be quoted
(decision 173). These are not instructions for developing this repository.

## `mcp/`

`server.py` (`Server`, `TOOLS`: JSON-RPC over stdio), `facts.py` (`Facts`: `query_facts`,
`get_component`, `get_sql`, `read_evidence`, `coverage`; read-only SQLite access).

## `report/`

| Module | Role |
| --- | --- |
| `data.py` | `RunData.load(run_dir)`: what every report reads |
| `tech.py` | Technical site (`build_tech_report`, `_Pages`, `NAV`, `CATALOGUE_PAGES`) |
| `catalogues.py` | Catalogue columns and rows per entity (`build`, `Catalogue`) |
| `functional.py` | Functional document (`build_functional_report`, `_FnPages`) |
| `flows.py` | Screen and batch flows from facts |
| `visuals.py`, `diagrams.py` | Shared graphics; Mermaid sources |
| `deck_builder.py` | `build_deck` (slides as data, `deck.json`), `load_enrichment`, `SECTIONS`, `PALETTE` |
| `mgmt.py` | HTML deck (`build_mgmt_report`) |
| `pptx.py`, `pptx_build.py`, `pptx_templates.py` | PowerPoint on the corporate templates |
| `summary.py` | Summary report and its PowerPoint (`build_summary_report`, `_Summary`, `to_deck`) |
| `pdf.py` | PDFs (`build_pdfs`, `find_browser`, `Printer`, `join_report`) |
| `batches.py`, `servers.py`, `comments.py` | Batch list, server table, source header comments |
| `tech_html/`, `summary_html/`, `mgmt_html/` | Jinja2 templates |
| `assets/`, `mgmt_assets/` | JS/CSS; `assets/vendor/` holds Chart.js and Mermaid (vendored, offline) |

**Change here when** something is shown wrongly but the facts in `data/*.json` are right.
If the facts are wrong, the fix belongs in an analyzer, the linker or `store/counts.py`.

## `gui/`

`app.py` (`create_app`, `Workspace`, request body models), `jobs.py` (`Jobs`, `Job`, `Busy`),
`static/` (`index.html`, `app.js`, `gui.css`). Routes: [reference/gui-api.md](reference/gui-api.md).

## `templates/`

`audit.yaml` and `audit-overrides.yaml` written by `init` (the `{name}` placeholder is filled
in). Not to be confused with the repository's `templates/` folder (corporate `.potx` files).

## Outside the package

| Path | What |
| --- | --- |
| `rules/` | Rule packs and data tables (packaged as `legacy_audit/_rules`) |
| `templates/` | Corporate PowerPoint templates (packaged as `legacy_audit/_pptx_templates`) |
| `schemas/` | JSON Schemas generated from the models |
| `samples/` | Small synthetic legacy apps with `expected.yaml` counts (test fixtures, byte-for-byte: see `.gitattributes`) |
| `tests/` | pytest suite; see [testing.md](testing.md) |
| `scripts/` | `show_*.py` demos behind the VS Code tasks, `perf.py`, `demo_enrichment.py`, `gen_dev_reference.py` |
| `docs/` | Specification, decisions, progress, user guides, this developer guide |
| `.vscode/` | Tasks, debug configurations, recommended extensions |
| `audit.example.yaml` | Every config section with comments |
