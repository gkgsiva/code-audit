"""``legacy-audit report --type summary``: a four-part summary report, as HTML and PowerPoint.

1. **Project**: what the application is, its technology stack, and its modules (components)
   with a description, technology, complexity and lines of code;
2. **Database and queries**: SQL statements, PostgreSQL readiness, tables, databases;
3. **Integrations**: one row per interface with its purpose and description;
4. **Batches**: every batch once, with what it does and when it runs (``report/batches.py``).

Each part has headline figures, charts and the data behind them (tables, and a CSV per table
next to the page). Every number comes from the scan (rule 1); descriptions come from the
Copilot enrichment when it exists, else from the scan, and say which.

The page is ``reports/summary/index.html``; its **PowerPoint** button downloads
``summary.pptx``, built at the same time on the corporate template chosen for the run
(``branding.pptx_template`` or ``--template``) with the same slide builder as the management
deck, so the PowerPoint follows the template's masters and layouts (decision 124).
"""

from __future__ import annotations

import csv
import re
import shutil
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, field
from importlib.resources import files
from pathlib import Path
from typing import Any

from jinja2 import ChainableUndefined, Environment, PackageLoader, select_autoescape

from legacy_audit.config import Branding
from legacy_audit.enrich.status import latest_copy
from legacy_audit.report import batches, flows
from legacy_audit.report.data import RunData, slug
from legacy_audit.report.deck_builder import PALETTE, _cut, _num, load_enrichment
from legacy_audit.report.pptx_templates import TemplateError, resolve_template
from legacy_audit.store.writer import write_json

SECTIONS = [
    ("project", "Project", "Everyone", "series-1"),
    ("data", "Database and queries", "Architects and DBAs", "series-3"),
    ("integrations", "Integrations", "Architects", "series-2"),
    ("batch", "Batches", "Operations and architects", "series-7"),
]
SLIDE_ROWS = 10  # rows of a table on a slide; the HTML and the CSV have them all
COMPLEXITY_ORDER = ["Simple", "Medium", "Complex", "Very complex"]
GRADES = ["auto", "review", "manual"]
GRADE_TOKEN = {"auto": "good", "review": "warning", "manual": "critical"}
SERIES = [f"series-{i}" for i in range(1, 9)]


@dataclass
class Table:
    key: str  # CSV file name and HTML anchor
    title: str
    columns: list[str]
    rows: list[list[Any]]
    note: str = ""


@dataclass
class Chart:
    key: str
    title: str
    kind: str  # bar, doughnut, hbar
    labels: list[str]
    values: list[Any]
    colors: list[str] = field(default_factory=list)
    unit: str = ""


@dataclass
class Section:
    key: str
    title: str
    audience: str
    color: str
    intro: list[str]  # short summary sentences
    kpis: list[tuple[str, Any]]
    charts: list[Chart]
    tables: list[Table]
    source_note: str = ""


def _pct(part: float, whole: float) -> str:
    return f"{round(100 * part / whole)}%" if whole else "0%"


def _plural(n: int, word: str, many: str | None = None) -> str:
    return f"{_num(n)} {word if n == 1 else (many or word + 's')}"


def _top(counter: Counter[str], n: int) -> tuple[list[str], list[int]]:
    items = counter.most_common()
    if len(items) > n:
        rest = sum(v for _, v in items[n - 1 :])
        items = [*items[: n - 1], ("other", rest)]
    return [k for k, _ in items], [v for _, v in items]


def _eol(tech: dict[str, Any]) -> str:
    """End-of-life cell: the date when known, else yes / no / blank."""
    if tech.get("eol_date"):
        return str(tech["eol_date"])
    if tech.get("eol") is None:
        return ""
    return "yes" if tech["eol"] else "no"


class _Summary:
    def __init__(self, rd: RunData, enrichment: dict[str, Any]) -> None:
        self.rd = rd
        self.s = rd.summary
        self.c = self.s.get("counts", {})
        self.e = enrichment
        self.fn = enrichment.get("functional") or {}
        self.app = rd.run.get("application") or "Application"

    # ---- 1 project -------------------------------------------------------------------------------
    def description(self) -> tuple[list[str], str]:
        """(sentences, source): the AI's business summary, else one written from the counts."""
        if self.fn.get("summary"):
            return [self.fn["summary"]], "AI (Copilot, functional stage)"
        nar = (self.e.get("narratives") or {}).get("executive") or {}
        if nar.get("text"):
            return [nar["text"]], "AI (Copilot, narratives stage)"
        ov = self.e.get("overview") or {}
        if ov.get("purpose"):
            return [ov["purpose"]], "AI (Copilot, overview stage)"
        t, c = self.s.get("totals", {}), self.c
        langs = sorted(
            ((k, v.get("loc_code", 0)) for k, v in self.s.get("by_language", {}).items()),
            key=lambda kv: -kv[1],
        )
        main = [k for k, v in langs if v][:3]
        types = Counter(str(x.get("type")) for x in self.rd.items("components"))
        kinds = ", ".join(f"{_plural(n, k)}" for k, n in types.most_common(4))
        first = (
            f"{self.app} has {_plural(t.get('components', 0), 'module')} ({kinds}) and "
            f"{_num(t.get('loc_code', 0))} lines of code"
            + (f", mostly {', '.join(main)}" if main else "")
            + "."
        )
        second = (
            f"It offers {_plural(c.get('services', {}).get('total', 0), 'service')} and "
            f"{_plural(c.get('screens', {}).get('total', 0), 'screen')}, runs "
            f"{_plural(c.get('batch_jobs', {}).get('total', 0), 'batch', 'batches')}, uses "
            f"{_plural(len(self._tables()), 'table')} through "
            f"{_plural(c.get('sql', {}).get('total', 0), 'SQL statement')} and talks to "
            f"{_plural(c.get('integrations', {}).get('total', 0), 'other system')}."
        )
        return [first, second], "the scan (run the Copilot analysis for a business description)"

    def _component_counts(self) -> dict[str, Counter[str]]:
        per: dict[str, Counter[str]] = defaultdict(Counter)
        for name, key in (
            ("services", "services"),
            ("screens", "screens"),
            ("sql_statements", "sql"),
            ("batch_jobs", "batch jobs"),
        ):
            for item in self.rd.items(name):
                if item.get("component") and not (name == "screens" and item.get("partial")):
                    per[item["component"]][key] += 1
        for item in self.rd.items("integrations"):
            for comp in item.get("components") or []:
                per[comp]["integrations"] += 1
        return per

    def _languages(self) -> dict[str, list[str]]:
        loc: dict[str, Counter[str]] = defaultdict(Counter)
        for f in self.rd.items("inventory"):
            comp, lang = f.get("component"), f.get("language") or ""
            if comp and f.get("origin", "source") == "source" and f.get("loc_code"):
                loc[comp][lang] += f["loc_code"]
        return {c: [k for k, _ in cnt.most_common(4)] for c, cnt in loc.items()}

    def module_description(self, comp: dict[str, Any], counts: Counter[str]) -> tuple[str, str]:
        docs = {
            d["component"]: d for d in (self.e.get("component_docs") or {}).get("components", [])
        }
        doc = docs.get(comp["name"])
        if doc and doc.get("purpose"):
            return doc["purpose"], "AI"
        kind = str(comp.get("type") or "unknown")
        roles = [r for r in comp.get("roles") or [] if r != kind]
        parts = [f"{kind.capitalize()} module" + (f", also {', '.join(roles)}" if roles else "")]
        made = [f"{k} {v}" for k, v in (comp.get("composition") or {}).items()]
        what = [_plural(n, k[:-1] if k.endswith("s") else k, k) for k, n in sorted(counts.items())]
        if what:
            parts.append("with " + ", ".join(what))
        text = " ".join(parts) + "."
        if made:
            text += f" Files: {', '.join(made)}."
        return text, "scan"

    def project(self) -> Section:
        intro, source = self.description()
        t = self.s.get("totals", {})
        c = self.c
        comps = sorted(
            self.rd.items("components"), key=lambda x: (-(x.get("loc_code") or 0), x["name"])
        )
        metrics = {m["component"]: m for m in self.rd.items("metrics")}
        counts = self._component_counts()
        langs = self._languages()
        rows: list[list[Any]] = []
        for comp in comps:
            m = metrics.get(comp["name"], {})
            desc, by = self.module_description(comp, counts.get(comp["name"], Counter()))
            tech = comp.get("technology") or []
            techs = list(dict.fromkeys([*tech, *langs.get(comp["name"], [])]))
            avg = m.get("complexity_avg")
            rows.append(
                [
                    comp["name"],
                    str(comp.get("type") or ""),
                    desc + (" (AI)" if by == "AI" else ""),
                    ", ".join(techs[:5]),
                    m.get("complexity") or "",
                    f"{avg:.1f}" if isinstance(avg, int | float) else "",
                    comp.get("files") or 0,
                    comp.get("loc_code") or 0,
                ]
            )
        stack = [
            [x.get("name", ""), x.get("version") or "", x.get("category") or "", _eol(x)]
            for x in self.s.get("tech_stack", [])
        ]
        lang_loc = Counter(
            {
                k: v.get("loc_code", 0)
                for k, v in self.s.get("by_language", {}).items()
                if v.get("loc_code")
            }
        )
        ll, lv = _top(lang_loc, 8)
        bands = Counter(r[4] for r in rows if r[4])
        cl = [b for b in COMPLEXITY_ORDER if bands.get(b)]
        loc_rows = rows[:10]
        return Section(
            "project",
            "Project",
            "Everyone",
            "series-1",
            intro,
            [
                ("Lines of code", t.get("loc_code", 0)),
                ("Files", t.get("files", 0)),
                ("Modules", t.get("components", 0)),
                ("Services", c.get("services", {}).get("total", 0)),
                ("Screens", c.get("screens", {}).get("total", 0)),
                ("Technologies", len(stack)),
            ],
            [
                Chart(
                    "loc-language",
                    "Lines of code by language",
                    "doughnut",
                    ll,
                    lv,
                    SERIES[: len(ll)],
                    "lines",
                ),
                Chart(
                    "loc-module",
                    "Lines of code by module",
                    "hbar",
                    [r[0] for r in loc_rows],
                    [r[7] for r in loc_rows],
                    ["series-1"],
                    "lines",
                ),
                Chart(
                    "complexity",
                    "Modules by complexity",
                    "bar",
                    cl,
                    [bands[b] for b in cl],
                    [
                        {
                            "Simple": "good",
                            "Medium": "warning",
                            "Complex": "serious",
                            "Very complex": "critical",
                        }[b]
                        for b in cl
                    ],
                    "modules",
                ),
            ],
            [
                Table(
                    "tech-stack",
                    "Technology stack",
                    ["Technology", "Version", "Category", "End of life"],
                    stack,
                ),
                Table(
                    "modules",
                    "Modules",
                    [
                        "Module",
                        "Type",
                        "Description",
                        "Technology",
                        "Complexity",
                        "Avg. cyclomatic",
                        "Files",
                        "Lines of code",
                    ],
                    rows,
                    "Complexity is the band of the module's services and screens (weights in "
                    "audit.yaml); descriptions marked (AI) come from the Copilot analysis, the "
                    "others from the scan.",
                ),
            ],
            f"Description from {source}.",
        )

    # ---- 2 database ------------------------------------------------------------------------------
    def _tables(self) -> list[dict[str, Any]]:
        return [o for o in self.rd.items("db_objects") if o.get("object_type") == "table"]

    def data(self) -> Section:
        sql = self.c.get("sql", {})
        grades = self.c.get("pg_grades", {})
        total = sql.get("total", 0)
        tables = sorted(
            self._tables(), key=lambda o: (-len(o.get("referenced_by") or []), o["name"])
        )
        objects = self.c.get("db_objects", {})
        procs = self.c.get("stored_procedures", 0)
        dbs = [i for i in self.rd.items("integrations") if i.get("type") == "DB"]
        db_tech = [
            x["name"] for x in self.s.get("tech_stack", []) if x.get("category") == "database"
        ]
        by_comp: dict[str, Counter[str]] = defaultdict(Counter)
        features: Counter[str] = Counter()
        for st in self.rd.items("sql_statements"):
            comp = st.get("component") or "(no module)"
            by_comp[comp]["total"] += 1
            by_comp[comp][st.get("pg_grade") or "ungraded"] += 1
            if st.get("dynamic"):
                by_comp[comp]["dynamic"] += 1
            features.update(st.get("vendor_features") or [])
        intro = [
            f"The code holds {_plural(total, 'SQL statement')} "
            f"({_num(sql.get('distinct', 0))} distinct, {_num(sql.get('dynamic', 0))} built at "
            f"run time) on {_plural(len(tables), 'table')}"
            + (f" in {', '.join(dict.fromkeys(db_tech))}" if db_tech else "")
            + ".",
            f"{_pct(grades.get('auto', 0), total)} convert to PostgreSQL automatically, "
            f"{_pct(grades.get('review', 0), total)} need a review and "
            f"{_pct(grades.get('manual', 0), total)} a manual rewrite"
            + (f"; {_plural(procs, 'stored procedure')} to migrate." if procs else "."),
        ]
        types = Counter(sql.get("by_type", {}))
        tl, tv = _top(types, 8)
        crud_rows = [
            [
                o["name"],
                len(o.get("referenced_by") or []),
                "".join(k for k in "CRUD" if k in (o.get("crud") or {})),
            ]
            for o in tables
        ]
        comp_rows = [
            [
                k,
                v["total"],
                v.get("auto", 0),
                v.get("review", 0),
                v.get("manual", 0),
                v.get("dynamic", 0),
            ]
            for k, v in sorted(by_comp.items(), key=lambda kv: (-kv[1]["total"], kv[0]))
        ]
        return Section(
            "data",
            "Database and queries",
            "Architects and DBAs",
            "series-3",
            intro,
            [
                ("SQL statements", total),
                ("Distinct", sql.get("distinct", 0)),
                ("Built at run time", sql.get("dynamic", 0)),
                ("Tables", len(tables)),
                ("Stored procedures", procs),
                ("PostgreSQL auto", _pct(grades.get("auto", 0), total)),
            ],
            [
                Chart(
                    "pg-grade",
                    "PostgreSQL readiness",
                    "doughnut",
                    GRADES,
                    [grades.get(g, 0) for g in GRADES],
                    [GRADE_TOKEN[g] for g in GRADES],
                    "statements",
                ),
                Chart(
                    "sql-type", "SQL statements by type", "bar", tl, tv, ["series-3"], "statements"
                ),
                Chart(
                    "top-tables",
                    "Most used tables",
                    "hbar",
                    [r[0] for r in crud_rows[:10]],
                    [r[1] for r in crud_rows[:10]],
                    ["series-6"],
                    "statements",
                ),
            ],
            [
                Table(
                    "tables",
                    "Tables",
                    ["Table", "SQL statements", "CRUD"],
                    crud_rows,
                    "CRUD: Create, Read, Update, Delete seen in the code.",
                ),
                Table(
                    "sql-by-module",
                    "SQL by module",
                    ["Module", "Statements", "Auto", "Review", "Manual", "Built at run time"],
                    comp_rows,
                    "Auto / Review / Manual: PostgreSQL grade of each statement.",
                ),
                Table(
                    "databases",
                    "Databases connected",
                    ["Target", "Usages", "Modules"],
                    [
                        [i["target"], i.get("usages", 0), ", ".join(i.get("components") or [])]
                        for i in dbs
                    ],
                ),
                Table(
                    "vendor-features",
                    "Vendor-specific SQL features",
                    ["Feature", "Statements"],
                    [[k, v] for k, v in features.most_common(15)],
                ),
                Table(
                    "object-types",
                    "Database objects",
                    ["Kind", "Count"],
                    [[k, v] for k, v in sorted(objects.items())],
                ),
            ],
        )

    # ---- 3 integrations --------------------------------------------------------------------------
    def integrations(self) -> Section:
        items = sorted(
            self.rd.items("integrations"),
            key=lambda i: (str(i.get("purpose") or ""), i["type"], i["target"]),
        )
        stories = {x["integration_id"]: x for x in self.fn.get("interfaces", []) or []}
        purposes = Counter(str(i.get("purpose") or i["type"].lower()) for i in items)
        types = Counter(i["type"] for i in items)
        logins = [i for i in items if i.get("purpose") == "login / authentication"]
        web = sum(1 for i in items if i["type"] in ("HTTP", "SOAP"))
        files_n = sum(1 for i in items if i.get("purpose") == "file transfer")
        msg = sum(1 for i in items if i.get("purpose") == "messaging")
        intro = [
            f"{self.app} talks to {_plural(len(items), 'other system')}: {_num(web)} over "
            f"HTTP/HTTPS, {_num(files_n)} by file transfer, {_num(msg)} by messaging"
            + (
                f", and signs users in through {', '.join(sorted({self._idp(i) for i in logins}))}"
                if logins
                else ""
            )
            + ".",
            "Each system is listed once, with every place in the code that uses it.",
        ]
        rows = []
        for i in items:
            story = stories.get(i["id"])
            if story and story.get("purpose"):
                partner = f"{story['partner']}: " if story.get("partner") else ""
                desc = f"{partner}{story['purpose']} (AI)"
            else:
                desc = i.get("description") or ""
            rows.append(
                [
                    i.get("purpose") or "",
                    i["type"],
                    i["target"],
                    i.get("direction") or "",
                    i.get("usages", 0),
                    ", ".join(i.get("components") or []),
                    desc,
                ]
            )
        pl, pv = _top(purposes, 8)
        tl, tv = _top(types, 8)
        return Section(
            "integrations",
            "Integrations",
            "Architects",
            "series-2",
            intro,
            [
                ("Integrations", len(items)),
                ("HTTP / HTTPS", web),
                ("File transfer", files_n),
                ("Messaging", msg),
                ("Login / authentication", len(logins)),
                ("Usages in code", sum(int(i.get("usages") or 0) for i in items)),
            ],
            [
                Chart(
                    "by-purpose",
                    "Integrations by purpose",
                    "doughnut",
                    pl,
                    pv,
                    SERIES[: len(pl)],
                    "integrations",
                ),
                Chart(
                    "by-type", "Integrations by type", "bar", tl, tv, ["series-2"], "integrations"
                ),
            ],
            [
                Table(
                    "integrations",
                    "Integrations",
                    ["Purpose", "Type", "Target", "Direction", "Usages", "Modules", "Description"],
                    rows,
                    "Descriptions marked (AI) come from the Copilot analysis, the others from "
                    "the scan.",
                ),
            ],
        )

    @staticmethod
    def _idp(i: dict[str, Any]) -> str:
        m = re.match(r"Login / authentication with (.+?) \(", i.get("description") or "")
        return m.group(1) if m else "an identity provider"

    # ---- 4 batch ---------------------------------------------------------------------------------
    def batch(self) -> Section:
        bflows = flows.batch_flows(self.rd)
        said: dict[str, str] = {}
        for f in bflows:
            com = flows.code_comments(self.rd, f)
            if com:
                said[f.id] = com[0]["lines"][0]
        stories = {b["job_id"]: b for b in self.fn.get("batches", []) or []}
        rows = batches.batch_list(self.rd, bflows, said, stories)
        bj = self.c.get("batch_jobs", {})
        kinds = Counter(r["kind"] for r in rows)
        sources = Counter(
            {
                "autosys": "scheduler",
                "cron": "scheduler",
                "jcl": "scheduler",
                "spring-batch": "scheduler",
            }.get(r["when_from"], r["when_from"])
            for r in rows
        )
        source_words = {
            "scheduler": "scheduler definition",
            "AI (Copilot)": "Copilot analysis",
            "code": "stated in the code",
            "none": "no schedule found",
        }
        sl = [k for k in ("scheduler", "AI (Copilot)", "code", "none") if sources.get(k)]
        gaps = [
            c.get("label")
            for c in self.rd.run.get("coverage", [])
            if c.get("kind") in ("autosys", "crontab") and c.get("status") == "missing"
        ]
        intro = [
            f"{_plural(bj.get('total', 0), 'batch', 'batches')} counted: "
            f"{_num(bj.get('scheduled', 0))} scheduled jobs and "
            f"{_num(bj.get('unscheduled', 0))} batch modules that no scheduler definition starts"
            + (
                f" ({_plural(bj.get('boxes', 0), 'box', 'boxes')} group the jobs)"
                if bj.get("boxes")
                else ""
            )
            + ".",
            (
                f"Not provided: {', '.join(str(g) for g in gaps)}; when a batch runs is then taken "
                "from what its code states, or shown as not found."
            )
            if gaps
            else "Schedules come from the scheduler definitions provided.",
        ]
        table_rows = [
            [
                r["name"],
                r["kind"],
                r.get("component") or "",
                r["what"],
                r["when"],
                source_words.get(
                    {
                        "autosys": "scheduler",
                        "cron": "scheduler",
                        "jcl": "scheduler",
                        "spring-batch": "scheduler",
                    }.get(r["when_from"], r["when_from"]),
                    r["when_from"],
                ),
                r["steps"],
            ]
            for r in rows
        ]
        kl = [k for k, _ in kinds.most_common()]
        return Section(
            "batch",
            "Batches",
            "Operations and architects",
            "series-7",
            intro,
            [
                ("Batches", bj.get("total", 0)),
                ("Scheduled jobs", bj.get("scheduled", 0)),
                ("No scheduler definition", bj.get("unscheduled", 0)),
                ("Boxes", bj.get("boxes", 0)),
                ("In the list", len(rows)),
            ],
            [
                Chart(
                    "batch-kind",
                    "Batches by kind",
                    "bar",
                    kl,
                    [kinds[k] for k in kl],
                    ["series-7"],
                    "batches",
                ),
                Chart(
                    "schedule-source",
                    "Where the schedule comes from",
                    "doughnut",
                    [source_words[k] for k in sl],
                    [sources[k] for k in sl],
                    [
                        {
                            "scheduler": "good",
                            "AI (Copilot)": "series-7",
                            "code": "warning",
                            "none": "critical",
                        }[k]
                        for k in sl
                    ],
                    "batches",
                ),
            ],
            [
                Table(
                    "batches",
                    "All batches",
                    [
                        "Batch",
                        "Kind",
                        "Module",
                        "What it does",
                        "When it runs",
                        "Schedule from",
                        "Steps",
                    ],
                    table_rows,
                    "What it does: Copilot analysis, else the scheduler's description, else "
                    "the code's header comment, else the scan.",
                ),
            ],
        )


# ---- deck for the PowerPoint ------------------------------------------------------------------
def _cell(v: Any) -> str:
    return _num(v) if isinstance(v, int | float) else _cut(str(v or ""), 90)


def to_deck(rd: RunData, sections: list[Section], branding: Branding) -> dict[str, Any]:
    """The summary as ``deck.json`` slides, for ``pptx_build.build_on_template``."""
    app = rd.run.get("application") or "Application"
    slides: list[dict[str, Any]] = [
        {
            "id": "title",
            "section": SECTIONS[0][1],
            "audience": SECTIONS[0][2],
            "type": "title",
            "title": f"{app}: summary",
            "subtitle": f"Run {rd.run.get('run_id', '')} · "
            f"{str(rd.run.get('started_at', ''))[:10]}",
            "blocks": [],
            "notes": "",
            "stale": [],
            "color": "series-1",
        }
    ]

    def slide(
        sec: Section, sid: str, title: str, blocks: list[dict[str, Any]], layout: str = ""
    ) -> None:
        slides.append(
            {
                "id": f"{sec.key}-{sid}",
                "section": sec.title,
                "audience": sec.audience,
                "type": "content",
                "title": title,
                "subtitle": "",
                "blocks": blocks,
                "notes": sec.source_note,
                "stale": [],
                "color": sec.color,
                "layout": layout,
            }
        )

    def chart(c: Chart) -> dict[str, Any]:
        colors = (
            c.colors
            if len(c.colors) == len(c.labels)
            else [c.colors[0] if c.colors else "accent"] * len(c.labels)
        )
        return {
            "type": "chart",
            "chart": "bar" if c.kind == "hbar" else c.kind,
            "title": c.title,
            "labels": c.labels,
            "values": c.values,
            "unit": c.unit,
            "colors": colors,
        }

    def table(t: Table, cols: list[int] | None = None) -> dict[str, Any]:
        idx = cols or list(range(len(t.columns)))
        return {
            "type": "table",
            "title": t.title,
            "columns": [t.columns[i] for i in idx],
            "rows": [[_cell(r[i]) for i in idx] for r in t.rows[:SLIDE_ROWS]],
            "more": max(0, len(t.rows) - SLIDE_ROWS),
        }

    for sec in sections:
        slides.append(
            {
                "id": f"section-{sec.key}",
                "section": sec.title,
                "audience": sec.audience,
                "type": "section",
                "title": sec.title,
                "subtitle": f"For {sec.audience.lower()}",
                "color": sec.color,
                "blocks": [],
                "notes": "",
                "stale": [],
            }
        )
        kpis = {
            "type": "kpis",
            "items": [{"label": k, "value": v, "metric": "", "unit": ""} for k, v in sec.kpis],
        }
        first_chart = [chart(sec.charts[0])] if sec.charts else []
        slide(
            sec,
            "overview",
            f"{sec.title} at a glance",
            [kpis, {"type": "bullets", "items": sec.intro}, *first_chart],
            "wide-left",
        )
        if len(sec.charts) > 1:
            slide(sec, "charts", f"{sec.title}: figures", [chart(c) for c in sec.charts[1:3]])
        for t in sec.tables:
            if not t.rows:
                continue
            cols = None
            if t.key == "modules":
                cols = [0, 2, 3, 4, 7]
            elif t.key == "integrations":
                cols = [0, 1, 2, 3, 4]
            elif t.key == "batches":
                cols = [0, 3, 4, 5]
            slide(sec, slug(t.key), t.title, [table(t, cols)])
            if t.key == "integrations":
                slide(
                    sec,
                    "integrations-described",
                    "What each integration is for",
                    [table(t, [1, 2, 6])],
                )
    return {
        "version": "1",
        "application": app,
        "run_id": rd.run.get("run_id", ""),
        "branding": branding.model_dump(mode="json"),
        "palette": PALETTE,
        "sections": [{"key": s.key, "title": s.title, "audience": s.audience} for s in sections],
        "slides": slides,
    }


# ---- build --------------------------------------------------------------------------------------
@dataclass
class SummaryResult:
    page: Path
    pptx: Path | None
    message: str
    template: str | None = None


def _env() -> Environment:
    return Environment(
        loader=PackageLoader("legacy_audit.report", "summary_html"),
        autoescape=select_autoescape(["html"]),
        undefined=ChainableUndefined,
        trim_blocks=True,
        lstrip_blocks=True,
    )


def _write_csv(path: Path, t: Table) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(t.columns)
        w.writerows(t.rows)


def build_sections(run_dir: Path) -> tuple[RunData, list[Section]]:
    rd = RunData.load(run_dir)
    s = _Summary(rd, load_enrichment(run_dir))
    return rd, [s.project(), s.data(), s.integrations(), s.batch()]


def build_summary_report(
    run_dir: Path,
    branding: Branding | None = None,
    config_dir: Path | None = None,
    template: str | None = None,
) -> SummaryResult:
    """Write ``reports/summary/`` (index.html, summary.json, deck.json, CSVs, summary.pptx)."""
    branding = branding or Branding()
    rd, sections = build_sections(run_dir)
    out = run_dir / "reports" / "summary"
    if out.exists():
        shutil.rmtree(out)
    (out / "data").mkdir(parents=True)
    (out / "assets").mkdir()
    vendor = files("legacy_audit.report").joinpath("assets").joinpath("vendor")
    for item in vendor.iterdir():
        if item.name.startswith(("chart-", "LICENSE-chartjs")):
            (out / "assets" / item.name).write_bytes(item.read_bytes())
    for sec in sections:
        for t in sec.tables:
            _write_csv(out / "data" / f"{t.key}.csv", t)
    deck = to_deck(rd, sections, branding)
    pptx: Path | None = None
    label: str | None = None
    try:
        chosen = resolve_template(template or branding.pptx_template, config_dir)
        label = chosen.label
        from legacy_audit.report.pptx_build import build_on_template

        deck["pptx_template"] = {"id": chosen.id, "label": chosen.label, "file": chosen.path.name}
        pptx = out / "summary.pptx"
        build_on_template(deck, chosen, out, pptx)
        message = f"PowerPoint written ({chosen.label} template)."
    except TemplateError as err:
        pptx, message = None, f"PowerPoint skipped: {err}."
    except Exception as err:  # a damaged template or a file in use: report it, never crash
        pptx, message = None, f"PowerPoint failed: {type(err).__name__}: {err}"
    write_json(out / "deck.json", deck)  # the slide plan the PowerPoint was built from
    write_json(
        out / "summary.json",
        {
            "application": deck["application"],
            "run_id": rd.run.get("run_id"),
            "sections": [asdict(sec) for sec in sections],
        },
    )
    chart_js = next((p.name for p in (out / "assets").glob("chart-*.js")), "")
    html = (
        _env()
        .get_template("summary.html")
        .render(
            app=deck["application"],
            run=rd.run,
            sections=sections,
            palette=PALETTE,
            fmt=_num,
            pptx=pptx.name if pptx else None,
            template=label,
            message=message,
            chart_js=chart_js,
            charts=[
                {
                    "id": f"{s.key}-{c.key}",
                    "kind": c.kind,
                    "labels": c.labels,
                    "values": c.values,
                    "colors": [
                        f"#{PALETTE.get(x, PALETTE['accent'])}"
                        for x in (
                            c.colors
                            if len(c.colors) == len(c.labels)
                            else [c.colors[0] if c.colors else "accent"] * len(c.labels)
                        )
                    ],
                    "unit": c.unit,
                }
                for s in sections
                for c in s.charts
            ],
        )
    )
    (out / "index.html").write_text(html, encoding="utf-8")
    latest = latest_copy(run_dir)
    if latest is not None:
        dest = latest / "reports" / "summary"
        if dest.exists():
            shutil.rmtree(dest)
        shutil.copytree(out, dest)
    return SummaryResult(out / "index.html", pptx, message, label)
