"""Catalogue tables: which columns each entity shows, and its rows as plain lists.

A catalogue is written next to the pages as ``data/<name>.js`` (``LA_DATA["<name>"] = {...}``)
and drawn by ``report.js`` with search, per-column filters, sorting and paging. A script file and
not JSON, because browsers refuse ``fetch()`` on pages opened from disk (``file://``).

Column types: ``text``, ``num``, ``code`` (monospace), ``badge`` (grade/severity chip),
``list`` (comma-joined), ``link`` (``{text, href}`` to another report page) and ``evidence``
(``{text, href, vscode}`` to the source file). ``filter: true`` adds a value filter above the
column.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from legacy_audit.report.data import RunData, slug


@dataclass
class Column:
    key: str
    label: str
    type: str = "text"
    filter: bool = False

    def spec(self) -> dict[str, Any]:
        return {"key": self.key, "label": self.label, "type": self.type, "filter": self.filter}


@dataclass
class Catalogue:
    name: str
    title: str
    intro: str
    columns: list[Column]
    rows: list[list[Any]]

    def payload(self) -> dict[str, Any]:
        return {"columns": [c.spec() for c in self.columns], "rows": self.rows}


def _comp(name: str | None) -> dict[str, Any] | str:
    if not name:
        return ""
    return {"text": name, "href": f"components/{slug(name)}.html"}


def _ev(rd: RunData, root: Path, item: dict[str, Any]) -> dict[str, Any]:
    file, line = rd.first_evidence(item)
    return rd.evidence_links(root, file, line)


def _join(values: Any) -> str:
    if not values:
        return ""
    if isinstance(values, dict):
        return ", ".join(f"{k}: {v}" for k, v in values.items())
    return ", ".join(str(v) for v in values)


def _library_name(lib: dict[str, Any]) -> str:
    return f"{lib['group']}:{lib['artifact']}" if lib.get("group") else str(lib.get("artifact"))


def _ai_or(description: str | None, story: dict[str, Any] | None) -> str:
    """The Copilot interface story (stage 12) when there is one, else the scan's sentence."""
    if story and story.get("purpose"):
        partner = f"{story['partner']}: " if story.get("partner") else ""
        return f"{partner}{story['purpose']} (AI)"
    return description or ""


def ws_text(ws: dict[str, Any], story: dict[str, Any] | None) -> tuple[str, str]:
    """(what it is for, suggested migration): the Copilot story (stage 12) marked AI, else the
    scan's sentence and nothing."""
    if story and story.get("purpose"):
        partner = f"{story['partner']}: " if story.get("partner") else ""
        mig = f"{story['migration']} (AI)" if story.get("migration") else ""
        return f"{partner}{story['purpose']} (AI)", mig
    return ws.get("description") or "", ""


def ws_label(ws: dict[str, Any]) -> str:
    return f"{ws['style']} {'offered' if ws['side'] == 'offered' else 'called'}"


def build(
    rd: RunData,
    root: Path,
    stories: dict[str, dict[str, Any]] | None = None,
    ws_stories: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Catalogue]:
    """Every catalogue of the run; ``root`` is the report folder (evidence links are relative).
    ``stories`` are the Copilot interface stories by integration id and ``ws_stories`` its
    web service stories by web service id, when the stage exists."""
    ev: Callable[[dict[str, Any]], dict[str, Any]] = lambda item: _ev(rd, root, item)  # noqa: E731
    stories = stories or {}
    ws_stories = ws_stories or {}
    cats: list[Catalogue] = []

    cats.append(
        Catalogue(
            "services",
            "Services",
            "Independently invokable operations (counting rule in the appendix).",
            [
                Column("name", "Name", "code"),
                Column("kind", "Kind", filter=True),
                Column("component", "Component", "link", filter=True),
                Column("url", "URL", "code"),
                Column("handler", "Handler", "code"),
                Column("complexity", "Complexity", "badge", True),
                Column("tables", "Tables", "list"),
                Column("calls", "External calls", "list"),
                Column("fan_in", "Fan-in", "num"),
                Column("fan_out", "Fan-out", "num"),
                Column("evidence", "Evidence", "evidence"),
            ],
            [
                [
                    s["name"],
                    s["kind"],
                    _comp(s.get("component")),
                    s.get("url") or "",
                    s.get("handler") or "",
                    s.get("complexity") or "",
                    _join(s.get("tables")),
                    _join(s.get("external_calls")),
                    s.get("fan_in", 0),
                    s.get("fan_out", 0),
                    ev(s),
                ]
                for s in rd.items("services")
            ],
        )
    )

    cats.append(
        Catalogue(
            "screens",
            "Screens",
            "Rendered pages and maps; partials (included fragments) are listed but not counted.",
            [
                Column("name", "Screen", "code"),
                Column("technology", "Technology", filter=True),
                Column("component", "Component", "link", filter=True),
                Column("partial", "Partial", filter=True),
                Column("fields", "Fields", "num"),
                Column("complexity", "Complexity", "badge", True),
                Column("targeted", "Reached from", "num"),
                Column("evidence", "Evidence", "evidence"),
            ],
            [
                [
                    s["name"],
                    s.get("technology") or "",
                    _comp(s.get("component")),
                    "yes" if s.get("partial") else "no",
                    s.get("fields_count", 0),
                    s.get("complexity") or "",
                    len(s.get("targeted_by") or []),
                    ev(s),
                ]
                for s in rd.items("screens")
            ],
        )
    )

    cats.append(
        Catalogue(
            "sql",
            "SQL statements",
            "Every statement found, with its PostgreSQL grade. ? marks a part only known at run "
            "time (value holes are an injection risk, structural holes make the statement manual).",
            [
                Column("evidence", "Where", "evidence"),
                Column("type", "Type", filter=True),
                Column("source", "Source", filter=True),
                Column("component", "Component", "link", True),
                Column("grade", "PG grade", "badge", True),
                Column("dynamic", "Dynamic", filter=True),
                Column("holes", "Holes (value/structural)", "text"),
                Column("tables", "Tables", "list"),
                Column("text", "Statement", "code"),
                Column("reason", "Why", "text"),
            ],
            [
                [
                    ev(st),
                    st["type"],
                    st["source"],
                    _comp(st.get("component")),
                    st.get("pg_grade") or "",
                    "yes" if st.get("dynamic") or st["type"] == "DYNAMIC" else "no",
                    f"{st.get('value_holes', 0)}/{st.get('structural_holes', 0)}",
                    _join(st.get("tables")),
                    st.get("text", ""),
                    st.get("reason") or "",
                ]
                for st in rd.items("sql_statements")
            ],
        )
    )

    cats.append(
        Catalogue(
            "db-objects",
            "Database objects",
            "Tables, views and stored code referenced by SQL or defined in DDL; C/R/U/D = "
            "statements that create, read, update or delete.",
            [
                Column("name", "Object", "code"),
                Column("type", "Type", filter=True),
                Column("crud", "CRUD", "text"),
                Column("refs", "Referenced by", "num"),
                Column("grade", "PG grade", "badge", True),
                Column("evidence", "Evidence", "evidence"),
            ],
            [
                [
                    o["name"],
                    o["object_type"],
                    "".join(k for k in "CRUD" if (o.get("crud") or {}).get(k)),
                    len(o.get("referenced_by") or []),
                    o.get("pg_grade") or "",
                    ev(o),
                ]
                for o in rd.items("db_objects")
            ],
        )
    )

    cats.append(_crud_matrix(rd))

    cats.append(
        Catalogue(
            "integrations",
            "Integrations",
            "One row per interface (type and target); a target both read and written shows "
            "direction both; usages counts the code places that use it. Calls to an identity "
            "provider (Okta, Entra ID, ...) are 'login / authentication'. The description is "
            "the scan's; once the Copilot analysis has run, its business purpose (AI) replaces "
            "it.",
            [
                Column("type", "Type", filter=True),
                Column("purpose", "Purpose", filter=True),
                Column("target", "Target", "code"),
                Column("description", "Description"),
                Column("direction", "Direction", filter=True),
                Column("protocol", "Protocol", filter=True),
                Column("usages", "Usages", "num"),
                Column("components", "Components", "list"),
                Column("producers", "Producers", "list"),
                Column("consumers", "Consumers", "list"),
                Column("evidence", "Evidence", "evidence"),
            ],
            [
                [
                    i["type"],
                    i.get("purpose") or "",
                    i["target"],
                    _ai_or(i.get("description"), stories.get(i["id"])),
                    i.get("direction") or "",
                    i.get("protocol") or "",
                    i.get("usages", 0),
                    _join(i.get("components")),
                    _join(i.get("producers")),
                    _join(i.get("consumers")),
                    ev(i),
                ]
                for i in rd.items("integrations")
            ],
        )
    )

    web = rd.items("web_services")
    cats.append(
        Catalogue(
            "web-services",
            "Web services",
            "REST and SOAP web services the application offers (others call it) and the ones it "
            "calls, one row per service with its operations. Descriptions marked (AI) come from "
            "the Copilot analysis.",
            [
                Column("side", "Offered / called", filter=True),
                Column("style", "REST / SOAP", filter=True),
                Column("name", "Web service", "code"),
                Column("url", "Address", "code"),
                Column("operations", "Operations", "num"),
                Column("frameworks", "Found through", "list"),
                Column("component", "Component", "link", True),
                Column("description", "What it is for", "text"),
                Column("migration", "Suggested migration", "text"),
                Column("wsdl", "WSDL", "code"),
                Column("callers", "Callers", "num"),
                Column("evidence", "Evidence", "evidence"),
            ],
            [
                [
                    w["side"],
                    w["style"],
                    w["name"],
                    w.get("url") or "",
                    len(w.get("operations") or []),
                    _join(w.get("frameworks")),
                    _comp(w.get("component")),
                    *ws_text(w, ws_stories.get(w["id"])),
                    w.get("wsdl") or "",
                    len(w.get("callers") or []),
                    ev(w),
                ]
                for w in web
            ],
        )
    )
    cats.append(
        Catalogue(
            "web-operations",
            "Web service operations",
            "Every operation of every web service: HTTP method and path (REST) or operation "
            "name (SOAP), with the code that implements or calls it.",
            [
                Column("side", "Offered / called", filter=True),
                Column("service", "Web service", "code", True),
                Column("operation", "Operation", "code"),
                Column("method", "Method", filter=True),
                Column("path", "Path", "code"),
                Column("handler", "Code", "code"),
                Column("evidence", "Where", "evidence"),
            ],
            [
                [
                    w["side"],
                    w["name"],
                    o["name"],
                    o.get("method") or "",
                    o.get("path") or "",
                    o.get("handler") or "",
                    rd.evidence_links(root, o.get("file"), o.get("line")),
                ]
                for w in web
                for o in w.get("operations") or []
            ],
        )
    )

    cats.append(
        Catalogue(
            "batch-jobs",
            "Scheduler job details",
            "Every job of the scheduler definitions provided (Autosys, JCL, cron, Spring Batch), "
            "one row per job; boxes group jobs and are not counted as jobs.",
            [
                Column("name", "Job", "code"),
                Column("scheduler", "Scheduler", filter=True),
                Column("kind", "Kind", filter=True),
                Column("box", "Box", filter=True),
                Column("schedule", "Schedule"),
                Column("condition", "Condition", "code"),
                Column("command", "Command / programs", "code"),
                Column("machine", "Machine"),
                Column("component", "Component", "link", True),
                Column("complexity", "Complexity", "badge", True),
                Column("evidence", "Evidence", "evidence"),
            ],
            [
                [
                    j["name"],
                    j.get("scheduler") or "",
                    j.get("kind") or "",
                    j.get("box") or "",
                    j.get("schedule") or "",
                    j.get("condition") or "",
                    j.get("command") or _join(j.get("programs")),
                    j.get("machine") or "",
                    _comp(j.get("component")),
                    j.get("complexity") or "",
                    ev(j),
                ]
                for j in rd.items("batch_jobs")
            ],
        )
    )

    cats.append(
        Catalogue(
            "libraries",
            "Libraries",
            "Declared and undeclared libraries with known vulnerabilities, end of life and the "
            "recommended action.",
            [
                Column("name", "Library", "code"),
                Column("version", "Version", "code"),
                Column("scope", "Scope", filter=True),
                Column("declared", "Declared", filter=True),
                Column("license", "Licence", filter=True),
                Column("eol", "End of life", filter=True),
                Column("cves", "CVEs", "num"),
                Column("severity", "Max severity", "badge", True),
                Column("action", "Action", filter=True),
                Column("target", "Recommended", "code"),
                Column("reason", "Reason"),
                Column("evidence", "Evidence", "evidence"),
            ],
            [
                [
                    _library_name(lib),
                    lib.get("version") or "",
                    lib.get("scope") or "",
                    "yes" if lib.get("declared") else "no",
                    lib.get("license") or "unknown",
                    lib.get("eol_date") or ("yes" if lib.get("eol") else "no"),
                    len(lib.get("cves") or []),
                    lib.get("max_severity") or "",
                    (lib.get("recommendation") or {}).get("action", ""),
                    _recommended(lib),
                    (lib.get("recommendation") or {}).get("reason", ""),
                    ev(lib),
                ]
                for lib in rd.items("libraries")
            ],
        )
    )

    cats.append(
        Catalogue(
            "findings",
            "Findings",
            "Rule findings from the risk, PostgreSQL and dependency packs.",
            [
                Column("severity", "Severity", "badge", True),
                Column("rule", "Rule", filter=True),
                Column("category", "Category", filter=True),
                Column("component", "Component", "link", True),
                Column("message", "Finding"),
                Column("remediation", "Remediation"),
                Column("evidence", "Evidence", "evidence"),
            ],
            [
                [
                    f["severity"],
                    f["rule_id"],
                    f.get("category") or "",
                    _comp(rd.file_component.get(f.get("file") or "")),
                    f.get("message") or "",
                    f.get("remediation") or "",
                    ev(f),
                ]
                for f in rd.items("findings")
            ],
        )
    )

    return {c.name: c for c in cats}


def _recommended(lib: dict[str, Any]) -> str:
    rec = lib.get("recommendation") or {}
    target = rec.get("target") or lib.get("recommended_version") or ""
    return f"{target} (verify)" if target and rec.get("verify") else str(target)


def _crud_matrix(rd: RunData) -> Catalogue:
    """Tables by components: which component creates, reads, updates or deletes each table."""
    components = sorted({c["name"] for c in rd.items("components")})
    rows: list[list[Any]] = []
    for obj in rd.items("db_objects"):
        if obj.get("object_type") not in ("table", "view"):
            continue
        cells: dict[str, set[str]] = {c: set() for c in components}
        for op, sql_ids in (obj.get("crud") or {}).items():
            for sid in sql_ids:
                comp = rd.component_of(sid)
                if comp in cells:
                    cells[comp].add(op)
        rows.append(
            [obj["name"], *("".join(k for k in "CRUD" if k in cells[c]) for c in components)]
        )
    cols = [Column("name", "Table", "code")] + [
        Column(f"c{i}", c, "text", filter=True) for i, c in enumerate(components)
    ]
    return Catalogue(
        "crud",
        "CRUD matrix",
        "Which component creates (C), reads (R), updates (U) or deletes (D) each table, from the "
        "SQL statements found in its code.",
        cols,
        rows,
    )


def batches(rd: RunData, root: Path, rows: list[dict[str, Any]]) -> Catalogue:
    """The consolidated batch list (``report/batches.py``): one row per batch, scheduled or
    not, with what it does and when it runs, and where each came from."""

    def when_ev(r: dict[str, Any]) -> dict[str, Any] | str:
        if r.get("when_file"):
            return rd.evidence_links(root, r["when_file"], r.get("when_line"))
        return ""

    return Catalogue(
        "batches",
        "All batches",
        "Every batch once: scheduled boxes and jobs, and the batch components, programs and "
        "listeners no scheduler definition starts. 'What it does' comes from the Copilot "
        "analysis when present, else the scheduler's description, else the code's own header "
        "comment, else the scan; 'When it runs' from the scheduler, else the Copilot analysis, "
        "else what the code states (cron expressions, @Scheduled, comments).",
        [
            Column("name", "Batch", "link"),
            Column("kind", "Kind", filter=True),
            Column("component", "Component", "link", True),
            Column("what", "What it does"),
            Column("what_from", "Described by", filter=True),
            Column("when", "When it runs"),
            Column("when_from", "Schedule from", filter=True),
            Column("scheduler", "Scheduler", filter=True),
            Column("steps", "Steps", "num"),
            Column("when_evidence", "Schedule evidence", "evidence"),
            Column("evidence", "Evidence", "evidence"),
        ],
        [
            [
                {"text": r["name"], "href": f"flows/{r['slug']}.html"},
                r["kind"],
                _comp(r.get("component")),
                r["what"],
                r["what_from"],
                r["when"],
                r["when_from"],
                r["scheduler"],
                r["steps"],
                when_ev(r),
                rd.evidence_links(root, *r["evidence"]) if r["evidence"][0] else "",
            ]
            for r in rows
        ],
    )
