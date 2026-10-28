# legacy-audit: instructions for developers and coding agents

This repository builds **legacy-audit**, a tool a cloud-migration team uses to audit legacy
applications (Java/Struts/Spring, Java Swing, C/C++, Pro*C, COBOL/CICS, JCL, shell, Autosys,
IBM MQ, CFT, Angular/AngularJS/Node.js, VB6, VB.NET, C#/.NET) before migrating them, by default
to Java 25, Spring Boot 4.x, Angular 21, PostgreSQL and Google Cloud (other targets:
WildFly on GKE, or audit only).

These instructions are about **developing this tool**. The files in `legacy_audit/copilot/`
(the `legacy-auditor` agent, `/audit-*` prompts, their `copilot-instructions.md`) are a product
feature that `legacy-audit init` installs into the audited workspaces; they are not
instructions for this repository. Edit them only when the task is about that feature.

## Read before changing code

- `docs/developer/README.md`: the developer guide. Start with
  `docs/developer/architecture.md` (pipeline and data flow) and `docs/developer/packages.md`
  (where things are). Recipes for common changes: `docs/developer/extending.md`.
- `docs/developer/reference/`: generated from the code: modules and signatures, CLI, every
  `audit.yaml` key, every fact entity and SQLite table, analyzers, enrichment stages, GUI
  routes, VS Code tasks. Use it to find the right module quickly; read the code itself before
  changing it.
- `docs/decisions.md`: numbered decisions (they win over `docs/plan.md`). Search it for the
  feature you are changing; code comments cite decisions by number ("decision 177").
- `docs/progress.md`: latest state, known gaps and next steps.

## What the tool is

- A deterministic **Python 3.11+ scan engine** (`legacy_audit/`): walks a codebase, runs one
  analyzer plugin per file (`legacy_audit/analyzers/`), links facts across files
  (`legacy_audit/linker/`), computes metrics, rules, PostgreSQL grades, dependency security and
  effort, and writes facts as JSON, CSV, SQLite and a CycloneDX SBOM.
- **AI enrichment**, always optional and separate from the scan: written by GitHub Copilot in
  VS Code (the installed agent) or by `legacy-audit enrich` with a model set in
  `audit.yaml > ai` (OpenAI-compatible, Azure OpenAI, Anthropic, Ollama), and checked by the
  same validator (`legacy_audit/enrich/validate.py`).
- **Reports** (`legacy_audit/report/`): technical HTML site, functional document, management
  HTML deck, summary report, editable PPTX on the corporate templates in `templates/`, and
  PDFs printed by a local Edge/Chrome/Chromium.
- A **local web GUI** (`legacy_audit/gui/`, FastAPI + plain HTML/JS) and a read-only **MCP
  server** for Copilot (`legacy_audit/mcp/`).

## Non-negotiable rules

1. **Scripts count, AI interprets.** Every number in any output comes from deterministic code
   and every fact carries `evidence: [{file, line}]`. Nothing in the scan or the reports calls
   an LLM. AI text is labelled "(AI)" in reports; a missing stage shows a placeholder, never an
   invented value.
2. **No network at scan time.** No cloud APIs, telemetry, or CDN links in generated HTML
   (vendor JS/CSS in `report/assets/vendor/`). Network use is explicit and optional only: Git
   clone, vulnerability data (osv.dev / NVD), `legacy-audit enrich` with a remote model.
3. **Never write into the scanned source.** Outputs go to `audit-output/`; inputs come from
   the source tree and `audit-inputs/`.
4. **Re-runnable.** Each scan is a snapshot `audit-output/runs/<run-id>/` with `latest`
   pointing to the newest. Per-file results are cached; linking, metrics, rules and totals are
   recomputed every run; `audit-overrides.yaml` is re-applied every run.
5. **Missing inputs are coverage gaps, not errors.** Report them ("Autosys JIL: not
   provided") and continue. One file or one step that fails must not fail the run: make it a
   finding or a run warning (decision 178).
6. **Secrets.** Never write tokens or passwords to config, outputs or logs. Mask credentials
   found in scanned files. API keys are read from environment variables only.
7. **Counting rules are code and docs.** Defaults in `config.DEFAULT_COUNTING_RULES` (from
   `docs/plan.md` § Counting rules); every report prints them. Headline counts are computed
   only in `legacy_audit/store/counts.py`.

## Architecture rules

- An analyzer implements `supports(file) -> bool` and `analyze(file, ctx) -> Facts`, has
  `name` and `version` class attributes, is registered in `legacy_audit/analyzers/__init__.py`
  only, reads only its own file, and never touches the store. Cross-file work belongs to the
  linker (`Symbol`s and `Ref`s).
- **Bump an analyzer's `version`** whenever its output changes (cached results are keyed by
  it). Bump `INVENTORY_VERSION` in `inventory/files.py` when the per-file record changes.
- Fact ids come from `model.make_id(kind, *key_fields)` and must be unique per entity and
  stable across runs.
- Count lines with `analyzers.textutil.split_lines` (`\n`, `\r\n`, lone `\r`), never
  `str.splitlines()` (decision 159).
- The CLI (`cli.py`) and the GUI (`gui/app.py`) hold no logic: both call the same functions.
- Reports only display facts read through `report/data.py` `RunData`. A wrong number is fixed
  where the fact is made (analyzer, linker, `store/counts.py`), not in the report.
- Models are pydantic v2 with `extra="forbid"`; config keys get a `Field(description=...)`.
  After changing a model run `python -m legacy_audit.model.schemas`.
- Copilot product files: every YAML front-matter value is quoted (decision 173). A new file
  that `init` installs must be added to `scan.COPILOT_PATHS` so it is never scanned.

## Tech choices

Python 3.11+ (CI: 3.11 and 3.13), `pyproject.toml` with hatchling, `typer` CLI, `pydantic` v2,
`jinja2`, `sqlglot` (SQL), `tree-sitter` grammars (Java, C, C++, TypeScript, C#), `fastapi` +
`uvicorn`, `python-pptx`, `pypdf`. Node 18+ only for optional Mermaid PNGs (`mmdc`); without it
the diagram goes to the speaker notes. OWASP Dependency-Check and OSV-Scanner are optional
external tools. Keep runtime dependencies few: every new one needs a one-line reason in
`docs/decisions.md` and must install from wheels on Windows, Linux and macOS.

## Commands

```
python -m venv .venv                       # Linux/WSL2: python3 -m venv .venv
.venv\Scripts\activate                     # Linux/WSL2: source .venv/bin/activate
pip install -e ".[dev]"
pytest                                     # all tests (a few minutes)
pytest tests/analyzers/test_java.py -k soap
ruff check . && ruff format --check .      # lint and format (ruff format . to fix)
mypy legacy_audit                          # strict type check
python -m legacy_audit.model.schemas       # after changing a model
python scripts/gen_dev_reference.py        # after changing modules, CLI, config, entities, routes, tasks
legacy-audit scan -c samples/struts-oracle-app/audit.yaml
legacy-audit report --type all -c samples/struts-oracle-app/audit.yaml
```

All four CI checks (ruff check, ruff format --check, mypy, pytest) must pass on Ubuntu and
Windows. The VS Code tasks in `.vscode/tasks.json` run the same commands.

## Code conventions

- Match the surrounding code: its naming, comment density and idioms. Modules start with a
  docstring saying what they do (it appears in the generated reference); public functions and
  classes get a one-line docstring when the name is not enough. Comments explain *why*, and
  cite the decision number when one applies.
- Full type hints (`mypy --strict` on `legacy_audit/`), `from __future__ import annotations`,
  modern syntax (`X | None`, `list[str]`). Line length 100 (ruff).
- Logging with `logging.getLogger("legacy_audit")`; never `print` in library code (scripts and
  the CLI's console output are fine). The scan's log goes to `runs/<id>/logs/scan.log`.
- Errors a user can fix are clear messages (CLI: `raise _fail("...")`), not tracebacks.
- Import heavy modules inside CLI command functions, like the existing commands.
- Cross-platform: the team works on Windows laptops and on WSL2. Use `pathlib`; store paths
  as POSIX strings (`as_posix()`); accept `\` in config paths; open every text file with
  `encoding="utf-8"`; no shell-only tricks; no symlinks required.

## Tests

- `pytest` with tests next to their area (`tests/analyzers/test_<analyzer>.py`,
  `tests/test_<area>.py`). Every analyzer change gets a unit test on a small text
  (`tests/analyzers/helpers.py`); every bug fix a test that fails without it.
- Every sample in `samples/` has an `expected.yaml` of exact counts checked by
  `tests/test_samples.py`. When a change moves a number, verify it by hand against the
  sample's source before updating `expected.yaml`, and say why in the commit message.
- Tests use no network and no installed external tools, and write only under `tmp_path`.
- Drift tests fail when generated files are stale: `schemas/` (`tests/test_schemas.py`) and
  `docs/developer/reference/` (`tests/test_dev_reference.py`).

## For every change

1. Read the related decisions and code first; when the request is ambiguous, pick the
   simplest option consistent with the rules above and record it.
2. Make the change with tests; keep the development branch green.
3. Update the docs in the same change:
   - `docs/decisions.md`: a new numbered row (date, decision, reason and who asked) for any
     behaviour, rule or dependency decision.
   - `docs/progress.md`: a dated section (what was done, known gaps, next step).
   - `CHANGELOG.md`: user-visible changes, in plain words.
   - A user-visible feature gets a demo script `scripts/show_<feature>.py`, a VS Code task
     `audit: <next number>. <what it shows>` (with a `windows` command variant) and a section
     in `docs/validate-in-vscode.md` with numbered steps for Windows and WSL2: what to run,
     what you should see, and what to do in an existing audit folder (re-run
     `legacy-audit init` if Copilot files changed, rescan, `/audit-enrich stale` if facts
     changed).
   - User guides (`docs/usage-guide.html`, `docs/user-guide.md`, `docs/admin-guide.md`,
     `docs/copilot-agent.md`) when analysts need new steps.
   - Regenerate `docs/developer/reference/` and `schemas/` when they are affected, and
     update `docs/developer/*.md` when the architecture or a recipe changes.
4. Run the full test suite, lint and type check; scan the samples the change touches.

## Writing for users

The people who run the tool are mostly not developers. In user-facing text (CLI messages, GUI,
reports, user guides, validate steps): plain words, short sentences, numbered steps, say what
they will see, give the exact command or menu path, and cover both Windows and WSL2 where they
differ. No jargon without a one-line explanation.

## Commits and pull requests

- Small commits with plain messages describing the change (what and why).
- Do not add AI-tool attribution, co-author trailers, "generated with" lines or links to AI
  tool sessions in commits, pull requests, code or docs.
- Pull requests target the team's development branch (`main` in the office repository), are
  squash-merged, and follow `.github/pull_request_template.md`.
