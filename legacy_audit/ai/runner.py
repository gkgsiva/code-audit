"""``legacy-audit enrich``: write the enrichment stages with any model, then validate them.

For each stage, in order: build the prompt (``context.py``), call the model, take the JSON out
of its answer, write ``enrichment/<stage>.json`` and run the same validator Copilot's output
goes through. When the validator finds errors, they are sent back to the model to fix
(``ai.repair_rounds`` times). A stage that still fails keeps its file, marked invalid in
``status.json``, and the run goes on; the reports show its words with a warning, as they do for
Copilot. The model only writes words: every number and id it uses is checked against the scan.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from legacy_audit.ai import context
from legacy_audit.ai.providers import AiError, Client, Settings, extract_json
from legacy_audit.enrich import status as st
from legacy_audit.enrich.stages import BY_NAME, Stage
from legacy_audit.enrich.validate import validate_run
from legacy_audit.report.data import RunData
from legacy_audit.store.writer import write_json

log = logging.getLogger(__name__)


@dataclass
class StageResult:
    stage: str
    ok: bool
    rounds: int = 0
    errors: list[str] = field(default_factory=list)
    warnings: int = 0


def pick_stages(run_dir: Path, names: list[str] | None, stale: bool) -> list[Stage]:
    """Stages to run, in enrichment order: the named ones, the stale and missing ones, or all."""
    active = st.active_stages(run_dir)
    if names:
        unknown = sorted(set(names) - set(BY_NAME))
        if unknown:
            raise AiError(f"No such stage: {', '.join(unknown)}")
        skipped = sorted(set(names) - {s.name for s in active})
        if skipped:
            raise AiError(f"Not needed when the target is none (audit only): {', '.join(skipped)}")
        return [s for s in active if s.name in names]
    if stale:
        status = st.compute_status(run_dir)["stages"]
        return [s for s in active if not status[s.name]["present"] or status[s.name].get("stale")]
    return active


def _write(run_dir: Path, stage: Stage, data: dict[str, Any]) -> None:
    for root in (run_dir, st.latest_copy(run_dir)):
        if root is not None:
            (root / "enrichment").mkdir(parents=True, exist_ok=True)
            write_json(root / "enrichment" / stage.file, data)


def run_stage(
    run_dir: Path, stage: Stage, client: Client, s: Settings, rd: RunData | None = None
) -> StageResult:
    system, user = context.build(run_dir, stage, s.context_chars, rd)
    result = StageResult(stage.name, ok=False)
    prompt = user
    for attempt in range(s.repair_rounds + 1):
        result.rounds = attempt + 1
        answer = client.complete(system, prompt)
        try:
            data = extract_json(answer)
        except AiError as err:
            result.errors = [str(err)]
            prompt = user + "\n\n" + context.repair_prompt(answer[:4000], result.errors)
            continue
        if not isinstance(data, dict):
            result.errors = ["the answer is not a JSON object"]
            prompt = user + "\n\n" + context.repair_prompt(answer[:4000], result.errors)
            continue
        raw = data.get("meta")
        meta = {k: v for k, v in raw.items() if k != "built_from"} if isinstance(raw, dict) else {}
        data["meta"] = {**meta, "stage": stage.name, "author": s.label}
        _write(run_dir, stage, data)
        rep = validate_run(run_dir, [stage.name], restamp=True)
        result.errors = [str(m) for m in rep.errors]
        result.warnings = len(rep.warnings)
        if not result.errors:
            result.ok = True
            return result
        log.info("%s: %d error(s), asking %s to fix them", stage.name, len(result.errors), s.label)
        prompt = user + "\n\n" + context.repair_prompt(answer, result.errors)
    return result


def enrich(
    run_dir: Path,
    s: Settings,
    stages: list[Stage],
    client: Client | None = None,
    progress: Callable[[str], None] = lambda _: None,
) -> list[StageResult]:
    client = client or Client(s)
    st.sync_from_latest(run_dir)
    rd = RunData.load(run_dir) if any(x.name in context.SOURCE_STAGES for x in stages) else None
    results: list[StageResult] = []
    for stage in stages:
        progress(f"Stage {stage.number} {stage.name}: asking {s.label}...")
        try:
            res = run_stage(run_dir, stage, client, s, rd)
        except AiError as err:
            res = StageResult(stage.name, ok=False, errors=[str(err)])
            results.append(res)
            progress(f"Stage {stage.number} {stage.name}: stopped: {err}")
            break  # the endpoint itself failed (key, network, model): later stages would too
        results.append(res)
        state = "valid" if res.ok else f"{len(res.errors)} error(s) left"
        progress(f"Stage {stage.number} {stage.name}: {state} after {res.rounds} round(s)")
    return results


def check(s: Settings, client: Client | None = None) -> str:
    """Send a tiny request, to test the provider, model, key and network settings."""
    client = client or Client(s)
    answer = client.complete(
        "You answer with JSON only.", 'Reply with exactly this JSON object: {"ok": true}'
    )
    data = extract_json(answer)
    if not (isinstance(data, dict) and data.get("ok") is True):
        raise AiError(f"{s.label} answered, but not as asked: {answer[:200]}")
    return f"{s.label} answered correctly."
