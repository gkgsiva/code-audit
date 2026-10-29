"""Background jobs for the GUI: one scan or report build at a time per workspace, with
progress events the browser reads as Server-Sent Events and a cancel switch.

On the hosted site every project's jobs also pass a shared ``Gate``: at most N run at once
across all projects, the others wait in order (state ``queued``, with their place in line).
Each job reports how far it is and an estimate of the time left."""

from __future__ import annotations

import logging
import threading
import time
import traceback
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from legacy_audit.parallel import Cancelled

log = logging.getLogger("legacy_audit")

HISTORY = 20
_THROTTLE_S = 0.25


class Busy(Exception):
    pass


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


@dataclass
class Job:
    kind: str  # scan | report | enrich
    title: str
    id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    state: str = "running"  # queued | running | done | failed | cancelled
    project: str | None = None
    user: str | None = None
    expected_seconds: float | None = None  # how long the last similar job took
    started_at: str = field(default_factory=_now)
    finished_at: str | None = None
    result: dict[str, Any] | None = None
    error: str | None = None
    events: list[dict[str, Any]] = field(default_factory=list)
    cancel: threading.Event = field(default_factory=threading.Event)
    queue_position: int | None = None
    thread_id: int | None = None
    _lock: threading.Lock = field(default_factory=threading.Lock)
    _last: tuple[str, float] = ("", 0.0)
    _created: float = field(default_factory=time.monotonic)
    _began: float | None = None  # monotonic time the work started (after the queue)
    _stage: tuple[str, float, int, int] = ("", 0.0, 0, 0)  # name, since, n, total

    def emit(self, type_: str, **data: Any) -> None:
        with self._lock:
            self.events.append({"seq": len(self.events), "type": type_, "time": _now(), **data})
            if type_ == "progress":
                stage, n, total = data.get("stage", ""), data.get("n", 0), data.get("total", 0)
                since = self._stage[1] if stage == self._stage[0] else time.monotonic()
                self._stage = (stage, since, n, total)

    def progress(self, stage: str, n: int, total: int) -> None:
        """Scan progress callback, thinned out so a 100k-file scan sends a few events a second."""
        now = time.monotonic()
        last_stage, last_time = self._last
        if stage == last_stage and now - last_time < _THROTTLE_S and n != total:
            return
        self._last = (stage, now)
        self.emit("progress", stage=stage, n=n, total=total)

    def estimate(self) -> dict[str, Any]:
        """Elapsed time, the current step's progress and the time left (None: no basis yet).

        The time left comes from how long the same kind of job took last time on this project
        when known; else from the current step's pace (items done per second)."""
        with self._lock:
            stage, since, n, total = self._stage
            began = self._began
        out: dict[str, Any] = {
            "stage": stage or None,
            "stage_done": n,
            "stage_total": total,
            "stage_percent": round(100.0 * n / total, 1) if total else None,
            "elapsed_seconds": None,
            "percent": None,
            "eta_seconds": None,
            "eta_basis": None,
        }
        if began is None:
            return out
        now = time.monotonic()
        elapsed = now - began
        out["elapsed_seconds"] = round(elapsed, 1)
        if self.state != "running":
            out["percent"] = 100.0 if self.state == "done" else None
            return out
        if self.expected_seconds and self.expected_seconds > 0:
            left = max(self.expected_seconds - elapsed, 0.0)
            out["percent"] = round(min(99.0, 100.0 * elapsed / self.expected_seconds), 1)
            out["eta_seconds"] = round(left) if left > 0 else None
            out["eta_basis"] = "last run" if left > 0 else "taking longer than last time"
        elif total and n:
            pace = (now - since) / n
            out["eta_seconds"] = round(pace * (total - n))
            out["eta_basis"] = f"pace of the {stage} step"
        return out

    def timings(self) -> tuple[float | None, float | None]:
        """Seconds waited in the queue and seconds worked (None: the work never started)."""
        if self._began is None:
            return None, None
        return self._began - self._created, time.monotonic() - self._began

    def since(self, seq: int) -> list[dict[str, Any]]:
        with self._lock:
            return self.events[seq:]

    def info(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "kind": self.kind,
            "title": self.title,
            "state": self.state,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "result": self.result,
            "error": self.error,
            "events": len(self.events),
            "project": self.project,
            "user": self.user,
            "queue_position": self.queue_position,
            "expected_seconds": self.expected_seconds,
            **self.estimate(),
        }

    @property
    def active(self) -> bool:
        return self.state in ("queued", "running")


class _ToJob(logging.Handler):
    """Warnings logged by the job's own thread become ``warning`` events (other projects' jobs
    log on other threads and stay out)."""

    def __init__(self, job: Job) -> None:
        super().__init__(logging.WARNING)
        self.job = job

    def emit(self, record: logging.LogRecord) -> None:
        if self.job.thread_id is None or record.thread == self.job.thread_id:
            self.job.emit("warning", message=record.getMessage())


class Gate:
    """At most ``limit()`` jobs run at once; the rest wait first come, first served."""

    def __init__(self, limit: Callable[[], int]) -> None:
        self.limit = limit
        self._cond = threading.Condition()
        self._waiting: list[Job] = []
        self._running: list[Job] = []

    def acquire(self, job: Job) -> None:
        with self._cond:
            self._waiting.append(job)
            try:
                while True:
                    if job.cancel.is_set():
                        raise Cancelled
                    place = self._waiting.index(job)
                    if place == 0 and len(self._running) < max(1, self.limit()):
                        break
                    if job.queue_position != place + 1:
                        job.queue_position = place + 1
                        job.emit("queued", position=place + 1)
                    self._cond.wait(0.5)
            finally:
                self._waiting.remove(job)
                self._cond.notify_all()
            self._running.append(job)
            job.queue_position = None

    def release(self, job: Job) -> None:
        with self._cond:
            if job in self._running:
                self._running.remove(job)
            self._cond.notify_all()

    def snapshot(self) -> dict[str, list[Job]]:
        with self._cond:
            return {"running": list(self._running), "waiting": list(self._waiting)}


class Jobs:
    def __init__(
        self,
        gate: Gate | None = None,
        on_create: Callable[[Job], None] | None = None,
        on_end: Callable[[Job], None] | None = None,
    ) -> None:
        self._lock = threading.Lock()
        self.current: Job | None = None
        self.history: list[Job] = []
        self.gate = gate
        self.on_create = on_create
        self.on_end = on_end

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            if self.current and self.current.id == job_id:
                return self.current
            return next((j for j in self.history if j.id == job_id), None)

    def start(
        self,
        kind: str,
        title: str,
        work: Callable[[Job], dict[str, Any]],
        **extra: Any,
    ) -> Job:
        with self._lock:
            if self.current is not None and self.current.active:
                raise Busy(f"'{self.current.title}' is still running")
            job = Job(kind, title, state="queued" if self.gate else "running", **extra)
            self.current = job
            self.history = [job, *self.history][:HISTORY]
        if self.on_create:
            self.on_create(job)
        thread = threading.Thread(target=self._run, args=(job, work), daemon=True)
        thread.start()
        return job

    def _run(self, job: Job, work: Callable[[Job], dict[str, Any]]) -> None:
        job.thread_id = threading.get_ident()
        handler = _ToJob(job)
        log.addHandler(handler)
        gated = False
        try:
            if self.gate is not None:
                self.gate.acquire(job)
                gated = True
                job.state = "running"
            job._began = time.monotonic()
            job.emit("started", title=job.title)
            job.result = work(job)
            job.state = "done"
        except Cancelled:
            job.state = "cancelled"
        except Exception as err:  # reported to the browser; the server keeps running
            job.state = "failed"
            job.error = f"{type(err).__name__}: {err}"
            log.debug("Job %s failed\n%s", job.id, traceback.format_exc())
        finally:
            if gated and self.gate is not None:
                self.gate.release(job)
            log.removeHandler(handler)
            job.finished_at = _now()
            job.emit("end", state=job.state, result=job.result, error=job.error)
            if self.on_end:
                try:
                    self.on_end(job)
                except Exception:  # bookkeeping must not break the job
                    log.debug("on_end failed for job %s\n%s", job.id, traceback.format_exc())

    def wait(self, timeout: float = 600) -> Job | None:
        """Block until the current job ends (tests and the CLI)."""
        end = time.monotonic() + timeout
        while self.current is not None and self.current.active:
            if time.monotonic() > end:
                break
            time.sleep(0.05)
        return self.current
