# legacy-audit: rules for Copilot in this workspace

This workspace is audited with `legacy-audit`. When working on the audit:

- Numbers come only from `audit-output/latest/data/` (scanner output). Never invent or recount
  a number; copy it as a figure `{label, metric, value}` with its `audit-summary.json` path, or
  mark it `estimate: true` with a note.
- Every claim cites evidence (`file:line` from the facts) or is labelled an assumption.
- Write only under `audit-output/latest/enrichment/`. Never change the legacy source code,
  `audit-output/latest/data/` or `audit.yaml`.
- Run `legacy-audit validate` after writing each enrichment stage and fix its errors.
- Stop and ask when evidence conflicts.
- Never paste passwords, tokens or keys into chat or files.
