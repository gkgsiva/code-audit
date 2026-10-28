"""The migration target chosen in ``audit.yaml > target.profile`` (decision 161).

- ``spring-boot-angular`` (default): Java 25, Spring Boot 4, Angular 21, PostgreSQL on GCP.
- ``wildfly-gke``: the application is lifted as it is onto WildFly (Jakarta EE 11, Java 25) in
  containers on GKE; only the database moves, to PostgreSQL.
- ``none``: audit only. Nothing is suggested: no target mapping, upgrade advice beyond end of
  life and vulnerabilities, PostgreSQL grades, effort, or target and plan stages.

The scan facts and counts do not depend on the target. What does: the rule tables (each pack's
``data`` may hold a ``profiles: {<profile>: {key: value}}`` block replacing top-level keys, and
any entry may hold ``by_target: {<profile>[,<profile>...]: {field: value} | skip}``), the
``[[phrase]]`` placeholders in finding texts, the AI stages that run and the report wording.
The scan writes the choice to ``data/target.json``; the reports and the AI read it from there.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from legacy_audit.config import Target

DEFAULT = "spring-boot-angular"
WILDFLY = "wildfly-gke"
NONE = "none"
PROFILES = (DEFAULT, WILDFLY, NONE)
# Stages that describe the move to a target: not written when there is none.
TARGET_STAGES = ("service_mapping", "data_migration", "dependency_plan", "target_architecture",
                 "plan")  # fmt: skip
FILE = "target.json"
_PHRASE = re.compile(r"\[\[([a-z0-9-]+)\]\]")


def label(t: Target) -> str:
    if t.profile == NONE:
        return "Audit only (no target)"
    if t.profile == WILDFLY:
        return (
            f"WildFly {t.wildfly} (Jakarta EE {t.jakarta_ee}, Java {t.java}) on GKE, "
            f"database on {_db(t)}"
        )
    return f"Java {t.java}, Spring Boot {t.spring_boot}, Angular {t.angular}, {_db(t)} on GCP"


def _db(t: Target) -> str:
    return "PostgreSQL" if t.database.lower() == "postgresql" else t.database


def info(t: Target) -> dict[str, Any]:
    """What ``data/target.json`` holds."""
    versions = {"java": t.java, "database": t.database, "cloud": t.cloud}
    if t.profile == DEFAULT:
        versions |= {"spring_boot": t.spring_boot, "angular": t.angular}
    elif t.profile == WILDFLY:
        versions |= {"wildfly": t.wildfly, "jakarta_ee": t.jakarta_ee, "platform": "GKE"}
    return {
        "profile": t.profile,
        "label": label(t),
        "audit_only": t.profile == NONE,
        "versions": {} if t.profile == NONE else versions,
        "skipped_stages": list(TARGET_STAGES) if t.profile == NONE else [],
    }


def read(run_dir: Path) -> dict[str, Any]:
    """``data/target.json`` of a run; a run scanned before targets existed had the default."""
    try:
        data = json.loads((run_dir / "data" / FILE).read_text(encoding="utf-8"))
        if isinstance(data, dict) and data.get("profile") in PROFILES:
            return data
    except (OSError, ValueError):
        pass
    return info(Target())


def profile_of(run_dir: Path) -> str:
    return str(read(run_dir)["profile"])


def audit_only(run_dir: Path) -> bool:
    return profile_of(run_dir) == NONE


def _applies(key: str, profile: str) -> bool:
    return profile in {k.strip() for k in key.split(",")}


_SKIP = object()


def _resolve(node: Any, profile: str) -> Any:
    if isinstance(node, dict):
        node = dict(node)
        by = node.pop("by_target", None)
        if isinstance(by, dict):
            for key, change in by.items():
                if _applies(str(key), profile):
                    if change == "skip":
                        return _SKIP
                    node.update(change or {})
        return {k: v for k, v in ((k, _resolve(v, profile)) for k, v in node.items())
                if v is not _SKIP}  # fmt: skip
    if isinstance(node, list):
        return [v for v in (_resolve(x, profile) for x in node) if v is not _SKIP]
    return node


def resolve(data: dict[str, Any], profile: str) -> dict[str, Any]:
    """A pack's ``data`` for one target: its ``profiles`` block applied, then ``by_target``."""
    data = dict(data)
    profiles = data.pop("profiles", None) or {}
    for key, change in profiles.items():
        if _applies(str(key), profile):
            data.update(change or {})
    out = _resolve(data, profile)
    return out if isinstance(out, dict) else {}


def fill(text: str | None, phrases: dict[str, str]) -> str | None:
    """Replace ``[[name]]`` placeholders (written by analyzers and rules) with the target's
    wording; an unknown name becomes empty. Doubled spaces left by an empty phrase go."""
    if not text or "[[" not in text:
        return text
    out = _PHRASE.sub(lambda m: phrases.get(m.group(1), ""), text)
    return re.sub(r" {2,}", " ", out).replace(" .", ".").replace(" ;", ";").strip()
