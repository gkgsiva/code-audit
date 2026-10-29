# Parity fixtures

What the Python tool (branch `office-main`, the last version before the rewrite) wrote for each
sample with `legacy-audit scan --full`: the inventory, the components, the coverage findings
(`build-output-skipped`, `file-too-large`, `file-unreadable`) and the input coverage.
`ParityTest` scans every sample with the Java engine and compares field by field.

The component `type` and `roles` come from the linker (Phase 5); until then they are not
compared. Regenerate these files only from `office-main`, never by hand.
