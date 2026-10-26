"""Build ``deck.json``: the management presentation as data, rendered as HTML and as PPTX.

Slides follow ``docs/plan.md`` § Outputs (executive summary, functional view, current
architecture, technical deep-dive, target architecture, plan, risks and next steps). Numbers come
from the run's facts (``audit-summary.json``, ``effort.json``, entity files); interpretation comes
from the validated enrichment. A slide part whose enrichment stage is missing shows a placeholder
naming the prompt to run, so the deck can be built straight after a scan.

Block types: ``kpis``, ``bullets``, ``text``, ``chart`` (bar, doughnut, line; one series),
``table``, ``diagram`` (Mermaid source, plus a PNG path once rendered) and ``placeholder``.
"""

from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any

from legacy_audit.config import Branding
from legacy_audit.enrich.stages import BY_NAME
from legacy_audit.enrich.status import (
    active_stages,
    compute_status,
    enrichment_dir,
    sync_from_latest,
)
from legacy_audit.report import diagrams, flows
from legacy_audit.report import visuals as vz
from legacy_audit.report.data import RunData
from legacy_audit.report.servers import server_rows

DECK_VERSION = "1"
SEVERITIES = ["critical", "high", "medium", "low", "info"]
SEVERITY_TOKEN = {
    "critical": "critical",
    "high": "serious",
    "medium": "warning",
    "low": "info",
    "info": "info",
}
GRADES = ["auto", "review", "manual"]
GRADE_TOKEN = {"auto": "good", "review": "warning", "manual": "critical"}
# Light-theme values of the colour tokens; the PPTX uses these, the HTML deck its CSS variables.
PALETTE = {
    "accent": "2A78D6",
    "good": "0CA30C",
    "warning": "FAB219",
    "serious": "EC835A",
    "critical": "D03B3B",
    "info": "898781",
    "text": "0B0B0B",
    "muted": "6D6B66",
    "surface": "FCFCFB",
    "grid": "E1E0D9",
    "series-1": "2A78D6",
    "series-2": "EB6834",
    "series-3": "1BAF7A",
    "series-4": "EDA100",
    "series-5": "E87BA4",
    "series-6": "008300",
    "series-7": "6250D6",
    "series-8": "E34948",
    # heat-map steps (sequential blue) and flow-node fills / borders
    "seq-0": "F3F2EE",
    "seq-1": "CDE2FB",
    "seq-2": "9EC5F4",
    "seq-3": "6DA7EC",
    "seq-4": "3987E5",
    "seq-5": "256ABF",
    "seq-6": "184F95",
    "risk-0": "D3EFD3",
    "risk-1": "FDE8B5",
    "risk-2": "F9D2C1",
    "risk-3": "F2BDBD",
    "kind-screen": "DBE9FB/2A78D6",
    "kind-service": "ECE3FB/6250D6",
    "kind-table": "DCF3DC/0CA30C",
    "kind-system": "FDE7D9/EC835A",
    "kind-trigger": "FFF1CC/D69A00",
    "kind-job": "D8F1F0/13867F",
    "kind-muted": "F0EFEC/898781",
}
SECTION_COLOR = {
    "exec": "series-1",
    "functional": "series-7",
    "current": "series-3",
    "technical": "series-2",
    "target": "series-6",
    "plan": "series-4",
    "risks": "series-8",
}
KEY_FLOWS = 3  # screen flows and batch flows given their own slide
MAX_ROWS = 10  # per table on a slide
MAX_CELL = 100  # characters per table cell on a slide (full text in the technical report)
RESOLVING_ACTIONS = {"upgrade", "replace", "remove"}
SECTIONS = [
    ("exec", "Executive summary", "Top management"),
    ("functional", "Functional view", "Business and functional teams"),
    ("current", "Current architecture", "Architects"),
    ("technical", "Technical deep-dive", "Tech leads and developers"),
    ("target", "Target architecture", "Architects"),
    ("plan", "Plan", "All"),
    ("risks", "Risks, assumptions and next steps", "All"),
]


def _num(v: Any) -> str:
    if isinstance(v, float):
        return f"{v:,.1f}".rstrip("0").rstrip(".")
    if isinstance(v, int):
        return f"{v:,}"
    return str(v)


def _cut(text: str, limit: int = MAX_CELL) -> str:
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "\u2026"


def _get(d: Any, path: str, default: Any = 0) -> Any:
    for part in path.split("."):
        if not isinstance(d, dict) or part not in d:
            return default
        d = d[part]
    return d


class _Deck:
    def __init__(self, rd: RunData, enrichment: dict[str, Any], status: dict[str, Any]) -> None:
        self.rd = rd
        self.s = rd.summary
        self.e = enrichment
        self.status = status
        self.slides: list[dict[str, Any]] = []
        self.section = SECTIONS[0]

    # ---- helpers -------------------------------------------------------------------------------
    def start(self, key: str, divider: bool = True) -> None:
        self.section = next(s for s in SECTIONS if s[0] == key)
        if divider and key != "exec":
            self.slides.append(
                {
                    "id": f"section-{key}",
                    "section": self.section[1],
                    "audience": self.section[2],
                    "type": "section",
                    "title": self.section[1],
                    "subtitle": f"For {self.section[2].lower()}",
                    "color": SECTION_COLOR[key],
                    "blocks": [],
                    "notes": "",
                    "stale": [],
                }
            )

    def add_unless(self, skip: bool, *args: Any, **kwargs: Any) -> None:
        """``add`` the slide unless ``skip`` (e.g. a target slide when there is no target)."""
        if not skip:
            self.add(*args, **kwargs)

    def add(
        self,
        sid: str,
        title: str,
        blocks: list[dict[str, Any]],
        notes: str = "",
        kind: str = "content",
        subtitle: str = "",
        layout: str = "",
    ) -> None:
        uses = sorted({b["stage"] for b in blocks if b.get("stage")})
        stale = [u for u in uses if u in self.status.get("stale", [])]
        self.slides.append(
            {
                "id": sid,
                "section": self.section[1],
                "audience": self.section[2],
                "type": kind,
                "title": title,
                "subtitle": subtitle,
                "blocks": blocks,
                "notes": notes,
                "stale": stale,
                "color": SECTION_COLOR[self.section[0]],
                "layout": layout,
            }
        )

    def stage(self, name: str) -> dict[str, Any] | None:
        return self.e.get(name)

    def placeholder(self, stage: str, what: str) -> dict[str, Any]:
        st = BY_NAME[stage]
        return {
            "type": "placeholder",
            "stage": stage,
            "text": f"{what}: run /audit-enrich {stage} in Copilot (stage {st.number}, "
            f"{st.title}), then rebuild the deck.",
        }

    def kpi(self, label: str, metric: str, unit: str = "") -> dict[str, Any]:
        src = {**self.s, "effort": self.rd.effort or {}}
        return {"label": label, "value": _get(src, metric), "metric": metric, "unit": unit}

    @staticmethod
    def chart(
        chart: str,
        title: str,
        labels: list[str],
        values: list[Any],
        unit: str = "",
        colors: list[str] | None = None,
    ) -> dict[str, Any]:
        return {
            "type": "chart",
            "chart": chart,
            "title": title,
            "labels": labels,
            "values": values,
            "unit": unit,
            "colors": colors or ["accent"] * len(labels),
        }

    @staticmethod
    def table(columns: list[str], rows: list[list[Any]], title: str = "") -> dict[str, Any]:
        """A table; beyond MAX_ROWS the rest is counted in ``more`` (full lists are in the
        technical report)."""
        cells = [
            [_num(c) if isinstance(c, int | float) else _cut(str(c or "")) for c in r] for r in rows
        ]
        return {
            "type": "table",
            "title": title,
            "columns": columns,
            "rows": cells[:MAX_ROWS],
            "more": max(0, len(cells) - MAX_ROWS),
        }

    def narrative(self, who: str) -> str:
        n = (self.stage("narratives") or {}).get(who) or {}
        return str(n.get("text", ""))

    def flow_slide(self, f: flows.Flow, story: dict[str, Any] | None, what: str) -> None:
        block = f.deck_block()
        words: dict[str, Any]
        if story:
            items = [story["purpose"]]
            if f.kind == "screen":
                items += story.get("user_actions", [])[:4]
            else:
                if story.get("when"):
                    items.append("When: " + story["when"])
                items += [st["description"] for st in story.get("steps", [])][:4]
                if not story.get("steps"):
                    items += [
                        f"{pg['name']}: {pg['what_it_does']}" for pg in story.get("programs", [])
                    ][:4]
                if story.get("on_failure"):
                    items.append("If it fails: " + story["on_failure"])
            words = {"type": "bullets", "stage": "functional", "items": items}
        else:
            said = [
                f"{c['file'].rsplit('/', 1)[-1]}: {' '.join(c['lines'])}"
                for c in flows.code_comments(self.rd, f, limit=3)
            ]
            words = {
                "type": "bullets",
                "items": [f.summary, *[f"The code says: {x}" for x in said]],
            }
        self.add(
            f"fn-{f.kind}-{f.slug}",
            f"{what} flow: {f.name}",
            [block, words],
            "The flow is drawn from the scan (services, SQL, tables, scheduler definitions); "
            "the words beside it are the Copilot agent's, checked by legacy-audit validate.",
            layout="wide-left",
        )

    def gauges(self) -> dict[str, Any]:
        c = self.s.get("counts", {})
        sql = _get(c, "sql.total")
        libs = _get(c, "libraries.total")
        cov = self.rd.run.get("coverage", [])
        present = sum(1 for x in cov if x.get("status") == "present")
        items = (
            []
            if self.rd.audit_only
            else [  # audit only: no target database
                vz.gauge(
                    "SQL converting automatically",
                    _get(c, "pg_grades.auto"),
                    sql,
                    "good",
                    f"{_num(_get(c, 'pg_grades.auto'))} of {_num(sql)} statements",
                ),
            ]
        )
        items += [
            vz.gauge("Libraries with no known vulnerability",
                     libs - _get(c, "vulnerable_libraries.total"), libs, "series-1",
                     f"{_num(_get(c, 'vulnerable_libraries.total'))} vulnerable"),
            vz.gauge("Libraries still supported", libs - _get(c, "eol_libraries"), libs,
                     "series-3", f"{_num(_get(c, 'eol_libraries'))} end of life"),
            vz.gauge("Inputs provided", present, len(cov), "series-7",
                     f"{present} of {len(cov)} kinds"),
        ]  # fmt: skip
        return {"type": "gauges", "items": items}

    @staticmethod
    def heatmap(
        title: str, columns: list[str], rows: list[dict[str, Any]], scale: str, legend: str = ""
    ) -> dict[str, Any]:
        """Rows of ``{label, cells: [{text, level}]}``; ``scale`` is seq (0..6) or risk (0..3)."""
        return {
            "type": "heatmap",
            "title": title,
            "columns": columns,
            "rows": rows,
            "scale": scale,
            "legend": legend,
        }

    def crud_heatmap(self, limit_tables: int = 8, limit_rows: int = 8) -> dict[str, Any]:
        mx = flows.crud_matrix(self.rd)
        tables = mx["tables"][:limit_tables]
        level = {0: 0, 1: 2, 2: 3, 3: 4, 4: 6}
        rows = [
            {
                "label": r,
                "cells": [
                    {
                        "text": mx["cells"].get(f"{r}|{t}", ""),
                        "level": level[len(mx["cells"].get(f"{r}|{t}", ""))],
                    }
                    for t in tables
                ],
            }
            for r in mx["rows"][:limit_rows]
        ]
        return self.heatmap(
            "Create, read, update, delete by component and table",
            tables,
            rows,
            "seq",
            "C create · R read · U update · D delete; darker = more operations. "
            + (
                f"Busiest {limit_tables} of {len(mx['tables'])} tables."
                if len(mx["tables"]) > limit_tables
                else ""
            ),
        )

    # ---- sections ------------------------------------------------------------------------------
    def executive(self) -> None:
        self.start("exec")
        ov = self.stage("overview")
        app = self.rd.run.get("application") or "Application"
        purpose: dict[str, Any] = (
            {"type": "text", "text": ov["purpose"], "stage": "overview"}
            if ov
            else self.placeholder("overview", "Purpose of the application")
        )
        self.add(
            "exec-title",
            f"{app}: legacy audit",
            [purpose],
            self.narrative("executive"),
            kind="title",
            subtitle=f"Run {self.rd.run.get('run_id', '')} · "
            f"{str(self.rd.run.get('started_at', ''))[:10]} · "
            f"Target: {self.rd.target.get('label', '')}",
        )

        kpis = {
            "type": "kpis",
            "items": [
                self.kpi("Components", "totals.components"),
                self.kpi("Services", "counts.services.total"),
                self.kpi("Screens", "counts.screens.total"),
                self.kpi("SQL statements", "counts.sql.total"),
                self.kpi("Integrations", "counts.integrations.total"),
                self.kpi("Batch jobs", "counts.batch_jobs.total"),
                self.kpi("Libraries", "counts.libraries.total"),
                self.kpi("Lines of code", "totals.loc_code"),
            ],
        }
        points: dict[str, Any] = (
            {
                "type": "bullets",
                "stage": "overview",
                "items": [p["text"] for p in ov.get("key_points", [])],
            }
            if ov
            else self.placeholder("overview", "Key points")
        )
        self.add(
            "exec-estate",
            "The estate at a glance",
            [kpis, points],
            "Counts are from the scan (audit-summary.json); counting rules are printed in "
            "the technical report's appendix.",
        )
        self.add(
            "exec-readiness",
            "Health at a glance" if self.rd.audit_only else "Migration readiness",
            [self.gauges()],
            "Each ring is a share computed from the scan's counts (audit-summary.json).",
        )

        risks = self.stage("risks")
        order = {s: i for i, s in enumerate(SEVERITIES)}
        if risks:
            top = sorted(risks["risks"], key=lambda r: order.get(r["severity"], 9))[:5]
            risk_block = self.table(
                ["Risk", "Severity", "Mitigation", "Owner"],
                [[r["title"], r["severity"], r["mitigation"], r.get("owner", "")] for r in top],
            )
            risk_block["stage"] = "risks"
        else:
            risk_block = self.placeholder("risks", "Top 5 risks")
        sev = _get(self.s, "counts.findings_by_severity", {}) or {}
        labels = [s for s in SEVERITIES if sev.get(s)]
        self.add(
            "exec-risks",
            "Top 5 risks",
            [
                risk_block,
                self.chart(
                    "bar",
                    "Scan findings by severity",
                    labels,
                    [sev[s] for s in labels],
                    "findings",
                    [SEVERITY_TOKEN[s] for s in labels],
                ),
            ],
        )

        if self.rd.audit_only:
            return  # no target: no approach, timeline or effort
        plan = self.stage("plan")
        effort = {
            "type": "kpis",
            "items": [
                self.kpi("Effort, low", "effort.low", "person-days"),
                self.kpi("Effort, likely", "effort.likely", "person-days"),
                self.kpi("Effort, high", "effort.high", "person-days"),
            ],
            "note": str(_get(self.rd.effort, "label", "")) if self.rd.effort else "",
        }
        if not self.rd.effort:
            effort = {"type": "text", "text": "No effort estimate in this run (effort.json)."}
        if plan:
            months = max(
                (w["start_month"] + w["duration_months"] for w in plan["waves"]), default=0
            )
            steps: dict[str, Any] = {
                "type": "bullets",
                "stage": "plan",
                "items": [plan["approach"]]
                + [
                    f"{w['name']}: {w['goal']} (months {w['start_month']} to "
                    f"{w['start_month'] + w['duration_months']})"
                    for w in plan["waves"]
                ]
                + ([f"About {months} months in total"] if months else []),
            }
        else:
            steps = self.placeholder("plan", "Approach and timeline")
        self.add(
            "exec-approach",
            "Approach, timeline and effort",
            [effort, steps],
            "Effort is the scanner's estimate from configured weights, not a quote.",
        )

    def functional(self) -> None:
        self.start("functional")
        cl = self.stage("classification")
        fn = self.stage("functional")
        if fn and fn.get("domains"):
            block: dict[str, Any] = {
                "type": "capmap",
                "stage": "functional",
                "domains": [
                    {
                        "name": d["name"],
                        "color": f"series-{i % 8 + 1}",
                        "caps": [c["name"] for c in d.get("capabilities", [])],
                    }
                    for i, d in enumerate(fn["domains"])
                ],
            }
        elif cl:
            block = self.table(
                ["Component", "Capability", "Kind", "Criticality"],
                [
                    [c["component"], c["capability"], c["kind"], c["criticality"]]
                    for c in cl["components"]
                ],
            )
            block["stage"] = "classification"
        else:
            block = self.placeholder("functional", "Capability map")
        self.add("fn-capabilities", "Capability map", [block])

        if fn and fn.get("use_cases"):
            uc = self.table(
                ["Use case", "Who", "Goal"],
                [[f"{u['id']} {u['name']}", u["actor"], u["goal"]] for u in fn["use_cases"]],
            )
            uc["stage"] = "functional"
            actors: dict[str, Any] = {
                "type": "bullets",
                "title": "Who uses it",
                "stage": "functional",
                "items": [f"{a['name']} ({a['kind']})" for a in fn.get("actors", [])],
            }
            self.add("fn-use-cases", "Users and use cases", [actors, uc])
        else:
            self.add(
                "fn-use-cases",
                "Users and use cases",
                [self.placeholder("functional", "Actors and use cases")],
            )

        per = Counter(
            s.get("component") or "(none)" for s in self.rd.items("screens") if not s.get("partial")
        )
        names = sorted(per, key=lambda n: (-per[n], n))
        screens: dict[str, Any] = (
            self.chart("bar", "Screens per component", names, [per[n] for n in names], "screens")
            if names
            else {"type": "text", "text": "No screens found by the scan."}
        )
        self.add("fn-screens", "Screens per component", [screens])

        fl = self.stage("flows")
        flows_block: dict[str, Any] = (
            {
                "type": "bullets",
                "stage": "flows",
                "items": [f"{f['name']}: {f['summary']}" for f in fl["flows"]],
            }
            if fl
            else self.placeholder("flows", "Key business flows")
        )
        self.add("fn-flows", "Key business flows", [flows_block])

        stories = {x["screen_id"]: x for x in (fn or {}).get("screens", [])}
        key_screens = sorted(
            flows.screen_flows(self.rd), key=lambda f: (-len(f.services) - len(f.tables), f.name)
        )[:KEY_FLOWS]
        for f in key_screens:
            self.flow_slide(f, stories.get(f.id), "Screen")
        bstories = {x["job_id"]: x for x in (fn or {}).get("batches", [])}
        batch = flows.batch_flows(self.rd)
        for f in sorted(batch, key=lambda f: (-len(f.steps) - len(f.tables), f.name))[:KEY_FLOWS]:
            self.flow_slide(f, bstories.get(f.id), "Batch")
        if batch:
            week = flows.week_grid(batch)
            rows = [
                {
                    "label": r["name"],
                    "cells": [{"text": c, "level": 4 if c else 0} for c in r["cells"]],
                }
                for r in week[:MAX_ROWS]
            ]
            self.add(
                "fn-batch-calendar",
                "Batch calendar",
                [
                    self.heatmap(
                        "When each batch runs",
                        flows.DAY_NAMES,
                        rows,
                        "seq",
                        "Start times from the scheduler definitions (Autosys, cron).",
                    ),
                    {"type": "bullets", "items": [f"{r['name']}: {r['text']}" for r in week]},
                ],
            )
        if fn and fn.get("business_rules"):
            rules = self.table(
                ["Rule", "Kind", "What it says"],
                [
                    [f"{r['id']} {r['name']}", r["kind"], r["description"]]
                    for r in fn["business_rules"]
                ],
            )
            rules["stage"] = "functional"
            self.add("fn-rules", "Business rules to keep", [rules])
        else:
            self.add(
                "fn-rules",
                "Business rules to keep",
                [self.placeholder("functional", "Business rules")],
            )

        n = (self.stage("narratives") or {}).get("functional")
        users: dict[str, Any] = (
            {"type": "bullets", "stage": "narratives", "items": [n["text"], *n["key_messages"]]}
            if n
            else self.placeholder("narratives", "What changes for users")
        )
        self.add("fn-users", "What changes for users", [users])

    def current(self) -> None:
        self.start("current")
        rows = [
            [t["name"], t.get("version") or "", t.get("category") or "", t.get("eol") or ""]
            for t in self.s.get("tech_stack", [])
        ]
        self.add(
            "cur-stack",
            "Technology stack",
            [self.table(["Technology", "Version", "Category", "End of life"], rows)],
        )
        servers = server_rows(self.rd, self.e)
        if servers:
            self.add(
                "cur-servers",
                "Application and web servers",
                [
                    self.table(
                        ["Product", "Kind", "Version", "End of life", "Modules", "Target"],
                        [
                            [
                                r["name"],
                                r["kind"],
                                r["version"],
                                r["eol"],
                                ", ".join(r["components"]),
                                r["target"],
                            ]
                            for r in servers
                        ],
                    )
                ],
                "From deployment descriptors, server configuration, Dockerfiles and start scripts.",
            )

        arch = self.stage("architecture")
        if arch and arch.get("diagrams"):
            d = arch["diagrams"][0]
            diag = {
                "type": "diagram",
                "title": d["title"],
                "mermaid": d["mermaid"],
                "stage": "architecture",
            }
        else:
            g = diagrams.component_graph(self.rd)
            diag = {
                "type": "diagram",
                "title": "Components and their dependencies (from scan)",
                "mermaid": g.source,
                "note": g.note,
            }
        notes = "; ".join(n["text"] for n in (arch or {}).get("notes", []))
        self.add("cur-components", "Component diagram", [diag], notes)
        if arch and arch.get("layers"):
            self.add(
                "cur-layers",
                "Layers",
                [
                    {
                        "type": "layers",
                        "stage": "architecture",
                        "layers": [
                            {
                                "name": la["name"],
                                "color": f"series-{i % 8 + 1}",
                                "items": la.get("components", []),
                                "note": la.get("description", ""),
                            }
                            for i, la in enumerate(arch["layers"])
                        ],
                    }
                ],
            )
        else:
            self.add("cur-layers", "Layers", [self.placeholder("architecture", "Layers")])
        self.add(
            "cur-crud",
            "Who changes which data",
            [self.crud_heatmap()],
            "From the SQL each component runs: letters are create, read, update, delete.",
        )

        by_type = _get(self.s, "counts.integrations.by_type", {}) or {}
        labels = sorted(by_type, key=lambda k: (-by_type[k], k))
        top = self.table(
            ["Type", "Target", "Direction", "Usages"],
            [
                [
                    i["type"],
                    i["target"]
                    + (f" ({len(i['endpoints'])} grouped)" if i.get("endpoints") else ""),
                    i.get("direction", ""),
                    i.get("usages", 0),
                ]
                for i in self.s.get("integrations", [])[:8]
            ],
        )
        self.add(
            "cur-integrations",
            "Integrations",
            [
                self.chart(
                    "bar",
                    "Integrations by type",
                    labels,
                    [by_type[k] for k in labels],
                    "integrations",
                ),
                top,
            ],
        )

        web = self.s.get("web_services", []) or []
        if web:
            by_style = _get(self.s, "counts.web_services.by_style", {}) or {}
            wl = [f"{st} {side}" for side in ("offered", "called") for st in ("REST", "SOAP")]
            self.add(
                "cur-web-services",
                "Web services (REST and SOAP)",
                [
                    {
                        "type": "kpis",
                        "items": [
                            self.kpi("Offered", "counts.web_services.offered"),
                            self.kpi("Called", "counts.web_services.called"),
                            self.kpi("Operations", "counts.web_services.operations"),
                        ],
                    },
                    self.chart(
                        "bar",
                        "Web services by style",
                        wl,
                        [by_style.get(k, 0) for k in wl],
                        "web services",
                    ),
                    self.table(
                        ["Offered / called", "Style", "Web service", "Operations"],
                        [
                            [w["side"], w["style"], w["name"], w.get("operations", 0)]
                            for w in web[:8]
                        ],
                    ),
                ],
                "Offered: other systems call the application; called: it calls them. "
                "Every operation is in the technical report, Web services page.",
            )

        grades = _get(self.s, "counts.pg_grades", {}) or {}
        gl = [g for g in GRADES if grades.get(g)]
        self.add(
            "cur-data",
            "Data landscape",
            [
                {
                    "type": "kpis",
                    "items": [
                        self.kpi("Tables", "counts.db_objects.table"),
                        self.kpi("Stored procedures", "counts.stored_procedures"),
                        self.kpi("SQL statements", "counts.sql.total"),
                        self.kpi("Distinct SQL", "counts.sql.distinct"),
                    ],
                },
                self.table(
                    ["Table", "CRUD", "Referenced by"],
                    [
                        [t["name"], "".join(t.get("crud", [])), t.get("references", 0)]
                        for t in self.s.get("top_tables", [])[:8]
                    ],
                    "Most used tables",
                ),
                *(
                    []
                    if self.rd.audit_only
                    else [
                        self.chart(
                            "doughnut",
                            "SQL by PostgreSQL grade",
                            gl,
                            [grades[g] for g in gl],
                            "statements",
                            [GRADE_TOKEN[g] for g in gl],
                        )
                    ]
                ),
            ],
        )

    def technical(self) -> None:
        self.start("technical")
        cx = _get(self.s, "counts.complexity.services", {}) or {}
        order = ["Simple", "Medium", "Complex", "Very complex"]
        cl = [c for c in order if cx.get(c)] + sorted(set(cx) - set(order))
        self.add(
            "tech-services",
            "Services by complexity",
            [
                self.chart(
                    "bar", "Services by complexity band", cl, [cx[c] for c in cl], "services"
                ),
                self.table(
                    ["Service", "Kind", "Component", "Tables"],
                    [
                        [s["name"], s["kind"], s.get("component", ""), s.get("tables", 0)]
                        for s in self.s.get("top_services", [])[:8]
                    ],
                    "Largest services",
                ),
            ],
        )

        grades = _get(self.s, "counts.pg_grades", {}) or {}
        gl = [g for g in GRADES if grades.get(g)]
        dm = self.stage("data_migration")
        strategy: dict[str, Any] = (
            {"type": "text", "text": dm["strategy"], "stage": "data_migration"}
            if dm and dm.get("strategy")
            else self.placeholder("data_migration", "Data strategy")
        )
        self.add_unless(
            self.rd.audit_only,
            "tech-sql",
            "SQL readiness for PostgreSQL",
            [
                {
                    "type": "kpis",
                    "items": [
                        self.kpi("SQL statements", "counts.sql.total"),
                        self.kpi("Built at run time", "counts.sql.dynamic"),
                        self.kpi("Not parsed", "counts.sql.not_parsed"),
                    ],
                },
                self.chart(
                    "doughnut",
                    "PostgreSQL grade",
                    gl,
                    [grades[g] for g in gl],
                    "statements",
                    [GRADE_TOKEN[g] for g in gl],
                ),
                strategy,
            ],
        )

        jobs = self.rd.items("batch_jobs")
        coverage = _get(self.s, "coverage", {}) or {}
        gaps = [
            v
            for k, v in coverage.items()
            if k in ("autosys", "crontab") and "not provided" in str(v)
        ]
        if jobs:
            sched = Counter(j.get("scheduler") or "unknown" for j in jobs if j.get("kind") != "box")
            sl = sorted(sched, key=lambda k: (-sched[k], k))
            batch: list[dict[str, Any]] = [
                self.chart("bar", "Batch jobs by scheduler", sl, [sched[k] for k in sl], "jobs")
            ]
        else:
            batch = [{"type": "text", "text": "The scan found no scheduled batch jobs."}]
        loose = [
            f
            for f in flows.batch_flows(self.rd)
            if f.schedule.get("scheduler") in ("none", "event")
        ]
        if loose:
            batch.append(
                {
                    "type": "bullets",
                    "title": "Batch work with no scheduler definition",
                    "items": [f"{f.name}: {f.summary}" for f in loose[:MAX_ROWS]],
                }
            )
        if gaps:
            batch.append({"type": "bullets", "title": "Inputs not provided", "items": gaps})
        self.add("tech-batch", "Batch landscape", batch)

        libs = self.rd.items("libraries")
        vulnerable = [lb for lb in libs if lb.get("cves")]
        remaining = [
            lb
            for lb in vulnerable
            if (lb.get("recommendation") or {}).get("action") not in RESOLVING_ACTIONS
        ]
        dp = self.stage("dependency_plan")
        themes: dict[str, Any] = (
            {
                "type": "bullets",
                "stage": "dependency_plan",
                "items": [
                    f"{t['name']}: {t['action']}" + (f" ({t['wave']})" if t.get("wave") else "")
                    for t in dp["themes"]
                ],
            }
            if dp
            else self.placeholder("dependency_plan", "Upgrade themes")
        )
        self.add(
            "tech-security",
            "Dependency security: exposure today and after migration",
            [
                {
                    "type": "kpis",
                    "items": [
                        self.kpi("Vulnerable libraries", "counts.vulnerable_libraries.total"),
                        self.kpi(
                            "Known vulnerabilities", "counts.vulnerable_libraries.vulnerabilities"
                        ),
                        self.kpi("End-of-life libraries", "counts.eol_libraries"),
                    ],
                },
                self.chart(
                    "bar",
                    "Vulnerable libraries",
                    ["Today", "After recommended upgrades"],
                    [len(vulnerable), len(remaining)],
                    "libraries",
                    ["critical", "good"],
                ),
                themes,
            ],
            "'After' counts vulnerable libraries whose recommendation is not upgrade, replace or "
            "remove, i.e. what remains if every recommendation is applied.",
        )

        cm = self.s.get("component_metrics", [])
        lang = _get(self.s, "by_language", {}) or {}
        ll = sorted(
            (k for k in lang if lang[k].get("loc_code")), key=lambda k: -lang[k]["loc_code"]
        )[:8]
        self.add(
            "tech-quality",
            "Code quality",
            [
                self.table(
                    [
                        "Component",
                        "Complexity",
                        "Avg. complexity",
                        "Duplication %",
                        "Dead-code candidates",
                        "Test ratio",
                    ],
                    [
                        [
                            m["component"],
                            m.get("complexity", ""),
                            m.get("complexity_avg", 0),
                            m.get("duplication_pct", 0),
                            m.get("dead_code_candidates", 0),
                            m.get("test_ratio", 0),
                        ]
                        for m in cm
                    ],
                ),
                self.chart(
                    "bar",
                    "Lines of code by language",
                    ll,
                    [lang[k]["loc_code"] for k in ll],
                    "lines",
                ),
            ],
        )

        sev = _get(self.s, "counts.findings_by_severity", {}) or {}
        labels = [s for s in SEVERITIES if sev.get(s)]
        self.add(
            "tech-findings",
            "Findings",
            [
                self.chart(
                    "bar",
                    "Findings by severity",
                    labels,
                    [sev[s] for s in labels],
                    "findings",
                    [SEVERITY_TOKEN[s] for s in labels],
                ),
                self.table(
                    ["Severity", "Rule", "Finding"],
                    [
                        [f["severity"], f["rule"], f["message"]]
                        for f in self.s.get("top_findings", [])[:5]
                    ],
                    "Top findings",
                ),
            ],
        )

        cov = self.rd.run.get("coverage", [])
        self.add(
            "tech-coverage",
            "Input coverage",
            [
                self.table(
                    ["Input", "Status", "Files"],
                    [
                        [
                            c.get("label") or c.get("kind", ""),
                            c.get("status", ""),
                            c.get("file_count", 0),
                        ]
                        for c in cov
                    ],
                )
            ],
            "A missing input is a coverage gap, not an absence: the scan cannot see what was "
            "not provided.",
        )

    def target(self) -> None:
        self.start("target")
        ta = self.stage("target_architecture")
        if ta:
            blocks: list[dict[str, Any]] = [
                {
                    **self.table(
                        ["Target component", "Technology", "Platform", "Replaces"],
                        [
                            [
                                c["name"],
                                c["technology"],
                                c["platform"],
                                ", ".join(c.get("replaces", [])),
                            ]
                            for c in ta["components"]
                        ],
                    ),
                    "stage": "target_architecture",
                },
            ]
            if ta.get("diagrams"):
                d = ta["diagrams"][0]
                blocks.append(
                    {
                        "type": "diagram",
                        "title": d["title"],
                        "mermaid": d["mermaid"],
                        "stage": "target_architecture",
                    }
                )
        else:
            blocks = [self.placeholder("target_architecture", "Target architecture")]
        self.add("tgt-architecture", "Target architecture", blocks, self.narrative("architect"))
        if ta:
            legacy = sorted({r for c in ta["components"] for r in c.get("replaces", [])})
            trans: dict[str, Any] = {
                "type": "flow",
                "title": "From today to the target",
                "stage": "target_architecture",
                "columns": [
                    {
                        "title": "Today",
                        "nodes": [{"key": "o:" + n, "label": n, "kind": "muted"} for n in legacy],
                    },
                    {
                        "title": "Target on Google Cloud",
                        "nodes": [
                            {
                                "key": "t:" + c["name"],
                                "label": f"{c['name']}\n{c['technology']} · {c['platform']}",
                                "kind": "service",
                            }
                            for c in ta["components"]
                        ],
                    },
                ],
                "edges": [
                    {"from": "o:" + r, "to": "t:" + c["name"], "label": "becomes"}
                    for c in ta["components"]
                    for r in c.get("replaces", [])
                ],
            }
            d = diagrams.current_to_target(self.rd, ta)
            trans["mermaid"] = d.source if d else ""
        else:
            trans = self.placeholder("target_architecture", "From today to the target")
        self.add("tgt-transition", "From today to the target", [trans])
        self.add(
            "tgt-mapping",
            "Legacy to target mapping",
            [
                self.table(
                    ["Legacy", "Target"],
                    [[m.get("legacy", ""), m.get("target", "")] for m in self.rd.target_mapping],
                )
            ],
            f"Mapping rules from rules/target-mapping.yaml for {self.rd.target.get('label', '')}.",
        )

    def plan(self) -> None:
        self.start("plan")
        plan = self.stage("plan")
        if not plan:
            self.add("plan-roadmap", "Roadmap", [self.placeholder("plan", "Roadmap")])
            self.add("plan-waves", "Waves and roadmap", [self.placeholder("plan", "Waves")])
            self.add(
                "plan-team",
                "Team and decisions needed",
                [self.placeholder("plan", "Team and decisions")],
            )
            return
        waves = sorted(plan["waves"], key=lambda w: w["order"])
        g = vz.gantt(waves)
        self.add(
            "plan-roadmap",
            "Roadmap",
            [
                {
                    "type": "gantt",
                    "stage": "plan",
                    "months": g["months"],
                    "bars": [
                        {k: b[k] for k in ("name", "goal", "start", "end", "color")}
                        for b in g["bars"]
                    ],
                }
            ],
            "Waves, start month and duration from the plan stage.",
        )

        def effort(w: dict[str, Any]) -> float:
            return float(
                sum(f["value"] for f in w.get("effort", []) if isinstance(f["value"], int | float))
            )

        rows = [
            [
                w["name"],
                w["goal"],
                f"{w['start_month']} to {w['start_month'] + w['duration_months']}",
                _num(effort(w)),
            ]
            for w in waves
        ]
        total = 0.0
        cumulative = []
        for w in waves:
            total += effort(w)
            cumulative.append(round(total, 1))
        table = self.table(["Wave", "Goal", "Months", "Effort (person-days)"], rows)
        table["stage"] = "plan"
        self.add(
            "plan-waves",
            "Waves and roadmap",
            [
                table,
                self.chart(
                    "line",
                    "Cumulative effort by wave",
                    [w["name"] for w in waves],
                    cumulative,
                    "person-days",
                ),
            ],
            "Wave effort is the plan's split of effort.json by component; a split marked as an "
            "estimate in the enrichment is the agent's.",
        )
        team = self.table(["Role", "People"], [[t["role"], t["count"]] for t in plan["team"]])
        team["stage"] = "plan"
        self.add(
            "plan-team",
            "Team and decisions needed",
            [
                team,
                {
                    "type": "bullets",
                    "title": "Decisions needed",
                    "stage": "plan",
                    "items": plan["decisions_needed"],
                },
            ],
        )

    def risks(self) -> None:
        self.start("risks")
        risks = self.stage("risks")
        order = {s: i for i, s in enumerate(SEVERITIES)}
        if risks:
            block = self.table(
                ["ID", "Risk", "Severity", "Likelihood", "Mitigation", "Owner"],
                [
                    [
                        r["id"],
                        r["title"],
                        r["severity"],
                        r["likelihood"],
                        r["mitigation"],
                        r.get("owner", ""),
                    ]
                    for r in sorted(risks["risks"], key=lambda r: order.get(r["severity"], 9))
                ],
            )
            block["stage"] = "risks"
        else:
            block = self.placeholder("risks", "Risk register")
        self.add("risk-register", "Risk register", [block])
        if risks:
            rm = vz.risk_matrix(risks["risks"])
            heat = self.heatmap(
                "Risks by severity and likelihood",
                [f"likelihood {c}" for c in rm["columns"]],
                [
                    {
                        "label": r["severity"],
                        "cells": [
                            {"text": " ".join(c["ids"]), "level": c["level"]} for c in r["cells"]
                        ],
                    }
                    for r in rm["rows"]
                ],
                "risk",
                "Each cell lists the risk ids; red = act first.",
            )
            heat["stage"] = "risks"
        else:
            heat = self.placeholder("risks", "Risk heat map")
        self.add("risk-heatmap", "Risk heat map", [heat])

        asm = self.stage("assumptions")
        if asm:
            blocks: list[dict[str, Any]] = [
                {
                    "type": "bullets",
                    "title": "Assumptions",
                    "stage": "assumptions",
                    "items": [
                        f"{a['id']}: {a['statement']} (owner: {a.get('owner', 'TBD')})"
                        for a in asm["assumptions"]
                    ],
                },
                {
                    "type": "bullets",
                    "title": "Open questions",
                    "stage": "assumptions",
                    "items": asm.get("open_questions", []),
                },
            ]
        else:
            blocks = [self.placeholder("assumptions", "Assumptions and open questions")]
        self.add("risk-assumptions", "Assumptions and open questions", blocks)

        plan = self.stage("plan")
        steps: list[str] = list((plan or {}).get("next_steps", []))
        if self.status.get("missing"):
            steps.append("Complete the enrichment: " + ", ".join(self.status["missing"]))
        if self.status.get("stale"):
            steps.append(
                "Refresh stale enrichment (/audit-enrich stale): " + ", ".join(self.status["stale"])
            )
        gaps = [
            c.get("label") or c.get("kind", "")
            for c in self.rd.run.get("coverage", [])
            if c.get("status") == "missing"
        ]
        if gaps:
            steps.append("Provide missing inputs: " + ", ".join(gaps))
        self.add("risk-next", "Next steps", [{"type": "bullets", "items": steps or ["None."]}])


def load_enrichment(run_dir: Path) -> dict[str, Any]:
    """Stage name -> parsed file, for every stage file present (validated or not). With no
    target (audit only), files of the target stages left by an earlier run are not used."""
    sync_from_latest(run_dir)
    out: dict[str, Any] = {}
    edir = enrichment_dir(run_dir)
    for stage in active_stages(run_dir):
        name = stage.name
        p = edir / stage.file
        if p.exists():
            try:
                out[name] = json.loads(p.read_text(encoding="utf-8"))
            except ValueError:
                continue
    return out


def build_deck(run_dir: Path, branding: Branding | None = None) -> dict[str, Any]:
    rd = RunData.load(run_dir)
    enrichment = load_enrichment(run_dir)
    status = compute_status(run_dir)
    deck = _Deck(rd, enrichment, status)
    deck.executive()
    deck.functional()
    deck.current()
    deck.technical()
    if not rd.audit_only:  # no target: no target architecture or plan slides
        deck.target()
        deck.plan()
    deck.risks()
    b = branding or Branding()
    return {
        "version": DECK_VERSION,
        "application": rd.run.get("application", ""),
        "run_id": rd.run.get("run_id", run_dir.name),
        "generated_from": {
            "facts": "data/audit-summary.json, data/effort.json, data/*.json",
            "enrichment": {
                name: ("stale" if name in status["stale"] else "present") for name in enrichment
            },
            "missing": status["missing"],
        },
        "branding": b.model_dump(mode="json"),
        "palette": PALETTE,
        "sections": [
            {"key": k, "title": t, "audience": a}
            for k, t, a in SECTIONS
            if not (rd.audit_only and k in ("target", "plan"))
        ],
        "target": rd.target,
        "slides": deck.slides,
    }
