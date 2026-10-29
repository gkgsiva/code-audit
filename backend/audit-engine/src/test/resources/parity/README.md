# Parity fixtures

What the Python tool (branch `office-main`, the last version before the rewrite) wrote for each
sample with `legacy-audit scan --full`: the inventory, the components, the coverage findings
(`build-output-skipped`, `file-too-large`, `file-unreadable`) and the input coverage.
`ParityTest` scans every sample with the Java engine and compares field by field.

The component `type` and `roles` come from the linker (Phase 5); until then they are not
compared. Regenerate these files only from `office-main`, never by hand.

Since Phase 2:

- `<sample>/analyzers.json`: what each Python analyzer returned for each sample file (taken
  from the Python tool's file cache). `AnalyzerParityTest` compares every ported analyzer.
- `cases/<analyzer>.json.gz`: analyzer calls recorded while the Python tool's own test suite
  ran (the file record, its text or bytes, the config, and the facts or the error Python
  returned), at most 600 per analyzer. `CaseParityTest` replays them.
- `sqltools.json.gz`: SQL statements with the tables, statement type and features Python found;
  entries with `extra: true` are hand-written statements in six dialects, where a few
  differences are allowed (decision 209). `SqlToolsParityTest` compares them.
- `rules.json`: ignore rules, languages, categories, line counts and encodings
  (`RulesParityTest`).
