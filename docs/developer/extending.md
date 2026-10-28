# How to change and extend legacy-audit

Step-by-step recipes for the changes this tool gets most often. Each recipe ends with the same
"definition of done" (last section). Read [architecture.md](architecture.md) first if you have
not.

Before you start any change:

1. Read `docs/decisions.md` for decisions about the area (search for the module or feature
   name). A decision there wins over `docs/plan.md`.
2. Find where the change belongs. A wrong **number or fact** is fixed where the fact is made
   (an analyzer, the linker, `store/counts.py`), never in a report. A wrong **display** of a
   right fact is fixed in `report/`.
3. Look at the sample that shows the case (`samples/README.md`) or add a small fixture.

## Add an analyzer

For a new language, framework or input format.

1. Create `legacy_audit/analyzers/<name>.py` with a module docstring saying what it reads and
   what facts it makes (the docstring appears in [reference/analyzers.md](reference/analyzers.md)):

   ```python
   """Acme job files (``.ajob``): each job becomes a ``BatchJob`` (scheduler ``acme``) ..."""

   from __future__ import annotations

   from typing import ClassVar

   from legacy_audit.analyzers.base import AnalyzerContext, Facts
   from legacy_audit.analyzers.textutil import split_lines
   from legacy_audit.model import BatchJob, Evidence, FileRecord, make_id


   class AcmeAnalyzer:
       name: ClassVar[str] = "acme"
       version: ClassVar[str] = "1"

       def supports(self, file: FileRecord) -> bool:
           return file.language == "acme-job"

       def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
           facts = Facts()
           text = ctx.text or ""
           facts.coverage.lines = text.count("\n") + 1
           for no, line in enumerate(split_lines(text), 1):
               ...
               facts.add(
                   "batch_jobs",
                   BatchJob(
                       id=make_id("batch_job", "acme", name),
                       name=name,
                       scheduler="acme",
                       evidence=[Evidence(file=file.path, line=no)],
                   ),
               )
           return facts
   ```

   Rules for analyzers:
   - Look at **this file only**; never open other files and never write anything. Use
     `ctx.text` (already decoded) or `ctx.read_bytes()`.
   - Every fact has a stable id from `make_id(kind, *key_fields)` and `evidence` with the file
     and 1-based line. Split lines with `analyzers.textutil.split_lines` (the one line-break
     rule: `\n`, `\r\n`, lone `\r`; decision 159), so lines match what the validator and the
     reports count.
   - What other files define or use is expressed with `facts.symbol(...)` and `facts.ref(...)`;
     the linker resolves them.
   - Parts you cannot parse go in `facts.coverage.gap(start, end, reason)`: a coverage gap,
     not an exception. An exception is caught by `inventory/worker.run_analyzer` and turned
     into an `analyzer-error` finding, but do not rely on it.
   - Mask credentials: never copy a password or token value into a fact.
2. Register it in `legacy_audit/analyzers/__init__.py` (import and add to the tuple). Order
   matters only when two analyzers depend on the same file's results; there is no data flow
   between analyzers, so put it near related ones.
3. If the file type is new, teach the inventory to recognise it: `inventory/language.py`
   (`EXTENSIONS`, `FILENAMES` or `sniff`; the category in `detect_category`) and comment syntax
   in `inventory/loc.py` (`SYNTAX`). Bump `INVENTORY_VERSION` in `inventory/files.py` when
   existing files change language or LOC.
4. Unit tests in `tests/analyzers/test_<name>.py` with the helpers in `tests/analyzers/helpers.py`:

   ```python
   from legacy_audit.analyzers.acme import AcmeAnalyzer
   from tests.analyzers.helpers import entities, run


   def test_job_found():
       facts = run(AcmeAnalyzer(), "jobs/nightly.ajob", "JOB NIGHTLY\n  RUN billing\n")
       jobs = entities(facts, "batch_jobs")
       assert [j.name for j in jobs] == ["NIGHTLY"]
       assert jobs[0].evidence[0].line == 1
   ```
5. If the new facts need linking (refs to resolve, jobs to attach to components), add it to
   `linker/link.py` and test in `tests/test_linker.py`.
6. Add the technology to a sample (or a new sample, below) and update its `expected.yaml`.
7. If a new dependency is needed (a tree-sitter grammar, ...), add it to `pyproject.toml` and a
   one-line reason in `docs/decisions.md`.

## Change an existing analyzer

1. Write a failing unit test in `tests/analyzers/` first, from the smallest text that shows the
   problem.
2. Fix the analyzer.
3. **Bump its `version`** (a string; `"3"` -> `"4"`). Cached per-file results are keyed by it;
   without the bump, existing audit folders keep the old results until the file changes.
4. Run `pytest tests/test_samples.py`; if a sample count moves, check the new value by hand
   before changing `expected.yaml`, and say why in the commit message.

## Add a field to a fact, or a new entity

New field on an existing model (`legacy_audit/model/facts.py`):

1. Add it with a default and a `Field(description=...)` (the description is what the generated
   reference and the JSON Schema show).
2. Fill it in the analyzer or the linker (bump the analyzer version).
3. `python -m legacy_audit.model.schemas` to regenerate `schemas/facts/`.
4. The JSON, CSV and SQLite writers pick it up by themselves (`store/writer.py` reads the model
   fields). Show it where it is useful: catalogue columns (`report/catalogues.py`), summary,
   deck.
5. If the Copilot agent should use it, mention it in `legacy_audit/copilot/agent.md`
   ("Where things are") and, if a stage uses it, in `legacy_audit/ai/context.py`.

New entity (a new `data/<entity>.json`):

1. New model deriving from `Fact` in `model/facts.py`; add it to `ENTITIES` (export order) and
   `SCHEMA_MODELS`, and export it from `model/__init__.py`.
2. Add the name to `report/data.py` `ENTITIES` too (the reports load that list).
3. Regenerate schemas; `store/writer.py`, `store/diff.py`, `mcp/facts.py`, the GUI facts browser
   and `legacy-audit facts` follow `model.ENTITIES` automatically.
4. Facts from analyzers come in through `Facts.add("<entity>", fact)`; merging duplicates goes in
   `linker/aggregate.py` and the linker's `run` must pass the list through to its result.
5. If enrichment stages read it, add it to their `inputs` in `enrich/stages.py` (so they go
   stale when it changes).

## Add or change a count

All headline counts are computed in **one** place: `store/counts.py` `counts(facts)`.
`audit-summary.json`, every report and `tests/test_samples.py` read them from there.

1. Change `counts`, keeping its counting rule in `config.py` `DEFAULT_COUNTING_RULES` (every
   report prints these) and in `docs/plan.md` § Counting rules in step.
2. Update each sample's `expected.yaml` (`counts:`) after checking the numbers by hand.
3. If the Copilot agent quotes the number, the validator checks it by its `metric` path in
   `audit-summary.json`: keep paths stable or say in `docs/decisions.md` what moved.

## Add a configuration key

1. Add the field to the right model in `legacy_audit/config.py` with a default and a
   `Field(description=...)`. Models use `extra="forbid"`, so old configs without the key keep
   working and misspelt keys fail with a clear message.
2. Document it in `audit.example.yaml` (commented) and, if analysts should see it in new
   workspaces, in `legacy_audit/templates/audit.yaml`.
3. If it changes per-file analysis results, make it part of the cache key in `scan.py`
   (`cache_version`) or bump the analyzer version; if it changes only linking, metrics, rules or
   reports, nothing else is needed (those are recomputed every run).
4. Test parsing in `tests/test_config.py`.

## Add or change a rule

Rules are data: `rules/risk-rules.yaml`, `rules/pg-rules.yaml`, `rules/dependency-upgrades.yaml`,
`rules/eol.yaml`, `rules/target-mapping.yaml`. Syntax and examples: [../rules.md](../rules.md).
Text rules (regex) are part of the file-cache key, so files are re-read automatically. Test in
`tests/test_rules.py` (and `tests/test_security.py` for EOL and upgrades). Rule text may use
`[[phrase]]` placeholders that `target.fill` replaces per target profile, and rule tables may
hold `profiles:` / `by_target:` blocks (see `target.py`).

## Add a page or a section to a report

Technical site (`report/tech.py`):

1. A Jinja2 template in `report/tech_html/` extending `base.html` (macros in `_macros.html`).
2. A method on `_Pages` that gathers the data from `self.rd` (`RunData`) and calls
   `self._render(template, path, page_id, title, **ctx)`.
3. Call it from `_build_into`, and add the page to `NAV` if it belongs in the menu.
4. A table that needs search/sort/paging is a catalogue: columns in `report/catalogues.py`,
   page in `CATALOGUE_PAGES`.

Summary report (`report/summary.py`): a section is a `Section` (kpis, charts, tables) built
by a `_Summary` method; register it in `SECTIONS` and in `build_sections`, and make
`to_deck` turn it into slides. Each table gets a CSV next to the page.

Management deck and PowerPoint: slides are data built in `report/deck_builder.py`
(`_Deck`; `SECTIONS`), rendered as HTML by `mgmt.py` and as PPTX by `pptx_build.py` on the
corporate templates. Use the template's layouts only; never draw a custom master.

PDF: `report/pdf.py` prints the HTML pages; a page of the technical or functional site is
printed only when it is in that site's `NAV` / `FN_NAV` (`pdf.parts_for`; detail pages come
from `DETAIL_FOLDERS`). Check the page prints well (wide tables
go to landscape automatically; decision 174).

In every report: numbers come from `RunData` (the scan), never computed from enrichment text;
AI-written text is labelled "(AI)" and missing stages show a placeholder; HTML must work from
`file://` with no network (vendored JS/CSS only, nothing from a CDN).

Tests: `tests/test_report.py` (technical site), `tests/test_functional.py`,
`tests/test_mgmt.py` (deck, PPTX), `tests/test_summary.py`, `tests/test_pdf.py`.

## Add a CLI command or option

1. A function decorated with `@app.command()` in `legacy_audit/cli.py`, with `Annotated[...,
   typer.Option(help=...)]` parameters (the help text is shown in `--help` and in
   [reference/cli.md](reference/cli.md)). Import heavy modules inside the function, like the
   other commands, so `legacy-audit --help` stays fast.
2. Keep logic out of the CLI: put it in a module the GUI can call too.
3. Errors the user can fix: `raise _fail("message")` (exit code 1, red message, no traceback).
4. Test with `typer.testing.CliRunner` (`tests/conftest.py` `runner`, `invoke`).
5. Add the command to the usage block in `README.md`.

## Add a GUI action

1. A route inside `create_app` in `legacy_audit/gui/app.py` (request body as a pydantic model
   at the top of the file). Long work goes through `jobs.start(...)` so the page shows progress
   and can cancel; only one job runs at a time.
2. Check every path the client sends: run ids with `RUN_ID`, file names with `SAFE_NAME`, and
   resolved paths with `is_relative_to` the workspace or run folder.
3. The page: `gui/static/index.html` and `app.js` (plain JS, no build step, no CDN). Changes
   must send the `X-Legacy-Audit: 1` header (the existing `api()` helper in `app.js` does).
4. Test with FastAPI's `TestClient` in `tests/test_gui.py`.

## Add or change an enrichment stage

1. Model in `legacy_audit/enrich/models.py` (derive from `StageFile`; ids in `*_id` / `*_ids`
   fields are checked against the facts, figures must carry a `metric` path, claims cite
   evidence or say `assumption: true`).
2. Entry in `enrich/stages.py` `STAGES` with the fact files it reads (`inputs`) and upstream
   stages (`after`); keep `inputs` to what it really reads, since it decides staleness. If it
   suggests a target, add it to `target.TARGET_STAGES` so `profile: none` skips it.
3. Regenerate schemas (`python -m legacy_audit.model.schemas`).
4. Tell both writers what to do: the Copilot prompt `legacy_audit/copilot/prompts/audit-enrich.prompt.md`
   (and `agent.md` if it is a new rule), and the CLI path `legacy_audit/ai/context.py` (which
   facts and source excerpts go in the prompt).
5. Use it in the reports, through `deck_builder.load_enrichment`, labelled "(AI)".
6. Add or update the demo enrichment in `tests/fixtures/enrichment/` and tests in
   `tests/test_enrich.py`, `tests/test_ai.py`.
7. Analysts must run `legacy-audit init` again to get the new prompt files: say so in the
   validate section.

## Add a VS Code task and validation steps

Every user-visible feature gets a way to see it work from VS Code:

1. A script `scripts/show_<feature>.py` that scans a sample and prints what to look at (copy
   the shape of an existing one: module docstring with usage, `main(argv) -> int`).
2. A task in `.vscode/tasks.json` labelled `audit: <next number>. <what it shows>`, with a
   `command` for Linux/macOS/WSL2 (`.venv/bin/python`) and a `windows.command`
   (`.venv\\Scripts\\python`). Reuse the `sample` input when it applies.
3. A section in `docs/validate-in-vscode.md`: numbered steps, written for a non-technical
   reader, saying what to run, what they should see and the steps for their own audit folder
   (`legacy-audit init` if Copilot files changed, rescan, `/audit-enrich stale` if facts changed).
4. Regenerate the reference (the task list is in [reference/vscode-tasks.md](reference/vscode-tasks.md)).

## Add a sample application

1. A folder in `samples/<name>/` with `audit.yaml`, the source, optional `audit-inputs/` and an
   `expected.yaml` (copy the structure of an existing one; see `tests/test_samples.py` for the
   keys it checks). Keep it small (under ~2,000 lines), realistic in structure.
2. Add it to the table in `samples/README.md`, to the `sample` input in `.vscode/tasks.json`
   (and `.vscode/launch.json`).
3. Samples are byte-for-byte fixtures (`.gitattributes`: `samples/** -text`), so line endings
   you write are kept. Write every count in `expected.yaml` by checking it by hand.

## Add a dependency

Runtime dependencies stay few. Add it to `pyproject.toml` `dependencies` (or `dev` for test
tools), with a one-line reason in `docs/decisions.md`. It must be pure Python or ship wheels
for Windows, Linux and macOS on Python 3.11-3.13, and must not reach the network at scan time.
If it has no type hints, add a `[[tool.mypy.overrides]]` entry like the one for `pptx`.

## Definition of done (every change)

- [ ] Tests added or updated; `pytest` passes (`audit: 2. tests (pytest)`).
- [ ] `ruff check .`, `ruff format --check .` and `mypy legacy_audit` pass
      (`audit: 3. lint + type check`).
- [ ] If a model changed: `python -m legacy_audit.model.schemas`.
- [ ] If modules, CLI options, config keys, entities, GUI routes, analyzers, stages or tasks
      changed: `python scripts/gen_dev_reference.py`.
- [ ] Sample counts still match, or `expected.yaml` updated after checking by hand.
- [ ] `docs/decisions.md`: a numbered row for every decision (what and why, who asked).
- [ ] `docs/progress.md`: a dated section (done, known gaps, next step).
- [ ] User-visible change: a VS Code task, a section in `docs/validate-in-vscode.md`, and the
      plain-language guides (`docs/usage-guide.html`, `docs/user-guide.md`) when users need
      new steps.
- [ ] `CHANGELOG.md` entry under the next version.
- [ ] Works on Windows and on WSL2 (paths with `pathlib`, no shell-only tricks, UTF-8 file I/O
      with explicit `encoding="utf-8"`).
