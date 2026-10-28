"""Graphics shared by the technical report and the functional document.

Chart specs are drawn by ``assets/report.js`` with the vendored Chart.js; gauges, heat maps,
the risk matrix, the batch week calendar and Gantt bars are plain HTML and CSS (no script), so
they print and work without JavaScript. Every value shown comes from the facts or the validated
enrichment passed in; nothing here counts anything new.

Colours are CSS tokens (``series-1`` ... ``series-8`` for identity, ``seq-*`` for magnitude,
``good`` / ``warning`` / ``serious`` / ``critical`` for status), so light and dark themes both
work; the PPTX uses the light values of the same tokens (``deck_builder.PALETTE``).
"""

from __future__ import annotations

from typing import Any

from markupsafe import Markup

# Small stroke icons (24x24, currentColor), inline so the report stays offline.
_ICON_PATHS = {
    "files": "M6 2h9l5 5v15H6z M14 2v6h6",
    "code": "M8 7l-5 5 5 5 M16 7l5 5-5 5 M14 4l-4 16",
    "components": "M3 3h8v8H3z M13 3h8v8h-8z M3 13h8v8H3z M13 13h8v8h-8z",
    "services": "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8 M12 2v3 M12 19v3 M2 12h3 M19 12h3 "
    "M5 5l2 2 M17 17l2 2 M5 19l2-2 M17 7l2-2",
    "screens": "M3 4h18v12H3z M8 20h8 M12 16v4",
    "sql": "M4 6c0-2 16-2 16 0v12c0 2-16 2-16 0z M4 6c0 2 16 2 16 0 M4 12c0 2 16 2 16 0",
    "warning": "M12 3l10 18H2z M12 10v5 M12 18v.5",
    "procedure": "M4 4h16v16H4z M8 9h8 M8 13h8 M8 17h5",
    "integrations": "M7 7h10 M14 4l3 3-3 3 M17 17H7 M10 14l-3 3 3 3",
    "batch": "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18 M12 7v5l3 3",
    "libraries": "M4 4h5v16H4z M10 4h5v16h-5z M16 5l4 1-3 14-4-1z",
    "shield": "M12 2l8 3v6c0 5-3.5 9-8 11-4.5-2-8-6-8-11V5z",
    "eol": "M6 2h12 M6 22h12 M7 2c0 6 10 6 10 10S7 16 7 22 M17 2c0 6-10 6-10 10s10 4 10 10",
    "findings": "M5 21V4 M5 4h12l-2 4 2 4H5",
    "effort": "M3 5h18v16H3z M3 10h18 M8 3v4 M16 3v4",
    "users": "M9 11a4 4 0 1 0 0-8a4 4 0 1 0 0 8 M2 21c0-4 3-7 7-7s7 3 7 7 M17 4a4 4 0 0 1 0 7 "
    "M19 14c2 1 3 3 3 7",
    "rules": "M9 11l3 3 8-8 M20 12v7H4V5h11",
    "data": "M4 6c0-2 16-2 16 0v12c0 2-16 2-16 0z M4 6c0 2 16 2 16 0",
    "flow": "M3 5h6v4H3z M15 15h6v4h-6z M9 7h3v10h3",
    "calendar": "M3 5h18v16H3z M3 10h18 M8 3v4 M16 3v4 M7 14h2 M11 14h2 M15 14h2",
    "interfaces": "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20 M2 12h20 M12 2c3 3 3 17 0 20 "
    "M12 2c-3 3-3 17 0 20",
    "glossary": "M4 4h12a4 4 0 0 1 4 4v12H8a4 4 0 0 1-4-4z M8 9h8 M8 13h6",
    "capability": "M3 12l9-9 9 9-9 9z",
}


def icon(name: str) -> Markup:
    d = _ICON_PATHS.get(name, _ICON_PATHS["files"])
    return Markup(
        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" '
        f'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="{d}"/></svg>'
    )


def chart(
    chart_id: str,
    title: str,
    kind: str,
    labels: list[str],
    values: list[Any] | None = None,
    *,
    colors: list[str] | str = "series-1",
    datasets: list[dict[str, Any]] | None = None,
    label: str = "Count",
    category: str = "",
    unit: str = "",
    horizontal: bool = False,
    note: str = "",
    tall: bool = False,
    **extra: Any,
) -> dict[str, Any]:
    """A chart for the ``chart`` macro: ``kind`` is bar, stacked, doughnut, radar or bubble.
    ``values`` (one series) or ``datasets`` ([{label, values, color}]) also fill the
    "Show as table" view, so no chart is colour-only."""
    values = list(values or [])
    cols = [colors] * len(labels) if isinstance(colors, str) else list(colors)
    spec: dict[str, Any] = {
        "type": kind,
        "labels": labels,
        "values": values,
        "colors": cols,
        "label": label,
        "unit": unit,
        "horizontal": horizontal,
        **extra,
    }
    if datasets is not None:
        spec["datasets"] = datasets
    has_data = (
        bool(values) or any(d.get("values") for d in datasets or []) or bool(extra.get("points"))
    )
    return {
        "id": chart_id,
        "title": title,
        "labels": labels,
        "values": values,
        "datasets": datasets or [],
        "label": label,
        "category": category or title,
        "spec": spec,
        "note": note,
        "has_data": has_data,
        "tall": tall or len(labels) > 8,
    }


def gauge(
    label: str, part: float, whole: float, color: str = "good", sub: str = ""
) -> dict[str, Any]:
    """A ring showing ``part`` of ``whole`` as a percentage (both from the facts)."""
    pct = round(100.0 * part / whole) if whole else 0
    return {"label": label, "pct": pct, "part": part, "whole": whole, "color": color, "sub": sub}


def heat_level(n: int, top: int) -> int:
    """1..6 shade for a positive count relative to the largest one (0 = empty)."""
    if n <= 0 or top <= 0:
        return 0
    return max(1, min(6, round(6 * n / top)))


RISK_SEVERITIES = ["critical", "high", "medium", "low"]
RISK_LIKELIHOOD = ["low", "medium", "high"]


def risk_matrix(risks: list[dict[str, Any]]) -> dict[str, Any]:
    """Severity (rows) x likelihood (columns) with the risk ids in each cell."""
    cells: dict[tuple[str, str], list[str]] = {}
    for r in risks:
        cells.setdefault((r.get("severity", ""), r.get("likelihood", "")), []).append(r["id"])
    rows = []
    for i, sev in enumerate(RISK_SEVERITIES):
        row = []
        for j, lik in enumerate(RISK_LIKELIHOOD):
            score = (3 - i) + j  # 0 (low/low) .. 5 (critical/high)
            ids = cells.get((sev, lik), [])
            level = 0 if score <= 1 else 1 if score == 2 else 2 if score == 3 else 3
            row.append({"ids": ids, "level": level})
        rows.append({"severity": sev, "cells": row})
    return {"rows": rows, "columns": RISK_LIKELIHOOD, "total": len(risks)}


def gantt(waves: list[dict[str, Any]]) -> dict[str, Any]:
    """Plan waves as bars on a month axis (start and duration from the plan)."""
    end = max((w["start_month"] + w["duration_months"] for w in waves), default=0)
    span = max(end, 1)
    bars = []
    for k, w in enumerate(sorted(waves, key=lambda w: w["order"])):
        bars.append(
            {
                "name": w["name"],
                "goal": w.get("goal", ""),
                "left": round(100 * w["start_month"] / span, 2),
                "width": round(100 * w["duration_months"] / span, 2),
                "start": w["start_month"],
                "end": w["start_month"] + w["duration_months"],
                "color": f"series-{k % 8 + 1}",
            }
        )
    step = 1 if span <= 12 else 3 if span <= 36 else 6
    ticks = [{"m": m, "left": round(100 * m / span, 2)} for m in range(0, span + 1, step)]
    return {"bars": bars, "ticks": ticks, "months": end}


COMPLEXITY_BANDS = ["Simple", "Medium", "Complex", "Very complex"]
BAND_COLOR = {
    "Simple": "good",
    "Medium": "warning",
    "Complex": "serious",
    "Very complex": "critical",
}
