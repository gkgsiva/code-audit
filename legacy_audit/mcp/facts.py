"""Read-only queries over a run: ``facts.sqlite``, ``run.json`` and evidence lines in the source.

Shared by the MCP server and ``legacy-audit facts``. Nothing here writes; SQLite is opened
read-only, and ``read_evidence`` only reads files inside the scanned source roots or the
``audit-inputs`` folder, with credentials masked.
"""

from __future__ import annotations

import json
import re
import sqlite3
from collections.abc import Callable
from pathlib import Path
from typing import Any

from legacy_audit.model import ENTITIES
from legacy_audit.report.data import RunData

MAX_LIMIT = 500
SECRET_LINE = re.compile(
    r"^(\s*[\w.-]*(?:passw(?:or)?d|pwd|passphrase|secret|api[_-]?key|token|credential)[\w.-]*"
    r"\s*[:=]\s*)(\S.*)$",
    re.I | re.M,
)


class FactsError(Exception):
    pass


def _cell(value: Any) -> Any:
    if isinstance(value, str) and value[:1] in "[{":
        try:
            return json.loads(value)
        except ValueError:
            return value
    return value


class Facts:
    """One run, resolved on each call through ``run_dir()`` so a re-scan is picked up."""

    def __init__(self, run_dir: Callable[[], Path]) -> None:
        self._run_dir = run_dir

    def _db(self) -> sqlite3.Connection:
        path = self._run_dir() / "data" / "facts.sqlite"
        if not path.exists():
            raise FactsError(f"No facts database at {path}; run 'legacy-audit scan' first")
        conn = sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True)
        conn.row_factory = sqlite3.Row
        return conn

    def query_facts(
        self,
        kind: str,
        filters: dict[str, Any] | None = None,
        limit: int = 50,
        fields: list[str] | None = None,
        *,
        offset: int = 0,
        search: str | None = None,
    ) -> dict[str, Any]:
        """Rows of one entity. A filter value matches a column exactly; ``~text`` matches rows
        whose column contains the text (for list columns such as ``tables``). ``search`` keeps
        rows where any column contains the text; ``offset`` pages through the rest."""
        if kind not in ENTITIES:
            raise FactsError(f"Unknown kind '{kind}'; one of: {', '.join(ENTITIES)}")
        columns = list(ENTITIES[kind].model_fields)
        for name in [*(filters or {}), *(fields or [])]:
            if name not in columns:
                raise FactsError(f"'{name}' is not a field of {kind}: {', '.join(columns)}")
        where, args = [], []
        for col, value in (filters or {}).items():
            if isinstance(value, str) and value.startswith("~"):
                where.append(f'"{col}" LIKE ?')
                args.append(f"%{value[1:]}%")
            else:
                where.append(f'"{col}" = ?')
                args.append(value)
        if search:
            where.append("(" + " OR ".join(f'"{c}" LIKE ?' for c in columns) + ")")
            args += [f"%{search}%"] * len(columns)
        sql_where = f" WHERE {' AND '.join(where)}" if where else ""
        select = ", ".join(f'"{c}"' for c in fields) if fields else "*"
        limit = max(1, min(int(limit), MAX_LIMIT))
        offset = max(0, int(offset))
        with self._db() as conn:
            total = conn.execute(f'SELECT COUNT(*) FROM "{kind}"{sql_where}', args).fetchone()[0]
            rows = conn.execute(
                f'SELECT {select} FROM "{kind}"{sql_where} LIMIT {limit} OFFSET {offset}', args
            ).fetchall()
        return {
            "kind": kind,
            "total": total,
            "offset": offset,
            "returned": len(rows),
            "rows": [{k: _cell(v) for k, v in dict(r).items()} for r in rows],
        }

    def _one(self, kind: str, key: str, value: str) -> dict[str, Any] | None:
        rows = self.query_facts(kind, {key: value}, limit=1)["rows"]
        return rows[0] if rows else None

    def get_component(self, ref: str) -> dict[str, Any]:
        comp = self._one("components", "id", ref) or self._one("components", "name", ref)
        if comp is None:
            raise FactsError(f"No component with id or name '{ref}'")
        name = comp["name"]
        out: dict[str, Any] = {"component": comp}
        for kind, fields in (
            ("services", ["id", "name", "kind", "complexity"]),
            ("screens", ["id", "name", "partial", "complexity"]),
            ("sql_statements", ["id", "type", "pg_grade", "file", "line"]),
            ("batch_jobs", ["id", "name", "scheduler"]),
        ):
            out[kind] = self.query_facts(kind, {"component": name}, limit=100, fields=fields)
        out["integrations"] = self.query_facts(
            "integrations", {"components": f"~{name}"}, 100, ["id", "type", "target", "direction"]
        )
        return out

    def get_sql(self, sql_id: str) -> dict[str, Any]:
        row = self._one("sql_statements", "id", sql_id)
        if row is None:
            raise FactsError(f"No SQL statement '{sql_id}'")
        return row

    def read_evidence(
        self, file: str, line: int | None = None, context: int = 20
    ) -> dict[str, Any]:
        rd = RunData.load(self._run_dir())
        path = rd.abs_path(file)
        allowed = [p.resolve() for p in rd.roots.values()]
        if path is None or not path.is_file():
            raise FactsError(f"'{file}' is not a file of the scanned source")
        real = path.resolve()
        if not any(real.is_relative_to(root) for root in allowed):
            raise FactsError(f"'{file}' is outside the scanned source")
        lines = real.read_text(encoding="utf-8", errors="replace").splitlines()
        context = max(0, min(int(context), 200))
        start = 1 if line is None else max(1, line - context)
        end = min(len(lines), (line or 1) + context if line is not None else 2 * context + 1)
        text = "\n".join(lines[start - 1 : end])
        return {
            "file": file,
            "line": line,
            "start": start,
            "end": end,
            "text": SECRET_LINE.sub(lambda m: m.group(1) + "****", text),
        }

    def coverage(self) -> dict[str, Any]:
        run = json.loads((self._run_dir() / "run.json").read_text(encoding="utf-8"))
        return {
            "run_id": run.get("run_id"),
            "inputs": run.get("coverage", []),
            "analyzers": run.get("analyzers", {}),
            "unresolved_references": run.get("unresolved_references", []),
            "vulnerability_sources": run.get("vulnerability_sources", []),
        }
