"""Line numbers count lines as VS Code and the validator do: a line ends at ``\\n``, ``\\r\\n``
or a lone ``\\r`` (old Mac files), never at a form feed or another Unicode line separator."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from legacy_audit.analyzers.textutil import split_lines
from legacy_audit.config import load_config
from legacy_audit.enrich.validate import _Checker
from legacy_audit.inventory.encoding import decode
from legacy_audit.scan import run_scan
from legacy_audit.security import vulns

# An XML document with old Mac line endings and page breaks, like the XPath specification
# shipped in the examples of an XSL formatter: 6 lines in VS Code, the address on line 5.
SPEC = b'<?xml version="1.0"?>\r<spec>\r<p>see\x0cpage</p>\r<p>\x0c</p>\r' + (
    b'<loc href="http://pricing.acme.com/api/prices">XPath</loc>\r</spec>\r'
)


@pytest.fixture(autouse=True)
def _no_security_tools(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)


def test_split_lines_breaks_only_on_line_endings() -> None:
    assert split_lines("a\r\nb\rc\nd\x0ce\u2028f\x85g\n") == ["a", "b", "c", "d\x0ce\u2028f\x85g"]
    assert split_lines("") == [] and split_lines("x") == ["x"]
    assert decode(b"a\r\nb\rc", "utf-8") == "a\nb\nc"


def test_evidence_lines_stay_inside_the_file(tmp_path: Path) -> None:
    (tmp_path / "app" / "docs").mkdir(parents=True)
    (tmp_path / "app" / "docs" / "xpath.xml").write_bytes(SPEC)
    (tmp_path / "audit.yaml").write_text(
        'application:\n  name: lines\nsource:\n  type: local\n  paths: ["app"]\n', "utf-8"
    )
    run_dir = run_scan(load_config(tmp_path / "audit.yaml")).run_dir
    data = json.loads((run_dir / "data" / "web_services.json").read_text(encoding="utf-8"))
    rows = data["items"] if isinstance(data, dict) else data
    assert [e["line"] for r in rows for e in r["evidence"]] == [5]
    checker = _Checker.__new__(_Checker)
    checker.line_counts = {}
    assert checker._lines(tmp_path / "app" / "docs" / "xpath.xml") == 6
