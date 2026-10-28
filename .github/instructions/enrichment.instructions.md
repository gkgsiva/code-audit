---
applyTo: "legacy_audit/enrich/**,legacy_audit/ai/**"
---

# Enrichment (AI) and its validation

- The model only writes words. Every figure it writes must equal the scan's value at its
  `metric` path (or carry `estimate: true`), every id must exist in the facts, every evidence
  file and line must exist (`enrich/validate.py`). Do not weaken these checks.
- A stage = a pydantic model in `enrich/models.py` + an entry in `enrich/stages.py` `STAGES`
  whose `inputs` (fact files) and `after` (upstream stages) decide staleness: list only what
  the stage really reads. Stages that suggest a target belong in `target.TARGET_STAGES`.
- After changing a model: `python -m legacy_audit.model.schemas` (enrichment schemas are
  installed into audited workspaces too).
- `legacy-audit enrich` (`ai/`) uses only the standard library for HTTP. Keys come from the
  environment variable named in `ai.api_key_env` (or the provider default), never from the
  config file or logs. Support for a corporate CA bundle (`ai.ca_bundle`) must keep working.
- Keep the Copilot prompts (`legacy_audit/copilot/`) and `ai/context.py` consistent: both
  writers must produce the same stage files.
- Tests: `tests/test_enrich.py`, `tests/test_enrich_new_stacks.py`, `tests/test_ai.py` (fake
  model servers, no network). Demo enrichment that passes validation is in
  `tests/fixtures/enrichment/`.
