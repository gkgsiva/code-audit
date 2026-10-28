"""One scan at a time per output folder.

The lock is an OS file lock on ``runs/.lock`` (``fcntl`` on Linux/macOS, ``msvcrt`` on Windows),
so it is released when the process ends, even after a crash or a killed terminal; a stale lock
file never blocks the next scan.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from types import TracebackType

LOCK_FILE = ".lock"


class ScanBusy(Exception):
    pass


class ScanLock:
    def __init__(self, runs_dir: Path) -> None:
        self.path = runs_dir / LOCK_FILE
        self._fd: int | None = None

    def acquire(self) -> ScanLock:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(self.path, os.O_RDWR | os.O_CREAT, 0o644)
        try:
            _lock(fd)
        except OSError:
            os.close(fd)
            raise ScanBusy(
                f"Another scan is running on {self.path.parent.parent} "
                "(wait for it to finish or cancel it)."
            ) from None
        os.ftruncate(fd, 0)
        os.write(fd, str(os.getpid()).encode())
        self._fd = fd
        return self

    def release(self) -> None:
        if self._fd is not None:
            try:
                _unlock(self._fd)
            finally:
                os.close(self._fd)
                self._fd = None

    def __enter__(self) -> ScanLock:
        return self.acquire()

    def __exit__(
        self, et: type[BaseException] | None, ev: BaseException | None, tb: TracebackType | None
    ) -> None:
        self.release()


if sys.platform == "win32":  # pragma: no cover - exercised by the Windows CI job
    import msvcrt

    def _lock(fd: int) -> None:
        os.lseek(fd, 0, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)

    def _unlock(fd: int) -> None:
        os.lseek(fd, 0, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)

else:
    import fcntl

    def _lock(fd: int) -> None:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def _unlock(fd: int) -> None:
        fcntl.flock(fd, fcntl.LOCK_UN)
