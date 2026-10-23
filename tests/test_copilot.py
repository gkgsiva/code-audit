"""Phase 5: Copilot assets installed by ``legacy-audit init``."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from typer.testing import CliRunner

from legacy_audit.cli import app
from legacy_audit.copilot.install import MANIFEST, assets, install
from legacy_audit.enrich.stages import STAGES


def test_init_installs_copilot_files(sample_copy) -> None:  # type: ignore[no-untyped-def]
    app_dir = sample_copy("struts-oracle-app")
    res = CliRunner().invoke(app, ["init", str(app_dir)])
    assert res.exit_code == 0, res.output
    gh = app_dir / ".github"
    for rel in (
        "agents/legacy-auditor.agent.md",
        "chatmodes/legacy-auditor.chatmode.md",
        "copilot-instructions.md",
        "prompts/audit-scan.prompt.md",
        "prompts/audit-enrich.prompt.md",
        "prompts/audit-report.prompt.md",
        "prompts/audit-qa.prompt.md",
        "legacy-audit/schemas/plan.schema.json",
    ):
        assert (gh / rel).is_file(), rel
    assert json.loads((app_dir / ".vscode" / "mcp.json").read_text())["servers"]["legacy-audit"]
    agent = (gh / "agents" / "legacy-auditor.agent.md").read_text(encoding="utf-8")
    assert agent.startswith("---\ndescription: ") and "\ntools: " in agent
    assert "Numbers come only from the scanner" in agent
    enrich = (gh / "prompts" / "audit-enrich.prompt.md").read_text(encoding="utf-8")
    assert enrich.startswith("---\nagent: legacy-auditor\n")
    for stage in STAGES:  # every stage is explained to the agent
        assert f"`{stage.name}`" in enrich, stage.name


def test_reinit_keeps_user_edits(tmp_path: Path) -> None:
    first = install(tmp_path, "both")
    assert {d.action for d in first} == {"created"}
    instr = tmp_path / ".github" / "copilot-instructions.md"
    instr.write_text(instr.read_text(encoding="utf-8") + "\n- Our team rule.\n", encoding="utf-8")
    second = {d.path: d.action for d in install(tmp_path, "both")}
    assert second[".github/copilot-instructions.md"] == "unchanged"  # edited: left alone
    assert second[".github/agents/legacy-auditor.agent.md"] == "unchanged"
    assert "Our team rule." in instr.read_text(encoding="utf-8")
    assert not instr.with_name("copilot-instructions.md.new").exists()

    # a new tool version changes the template: unedited files update, edited ones are kept
    manifest = json.loads((tmp_path / MANIFEST).read_text(encoding="utf-8"))
    agent = tmp_path / ".github" / "agents" / "legacy-auditor.agent.md"
    agent.write_text("old version\n", encoding="utf-8")  # as an older tool installed it
    instr_old = "old instructions\n"
    instr.write_text(instr_old + "- Our team rule.\n", encoding="utf-8")  # user-edited
    manifest["files"][".github/agents/legacy-auditor.agent.md"] = hashlib.sha256(
        b"old version\n"
    ).hexdigest()
    manifest["files"][".github/copilot-instructions.md"] = hashlib.sha256(
        instr_old.encode()
    ).hexdigest()
    (tmp_path / MANIFEST).write_text(json.dumps(manifest), encoding="utf-8")
    third = {d.path: d.action for d in install(tmp_path, "both")}
    assert third[".github/agents/legacy-auditor.agent.md"] == "updated"
    assert (
        agent.read_text(encoding="utf-8")
        == assets("both")[".github/agents/legacy-auditor.agent.md"]
    )
    assert third[".github/copilot-instructions.md"] == "kept"
    assert "Our team rule." in instr.read_text(encoding="utf-8")
    new = instr.with_name("copilot-instructions.md.new").read_text(encoding="utf-8")
    assert new == assets("both")[".github/copilot-instructions.md"]


def test_layouts(tmp_path: Path) -> None:
    agents = assets("agents")
    assert ".github/agents/legacy-auditor.agent.md" in agents
    assert not any("chatmodes" in p for p in agents)
    chat = assets("chatmodes")
    assert ".github/chatmodes/legacy-auditor.chatmode.md" in chat
    assert chat[".github/prompts/audit-scan.prompt.md"].startswith("---\nmode: legacy-auditor\n")
    assert assets("none") == {}
    res = CliRunner().invoke(app, ["init", str(tmp_path), "--copilot-layout", "nope"])
    assert res.exit_code == 1


def test_front_matter_is_valid_yaml() -> None:
    """VS Code drops an agent or prompt file whose front-matter does not parse; a plain value
    holding ": " (as in "Enrich the scan facts: all stages") is invalid YAML."""
    import yaml

    for layout in ("agents", "chatmodes"):
        for rel, text in assets(layout, model="GPT-5 (copilot)").items():
            if not rel.endswith(".md") or not text.startswith("---\n"):
                continue
            head = yaml.safe_load(text.split("---\n")[1])
            assert isinstance(head, dict) and head["description"], rel
    agent_md = assets("agents")[".github/agents/legacy-auditor.agent.md"]
    agent = yaml.safe_load(agent_md.split("---\n")[1])
    assert agent["description"].startswith("Audits this legacy application with legacy-audit: ")
    enrich = assets("agents")[".github/prompts/audit-enrich.prompt.md"].split("---\n")[1]
    assert yaml.safe_load(enrich) == {
        "agent": "legacy-auditor",
        "description": "Enrich the scan facts: all stages, stale stages, or one stage",
    }
