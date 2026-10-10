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
from legacy_audit.enrich.status import compute_status, enrichment_dir, sync_from_latest
from legacy_audit.report import diagrams
from legacy_audit.report.data import RunData

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
}
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
    def start(self, key: str) -> None:
        self.section = next(s for s in SECTIONS if s[0] == key)

    def add(
        self,
        sid: str,
        title: str,
        blocks: list[dict[str, Any]],
        notes: str = "",
        kind: str = "content",
        subtitle: str = "",
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
            f"{str(self.rd.run.get('started_at', ''))[:10]}",
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
        if cl:
            block = self.table(
                ["Component", "Capability", "Kind", "Criticality"],
                [
                    [c["component"], c["capability"], c["kind"], c["criticality"]]
                    for c in cl["components"]
                ],
            )
            block["stage"] = "classification"
        else:
            block = self.placeholder("classification", "Capabilities and modules")
        self.add("fn-capabilities", "Capabilities and modules", [block])

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
        flows: dict[str, Any] = (
            {
                "type": "bullets",
                "stage": "flows",
                "items": [f"{f['name']}: {f['summary']}" for f in fl["flows"]],
            }
            if fl
            else self.placeholder("flows", "Key business flows")
        )
        self.add("fn-flows", "Key business flows", [flows])

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

        by_type = _get(self.s, "counts.integrations.by_type", {}) or {}
        labels = sorted(by_type, key=lambda k: (-by_type[k], k))
        top = self.table(
            ["Type", "Target", "Direction", "Usages"],
            [
                [i["type"], i["target"], i.get("direction", ""), i.get("usages", 0)]
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
                self.chart(
                    "doughnut",
                    "SQL by PostgreSQL grade",
                    gl,
                    [grades[g] for g in gl],
                    "statements",
                    [GRADE_TOKEN[g] for g in gl],
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
        self.add(
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
            batch = [{"type": "text", "text": "The scan found no batch jobs."}]
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
        self.add(
            "tgt-mapping",
            "Legacy to target mapping",
            [
                self.table(
                    ["Legacy", "Target"],
                    [[m.get("legacy", ""), m.get("target", "")] for m in self.rd.target_mapping],
                )
            ],
            "Mapping rules from rules/target-mapping.yaml.",
        )

    def plan(self) -> None:
        self.start("plan")
        plan = self.stage("plan")
        if not plan:
            self.add("plan-waves", "Waves and roadmap", [self.placeholder("plan", "Waves")])
            self.add(
                "plan-team",
                "Team and decisions needed",
                [self.placeholder("plan", "Team and decisions")],
            )
            return
        waves = sorted(plan["waves"], key=lambda w: w["order"])

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
    """Stage name -> parsed file, for every stage file present (validated or not)."""
    sync_from_latest(run_dir)
    out: dict[str, Any] = {}
    edir = enrichment_dir(run_dir)
    for name, stage in BY_NAME.items():
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
        "sections": [{"key": k, "title": t, "audience": a} for k, t, a in SECTIONS],
        "slides": deck.slides,
    }
