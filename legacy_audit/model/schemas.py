"""Export the fact and enrichment models as JSON Schema files (``schemas/facts/``,
``schemas/enrichment/``).

Run ``python -m legacy_audit.model.schemas`` after changing a model; a test fails if the
committed files drift from the models.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from legacy_audit.model.facts import SCHEMA_MODELS

SCHEMA_DIR = Path(__file__).resolve().parents[2] / "schemas" / "facts"
ENRICH_SCHEMA_DIR = SCHEMA_DIR.parent / "enrichment"


def build_schemas() -> dict[str, dict[str, Any]]:
    out: dict[str, dict[str, Any]] = {}
    for name, model in SCHEMA_MODELS.items():
        schema = model.model_json_schema()
        schema["$schema"] = "https://json-schema.org/draft/2020-12/schema"
        schema["$id"] = f"legacy-audit/facts/{name}.schema.json"
        out[name] = schema
    return out


def build_enrichment_schemas() -> dict[str, dict[str, Any]]:
    from legacy_audit.enrich.stages import STAGES

    out: dict[str, dict[str, Any]] = {}
    for stage in STAGES:
        schema = stage.model.model_json_schema()
        schema["$schema"] = "https://json-schema.org/draft/2020-12/schema"
        schema["$id"] = f"legacy-audit/enrichment/{stage.name}.schema.json"
        out[stage.name] = schema
    return out


def render(schema: dict[str, Any]) -> str:
    return json.dumps(schema, indent=2, sort_keys=True) + "\n"


def export(target: Path = SCHEMA_DIR) -> list[Path]:
    written = []
    for folder, schemas in (
        (target, build_schemas()),
        (target.parent / "enrichment", build_enrichment_schemas()),
    ):
        folder.mkdir(parents=True, exist_ok=True)
        for name, schema in schemas.items():
            path = folder / f"{name}.schema.json"
            path.write_text(render(schema), encoding="utf-8", newline="\n")
            written.append(path)
    return written


if __name__ == "__main__":
    for p in export(Path(sys.argv[1]) if len(sys.argv) > 1 else SCHEMA_DIR):
        print(p)
