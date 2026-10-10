"""Background jobs for the GUI: one scan or report build at a time per workspace, with
progress events the browser reads as Server-Sent Events and a cancel switch."""

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
    kind: str  # scan | report
    title: str
    id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    state: str = "running"  # running | done | failed | cancelled
    started_at: str = field(default_factory=_now)
    finished_at: str | None = None
    result: dict[str, Any] | None = None
    error: str | None = None
    events: list[dict[str, Any]] = field(default_factory=list)
    cancel: threading.Event = field(default_factory=threading.Event)
    _lock: threading.Lock = field(default_factory=threading.Lock)
    _last: tuple[str, float] = ("", 0.0)

    def emit(self, type_: str, **data: Any) -> None:
        with self._lock:
            self.events.append({"seq": len(self.events), "type": type_, "time": _now(), **data})

    def progress(self, stage: str, n: int, total: int) -> None:
        """Scan progress callback, thinned out so a 100k-file scan sends a few events a second."""
        now = time.monotonic()
        last_stage, last_time = self._last
        if stage == last_stage and now - last_time < _THROTTLE_S and n != total:
            return
        self._last = (stage, now)
        self.emit("progress", stage=stage, n=n, total=total)

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
        }


class _ToJob(logging.Handler):
    """Warnings logged while a job runs become ``warning`` events."""

    def __init__(self, job: Job) -> None:
        super().__init__(logging.WARNING)
        self.job = job

    def emit(self, record: logging.LogRecord) -> None:
        self.job.emit("warning", message=record.getMessage())


class Jobs:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.current: Job | None = None
        self.history: list[Job] = []

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            if self.current and self.current.id == job_id:
                return self.current
            return next((j for j in self.history if j.id == job_id), None)

    def start(self, kind: str, title: str, work: Callable[[Job], dict[str, Any]]) -> Job:
        with self._lock:
            if self.current is not None and self.current.state == "running":
                raise Busy(f"'{self.current.title}' is still running")
            job = Job(kind, title)
            self.current = job
            self.history = [job, *self.history][:HISTORY]
        thread = threading.Thread(target=self._run, args=(job, work), daemon=True)
        thread.start()
        return job

    def _run(self, job: Job, work: Callable[[Job], dict[str, Any]]) -> None:
        handler = _ToJob(job)
        log.addHandler(handler)
        job.emit("started", title=job.title)
        try:
            job.result = work(job)
            job.state = "done"
        except Cancelled:
            job.state = "cancelled"
        except Exception as err:  # reported to the browser; the server keeps running
            job.state = "failed"
            job.error = f"{type(err).__name__}: {err}"
            log.debug("Job %s failed\n%s", job.id, traceback.format_exc())
        finally:
            log.removeHandler(handler)
            job.finished_at = _now()
            job.emit("end", state=job.state, result=job.result, error=job.error)

    def wait(self, timeout: float = 600) -> Job | None:
        """Block until the current job ends (tests and the CLI)."""
        end = time.monotonic() + timeout
        while self.current is not None and self.current.state == "running":
            if time.monotonic() > end:
                break
            time.sleep(0.05)
        return self.current
