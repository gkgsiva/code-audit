"""``legacy-audit report --type tech``: a static, self-contained HTML site built from one run.

Pages are rendered with Jinja2 from ``tech_html/``; catalogues are written as ``data/*.js`` and
drawn by ``assets/report.js``; Chart.js and Mermaid are vendored in ``assets/vendor/``. The site
opens from disk (``file://``) with no server and no network. It is written to
``runs/<id>/reports/tech/``; when ``latest`` is a copy of that run (no symlinks on the machine),
the copy gets its own build so its relative links stay right.
"""

from __future__ import annotations

import json
import re
import shutil
from collections import Counter, defaultdict
from importlib.resources import files
from pathlib import Path
from typing import Any

from jinja2 import ChainableUndefined, Environment, PackageLoader, select_autoescape

from legacy_audit.report import catalogues as cat
from legacy_audit.report import diagrams
from legacy_audit.report.data import RunData, slug

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
    ("services", "services.html", "Services"),
    ("screens", "screens.html", "Screens"),
    ("sql", "sql.html", "SQL"),
    ("db-objects", "db-objects.html", "Database"),
    ("integrations", "integrations.html", "Integrations"),
    ("batch-jobs", "batch-jobs.html", "Batch jobs"),
    ("libraries", "libraries.html", "Libraries"),
    ("postgresql", "postgresql.html", "PostgreSQL"),
    ("security", "security.html", "Security"),
    ("findings", "findings.html", "Findings"),
    ("appendix", "appendix.html", "Appendix"),
]
CATALOGUE_PAGES = {
    "services": ["services"],
    "screens": ["screens"],
    "sql": ["sql"],
    "db-objects": ["db-objects", "crud"],
    "integrations": ["integrations"],
    "batch-jobs": ["batch-jobs"],
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


def _env() -> Environment:
    env = Environment(
        loader=PackageLoader("legacy_audit.report", "tech_html"),
        autoescape=select_autoescape(["html"]),
        undefined=ChainableUndefined,  # optional fact fields render empty
        trim_blocks=True,
        lstrip_blocks=True,
    )
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
    cats = cat.build(rd, out)
    for c in cats.values():
        _write_data_js(out / "data" / f"{c.name}.js", c.name, c.payload())
    if rd.has_sbom:
        shutil.copyfile(rd.run_dir / "data" / "sbom.cdx.json", out / "sbom.cdx.json")
    page = _Pages(rd, out, cats, _env())
    page.overview()
    page.components()
    page.architecture()
    for page_id, names in CATALOGUE_PAGES.items():
        page.catalogue(page_id, [cats[n] for n in names])
    page.postgresql()
    page.security()
    page.findings()
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
        run = rd.run
        self.common = {
            "app": run.get("application") or s.get("run", {}).get("application") or "application",
            "run_id": run.get("run_id", ""),
            "run_date": (run.get("started_at") or "")[:16].replace("T", " "),
            "tool_version": run.get("tool_version", ""),
            "sources": run.get("sources", []),
            "badges": self._badges(),
            "nav": [{"id": i, "href": h, "title": t} for i, h, t in NAV],
            "effort_label": (rd.effort or {}).get("label", "estimate"),
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
        return {
            "key": key,
            "label": label,
            "value": value,
            "display": _fmt(value),
            "href": href,
            "sub": sub,
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
            self._tile("counts.pg_grades.manual", "SQL needing manual work", "postgresql.html"),
            self._tile("counts.stored_procedures", "Stored procedures", "postgresql.html"),
            self._tile("counts.integrations.total", "Integrations", "integrations.html"),
            self._tile(
                "counts.batch_jobs.total",
                "Batch jobs",
                "batch-jobs.html",
                f"{_fmt(_get(c, 'batch_jobs.boxes'))} boxes",
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
        loc_chart = bar(
            "loc",
            "Code lines by language",
            [k for k, _ in langs],
            [v for _, v in langs],
            label="Code lines",
            category="Language",
            unit="lines",
            horizontal=True,
        )
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
            tech_stack=sorted(
                self.rd.items("tech_stack"), key=lambda t: (t.get("category") or "", t["name"])
            ),
            components=comps,
            effort=effort or None,
            largest_files=largest,
            charts=True,
        )

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
            entry = [
                {**e, "link": self._link(*rd.first_evidence(e))}
                for e in rd.items("entry_points")
                if e.get("component") == name
            ]
            ctx = {
                **comp,
                "tiles": tiles,
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
                    "batch-jobs",
                    "findings",
                ],
            )
        self._render(
            "components.html", "components.html", "components", "Components", components=rows
        )

    def architecture(self) -> None:
        self._render(
            "architecture.html",
            "architecture.html",
            "architecture",
            "Architecture and flows",
            components_diagram=diagrams.component_graph(self.rd),
            integration_diagram=diagrams.integration_map(self.rd),
            batch_diagrams=diagrams.batch_chains(self.rd),
            diagrams=True,
        )

    def catalogue(self, page_id: str, cats: list[cat.Catalogue]) -> None:
        self._render(
            "catalogue.html",
            f"{page_id}.html",
            page_id,
            cats[0].title,
            catalogues=cats,
            data_scripts=[c.name for c in cats],
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
            risky=risky,
            licence_flags=flags,
            licence_unknown=unknown,
            library_count=len(self.rd.items("libraries")),
            has_sbom=self.rd.has_sbom,
            data_scripts=["libraries"],
            charts=True,
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
