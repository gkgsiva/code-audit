"""Version comparison close to Maven's ordering, and simple range expressions.

``1.2.17 < 1.2.18``, ``2.0-beta1 < 2.0-rc1 < 2.0 = 2.0.0 = 2.0.Final < 2.0-sp1 < 2.0.1``.
Ranges are comma-separated bounds that must all hold: ``"<2"``, ``">=3.0,<3.5"``,
``"<=5.3.39"``; ``"*"`` matches any version (also an unknown one).
"""

from __future__ import annotations

import re
from functools import cmp_to_key

_PRE = {"alpha": -5, "a": -5, "beta": -4, "b": -4, "milestone": -3, "m": -3, "ea": -3,
        "rc": -2, "cr": -2, "pre": -2, "snapshot": -1}  # fmt: skip
_RELEASE = {"", "ga", "final", "release", "r"}
_PART = re.compile(r"\d+|[a-z]+")


def _parts(version: str) -> list[int | str]:
    out: list[int | str] = []
    for p in _PART.findall(version.lower()):
        out.append(int(p) if p.isdigit() else p)
    while out and (out[-1] == 0 or out[-1] in _RELEASE):
        out.pop()
    return out


def _rank(p: int | str) -> tuple[int, int, str]:
    if isinstance(p, int):
        return (1, p, "")
    if p in _PRE:
        return (-1, _PRE[p], "")
    if p in _RELEASE:
        return (0, 0, "")
    return (0, 1, p)  # sp, other qualifiers: just above the release


def compare(a: str, b: str) -> int:
    pa, pb = _parts(a), _parts(b)
    for i in range(max(len(pa), len(pb))):
        # A missing part is 0 next to a number and "release" next to a qualifier.
        other = pb[i] if i >= len(pa) else pa[i]
        pad = (1, 0, "") if isinstance(other, int) else (0, 0, "")
        ra = _rank(pa[i]) if i < len(pa) else pad
        rb = _rank(pb[i]) if i < len(pb) else pad
        if ra != rb:
            return -1 if ra < rb else 1
    return 0


def sort_versions(versions: list[str]) -> list[str]:
    return sorted(versions, key=cmp_to_key(compare))


def in_range(version: str | None, spec: str | None) -> bool | None:
    """True/False, or None when the version is unknown and the range is not ``*``."""
    if spec is None or spec.strip() in ("", "*", "any"):
        return True
    if not version:
        return None
    for bound in spec.split(","):
        bound = bound.strip()
        m = re.match(r"(<=|>=|<|>|==|=)?\s*(.+)", bound)
        if not m:
            continue
        op, ref = m.group(1) or "=", m.group(2)
        c = compare(version, ref)
        ok = {"<": c < 0, "<=": c <= 0, ">": c > 0, ">=": c >= 0, "=": c == 0, "==": c == 0}[op]
        if not ok:
            return False
    return True
