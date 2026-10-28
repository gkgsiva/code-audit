---
applyTo: "legacy_audit/model/**,legacy_audit/config.py,legacy_audit/store/**,schemas/**"
---

# Fact model, configuration and store

- Facts are pydantic v2 models deriving from `Fact` (`id`, `evidence`) with
  `extra="forbid"`. A new field has a default and a `Field(description=...)`. A new entity
  goes in `ENTITIES` and `SCHEMA_MODELS` (`model/facts.py`), in `model/__init__.py`, and in
  the `ENTITIES` tuple of `report/data.py`.
- After any model change run `python -m legacy_audit.model.schemas`; never edit `schemas/`
  by hand.
- Config models in `config.py` also use `extra="forbid"`; new keys have a default (old
  `audit.yaml` files must keep loading) and a description, and are documented in
  `audit.example.yaml`. If a key changes per-file analysis results, add it to the cache key in
  `scan.py` (`cache_version`).
- `store/writer.py` writes every entity as JSON (source of truth), CSV and a SQLite table
  from the model fields; lists and objects are JSON text in SQLite.
- Headline counts live only in `store/counts.py`; their definitions are in
  `config.DEFAULT_COUNTING_RULES` and `docs/plan.md` § Counting rules.
- Regenerate `docs/developer/reference/` after changes (`python scripts/gen_dev_reference.py`).
