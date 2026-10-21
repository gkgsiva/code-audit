"""Application servers, web servers and runtimes, one row per product, for the reports.

The rows come from the tech-stack items the scan found (categories ``app-server``,
``web-server``, and the Java and Node.js runtimes, decision 131): every version seen, end of
life, the components whose files name the product, the files themselves and the default target from
``rules/target-mapping.yaml``. Nothing is counted here; it only groups the scan's items.
"""

from __future__ import annotations

from collections import defaultdict
from typing import Any

from legacy_audit.report.data import RunData

KINDS = {"app-server": "Application server", "web-server": "Web server", "runtime": "Runtime"}
ORDER = list(KINDS)
_TARGET_ID = {"app-server": "app-server", "web-server": "web-server"}
_RUNTIME_TARGET = {
    "Node.js": "Node.js 24 LTS on Cloud Run, or the service rewritten in Spring Boot 4 (Java 25)",
    "Java": "Java 25 (Spring Boot 4)",
}
MAX_FILES = 5


def _version_key(v: str) -> tuple[int, ...]:
    out = []
    for part in v.replace("-", ".").split("."):
        out.append(int(part) if part.isdigit() else 0)
    return tuple(out)


def server_rows(rd: RunData) -> list[dict[str, Any]]:
    """One row per server or runtime product, application servers first."""
    targets = {m.get("id"): m.get("target", "") for m in rd.target_mapping}
    groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for t in rd.items("tech_stack"):
        cat = t.get("category") or ""
        if cat in KINDS and (cat != "runtime" or t["name"] in _RUNTIME_TARGET):
            groups[(cat, t["name"])].append(t)
    rows = []
    for (cat, name), items in groups.items():
        versions = sorted({str(t["version"]) for t in items if t.get("version")}, key=_version_key)
        files: list[str] = []
        lines: dict[str, int | None] = {}
        for t in items:
            for ev in t.get("evidence") or []:
                f = ev.get("file")
                if f and f not in lines:
                    files.append(f)
                    lines[f] = ev.get("line")
        comps = sorted({c for f in files if (c := rd.file_component.get(f))})
        eol_items = [t for t in items if t.get("eol")]
        eol = ""
        if eol_items:
            first = eol_items[0]
            if len(versions) > 1:
                old = sorted({str(t.get("version") or "") for t in eol_items}, key=_version_key)
                eol = "yes: " + ", ".join(old)
            else:
                eol = "yes" + (f" (since {first['eol_date']})" if first.get("eol_date") else "")
        elif any(t.get("eol") is False for t in items):
            eol = "no"
        replacement = next((t["replacement"] for t in eol_items if t.get("replacement")), "")
        rows.append(
            {
                "name": name,
                "category": cat,
                "kind": KINDS[cat],
                "versions": versions,
                "version": ", ".join(versions),
                "eol": eol,
                "replacement": replacement,
                "components": comps,
                "files": files[:MAX_FILES],
                "more_files": max(0, len(files) - MAX_FILES),
                "lines": lines,
                "usages": sum(int(t.get("usages") or 1) for t in items),
                "target": targets.get(_TARGET_ID.get(cat, ""), "") or _RUNTIME_TARGET.get(name, ""),
            }
        )
    rows.sort(key=lambda r: (ORDER.index(str(r["category"])), str(r["name"]).lower()))
    return rows
