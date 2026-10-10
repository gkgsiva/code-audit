"""Mermaid diagrams generated from the facts (code edges, integrations, batch jobs).

Every node and arrow comes from a fact; labels are escaped for Mermaid. Large estates are capped
(``MAX_NODES``) with a note, since a diagram past that size is unreadable; the catalogues hold
the full lists.
"""

from __future__ import annotations

import re
from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Any

from legacy_audit.report.data import RunData

MAX_NODES = 60
_CONDITION = re.compile(
    r"\b(s|success|f|failure|d|done|n|notrunning|e|exitcode)\s*\(\s*([\w.#-]+)", re.I
)
_COND_LABEL = {"s": "success", "f": "failure", "d": "done", "n": "not running", "e": "exit code"}
_RUN_EDGES = {"invokes", "runs", "triggers", "calls"}


@dataclass
class Diagram:
    title: str
    source: str  # Mermaid text
    note: str = ""
    nodes: int = 0


def _label(text: str) -> str:
    """A quoted Mermaid label (quotes and markup characters escaped as entities)."""
    out = (
        str(text)
        .replace("&", "#amp;")
        .replace('"', "#quot;")
        .replace("<", "#lt;")
        .replace(">", "#gt;")
        .replace("@", "#64;")  # Mermaid reads user@host as a markdown link
        .replace("://", "#58;//")  # ...and http://host as one
    )
    return f'"{out}"'


class _Ids:
    def __init__(self, prefix: str) -> None:
        self.prefix = prefix
        self.ids: dict[str, str] = {}

    def __call__(self, key: str) -> str:
        if key not in self.ids:
            self.ids[key] = f"{self.prefix}{len(self.ids)}"
        return self.ids[key]


def component_graph(rd: RunData) -> Diagram:
    """Components and the code edges between them (calls, includes, uses, runs...)."""
    pairs: Counter[tuple[str, str]] = Counter()
    kinds: dict[tuple[str, str], set[str]] = defaultdict(set)
    for e in rd.items("dependencies"):
        if not e.get("resolved", True):
            continue
        a, b = rd.component_of(e["source"]), rd.component_of(e["target"])
        if a and b and a != b:
            pairs[(a, b)] += 1
            kinds[(a, b)].add(e["kind"])
    comps = {c["name"]: c for c in rd.items("components")}
    ranked = sorted(comps, key=lambda n: -comps[n].get("loc_code", 0))[:MAX_NODES]
    keep = set(ranked)
    ids = _Ids("c")
    lines = ["flowchart LR"]
    for name in sorted(keep):
        c = comps[name]
        text = _label(f"{name} · {c.get('type')} · {c.get('loc_code', 0)} LOC")
        lines.append(f"  {ids(name)}[{text}]")
    for (a, b), n in sorted(pairs.items()):
        if a in keep and b in keep:
            lines.append(
                f"  {ids(a)} -->|{_label(f'{n} · ' + ', '.join(sorted(kinds[(a, b)])))}| {ids(b)}"
            )
    note = ""
    if len(comps) > MAX_NODES:
        note = f"Showing the {MAX_NODES} largest of {len(comps)} components."
    if not pairs:
        note = (note + " " if note else "") + "No code edges cross component boundaries."
    return Diagram("Component dependencies", "\n".join(lines), note, len(keep))


def integration_map(rd: RunData) -> Diagram:
    """Components and the external systems they reach (databases, queues, hosts, files)."""
    integs = sorted(rd.items("integrations"), key=lambda i: -int(i.get("usages") or 0))
    shown = integs[:MAX_NODES]
    comp_ids, int_ids = _Ids("c"), _Ids("i")
    lines = ["flowchart LR"]
    comps: set[str] = set()
    for i in shown:
        comps.update(i.get("components") or [])
    for c in sorted(comps):
        lines.append(f"  {comp_ids(c)}[{_label(c)}]")
    for i in shown:
        iid = int_ids(i["id"])
        text = _label(f"{i['type']}: {i['target']}")
        shape = f"[({text})]" if i["type"] == "DB" else f"([{text}])"
        lines.append(f"  {iid}{shape}")
        direction = i.get("direction") or "unknown"
        for c in i.get("components") or []:
            if direction == "outbound":
                lines.append(f"  {comp_ids(c)} --> {iid}")
            elif direction == "inbound":
                lines.append(f"  {iid} --> {comp_ids(c)}")
            elif direction == "both":
                lines.append(f"  {comp_ids(c)} <--> {iid}")
            else:
                lines.append(f"  {comp_ids(c)} -.- {iid}")
    note = (
        f"Showing the {MAX_NODES} most used of {len(integs)} integrations."
        if len(integs) > MAX_NODES
        else ""
    )
    if not integs:
        note = "No integrations found."
    return Diagram("Integration map", "\n".join(lines), note, len(comps) + len(shown))


def batch_chains(rd: RunData) -> list[Diagram]:
    """One flowchart per scheduler: jobs inside their boxes, dependencies with their condition,
    and what each job runs (scripts, programs, Spring Batch jobs, steps)."""
    jobs = rd.items("batch_jobs")
    by_sched: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for j in jobs:
        by_sched[j.get("scheduler") or "unknown"].append(j)
    runs: dict[str, list[str]] = defaultdict(list)
    for e in rd.items("dependencies"):
        if e["kind"] in _RUN_EDGES and e["source"].startswith(("batch_job:", "file:")):
            runs[e["source"]].append(e["target"])
    out = []
    for sched in sorted(by_sched):
        group = by_sched[sched][:MAX_NODES]
        ids = _Ids("j")
        names = {j["name"]: j for j in group}
        lines = ["flowchart TD"]
        boxes: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for j in group:
            if j.get("kind") != "box":
                boxes[j.get("box") or ""].append(j)
        for box, members in sorted(boxes.items()):
            if box:
                lines.append(f"  subgraph {ids('box:' + box)}[{_label('box ' + box)}]")
            for j in members:
                kind = j.get("kind") or "job"
                text = _label(f"{j['name']} ({kind})" if kind != "job" else j["name"])
                shape = f"{{{{{text}}}}}" if kind == "file-watcher" else f"[{text}]"
                lines.append(f"    {ids(j['name'])}{shape}")
            if box:
                lines.append("  end")
        for j in group:
            conds = {
                m.group(2): _COND_LABEL.get(m.group(1)[0].lower(), m.group(1))
                for m in _CONDITION.finditer(j.get("condition") or "")
            }
            for pred in j.get("predecessors") or []:
                if pred in names:
                    label = conds.get(pred)
                    arrow = f"-->|{_label(label)}|" if label else "-->"
                    lines.append(f"  {ids(pred)} {arrow} {ids(j['name'])}")
            for target in _run_targets(rd, runs, j["id"]):
                lines.append(
                    f"  {ids(j['name'])} -.->|runs| {ids('t:' + target)}[{_label(target)}]"
                )
            steps = j.get("steps") or []
            prev = ids(j["name"])
            for step in steps[:12]:
                sid = ids(f"step:{j['name']}:{step}")
                lines.append(f"  {prev} --> {sid}([{_label(step)}])")
                prev = sid
            if not steps:
                for prog in (j.get("programs") or [])[:6]:
                    text = _label(prog.rsplit(".", 1)[-1])
                    lines.append(f"  {ids(j['name'])} -.-> {ids('p:' + prog)}[{text}]")
        note = (
            f"Showing {MAX_NODES} of {len(by_sched[sched])} jobs."
            if len(by_sched[sched]) > MAX_NODES
            else ""
        )
        out.append(Diagram(f"Batch chains: {sched}", "\n".join(lines), note, len(group)))
    return out


def _run_targets(rd: RunData, runs: dict[str, list[str]], job_id: str) -> list[str]:
    """What a job runs: scripts and programs, then (one hop on) the jobs those trigger."""
    out: list[str] = []
    for t in runs.get(job_id, []):
        if t.startswith("unresolved:"):
            out.append(t.split(":", 1)[1] + " (missing)")
            continue
        if t.startswith("batch_job:"):
            continue
        out.append(rd.label(t).rsplit("/", 1)[-1])
        for t2 in runs.get(t, []):
            if t2.startswith("batch_job:"):
                out.append(rd.label(t2) + " (batch job)")
    return list(dict.fromkeys(out))[:6]


_LAYER_FILLS = ["#dbe9fb", "#ece3fb", "#dcf3dc", "#fde7d9", "#fff1cc", "#d8f1f0", "#fbe1ec"]
_LAYER_STROKES = ["#2a78d6", "#6250d6", "#0ca30c", "#eb6834", "#d69a00", "#13867f", "#e87ba4"]


def layered(architecture: dict[str, Any]) -> Diagram | None:
    """The agent's layers (enrichment ``architecture.layers``) as coloured bands, top down."""
    layers = architecture.get("layers") or []
    if not layers:
        return None
    lines = ["flowchart TB"]
    prev = None
    for i, layer in enumerate(layers):
        lid = f"L{i}"
        lines.append(f"  subgraph {lid}[{_label(layer['name'])}]")
        lines.append("    direction LR")
        comps = layer.get("components") or ["(none)"]
        for k, comp in enumerate(comps):
            lines.append(f"    {lid}c{k}[{_label(comp)}]")
        lines.append("  end")
        fill, stroke = _LAYER_FILLS[i % 7], _LAYER_STROKES[i % 7]
        lines.append(f"  style {lid} fill:{fill},stroke:{stroke},color:#0b0b0b")
        if prev is not None:
            lines.append(f"  {prev} --> {lid}")
        prev = lid
    return Diagram(
        "Layers (interpreted by the Copilot agent)",
        "\n".join(lines),
        "Layers and their components come from enrichment/architecture.json.",
        len(layers),
    )


def current_to_target(rd: RunData, target: dict[str, Any]) -> Diagram | None:
    """Legacy components (scan) on the left, target components (enrichment) on the right, an
    arrow for each 'replaces'."""
    comps = target.get("components") or []
    if not comps:
        return None
    legacy = sorted({r for c in comps for r in c.get("replaces") or []})
    lines = ["flowchart LR", "  subgraph NOW[Today]"]
    ids = _Ids("o")
    for name in legacy:
        lines.append(f"    {ids(name)}[{_label(name)}]")
    lines += ["  end", "  subgraph NEXT[Target on Google Cloud]"]
    tids = _Ids("t")
    for c in comps:
        text = _label(f"{c['name']} · {c['technology']} · {c['platform']}")
        lines.append(f"    {tids(c['name'])}([{text}])")
    lines.append("  end")
    for c in comps:
        for r in c.get("replaces") or []:
            lines.append(f"  {ids(r)} ==> {tids(c['name'])}")
    lines.append("  style NOW fill:#f0efec,stroke:#898781,color:#0b0b0b")
    lines.append("  style NEXT fill:#dcf3dc,stroke:#0ca30c,color:#0b0b0b")
    return Diagram(
        "Today and target (interpreted by the Copilot agent)",
        "\n".join(lines),
        "Target components and what they replace come from enrichment/target_architecture.json.",
        len(legacy) + len(comps),
    )
