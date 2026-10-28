# Phase 5 — Copilot agent, enrichment and management outputs

Goal: analysts can run AI enrichment with GitHub Copilot in VS Code over the scan facts, the results are validated and merged, and the management HTML deck and editable PPTX are generated.

## Copilot assets (installed by `legacy-audit init`)

Templates in `legacy_audit/copilot/`, installed into the legacy workspace:

- `.github/agents/legacy-auditor.agent.md` and, for older VS Code/Copilot builds, `.github/chatmodes/legacy-auditor.chatmode.md` (same content, old front-matter). `init --copilot-layout agents|chatmodes|both` (default both). Document the Copilot version tested; keep front-matter minimal (description, tools) so format changes are cheap.
- `.github/copilot-instructions.md` — always-on rules: numbers only from `audit-output/latest/data`; cite evidence (file:line) or label as assumption; write only under `audit-output/latest/enrichment/`; validate after each stage with `legacy-audit validate`; stop and ask when evidence conflicts; never paste secrets.
- `.github/prompts/audit-scan.prompt.md`, `audit-enrich.prompt.md` (argument: `all` | `stale` | stage name), `audit-report.prompt.md`, `audit-qa.prompt.md`.
- Do not overwrite user-modified files: `init` compares against the installed version hash and writes `.new` copies when changed.

## Enrichment

- JSON Schemas in `schemas/enrichment/` for: `overview`, `classification`, `architecture` (layers, deployment units, Mermaid), `flows`, `service_mapping`, `data_migration`, `dependency_plan` (upgrade themes linked to waves), `risks`, `assumptions`, `target_architecture`, `plan` (waves, sequence, effort ranges from `effort.json`, team shape), `narratives` (executive, architect, functional, developer).
- Every file carries `meta: {stage, schema_version, built_from: {run_id, facts_hash_by_input}, generated_at, author: "copilot-agent"}`.
- `legacy-audit validate` — schema check, referential check (every referenced id exists in facts), "numbers come from facts" check (numeric claims about counts must match facts or be marked estimates), evidence paths exist.
- **Staleness** — each stage declares which fact kinds it depends on (e.g. flows ← services, screens, batch_jobs, integrations, code edges). After a re-run, compare hashes; mark stale stages in `enrichment/status.json`; `audit-enrich stale` re-runs only those. Prior enrichment is carried into the new run folder.
- Overrides from `audit-overrides.yaml` (accepted risks, service merges, reclassifications) are applied before the agent sees facts and noted in the enrichment.
- Stage prompts are written so each fits a modest context window: they read `audit-summary.json` plus targeted SQLite queries, iterate per component for large estates, and keep a carried `enrichment/_working-summary.md`.

## MCP server (optional)

`legacy-audit mcp` — stdio MCP server exposing read-only tools over the latest run's SQLite and source: `query_facts(kind, filters, limit)`, `get_component(id)`, `get_sql(id)`, `read_evidence(file, line, context=20)`, `coverage()`. Document how to register it in VS Code. Read-only; paths confined to the source and output roots.

## Management outputs

- `deck_builder.py` builds `deck.json` (slides, each with type, title, audience, content blocks, chart data) from facts + enrichment, following the section table in `docs/plan.md` § Outputs. Slides whose enrichment is missing show a clear placeholder ("Run /audit-enrich plan").
- **HTML deck** — static slider with vendored Chart.js, keyboard navigation, print-to-PDF stylesheet, speaker notes.
- **PPTX** — `pptx/build-pptx.js` (Node, `pptxgenjs`) reads `deck.json`, uses native editable charts (bar, doughnut, line), tables, and PNG diagrams rendered from Mermaid via `@mermaid-js/mermaid-cli` when available. Theme from `branding` (logo, colours, fonts; optional master `.pptx`/`.potx` path reserved for the corporate template). Python calls Node via subprocess; if Node/packages are missing, skip with a clear message.
- Include a dependency-security slide (exposure today → after migration) and an input-coverage slide.

## Acceptance criteria

- `init` on a sample installs the Copilot files; re-running `init` keeps user edits.
- Hand-written enrichment fixtures for one sample validate; a fixture with a wrong count or missing evidence fails validation with a clear message.
- Adding Autosys input + re-scan marks `flows`, `risks`, `plan`, `narratives` stale but not `classification` (test).
- `report --type mgmt` and `--type pptx` build for a sample with fixtures; the PPTX opens (python-pptx smoke test reads slide count and chart types) and charts are native charts, not images.
- MCP server responds to each tool in a test harness.
