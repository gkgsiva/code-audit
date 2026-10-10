"""CycloneDX 1.5 SBOM (``data/sbom.cdx.json``) from the library inventory and vulnerabilities."""

from __future__ import annotations

import re
import uuid
from typing import Any

from legacy_audit import __version__
from legacy_audit.model import Library
from legacy_audit.security.vulns import purl

_SHA1 = re.compile(r"^[0-9a-f]{40}$")
_NS = uuid.UUID("8c3a6f0e-6f1e-4c1b-9a43-1b7f2d0a5e11")


def _scope(lib: Library) -> str:
    s = (lib.scope or "").lower()
    if s == "test":
        return "excluded"
    if s in ("provided", "optional"):
        return "optional"
    return "required"


def _component(lib: Library) -> dict[str, Any]:
    comp: dict[str, Any] = {"type": "library", "bom-ref": lib.id, "name": lib.artifact}
    if lib.group:
        comp["group"] = lib.group
    if lib.version:
        comp["version"] = lib.version
    comp["scope"] = _scope(lib)
    p = purl(lib)
    if p:
        comp["purl"] = p
    if lib.sha1 and _SHA1.match(lib.sha1.lower()):
        comp["hashes"] = [{"alg": "SHA-1", "content": lib.sha1.lower()}]
    if lib.license:
        comp["licenses"] = [{"license": {"name": lib.license}}]
    props = [
        {"name": "legacy-audit:source", "value": lib.source},
        {"name": "legacy-audit:declared", "value": str(lib.declared).lower()},
    ]
    if lib.eol:
        props.append({"name": "legacy-audit:eol", "value": lib.eol_date or "true"})
    if lib.recommendation:
        props.append({"name": "legacy-audit:recommendation", "value": lib.recommendation.action})
    comp["properties"] = props
    locations = sorted({e.file for e in lib.evidence})
    if locations:
        comp["evidence"] = {"occurrences": [{"location": loc} for loc in locations[:20]]}
    return comp


def _method(score: float | None) -> str:
    return "CVSSv31" if score is not None else "other"


def build_sbom(
    application: str, run_id: str, timestamp: str, libraries: list[Library]
) -> dict[str, Any]:
    app_ref = f"application:{application}"
    vulns: dict[str, dict[str, Any]] = {}
    for lib in libraries:
        for v in lib.vulnerabilities:
            entry = vulns.get(v.id)
            if entry is None:
                rating: dict[str, Any] = {"severity": v.severity, "method": _method(v.cvss)}
                if v.cvss is not None:
                    rating["score"] = v.cvss
                entry = vulns[v.id] = {
                    "bom-ref": f"vulnerability:{v.id}",
                    "id": v.id,
                    "source": {"name": "NVD" if v.id.startswith("CVE-") else "OSV"},
                    "ratings": [rating],
                    "affects": [],
                    "properties": [
                        {"name": "legacy-audit:reported-by", "value": ",".join(v.sources)}
                    ],
                }
                if v.description:
                    entry["description"] = v.description
                if v.aliases:
                    entry["references"] = [{"id": a, "source": {"name": "OSV"}} for a in v.aliases]
                if v.fixed_versions:
                    entry["recommendation"] = "Fixed in " + ", ".join(v.fixed_versions)
            entry["affects"].append({"ref": lib.id})
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": f"urn:uuid:{uuid.uuid5(_NS, f'{application}/{run_id}')}",
        "version": 1,
        "metadata": {
            "timestamp": timestamp,
            "tools": {
                "components": [
                    {"type": "application", "name": "legacy-audit", "version": __version__}
                ]
            },
            "component": {"type": "application", "bom-ref": app_ref, "name": application},
        },
        "components": [_component(lib) for lib in libraries],
        "dependencies": [
            {"ref": app_ref, "dependsOn": [lib.id for lib in libraries]},
        ],
        "vulnerabilities": [vulns[k] for k in sorted(vulns)],
    }
