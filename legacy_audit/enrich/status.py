"""Enrichment staleness, stamping and carry-over between runs.

Each stage file records in ``meta.built_from`` the hash of every fact file and upstream stage it
was built from (stamped by ``legacy-audit validate``). After a re-scan the new run's hashes are
compared with those: a stage whose inputs changed, or whose upstream stage is stale, is stale.
``enrichment/status.json`` lists the result and ``/audit-enrich stale`` re-runs those stages.

Where ``latest`` is a copy rather than a symlink (Windows without developer mode), the agent writes
into ``latest/enrichment/``; ``sync_from_latest`` brings those files into the run folder first so
nothing written there is lost.
"""

from __future__ import annotations

import hashlib
import json
import logging
import shutil
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from legacy_audit.enrich.stages import BY_NAME, STAGES, Stage, fact_file
from legacy_audit.store.writer import write_json

log = logging.getLogger("legacy_audit")

ENRICHMENT = "enrichment"
STATUS_FILE = "status.json"
_MISSING = "missing"


def enrichment_dir(run_dir: Path) -> Path:
    return run_dir / ENRICHMENT


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _read(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def stage_hash(path: Path) -> str:
    """Hash of a stage file's content without its ``meta`` (so stamping does not change it)."""
    if not path.exists():
        return _MISSING
    try:
        data = _read(path)
    except (OSError, ValueError):
        return _sha(path.read_bytes())
    if isinstance(data, dict):
        data.pop("meta", None)
    return _sha(json.dumps(data, sort_keys=True, ensure_ascii=False).encode("utf-8"))


def current_hashes(run_dir: Path, stage: Stage) -> dict[str, str]:
    out: dict[str, str] = {}
    for kind in stage.inputs:
        p = run_dir / "data" / fact_file(kind)
        out[kind] = _sha(p.read_bytes()) if p.exists() else _MISSING
    for up in stage.after:
        out[f"stage:{up}"] = stage_hash(enrichment_dir(run_dir) / BY_NAME[up].file)
    return out


def stamp(run_dir: Path, stage: Stage, *, force: bool = False) -> bool:
    """Record what the stage was built from, when it is not recorded yet (or ``force``)."""
    path = enrichment_dir(run_dir) / stage.file
    data = _read(path)
    meta = data.setdefault("meta", {"stage": stage.name})
    if meta.get("built_from") and not force:
        return False
    meta["built_from"] = {
        "run_id": run_dir.name,
        "facts_hash_by_input": current_hashes(run_dir, stage),
    }
    meta.setdefault("generated_at", datetime.now(UTC).isoformat(timespec="seconds"))
    write_json(path, data)
    return True


def compute_status(run_dir: Path) -> dict[str, Any]:
    """Present / stale per stage; written to ``enrichment/status.json``."""
    edir = enrichment_dir(run_dir)
    stages: dict[str, dict[str, Any]] = {}
    for st in STAGES:
        path = edir / st.file
        entry: dict[str, Any] = {"number": st.number, "file": st.file, "present": path.exists()}
        if path.exists():
            try:
                built = (_read(path).get("meta") or {}).get("built_from")
            except (OSError, ValueError, AttributeError):
                built = None
            if not built:
                entry.update(stale=False, validated=False, changed=[])
            else:
                now = current_hashes(run_dir, st)
                was = built.get("facts_hash_by_input") or {}
                changed = sorted(k for k in now if was.get(k) != now[k])
                upstream = [u for u in st.after if stages.get(u, {}).get("stale")]
                entry.update(
                    validated=True,
                    built_from_run=built.get("run_id"),
                    changed=changed,
                    stale_upstream=upstream,
                    stale=bool(changed or upstream),
                )
        stages[st.name] = entry
    run = _read(run_dir / "run.json") if (run_dir / "run.json").exists() else {}
    return {
        "run_id": run_dir.name,
        "stages": stages,
        "stale": [n for n, e in stages.items() if e.get("stale")],
        "missing": [n for n, e in stages.items() if not e["present"]],
        "overrides": run.get("overrides") or {},
    }


def write_status(run_dir: Path) -> dict[str, Any]:
    status = compute_status(run_dir)
    edir = enrichment_dir(run_dir)
    if edir.exists():
        write_json(edir / STATUS_FILE, status)
    return status


def latest_copy(run_dir: Path) -> Path | None:
    """``<output>/latest`` when it is a plain copy of this run, else None."""
    if run_dir.parent.name != "runs":
        return None
    latest = run_dir.parent.parent / "latest"
    if latest.is_symlink() or not latest.is_dir():
        return None
    try:
        run_id = _read(latest / "run.json").get("run_id")
    except (OSError, ValueError):
        return None
    return latest if run_id == run_dir.name else None


def sync_from_latest(run_dir: Path) -> None:
    """Bring files the agent wrote into a copied ``latest/enrichment`` into the run folder."""
    latest = latest_copy(run_dir)
    if latest is not None and (latest / ENRICHMENT).is_dir():
        shutil.copytree(latest / ENRICHMENT, enrichment_dir(run_dir), dirs_exist_ok=True)


def sync_to_latest(run_dir: Path) -> None:
    """After stamping, put the run's enrichment back into a copied ``latest``."""
    latest = latest_copy(run_dir)
    if latest is not None and enrichment_dir(run_dir).is_dir():
        shutil.copytree(enrichment_dir(run_dir), latest / ENRICHMENT, dirs_exist_ok=True)


def carry_over(previous_run: Path | None, run_dir: Path) -> dict[str, Any] | None:
    """Copy the previous run's enrichment into a new run and mark what is now stale."""
    if previous_run is None or not previous_run.is_dir():
        return None
    sync_from_latest(previous_run)
    src = enrichment_dir(previous_run)
    if not src.is_dir() or not any(src.glob("*.json")):
        return None
    shutil.copytree(
        src, enrichment_dir(run_dir), ignore=shutil.ignore_patterns(STATUS_FILE), dirs_exist_ok=True
    )
    status = write_status(run_dir)
    present = [n for n, e in status["stages"].items() if e["present"]]
    log.info(
        "Enrichment carried from %s: %d stage file(s); stale: %s",
        previous_run.name,
        len(present),
        ", ".join(status["stale"]) or "none",
    )
    return status
