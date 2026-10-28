# Developer guide

Technical documentation for people and coding agents (GitHub Copilot) who maintain and extend
**legacy-audit**. The user guides are elsewhere (`docs/usage-guide.html`, `docs/user-guide.md`,
`docs/admin-guide.md`, `docs/copilot-agent.md`).

## Read in this order

1. [architecture.md](architecture.md): the pipeline from source files to facts, enrichment and
   reports; what each step reads and writes; the run folder.
2. [packages.md](packages.md): each package and module, its main classes and functions, and
   where to make a given kind of change.
3. [extending.md](extending.md): recipes (new analyzer, new fact field or entity, count,
   config key, rule, report page, CLI command, GUI action, enrichment stage, VS Code task,
   sample) and the definition of done.
4. [testing.md](testing.md): running tests, lint and type checks; what each test file covers;
   CI.
5. [troubleshooting.md](troubleshooting.md): where to look when something is wrong, and the
   pitfalls this code base has already hit.

Generated from the code (do not edit by hand; run `python scripts/gen_dev_reference.py`):

| Page | Content |
| --- | --- |
| [reference/modules.md](reference/modules.md) | Every module: docstring, classes, methods, functions with signatures |
| [reference/cli.md](reference/cli.md) | Every `legacy-audit` command and option |
| [reference/config.md](reference/config.md) | Every `audit.yaml` key with type, default and description |
| [reference/entities.md](reference/entities.md) | Every fact entity (the `data/*.json` files and `facts.sqlite` tables) and `run.json` |
| [reference/analyzers.md](reference/analyzers.md) | Registered analyzers in order, with versions |
| [reference/enrichment-stages.md](reference/enrichment-stages.md) | The AI enrichment stages, their inputs and order |
| [reference/gui-api.md](reference/gui-api.md) | The GUI's HTTP routes |
| [reference/vscode-tasks.md](reference/vscode-tasks.md) | The VS Code tasks |

Other project documents:

| Document | Use it for |
| --- | --- |
| `docs/plan.md` | The original specification: scope, data model, counting rules, outputs |
| `docs/decisions.md` | Every decision since (numbered; wins over `plan.md`). Search it before changing behaviour |
| `docs/progress.md` | What was done, known gaps and next steps, per change |
| `docs/validate-in-vscode.md` | How each feature is checked from VS Code, step by step |
| `docs/rules.md` | Rule pack syntax |
| `docs/performance.md` | Scan times on a large generated estate |
| `docs/prompts/` | The build prompts of the original phases 1-7 (history; all phases are done) |
| `CHANGELOG.md` | User-visible changes |
| `samples/README.md` | What each sample application exercises |

## Five-minute tour

```
python3 -m venv .venv && .venv/bin/python -m pip install -e ".[dev]"   # Windows: see testing.md
legacy-audit scan -c samples/struts-oracle-app/audit.yaml            # writes samples/.../audit-output/
legacy-audit report --type all -c samples/struts-oracle-app/audit.yaml --open
legacy-audit facts services -c samples/struts-oracle-app/audit.yaml
legacy-audit serve -w samples/struts-oracle-app                      # the GUI
pytest
```

Then open `samples/struts-oracle-app/audit-output/latest/`: `run.json`, `data/*.json`,
`data/facts.sqlite` and `reports/`.

## Glossary

| Term | Meaning |
| --- | --- |
| **Audited workspace** | The folder an analyst audits: legacy source (or a Git source), `audit.yaml`, `audit-overrides.yaml`, `audit-inputs/`, `audit-output/`. Not this repository |
| **Run** | One scan: `audit-output/runs/<yyyymmdd-hhmmss-xxxxxx>/`; `latest` points at the newest |
| **Fact** | A pydantic model (`model/facts.py`) with a stable `id` and `evidence`; one row of an entity |
| **Entity** | A kind of fact and its file: `services`, `screens`, `sql_statements`, `integrations`, ... (`model.ENTITIES`) |
| **Evidence** | `{file, line, snippet}`: where in the scanned source a fact was found |
| **Analyzer** | A plugin that reads one file and returns `Facts` (`analyzers/base.py`) |
| **Symbol / Ref** | What a file defines / what it points at by name; the linker turns refs into `CodeEdge`s (`dependencies`) |
| **Component** | A unit of the application (a war, a batch, a COBOL online, a config folder), detected from build files and folders |
| **Supplementary inputs** | Exports that are not code (Autosys JIL, MQSC, CFT, DDL, crontab, scanner reports) in `audit-inputs/<kind>/` |
| **Coverage gap** | Something missing or unreadable (an input not provided, an unparsed region, a skipped file); reported, never an error |
| **Counting rules** | The definitions of service, screen, query, ... (`config.DEFAULT_COUNTING_RULES`), printed in every report |
| **Enrichment** | AI-written interpretation, one JSON file per stage in `enrichment/`, validated against the facts |
| **Stale stage** | An enrichment stage whose input facts or upstream stages changed since it was validated |
| **Target profile** | `target.profile`: `spring-boot-angular` (default), `wildfly-gke` (lift to WildFly on GKE) or `none` (audit only) |
| **PostgreSQL grade** | `auto` / `review` / `manual`: how much work a SQL statement needs to run on PostgreSQL |
| **Copilot product files** | `legacy_audit/copilot/`: the `legacy-auditor` agent and `/audit-*` prompts installed into audited workspaces by `init` |
| **Decision N** | Row N of `docs/decisions.md`; code comments cite them |

## Ground rules

The non-negotiable rules, conventions and the workflow for every change are in
[`.github/copilot-instructions.md`](../../.github/copilot-instructions.md) (read by Copilot on
every request) and [`CONTRIBUTING.md`](../../CONTRIBUTING.md).
