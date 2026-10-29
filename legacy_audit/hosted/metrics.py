"""Numbers for the admin dashboard: usage from the activity log, and the server's memory, CPU
and disk, sampled every few seconds so the page can draw the last hour.

Standard library only. Inside a container the memory limit and CPU use come from the cgroup
files (v2, then v1); elsewhere from ``/proc``; on Windows only what Python itself can tell.
"""

from __future__ import annotations

import contextlib
import os
import platform
import shutil
import sys
import threading
import time
from collections import Counter, defaultdict, deque
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any

SAMPLE_SECONDS = 10
SAMPLES = 360  # one hour
NO_LIMIT = 1 << 60  # cgroup v1 writes a huge number when there is no limit


def _read(path: str) -> str | None:
    try:
        return Path(path).read_text(encoding="utf-8").strip()
    except OSError:
        return None


def _int(text: str | None) -> int | None:
    try:
        return int(text) if text is not None else None
    except ValueError:
        return None


def _meminfo() -> dict[str, int]:
    out: dict[str, int] = {}
    for line in (_read("/proc/meminfo") or "").splitlines():
        name, _, rest = line.partition(":")
        parts = rest.split()
        if parts and parts[0].isdigit():
            out[name] = int(parts[0]) * 1024
    return out


def memory() -> dict[str, Any]:
    """Memory used and the limit, in bytes: the container's when there is one, else the
    machine's. ``process`` is this server's own resident memory."""
    used = _int(_read("/sys/fs/cgroup/memory.current"))
    limit_text = _read("/sys/fs/cgroup/memory.max")
    source = "container"
    if used is None:
        used = _int(_read("/sys/fs/cgroup/memory/memory.usage_in_bytes"))
        limit_text = _read("/sys/fs/cgroup/memory/memory.limit_in_bytes")
    limit = _int(limit_text) if limit_text and limit_text != "max" else None
    info = _meminfo()
    total = info.get("MemTotal")
    if limit is not None and (limit >= NO_LIMIT or (total and limit >= total)):
        limit = None
    if used is None:
        source = "machine"
        if total and "MemAvailable" in info:
            used = total - info["MemAvailable"]
    if limit is None:
        limit = total
    if source == "container" and limit == total:
        source = "container (no limit set: the machine's memory)"
    rss = None
    for line in (_read("/proc/self/status") or "").splitlines():
        if line.startswith("VmRSS:"):
            rss = int(line.split()[1]) * 1024
    return {"used": used, "limit": limit, "process": rss, "source": source}


def _cpu_quota() -> float | None:
    """CPUs the container may use (None: no limit)."""
    text = _read("/sys/fs/cgroup/cpu.max")
    if text:
        quota, _, period = text.partition(" ")
        if quota != "max" and period:
            return int(quota) / int(period)
        return None
    quota_v1 = _int(_read("/sys/fs/cgroup/cpu/cpu.cfs_quota_us"))
    period_v1 = _int(_read("/sys/fs/cgroup/cpu/cpu.cfs_period_us"))
    if quota_v1 and quota_v1 > 0 and period_v1:
        return quota_v1 / period_v1
    return None


def _cpu_seconds() -> float | None:
    """CPU time used so far by the container (or this process), in seconds."""
    for line in (_read("/sys/fs/cgroup/cpu.stat") or "").splitlines():
        if line.startswith("usage_usec "):
            return int(line.split()[1]) / 1_000_000
    v1 = _int(_read("/sys/fs/cgroup/cpuacct/cpuacct.usage"))
    if v1 is not None:
        return v1 / 1_000_000_000
    t = os.times()
    return t.user + t.system + t.children_user + t.children_system


def cpus() -> float:
    return _cpu_quota() or float(os.cpu_count() or 1)


class Sampler:
    """Samples memory and CPU every ``SAMPLE_SECONDS`` in a background thread."""

    def __init__(self, interval: float = SAMPLE_SECONDS) -> None:
        self.interval = interval
        self.started = time.time()
        self.samples: deque[dict[str, Any]] = deque(maxlen=SAMPLES)
        self._stop = threading.Event()
        self._last: tuple[float, float] | None = None
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self.sample()
        self._thread = threading.Thread(target=self._loop, name="la-metrics", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _loop(self) -> None:
        while not self._stop.wait(self.interval):
            # a dashboard number is never worth a crash
            with contextlib.suppress(Exception):
                self.sample()

    def sample(self) -> dict[str, Any]:
        now = time.monotonic()
        used = _cpu_seconds()
        percent = None
        with self._lock:
            if used is not None and self._last is not None and now > self._last[0]:
                share = (used - self._last[1]) / (now - self._last[0])
                percent = round(max(0.0, min(100.0, 100.0 * share / cpus())), 1)
            if used is not None:
                self._last = (now, used)
            mem = memory()
            point = {
                "time": datetime.now(UTC).isoformat(timespec="seconds"),
                "cpu_percent": percent,
                "memory_used": mem["used"],
                "memory_limit": mem["limit"],
            }
            self.samples.append(point)
        return point

    def history(self) -> list[dict[str, Any]]:
        with self._lock:
            return list(self.samples)


def system(sampler: Sampler, audits_root: Path, jobs: dict[str, int]) -> dict[str, Any]:
    disk = shutil.disk_usage(audits_root)
    try:
        load = [round(x, 2) for x in os.getloadavg()]
    except (AttributeError, OSError):
        load = None
    history = sampler.history()
    return {
        "memory": memory(),
        "cpu": {
            "percent": history[-1]["cpu_percent"] if history else None,
            "cpus": cpus(),
            "machine_cpus": os.cpu_count(),
            "load": load,
        },
        "disk": {"total": disk.total, "used": disk.used, "free": disk.free},
        "uptime_seconds": round(time.time() - sampler.started),
        "threads": threading.active_count(),
        "python": sys.version.split()[0],
        "platform": platform.platform(terse=True),
        "jobs": jobs,
        "history": history,
        "sample_seconds": sampler.interval,
    }


# ---- usage from the activity log ----------------------------------------------------------------


def _day(iso: str) -> str:
    return iso[:10]


def usage(
    entries: list[dict[str, Any]],
    days: int,
    users: list[Any],
    projects: list[Any],
    today: date | None = None,
) -> dict[str, Any]:
    """Counts per day and totals for the last ``days`` days. ``entries`` are activity log
    entries (any order); ``users`` and ``projects`` are the registry's records."""
    today = today or datetime.now(UTC).date()
    first = today - timedelta(days=days - 1)
    day_list = [(first + timedelta(days=i)).isoformat() for i in range(days)]
    start = day_list[0]
    per_day: dict[str, dict[str, Any]] = {
        d: {
            "day": d,
            "logins": 0,
            "users": set(),
            "scans_done": 0,
            "scans_failed": 0,
            "scans_cancelled": 0,
            "reports": 0,
            "enrich": 0,
        }
        for d in day_list
    }
    scan_seconds: list[float] = []
    wait_seconds: list[float] = []
    by_project: Counter[str] = Counter()
    by_user: Counter[str] = Counter()
    failures: list[dict[str, Any]] = []
    active: set[str] = set()
    project_seconds: dict[str, float] = defaultdict(float)
    for e in entries:
        t = str(e.get("time") or "")
        d = _day(t)
        if d < start or d not in per_day:
            continue
        row = per_day[d]
        user = e.get("user")
        action = e.get("action")
        if user:
            row["users"].add(user)
            active.add(user)
            by_user[user] += 1
        if action == "log in":
            row["logins"] += 1
        if action != "job finished":
            continue
        kind, state = e.get("kind"), e.get("state")
        project = e.get("project") or "-"
        worked, waited = e.get("seconds"), e.get("wait_seconds")
        if isinstance(waited, int | float):
            wait_seconds.append(float(waited))
        if kind == "scan":
            by_project[project] += 1
            if state == "done":
                row["scans_done"] += 1
                if isinstance(worked, int | float):
                    scan_seconds.append(float(worked))
                    project_seconds[project] += float(worked)
            elif state == "failed":
                row["scans_failed"] += 1
            else:
                row["scans_cancelled"] += 1
        elif kind == "report" and state == "done":
            row["reports"] += 1
        elif kind == "enrich" and state == "done":
            row["enrich"] += 1
        if state == "failed":
            failures.append(
                {
                    "time": t,
                    "project": project,
                    "kind": kind,
                    "title": e.get("title"),
                    "error": e.get("error"),
                }
            )
    series = []
    for d in day_list:
        row = dict(per_day[d])
        row["active_users"] = len(row.pop("users"))
        series.append(row)
    scans = sum(r["scans_done"] + r["scans_failed"] + r["scans_cancelled"] for r in series)
    done = sum(r["scans_done"] for r in series)
    report_titles: Counter[str] = Counter(
        str(e.get("title") or "reports")
        for e in entries
        if e.get("action") == "job finished"
        and e.get("kind") == "report"
        and e.get("state") == "done"
        and _day(str(e.get("time") or "")) >= start
    )
    return {
        "days": days,
        "from": start,
        "to": day_list[-1],
        "series": series,
        "totals": {
            "users": len([u for u in users if not getattr(u, "disabled", False)]),
            "active_users": len(active),
            "projects": len(projects),
            "archived_projects": len([p for p in projects if getattr(p, "archived", False)]),
            "logins": sum(r["logins"] for r in series),
            "scans": scans,
            "scans_done": done,
            "scans_failed": sum(r["scans_failed"] for r in series),
            "success_percent": round(100.0 * done / scans, 1) if scans else None,
            "scan_seconds_avg": round(sum(scan_seconds) / len(scan_seconds))
            if scan_seconds
            else None,
            "scan_seconds_max": round(max(scan_seconds)) if scan_seconds else None,
            "wait_seconds_avg": round(sum(wait_seconds) / len(wait_seconds), 1)
            if wait_seconds
            else None,
            "reports": sum(r["reports"] for r in series),
            "enrich": sum(r["enrich"] for r in series),
        },
        "reports_by_type": [{"name": k, "count": v} for k, v in report_titles.most_common(8)],
        "top_projects": [
            {"name": k, "scans": v, "scan_seconds": round(project_seconds.get(k, 0.0))}
            for k, v in by_project.most_common(8)
        ],
        "top_users": [{"name": k, "actions": v} for k, v in by_user.most_common(8)],
        "failures": sorted(failures, key=lambda f: f["time"], reverse=True)[:20],
    }
