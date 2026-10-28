"""Phase 5: the read-only MCP server and ``legacy-audit facts``."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest
from typer.testing import CliRunner

from legacy_audit.cli import app
from legacy_audit.config import load_config
from legacy_audit.mcp.facts import Facts
from legacy_audit.mcp.server import TOOLS, Server
from legacy_audit.scan import run_scan
from legacy_audit.security import vulns


@pytest.fixture(scope="module")
def scanned(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """A scanned copy of the Struts sample (its own audit-output)."""
    import shutil

    from tests.conftest import SAMPLES

    app_dir = tmp_path_factory.mktemp("mcp") / "struts-oracle-app"
    shutil.copytree(
        SAMPLES / "struts-oracle-app", app_dir, ignore=shutil.ignore_patterns("audit-output")
    )
    mp = pytest.MonkeyPatch()
    mp.setattr(vulns, "find_tool", lambda configured, names: None)
    run_scan(load_config(app_dir / "audit.yaml"))
    mp.undo()
    return app_dir


def _call(server: Server, name: str, args: dict[str, Any]) -> tuple[bool, Any]:
    reply = server.handle(
        {
            "jsonrpc": "2.0",
            "id": 7,
            "method": "tools/call",
            "params": {"name": name, "arguments": args},
        }
    )
    assert reply is not None and reply["id"] == 7
    res = reply["result"]
    text = res["content"][0]["text"]
    return res["isError"], (text if res["isError"] else json.loads(text))


def test_each_tool_responds(scanned: Path) -> None:
    out = scanned / "audit-output"
    run_dir = next(p for p in (out / "runs").iterdir() if p.is_dir())
    server = Server(Facts(lambda: run_dir))

    err, res = _call(
        server, "query_facts", {"kind": "services", "filters": {"kind": "struts-action"}}
    )
    assert not err and res["total"] == 3 and len(res["rows"]) == 3
    assert isinstance(res["rows"][0]["evidence"], list)  # JSON columns come back as JSON
    err, res = _call(
        server,
        "query_facts",
        {
            "kind": "sql_statements",
            "filters": {"tables": "~ORDERS"},
            "fields": ["id", "text"],
            "limit": 2,
        },
    )
    assert not err and res["returned"] == 2 and set(res["rows"][0]) == {"id", "text"}

    err, comp = _call(server, "get_component", {"id": "orders-web"})
    assert not err and comp["component"]["name"] == "orders-web"
    assert comp["services"]["total"] == 4 and comp["integrations"]["total"] == 3

    sql_id = res["rows"][0]["id"]
    err, sql = _call(server, "get_sql", {"id": sql_id})
    assert not err and sql["id"] == sql_id and "pg_grade" in sql

    err, ev = _call(
        server, "read_evidence", {"file": "conf/orders-app.properties", "line": 4, "context": 2}
    )
    assert not err and ev["start"] == 2 and ev["end"] == 6
    assert "db.password" in ev["text"] and "Orders2009!" not in ev["text"]  # masked

    err, cov = _call(server, "coverage", {})
    assert not err and cov["run_id"] == run_dir.name and cov["inputs"]

    # errors are tool results, not crashes
    assert _call(server, "query_facts", {"kind": "nope"})[0]
    assert _call(server, "query_facts", {"kind": "services", "filters": {"x; DROP": 1}})[0]
    assert _call(server, "read_evidence", {"file": "../../../../etc/passwd"})[0]
    assert _call(server, "get_sql", {"id": "sql:none"})[0]
    assert _call(server, "unknown", {})[0]


def test_stdio_protocol(scanned: Path) -> None:
    """Spawn `legacy-audit mcp` and speak JSON-RPC to it as Copilot does."""
    msgs = [
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "test", "version": "1"},
            },
        },
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
        {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {"name": "coverage", "arguments": {}},
        },
        {"jsonrpc": "2.0", "id": 4, "method": "nope"},
    ]
    proc = subprocess.run(
        [sys.executable, "-m", "legacy_audit", "mcp", "-c", str(scanned / "audit.yaml")],
        input="".join(json.dumps(m) + "\n" for m in msgs) + "not json\n",
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=120,
        check=True,
    )
    replies = [json.loads(line) for line in proc.stdout.splitlines()]
    assert [r["id"] for r in replies] == [1, 2, 3, 4, None]  # the notification gets no reply
    assert replies[0]["result"]["serverInfo"]["name"] == "legacy-audit"
    assert [t["name"] for t in replies[1]["result"]["tools"]] == [t["name"] for t in TOOLS]
    assert not replies[2]["result"]["isError"]
    assert replies[3]["error"]["code"] == -32601
    assert replies[4]["error"]["code"] == -32700


def test_facts_command(scanned: Path) -> None:
    res = CliRunner().invoke(
        app,
        [
            "facts",
            "services",
            "-c",
            str(scanned / "audit.yaml"),
            "--filter",
            "kind=servlet",
            "--fields",
            "id,name",
        ],
    )
    assert res.exit_code == 0, res.output
    data = json.loads(res.output)
    assert data["total"] == 1 and data["rows"][0]["name"] == "health"
    bad = CliRunner().invoke(
        app, ["facts", "services", "-c", str(scanned / "audit.yaml"), "--filter", "kind"]
    )
    assert bad.exit_code == 1


def test_batch_jobs_query_names_the_unscheduled_batches(tmp_path: Path) -> None:
    """With no scheduler input, batch_jobs is empty but the summary counts the batch
    components: the query says so, with the ids to document them by (decision 160)."""
    from tests.conftest import SAMPLES

    mp = pytest.MonkeyPatch()
    mp.setattr(vulns, "find_tool", lambda configured, names: None)
    cfg = load_config(SAMPLES / "legacy-layout-app" / "audit.yaml")
    run_dir = run_scan(cfg, output_dir=tmp_path / "out").run_dir
    mp.undo()
    result = Facts(lambda: run_dir).query_facts("batch_jobs")
    assert result["total"] == 0
    assert [b["component"] for b in result["unscheduled_batches"]] == [
        "billing-batch",
        "settlement-c",
    ]
    assert all(b["job_id"].startswith("component:") for b in result["unscheduled_batches"])
    assert "counts.batch_jobs.total in audit-summary.json (2)" in result["note"]
    assert "note" not in Facts(lambda: run_dir).query_facts("services")
