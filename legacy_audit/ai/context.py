"""What a model is shown for one enrichment stage, within a character budget.

Copilot in VS Code reads the facts itself, with tools. A model called over an API cannot, so
this module packs the same material into the prompt, deterministically: the agent's rules and
the stage instructions (the very files Copilot gets), the stage's JSON Schema, the scan summary,
the fact files the stage reads (``stages.py``), earlier stages it builds on and, for the
functional and component stages, the source of every batch script and program with its
comments, the code of every desktop window and ASP.NET page (their logic is in event handlers
and code-behind) and of every web service operation the application offers. Long lists are cut
with a note saying how many rows were left out, so the model knows it saw a part. Credentials
in source text are masked.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from legacy_audit import target
from legacy_audit.copilot.install import _asset
from legacy_audit.enrich.stages import Stage, fact_file
from legacy_audit.report import flows
from legacy_audit.report.comments import mask_secrets
from legacy_audit.report.data import RunData

ROW_CHARS = 1500  # one fact row longer than this is cut
SOURCE_LINES = 150  # lines of each batch source file shown
SCREEN_LINES = 120  # lines of each window's code shown
HANDLER_LINES = 40  # lines of each web service operation shown, from its declaration
SOURCE_STAGES = {"functional", "component_docs"}
# Screens whose logic lives in the screen's own code (event handlers, code-behind): desktop
# windows and ASP.NET pages. Web pages (JSP, Angular...) reach their logic through services.
CODE_SCREENS = {"vb6", "winforms", "wpf", "swing", "awt", "javafx", "aspx"}
_VB6_ATTR = re.compile(r"^Attribute VB_", re.I)

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


def _numbered(path: Path | None, start: int, limit: int) -> list[str]:
    """Lines ``start``.. of ``path`` (1-based), numbered as in the file, secrets masked."""
    if path is None:
        return []
    lines = _read(path, start - 1 + limit)[start - 1 :]
    return [f"{i:>4}: {x}" for i, x in enumerate(lines, start)]


def _code_start(path: Path) -> int:
    """Where the code of a VB6 form or control starts: after its layout and attributes."""
    head = _read(path, 2000)
    last = max((i for i, x in enumerate(head, 1) if _VB6_ATTR.match(x)), default=0)
    return last + 1


def _screen_files(rd: RunData, scr: dict[str, Any]) -> list[str]:
    """The files holding a screen's code: the code file next to a WinForms designer file, the
    code-behind of a WPF window or ASP.NET page, else the screen's own file."""
    file = str(scr.get("file") or "")
    known = {f["path"] for f in rd.items("inventory")}
    base = re.sub(r"\.Designer(\.(vb|cs))$", r"\1", file, flags=re.I)
    out = [base] if base != file and base in known else []
    out += [f"{file}{ext}" for ext in (".cs", ".vb") if f"{file}{ext}" in known]
    return out or [file]


def _screen_sources(rd: RunData, budget: int) -> str:
    """The code of each desktop window and ASP.NET page (event handlers, code-behind): the
    business logic and database calls of these screens are written there, not in services."""
    parts: list[str] = []
    used = 0
    screens = [s for s in rd.items("screens") if s.get("technology") in CODE_SCREENS]
    for scr in sorted(screens, key=lambda s: (bool(s.get("partial")), s["name"])):
        if used >= budget:
            parts.append(f"(cut: {len(screens)} screens in all)\n")
            break
        head = f"### Screen {scr['name']} (id {scr['id']}; {scr['technology']})\n"
        body = ""
        for rel in _screen_files(rd, scr):
            path = rd.abs_path(rel)
            if path is None:
                continue
            start = _code_start(path) if rel.lower().endswith((".frm", ".ctl")) else 1
            lines = _numbered(path, start, SCREEN_LINES)
            if lines:
                body += f"#### {rel} (lines {start}-{start + len(lines) - 1})\n"
                body += "\n".join(lines) + "\n"
        block = head + body
        if used + len(block) > budget:
            block = block[: max(0, budget - used)] + "\n(cut)\n"
        parts.append(block)
        used += len(block)
    if not screens:
        return ""
    return "## Screen code (desktop windows and ASP.NET pages)\n\n" + "".join(parts)


def _handler_sources(rd: RunData, budget: int) -> str:
    """The code of each operation of the web services the application offers, from its
    declaration, so the model can say what each service does and cite the line."""
    parts: list[str] = []
    used = 0
    offered = [w for w in rd.items("web_services") if w.get("side") == "offered"]
    for ws in sorted(offered, key=lambda w: w["name"]):
        head = f"### Web service {ws['name']} (id {ws['id']}; {ws.get('style') or ''})\n"
        body = ""
        for op in ws.get("operations") or []:
            path = rd.abs_path(op["file"]) if op.get("file") else None
            lines = _numbered(path, max(1, (op.get("line") or 1) - 3), HANDLER_LINES)
            if lines:
                body += f"#### {op['name']} ({op.get('handler') or op['file']})\n"
                body += "\n".join(lines) + "\n"
        if not body:
            continue
        block = head + body
        if used + len(block) > budget:
            parts.append(block[: max(0, budget - used)] + "\n(cut)\n")
            break
        parts.append(block)
        used += len(block)
    if not parts:
        return ""
    return "## Web service code (operations offered)\n\n" + "".join(parts)


def _stage_prompt() -> str:
    text = _asset("prompts/audit-enrich.prompt.md")
    # the first line is the VS Code input line; the rest is the same guidance Copilot follows
    return re.sub(r"^Enrich the scan facts.*\n", "", text)


def build(run_dir: Path, stage: Stage, budget: int, rd: RunData | None = None) -> tuple[str, str]:
    """(system prompt, user prompt) for ``stage`` on the run in ``run_dir``."""
    system = _asset("agent.md") + "\n\n" + HEADLESS
    schema = json.dumps(stage.model.model_json_schema(), separators=(",", ":"))
    goal = target.read(run_dir)
    fixed = (
        f"# Stage {stage.number}: {stage.title} (`{stage.name}`)\n\n"
        f"Write `{stage.file}` for this stage only. Start with "
        f'`"meta": {{"stage": "{stage.name}"}}`.\n\n'
        f"**Migration target: {goal['label']}** (`data/target.json`, profile "
        f"`{goal['profile']}`). Follow the rules for this target in the agent rules.\n\n"
        f"## How to enrich (the same guidance the VS Code agent follows)\n\n{_stage_prompt()}\n\n"
        f"## JSON Schema of `{stage.file}`\n\n{schema}\n\n"
    )
    left = max(budget - len(fixed), 4000)
    data = run_dir / "data"
    run = _load(run_dir / "run.json") or {}
    summary = _load(data / "audit-summary.json") or {}
    context = {k: run.get(k) for k in ("application", "coverage", "overrides", "run_id", "target")}
    blocks = [
        _rows_block("run.json (application, input coverage, overrides)", context, left // 10),
        _rows_block("audit-summary.json", summary, left // 10),
    ]
    left -= sum(len(b) for b in blocks)
    sources = ""
    if stage.name in SOURCE_STAGES:
        rd = rd or RunData.load(run_dir)
        sources = _batch_sources(rd, left // 6)
        sources += ("\n" if sources else "") + _screen_sources(rd, left // 6)
        sources += ("\n" if sources else "") + _handler_sources(rd, left // 10)
        sources = sources.strip("\n")
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
