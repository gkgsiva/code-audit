# Testing, linting and CI

## Commands

From the repository root, with the virtual environment active (or through the VS Code tasks):

| What | Linux / macOS / WSL2 | Windows (PowerShell) | VS Code task |
| --- | --- | --- | --- |
| Set up | `python3 -m venv .venv && .venv/bin/python -m pip install -e ".[dev]"` | `python -m venv .venv; .venv\Scripts\python -m pip install -e ".[dev]"` | `audit: 1. set up (venv + install)` |
| All tests | `.venv/bin/python -m pytest` | `.venv\Scripts\python -m pytest` | `audit: 2. tests (pytest)` |
| One file / one test | `pytest tests/test_linker.py -k soap` | same | Testing view (flask icon) |
| Lint and format check | `ruff check .` and `ruff format --check .` | same | `audit: 3. lint + type check` |
| Format | `ruff format .` | same | Format Document (Ruff is the formatter in `.vscode/settings.json`) |
| Type check | `mypy legacy_audit` (strict) | same | `audit: 3. lint + type check` |
| Regenerate JSON Schemas | `python -m legacy_audit.model.schemas` | same | |
| Regenerate developer reference | `python scripts/gen_dev_reference.py` | same | `audit: 35. regenerate the developer reference (docs/developer/reference)` |
| Scan a sample | `legacy-audit scan -c samples/<name>/audit.yaml` | same | `audit: 4. scan a sample` |
| Debug a scan | | | Run and Debug > `legacy-audit: scan a sample` |

On WSL2 and Linux use `python3` (there is no `python` alias on Ubuntu); on Windows `python`.
After pulling changes that add a dependency (for example a tree-sitter grammar), run the setup
step again (`pip install -e ".[dev]"`).

The full suite takes a few minutes; `tests/test_samples.py` (a full scan of every sample) is the
slowest part.

## What the tests cover

| Test file | Covers |
| --- | --- |
| `tests/analyzers/test_*.py` | One analyzer each, on small in-memory texts (`tests/analyzers/helpers.py`: `run(analyzer, path, text)`, `entities(facts, name)`, `config(**extra)`) |
| `tests/test_samples.py` | Scans every `samples/*/` and compares with its `expected.yaml`: inventory, LOC by language, components, counts, services, screens, integrations, batch jobs, findings, grades, libraries |
| `tests/test_scan.py` | Scan, re-run, cache, coverage, diff, overrides; the source tree is never written (`tree_hash`) |
| `tests/test_linking_rerun.py` | Late inputs linking to cached sources, analyzer crash isolation |
| `tests/test_linker.py` | Reference resolution, merging, counting rules, web services, grouping, failing steps |
| `tests/test_components.py`, `tests/test_walker.py`, `tests/test_language.py`, `tests/test_loc.py`, `tests/test_encoding.py`, `tests/test_line_numbers.py` | Inventory: components and grouping, walking and excludes, language detection, LOC, encodings, line breaks |
| `tests/test_metrics.py`, `tests/test_rules.py`, `tests/test_security.py`, `tests/test_targets.py` | Metrics and effort, rule packs and PostgreSQL grading, dependency security, target profiles |
| `tests/test_schemas.py` | `schemas/` match the models; every entity has `id` and `evidence` |
| `tests/test_dev_reference.py` | `docs/developer/reference/` matches the code |
| `tests/test_config.py` | `audit.yaml` parsing and errors |
| `tests/test_report.py`, `tests/test_functional.py`, `tests/test_mgmt.py`, `tests/test_summary.py`, `tests/test_batch_list.py`, `tests/test_pdf.py` | Reports |
| `tests/test_enrich.py`, `tests/test_enrich_new_stacks.py`, `tests/test_ai.py` | Enrichment validation, staleness, carry-over, `legacy-audit enrich` with fake model servers |
| `tests/test_copilot.py`, `tests/test_mcp.py` | Copilot files installed by `init` (headers parse as YAML), the MCP server and `legacy-audit facts` |
| `tests/test_gui.py`, `tests/test_hardening.py` | GUI API and its guards, secrets never in outputs; parallel analysis, cancel, interrupted runs, odd files, doctor |
| `tests/test_git_source.py` | Git sources, with a local bare repository as the remote (no network) |

Fixtures: `tests/conftest.py` (`runner` and `invoke` for the CLI, `sample_copy` copies a sample
to a temp folder, `tree_hash`, `write`), `tests/fixtures/enrichment/<sample>/` (demo enrichment
files that pass the validator), `tests/fixtures/security/` (scanner reports).
`tests/stress_tree.py` generates a large synthetic estate for `scripts/perf.py`.

## Rules for tests

- **No network, no installed tools.** Tests must pass on a laptop with nothing but Python.
  Vulnerability scanners are forced "not installed" in sample tests
  (`monkeypatch.setattr(vulns, "find_tool", ...)`); model servers in `test_ai.py` are local fake
  HTTP servers; the PDF browser is disabled by an autouse fixture in `conftest.py` unless a test
  is marked `@pytest.mark.real_browser` (those are skipped when no browser is installed).
- **Write only under `tmp_path`.** Scan samples with `output_dir=tmp_path / "out"` or a
  `sample_copy`, never into `samples/` itself.
- **Exact numbers.** Sample counts are exact and checked by hand. When a change moves one,
  verify it against the sample's source, then update `expected.yaml` and explain it in the
  commit.
- **Windows.** CI runs on Windows too. Use `pathlib`, compare paths as POSIX strings
  (`as_posix()`), open files with `encoding="utf-8"`, and skip POSIX-only tests with
  `@pytest.mark.skipif(sys.platform == "win32", reason=...)` only when there is no other way.
- New behaviour gets a test in the matching file above; a bug fix gets a test that fails
  without the fix.

## Drift checks

Three kinds of generated files are committed and checked by tests:

| Files | Regenerate with | Test |
| --- | --- | --- |
| `schemas/facts/*.schema.json`, `schemas/enrichment/*.schema.json` | `python -m legacy_audit.model.schemas` | `tests/test_schemas.py` |
| `docs/developer/reference/*.md` | `python scripts/gen_dev_reference.py` | `tests/test_dev_reference.py` |
| `samples/*/expected.yaml` | by hand, after checking | `tests/test_samples.py` |

## CI

`.github/workflows/ci.yml` runs on every pull request and on pushes to `main`: Ubuntu and
Windows, Python 3.11 and 3.13; steps: `pip install -e ".[dev]"`, `ruff check .`,
`ruff format --check .`, `mypy legacy_audit`, `pytest`. Keep all four green.

`.github/workflows/copilot-setup-steps.yml` prepares the environment for the GitHub Copilot
coding agent (Python 3.11 and the dev install) so it can run the same checks.
