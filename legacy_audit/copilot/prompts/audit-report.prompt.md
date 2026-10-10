Validate the enrichment and build the reports.

1. Run `legacy-audit validate`. If it reports errors, fix them in
   `audit-output/latest/enrichment/` (never in `data/`) and validate again. Report any warnings.
2. If stages are stale or missing, tell the user which, and ask whether to run
   `/audit-enrich stale` first or build the reports with placeholders.
3. Run `legacy-audit report --type all`. It builds:
   - `audit-output/latest/reports/tech/index.html`: technical site;
   - `audit-output/latest/reports/mgmt/index.html`: management deck (arrow keys to move,
     `N` for speaker notes, print to PDF from the browser);
   - `audit-output/latest/reports/deck.pptx`: editable PowerPoint (needs Node.js; the command
     says if it was skipped and why).
4. Tell the user where the files are and which slides still show a placeholder.
