"""Process pool for the CPU-heavy steps (per-file analysis, PostgreSQL grading).

Results come back in input order, so a run is the same whatever the number of workers. Small
batches run in this process: starting workers costs more than it saves, and tests that patch
an analyzer keep working.
"""

from __future__ import annotations

import os
import threading
from collections.abc import Callable, Iterator, Sequence
from concurrent.futures import Future, ProcessPoolExecutor
from typing import Any, TypeVar

T = TypeVar("T")
R = TypeVar("R")

MIN_BATCH = 64  # fewer items than this: no pool
MAX_AUTO_WORKERS = 8
_INFLIGHT_PER_WORKER = 4


class Cancelled(Exception):
    """The user asked to stop (GUI cancel button or Ctrl+C)."""


def worker_count(configured: int) -> int:
    """``configured`` workers, or for 0 (auto) one per CPU less one, at most 8."""
    if configured > 0:
        return configured
    return max(1, min((os.cpu_count() or 2) - 1, MAX_AUTO_WORKERS))


def check(cancel: threading.Event | None) -> None:
    if cancel is not None and cancel.is_set():
        raise Cancelled


def ordered_map(
    fn: Callable[[T], R],
    items: Sequence[T],
    workers: int,
    *,
    initializer: Callable[..., None] | None = None,
    initargs: tuple[Any, ...] = (),
    cancel: threading.Event | None = None,
) -> Iterator[R]:
    """``map(fn, items)`` across ``workers`` processes, yielding in input order.

    ``initializer(*initargs)`` runs once per worker (and once here when no pool is used) to set
    up state that is expensive to send with every item. Raises ``Cancelled`` when ``cancel`` is
    set; outstanding work is dropped.
    """
    if workers <= 1 or len(items) < MIN_BATCH:
        if initializer is not None:
            initializer(*initargs)
        for item in items:
            check(cancel)
            yield fn(item)
        return
    pool = ProcessPoolExecutor(max_workers=workers, initializer=initializer, initargs=initargs)
    try:
        window = workers * _INFLIGHT_PER_WORKER
        pending: list[Future[R]] = []
        it = iter(items)
        for item in it:
            pending.append(pool.submit(fn, item))
            if len(pending) >= window:
                break
        while pending:
            check(cancel)
            result = pending.pop(0).result()
            nxt = next(it, None)
            if nxt is not None:
                pending.append(pool.submit(fn, nxt))
            yield result
    finally:
        pool.shutdown(wait=True, cancel_futures=True)
