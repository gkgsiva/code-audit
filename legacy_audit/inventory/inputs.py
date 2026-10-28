"""Supplementary inputs coverage (``audit-inputs/<kind>/``). Missing is a gap, not an error."""

from __future__ import annotations

from pathlib import Path

from legacy_audit.config import INPUT_KINDS
from legacy_audit.model import CoverageStatus, Evidence, InputCoverage, make_id


def _files(folder: Path) -> list[Path]:
    if not folder.is_dir():
        return []
    return sorted(
        p
        for p in folder.rglob("*")
        if p.is_file() and not any(part.startswith(".") for part in p.relative_to(folder).parts)
    )


def input_coverage(inputs_dir: Path, display_root: str = "audit-inputs") -> list[InputCoverage]:
    out = []
    for kind, label in INPUT_KINDS.items():
        files = _files(inputs_dir / kind)
        rel = f"{display_root}/{kind}"
        out.append(
            InputCoverage(
                id=make_id("input_coverage", kind),
                kind=kind,
                label=label,
                status=CoverageStatus.PRESENT if files else CoverageStatus.MISSING,
                path=rel,
                file_count=len(files),
                evidence=[
                    Evidence(file=f"{rel}/{p.relative_to(inputs_dir / kind).as_posix()}")
                    for p in files[:20]
                ],
            )
        )
    return out


def coverage_line(c: InputCoverage) -> str:
    if c.status == CoverageStatus.PRESENT:
        return f"{c.label}: {c.file_count} file(s)"
    return f"{c.label}: not provided"
