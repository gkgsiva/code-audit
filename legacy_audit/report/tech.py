"""``legacy-audit report --type tech``: a static, self-contained HTML site built from one run.

Pages are rendered with Jinja2 from ``tech_html/``; catalogues are written as ``data/*.js`` and
drawn by ``assets/report.js``; Chart.js and Mermaid are vendored in ``assets/vendor/``. The site
opens from disk (``file://``) with no server and no network. It is written to
``runs/<id>/reports/tech/``; when ``latest`` is a copy of that run (no symlinks on the machine),
the copy gets its own build so its relative links stay right.
"""

from __future__ import annotations

import csv
import json
import re
import shutil
from collections import Counter, defaultdict
from collections.abc import Callable
from importlib.resources import files
from pathlib import Path
from typing import Any

from jinja2 import ChainableUndefined, Environment, PackageLoader, select_autoescape

from legacy_audit.report import batches, diagrams, flows
from legacy_audit.report import catalogues as cat
from legacy_audit.report import visuals as vz
from legacy_audit.report.data import RunData, slug
from legacy_audit.report.servers import server_rows

SEVERITIES = ["critical", "high", "medium", "low", "info"]
SEVERITY_COLOR = {
    "critical": "critical",
    "high": "serious",
    "medium": "warning",
    "low": "info",
    "info": "info",
}
GRADES = ["auto", "review", "manual"]
GRADE_COLOR = {"auto": "good", "review": "warning", "manual": "critical"}
COPYLEFT = re.compile(r"\b(A?GPL|LGPL|EPL|MPL|CDDL|EUPL|CC-BY-SA)", re.I)
_SECRET_LINE = re.compile(
    r"^(\s*[\w.-]*(?:passw(?:or)?d|pwd|passphrase|secret|api[_-]?key|token|credential)[\w.-]*"
    r"\s*[:=]\s*)(\S.*)$",
    re.I | re.M,
)
NAV = [
    ("overview", "index.html", "Overview"),
    ("components", "components.html", "Components"),
    ("architecture", "architecture.html", "Architecture"),
    ("flows", "flows.html", "Flows"),
    ("data-model", "data-model.html", "Data model"),
    ("services", "services.html", "Services"),
    ("screens", "screens.html", "Screens"),
    ("sql", "sql.html", "SQL"),
    ("db-objects", "db-objects.html", "Database"),
    ("integrations", "integrations.html", "Integrations"),
    ("web-services", "web-services.html", "Web services"),
    ("batch-jobs", "batch-jobs.html", "Batch jobs"),
    ("libraries", "libraries.html", "Libraries"),
    ("postgresql", "postgresql.html", "PostgreSQL"),
    ("security", "security.html", "Security"),
    ("findings", "findings.html", "Findings"),
    ("analysis", "analysis.html", "Analysis"),
    ("appendix", "appendix.html", "Appendix"),
]
CATALOGUE_PAGES = {
    "services": ["services"],
    "screens": ["screens"],
    "sql": ["sql"],
    "db-objects": ["db-objects", "crud"],
    "integrations": ["integrations"],
    "web-services": ["web-services", "web-operations"],
    "batch-jobs": ["batches", "batch-jobs"],
    "libraries": ["libraries"],
}
GLOSSARY = [
    (
        "Service",
        "An independently invokable operation: Struts action, Spring handler, servlet, "
        "web service operation, CICS transaction, batch job.",
    ),
    (
        "Screen",
        "A rendered page or map (JSP/JSF/HTML view, BMS map); partials are included "
        "fragments, not counted.",
    ),
    ("Component", "A buildable or deployable unit found from build files and folders."),
    (
        "Integration",
        "One external system reached in one direction: database, queue, host, file drop, partner.",
    ),
    (
        "Web service",
        "A REST or SOAP service, listed once with its operations: offered (other systems call "
        "the application) or called (the application calls another system).",
    ),
    (
        "PG grade",
        "auto: converts to PostgreSQL cleanly; review: vendor syntax to check; manual: "
        "stored code, unparseable SQL, or SQL whose structure is only known at run time.",
    ),
    ("Value hole", "A value concatenated into SQL text at run time (?): bind it; injection risk."),
    (
        "Structural hole",
        "A table, column or clause only known at run time (?): the statement "
        "cannot be converted by a script.",
    ),
    (
        "Evidence",
        "The file and line every fact was found at; links open the file, or VS Code at the line.",
    ),
    (
        "Coverage gap",
        "An input that was not provided or a tool that did not run; reported, never "
        "treated as 'nothing found'.",
    ),
    (
        "Dead-code candidate",
        "A source file nothing reachable uses; candidates only, since "
        "reflection and outside callers are invisible to a scan.",
    ),
]


# KPI tile -> (icon, colour class); colours follow the section the tile links to.
TILE_STYLE = {
    "totals.files": ("files", "c1"),
    "totals.loc_code": ("code", "c1"),
    "totals.components": ("components", "c3"),
    "counts.services.total": ("services", "c7"),
    "counts.screens.total": ("screens", "c1"),
    "counts.sql.total": ("sql", "c6"),
    "counts.pg_grades.manual": ("warning", "c8"),
    "counts.stored_procedures": ("procedure", "c6"),
    "counts.integrations.total": ("integrations", "c2"),
    "counts.web_services.total": ("integrations", "c2"),
    "counts.batch_jobs.total": ("batch", "c4"),
    "counts.libraries.total": ("libraries", "c5"),
    "counts.vulnerable_libraries.total": ("shield", "c8"),
    "counts.eol_libraries": ("eol", "c8"),
    "totals.findings": ("findings", "c2"),
    "effort.likely": ("effort", "c7"),
}
COMPONENT_TILE_STYLE = {
    "component.files": ("files", "c1"),
    "component.loc_code": ("code", "c1"),
    "component.services": ("services", "c7"),
    "component.screens": ("screens", "c1"),
    "component.sql": ("sql", "c6"),
    "component.integrations": ("integrations", "c2"),
    "component.jobs": ("batch", "c4"),
    "component.findings": ("findings", "c2"),
}


def _env() -> Environment:
    env = Environment(
        loader=PackageLoader("legacy_audit.report", "tech_html"),
        autoescape=select_autoescape(["html"]),
        undefined=ChainableUndefined,  # optional fact fields render empty
        trim_blocks=True,
        lstrip_blocks=True,
    )
    env.globals["icon"] = vz.icon
    return env


def build_tech_report(run_dir: Path, inputs_dir: Path | None = None) -> Path:
    """Build the site for a run; returns ``reports/tech/index.html`` of that run."""
    rd = RunData.load(run_dir, inputs_dir)
    out = run_dir / "reports" / "tech"
    _build_into(rd, out)
    latest = run_dir.parent.parent / "latest"
    if latest.is_dir() and not latest.is_symlink() and latest.resolve() != run_dir.resolve():
        run_json = latest / "run.json"
        if run_json.exists():
            latest_id = json.loads(run_json.read_text(encoding="utf-8")).get("run_id")
            if latest_id == rd.run.get("run_id"):
                _build_into(rd, latest / "reports" / "tech")
    return out / "index.html"


def _build_into(rd: RunData, out: Path) -> None:
    if out.exists():
        shutil.rmtree(out)
    (out / "data").mkdir(parents=True)
    _copy_assets(out / "assets")
    from legacy_audit.report.deck_builder import load_enrichment

    fn = load_enrichment(rd.run_dir).get("functional") or {}
    stories = rd.by_row({i["integration_id"]: i for i in fn.get("interfaces", []) or []})
    ws_stories = {w["web_service_id"]: w for w in fn.get("web_services", []) or []}
    cats = cat.build(rd, out, stories, ws_stories)
    if rd.audit_only:  # no target database: no PostgreSQL grades
        for name in ("sql", "db-objects"):
            if name in cats:
                cats[name].drop("grade")
                cats[name].intro = cats[name].intro.replace(", with its PostgreSQL grade", "")
    for c in cats.values():
        _write_data_js(out / "data" / f"{c.name}.js", c.name, c.payload())
    if rd.has_sbom:
        shutil.copyfile(rd.run_dir / "data" / "sbom.cdx.json", out / "sbom.cdx.json")
    page = _Pages(rd, out, cats, _env())
    cats["batches"] = cat.batches(rd, out, page.batch_rows)
    _write_data_js(out / "data" / "batches.js", "batches", cats["batches"].payload())
    _write_batches_csv(out / "data" / "batches.csv", page.batch_rows)
    _write_catalogue_csv(out / "data" / "web-services.csv", cats["web-services"])
    page.overview()
    page.components()
    page.architecture()
    page.flows()
    page.data_model()
    for page_id, names in CATALOGUE_PAGES.items():
        page.catalogue(page_id, [cats[n] for n in names])
    if not rd.audit_only:
        page.postgresql()
    page.security()
    page.findings()
    page.analysis()
    page.appendix()


def _copy_assets(dest: Path) -> None:
    src = files("legacy_audit.report").joinpath("assets")
    dest.mkdir(parents=True, exist_ok=True)
    for item in src.iterdir():
        if item.is_dir():
            (dest / item.name).mkdir(exist_ok=True)
            for f in item.iterdir():
                if f.is_file():
                    (dest / item.name / f.name).write_bytes(f.read_bytes())
        elif item.is_file():
            (dest / item.name).write_bytes(item.read_bytes())


def _write_batches_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    """The consolidated batch list for Excel (UTF-8 with BOM so Excel reads accents)."""
    cols = ["name", "kind", "component", "what", "what_from", "when", "when_from", "scheduler",
            "steps", "when_file", "when_line"]  # fmt: skip
    with path.open("w", encoding="utf-8-sig", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(cols)
        for r in rows:
            w.writerow(["" if r.get(c) is None else r.get(c) for c in cols])


def _write_catalogue_csv(path: Path, c: cat.Catalogue) -> None:
    """A catalogue as CSV for Excel: links and evidence become their text."""

    def cell(v: Any) -> Any:
        return v.get("text", "") if isinstance(v, dict) else v

    with path.open("w", encoding="utf-8-sig", newline="") as fh:
        w = csv.writer(fh)
        w.writerow([col.label for col in c.columns])
        w.writerows([[cell(v) for v in row] for row in c.rows])


def _write_data_js(path: Path, name: str, payload: Any) -> None:
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    path.write_text(
        f"window.LA_DATA=window.LA_DATA||{{}};window.LA_DATA[{json.dumps(name)}]={body};\n",
        encoding="utf-8",
    )


def _get(d: Any, path: str, default: Any = 0) -> Any:
    for part in path.split("."):
        if not isinstance(d, dict) or part not in d:
            return default
        d = d[part]
    return d


def _fmt(v: Any) -> str:
    if isinstance(v, bool):
        return str(v)
    if isinstance(v, int):
        return f"{v:,}"
    if isinstance(v, float):
        return f"{v:,.1f}"
    return str(v)


def bar(
    chart_id: str,
    title: str,
    labels: list[str],
    values: list[float],
    colors: list[str] | str = "series-1",
    *,
    label: str = "Count",
    category: str = "",
    unit: str = "",
    horizontal: bool = False,
    note: str = "",
) -> dict[str, Any]:
    """A one-series bar chart (``colors``: CSS token names, one per bar or one for all)."""
    cols = [colors] * len(labels) if isinstance(colors, str) else colors
    spec = {
        "labels": labels,
        "values": values,
        "colors": cols,
        "label": label,
        "unit": unit,
        "horizontal": horizontal,
    }
    return {
        "id": chart_id,
        "title": title,
        "labels": labels,
        "values": values,
        "label": label,
        "category": category or title,
        "spec": spec,
        "note": note,
    }


class _Pages:
    def __init__(self, rd: RunData, out: Path, cats: dict[str, cat.Catalogue], env: Environment):
        self.rd = rd
        self.out = out
        self.cats = cats
        self.env = env
        s = rd.summary
        self.counts = s.get("counts", {})
        from legacy_audit.report.deck_builder import load_enrichment

        self.e = load_enrichment(rd.run_dir)
        self.screen_flows = flows.screen_flows(rd)
        self.batch_flows = flows.batch_flows(rd)
        self.batch_comments = {f.id: flows.code_comments(rd, f) for f in self.batch_flows}
        self.batch_said = {  # first line of the code's own comment, for the batch cards
            fid: c[0]["lines"][0] for fid, c in self.batch_comments.items() if c
        }
        stories = {
            b["job_id"]: b for b in (self.e.get("functional") or {}).get("batches", []) or []
        }
        self.batch_rows = batches.batch_list(rd, self.batch_flows, self.batch_said, stories)
        run = rd.run
        self.common = {
            "app": run.get("application") or s.get("run", {}).get("application") or "application",
            "run_id": run.get("run_id", ""),
            "run_date": (run.get("started_at") or "")[:16].replace("T", " "),
            "tool_version": run.get("tool_version", ""),
            "sources": run.get("sources", []),
            "badges": self._badges(),
            "nav": [
                {"id": i, "href": h, "title": t}
                for i, h, t in NAV
                if not (i == "postgresql" and rd.audit_only)
            ],
            "target_label": rd.target.get("label", ""),
            "audit_only": rd.audit_only,
            "effort_label": (rd.effort or {}).get("label", "estimate"),
            "doc_kind": "Technical audit",
        }

    # ---- shared -------------------------------------------------------------------------------
    def _badges(self) -> list[dict[str, str]]:
        out = []
        for c in self.rd.run.get("coverage", []):
            if c.get("kind") in ("dependency-check", "osv-scanner"):
                continue
            n = c.get("file_count", 0)
            status = c.get("status", "missing")
            text = (
                f"{c['label']}: {n} file(s)"
                if status == "present"
                else f"{c['label']}: not provided"
            )
            out.append({"status": status, "text": text, "title": c.get("path", "")})
        for v in self.rd.run.get("vulnerability_sources", []):
            name = {"dependency-check": "Dependency-Check", "osv-scanner": "OSV-Scanner"}.get(
                v["name"], v["name"]
            )
            if v.get("status") in ("imported", "ran"):
                date = v.get("database_date")
                text = f"{name}: {v['status']}" + (f", database {date}" if date else "")
                out.append({"status": "present", "text": text, "title": ""})
            else:
                text = f"{name}: {v.get('detail') or v.get('status')}"
                out.append({"status": "missing", "text": text, "title": ""})
        return out

    def _render(self, template: str, path: str, page_id: str, title: str, **ctx: Any) -> None:
        depth = path.count("/")
        html = self.env.get_template(template).render(
            **self.common,
            base="../" * depth,
            page_id=page_id,
            page_title=title,
            data_scripts=ctx.pop("data_scripts", []),
            charts=ctx.pop("charts", False),
            diagrams=ctx.pop("diagrams", False),
            **ctx,
        )
        target = self.out / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(html, encoding="utf-8")

    def _link(self, file: str | None, line: int | None) -> dict[str, Any]:
        return self.rd.evidence_links(self.out, file, line)

    def _tile(self, key: str, label: str, href: str = "", sub: str = "") -> dict[str, Any]:
        value = _get(self.rd.summary, key)
        ico, color = TILE_STYLE.get(key, ("files", "c1"))
        return {
            "key": key,
            "label": label,
            "value": value,
            "display": _fmt(value),
            "href": href,
            "sub": sub,
            "icon": ico,
            "color": color,
        }

    # ---- pages --------------------------------------------------------------------------------
    def overview(self) -> None:
        s, c = self.rd.summary, self.counts
        effort = self.rd.effort or {}
        tiles = [
            self._tile(
                "totals.files", "Files", "", f"{_fmt(_get(s, 'totals.input_files'))} input files"
            ),
            self._tile(
                "totals.loc_code",
                "Code lines",
                "",
                f"{_fmt(_get(s, 'totals.loc_comment'))} comment lines",
            ),
            self._tile("totals.components", "Components", "components.html"),
            self._tile("counts.services.total", "Services", "services.html"),
            self._tile(
                "counts.screens.total",
                "Screens",
                "screens.html",
                f"{_fmt(_get(c, 'screens.partials'))} partials not counted",
            ),
            self._tile(
                "counts.sql.total",
                "SQL statements",
                "sql.html",
                f"{_fmt(_get(c, 'sql.distinct'))} distinct, {_fmt(_get(c, 'sql.dynamic'))} dynamic",
            ),
            *(
                []
                if self.rd.audit_only
                else [
                    self._tile(
                        "counts.pg_grades.manual", "SQL needing manual work", "postgresql.html"
                    )
                ]
            ),
            self._tile(
                "counts.stored_procedures",
                "Stored procedures",
                "db-objects.html" if self.rd.audit_only else "postgresql.html",
            ),
            self._tile("counts.integrations.total", "Integrations", "integrations.html"),
            self._tile(
                "counts.web_services.total",
                "Web services",
                "web-services.html",
                f"{_fmt(_get(c, 'web_services.offered'))} offered, "
                f"{_fmt(_get(c, 'web_services.called'))} called",
            ),
            self._tile(
                "counts.batch_jobs.total",
                "Batch jobs",
                "batch-jobs.html",
                f"{_fmt(_get(c, 'batch_jobs.scheduled'))} scheduled, "
                f"{_fmt(_get(c, 'batch_jobs.unscheduled'))} with no schedule found",
            ),
            self._tile(
                "counts.libraries.total",
                "Libraries",
                "libraries.html",
                f"{_fmt(_get(c, 'libraries.undeclared'))} undeclared",
            ),
            self._tile(
                "counts.vulnerable_libraries.total",
                "Vulnerable libraries",
                "security.html",
                f"{_fmt(_get(c, 'vulnerable_libraries.vulnerabilities'))} vulnerabilities",
            ),
            self._tile("counts.eol_libraries", "End-of-life libraries", "security.html"),
            self._tile("totals.findings", "Findings", "findings.html"),
        ]
        if effort:
            tiles.append(
                self._tile(
                    "effort.likely",
                    "Effort, likely (person-days)",
                    "",
                    f"{_fmt(effort.get('low'))} to {_fmt(effort.get('high'))}",
                )
            )
        langs = sorted(
            ((k, v.get("loc_code", 0)) for k, v in s.get("by_language", {}).items()),
            key=lambda kv: -kv[1],
        )
        langs = [kv for kv in langs if kv[1] > 0]
        top_langs = langs[:7]
        if len(langs) > 7:
            top_langs.append(("other", sum(v for _, v in langs[7:])))
        loc_chart = vz.chart(
            "loc",
            "Code lines by language",
            "doughnut",
            [k for k, _ in top_langs],
            [v for _, v in top_langs],
            colors=[f"series-{i % 8 + 1}" for i in range(len(top_langs))],
            label="Code lines",
            category="Language",
            unit="lines",
            note="Top 7 languages; the rest are grouped as 'other'." if len(langs) > 7 else "",
        )
        band_chart = self._band_chart()
        gauges = self._gauges()
        metrics = {m["component"]: m for m in s.get("component_metrics", [])}
        comps = []
        for comp in self.rd.items("components"):
            m = metrics.get(comp["name"], {})
            comps.append(
                {
                    **comp,
                    "slug": slug(comp["name"]),
                    "complexity": m.get("complexity"),
                    "duplication_pct": m.get("duplication_pct"),
                    "test_ratio": m.get("test_ratio"),
                    "dead_code_candidates": m.get("dead_code_candidates"),
                }
            )
        largest = [{**f, "link": self._link(f["path"], None)} for f in s.get("largest_files", [])]
        self._render(
            "overview.html",
            "index.html",
            "overview",
            "Overview",
            tiles=tiles,
            loc_chart=loc_chart,
            band_chart=band_chart,
            gauges=gauges,
            tech_stack=sorted(
                self.rd.items("tech_stack"), key=lambda t: (t.get("category") or "", t["name"])
            ),
            servers=[
                {
                    **r,
                    "links": [self._link(f, r["lines"].get(f)) for f in r["files"]],
                }
                for r in server_rows(self.rd, self.e)
            ],
            components=comps,
            effort=effort or None,
            largest_files=largest,
            charts=True,
        )

    def _band_chart(self) -> dict[str, Any]:
        """Items (services, screens, jobs, units) per complexity band, per component."""
        rows = [m for m in self.rd.items("metrics") if m.get("bands")]
        rows.sort(key=lambda m: -sum(m["bands"].values()))
        rows = rows[:15]
        labels = [m["component"] for m in rows]
        datasets = [
            {
                "label": band,
                "values": [int(m["bands"].get(band, 0)) for m in rows],
                "color": vz.BAND_COLOR[band],
            }
            for band in vz.COMPLEXITY_BANDS
        ]
        return vz.chart(
            "bands",
            "Complexity bands per component",
            "stacked",
            labels,
            datasets=datasets,
            category="Component",
            unit="items",
            horizontal=True,
            note="Services, screens and batch jobs per complexity band (weights in the "
            "configuration). Largest 15 components.",
        )

    def _gauges(self) -> list[dict[str, Any]]:
        c, rd = self.counts, self.rd
        sql_total = _get(c, "sql.total")
        libs = _get(c, "libraries.total")
        cov = rd.run.get("coverage", [])
        out = [
            vz.gauge(
                "SQL that converts automatically",
                _get(c, "pg_grades.auto"),
                sql_total,
                "good",
                f"{_fmt(_get(c, 'pg_grades.auto'))} of {_fmt(sql_total)} statements",
            ),
            vz.gauge(
                "Libraries with no known vulnerability",
                libs - _get(c, "vulnerable_libraries.total"),
                libs,
                "series-1",
                f"{_fmt(_get(c, 'vulnerable_libraries.total'))} vulnerable of {_fmt(libs)}",
            ),
            vz.gauge(
                "Libraries still supported",
                libs - _get(c, "eol_libraries"),
                libs,
                "series-3",
                f"{_fmt(_get(c, 'eol_libraries'))} end of life",
            ),
            vz.gauge(
                "Inputs provided",
                sum(1 for x in cov if x.get("status") == "present"),
                len(cov),
                "series-7",
                f"{sum(1 for x in cov if x.get('status') == 'present')} of {len(cov)} kinds",
            ),
        ]
        return out

    def components(self) -> None:
        rd = self.rd
        per: dict[str, Counter[str]] = defaultdict(Counter)
        for name, key in (
            ("services", "services"),
            ("screens", "screens"),
            ("sql_statements", "sql"),
            ("batch_jobs", "jobs"),
        ):
            for item in rd.items(name):
                if item.get("component"):
                    per[item["component"]][key] += 1
        for item in rd.items("integrations"):
            for comp in item.get("components") or []:
                per[comp]["integrations"] += 1
        for f in rd.items("findings"):
            comp = rd.file_component.get(f.get("file") or "")
            if comp:
                per[comp]["findings"] += 1
        edges: dict[tuple[str, str], Counter[str]] = defaultdict(Counter)
        for e in rd.items("dependencies"):
            a, b = rd.component_of(e["source"]), rd.component_of(e["target"])
            if a and b and a != b:
                edges[(a, b)][e["kind"]] += 1
        libs: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for lib in rd.items("libraries"):
            used_in = {rd.file_component.get(w, "") for w in lib.get("where_used") or []}
            for comp in sorted(used_in - {""}):
                libs[comp].append(
                    {
                        "name": cat._library_name(lib),
                        "version": lib.get("version") or "",
                        "action": (lib.get("recommendation") or {}).get("action", ""),
                        "cves": len(lib.get("cves") or []),
                        "eol": lib.get("eol"),
                    }
                )
        radar = self._radars(per)
        docs = {}
        for d in (self.e.get("component_docs") or {}).get("components", []):
            docs[d["component"]] = {
                **d,
                "links": [
                    self._link(ev.get("file"), ev.get("line")) for ev in d.get("evidence", [])
                ],
                "elements": [
                    {**k, "link": self._link(k["file"], None) if k.get("file") else None}
                    for k in d.get("key_elements", [])
                ],
                "tables": [rd.label(i) for i in d.get("db_object_ids", [])],
            }
        rows = []
        for comp in rd.items("components"):
            name = comp["name"]
            n = per[name]
            rows.append(
                {
                    **comp,
                    "slug": slug(name),
                    "n_services": n["services"],
                    "n_screens": n["screens"],
                    "n_sql": n["sql"],
                    "n_findings": n["findings"],
                }
            )

            def deps(pairs: list[tuple[str, Counter[str]]]) -> list[dict[str, Any]]:
                return [
                    {
                        "name": o,
                        "slug": slug(o),
                        "count": sum(k.values()),
                        "kinds": ", ".join(sorted(k)),
                    }
                    for o, k in pairs
                ]

            tiles = [
                {"key": "component.files", "label": "Files", "value": comp.get("files", 0)},
                {
                    "key": "component.loc_code",
                    "label": "Code lines",
                    "value": comp.get("loc_code", 0),
                },
                {"key": "component.services", "label": "Services", "value": n["services"]},
                {"key": "component.screens", "label": "Screens", "value": n["screens"]},
                {"key": "component.sql", "label": "SQL statements", "value": n["sql"]},
                {
                    "key": "component.integrations",
                    "label": "Integrations",
                    "value": n["integrations"],
                },
                {"key": "component.jobs", "label": "Batch jobs", "value": n["jobs"]},
                {"key": "component.findings", "label": "Findings", "value": n["findings"]},
            ]
            for t in tiles:
                t["display"], t["sub"] = _fmt(t["value"]), ""
                t["icon"], t["color"] = COMPONENT_TILE_STYLE[t["key"]]
            entry = [
                {**e, "link": self._link(*rd.first_evidence(e))}
                for e in rd.items("entry_points")
                if e.get("component") == name
            ]
            ctx = {
                **comp,
                "tiles": tiles,
                "radar": radar.get(name),
                "doc": docs.get(name),
                "flows": [
                    {"name": f.name, "slug": f.slug, "kind": f.kind, "summary": f.summary}
                    for f in [*self.screen_flows, *self.batch_flows]
                    if f.component == name
                ],
                "entry_points": entry,
                "deps_out": deps(sorted((b, k) for (a, b), k in edges.items() if a == name)),
                "deps_in": deps(sorted((a, k) for (a, b), k in edges.items() if b == name)),
                "libraries": libs.get(name, []),
            }
            self._render(
                "component.html",
                f"components/{slug(name)}.html",
                "components",
                f"Component: {name}",
                c=ctx,
                data_scripts=[
                    "services",
                    "screens",
                    "sql",
                    "integrations",
                    "web-services",
                    "batch-jobs",
                    "findings",
                ],
                charts=True,
            )
        self._render(
            "components.html", "components.html", "components", "Components", components=rows
        )

    def _radars(self, per: dict[str, Counter[str]]) -> dict[str, dict[str, Any]]:
        """Each component's profile against the largest value of each axis in this estate
        (100 = the largest); the raw values are in the tooltip and the table view."""
        metrics = {m["component"]: m for m in self.rd.items("metrics")}
        axes: list[tuple[str, Callable[[dict[str, Any], dict[str, Any]], Any]]] = [
            ("Code lines", lambda c, m: c.get("loc_code", 0)),
            ("Complexity", lambda c, m: m.get("complexity_score") or 0),
            ("Fan-in", lambda c, m: m.get("fan_in_max") or 0),
            ("Fan-out", lambda c, m: m.get("fan_out_max") or 0),
            ("Duplication %", lambda c, m: m.get("duplication_pct") or 0),
            ("Findings", lambda c, m: per[c["name"]]["findings"]),
            ("SQL", lambda c, m: per[c["name"]]["sql"]),
        ]
        comps = self.rd.items("components")
        raw = {c["name"]: [float(f(c, metrics.get(c["name"], {}))) for _, f in axes] for c in comps}
        tops = [max((r[i] for r in raw.values()), default=0) for i in range(len(axes))]
        out = {}
        for name, vals in raw.items():
            out[name] = vz.chart(
                "radar",
                "Profile against the other components",
                "radar",
                [a for a, _ in axes],
                [round(100 * v / t) if t else 0 for v, t in zip(vals, tops, strict=True)],
                label=name,
                category="Measure",
                color="series-3",
                raw=[_fmt(int(v) if float(v).is_integer() else v) for v in vals],
                note="100 = the highest value of that measure among the components; "
                "the table shows the measured values.",
            )
        return out

    def architecture(self) -> None:
        self._render(
            "architecture.html",
            "architecture.html",
            "architecture",
            "Architecture and flows",
            components_diagram=diagrams.component_graph(self.rd),
            integration_diagram=diagrams.integration_map(self.rd),
            batch_diagrams=diagrams.batch_chains(self.rd),
            layers_diagram=diagrams.layered(self.e.get("architecture") or {}),
            target_diagram=diagrams.current_to_target(
                self.rd, self.e.get("target_architecture") or {}
            ),
            diagrams=True,
        )

    # ---- flows (scan) -----------------------------------------------------------------------
    def flow_ctx(self, f: flows.Flow, story: dict[str, Any] | None = None) -> dict[str, Any]:
        """What a flow page shows: the scan's flow, plus the agent's story when there is one."""
        rd = self.rd
        steps = [{**st, "link": self._link(*st["evidence"]), "label": st["name"]} for st in f.steps]
        if story:
            story = {
                **story,
                "links": [
                    self._link(ev.get("file"), ev.get("line")) for ev in story.get("evidence", [])
                ],
                "step_list": [
                    {
                        **st,
                        "facts": [rd.label(i).rsplit("/", 1)[-1] for i in st.get("fact_ids", [])],
                    }
                    for st in sorted(story.get("steps", []), key=lambda x: x.get("order", 0))
                ],
            }
        return {
            "flow": f,
            "diagram": {
                "title": "Flow" if f.kind == "screen" else "Batch flow",
                "source": f.mermaid,
                "nodes": sum(len(n) for _, n in f.columns),
                "note": f.note,
                "legend": True,
            },
            "steps": steps,
            "tables": [
                {**t, "words": flows.crud_words(t["crud"]) if t["crud"] else "uses"}
                for t in f.tables
            ],
            "link": self._link(*f.evidence),
            "comments": [
                {**c, "link": self._link(c["file"], None)}
                for c in self.batch_comments.get(f.id, [])
            ],
            "story": story,
            "component_slug": slug(f.component) if f.component else "",
        }

    def flows(self) -> None:
        all_flows = [*self.screen_flows, *self.batch_flows]
        for f in all_flows:
            self._render(
                "flow.html",
                f"flows/{f.slug}.html",
                "flows",
                f"{'Screen' if f.kind == 'screen' else 'Batch'} flow: {f.name}",
                audience="technical",
                diagrams=True,
                **self.flow_ctx(f),
            )
        self._render(
            "flows.html",
            "flows.html",
            "flows",
            "Screen and batch flows",
            audience="technical",
            screen_flows=self.screen_flows,
            batch_flows=self.batch_flows,
            said=self.batch_said,
            week=flows.week_grid(self.batch_flows),
            days=flows.DAY_NAMES,
        )

    def data_model(self) -> None:
        mx = flows.crud_matrix(self.rd)
        top = sorted(self.summary_top_tables(), key=lambda t: -t[1])[:15]
        refs_chart = vz.chart(
            "table-refs",
            "Most used tables",
            "bar",
            [t for t, _ in top],
            [n for _, n in top],
            colors="series-6",
            label="SQL statements",
            category="Table",
            horizontal=True,
        )
        pairs = flows.tables_used_together(self.rd)
        self._render(
            "data-model.html",
            "data-model.html",
            "data-model",
            "Data model",
            matrix=mx,
            refs_chart=refs_chart,
            data_map={
                "title": "Tables used together in the same SQL statement",
                "source": flows.data_map_mermaid(self.rd),
                "nodes": len(pairs),
                "note": "A line joins two tables read or written by the same statement (joins, "
                "sub-queries): a hint of the relationships, not the declared keys.",
            },
            charts=True,
            diagrams=True,
        )

    def summary_top_tables(self) -> list[tuple[str, int]]:
        out: Counter[str] = Counter()
        for st in self.rd.items("sql_statements"):
            for t in {t.upper() for t in st.get("tables") or []}:
                out[t] += 1
        return list(out.items())

    def catalogue(self, page_id: str, cats: list[cat.Catalogue]) -> None:
        title = "Batch jobs" if page_id == "batch-jobs" else cats[0].title

        self._render(
            "catalogue.html",
            f"{page_id}.html",
            page_id,
            title,
            catalogues=cats,
            data_scripts=[c.name for c in cats],
            batch_csv=page_id == "batch-jobs",
            csv_file="web-services.csv" if page_id == "web-services" else None,
        )

    def postgresql(self) -> None:
        grades = self.counts.get("pg_grades", {})
        grade_chart = bar(
            "grades",
            "SQL statements by PostgreSQL grade",
            GRADES,
            [grades.get(g, 0) for g in GRADES],
            [GRADE_COLOR[g] for g in GRADES],
            label="Statements",
            category="Grade",
        )
        constructs: Counter[str] = Counter()
        for st in self.rd.items("sql_statements"):
            constructs.update(st.get("vendor_features") or [])
        top = constructs.most_common(15)
        construct_chart = bar(
            "constructs",
            "Vendor constructs (statements using each)",
            [k for k, _ in top],
            [v for _, v in top],
            label="Statements",
            category="Construct",
            horizontal=True,
            note="Top 15; one statement can use several.",
        )
        procedures = []
        for o in self.rd.items("db_objects"):
            if o.get("object_type") in ("package", "procedure", "function", "trigger"):
                procedures.append(
                    {
                        "name": o["name"],
                        "type": o["object_type"],
                        "grade": o.get("pg_grade") or "",
                        "reason": o.get("reason") or "",
                        "link": self._link(*self.rd.first_evidence(o)),
                    }
                )
        self._render(
            "postgresql.html",
            "postgresql.html",
            "postgresql",
            "PostgreSQL readiness",
            grade_chart=grade_chart,
            construct_chart=construct_chart,
            procedures=procedures,
            data_scripts=["sql"],
            charts=True,
        )

    def security(self) -> None:
        vl = self.counts.get("vulnerable_libraries", {})
        by_sev = vl.get("by_severity", {})
        sevs = [s for s in SEVERITIES if s in by_sev] or SEVERITIES[:4]
        severity_chart = bar(
            "vuln-sev",
            "Vulnerable libraries by highest severity",
            sevs,
            [by_sev.get(s, 0) for s in sevs],
            [SEVERITY_COLOR[s] for s in sevs],
            label="Libraries",
            category="Severity",
        )
        recs = sorted(self.counts.get("recommendations", {}).items(), key=lambda kv: -kv[1])
        action_chart = bar(
            "actions",
            "Libraries by recommended action",
            [k for k, _ in recs],
            [v for _, v in recs],
            label="Libraries",
            category="Action",
            horizontal=True,
        )
        points: list[dict[str, Any]] = []
        for lib in self.rd.items("libraries"):
            vulns = lib.get("vulnerabilities") or []
            if not lib.get("cves"):
                continue
            cvss = max((v.get("cvss") or 0 for v in vulns), default=0)
            used = len(lib.get("where_used") or [])
            name = f"{cat._library_name(lib)} {lib.get('version') or ''}".strip()
            points.append(
                {
                    "x": len(lib["cves"]),
                    "y": cvss,
                    "r": min(22, 5 + 2 * used),
                    "name": name,
                    "label": f"{name}: {len(lib['cves'])} CVE(s), highest CVSS {cvss}, "
                    f"used in {used} place(s)",
                }
            )
        bubble_chart = vz.chart(
            "vuln-bubble",
            "Vulnerable libraries: how many CVEs and how severe",
            "bubble",
            [p["name"] for p in points],
            points=points,
            xLabel="Known vulnerabilities (CVEs)",
            yLabel="Highest CVSS score",
            category="Library",
            color="series-8",
            note="One bubble per vulnerable library; a bigger bubble is used in more places.",
        )
        rank = {s: i for i, s in enumerate(SEVERITIES)}
        libs = [lib for lib in self.rd.items("libraries") if lib.get("cves") or lib.get("eol")]
        libs.sort(
            key=lambda lib: (
                rank.get(lib.get("max_severity") or "", 9),
                -len(lib.get("cves") or []),
                cat._library_name(lib),
            )
        )
        risky = [
            {
                "name": cat._library_name(lib),
                "version": lib.get("version") or "",
                "severity": lib.get("max_severity") or "",
                "cves": len(lib.get("cves") or []),
                "eol": lib.get("eol_date") or ("yes" if lib.get("eol") else ""),
                "action": (lib.get("recommendation") or {}).get("action", ""),
                "target": cat._recommended(lib),
                "link": self._link(*self.rd.first_evidence(lib)),
            }
            for lib in libs[:20]
        ]
        flags, unknown = [], 0
        for lib in self.rd.items("libraries"):
            lic = lib.get("license")
            if not lic:
                unknown += 1
            elif COPYLEFT.search(lic):
                flags.append(
                    {
                        "name": cat._library_name(lib),
                        "version": lib.get("version") or "",
                        "licence": lic,
                        "why": "copyleft: check the distribution terms",
                    }
                )
        self._render(
            "security.html",
            "security.html",
            "security",
            "Dependency security",
            vuln_sources=self.rd.run.get("vulnerability_sources", []),
            severity_chart=severity_chart,
            action_chart=action_chart,
            bubble_chart=bubble_chart,
            risky=risky,
            licence_flags=flags,
            licence_unknown=unknown,
            library_count=len(self.rd.items("libraries")),
            has_sbom=self.rd.has_sbom,
            data_scripts=["libraries"],
            charts=True,
        )

    def analysis(self) -> None:
        """The Copilot enrichment, validated by ``legacy-audit validate``, labelled as such."""
        from legacy_audit.enrich.status import active_stages, compute_status
        from legacy_audit.report.deck_builder import load_enrichment

        e = load_enrichment(self.rd.run_dir)
        status = compute_status(self.rd.run_dir)

        def cite(item: dict[str, Any]) -> dict[str, Any]:
            links = [self._link(ev.get("file"), ev.get("line")) for ev in item.get("evidence", [])]
            return {**item, "links": links}

        stages = []
        for st in active_stages(self.rd.run_dir):
            entry = status["stages"][st.name]
            state = (
                "missing"
                if not entry["present"]
                else "stale"
                if entry.get("stale")
                else "valid"
                if entry.get("validated")
                else "not validated"
            )
            stages.append(
                {
                    "number": st.number,
                    "name": st.name,
                    "title": st.title,
                    "state": state,
                    "changed": entry.get("changed", []),
                }
            )

        def diagram_list(stage: str) -> list[dict[str, Any]]:
            return [
                {
                    "title": d["title"],
                    "source": d["mermaid"],
                    "nodes": 1,
                    "note": "Drawn by the Copilot agent (enrichment), not by the scanner.",
                }
                for d in (e.get(stage) or {}).get("diagrams", [])
            ]

        risks = sorted(
            (cite(r) for r in (e.get("risks") or {}).get("risks", [])),
            key=lambda r: SEVERITIES.index(r["severity"]) if r["severity"] in SEVERITIES else 9,
        )
        self._render(
            "analysis.html",
            "analysis.html",
            "analysis",
            "Analysis",
            stages=stages,
            e=e,
            key_points=[cite(k) for k in (e.get("overview") or {}).get("key_points", [])],
            classes=[cite(c) for c in (e.get("classification") or {}).get("components", [])],
            flows=[cite(f) for f in (e.get("flows") or {}).get("flows", [])],
            mappings=[
                {**cite(m), "service": self.rd.label(m["service_id"])}
                for m in (e.get("service_mapping") or {}).get("mappings", [])
            ],
            risks=risks,
            risk_matrix=vz.risk_matrix(risks) if risks else None,
            gantt=vz.gantt((e.get("plan") or {}).get("waves", [])) if e.get("plan") else None,
            arch_diagrams=diagram_list("architecture"),
            target_diagrams=diagram_list("target_architecture"),
            label=self.rd.label,
            diagrams=True,
        )

    def findings(self) -> None:
        by_sev = self.counts.get("findings_by_severity", {})
        sevs = [s for s in SEVERITIES if s in by_sev]
        severity_chart = bar(
            "find-sev",
            "Findings by severity",
            sevs,
            [by_sev[s] for s in sevs],
            [SEVERITY_COLOR[s] for s in sevs],
            label="Findings",
            category="Severity",
        )
        cats_ = Counter(
            f.get("category") or "other" for f in self.rd.items("findings")
        ).most_common()
        category_chart = bar(
            "find-cat",
            "Findings by category",
            [k for k, _ in cats_],
            [v for _, v in cats_],
            label="Findings",
            category="Category",
            horizontal=True,
        )
        rules = sorted(self.counts.get("findings", {}).items(), key=lambda kv: (-kv[1], kv[0]))
        rule_chart = bar(
            "find-rule",
            "Findings by rule",
            [k for k, _ in rules],
            [v for _, v in rules],
            label="Findings",
            category="Rule",
            horizontal=True,
        )
        self._render(
            "findings.html",
            "findings.html",
            "findings",
            "Findings",
            severity_chart=severity_chart,
            category_chart=category_chart,
            rule_chart=rule_chart,
            data_scripts=["findings"],
            charts=True,
        )

    def appendix(self) -> None:
        run = self.rd.run
        config_path = Path(run["config_path"]) if run.get("config_path") else None
        if config_path is not None and config_path.exists():
            text = _SECRET_LINE.sub(
                lambda m: m.group(1) + "****",
                config_path.read_text(encoding="utf-8", errors="replace"),
            )
        else:
            text = "(configuration file not found)"
        levels: Counter[str] = Counter()
        notable: list[str] = []
        log = self.rd.run_dir / "logs" / "scan.log"
        if log.exists():
            for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
                m = re.match(r"^\S+ \S+ (DEBUG|INFO|WARNING|ERROR|CRITICAL) ", line)
                if m:
                    levels[m.group(1)] += 1
                    if m.group(1) in ("WARNING", "ERROR", "CRITICAL"):
                        notable.append(line)
        self._render(
            "appendix.html",
            "appendix.html",
            "appendix",
            "Appendix",
            counting_rules=run.get("counting_rules", {}),
            input_coverage=run.get("coverage", []),
            analyzers=sorted(run.get("analyzers", {}).items()),
            run={
                "run_id": run.get("run_id"),
                "started_at": run.get("started_at"),
                "finished_at": run.get("finished_at"),
                "files_total": run.get("files_total"),
                "files_analyzed": run.get("files_analyzed"),
                "files_cached": run.get("files_cached"),
                "full_scan": run.get("full_scan"),
                "unresolved_references": run.get("unresolved_references"),
                "config_hash": run.get("config_hash"),
                "timings": run.get("timings", {}),
                "warnings": run.get("warnings", []),
            },
            config_name=config_path.name if config_path else "audit.yaml",
            config_text=text,
            log_levels=dict(levels),
            log_lines=notable[-50:],
            glossary=GLOSSARY,
        )
