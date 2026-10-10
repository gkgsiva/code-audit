"""Autosys JIL (``audit-inputs/autosys/`` and any ``.jil``): ``insert_job`` / ``update_job`` /
``delete_job`` with ``job_type``, ``box_name``, ``command``, ``machine``, ``condition``,
``start_times``, ``days_of_week``, ``run_calendar``, ``date_conditions`` and ``watch_file``.

Every job is a ``BatchJob`` (scheduler ``autosys``); a ``BOX`` is a container (kind ``box``,
not counted as a job); a file watcher (``FW``) is kind ``file-watcher`` and its watched file an
inbound ``FILE`` integration. A job's command is read like a script line, so it links to the
scripts, programs, jars and Spring Batch jobs it runs. Condition predecessors become
``depends-on`` references between jobs.
"""

from __future__ import annotations

import re
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.integrations import integration
from legacy_audit.analyzers.shell import command_facts
from legacy_audit.model import BatchJob, Evidence, FileRecord, make_id

# fmt: off
JIL_ATTRS = (
    "insert_job", "update_job", "delete_job", "override_job", "delete_box", "job_type",
    "box_name", "command", "machine", "owner", "permission", "date_conditions", "days_of_week",
    "start_times", "start_mins", "run_calendar", "exclude_calendar", "run_window", "condition",
    "description", "std_in_file", "std_out_file", "std_err_file", "alarm_if_fail",
    "alarm_if_terminated", "max_run_alarm", "min_run_alarm", "term_run_time", "n_retrys",
    "watch_file", "watch_interval", "watch_file_min_size", "profile", "timezone", "box_success",
    "box_failure", "box_terminator", "job_terminator", "job_load", "priority", "group",
    "application", "auto_delete", "auto_hold", "chk_files", "heartbeat_interval",
    "max_exit_success", "must_complete_times", "must_start_times", "send_notification",
    "notification_msg", "notification_id", "resources", "fail_codes", "success_codes", "envvars",
    "net_relationship", "ulimit", "avg_runtime",
)
# fmt: on
_ATTR = re.compile(r"(?:^|\s)(" + "|".join(JIL_ATTRS) + r"):\s*", re.I)
_CONDITION = re.compile(
    r"\b(s|success|f|failure|d|done|n|notrunning|t|terminated|e|exitcode)\s*\(\s*([^,)\s]+)", re.I
)
_JOB_TYPES = {"c": "CMD", "cmd": "CMD", "b": "BOX", "box": "BOX", "f": "FW", "fw": "FW"}
_SCHEDULE_ATTRS = (
    "date_conditions",
    "days_of_week",
    "start_times",
    "start_mins",
    "run_calendar",
    "exclude_calendar",
    "run_window",
    "timezone",
)


def parse_jil(
    text: str,
) -> tuple[list[tuple[int, str, str, dict[str, tuple[str, int]]]], list[int]]:
    """Return ([(line, verb, job, {attr: (value, line)})], unparsed line numbers)."""
    jobs: list[tuple[int, str, str, dict[str, tuple[str, int]]]] = []
    unparsed: list[int] = []
    text = re.sub(r"/\*[\s\S]*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), text)
    current: dict[str, tuple[str, int]] | None = None
    for no, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        # Several "attr: value" pairs may share a line: split on attribute names.
        starts = [m for m in _ATTR.finditer(line)]
        if not starts or (starts[0].start() > 0 and line[: starts[0].start()].strip()):
            unparsed.append(no)
            continue
        for k, m in enumerate(starts):
            end = starts[k + 1].start() if k + 1 < len(starts) else len(line)
            key = m.group(1).lower()
            value = line[m.end() : end].strip().strip('"')
            if key in ("insert_job", "update_job", "delete_job", "override_job"):
                current = {}
                jobs.append((no, key, value.split()[0] if value else "", current))
            elif current is not None:
                current[key] = (value, no)
            else:
                unparsed.append(no)
    return jobs, unparsed


def predecessors(condition: str) -> list[str]:
    out: list[str] = []
    for m in _CONDITION.finditer(condition):
        name = m.group(2).split("^", 1)[0]
        if name not in out:
            out.append(name)
    return out


class AutosysAnalyzer:
    name: ClassVar[str] = "autosys"
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        return file.language == "jil" or (
            file.path.lower().startswith("audit-inputs/autosys/") and file.category != "binary"
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        path = file.path
        facts.coverage.lines = text.count("\n") + 1
        parsed, unparsed = parse_jil(text)
        for no in unparsed:
            facts.coverage.gap(no, no, "not a JIL statement")
        jobs: dict[str, tuple[int, dict[str, tuple[str, int]]]] = {}
        for line, verb, name, attrs in parsed:
            if verb == "delete_job":
                jobs.pop(name, None)
            elif verb in ("update_job", "override_job") and name in jobs:
                jobs[name][1].update(attrs)
            else:
                jobs[name] = (line, dict(attrs))
        if jobs:
            facts.add(
                "tech_stack", tech("Autosys", None, "scheduler", path, next(iter(jobs.values()))[0])
            )
        for name, (line, attrs) in jobs.items():
            self._job(path, name, line, attrs, ctx.config.sql.dialect, facts)
        return facts

    def _job(
        self,
        path: str,
        name: str,
        line: int,
        attrs: dict[str, tuple[str, int]],
        dialect: str,
        facts: Facts,
    ) -> None:
        def get(key: str) -> str | None:
            return attrs[key][0] if key in attrs else None

        jtype = _JOB_TYPES.get(
            (get("job_type") or "CMD").lower(), (get("job_type") or "CMD").upper()
        )
        kind = {"BOX": "box", "FW": "file-watcher"}.get(jtype, "job")
        condition = get("condition")
        preds = predecessors(condition) if condition else []
        schedule = "; ".join(f"{k}: {get(k)}" for k in _SCHEDULE_ATTRS if get(k)) or None
        jid = make_id("batch_job", "autosys", name)
        job = BatchJob(
            id=jid,
            name=name,
            scheduler="autosys",
            kind=kind,
            schedule=schedule,
            box=get("box_name"),
            command=get("command"),
            machine=get("machine"),
            condition=condition,
            predecessors=preds,
            evidence=[
                Evidence(file=path, line=line, snippet=f"insert_job: {name} job_type: {jtype}")
            ],
        )
        facts.add("batch_jobs", job)
        facts.symbol("autosys-job", name, path, line, owner=jid, data={"kind": kind})
        src = f"batch_job:{jid}"
        if job.box:
            facts.ref(
                kind="contains",
                source=src,
                source_label=name,
                target=job.box,
                target_kinds=["autosys-job"],
                file=path,
                line=attrs["box_name"][1],
                data={"reverse": "true"},
            )
        for pred in preds:
            facts.ref(
                kind="depends-on",
                source=src,
                source_label=name,
                target=pred,
                target_kinds=["autosys-job"],
                file=path,
                line=attrs["condition"][1],
            )
        if job.command:
            cline = attrs["command"][1]
            sub = command_facts(
                job.command, path=path, line=cline, dialect=dialect, source=src, source_label=name
            )
            facts.extend(sub)
        watch = get("watch_file")
        if kind == "file-watcher" and watch:
            facts.add(
                "integrations",
                integration(
                    type="FILE",
                    target=watch,
                    direction="inbound",
                    file=path,
                    line=attrs["watch_file"][1],
                    protocol="autosys-fw",
                    details={"job": name},
                ),
            )
