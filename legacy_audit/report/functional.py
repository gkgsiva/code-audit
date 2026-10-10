"""``legacy-audit report --type functional``: the functional document, for business readers.

A static, offline HTML site like the technical report (same theme, vendored Chart.js and Mermaid,
evidence links), written to ``runs/<id>/reports/functional/``. It reads two things:

- the **scan facts**, for every count, every screen and batch flow (screen -> services ->
  tables -> next screen; batch -> steps -> programs -> tables and files), the CRUD heat map, the
  batch calendar and the interface map;
- the **validated enrichment** (``functional``, ``classification``, ``flows``, ``overview``,
  ``assumptions``, ``narratives``), for the words: capabilities, actors, use cases, business
  rules, data entities, one plain-language story per screen and per batch, glossary.

A part whose enrichment stage is missing shows a placeholder naming the prompt to run, and the
scan-only parts still build, so the document can be built straight after a scan.
"""

from __future__ import annotations

import json
import shutil
from collections import defaultdict
from pathlib import Path
from typing import Any

from legacy_audit.report import diagrams as dg
from legacy_audit.report import flows
from legacy_audit.report import visuals as vz
from legacy_audit.report.data import RunData
from legacy_audit.report.tech import _copy_assets, _env, _fmt, _Pages

FN_NAV = [
    ("summary", "index.html", "Summary"),
    ("use-cases", "use-cases.html", "Users and use cases"),
    ("business-flows", "business-flows.html", "Business flows"),
    ("screens", "screens.html", "Screens"),
    ("batch", "batch.html", "Batch"),
    ("rules", "rules.html", "Business rules"),
    ("data", "data.html", "Data"),
    ("interfaces", "interfaces.html", "Interfaces"),
    ("glossary", "glossary.html", "Glossary and questions"),
]
FOOTER = (
    "Counts, flows, tables and schedules come from the scan of this run (deterministic scripts). "
    "Descriptions in plain words were written by the Copilot agent and checked against the scan "
    "by legacy-audit validate; each cites the code it is based on or is marked as an assumption."
)
RULE_COLOR = {
    "validation": "c1",
    "calculation": "c7",
    "eligibility": "c3",
    "status-change": "c4",
    "limit": "c8",
    "notification": "c2",
    "other": "c5",
}
ACTOR_STYLE = {
    "user": ("users", "c1"),
    "external-system": ("interfaces", "c2"),
    "scheduler": ("calendar", "c4"),
    "operator": ("services", "c7"),
}


def build_functional_report(run_dir: Path, inputs_dir: Path | None = None) -> Path:
    """Build the functional document for a run; returns its ``index.html``."""
    rd = RunData.load(run_dir, inputs_dir)
    out = run_dir / "reports" / "functional"
    _build_into(rd, out)
    latest = run_dir.parent.parent / "latest"
    if latest.is_dir() and not latest.is_symlink() and latest.resolve() != run_dir.resolve():
        run_json = latest / "run.json"
        if run_json.exists():
            latest_id = json.loads(run_json.read_text(encoding="utf-8")).get("run_id")
            if latest_id == rd.run.get("run_id"):
                _build_into(rd, latest / "reports" / "functional")
    return out / "index.html"


def _build_into(rd: RunData, out: Path) -> None:
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    _copy_assets(out / "assets")
    p = _FnPages(rd, out)
    p.summary()
    p.use_cases()
    p.business_flows()
    p.screens()
    p.batch()
    p.rules()
    p.data()
    p.interfaces()
    p.glossary()


class _FnPages(_Pages):
    def __init__(self, rd: RunData, out: Path) -> None:
        super().__init__(rd, out, {}, _env())
        self.common["nav"] = [{"id": i, "href": h, "title": t} for i, h, t in FN_NAV]
        self.common["doc_kind"] = "Functional document"
        self.common["footer_note"] = FOOTER
        self.fn: dict[str, Any] = self.e.get("functional") or {}
        self.screen_story = {s["screen_id"]: s for s in self.fn.get("screens", [])}
        self.batch_story = {b["job_id"]: b for b in self.fn.get("batches", [])}

    # ---- helpers ------------------------------------------------------------------------------
    def _cites(self, item: dict[str, Any]) -> dict[str, Any]:
        links = [self._link(ev.get("file"), ev.get("line")) for ev in item.get("evidence", [])]
        return {**item, "links": links}

    def _names(self, ids: list[str]) -> list[str]:
        return [self.rd.label(i).rsplit("/", 1)[-1] for i in ids]

    def _flow_href(self) -> dict[str, str]:
        return {f.id: f"flows/{f.slug}.html" for f in [*self.screen_flows, *self.batch_flows]}

    def _missing(self, stage: str) -> bool:
        return not self.e.get(stage)

    # ---- pages --------------------------------------------------------------------------------
    def summary(self) -> None:
        tiles = [
            self._tile("counts.screens.total", "Screens", "screens.html"),
            self._tile("counts.services.total", "Services (operations)", ""),
            self._tile("counts.batch_jobs.total", "Batch jobs", "batch.html"),
            self._tile("counts.db_objects.table", "Tables", "data.html"),
            self._tile("counts.integrations.total", "Interfaces", "interfaces.html"),
            self._tile("totals.components", "Components", ""),
        ]
        tiles[3]["icon"], tiles[3]["color"] = "data", "c6"
        domains = self._domains()
        actors = [
            {**a, "icon": ACTOR_STYLE[a["kind"]][0], "color": ACTOR_STYLE[a["kind"]][1]}
            for a in self.fn.get("actors", [])
        ]
        ov = self.e.get("overview") or {}
        narrative = ((self.e.get("narratives") or {}).get("functional") or {}).get("text", "")
        per_comp: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0])
        for scr in self.rd.items("screens"):
            if not scr.get("partial"):
                per_comp[scr.get("component") or "(none)"][0] += 1
        for svc in self.rd.items("services"):
            per_comp[svc.get("component") or "(none)"][1] += 1
        for job in self.rd.items("batch_jobs"):
            if job.get("kind") != "box":
                per_comp[job.get("component") or "(none)"][2] += 1
        names = sorted(per_comp, key=lambda n: -sum(per_comp[n]))[:12]
        mix_chart = vz.chart(
            "mix",
            "What each part of the application offers",
            "stacked",
            names,
            datasets=[
                {
                    "label": "Screens",
                    "values": [per_comp[n][0] for n in names],
                    "color": "series-1",
                },
                {
                    "label": "Services",
                    "values": [per_comp[n][1] for n in names],
                    "color": "series-7",
                },
                {
                    "label": "Batch jobs",
                    "values": [per_comp[n][2] for n in names],
                    "color": "series-4",
                },
            ],
            category="Component",
            horizontal=True,
            note="Counts from the scan, per component.",
        )
        self._render(
            "fn_summary.html",
            "index.html",
            "summary",
            "What the application does",
            tiles=tiles,
            fn_summary=self.fn.get("summary") or ov.get("purpose") or "",
            from_overview=not self.fn.get("summary") and bool(ov.get("purpose")),
            users=ov.get("users", []),
            domains=domains,
            actors=actors,
            narrative=narrative,
            key_points=[self._cites(k) for k in ov.get("key_points", [])],
            mix_chart=mix_chart,
            missing_functional=self._missing("functional"),
            charts=True,
        )

    def _domains(self) -> list[dict[str, Any]]:
        """Capability map: the agent's domains, else components grouped by the scanner's type
        (labelled as such)."""
        href = self._flow_href()
        out = []
        if self.fn.get("domains"):
            for i, d in enumerate(self.fn["domains"]):
                caps = []
                for cap in d.get("capabilities", []):
                    links = [
                        {"name": n, "href": href.get(i_)}
                        for i_, n in zip(
                            cap.get("screen_ids", []) + cap.get("job_ids", []),
                            self._names(cap.get("screen_ids", []) + cap.get("job_ids", [])),
                            strict=True,
                        )
                    ]
                    caps.append(
                        {
                            **self._cites(cap),
                            "flows": links,
                            "n_services": len(cap.get("service_ids", [])),
                        }
                    )
                out.append({**d, "caps": caps, "color": f"c{i % 8 + 1}", "from_scan": False})
            return out
        classes = {
            c["component"]: c for c in (self.e.get("classification") or {}).get("components", [])
        }
        groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for comp in self.rd.items("components"):
            cl = classes.get(comp["name"])
            key = cl["capability"] if cl else (comp.get("type") or "unknown")
            groups[key].append(
                {
                    "name": comp["name"],
                    "description": (
                        f"{comp.get('files', 0)} files, {_fmt(comp.get('loc_code', 0))} code lines"
                    ),
                    "flows": [],
                    "links": [],
                }
            )
        for i, (name, caps) in enumerate(sorted(groups.items())):
            out.append(
                {
                    "name": name,
                    "description": "",
                    "caps": caps,
                    "color": f"c{i % 8 + 1}",
                    "from_scan": not classes,
                }
            )
        return out

    def use_cases(self) -> None:
        ucs = [
            {
                **self._cites(u),
                "screens": [
                    {"name": n, "href": self._flow_href().get(i)}
                    for i, n in zip(
                        u.get("screen_ids", []), self._names(u.get("screen_ids", [])), strict=True
                    )
                ],
            }
            for u in self.fn.get("use_cases", [])
        ]
        actors = [
            {**a, "icon": ACTOR_STYLE[a["kind"]][0], "color": ACTOR_STYLE[a["kind"]][1]}
            for a in self.fn.get("actors", [])
        ]
        diagram = None
        if ucs:
            lines = ["flowchart LR"]
            aid = {a["name"]: f"a{i}" for i, a in enumerate(actors)}
            for a in actors:
                lines.append(f"  {aid[a['name']]}([{dg._label(a['name'])}]):::actor")
            for i, u in enumerate(ucs):
                lines.append(f"  u{i}({dg._label(u['id'] + ' ' + u['name'])}):::usecase")
                if u["actor"] in aid:
                    lines.append(f"  {aid[u['actor']]} --> u{i}")
            lines.append(
                "  classDef actor fill:#fff1cc,stroke:#d69a00,color:#0b0b0b\n"
                "  classDef usecase fill:#ece3fb,stroke:#6250d6,color:#0b0b0b"
            )
            diagram = {
                "title": "Who does what",
                "source": "\n".join(lines),
                "nodes": len(actors) + len(ucs),
                "note": "Actors (left) and the use cases they start, from the enrichment.",
            }
        self._render(
            "fn_use_cases.html",
            "use-cases.html",
            "use-cases",
            "Users and use cases",
            actors=actors,
            use_cases=ucs,
            diagram=diagram,
            missing_functional=self._missing("functional"),
            diagrams=True,
        )

    def business_flows(self) -> None:
        out = []
        for f in (self.e.get("flows") or {}).get("flows", []):
            steps = sorted(f.get("steps", []), key=lambda s: s["order"])
            lines = ["flowchart LR"]
            prev = None
            for k, st in enumerate(steps):
                lines.append(f"  s{k}[{dg._label(str(st['order']) + '. ' + st['description'])}]")
                lines.append(f"  style s{k} {_STEP_STYLES[k % len(_STEP_STYLES)]}")
                if prev is not None:
                    lines.append(f"  {prev} --> s{k}")
                prev = f"s{k}"
            if f.get("trigger"):
                lines.append(f"  t0{{{{{dg._label(f['trigger'])}}}}} --> s0")
                lines.append("  style t0 fill:#fff1cc,stroke:#d69a00,color:#0b0b0b")
            out.append(
                {
                    **self._cites(f),
                    "steps": [{**st, "facts": self._names(st.get("fact_ids", []))} for st in steps],
                    "diagram": {
                        "title": f["name"],
                        "source": "\n".join(lines),
                        "nodes": len(steps),
                        "note": "",
                    },
                }
            )
        self._render(
            "fn_business_flows.html",
            "business-flows.html",
            "business-flows",
            "Business flows",
            flows_=out,
            missing=self._missing("flows"),
            diagrams=True,
        )

    def _flow_pages(self, items: list[flows.Flow], stories: dict[str, Any]) -> None:
        for f in items:
            self._render(
                "flow.html",
                f"flows/{f.slug}.html",
                "screens" if f.kind == "screen" else "batch",
                f"{'Screen' if f.kind == 'screen' else 'Batch'}: {f.name}",
                audience="functional",
                diagrams=True,
                **self.flow_ctx(f, stories.get(f.id)),
            )

    def screens(self) -> None:
        self._flow_pages(self.screen_flows, self.screen_story)
        real = {f.id for f in self.screen_flows}
        lines = ["flowchart LR"]
        ids = {f.id: f"s{i}" for i, f in enumerate(self.screen_flows[: dg.MAX_NODES])}
        for f in self.screen_flows[: dg.MAX_NODES]:
            lines.append(f"  {ids[f.id]}[{dg._label(f.name)}]:::screen")
        n_edges = 0
        by_name = {f.name: f.id for f in self.screen_flows}
        for f in self.screen_flows[: dg.MAX_NODES]:
            for nxt in f.after:
                target = by_name.get(nxt)
                if target in ids and target in real:
                    lines.append(f"  {ids[f.id]} --> {ids[target]}")
                    n_edges += 1
        lines.append(flows.MERMAID_CLASSES)
        nav_map = {
            "title": "How the screens connect",
            "source": "\n".join(lines),
            "nodes": len(ids),
            "note": (
                f"Showing {dg.MAX_NODES} of {len(self.screen_flows)} screens. "
                if len(self.screen_flows) > dg.MAX_NODES
                else ""
            )
            + ("" if n_edges else "No links between screens were found by the scan."),
        }
        self._render(
            "fn_screens.html",
            "screens.html",
            "screens",
            "Screens",
            screen_flows=self.screen_flows,
            stories=self.screen_story,
            nav_map=nav_map,
            missing_functional=self._missing("functional"),
            diagrams=True,
        )

    def batch(self) -> None:
        self._flow_pages(self.batch_flows, self.batch_story)
        self._render(
            "fn_batch.html",
            "batch.html",
            "batch",
            "Batch",
            batch_flows=self.batch_flows,
            stories=self.batch_story,
            week=flows.week_grid(self.batch_flows),
            days=flows.DAY_NAMES,
            gaps=[
                c.get("label")
                for c in self.rd.run.get("coverage", [])
                if c.get("kind") in ("autosys", "crontab") and c.get("status") == "missing"
            ],
            missing_functional=self._missing("functional"),
        )

    def rules(self) -> None:
        rules = [
            {
                **self._cites(r),
                "color": RULE_COLOR.get(r["kind"], "c5"),
                "facts": self._names(r.get("fact_ids", [])),
            }
            for r in self.fn.get("business_rules", [])
        ]
        kinds: dict[str, int] = defaultdict(int)
        for r in rules:
            kinds[r["kind"]] += 1
        kl = sorted(kinds, key=lambda k: (-kinds[k], k))
        chart = vz.chart(
            "rule-kinds",
            "Business rules by kind",
            "doughnut",
            kl,
            [kinds[k] for k in kl],
            colors=[f"series-{int(RULE_COLOR.get(k, 'c5')[1:])}" for k in kl],
            label="Rules",
            category="Kind",
        )
        self._render(
            "fn_rules.html",
            "rules.html",
            "rules",
            "Business rules",
            rules=rules,
            chart=chart,
            missing_functional=self._missing("functional"),
            charts=True,
        )

    def data(self) -> None:
        entities = [
            {**self._cites(d), "tables": self._names(d.get("db_object_ids", []))}
            for d in self.fn.get("data_entities", [])
        ]
        pairs = flows.tables_used_together(self.rd)
        self._render(
            "fn_data.html",
            "data.html",
            "data",
            "Business data",
            entities=entities,
            matrix=flows.crud_matrix(self.rd),
            data_map={
                "title": "Tables used together",
                "source": flows.data_map_mermaid(self.rd),
                "nodes": len(pairs),
                "note": "Two tables are joined when the same statement uses both.",
            },
            missing_functional=self._missing("functional"),
            diagrams=True,
        )

    def interfaces(self) -> None:
        stories = {i["integration_id"]: i for i in self.fn.get("interfaces", [])}
        integ = [
            i
            for i in self.rd.items("integrations")
            if i.get("type") not in ("MQ-CHANNEL", "MQ-QMGR")
        ]
        integ.sort(key=lambda i: (-(i.get("usages") or 0), i["type"], i["target"]))
        shown = integ[: dg.MAX_NODES]
        app = self.common["app"]
        lines = ["flowchart LR", f"  APP(({dg._label(app)})):::app"]
        for k, i in enumerate(shown):
            story = stories.get(i["id"])
            name = (story or {}).get("partner") or f"{i['type']} {i['target']}"
            node = f"  p{k}([{dg._label(name)}]):::{'db' if i['type'] == 'DB' else 'ext'}"
            lines.append(node)
            label = dg._label(i["type"])
            d = i.get("direction")
            if d == "outbound":
                lines.append(f"  APP -->|{label}| p{k}")
            elif d == "inbound":
                lines.append(f"  p{k} -->|{label}| APP")
            elif d == "both":
                lines.append(f"  APP <-->|{label}| p{k}")
            else:
                lines.append(f"  APP -.-|{label}| p{k}")
        lines.append(
            "  classDef app fill:#2a78d6,stroke:#1c5cab,color:#ffffff\n"
            "  classDef ext fill:#fde7d9,stroke:#ec835a,color:#0b0b0b\n"
            "  classDef db fill:#dcf3dc,stroke:#0ca30c,color:#0b0b0b"
        )
        rows = [
            {
                **i,
                "story": self._cites(stories[i["id"]]) if i["id"] in stories else None,
                "link": self._link(*self.rd.first_evidence(i)),
            }
            for i in integ
        ]
        by_type: dict[str, int] = defaultdict(int)
        for i in integ:
            by_type[i["type"]] += 1
        tl = sorted(by_type, key=lambda t: (-by_type[t], t))
        chart = vz.chart(
            "int-types",
            "Interfaces by type",
            "doughnut",
            tl,
            [by_type[t] for t in tl],
            colors=[f"series-{k % 8 + 1}" for k in range(len(tl))],
            label="Interfaces",
            category="Type",
        )
        self._render(
            "fn_interfaces.html",
            "interfaces.html",
            "interfaces",
            "Interfaces with other systems",
            context={
                "title": "The application and the systems around it",
                "source": "\n".join(lines),
                "nodes": len(shown) + 1,
                "note": (
                    f"Showing {dg.MAX_NODES} of {len(integ)} interfaces."
                    if len(integ) > dg.MAX_NODES
                    else ""
                ),
            },
            rows=rows,
            chart=chart,
            missing_functional=self._missing("functional"),
            charts=True,
            diagrams=True,
        )

    def glossary(self) -> None:
        from legacy_audit.report.tech import GLOSSARY

        asm = self.e.get("assumptions") or {}
        gaps = [
            c.get("label") or c.get("kind", "")
            for c in self.rd.run.get("coverage", [])
            if c.get("status") == "missing"
        ]
        self._render(
            "fn_glossary.html",
            "glossary.html",
            "glossary",
            "Glossary, assumptions and open questions",
            terms=sorted(self.fn.get("glossary", []), key=lambda t: t["term"].lower()),
            tool_terms=GLOSSARY,
            assumptions=asm.get("assumptions", []),
            questions=asm.get("open_questions", []),
            gaps=gaps,
            missing_functional=self._missing("functional"),
        )


_STEP_STYLES = [
    "fill:#dbe9fb,stroke:#2a78d6,color:#0b0b0b",
    "fill:#ece3fb,stroke:#6250d6,color:#0b0b0b",
    "fill:#dcf3dc,stroke:#0ca30c,color:#0b0b0b",
    "fill:#fde7d9,stroke:#ec835a,color:#0b0b0b",
    "fill:#d8f1f0,stroke:#13867f,color:#0b0b0b",
]
