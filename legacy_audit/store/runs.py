"""Run folders: ``<output>/runs/<run-id>/`` plus a ``latest`` pointer.

``latest`` is a symlink to the newest run where the OS allows it, otherwise a copy of the run
folder (Windows without developer mode). ``runs/LATEST`` always holds the newest run id, and is
what the tool itself reads.
"""

from __future__ import annotations

import hashlib
import os
import shutil
from datetime import datetime
from pathlib import Path

LATEST_FILE = "LATEST"
HISTORY_FILE = "HISTORY"  # run ids in completion order (ids only sort to the second)


class RunNotFound(Exception):
    pass


def new_run_id(seed: str, now: datetime, runs_dir: Path) -> str:
    stamp = now.strftime("%Y%m%d-%H%M%S")
    n = 0
    while True:
        short = hashlib.sha256(f"{seed}|{now.isoformat()}|{n}".encode()).hexdigest()[:6]
        run_id = f"{stamp}-{short}"
        if not (runs_dir / run_id).exists():
            return run_id
        n += 1


def runs_dir(output_dir: Path) -> Path:
    return output_dir / "runs"


def list_runs(output_dir: Path) -> list[str]:
    """Completed runs, oldest first."""
    d = runs_dir(output_dir)
    if not d.is_dir():
        return []
    present = {p.name for p in d.iterdir() if p.is_dir() and (p / "run.json").exists()}
    history_file = d / HISTORY_FILE
    history = history_file.read_text(encoding="utf-8").split() if history_file.exists() else []
    ordered = [r for r in dict.fromkeys(history) if r in present]
    return sorted(present - set(ordered)) + ordered


def set_latest(output_dir: Path, run_id: str) -> None:
    rd = runs_dir(output_dir)
    (rd / LATEST_FILE).write_text(run_id + "\n", encoding="utf-8")
    with (rd / HISTORY_FILE).open("a", encoding="utf-8") as fh:
        fh.write(run_id + "\n")
    link = output_dir / "latest"
    if link.is_symlink() or link.is_file():
        link.unlink()
    elif link.is_dir():
        shutil.rmtree(link)
    try:
        os.symlink(Path("runs") / run_id, link, target_is_directory=True)
    except OSError:
        shutil.copytree(rd / run_id, link)


def resolve_run(output_dir: Path, ref: str) -> Path:
    """Find a run folder from an id, 'latest', 'previous' or a path."""
    p = Path(ref)
    if p.is_dir() and (p / "run.json").exists():
        return p
    runs = list_runs(output_dir)
    if ref == "latest":
        latest = runs_dir(output_dir) / LATEST_FILE
        run_id = latest.read_text(encoding="utf-8").strip() if latest.exists() else ""
        if not run_id and runs:
            run_id = runs[-1]
        if run_id:
            return runs_dir(output_dir) / run_id
    elif ref == "previous":
        if len(runs) >= 2:
            return runs_dir(output_dir) / runs[-2]
    else:
        matches = [r for r in runs if r == ref or r.startswith(ref)]
        if len(matches) == 1:
            return runs_dir(output_dir) / matches[0]
        if len(matches) > 1:
            raise RunNotFound(f"Run id '{ref}' is ambiguous: {', '.join(matches)}")
    raise RunNotFound(f"No run '{ref}' in {runs_dir(output_dir)}")
