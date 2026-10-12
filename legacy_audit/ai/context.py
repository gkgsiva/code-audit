"""What a model is shown for one enrichment stage, within a character budget.

Copilot in VS Code reads the facts itself, with tools. A model called over an API cannot, so
this module packs the same material into the prompt, deterministically: the agent's rules and
the stage instructions (the very files Copilot gets), the stage's JSON Schema, the scan summary,
the fact files the stage reads (``stages.py``), earlier stages it builds on and, for the
functional and component stages, the source of every batch script and program with its
comments. Long lists are cut with a note saying how many rows were left out, so the model knows
it saw a part. Credentials in source text are masked.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from legacy_audit.copilot.install import _asset
from legacy_audit.enrich.stages import Stage, fact_file
from legacy_audit.report import flows
from legacy_audit.report.comments import mask_secrets
from legacy_audit.report.data import RunData

ROW_CHARS = 1500  # one fact row longer than this is cut
SOURCE_LINES = 150  # lines of each batch source file shown
SOURCE_STAGES = {"functional", "component_docs"}

HEADLESS = """
## Working without tools

You are called through an API by `legacy-audit enrich`, not in VS Code: you cannot run commands,
open files or ask questions. Everything you may use is in the message below (scan summary, fact
files, earlier stages and, where useful, source excerpts). Where a list says rows were left out,
do not guess what they hold.

Answer with ONE JSON object for the requested stage, matching the JSON Schema given, and nothing
else (no Markdown fences, no comments). Leave `meta.built_from` out. The tool validates your
answer and, if it finds errors, sends them back for you to fix. When you would have stopped to
ask (rule 6), record the question as an assumption or open question in the JSON instead.
""".strip()


def _compact(row: Any) -> str:
    if isinstance(row, dict):
        row = {k: v for k, v in row.items() if v not in (None, "", [], {})}
    text = json.dumps(row, ensure_ascii=False, separators=(",", ":"), default=str)
    return text if len(text) <= ROW_CHARS else text[: ROW_CHARS - 12] + '..."(cut)"'


def _rows_block(title: str, data: Any, budget: int) -> str:
    """A fact file as one compact JSON row per line, cut at ``budget`` characters."""
    if isinstance(data, dict) and not isinstance(data.get("items"), list):
        text = json.dumps(data, ensure_ascii=False, separators=(",", ":"), default=str)
        cut = "" if len(text) <= budget else " (cut)"
        return f"### {title}{cut}\n{text[:budget]}\n"
    rows = data["items"] if isinstance(data, dict) else list(data or [])
    out: list[str] = []
    used = 0
    for row in rows:
        line = _compact(row)
        if used + len(line) > budget and out:
            break
        out.append(line)
        used += len(line) + 1
    note = (
        "" if len(out) == len(rows) else f" ({len(rows) - len(out)} of {len(rows)} rows left out)"
    )
    return f"### {title}: {len(rows)} rows{note}\n" + "\n".join(out) + "\n"


def _read(path: Path, limit: int) -> list[str]:
    try:
        with path.open(encoding="utf-8", errors="replace") as fh:
            lines = [next(fh, None) for _ in range(limit)]
    except OSError:
        return []
    return [mask_secrets(x.rstrip("\n")) for x in lines if x is not None]


def _load(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _batch_sources(rd: RunData, budget: int) -> str:
    """Each batch (as on the Flows page) with its steps, then the start of every source file it
    runs, numbered, so the model can read comments and cite lines."""
    parts: list[str] = []
    seen: set[str] = set()
    used = 0
    batches = flows.batch_flows(rd)
    for f in batches:
        head = (
            f"### Batch {f.name} (id {f.id}; {f.schedule.get('text') or 'no schedule found'})\n"
            f"steps: {', '.join(s['name'] for s in f.steps)}\n"
        )
        parts.append(head)
        used += len(head)
        for rel in f.files:
            if rel in seen or used >= budget:
                continue
            seen.add(rel)
            path = rd.abs_path(rel)
            lines = _read(path, SOURCE_LINES) if path else []
            if not lines:
                continue
            body = "\n".join(f"{i:>4}: {x}" for i, x in enumerate(lines, 1))
            block = f"#### {rel} (first {len(lines)} lines)\n{body}\n"
            if used + len(block) > budget:
                block = block[: max(0, budget - used)] + "\n(cut)\n"
            parts.append(block)
            used += len(block)
    if not batches:
        return ""
    return "## Batch sources (comments included)\n\n" + "".join(parts)


def _stage_prompt() -> str:
    text = _asset("prompts/audit-enrich.prompt.md")
    # the first line is the VS Code input line; the rest is the same guidance Copilot follows
    return re.sub(r"^Enrich the scan facts.*\n", "", text)


def build(run_dir: Path, stage: Stage, budget: int, rd: RunData | None = None) -> tuple[str, str]:
    """(system prompt, user prompt) for ``stage`` on the run in ``run_dir``."""
    system = _asset("agent.md") + "\n\n" + HEADLESS
    schema = json.dumps(stage.model.model_json_schema(), separators=(",", ":"))
    fixed = (
        f"# Stage {stage.number}: {stage.title} (`{stage.name}`)\n\n"
        f"Write `{stage.file}` for this stage only. Start with "
        f'`"meta": {{"stage": "{stage.name}"}}`.\n\n'
        f"## How to enrich (the same guidance the VS Code agent follows)\n\n{_stage_prompt()}\n\n"
        f"## JSON Schema of `{stage.file}`\n\n{schema}\n\n"
    )
    left = max(budget - len(fixed), 4000)
    data = run_dir / "data"
    run = _load(run_dir / "run.json") or {}
    summary = _load(data / "audit-summary.json") or {}
    context = {k: run.get(k) for k in ("application", "coverage", "overrides", "run_id")}
    blocks = [
        _rows_block("run.json (application, input coverage, overrides)", context, left // 10),
        _rows_block("audit-summary.json", summary, left // 10),
    ]
    left -= sum(len(b) for b in blocks)
    sources = ""
    if stage.name in SOURCE_STAGES:
        rd = rd or RunData.load(run_dir)
        sources = _batch_sources(rd, left // 4)
        left -= len(sources)
    upstream = [(n, run_dir / "enrichment" / f"{n}.json") for n in stage.after]
    upstream = [(n, p) for n, p in upstream if p.exists()]
    if upstream:
        each = (left // 5) // len(upstream)
        for name, path in upstream:
            blocks.append(_rows_block(f"Earlier stage {name}.json", _load(path), each))
        left -= sum(len(b) for b in blocks[-len(upstream) :])
    kinds = [k for k in stage.inputs if (data / fact_file(k)).exists()]
    for kind in kinds:
        each = max(left // max(len(kinds), 1), 2000)
        blocks.append(_rows_block(f"data/{fact_file(kind)}", _load(data / fact_file(kind)), each))
    user = fixed + "## Scan facts\n\n" + "\n".join(blocks) + ("\n" + sources if sources else "")
    return system, user


def repair_prompt(answer: str, errors: list[str]) -> str:
    listed = "\n".join(f"- {e}" for e in errors[:60])
    return (
        "The validator rejected your JSON with these errors:\n\n"
        f"{listed}\n\nHere is your previous answer:\n\n{answer}\n\n"
        "Answer again with the whole corrected JSON object, and nothing else."
    )
