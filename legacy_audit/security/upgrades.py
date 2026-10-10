"""Upgrade recommendations per library (data in ``rules/dependency-upgrades.yaml``)."""

from __future__ import annotations

import re
from typing import Any

from legacy_audit.model import Library, Recommendation
from legacy_audit.security.versions import compare, sort_versions


def _full(regex: str | None, value: str | None) -> bool:
    """True when ``regex`` is unset or ``value`` is unknown or fully matches it."""
    return not regex or not value or re.fullmatch(regex, value, re.I) is not None


def _find(
    entries: list[dict[str, Any]], lib: Library, need_group: bool = False
) -> dict[str, Any] | None:
    """First entry matching the library; ``need_group``: the library's group must be known."""
    if need_group and not lib.group:
        return None
    for e in entries:
        if re.fullmatch(str(e["artifact"]), lib.artifact, re.I) and _full(
            e.get("group"), lib.group
        ):
            return e
    return None


def _fixed_version(lib: Library) -> tuple[str | None, bool]:
    """Lowest version above the current one that every vulnerability lists as fixed.

    Returns (version, verify). ``verify`` is True when some vulnerability names no fix, or
    the fixes are on different lines and the highest one is taken."""
    per_vuln: list[list[str]] = []
    unknown = False
    for v in lib.vulnerabilities:
        fixes = [f for f in v.fixed_versions if not lib.version or compare(f, lib.version) > 0]
        if fixes:
            per_vuln.append(sort_versions(fixes))
        else:
            unknown = True
    if not per_vuln:
        return None, True
    # The lowest fix of each vulnerability; the highest of those fixes all of them on one line.
    lowest = sort_versions([fixes[0] for fixes in per_vuln])
    return lowest[-1], unknown


def recommend(lib: Library, data: dict[str, Any]) -> Recommendation:
    recipes: dict[str, str] = data.get("recipes", {})
    targets: dict[str, str] = data.get("targets", {})
    if lib.eol:
        front = _find(data.get("frontend", []), lib)
        if front is not None:
            return Recommendation(
                action=front["action"],
                target=front.get("target"),
                reason=front["reason"],
                effort=front.get("effort", "high"),
                rule="frontend",
            )
        boot = _find(data.get("boot_managed", []), lib, need_group=True)
        if boot is not None:
            return Recommendation(
                action="use-bom",
                target=f"{lib.replacement or 'current version'}, managed by Spring Boot "
                + str(targets.get("spring_boot", "4")),
                reason=f"end of life since {lib.eol_date}; Spring Boot manages the new version"
                if lib.eol_date
                else "end of life; Spring Boot manages the new version",
                effort="medium",
                recipe=recipes.get("spring_boot"),
                rule="eol-boot-managed",
            )
        eol_recipe = _find(data.get("eol_recipes", []), lib)
        recipe_key = str(eol_recipe["recipe"]) if eol_recipe else ""
        return Recommendation(
            action="replace",
            target=lib.replacement,
            reason=f"end of life since {lib.eol_date}" if lib.eol_date else "end of life",
            effort="medium",
            recipe=recipes.get(recipe_key),
            rule="eol",
        )
    front = _find(data.get("frontend", []), lib)
    if front is not None and lib.language != "java":
        return Recommendation(
            action=front["action"],
            target=front.get("target"),
            reason=front["reason"],
            effort=front.get("effort", "medium"),
            rule="frontend",
        )
    jak = _find(data.get("jakarta", []), lib)
    if jak is not None:
        return Recommendation(
            action="migrate-jakarta",
            target=jak["target"],
            reason="javax.* API: Spring Boot 4 runs on Jakarta EE "
            + str(targets.get("jakarta_ee", "11")),
            effort="low",
            recipe=recipes.get("jakarta"),
            rule="jakarta",
        )
    rep = _find(data.get("replace", []), lib)
    if rep is not None:
        return Recommendation(
            action="replace",
            target=rep["target"],
            reason=rep["reason"],
            effort=rep.get("effort", "medium"),
            rule="replace",
        )
    boot = _find(data.get("boot_managed", []), lib, need_group=True)
    if boot is not None:
        return Recommendation(
            action="use-bom",
            target=boot.get("target")
            or f"version managed by Spring Boot {targets.get('spring_boot', '4')}",
            reason="Spring Boot manages this dependency; drop the explicit version",
            effort="low",
            recipe=recipes.get("spring_boot"),
            verify=bool(boot.get("verify", False)),
            rule="boot-managed",
        )
    if lib.vulnerabilities:
        version, verify = _fixed_version(lib)
        return Recommendation(
            action="upgrade",
            target=f"{lib.group}:{lib.artifact}:{version}" if version and lib.group else version,
            reason=f"fixes {len(lib.vulnerabilities)} known vulnerabilities"
            if version
            else "known vulnerabilities; no fixed version in the reports",
            effort="low",
            verify=verify,
            rule="vulnerable",
        )
    return Recommendation(action="keep", reason="no rule applies", effort="low", rule="keep")


def apply_upgrades(libraries: list[Library], data: dict[str, Any]) -> None:
    for lib in libraries:
        lib.recommendation = rec = recommend(lib, data)
        if rec.action == "upgrade" and rec.target:
            lib.recommended_version = rec.target.rsplit(":", 1)[-1]
