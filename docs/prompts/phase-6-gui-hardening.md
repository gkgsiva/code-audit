# Phase 6 — GUI and hardening

Goal: analysts run and re-run audits from a local GUI, compare runs and open every output; the tool is fast, packaged and documented.

## GUI

- `legacy-audit serve [--port 8765] [--workspace PATH]` starts FastAPI + uvicorn bound to `127.0.0.1` only; opens the browser. Plain HTML/JS front-end (no build step), vendored assets, same visual style as the technical report.
- Long-running scans run in a background worker; progress via Server-Sent Events; one active scan per workspace; cancel supported.
- Screens (see `docs/plan.md` § GUI application): Project setup (local folders or Git URLs with token env-var name — never a token text box that persists), Inputs (coverage, open folder), Component picker (pre-scan tree, tick/untick, type override → writes `audit.yaml`/`audit-overrides.yaml`), Run (Re-run / Full rescan, live progress, warnings), Results dashboard, Explorer (drill-down with evidence links), AI enrichment status (done/stale/invalid per stage, copy next prompt for Copilot Chat), Reports (build/open tech, mgmt, PPTX; ZIP data pack), Compare runs.
- API endpoints are documented (OpenAPI) and reuse the CLI's functions — no duplicate logic.

## Hardening

- Performance: profile on a generated 1–2M LOC tree; parallelise analysis with a process pool; stream inventory; batch SQLite inserts. Target from `docs/decisions.md` (default 2M LOC < 15 min on a laptop); record results in `docs/performance.md`.
- Robustness: huge files (skip/limit with a finding), unreadable encodings, symlink loops, long Windows paths, read-only source trees, interrupted runs (resume or clean up).
- Security: GUI localhost-only; path traversal tests on Explorer/evidence endpoints; secrets masking tests across all outputs.
- Packaging: wheel + `pipx install` instructions; `legacy-audit doctor` checks Python, Node, pptxgenjs, mermaid-cli, Dependency-Check, JDK tools and prints what is optional/missing; offline install notes (wheelhouse, vendored Node packages).
- Docs: `docs/user-guide.md` (end-to-end walkthrough with screenshots of a sample), `docs/admin-guide.md` (vulnerability DB sync/mirror, branding/template, rule packs), `docs/rules.md` (how to add rules), `CHANGELOG.md`.

## Acceptance criteria

- From a clean machine with Python only: install, `init`, scan a sample via the GUI, view dashboard, build tech report — all without the CLI.
- Re-run after adding JIL from the Inputs screen updates dashboard and marks stale enrichment.
- Compare runs shows the differences.
- `doctor` output accurate when Node / Dependency-Check are absent.
- Performance numbers recorded and meet the target, or the gap is explained with a plan.
