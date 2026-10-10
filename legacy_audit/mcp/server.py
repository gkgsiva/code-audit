"""``legacy-audit mcp``: a stdio MCP server exposing read-only tools over the latest run.

Register it in VS Code with ``.vscode/mcp.json`` (``legacy-audit init`` writes one). The server
speaks JSON-RPC 2.0, one message per line, and implements what Copilot needs: ``initialize``,
``ping``, ``tools/list`` and ``tools/call``. No dependency beyond the standard library.
"""

from __future__ import annotations

import json
import sys
from collections.abc import Callable
from typing import IO, Any

from legacy_audit import __version__
from legacy_audit.mcp.facts import Facts, FactsError
from legacy_audit.model import ENTITIES

PROTOCOL_VERSION = "2025-06-18"

TOOLS: list[dict[str, Any]] = [
    {
        "name": "query_facts",
        "description": "Rows of one fact kind from the latest scan, with exact-match filters "
        "('~text' = contains). Returns total and rows (at most 'limit').",
        "inputSchema": {
            "type": "object",
            "properties": {
                "kind": {"type": "string", "enum": list(ENTITIES)},
                "filters": {"type": "object", "additionalProperties": True},
                "limit": {"type": "integer", "minimum": 1, "maximum": 500, "default": 50},
                "fields": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["kind"],
        },
    },
    {
        "name": "get_component",
        "description": "One component (by id or name) with its services, screens, SQL, batch "
        "jobs and integrations.",
        "inputSchema": {
            "type": "object",
            "properties": {"id": {"type": "string", "description": "Component id or name"}},
            "required": ["id"],
        },
    },
    {
        "name": "get_sql",
        "description": "One SQL statement by id: text, how it was built, PostgreSQL grade.",
        "inputSchema": {
            "type": "object",
            "properties": {"id": {"type": "string"}},
            "required": ["id"],
        },
    },
    {
        "name": "read_evidence",
        "description": "Source lines around an evidence location (file as in the facts). "
        "Credentials are masked.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "file": {"type": "string"},
                "line": {"type": "integer", "minimum": 1},
                "context": {"type": "integer", "minimum": 0, "maximum": 200, "default": 20},
            },
            "required": ["file"],
        },
    },
    {
        "name": "coverage",
        "description": "Input coverage, analyzer coverage and unresolved references of the scan.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


class Server:
    def __init__(self, facts: Facts) -> None:
        self.facts = facts
        self.calls: dict[str, Callable[[dict[str, Any]], Any]] = {
            "query_facts": lambda a: facts.query_facts(
                a["kind"], a.get("filters"), a.get("limit", 50), a.get("fields")
            ),
            "get_component": lambda a: facts.get_component(a["id"]),
            "get_sql": lambda a: facts.get_sql(a["id"]),
            "read_evidence": lambda a: facts.read_evidence(
                a["file"], a.get("line"), a.get("context", 20)
            ),
            "coverage": lambda a: facts.coverage(),
        }

    def handle(self, msg: dict[str, Any]) -> dict[str, Any] | None:
        """One JSON-RPC message in; the response out (None for a notification)."""
        method, msg_id = msg.get("method"), msg.get("id")
        if msg_id is None:  # notification (e.g. notifications/initialized)
            return None
        params = msg.get("params") or {}
        if method == "initialize":
            result: Any = {
                "protocolVersion": params.get("protocolVersion", PROTOCOL_VERSION),
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "legacy-audit", "version": __version__},
                "instructions": "Read-only facts from the latest legacy-audit scan. Numbers in "
                "these results are the scanner's; cite evidence as file:line.",
            }
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            result = self._call(params.get("name", ""), params.get("arguments") or {})
        else:
            return {
                "jsonrpc": "2.0",
                "id": msg_id,
                "error": {"code": -32601, "message": f"Method not found: {method}"},
            }
        return {"jsonrpc": "2.0", "id": msg_id, "result": result}

    def _call(self, name: str, args: dict[str, Any]) -> dict[str, Any]:
        fn = self.calls.get(name)
        if fn is None:
            return _text(f"Unknown tool '{name}'", error=True)
        try:
            return _text(json.dumps(fn(args), ensure_ascii=False, default=str))
        except (FactsError, KeyError, TypeError, ValueError, OSError) as err:
            return _text(f"{type(err).__name__}: {err}", error=True)

    def serve(self, stdin: IO[str] = sys.stdin, stdout: IO[str] = sys.stdout) -> None:
        for line in stdin:
            if not line.strip():
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                reply: dict[str, Any] | None = {
                    "jsonrpc": "2.0",
                    "id": None,
                    "error": {"code": -32700, "message": "Parse error"},
                }
            else:
                reply = self.handle(msg) if isinstance(msg, dict) else None
            if reply is not None:
                stdout.write(json.dumps(reply, ensure_ascii=False) + "\n")
                stdout.flush()


def _text(text: str, error: bool = False) -> dict[str, Any]:
    return {"content": [{"type": "text", "text": text}], "isError": error}
