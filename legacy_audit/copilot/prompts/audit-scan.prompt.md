Run or resume the legacy-audit scan of this workspace.

1. If `audit.yaml` is missing, run `legacy-audit init` and ask the user to review `audit.yaml`
   (sources, application name) before scanning.
2. Run `legacy-audit scan` in the terminal (add `--full` only if the user asks to ignore the
   cache). Wait for it to finish.
3. Read `audit-output/latest/data/audit-summary.json` and `audit-output/latest/run.json` and
   report, citing the numbers exactly as written there:
   - files, components, services, screens, SQL statements, integrations, batch jobs, libraries;
   - findings by severity and PostgreSQL grades;
   - input coverage gaps (inputs "not provided") and unresolved references;
   - which enrichment stages are stale (`audit-output/latest/enrichment/status.json`, if present).
4. Suggest the next step: `/audit-enrich all` on a first run, `/audit-enrich stale` after a
   re-scan, or providing missing inputs in `audit-inputs/<kind>/`.
