"""``legacy-audit validate``: check the agent's enrichment against the scan.

Per stage file, in order:

1. JSON Schema (the pydantic model): shape, enums, and every claim citing evidence or saying
   ``assumption: true``.
2. References: ids in ``*_id`` / ``*_ids`` fields exist in the facts; component names exist.
3. Numbers come from facts: every figure's value equals the scan's value at its ``metric`` path,
   unless marked ``estimate: true``. Counts written in prose ("12 services") that differ from the
   scan are reported as warnings, since prose may talk about a subset.
4. Evidence: each cited file exists in the scanned source and the line is inside it.

A stage with no errors gets ``meta.built_from`` stamped (the hashes it was built from), then
``enrichment/status.json`` is rewritten.
"""

from __future__ import annotations

import json
import re
from collections.abc import Iterator
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from pydantic import ValidationError

from legacy_audit.enrich import status as st
from legacy_audit.enrich.stages import BY_NAME, STAGES, Stage
from legacy_audit.report.data import RunData

# prose noun -> summary path of the count it must agree with
PROSE_COUNTS = {
    "services": "counts.services.total",
    "screens": "counts.screens.total",
    "sql statements": "counts.sql.total",
    "integrations": "counts.integrations.total",
    "web services": "counts.web_services.total",
    "batch jobs": "counts.batch_jobs.total",
    "libraries": "counts.libraries.total",
    "vulnerable libraries": "counts.vulnerable_libraries.total",
    "components": "totals.components",
    "findings": "totals.findings",
    "stored procedures": "counts.stored_procedures",
}
_PROSE = re.compile(
    r"(?<![\w.,])(\d[\d,]*)\s+(" + "|".join(sorted(PROSE_COUNTS, key=len, reverse=True)) + r")\b",
    re.I,
)
_HEDGE = re.compile(
    r"(about|around|approximately|roughly|~|over|under|up to|at least|"
    r"some|of the|of these|of them|out of|estimated?)\s*$",
    re.I,
)
# "Angular 21 components", "Java 25 services": a product version, not a count
_VERSIONED = re.compile(
    r"\b(Angular|AngularJS|Java|JDK|Spring Boot|Spring|Node\.js|Node|\.NET|NET|PostgreSQL|"
    r"Python|Oracle|WebSphere|WebLogic|Tomcat|JBoss|WildFly|Windows|VB|Visual Basic)\s*$",
    re.I,
)
COMPONENT_KEYS = {"component", "components", "replaces"}


@dataclass
class Message:
    level: str  # error | warning
    stage: str
    where: str
    text: str

    def __str__(self) -> str:
        where = f" at {self.where}" if self.where else ""
        return f"{self.level}: {self.stage}{where}: {self.text}"


@dataclass
class Report:
    run_dir: Path
    messages: list[Message] = field(default_factory=list)
    checked: list[str] = field(default_factory=list)
    stamped: list[str] = field(default_factory=list)
    status: dict[str, Any] = field(default_factory=dict)

    @property
    def errors(self) -> list[Message]:
        return [m for m in self.messages if m.level == "error"]

    @property
    def warnings(self) -> list[Message]:
        return [m for m in self.messages if m.level == "warning"]

    @property
    def ok(self) -> bool:
        return not self.errors


def _walk(node: Any, path: str = "") -> Iterator[tuple[str, str, Any]]:
    """(path, key, value) for every key of every dict in the tree."""
    if isinstance(node, dict):
        for k, v in node.items():
            p = f"{path}.{k}" if path else k
            yield path, k, v
            yield from _walk(v, p)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from _walk(v, f"{path}[{i}]")


def _dicts(node: Any, path: str = "") -> Iterator[tuple[str, dict[str, Any]]]:
    if isinstance(node, dict):
        yield path, node
        for k, v in node.items():
            yield from _dicts(v, f"{path}.{k}" if path else k)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from _dicts(v, f"{path}[{i}]")


_MISSING = object()


def lookup(facts: dict[str, Any], metric: str) -> Any:
    d: Any = facts
    for part in metric.split("."):
        if not isinstance(d, dict) or part not in d:
            return _MISSING
        d = d[part]
    return d


def _same(a: Any, b: Any) -> bool:
    if isinstance(a, bool) or isinstance(b, bool):
        return bool(a == b)
    if isinstance(a, int | float) and isinstance(b, int | float):
        return abs(float(a) - float(b)) < 1e-6
    return str(a) == str(b)


class _Checker:
    def __init__(self, run_dir: Path) -> None:
        self.rd = RunData.load(run_dir)
        self.facts: dict[str, Any] = dict(self.rd.summary)
        if self.rd.effort is not None:
            self.facts["effort"] = self.rd.effort
        self.components = {c["name"] for c in self.rd.items("components")}
        self.line_counts: dict[Path, int] = {}

    def check(self, stage: Stage, data: Any, out: list[Message]) -> None:
        def add(level: str, where: str, text: str) -> None:
            out.append(Message(level, stage.name, where, text))

        try:
            stage.model.model_validate(data)
        except ValidationError as err:
            for e in err.errors():
                loc = ".".join(str(x) for x in e["loc"])
                add("error", loc, e["msg"].removeprefix("Value error, "))
            return
        if data["meta"].get("stage") != stage.name:
            add("error", "meta.stage", f"is '{data['meta'].get('stage')}', expected '{stage.name}'")
        self._references(data, add)
        self._figures(data, add)
        self._evidence(data, add)
        self._prose(data, add)

    def _references(self, data: Any, add: Any) -> None:
        for path, key, value in _walk({k: v for k, v in data.items() if k != "meta"}):
            where = f"{path}.{key}" if path else key
            if key.endswith("_id") or key.endswith("_ids"):
                for v in value if isinstance(value, list) else [value]:
                    if isinstance(v, str) and v not in self.rd.by_id:
                        add("error", where, f"id '{v}' is not in the scan facts")
            elif key in COMPONENT_KEYS:
                for v in value if isinstance(value, list) else [value]:
                    if isinstance(v, str) and v not in self.components:
                        add("error", where, f"component '{v}' is not in components.json")

    def _figures(self, data: Any, add: Any) -> None:
        for path, d in _dicts(data):
            if "metric" not in d or "value" not in d or "label" not in d:
                continue
            metric, estimate = d.get("metric"), d.get("estimate", False)
            if not metric:
                if not estimate:
                    add(
                        "error",
                        path,
                        f"figure '{d['label']}' has no metric: add the summary "
                        "path it comes from, or mark it estimate: true and say how in note",
                    )
                continue
            actual = lookup(self.facts, metric)
            if actual is _MISSING:
                if not estimate:
                    add(
                        "error",
                        path,
                        f"metric '{metric}' is not in audit-summary.json or effort.json",
                    )
                continue
            if not estimate and not _same(d["value"], actual):
                add(
                    "error",
                    path,
                    f"figure '{d['label']}' says {d['value']} but the scan says "
                    f"{actual} ({metric}); copy the scan's number or mark it estimate: true",
                )

    def _lines(self, p: Path) -> int:
        if p not in self.line_counts:
            with p.open("rb") as fh:
                self.line_counts[p] = sum(1 for _ in fh)
        return self.line_counts[p]

    def _evidence(self, data: Any, add: Any) -> None:
        for path, key, value in _walk(data):
            if key != "evidence" or not isinstance(value, list):
                continue
            for i, ev in enumerate(value):
                where = f"{path}.evidence[{i}]" if path else f"evidence[{i}]"
                file, line = ev.get("file"), ev.get("line")
                p = self.rd.abs_path(file) if file else None
                if p is None or not p.is_file():
                    if self.rd.roots:
                        add("error", where, f"evidence file '{file}' is not in the scanned source")
                    continue
                if line is not None and not 1 <= line <= self._lines(p):
                    add("error", where, f"line {line} is outside '{file}' ({self._lines(p)} lines)")

    def _prose(self, data: Any, add: Any) -> None:
        for path, key, value in _walk(data):
            if not isinstance(value, str) or key in ("mermaid", "metric", "label"):
                continue
            for m in _PROSE.finditer(value):
                if _HEDGE.search(value[: m.start()]) or _VERSIONED.search(value[: m.start()]):
                    continue
                n = int(m.group(1).replace(",", ""))
                metric = PROSE_COUNTS[m.group(2).lower()]
                actual = lookup(self.facts, metric)
                if actual is not _MISSING and isinstance(actual, int) and n != actual:
                    where = f"{path}.{key}" if path else key
                    add(
                        "warning",
                        where,
                        f"text says '{m.group(0)}' but the scan counts "
                        f"{actual} ({metric}); use the scan's number, or say it is a subset",
                    )


def validate_run(run_dir: Path, stages: list[str] | None = None, restamp: bool = False) -> Report:
    """Validate the enrichment of one run; stamp the stages that pass; rewrite status.json."""
    st.sync_from_latest(run_dir)
    report = Report(run_dir)
    edir = st.enrichment_dir(run_dir)
    unknown = sorted(set(stages or []) - set(BY_NAME))
    for name in unknown:
        report.messages.append(Message("error", name, "", "no such stage"))
    checker = _Checker(run_dir)
    for stage in STAGES:
        if stages and stage.name not in stages:
            continue
        path = edir / stage.file
        if not path.exists():
            continue
        report.checked.append(stage.name)
        msgs: list[Message] = []
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except ValueError as err:
            msgs.append(Message("error", stage.name, "", f"not valid JSON: {err}"))
        else:
            checker.check(stage, data, msgs)
        report.messages.extend(msgs)
        if not any(m.level == "error" for m in msgs) and st.stamp(run_dir, stage, force=restamp):
            report.stamped.append(stage.name)
    report.status = st.write_status(run_dir) if edir.exists() else st.compute_status(run_dir)
    st.sync_to_latest(run_dir)
    return report
