"""Screen flows and batch flows built from the scan facts alone.

A **screen flow** follows one screen: where the user comes from, the services it calls (the ones
that load it and the ones its forms and links submit to), the tables those services read and
write (create, read, update, delete, from the SQL each service reaches), the external systems
they call, and the screens the user goes to next.

A **batch flow** follows one batch: an Autosys box with its jobs, or a job that runs on its own
(Autosys, cron, JCL, Spring Batch). It shows what starts it (schedule, condition, watched file),
its steps in order and what each runs, the tables and files it reads and writes, what runs
before and after it, and the failure handling found in the scheduler definition.

Both are deterministic: every node and arrow is a fact, with evidence. The functional report
adds the Copilot agent's plain-language description on top (``enrichment/functional.json``);
the technical report shows them as they are. ``columns`` and ``edges`` feed the PPTX, which
draws the same flow with native shapes; ``mermaid`` feeds the HTML pages.
"""

from __future__ import annotations

import re
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any

from legacy_audit.model import make_id
from legacy_audit.report.comments import header_comment
from legacy_audit.report.data import RunData, slug
from legacy_audit.report.diagrams import _COND_LABEL, _CONDITION, _RUN_EDGES, _label

MAX_PER_COLUMN = 12
CRUD_ORDER = "CRUD"
SQL_CRUD = {
    "SELECT": "R",
    "INSERT": "C",
    "UPDATE": "U",
    "DELETE": "D",
    "MERGE": "CU",
    "TRUNCATE": "D",
    "UPSERT": "CU",
}
CRUD_WORDS = {"C": "creates", "R": "reads", "U": "updates", "D": "deletes"}
# Node kind -> Mermaid class and the colour token the PPTX uses.
KIND_STYLE = {
    "screen": "screen",
    "from": "muted",
    "next": "muted",
    "service": "service",
    "table": "table",
    "system": "system",
    "trigger": "trigger",
    "job": "job",
    "run": "service",
    "file": "system",
}
MERMAID_CLASSES = (
    "  classDef screen fill:#dbe9fb,stroke:#2a78d6,color:#0b0b0b\n"
    "  classDef service fill:#ece3fb,stroke:#7c4dcc,color:#0b0b0b\n"
    "  classDef table fill:#dcf3dc,stroke:#0ca30c,color:#0b0b0b\n"
    "  classDef system fill:#fde7d9,stroke:#ec835a,color:#0b0b0b\n"
    "  classDef trigger fill:#fff1cc,stroke:#d69a00,color:#0b0b0b\n"
    "  classDef job fill:#d8f1f0,stroke:#13867f,color:#0b0b0b\n"
    "  classDef muted fill:#f0efec,stroke:#898781,color:#0b0b0b"
)
_SCHEDULER_WORD = {
    "autosys": "Autosys job",
    "cron": "Cron job",
    "jcl": "JCL job",
    "spring-batch": "Spring Batch job",
}
_DAYS = ["mo", "tu", "we", "th", "fr", "sa", "su"]
_DAY_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]


@dataclass
class Node:
    key: str
    label: str
    kind: str
    detail: str = ""


@dataclass
class Flow:
    id: str  # the screen's or the batch's fact id
    kind: str  # screen | batch
    name: str
    slug: str
    component: str | None
    summary: str  # one deterministic sentence
    columns: list[tuple[str, list[Node]]]
    edges: list[tuple[str, str, str]]  # (from key, to key, label)
    tables: list[dict[str, Any]] = field(default_factory=list)  # name, crud, id
    systems: list[dict[str, Any]] = field(default_factory=list)  # type, target, direction, id
    services: list[dict[str, Any]] = field(default_factory=list)
    steps: list[dict[str, Any]] = field(default_factory=list)  # batch only
    before: list[str] = field(default_factory=list)
    after: list[str] = field(default_factory=list)
    schedule: dict[str, Any] = field(default_factory=dict)  # batch only
    on_failure: list[str] = field(default_factory=list)
    evidence: tuple[str | None, int | None] = (None, None)
    note: str = ""
    files: list[str] = field(default_factory=list)  # batch: source files it runs, in order

    @property
    def mermaid(self) -> str:
        return to_mermaid(self.columns, self.edges)

    def deck_block(self) -> dict[str, Any]:
        """The flow as a deck block: columns of nodes and the arrows between them."""
        return {
            "type": "flow",
            "title": self.name,
            "columns": [
                {
                    "title": title,
                    "nodes": [
                        {"key": n.key, "label": n.label, "kind": KIND_STYLE.get(n.kind, n.kind)}
                        for n in nodes
                    ],
                }
                for title, nodes in self.columns
            ],
            "edges": [{"from": a, "to": b, "label": lbl} for a, b, lbl in self.edges],
            "mermaid": self.mermaid,
        }


def to_mermaid(columns: list[tuple[str, list[Node]]], edges: list[tuple[str, str, str]]) -> str:
    ids: dict[str, str] = {}
    lines = ["flowchart LR"]
    for c, (_, nodes) in enumerate(columns):
        for n in nodes:
            ids[n.key] = f"n{len(ids)}"
            shown = n.detail if n.kind in ("service", "screen") else ""
            text = _label(n.label + (f" · {shown}" if shown else ""))
            shape = {
                "table": f"[({text})]",
                "system": f"([{text}])",
                "trigger": f"{{{{{text}}}}}",
                "screen": f"[{text}]",
            }.get(n.kind, f"[{text}]")
            lines.append(f"  {ids[n.key]}{shape}:::{KIND_STYLE.get(n.kind, 'muted')}")
        del c
    for a, b, lbl in edges:
        if a in ids and b in ids:
            arrow = f"-->|{_label(lbl)}|" if lbl else "-->"
            lines.append(f"  {ids[a]} {arrow} {ids[b]}")
    lines.append(MERMAID_CLASSES)
    return "\n".join(lines)


def _crud(letters: set[str]) -> str:
    return "".join(c for c in CRUD_ORDER if c in letters)


def crud_words(letters: str) -> str:
    words = [CRUD_WORDS[c] for c in letters if c in CRUD_WORDS]
    if len(words) > 1:
        return ", ".join(words[:-1]) + " and " + words[-1]
    return words[0] if words else "uses"


class _Index:
    """Lookups shared by both kinds of flow."""

    def __init__(self, rd: RunData) -> None:
        self.rd = rd
        self.services = {s["id"]: s for s in rd.items("services")}
        self.screens = {s["id"]: s for s in rd.items("screens")}
        self.sql = {s["id"]: s for s in rd.items("sql_statements")}
        self.services_by_file: dict[str, list[str]] = defaultdict(list)
        for s in rd.items("services"):
            if s.get("file") and s.get("kind") != "batch-job":
                self.services_by_file[s["file"]].append(s["id"])
        # sql id -> table -> CRUD letters, from the linker's CRUD on each table
        self.sql_ops: dict[str, dict[str, set[str]]] = defaultdict(lambda: defaultdict(set))
        self.table_ids: dict[str, str] = {}
        for o in rd.items("db_objects"):
            if o.get("object_type") not in ("table", "view", None):
                continue
            self.table_ids.setdefault(o["name"].upper(), o["id"])
            for op, refs in (o.get("crud") or {}).items():
                for ref in refs:
                    self.sql_ops[ref][o["name"].upper()].add(op)
        self.integrations = {f"{i['type']} {i['target']}": i for i in rd.items("integrations")}
        self.out: dict[str, list[dict[str, Any]]] = defaultdict(list)
        self.into: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for e in rd.items("dependencies"):
            if e.get("resolved", True):
                self.out[e["source"]].append(e)
                self.into[e["target"]].append(e)

    def owners(self, node: str) -> list[str]:
        """Services behind a node: itself when it is a service, else the services defined in
        the file (a COBOL program that sends a BMS map)."""
        if node in self.services:
            return [node]
        item = self.rd.by_id.get(node) or {}
        if item.get("category") != "source":  # web.xml, struts-config.xml: not the caller
            return []
        return list(self.services_by_file.get(item.get("path") or "", []))

    def tables_of(self, sids: list[str]) -> dict[str, set[str]]:
        out: dict[str, set[str]] = defaultdict(set)
        for sid in sids:
            svc = self.services.get(sid) or {}
            wanted = {t.upper() for t in svc.get("tables") or []}
            for q in svc.get("sql") or []:
                ops = self.sql_ops.get(q)
                st = self.sql.get(q) or {}
                if ops:
                    for t, letters in ops.items():
                        if t in wanted:
                            out[t] |= letters
                else:
                    letters = set(SQL_CRUD.get(str(st.get("type") or "").upper(), ""))
                    for t in st.get("tables") or []:
                        if t.upper() in wanted and letters:
                            out[t.upper()] |= letters
            for t in wanted:
                out.setdefault(t, set())
        return out

    def systems_of(self, sids: list[str]) -> list[dict[str, Any]]:
        seen: dict[str, dict[str, Any]] = {}
        for sid in sids:
            for call in (self.services.get(sid) or {}).get("external_calls") or []:
                if call.startswith("DB "):
                    continue  # the database is shown as its tables
                it = self.integrations.get(call)
                typ, _, target = call.partition(" ")
                seen.setdefault(
                    call,
                    {
                        "type": typ,
                        "target": target,
                        "direction": (it or {}).get("direction") or "unknown",
                        "id": (it or {}).get("id"),
                    },
                )
        return sorted(seen.values(), key=lambda s: (s["type"], s["target"]))

    def table_rows(self, tables: dict[str, set[str]]) -> list[dict[str, Any]]:
        return [
            {"name": t, "crud": _crud(tables[t]), "id": self.table_ids.get(t)}
            for t in sorted(tables)
        ]


def _service_label(svc: dict[str, Any]) -> str:
    name = str(svc.get("name") or "")
    return name if len(name) <= 48 else "…" + name[-47:]


def _screen_label(rd: RunData, sid: str) -> str:
    name = rd.label(sid)
    return name.rsplit("/", 1)[-1]


def _system_label(s: dict[str, Any]) -> str:
    target = s["target"] if len(s["target"]) <= 40 else "…" + s["target"][-39:]
    return f"{s['type']} {target}"


def _cap(nodes: list[Node], note: list[str], what: str) -> list[Node]:
    if len(nodes) > MAX_PER_COLUMN:
        note.append(f"Showing {MAX_PER_COLUMN} of {len(nodes)} {what}.")
        return nodes[:MAX_PER_COLUMN]
    return nodes


# ---- screens ---------------------------------------------------------------------------------
def screen_flows(rd: RunData) -> list[Flow]:
    ix = _Index(rd)
    real = {sid: s for sid, s in ix.screens.items() if not s.get("partial")}

    def screens_of_service(sid: str, depth: int = 0) -> list[str]:
        out = list((ix.services.get(sid) or {}).get("screens") or [])
        if depth < 1:  # a service forwarding to another service (Struts action chaining)
            for e in ix.out.get(sid, []):
                if e["kind"] == "forwards" and e["target"] in ix.services:
                    out += screens_of_service(e["target"], depth + 1)
        return [s for s in dict.fromkeys(out) if s in real]

    actions: dict[str, list[tuple[str, str]]] = {}  # screen -> [(service, how)]
    loaders: dict[str, list[str]] = {}
    nexts: dict[str, list[str]] = {}
    for sid in real:
        acts: list[tuple[str, str]] = []
        nxt: list[str] = []
        for e in ix.out.get(sid, []):
            if e["kind"] in ("submits", "links"):
                if e["target"] in ix.services:
                    acts.append((e["target"], "submits" if e["kind"] == "submits" else "opens"))
                elif e["target"] in real and e["target"] != sid:
                    nxt.append(e["target"])
        load: list[str] = []
        for e in ix.into.get(sid, []):
            if e["kind"] in ("renders", "forwards", "reads", "sends"):
                for owner in ix.owners(e["source"]):
                    load.append(owner)
        for svc_id, svc in ix.services.items():
            if sid in (svc.get("screens") or []):
                load.append(svc_id)
        load = [s for s in dict.fromkeys(load) if s not in {a for a, _ in acts}]
        for a, _ in acts:
            nxt += [s for s in screens_of_service(a) if s != sid]
        actions[sid], loaders[sid] = list(dict.fromkeys(acts)), load
        nexts[sid] = list(dict.fromkeys(nxt))
    comes: dict[str, list[str]] = defaultdict(list)
    for sid, nxt in nexts.items():
        for n in nxt:
            comes[n].append(sid)

    flows = []
    for sid in sorted(real, key=lambda s: _screen_label(rd, s).lower()):
        scr = real[sid]
        note: list[str] = []
        svc_ids = [*loaders[sid], *(a for a, _ in actions[sid])]
        tables = ix.tables_of(svc_ids)
        systems = ix.systems_of(svc_ids)
        me = Node(sid, _screen_label(rd, sid), "screen", scr.get("technology") or "")
        from_nodes = _cap(
            [Node("from:" + s, _screen_label(rd, s), "from") for s in comes.get(sid, [])],
            note,
            "previous screens",
        )
        svc_nodes = [
            Node(s, _service_label(ix.services[s]), "service", "loads the screen")
            for s in loaders[sid]
        ] + [Node(s, _service_label(ix.services[s]), "service", how) for s, how in actions[sid]]
        svc_nodes = _cap(svc_nodes, note, "services")
        table_rows = ix.table_rows(tables)
        table_nodes = _cap(
            [Node("t:" + t["name"], t["name"], "table", t["crud"]) for t in table_rows],
            note,
            "tables",
        )
        sys_nodes = _cap(
            [Node("x:" + s["type"] + s["target"], _system_label(s), "system") for s in systems],
            note,
            "systems",
        )
        next_nodes = _cap(
            [Node("next:" + s, _screen_label(rd, s), "next") for s in nexts[sid]],
            note,
            "next screens",
        )
        edges: list[tuple[str, str, str]] = [("from:" + s, sid, "") for s in comes.get(sid, [])]
        for s in loaders[sid]:
            edges.append((sid, s, "loaded by"))
        for s, how in actions[sid]:
            edges.append((sid, s, how))
        shown_svcs = {n.key for n in svc_nodes}
        for s in svc_ids:
            if s not in shown_svcs:
                continue
            for t, letters in ix.tables_of([s]).items():
                edges.append((s, "t:" + t, _crud(letters)))
            for x in ix.systems_of([s]):
                edges.append((s, "x:" + x["type"] + x["target"], ""))
            for n in screens_of_service(s):
                if n != sid and n in nexts[sid]:
                    edges.append((s, "next:" + n, "shows"))
        for n in nexts[sid]:
            if not any(b == "next:" + n for _, b, _ in edges):
                edges.append((sid, "next:" + n, "link"))
        columns = [
            ("Comes from", from_nodes),
            ("Screen", [me]),
            ("Services", svc_nodes),
            ("Tables", table_nodes),
            ("External systems", sys_nodes),
            ("Goes to", next_nodes),
        ]
        columns = [c for c in columns if c[1]]
        n_tables = len(table_rows)
        summary = (
            f"The screen {me.label} uses {len(svc_ids)} service(s)"
            + (
                f" and touches {n_tables} table(s)"
                if n_tables
                else " and touches no table found by the scan"
            )
            + (f"; it calls {len(systems)} external system(s)" if systems else "")
            + "."
        )
        flows.append(
            Flow(
                id=sid,
                kind="screen",
                name=me.label,
                slug=slug(f"screen-{me.label}-{sid[-6:]}"),
                component=scr.get("component"),
                summary=summary,
                columns=columns,
                edges=edges,
                tables=table_rows,
                systems=systems,
                services=[
                    {
                        "id": s,
                        "name": ix.services[s].get("name"),
                        "kind": ix.services[s].get("kind"),
                        "role": "loads the screen" if s in loaders[sid] else dict(actions[sid])[s],
                        "url": ix.services[s].get("url"),
                        "handler": ix.services[s].get("handler"),
                    }
                    for s in svc_ids
                ],
                before=[_screen_label(rd, s) for s in comes.get(sid, [])],
                after=[_screen_label(rd, s) for s in nexts[sid]],
                evidence=rd.first_evidence(scr),
                note=" ".join(note),
            )
        )
    return flows


# ---- batch -----------------------------------------------------------------------------------
def _ordered(members: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Members in run order: predecessors first, then by name."""
    names = {m["name"] for m in members}
    done: list[dict[str, Any]] = []
    left = sorted(members, key=lambda m: m["name"])
    while left:
        placed = {d["name"] for d in done}
        ready = [
            m
            for m in left
            if all(p in placed or p not in names for p in m.get("predecessors") or [])
        ]
        nxt = ready[0] if ready else left[0]
        done.append(nxt)
        left.remove(nxt)
    return done


def parse_schedule(schedule: str | None, scheduler: str) -> dict[str, Any]:
    """Days of the week (0 = Monday), start times and a plain sentence, from a cron line or the
    Autosys schedule attributes. Unknown shapes keep the raw text."""
    out: dict[str, Any] = {"days": [], "times": [], "text": "", "raw": schedule or ""}
    if not schedule:
        out["text"] = (
            "Not scheduled in the inputs provided (run on demand, or by a scheduler whose "
            "definitions were not provided)."
        )
        return out
    if scheduler == "cron":
        parts = schedule.split()
        if len(parts) >= 5:
            minute, hour, dom, mon, dow = parts[:5]
            days = _cron_days(dow)
            times = _cron_times(minute, hour)
            out["days"], out["times"] = days, times
            when = _days_text(days)
            if dom != "*":
                when = f"on day {dom} of {'every month' if mon == '*' else 'month ' + mon}"
                out["monthly"] = dom
            out["text"] = f"Runs {when}" + (f" at {', '.join(times)}" if times else "") + "."
            return out
    if scheduler == "autosys":
        attrs = dict(
            (k.strip(), v.strip())
            for k, _, v in (p.partition(":") for p in schedule.split(";"))
            if k.strip()
        )
        days = [
            i
            for i, d in enumerate(_DAYS)
            if d in [x.strip()[:2].lower() for x in attrs.get("days_of_week", "").split(",")]
        ]
        if attrs.get("days_of_week", "").strip().lower() == "all":
            days = list(range(7))
        times = [t.strip().strip('"') for t in attrs.get("start_times", "").split(",") if t.strip()]
        if attrs.get("start_mins"):
            times = [f"every hour at minute {attrs['start_mins']}"]
        out["days"], out["times"] = days, times
        when = _days_text(days) if days else ""
        if attrs.get("run_calendar"):
            when = f"on the days of calendar {attrs['run_calendar']}"
            out["calendar"] = attrs["run_calendar"]
        text = "Runs " + (when or "when started") + (f" at {', '.join(times)}" if times else "")
        if attrs.get("exclude_calendar"):
            text += f", except calendar {attrs['exclude_calendar']}"
        out["text"] = text + "."
        return out
    out["text"] = f"Schedule: {schedule}."
    return out


def _cron_days(dow: str) -> list[int]:
    if dow in ("*", "?"):
        return list(range(7))
    days: set[int] = set()
    for part in dow.split(","):
        rng = part.split("/")[0]
        try:
            if "-" in rng:
                a, b = (int(x) for x in rng.split("-", 1))
                vals = range(a, b + 1)
            else:
                vals = range(int(rng), int(rng) + 1)
        except ValueError:
            names = [d[:3].lower() for d in _DAY_NAMES]
            if rng[:3].lower() in names:
                days.add(names.index(rng[:3].lower()))
            continue
        for v in vals:
            days.add((v - 1) % 7)  # cron: 0 or 7 = Sunday, 1 = Monday
    return sorted(days)


def _cron_times(minute: str, hour: str) -> list[str]:
    if hour == "*" and minute.isdigit():
        return [f"every hour at minute {minute}"]
    if all(x.isdigit() for x in hour.split(",")) and minute.isdigit():
        return [f"{int(h):02d}:{int(minute):02d}" for h in hour.split(",")]
    return [f"{hour}:{minute}"] if hour != "*" else []


def _days_text(days: list[int]) -> str:
    if len(days) == 7:
        return "every day"
    if days == [0, 1, 2, 3, 4]:
        return "every weekday (Mon to Fri)"
    if days == [5, 6]:
        return "at weekends"
    if not days:
        return "on no fixed day"
    return "every " + ", ".join(_DAY_NAMES[d] for d in days)


def batch_flows(rd: RunData) -> list[Flow]:
    ix = _Index(rd)
    jobs = rd.items("batch_jobs")
    by_name = {j["name"]: j for j in jobs}
    members: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for j in jobs:
        if j.get("box") and j["box"] in by_name:
            members[j["box"]].append(j)
    runs: dict[str, list[str]] = defaultdict(list)
    for e in rd.items("dependencies"):
        if e["kind"] in _RUN_EDGES and e.get("resolved", True):
            runs[e["source"]].append(e["target"])
    successors: dict[str, list[str]] = defaultdict(list)
    for j in jobs:
        for p in j.get("predecessors") or []:
            successors[p].append(j["name"])
    watched = _watched_files(rd)
    file_ids = {f["path"]: f["id"] for f in rd.items("inventory") if f.get("id")}

    def run_targets(jid: str) -> list[tuple[str, str]]:
        """(label, kind) of what a job runs, one hop on through scripts."""
        out: list[tuple[str, str]] = []
        for t in runs.get(jid, []):
            if t.startswith("batch_job:"):
                out.append((rd.label(t), "job"))
                continue
            out.append((rd.label(t).rsplit("/", 1)[-1], "run"))
            for t2 in runs.get(t, []):
                kind = "job" if t2.startswith("batch_job:") else "run"
                out.append((rd.label(t2).rsplit("/", 1)[-1], kind))
        return list(dict.fromkeys(out))

    def files_run(start: str) -> list[str]:
        """Source files a job or script runs, two hops on (a script, then its programs)."""
        out: list[str] = []
        frontier = [start]
        for _ in range(3):
            nxt: list[str] = []
            for n in frontier:
                for t in runs.get(n, []):
                    if t.startswith("batch_job:"):
                        continue
                    item = rd.by_id.get(t) or {}
                    p = item.get("path") or item.get("file")
                    if p:
                        out.append(str(p))
                    nxt.append(t)
                    if item.get("file") and item["file"] in file_ids:
                        nxt.append(file_ids[item["file"]])
            frontier = nxt
        return list(dict.fromkeys(out))

    callers: dict[str, list[str]] = defaultdict(list)
    for src, targets in runs.items():
        for t in targets:
            callers[t].append(src)

    def started_by(jid: str) -> list[str]:
        """Jobs that start this one, directly or through a script they run."""
        out: list[str] = []
        for src in callers.get(jid, []):
            if src.startswith("batch_job:"):
                out.append(rd.label(src))
                continue
            for src2 in callers.get(src, []):
                if src2.startswith("batch_job:"):
                    out.append(f"{rd.label(src2)} (via {rd.label(src).rsplit('/', 1)[-1]})")
        return list(dict.fromkeys(out))

    units = [
        j
        for j in jobs
        if not (j.get("box") and j["box"] in by_name) and j.get("kind") != "file-watcher"
    ]
    units += [
        j
        for j in jobs
        if j.get("kind") == "file-watcher" and not (j.get("box") and j["box"] in by_name)
    ]
    flows: list[Flow] = []
    for unit in sorted(units, key=lambda u: (u.get("scheduler") or "", u["name"])):
        note: list[str] = []
        is_box = unit.get("kind") == "box"
        steps_src = _ordered(members.get(unit["name"], [])) if is_box else [unit]
        sched = parse_schedule(unit.get("schedule"), unit.get("scheduler") or "")
        starters = started_by(unit["id"])
        if starters and not unit.get("schedule"):
            sched["text"] = f"Started by {', '.join(starters)}."
            sched["started_by"] = starters
        trig: list[Node] = []
        for who in starters:
            trig.append(Node("trigger:by:" + who, f"started by {who}", "trigger"))
        if unit.get("schedule"):
            trig.append(Node("trigger:schedule", sched["text"].removesuffix("."), "trigger"))
        for p in unit.get("predecessors") or []:
            trig.append(Node("trigger:after:" + p, f"after {p}", "trigger"))
        step_nodes: list[Node] = []
        run_nodes: list[Node] = []
        file_nodes: list[Node] = []
        edges: list[tuple[str, str, str]] = []
        job_sids: list[str] = []
        steps: list[dict[str, Any]] = []
        failure: list[str] = (
            [f"{unit['name']}: {_failure_words(f)}" for f in unit.get("on_failure") or []]
            if is_box
            else []
        )
        prev: str | None = None
        flow_files: list[str] = []
        for i, j in enumerate(steps_src, 1):
            flow_files += files_run(j["id"])
            jkey = "job:" + j["name"]
            kind = j.get("kind") or "job"
            if kind == "file-watcher":
                files = watched.get(j["id"], [])
                label = f"Wait for file {files[0]}" if files else f"Wait for a file ({j['name']})"
                step_nodes.append(Node(jkey, label, "trigger", j["name"]))
                for f in files:
                    fkey = "file:" + f
                    if fkey not in {n.key for n in file_nodes}:
                        file_nodes.append(Node(fkey, f"FILE {f}", "file", "in"))
                    edges.append((fkey, jkey, "arrives"))
            else:
                step_nodes.append(Node(jkey, j["name"], "job", j.get("scheduler") or ""))
            conds: dict[str, str] = {}
            for m in _CONDITION.finditer(j.get("condition") or ""):
                word = _COND_LABEL.get(m.group(1)[0].lower(), m.group(1))
                conds[m.group(2)] = (
                    f"{conds[m.group(2)]} or {word}" if m.group(2) in conds else word
                )
            linked = False
            for p in j.get("predecessors") or []:
                if any(s["name"] == p for s in steps_src):
                    edges.append(("job:" + p, jkey, conds.get(p, "")))
                    linked = True
                    if conds.get(p) == "failure":
                        failure.append(f"If {p} fails, {j['name']} runs.")
                    elif "failure" in conds.get(p, ""):
                        failure.append(f"{j['name']} runs after {p} whether it succeeds or fails.")
            if not linked and prev is not None and not is_box:
                edges.append((prev, jkey, "then"))
            if i == 1 or not linked:
                for tn in trig:
                    if tn.key.startswith("trigger:schedule") or not is_box:
                        edges.append((tn.key, jkey, ""))
            run_to = run_targets(j["id"])
            for label, tkind in run_to:
                rkey = "run:" + label
                if rkey not in {n.key for n in run_nodes}:
                    run_nodes.append(Node(rkey, label, tkind))
                edges.append((jkey, rkey, "runs"))
            for step in j.get("steps") or []:
                skey = f"step:{j['name']}:{step}"
                run_nodes.append(Node(skey, step, "run", "step"))
                edges.append((jkey, skey, "step"))
            if not j.get("steps"):
                for prog in (j.get("programs") or [])[:6]:
                    label = prog.rsplit(".", 1)[-1]
                    rkey = "run:" + label
                    if rkey not in {n.key for n in run_nodes}:
                        run_nodes.append(Node(rkey, label, "run", "program"))
                    edges.append((jkey, rkey, "runs"))
            for ds in j.get("datasets") or []:
                fkey = "file:" + ds
                if fkey not in {n.key for n in file_nodes}:
                    file_nodes.append(Node(fkey, ds, "file", "dataset"))
                edges.append((jkey, fkey, ""))
            failure += [f"{j['name']}: {_failure_words(f)}" for f in (j.get("on_failure") or [])]
            sid = make_id("service", "batch-job", j["id"])
            if sid in ix.services:
                job_sids.append(sid)
            steps.append(
                {
                    "order": i,
                    "name": j["name"],
                    "kind": kind,
                    "id": j["id"],
                    "description": j.get("description") or "",
                    "command": j.get("command") or "",
                    "condition": j.get("condition") or "",
                    "runs": [t for t, _ in run_to],
                    "steps": list(j.get("steps") or []),
                    "programs": list(j.get("programs") or []),
                    "datasets": list(j.get("datasets") or []),
                    "machine": j.get("machine") or "",
                    "service_id": sid if sid in ix.services else None,
                    "evidence": rd.first_evidence(j),
                }
            )
            prev = jkey
        tables = ix.tables_of(job_sids)
        table_rows = ix.table_rows(tables)
        systems = ix.systems_of(job_sids)
        table_nodes = [Node("t:" + t["name"], t["name"], "table", t["crud"]) for t in table_rows]
        for s in systems:
            key = "x:" + s["type"] + s["target"]
            if s["type"] == "FILE" and "file:" + s["target"] in {n.key for n in file_nodes}:
                continue
            file_nodes.append(Node(key, _system_label(s), "system", s["direction"]))
        for st in steps:
            if not st["service_id"]:
                continue
            src = "job:" + st["name"]
            for t, letters in ix.tables_of([st["service_id"]]).items():
                edges.append((src, "t:" + t, _crud(letters)))
            for x in ix.systems_of([st["service_id"]]):
                key = "x:" + x["type"] + x["target"]
                if x["direction"] == "inbound":
                    edges.append((key, src, "in"))
                else:
                    edges.append((src, key, "out" if x["direction"] == "outbound" else ""))
        names = {s["name"] for s in steps} | {unit["name"]}
        before = [p for p in unit.get("predecessors") or [] if p not in names]
        after = sorted(
            {n for s in names for n in successors.get(s, []) if n not in names and n in by_name}
        )
        columns = [
            ("Starts when", trig),
            ("Steps" if is_box or len(steps) > 1 else "Job", _cap(step_nodes, note, "steps")),
            ("Runs", _cap(run_nodes, note, "programs and steps")),
            ("Tables", _cap(table_nodes, note, "tables")),
            ("Files and systems", _cap(file_nodes, note, "files and systems")),
        ]
        columns = [c for c in columns if c[1]]
        kind_word = "Box" if is_box else _SCHEDULER_WORD.get(unit.get("scheduler") or "", "Job")
        summary = (
            f"{kind_word} {unit['name']}"
            + (f" has {len(steps)} step(s). " if is_box else ". ")
            + sched["text"]
            + (f" It touches {len(table_rows)} table(s)." if table_rows else "")
        )
        flows.append(
            Flow(
                id=unit["id"],
                kind="batch",
                name=unit["name"],
                slug=slug(f"batch-{unit['name']}-{unit['id'][-6:]}"),
                component=unit.get("component")
                or next((s.get("component") for s in steps_src if s.get("component")), None),
                summary=summary,
                columns=columns,
                edges=edges,
                tables=table_rows,
                systems=systems,
                services=[{"id": s, "name": ix.services[s].get("name")} for s in job_sids],
                steps=steps,
                before=before,
                after=after,
                schedule={
                    **sched,
                    "scheduler": unit.get("scheduler") or "",
                    "description": unit.get("description") or "",
                },
                on_failure=list(dict.fromkeys(failure)),
                evidence=rd.first_evidence(unit),
                note=" ".join(note),
                files=list(dict.fromkeys(flow_files)),
            )
        )
    flows += _unscheduled_flows(rd, ix, runs, callers, jobs)
    return flows


# ---- batch programs that no scheduler definition starts -------------------------------------
_PROGRAM_SERVICES = {"c-executable", "cobol-program"}
_BATCH_COMPONENTS = {"batch", "script"}
_EVENT_ENTRY = {"message-listener", "listener", "jms-listener", "mq-listener"}
_NO_SCHEDULE = (
    "No schedule found: no Autosys JIL, JCL or crontab entry in the inputs starts it. "
    "It may be run by hand or by a scheduler whose export was not provided."
)


def _unscheduled_flows(
    rd: RunData,
    ix: _Index,
    runs: dict[str, list[str]],
    callers: dict[str, list[str]],
    jobs: list[dict[str, Any]],
) -> list[Flow]:
    """Batch work the scan found but no scheduler definition covers: top-level scripts and
    batch programs in batch components, stand-alone C/COBOL batch programs, and message
    listeners. Without them the Batch pages would say "no batch" whenever the JIL, JCL or
    crontab export is missing (a coverage gap, not an absence of batches)."""
    covered: set[str] = set()
    todo = [j["id"] for j in jobs]
    while todo:
        n = todo.pop()
        for t in runs.get(n, []):
            if t not in covered:
                covered.add(t)
                todo.append(t)
    files = {f["path"]: f for f in rd.items("inventory")}
    file_ids = {f["path"]: f["id"] for f in files.values() if f.get("id")}
    covered_files = {
        (rd.by_id.get(c) or {}).get("path") or (rd.by_id.get(c) or {}).get("file") for c in covered
    }
    batch_comps = {c["name"] for c in rd.items("components") if c.get("type") in _BATCH_COMPONENTS}

    def comp_of(path: str) -> str | None:
        return rd.file_component.get(path)

    units: list[dict[str, Any]] = []  # {id, name, kind, file, line, component}
    seen_files: set[str] = set()
    for f in sorted(files.values(), key=lambda f: f["path"]):
        path = f["path"]
        if f.get("language") != "shell" or comp_of(path) not in batch_comps:
            continue
        fid = f.get("id") or ""
        if fid in covered or callers.get(fid):
            continue  # started by a job, or run by another script or program
        units.append(
            {"id": fid, "name": path.rsplit("/", 1)[-1], "kind": "script", "file": path,
             "line": None, "component": comp_of(path)}
        )  # fmt: skip
        seen_files.add(path)
    run_by_script = {
        t for u in units for t in runs.get(u["id"], [])
    }  # programs a top-level script runs are its steps, not units of their own
    for svc in sorted(ix.services.values(), key=lambda s: s["name"]):
        if svc.get("kind") not in _PROGRAM_SERVICES or svc["id"] in covered:
            continue
        if svc["id"] in run_by_script or svc.get("file") in covered_files:
            continue
        units.append(
            {"id": svc["id"], "name": svc["name"], "kind": "program", "file": svc.get("file"),
             "line": rd.first_evidence(svc)[1], "component": svc.get("component"),
             "service_id": svc["id"]}
        )  # fmt: skip
        seen_files.add(svc.get("file") or "")
    run_files = {
        (rd.by_id.get(t) or {}).get("file") or (rd.by_id.get(t) or {}).get("path")
        for t in run_by_script
    }
    for ep in sorted(rd.items("entry_points"), key=lambda e: e["name"]):
        kind = ep.get("kind") or ""
        if ep.get("file") in seen_files | covered_files | run_files:
            continue
        if ep.get("component") not in batch_comps:
            continue
        if kind in _EVENT_ENTRY or kind == "main":
            units.append(
                {"id": ep["id"], "name": ep["name"].rsplit(".", 1)[-1],
                 "kind": "listener" if kind in _EVENT_ENTRY else "program",
                 "file": ep.get("file"), "line": ep.get("line"), "component": ep.get("component")}
            )  # fmt: skip
            seen_files.add(ep.get("file") or "")

    sql_by_file: dict[str, list[str]] = defaultdict(list)
    for q in rd.items("sql_statements"):
        if q.get("file"):
            sql_by_file[q["file"]].append(q["id"])
    integ_by_file: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for i in rd.items("integrations"):
        for ev in i.get("evidence") or []:
            if ev.get("file"):
                integ_by_file[ev["file"]].append(i)

    def tables_of_file(path: str) -> dict[str, set[str]]:
        out: dict[str, set[str]] = defaultdict(set)
        for q in sql_by_file.get(path, []):
            ops = ix.sql_ops.get(q)
            st = ix.sql.get(q) or {}
            if ops:
                for t, letters in ops.items():
                    out[t] |= letters
            else:
                letters = set(SQL_CRUD.get(str(st.get("type") or "").upper(), ""))
                for t in st.get("tables") or []:
                    out[t.upper()] |= letters
        return out

    def systems_of_file(path: str) -> list[dict[str, Any]]:
        return [
            {"type": i["type"], "target": i["target"],
             "direction": i.get("direction") or "unknown", "id": i.get("id")}
            for i in integ_by_file.get(path, [])
            if i["type"] != "DB"
        ]  # fmt: skip

    def path_of(node: str) -> str:
        item = rd.by_id.get(node) or {}
        return str(item.get("path") or item.get("file") or "")

    flows: list[Flow] = []
    for u in units:
        note: list[str] = []
        ukey = "unit:" + u["name"]
        listener = u["kind"] == "listener"
        trig = [
            Node(
                "trigger:" + ("message" if listener else "none"),
                "when a message arrives (listener)" if listener else "no schedule found",
                "trigger" if listener else "muted",
            )
        ]
        head = Node(ukey, u["name"], "job", u["kind"])
        edges: list[tuple[str, str, str]] = [(trig[0].key, ukey, "")]
        run_nodes: list[Node] = []
        table_acc: dict[str, set[str]] = defaultdict(set)
        sys_acc: dict[str, dict[str, Any]] = {}
        sids: list[str] = []
        steps: list[dict[str, Any]] = []
        own_paths = [u.get("file") or ""]

        def add_tables(
            src: str,
            tabs: dict[str, set[str]],
            table_acc: dict[str, set[str]] = table_acc,
            edges: list[tuple[str, str, str]] = edges,
        ) -> None:
            for t, letters in tabs.items():
                table_acc[t] |= letters
                edges.append((src, "t:" + t, _crud(letters)))

        def add_systems(
            src: str,
            systems: list[dict[str, Any]],
            sys_acc: dict[str, dict[str, Any]] = sys_acc,
            edges: list[tuple[str, str, str]] = edges,
        ) -> None:
            for x in systems:
                key = "x:" + x["type"] + x["target"]
                sys_acc.setdefault(key, x)
                if x["direction"] == "inbound":
                    edges.append((key, src, "in"))
                else:
                    edges.append((src, key, "out" if x["direction"] == "outbound" else ""))

        if u.get("service_id"):
            sids.append(u["service_id"])
            add_tables(ukey, ix.tables_of([u["service_id"]]))
            add_systems(ukey, ix.systems_of([u["service_id"]]))
        else:
            add_tables(ukey, tables_of_file(u.get("file") or ""))
            add_systems(ukey, systems_of_file(u.get("file") or ""))
        # what it runs: the script's commands, or what the program's file reaches (2 hops)
        start = [u["id"]] + ([file_ids[u["file"]]] if u.get("file") in file_ids else [])
        reached: list[str] = []
        for s0 in start:
            for t in runs.get(s0, []):
                reached.append(t)
                if t.startswith("file:"):
                    reached += [t2 for t2 in runs.get(t, []) if not t2.startswith("service:")]
        order = 0
        for t in dict.fromkeys(reached):
            item = rd.by_id.get(t) or {}
            if t.startswith("file:") and item.get("category") == "source" and u["kind"] != "script":
                own_paths.append(path_of(t))  # a helper module of the program: part of it
                add_systems(ukey, systems_of_file(path_of(t)))
                continue
            order += 1
            label = rd.label(t).rsplit("/", 1)[-1]
            rkey = "run:" + label
            if t.startswith("batch_job:"):
                rkind = "job"
            elif t.startswith("service:"):
                rkind = "service"
            else:
                rkind = "run"
            if rkey not in {n.key for n in run_nodes}:
                run_nodes.append(Node(rkey, label, rkind))
            edges.append((ukey, rkey, "runs"))
            sid = make_id("service", "batch-job", t) if t.startswith("batch_job:") else t
            if sid in ix.services:
                sids.append(sid)
                add_tables(rkey, ix.tables_of([sid]))
                add_systems(rkey, ix.systems_of([sid]))
            elif t.startswith("file:"):
                add_tables(rkey, tables_of_file(path_of(t)))
                add_systems(rkey, systems_of_file(path_of(t)))
            steps.append(
                {
                    "order": order,
                    "name": label,
                    "kind": {"job": "job", "service": "program"}.get(rkind, "script or file"),
                    "id": t,
                    "description": "",
                    "command": "",
                    "condition": "",
                    "runs": [],
                    "steps": [],
                    "programs": [],
                    "datasets": [],
                    "machine": "",
                    "service_id": sid if sid in ix.services else None,
                    "evidence": rd.first_evidence(item) if item else (None, None),
                }
            )
        steps.insert(
            0,
            {
                "order": 0,
                "name": u["name"],
                "kind": u["kind"],
                "id": u["id"],
                "description": "",
                "command": "",
                "condition": "",
                "runs": [n.label for n in run_nodes],
                "steps": [],
                "programs": [],
                "datasets": [],
                "machine": "",
                "service_id": u.get("service_id"),
                "evidence": (u.get("file"), u.get("line")),
            },
        )
        table_rows = ix.table_rows(table_acc)
        table_nodes = [Node("t:" + t["name"], t["name"], "table", t["crud"]) for t in table_rows]
        systems = sorted(sys_acc.values(), key=lambda x: (x["type"], x["target"]))
        sys_nodes = [
            Node("x:" + x["type"] + x["target"], _system_label(x), "system", x["direction"])
            for x in systems
        ]
        what = {"script": "Script", "program": "Batch program", "listener": "Listener"}[u["kind"]]
        columns = [
            ("Starts when", trig),
            (what, [head]),
            ("Runs", _cap(run_nodes, note, "programs and files")),
            ("Tables", _cap(table_nodes, note, "tables")),
            ("Files and systems", _cap(sys_nodes, note, "files and systems")),
        ]
        columns = [c for c in columns if c[1]]
        when = (
            "Starts when a message arrives on the queue it listens to."
            if listener
            else _NO_SCHEDULE
        )
        summary = (
            f"{what} {u['name']}"
            + (f" runs {len(run_nodes)} program(s) or file(s)." if run_nodes else ".")
            + (f" It touches {len(table_rows)} table(s)." if table_rows else "")
            + ("" if listener else " No scheduler definition starts it.")
        )
        flows.append(
            Flow(
                id=u["id"],
                kind="batch",
                name=u["name"],
                slug=slug(f"batch-{u['name']}-{u['id'][-6:]}"),
                component=u.get("component"),
                summary=summary,
                columns=columns,
                edges=list(dict.fromkeys(edges)),
                tables=table_rows,
                systems=systems,
                services=[{"id": s, "name": ix.services[s].get("name")} for s in sids],
                steps=steps,
                schedule={
                    "days": [],
                    "times": [],
                    "text": when,
                    "raw": "",
                    "scheduler": "event" if listener else "none",
                    "description": "",
                    "unscheduled": not listener,
                },
                on_failure=[],
                evidence=(u.get("file"), u.get("line")),
                note=" ".join(note),
                files=list(
                    dict.fromkeys(
                        [p for p in own_paths if p]
                        + [str(e[0]) for st in steps[1:] if (e := st["evidence"])[0]]
                    )
                ),
            )
        )
    return flows


_FAILURE_WORDS = {
    "alarm_if_fail": "raises an alarm when it fails",
    "alarm_if_terminated": "raises an alarm when it is terminated",
    "n_retrys": "is retried {v} time(s) automatically",
    "max_run_alarm": "raises an alarm when it runs longer than {v} minutes",
    "term_run_time": "is stopped after {v} minutes",
    "box_failure": "the box fails when: {v}",
    "send_notification": "sends a notification",
    "notification_msg": "notification text: {v}",
}


def _failure_words(item: str) -> str:
    """'n_retrys: 1' -> 'is retried 1 time(s) automatically (n_retrys: 1)'."""
    key, _, value = item.partition(":")
    value = value.strip()
    words = _FAILURE_WORDS.get(key.strip())
    if not words or value in ("0", "n", "N"):
        return item
    return f"{words.format(v=value)} ({item})"


def _watched_files(rd: RunData) -> dict[str, list[str]]:
    """File-watcher job id -> watched files (the inbound FILE integration it declared)."""
    by_job: dict[str, list[str]] = defaultdict(list)
    names = {j["name"]: j["id"] for j in rd.items("batch_jobs") if j.get("kind") == "file-watcher"}
    for i in rd.items("integrations"):
        job = (i.get("details") or {}).get("job")
        if i.get("type") == "FILE" and job in names:
            by_job[names[job]].append(i["target"])
    return by_job


def crud_matrix(rd: RunData, group_by: str = "component") -> dict[str, Any]:
    """Rows (components, or capabilities via ``group_by``) x tables, cells = CRUD letters, from
    the SQL each statement's component runs. Returned sorted by the busiest tables."""
    ix = _Index(rd)
    cells: dict[tuple[str, str], set[str]] = defaultdict(set)
    for q, st in ix.sql.items():
        comp = st.get("component") or rd.file_component.get(st.get("file") or "") or "(none)"
        ops = ix.sql_ops.get(q)
        if ops:
            for t, letters in ops.items():
                cells[(comp, t)] |= letters
        else:
            letters = set(SQL_CRUD.get(str(st.get("type") or "").upper(), ""))
            for t in st.get("tables") or []:
                if letters:
                    cells[(comp, t.upper())] |= letters
    use: dict[str, int] = defaultdict(int)
    for (_, t), letters in cells.items():
        use[t] += len(letters)
    tables = sorted(use, key=lambda t: (-use[t], t))
    rows = sorted({c for c, _ in cells})
    return {
        "rows": rows,
        "tables": tables,
        "cells": {f"{r}|{t}": _crud(cells[(r, t)]) for r, t in cells},
        "table_ids": {t: ix.table_ids.get(t) for t in tables},
    }


def tables_used_together(rd: RunData, limit: int = 40) -> list[tuple[str, str, int]]:
    """Pairs of tables used in the same SQL statement (joins, sub-queries), with how many
    statements; a proxy for relationships when the DDL's keys are not parsed."""
    pairs: dict[tuple[str, str], int] = defaultdict(int)
    for st in rd.items("sql_statements"):
        ts = sorted({t.upper() for t in st.get("tables") or []})
        for i, a in enumerate(ts):
            for b in ts[i + 1 :]:
                pairs[(a, b)] += 1
    ranked = sorted(pairs.items(), key=lambda kv: (-kv[1], kv[0]))[:limit]
    return [(a, b, n) for (a, b), n in ranked]


def data_map_mermaid(rd: RunData) -> str:
    """Tables as nodes, coloured, joined when they appear in the same statement."""
    pairs = tables_used_together(rd)
    tables = {t for a, b, _ in pairs for t in (a, b)}
    ids = {t: f"t{i}" for i, t in enumerate(sorted(tables))}
    lines = ["flowchart LR"]
    for t, i in ids.items():
        lines.append(f"  {i}[({_label(t)})]:::table")
    for a, b, n in pairs:
        lines.append(f"  {ids[a]} ---|{_label(str(n) + ' statement(s)')}| {ids[b]}")
    lines.append(MERMAID_CLASSES)
    return "\n".join(lines)


def week_grid(flows: list[Flow]) -> list[dict[str, Any]]:
    """One row per scheduled batch: its start times on each day of the week."""
    rows = []
    for f in flows:
        if f.kind != "batch":
            continue
        sched = f.schedule
        days = set(sched.get("days") or [])
        times = ", ".join(sched.get("times") or []) or ("calendar" if sched.get("calendar") else "")
        cells = [times if d in days else "" for d in range(7)]
        if sched.get("monthly"):
            cells = ["" for _ in range(7)]
        rows.append(
            {
                "name": f.name,
                "slug": f.slug,
                "scheduler": sched.get("scheduler", ""),
                "cells": cells,
                "text": sched.get("text", ""),
                "hours": [_hour(t) for t in sched.get("times") or [] if _hour(t) is not None],
            }
        )
    return rows


def _hour(t: str) -> float | None:
    m = re.match(r"^(\d{1,2}):(\d{2})$", t.strip())
    return int(m.group(1)) + int(m.group(2)) / 60 if m else None


DAY_NAMES = _DAY_NAMES


_COMMENT_LANGS = {
    "shell",
    "sql",
    "plsql",
    "c",
    "cpp",
    "proc",
    "java",
    "cobol",
    "jcl",
    "perl",
    "python",
}


def code_comments(rd: RunData, f: Flow, limit: int = 6) -> list[dict[str, Any]]:
    """The header comment of each source file a batch runs (``{file, lines}``), read from the
    scanned source when it is on this machine; what the programs say about themselves."""
    langs = {i["path"]: i.get("language") or "" for i in rd.items("inventory")}
    out: list[dict[str, Any]] = []
    for path in f.files:
        lang = langs.get(path, "")
        if lang not in _COMMENT_LANGS:
            continue
        p = rd.abs_path(path)
        if p is None or not p.is_file():
            continue
        lines = header_comment(p, lang)
        if lines:
            out.append({"file": path, "lines": lines})
        if len(out) >= limit:
            break
    return out
