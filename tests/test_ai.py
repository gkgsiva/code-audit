"""Model-agnostic enrichment: providers, the prompt context and `legacy-audit enrich`."""

from __future__ import annotations

import json
import re
import shutil
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any

import pytest
from typer.testing import CliRunner

from legacy_audit.ai import context
from legacy_audit.ai.providers import AiError, Client, extract_json, settings
from legacy_audit.ai.runner import enrich, pick_stages
from legacy_audit.cli import app
from legacy_audit.config import AiConfig, load_config
from legacy_audit.enrich.stages import BY_NAME
from legacy_audit.scan import run_scan
from legacy_audit.security import vulns
from tests.conftest import SAMPLES

ENRICHMENT = Path(__file__).parent / "fixtures" / "enrichment"
STAGE_RE = re.compile(r"# Stage \d+: .*?\(`(\w+)`\)")


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)
    for name in ("PROVIDER", "MODEL", "BASE_URL", "API_KEY_ENV"):
        monkeypatch.delenv(f"LEGACY_AUDIT_AI_{name}", raising=False)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)


class Recorder:
    """A transport that records requests and answers from a queue of replies."""

    def __init__(self, *replies: dict[str, Any]) -> None:
        self.replies = list(replies)
        self.calls: list[tuple[str, dict[str, str], dict[str, Any]]] = []

    def __call__(self, url: str, headers: dict[str, str], body: dict[str, Any], s: Any) -> Any:
        self.calls.append((url, headers, body))
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return reply


def _chat(text: str) -> dict[str, Any]:
    return {"choices": [{"message": {"content": text}}]}


def test_settings_come_from_config_then_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    cfg = AiConfig(provider="openai", model="gpt-x")
    assert settings(cfg).label == "openai:gpt-x"
    monkeypatch.setenv("LEGACY_AUDIT_AI_PROVIDER", "ollama")
    monkeypatch.setenv("LEGACY_AUDIT_AI_MODEL", "llama3.1")
    s = settings(cfg)
    assert (s.provider, s.model, s.url) == ("ollama", "llama3.1", "http://localhost:11434/v1")
    assert settings(cfg, model="qwen2.5").model == "qwen2.5"  # the command line wins
    monkeypatch.setenv("LEGACY_AUDIT_AI_PROVIDER", "nope")
    with pytest.raises(AiError, match="Unknown AI provider"):
        settings(cfg)


def test_copilot_and_missing_settings_explain_what_to_do(monkeypatch: pytest.MonkeyPatch) -> None:
    with pytest.raises(AiError, match="/audit-enrich"):
        Client(settings(AiConfig()))
    with pytest.raises(AiError, match="No model"):
        Client(settings(AiConfig(provider="anthropic")))
    client = Client(settings(AiConfig(provider="openai", model="m")), Recorder())
    with pytest.raises(AiError, match="OPENAI_API_KEY"):
        client.complete("s", "u")


def test_openai_compatible_request(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("OPENAI_API_KEY", "sk-test")
    rec = Recorder(_chat('{"ok": true}'))
    client = Client(settings(AiConfig(provider="openai", model="gpt-x")), rec)
    assert client.complete("sys", "user") == '{"ok": true}'
    url, headers, body = rec.calls[0]
    assert url == "https://api.openai.com/v1/chat/completions"
    assert headers == {"Authorization": "Bearer sk-test"}
    assert body["model"] == "gpt-x" and body["messages"][0] == {"role": "system", "content": "sys"}
    assert body["response_format"] == {"type": "json_object"}


def test_rejected_parameters_are_dropped(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = Recorder(
        AiError("HTTP 400 from x: Unsupported parameter: 'max_tokens'"),
        AiError("HTTP 400 from x: 'temperature' does not support 0.1"),
        _chat("{}"),
    )
    client = Client(
        settings(AiConfig(provider="openai", model="m", base_url="http://gw.local/v1")), rec
    )
    client.complete("s", "u")  # a gateway without a key is allowed
    last = rec.calls[-1][2]
    assert "max_completion_tokens" in last and "max_tokens" not in last
    assert "temperature" not in last and rec.calls[-1][1] == {}


def test_ollama_azure_and_anthropic_requests(monkeypatch: pytest.MonkeyPatch) -> None:
    rec = Recorder(_chat("{}"))
    Client(settings(AiConfig(provider="ollama", model="llama3.1")), rec).complete("s", "u")
    assert rec.calls[0][0] == "http://localhost:11434/v1/chat/completions"
    assert rec.calls[0][1] == {}

    monkeypatch.setenv("AZURE_OPENAI_API_KEY", "az")
    rec = Recorder(_chat("{}"))
    cfg = AiConfig(
        provider="azure-openai", model="gpt4o-prod", base_url="https://r.openai.azure.com"
    )
    Client(settings(cfg), rec).complete("s", "u")
    url, headers, body = rec.calls[0]
    assert url.startswith("https://r.openai.azure.com/openai/deployments/gpt4o-prod/chat/")
    assert "api-version=" in url and headers == {"api-key": "az"} and "model" not in body

    monkeypatch.setenv("ANTHROPIC_API_KEY", "ak")
    rec = Recorder({"content": [{"type": "text", "text": '{"a": 1}'}]})
    out = Client(settings(AiConfig(provider="anthropic", model="claude-x")), rec).complete("s", "u")
    url, headers, body = rec.calls[0]
    assert out == '{"a": 1}' and url == "https://api.anthropic.com/v1/messages"
    assert headers["x-api-key"] == "ak" and body["system"] == "s"


def test_extract_json_tolerates_fences_and_text() -> None:
    assert extract_json('```json\n{"a": 1}\n```') == {"a": 1}
    assert extract_json('Here it is: {"a": [1, 2]} hope it helps') == {"a": [1, 2]}
    with pytest.raises(AiError):
        extract_json("no json here")


def _scan(sample: str, out: Path) -> Path:
    return run_scan(load_config(SAMPLES / sample / "audit.yaml"), output_dir=out).run_dir


def test_context_holds_facts_sources_and_schema(tmp_path: Path) -> None:
    run_dir = _scan("c-proc-batch", tmp_path)
    system, user = context.build(run_dir, BY_NAME["functional"], 150_000)
    assert "Numbers come only from the scanner" in system and "Working without tools" in system
    assert "# Stage 12: Functional (`functional`)" in user
    assert '"title":"Functional"' in user or '"properties"' in user  # the JSON Schema
    assert "### data/batch_jobs.json" in user and "## Batch sources" in user
    assert "Nightly settlement" in user  # the script's header comment, with line numbers
    assert re.search(r"^\s+2: # Nightly settlement", user, re.M)
    small = context.build(run_dir, BY_NAME["functional"], 10_000)[1]
    assert len(small) < len(user)


class _FakeModel(BaseHTTPRequestHandler):
    """An OpenAI-compatible endpoint answering each stage with the demo enrichment."""

    sample = ""
    first_bad = True

    def do_POST(self) -> None:
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        user = body["messages"][-1]["content"]
        found = STAGE_RE.search(user)
        name = found.group(1) if found else "check"
        path = ENRICHMENT / self.sample / f"{name}.json"
        data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"ok": True}
        if name == "functional" and _FakeModel.first_bad:
            _FakeModel.first_bad = False  # one wrong id, to see the repair round
            data = json.loads(json.dumps(data))
            data["batches"][0]["job_id"] = "batch_job:nope"
        text = "```json\n" + json.dumps(data) + "\n```"
        out = json.dumps({"choices": [{"message": {"content": text}}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *args: Any) -> None:
        pass


def test_enrich_command_with_a_local_endpoint(sample_copy, tmp_path: Path) -> None:  # type: ignore[no-untyped-def]
    app_dir = sample_copy("spring-mvc-batch-app")
    cfg = app_dir / "audit.yaml"
    assert CliRunner().invoke(app, ["scan", "-c", str(cfg)]).exit_code == 0
    _FakeModel.sample, _FakeModel.first_bad = "spring-mvc-batch-app", True
    server = HTTPServer(("127.0.0.1", 0), _FakeModel)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = f"http://127.0.0.1:{server.server_port}/v1"
    try:
        base = ["-c", str(cfg), "--provider", "openai", "--model", "fake", "--base-url", url]
        res = CliRunner().invoke(app, ["enrich", *base, "--check"])
        assert res.exit_code == 0 and "answered correctly" in res.output, res.output
        res = CliRunner().invoke(app, ["enrich", *base, "-s", "functional"])
        assert res.exit_code == 0, res.output
        assert "valid after 2 round(s)" in res.output
    finally:
        server.shutdown()
    edir = app_dir / "audit-output" / "latest" / "enrichment"
    data = json.loads((edir / "functional.json").read_text(encoding="utf-8"))
    assert data["meta"]["author"] == "openai:fake" and data["meta"]["built_from"]
    run_dir = (app_dir / "audit-output" / "latest").resolve()
    assert [s.name for s in pick_stages(run_dir, None, stale=True)][:1] == ["overview"]
    assert "functional" not in [s.name for s in pick_stages(run_dir, None, stale=True)]


def test_enrich_stops_when_the_endpoint_fails(tmp_path: Path) -> None:
    run_dir = _scan("c-proc-batch", tmp_path)
    s = settings(AiConfig(provider="ollama", model="m"))
    client = Client(s, Recorder(AiError("Cannot reach http://localhost:11434/v1")))
    results = enrich(run_dir, s, pick_stages(run_dir, ["overview", "flows"], False), client)
    assert len(results) == 1 and not results[0].ok and "Cannot reach" in results[0].errors[0]
    shutil.rmtree(run_dir / "enrichment", ignore_errors=True)


def test_gui_runs_the_enrichment_with_the_configured_model(
    sample_copy,
    monkeypatch: pytest.MonkeyPatch,  # type: ignore[no-untyped-def]
) -> None:
    from fastapi.testclient import TestClient

    from legacy_audit.gui.app import create_app

    app_dir = sample_copy("spring-mvc-batch-app")
    _FakeModel.sample, _FakeModel.first_bad = "spring-mvc-batch-app", False
    server = HTTPServer(("127.0.0.1", 0), _FakeModel)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    monkeypatch.setenv("LEGACY_AUDIT_AI_PROVIDER", "openai")
    monkeypatch.setenv("LEGACY_AUDIT_AI_MODEL", "fake")
    monkeypatch.setenv("LEGACY_AUDIT_AI_BASE_URL", f"http://127.0.0.1:{server.server_port}/v1")
    h = {"X-Legacy-Audit": "1"}
    client = TestClient(
        create_app(app_dir, 8765, opener=lambda p: None), base_url="http://127.0.0.1:8765"
    )
    try:
        assert client.post("/api/scan", json={"full": False}, headers=h).status_code == 200
        client.app.state.jobs.wait()  # type: ignore[attr-defined]
        enr = client.get("/api/runs/latest/enrichment").json()
        assert enr["ai"] == {"provider": "openai", "label": "openai:fake", "problem": None}
        body = {"stale": False, "stages": ["functional"]}
        assert client.post("/api/runs/latest/enrich", json=body, headers=h).status_code == 200
        job = client.app.state.jobs.wait()  # type: ignore[attr-defined]
        assert job.state == "done" and job.result["valid"] == ["functional"]
    finally:
        server.shutdown()
    states = {
        s["name"]: s["state"] for s in client.get("/api/runs/latest/enrichment").json()["stages"]
    }
    assert states["functional"] == "valid"


def test_copilot_model_is_pinned_in_the_agent_file() -> None:
    from legacy_audit.copilot.install import assets

    pinned = assets("agents", "Claude Sonnet 4.5 (copilot)")[
        ".github/agents/legacy-auditor.agent.md"
    ]
    assert 'model: "Claude Sonnet 4.5 (copilot)"\n---' in pinned
    assert (
        "model:" not in assets("agents")[".github/agents/legacy-auditor.agent.md"].split("---")[1]
    )
