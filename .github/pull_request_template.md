## What and why

<!-- What changes for the user or the code, and why (who asked, which problem). -->

## How it works

<!-- The modules changed and the approach. Name decisions added to docs/decisions.md. -->

## How to validate

<!-- The VS Code task and the section of docs/validate-in-vscode.md, or the commands to run. -->

## Checklist

- [ ] Tests added or updated; `pytest` passes
- [ ] `ruff check .`, `ruff format --check .` and `mypy legacy_audit` pass
- [ ] Analyzer `version` bumped if its output changed (and `INVENTORY_VERSION` if the per-file record changed)
- [ ] Sample counts unchanged, or `expected.yaml` updated after checking by hand (explained above)
- [ ] `schemas/` regenerated if a model changed (`python -m legacy_audit.model.schemas`)
- [ ] `docs/developer/reference/` regenerated if code structure, CLI, config, entities, routes or tasks changed (`python scripts/gen_dev_reference.py`)
- [ ] `docs/decisions.md`, `docs/progress.md` and `CHANGELOG.md` updated
- [ ] User-visible change: VS Code task, `docs/validate-in-vscode.md` section, user guides
- [ ] Works on Windows and WSL2 (paths, encodings, commands)
- [ ] After merging, analysts need to: <!-- nothing / pip install -e . / legacy-audit init / rescan / /audit-enrich stale -->
