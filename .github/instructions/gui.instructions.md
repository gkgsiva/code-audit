---
applyTo: "legacy_audit/gui/**"
---

# GUI

- The GUI has no logic of its own: routes call the same functions as the CLI. Long work (scan,
  reports, enrichment) runs through `Jobs.start` (`gui/jobs.py`): one job at a time, progress
  as Server-Sent Events, cancel.
- Security stays as it is: bound to 127.0.0.1, `Host` must be localhost, every
  POST/PUT/DELETE needs the `X-Legacy-Audit: 1` header (the `api()` helper in
  `static/app.js` sends it), run ids checked with `RUN_ID`, names with `SAFE_NAME`, every
  resolved path checked with `is_relative_to` the workspace, run or source folder.
- Front end: plain HTML/JS/CSS in `gui/static/` with no build step and nothing loaded from
  the internet (Chart.js and Mermaid come from `/vendor`).
- Labels and messages are for non-technical users.
- Tests: `tests/test_gui.py` (FastAPI `TestClient`). After adding a route, regenerate
  `docs/developer/reference/gui-api.md` (`python scripts/gen_dev_reference.py`).
