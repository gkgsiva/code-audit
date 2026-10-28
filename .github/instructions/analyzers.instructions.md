---
applyTo: "legacy_audit/analyzers/**,legacy_audit/inventory/**"
---

# Analyzers and inventory

- An analyzer reads **one file** (`ctx.text`, `ctx.read_bytes()`) and returns `Facts`. It never
  opens other files, never writes anything and never touches the store or the cache. What other
  files define or use goes in `facts.symbol(...)` / `facts.ref(...)`; the linker resolves them.
- Contract and helpers: `legacy_audit/analyzers/base.py`. Register new analyzers only in
  `legacy_audit/analyzers/__init__.py`. Recipe: `docs/developer/extending.md#add-an-analyzer`.
- **Bump the analyzer's `version`** in every change that alters its output, otherwise cached
  results in existing audit folders stay stale. Bump `INVENTORY_VERSION`
  (`inventory/files.py`) when the per-file record (language, category, LOC, line splitting)
  changes.
- Every fact: id from `make_id(kind, *key_fields)` (unique per entity, stable across runs),
  `evidence=[Evidence(file=file.path, line=n)]` with 1-based lines.
- Split lines with `analyzers.textutil.split_lines` only (decision 159).
- What cannot be parsed is `facts.coverage.gap(start, end, reason)`, not an exception.
- Never copy a credential value into a fact, a finding or a log; mask it.
- New file types: `inventory/language.py` (`EXTENSIONS`, `FILENAMES`, `sniff`,
  `detect_category`) and comment syntax in `inventory/loc.py` (`SYNTAX`).
- Tests: `tests/analyzers/test_<name>.py` with `tests/analyzers/helpers.py` (`run`,
  `entities`, `config`). Then run `pytest tests/test_samples.py`; if a sample count moves,
  check it by hand before editing `samples/<name>/expected.yaml`.
