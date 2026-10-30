Validate the enrichment and build the reports.

1. Run `legacy-audit validate`. If it reports errors, fix them in
   `audit-output/latest/enrichment/` (never in `data/`) and validate again. Report any warnings.
2. If stages are stale or missing, tell the user which, and ask whether to run
   `/audit-enrich stale` first or build the reports with placeholders.
3. Run `legacy-audit report --type all`. It builds:
   - `audit-output/latest/reports/tech/index.html`: technical site;
   - `audit-output/latest/reports/functional/index.html`: functional document for business
     readers (capabilities, use cases, a flow per screen and per batch, business rules, data,
     batch calendar, interfaces, glossary);
   - `audit-output/latest/reports/mgmt/index.html`: management deck (arrow keys to move,
     `N` for speaker notes, print to PDF from the browser);
   - `audit-output/latest/reports/summary/index.html`: one-page summary;
   - `audit-output/latest/reports/pdf/`: PDF copies of the four reports (needs Edge, Chrome or
     Chromium; the command says if they were skipped and why).
4. Tell the user where the files are and which slides still show a placeholder.
