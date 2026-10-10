"""Install the Copilot agent, instructions, prompt files and schemas into a legacy workspace.

``legacy-audit init`` calls ``install``. Copilot's file names changed across releases (custom
chat modes became custom agents), so the agent is written as ``.github/agents/*.agent.md``,
``.github/chatmodes/*.chatmode.md`` or both. Front-matter is kept to ``description`` and
``tools`` so format changes stay cheap.

User edits are kept: ``.github/legacy-audit/installed.json`` records the hash of each file as
installed. On a later ``init``, a file still equal to that hash is updated in place; a file the
user changed is left alone and the new version is written next to it as ``<name>.new``.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from importlib.resources import files
from pathlib import Path

from legacy_audit import __version__

LAYOUTS = ("agents", "chatmodes", "both", "none")
MANIFEST = ".github/legacy-audit/installed.json"
AGENT = "legacy-auditor"
DESCRIPTION = (
    "Audits this legacy application with legacy-audit: runs the scan, enriches the facts stage "
    "by stage with evidence, validates, and builds the reports."
)
# Tool-set names of VS Code 1.105-1.106. If Copilot reports an unknown tool, delete the
# `tools:` line: the agent then gets every tool.
TOOLS = "['edit', 'search', 'runCommands', 'problems', 'legacy-audit/*']"
PROMPTS = {
    "audit-scan": "Run or resume the legacy-audit scan and summarise its coverage",
    "audit-enrich": "Enrich the scan facts: all stages, stale stages, or one stage",
    "audit-report": "Validate the enrichment and build the HTML and PPTX reports",
    "audit-qa": "Cross-check the enrichment against the facts and list contradictions",
}


@dataclass
class Installed:
    path: str
    action: str  # created | updated | unchanged | kept (new version written to <path>.new)


def _asset(name: str) -> str:
    return files("legacy_audit.copilot").joinpath(name).read_text(encoding="utf-8")


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def assets(layout: str) -> dict[str, str]:
    """Relative path -> content for a layout."""
    if layout not in LAYOUTS:
        raise ValueError(f"layout must be one of {', '.join(LAYOUTS)}")
    out: dict[str, str] = {}
    if layout == "none":
        return out
    body = _asset("agent.md")
    head = f"---\ndescription: {DESCRIPTION}\ntools: {TOOLS}\n---\n\n"
    if layout in ("agents", "both"):
        out[f".github/agents/{AGENT}.agent.md"] = head + body
    if layout in ("chatmodes", "both"):
        out[f".github/chatmodes/{AGENT}.chatmode.md"] = head + body
    # prompt files name the agent: `agent:` in current builds, `mode:` in the chat-mode era
    key = "mode" if layout == "chatmodes" else "agent"
    for name, desc in PROMPTS.items():
        text = _asset(f"prompts/{name}.prompt.md")
        out[f".github/prompts/{name}.prompt.md"] = (
            f"---\n{key}: {AGENT}\ndescription: {desc}\n---\n\n{text}"
        )
    out[".github/copilot-instructions.md"] = _asset("copilot-instructions.md")
    out[".vscode/mcp.json"] = _asset("mcp.json")
    from legacy_audit.model.schemas import build_enrichment_schemas, render

    for stage, schema in build_enrichment_schemas().items():
        out[f".github/legacy-audit/schemas/{stage}.schema.json"] = render(schema)
    return out


def install(folder: Path, layout: str = "both") -> list[Installed]:
    manifest_path = folder / MANIFEST
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        manifest = {}
    recorded: dict[str, str] = manifest.get("files", {})
    done: list[Installed] = []
    for rel, content in assets(layout).items():
        target = folder / rel
        new_sha = _sha(content)
        if not target.exists():
            action = "created"
        else:
            current = _sha(target.read_text(encoding="utf-8"))
            if current == new_sha:
                recorded[rel] = new_sha
                done.append(Installed(rel, "unchanged"))
                continue
            if recorded.get(rel) == new_sha:  # edited by the user; no newer version to offer
                done.append(Installed(rel, "unchanged"))
                continue
            if recorded.get(rel) != current:  # edited by the user (or not ours)
                target.with_name(target.name + ".new").write_text(
                    content, encoding="utf-8", newline="\n"
                )
                done.append(Installed(rel, "kept"))
                continue
            action = "updated"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8", newline="\n")
        recorded[rel] = new_sha
        done.append(Installed(rel, action))
    if done:
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(
            json.dumps({"tool_version": __version__, "files": recorded}, indent=2, sort_keys=True)
            + "\n",
            encoding="utf-8",
            newline="\n",
        )
    return done
