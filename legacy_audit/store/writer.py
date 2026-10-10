"""Write facts as JSON (source of truth), CSV per entity and one SQLite database."""

from __future__ import annotations

import csv
import json
import sqlite3
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from legacy_audit.model import ENTITIES, Fact

FactSet = dict[str, Sequence[Fact]]


def _dump(items: Sequence[Fact]) -> list[dict[str, Any]]:
    return [i.model_dump(mode="json") for i in items]


def _csv_cell(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, list) and all(isinstance(v, dict) for v in value) and value:
        if all("file" in v for v in value):  # evidence
            return ";".join(
                f"{v['file']}:{v['line']}" if v.get("line") else v["file"] for v in value
            )
        return json.dumps(value, sort_keys=True)
    if isinstance(value, list):
        return ";".join(str(v) for v in value)
    if isinstance(value, dict):
        return json.dumps(value, sort_keys=True)
    return str(value)


def write_json(path: Path, data: Any) -> None:
    path.write_text(
        json.dumps(data, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def write_facts(data_dir: Path, facts: FactSet) -> None:
    data_dir.mkdir(parents=True, exist_ok=True)
    db_path = data_dir / "facts.sqlite"
    if db_path.exists():
        db_path.unlink()
    conn = sqlite3.connect(db_path)
    try:
        for entity, model in ENTITIES.items():
            rows = _dump(facts.get(entity, []))
            write_json(data_dir / f"{entity}.json", rows)
            columns = list(model.model_fields)
            with (data_dir / f"{entity}.csv").open("w", encoding="utf-8", newline="") as fh:
                w = csv.writer(fh)
                w.writerow(columns)
                for row in rows:
                    w.writerow([_csv_cell(row.get(c)) for c in columns])
            cols_sql = ", ".join(f'"{c}"' for c in columns)
            conn.execute(
                f'CREATE TABLE "{entity}" ({cols_sql}, PRIMARY KEY ("id"))'
                if "id" in columns
                else f'CREATE TABLE "{entity}" ({cols_sql})'
            )
            placeholders = ", ".join("?" for _ in columns)
            conn.executemany(
                f'INSERT INTO "{entity}" VALUES ({placeholders})',
                [[_sql_value(row.get(c)) for c in columns] for row in rows],
            )
        conn.commit()
    finally:
        conn.close()


def _sql_value(value: Any) -> Any:
    if isinstance(value, list | dict):
        return json.dumps(value, sort_keys=True)
    return value


def read_facts(data_dir: Path) -> dict[str, list[dict[str, Any]]]:
    out: dict[str, list[dict[str, Any]]] = {}
    for entity in ENTITIES:
        p = data_dir / f"{entity}.json"
        out[entity] = json.loads(p.read_text(encoding="utf-8")) if p.exists() else []
    return out
