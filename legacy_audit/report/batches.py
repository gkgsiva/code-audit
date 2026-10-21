"""One consolidated batch list: every batch with a short description and when it runs.

A batch is a batch flow (``flows.batch_flows``): a scheduled box or job, or, when no scheduler
definition covers it, a batch component, a program or a listener. Each row says what the batch
does and when it runs, and where each of the two came from:

- *what it does*: the Copilot story's purpose (stage 12, labelled AI), else the scheduler's own
  description, else the first line of the code's header comment, else the scan's sentence;
- *when it runs*: the scheduler definition (JIL, JCL, crontab, Spring Batch), else the AI story's
  ``when``, else a hint read from the code (``@Scheduled`` or Quartz cron expressions, comments
  such as "nightly" or "runs at 02:00"), else "no schedule found" with the inputs that are
  missing.

Hints are copied from the source with their file and line, never guessed (rule 1); they are
read at report time from the scanned source, like the header comments (decision 103).
"""

from __future__ import annotations

import re
from typing import Any

from legacy_audit.report.comments import mask_secrets
from legacy_audit.report.data import RunData
from legacy_audit.report.flows import Flow, parse_schedule

MAX_FILES = 200  # files of one batch component read for schedule hints
MAX_BYTES = 512 * 1024
_HINT_LANGS = {
    "java", "xml", "properties", "yaml", "shell", "sql", "plsql", "c", "proc", "cobol",
    "typescript", "javascript",
}  # fmt: skip

_SCHEDULED = re.compile(r"@Scheduled\s*\(([^)]*)\)")
# Node.js: cron.schedule('0 2 * * *', ...), new CronJob('...'), @Cron('...'), scheduleJob('...')
_NODE_CRON = re.compile(
    r"(?:\bcron\.schedule|\bnew\s+CronJob|@Cron|\bscheduleJob)\s*\(\s*['\"`]([^'\"`]+)['\"`]"
)
_CRON_ARG = re.compile(r"cron\s*=\s*\"([^\"]+)\"")
_RATE_ARG = re.compile(r"(fixedRate|fixedDelay)\s*=\s*(\d+)")
_CRON_SETTING = re.compile(
    r"(?i)cron(?:[._-]?expression)?\w*\s*(?:[:=]|\"\s*value\s*=)\s*[\"']?"
    r"((?:[\d*?/,LW#-]+|[A-Z]{3}(?:-[A-Z]{3})?)(?:\s+(?:[\d*?/,LW#-]+|[A-Z]{3}(?:-[A-Z]{3})?)){4,6})"
)
_WORDS = re.compile(
    r"(?i)\b(nightly|daily|weekly|monthly|hourly|quarterly|yearly|annually|"
    r"every\s+(?:day|night|morning|evening|hour|week|month|weekday|working day|"
    r"\d+\s+(?:minutes?|mins?|hours?|days?))|"
    r"each\s+(?:day|night|morning|evening|month|week)|once\s+a\s+(?:day|week|month)|"
    r"end[- ]of[- ](?:day|month|year)|month[- ]end|year[- ]end|"
    r"(?:runs?|run|started|scheduled|launched)\s+at\s+\d{1,2}(?::\d{2})?\s*(?:am|pm|h)?)\b"
)
_COMMENT = re.compile(r"^\s*(#|//|/\*|\*|--|rem\b|::|\*>|//\*)", re.I)


def _cron_text(expr: str, quartz: bool = False) -> str:
    """A cron line (5 fields) or a Spring/Quartz one (6-7 fields, seconds first) in words.
    Spring numbers the days like cron (0 or 7 = Sunday), Quartz from 1 = Sunday."""
    parts = expr.split()
    if len(parts) >= 6:
        parts = parts[1:6]
        if quartz:
            parts[4] = re.sub(r"\d", lambda m: str((int(m.group()) - 1) % 7), parts[4])
    return str(parse_schedule(" ".join(parts), "cron")["text"])


def _hint_in_text(text: str, lang: str) -> tuple[str, int] | None:
    lines = text.splitlines()
    for no, line in enumerate(lines, 1):
        m = _SCHEDULED.search(line)
        if m:
            args = m.group(1)
            c = _CRON_ARG.search(args)
            if c:
                return f'{_cron_text(c.group(1))} (@Scheduled cron "{c.group(1)}")', no
            r = _RATE_ARG.search(args)
            if r:
                return f"Runs every {int(r.group(2)) // 1000} s (@Scheduled {r.group(1)})", no
        m = _NODE_CRON.search(line) if lang in ("typescript", "javascript") else None
        if m and len(m.group(1).split()) >= 5:
            return f'{_cron_text(m.group(1))} (cron "{m.group(1)}")', no
        m = _CRON_SETTING.search(line)
        if m and lang in ("xml", "properties", "yaml", "java"):
            expr = m.group(1).strip()
            return f'{_cron_text(expr, quartz=lang == "xml")} (cron "{expr}")', no
    for no, line in enumerate(lines[:200], 1):
        w = _WORDS.search(line) if _COMMENT.match(line) or lang in ("properties", "yaml") else None
        if w:
            phrase = mask_secrets(" ".join(w.group(1).split()))
            return f"{phrase[0].upper()}{phrase[1:]}, says a comment in the code", no
    return None


def schedule_hint(rd: RunData, f: Flow) -> dict[str, Any] | None:
    """The first schedule the code of a batch states: its own files first, then the rest of its
    component (configuration holds Quartz triggers). ``{text, file, line}`` or None."""
    langs = {i["path"]: i.get("language") or "" for i in rd.items("inventory")}
    paths = list(dict.fromkeys(f.files))
    if f.component:
        paths += sorted(p for p, c in rd.file_component.items() if c == f.component)
    seen: set[str] = set()
    for path in list(dict.fromkeys(paths))[:MAX_FILES]:
        lang = langs.get(path, "")
        if path in seen or lang not in _HINT_LANGS:
            continue
        seen.add(path)
        p = rd.abs_path(path)
        if p is None or not p.is_file() or p.stat().st_size > MAX_BYTES:
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        hit = _hint_in_text(text, lang)
        if hit:
            return {"text": hit[0], "file": path, "line": hit[1]}
    return None


def _what_it_runs(rd: RunData, f: Flow) -> str:
    """The scan's own short description: the command or the first steps it runs."""
    job = rd.by_id.get(f.id) or {}
    if job.get("command"):
        return f"Runs {job['command']}"
    names = [str(s.get("name")) for s in f.steps if s.get("name")]
    if names:
        more = f" and {len(names) - 3} more" if len(names) > 3 else ""
        return f"Runs {', '.join(names[:3])}{more}"
    return f.summary


def _missing_inputs(rd: RunData) -> list[str]:
    names = {"autosys": "Autosys JIL", "crontab": "crontab"}
    cov = {c.get("kind"): c.get("status") for c in rd.items("input_coverage")}
    return [label for key, label in names.items() if cov.get(key) == "missing"]


def batch_list(
    rd: RunData,
    batch_flows: list[Flow],
    said: dict[str, str],
    stories: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    """One row per batch, scheduled or not, in name order."""
    missing = _missing_inputs(rd)
    rows: list[dict[str, Any]] = []
    for f in batch_flows:
        sched = f.schedule
        scheduler = sched.get("scheduler") or ""
        story = stories.get(f.id) or {}
        if story.get("purpose"):
            what, what_from = story["purpose"], "AI (Copilot)"
        elif sched.get("description"):
            what, what_from = sched["description"], "scheduler"
        elif said.get(f.id):
            what, what_from = said[f.id], "code comment"
        else:
            what, what_from = _what_it_runs(rd, f), "scan"
        hint: dict[str, Any] | None = None
        # A JCL job or a Spring Batch job alone does not say when it runs: look further.
        scheduled = bool(sched.get("raw") or sched.get("started_by"))
        if scheduler not in ("none", "event") and scheduled:
            when, when_from = sched.get("text") or "", scheduler
        elif scheduler == "event":
            when, when_from = "On each message received (listener).", "code"
        elif story.get("when"):
            when, when_from = story["when"], "AI (Copilot)"
        elif hint := schedule_hint(rd, f):
            when, when_from = hint["text"], "code"
        else:
            gap = f" ({', '.join(missing)} not provided)" if missing else ""
            when, when_from = f"No schedule found{gap}.", "none"
        if scheduler == "none":
            kind = "batch component" if f.id.startswith("component:") else "program"
        elif scheduler == "event":
            kind = "listener"
        else:
            kind = "box" if (rd.by_id.get(f.id) or {}).get("kind") == "box" else "job"
        rows.append(
            {
                "id": f.id,
                "name": f.name,
                "slug": f.slug,
                "kind": kind,
                "scheduler": scheduler if scheduler not in ("none", "event") else "",
                "component": f.component,
                "steps": len(f.steps),
                "what": what,
                "what_from": what_from,
                "when": when,
                "when_from": when_from,
                "when_file": hint["file"] if hint else None,
                "when_line": hint["line"] if hint else None,
                "evidence": f.evidence,
            }
        )
    return sorted(rows, key=lambda r: (r["name"].lower(), r["id"]))
