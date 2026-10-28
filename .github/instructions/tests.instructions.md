---
applyTo: "tests/**,samples/**"
---

# Tests and samples

- Tests run with no network and no installed external tools (scanners, browsers, Node,
  models). Use the existing fakes: `monkeypatch.setattr(vulns, "find_tool", ...)`, local fake
  HTTP servers in `tests/test_ai.py`, the autouse fixture in `tests/conftest.py` that disables
  the PDF browser (tests needing one are marked `@pytest.mark.real_browser`).
- Write only under `tmp_path` (`sample_copy` fixture, `output_dir=tmp_path / "out"`), never
  into `samples/`.
- Analyzer tests use `tests/analyzers/helpers.py` (`run(analyzer, path, text)`).
- Samples are byte-for-byte fixtures (`.gitattributes`: `samples/** -text`). Each has an
  `expected.yaml` of exact counts. Only change a count after checking it by hand against the
  sample's files, and say why in the commit message. Keep samples small (under ~2,000 lines)
  and describe each in `samples/README.md`.
- Tests must pass on Windows and Linux: build paths with `pathlib`, compare as POSIX strings,
  read and write with `encoding="utf-8"`.
- Typing is lenient in tests (`disallow_untyped_defs = false`), but ruff still applies.
