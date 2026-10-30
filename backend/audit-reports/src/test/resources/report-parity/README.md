# Report parity fixtures

Each folder holds one run of a sample and the four HTML reports the Python tool built from it
(`office-main`), used by `ReportParityTest`:

- `run.zip`: the run folder without `reports/`; `@SAMPLE@` in `run.json` stands for
  the sample folder under `samples/`.
- `expected.zip`: `tech/`, `functional/`, `mgmt/` and `summary/` as Python wrote them, without
  the copied `assets/` folders. In text files the sample folder is written `@SAMPLE@` (absolute)
  or `@REL@` (relative link). The summary page has the PowerPoint button and its script cut out,
  and the summary `deck.json` / `summary.pptx` are left out (the PowerPoint is not carried over).

Sets: `ref` (plain scans), `enriched` (with the enrichment files of the Python test fixtures) and
`auditonly` (target profile `none`).
