import json

from legacy_audit.model import ENTITIES, make_id
from legacy_audit.model.schemas import SCHEMA_DIR, build_schemas, render


def test_schema_files_match_models():
    """Fails when a model changed without re-running `python -m legacy_audit.model.schemas`."""
    built = build_schemas()
    on_disk = {p.name.removesuffix(".schema.json") for p in SCHEMA_DIR.glob("*.schema.json")}
    assert on_disk == set(built), "schema files added or removed"
    for name, schema in built.items():
        text = (SCHEMA_DIR / f"{name}.schema.json").read_text(encoding="utf-8")
        assert text == render(schema), f"{name}.schema.json drifted from the model"


def test_every_entity_has_id_and_evidence():
    for model in ENTITIES.values():
        assert {"id", "evidence"} <= set(model.model_fields)
        schema = model.model_json_schema()
        assert "id" in schema["required"]
        json.dumps(schema)


def test_make_id_deterministic():
    assert make_id("file", "r", "a/b") == make_id("file", "r", "a/b")
    assert make_id("file", "r", "a/b") != make_id("file", "r", "a/c")
    assert make_id("file", "r", "a/b").startswith("file:")
