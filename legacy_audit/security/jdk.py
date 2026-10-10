"""Optional Java 25 compatibility checks with the JDK's ``jdeps`` and ``jdeprscan``.

They run on the jars found in the source tree when a JDK is available (``security.jdk_tools``);
without a JDK the check is reported as not run. Results are ``jdk_findings`` items
``{tool, name, class, api, file}`` that the dependency rules turn into findings.
"""

from __future__ import annotations

import logging
import re
import shutil
import subprocess
from pathlib import Path
from typing import Any

from legacy_audit.config import JdkToolsConfig
from legacy_audit.model import make_id

log = logging.getLogger("legacy_audit")

MAX_JARS = 200
TIMEOUT_S = 120
# jdeps -jdkinternals:  "   com.foo.Bar     -> sun.misc.BASE64Encoder    JDK internal API (...)"
_JDEPS = re.compile(r"^\s+(\S+)\s+->\s+(\S+)\s+(JDK internal API|JDK removed internal API)")
# jdeprscan --for-removal: "class com/foo/Bar uses deprecated method java/lang/Thread::stop()V
# (forRemoval=true)"
_JDEPRSCAN = re.compile(r"^class (\S+) uses deprecated \w+ (\S+).*forRemoval=true")


def find_jdk_tool(cfg: JdkToolsConfig, name: str) -> str | None:
    if cfg.java_home:
        for candidate in (f"{name}.exe", name):
            p = Path(cfg.java_home) / "bin" / candidate
            if p.is_file():
                return str(p)
        return None
    return shutil.which(name)


def _run(cmd: list[str]) -> str | None:
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=TIMEOUT_S, check=False)
    except (OSError, subprocess.TimeoutExpired) as err:
        log.warning("%s failed: %s", Path(cmd[0]).name, type(err).__name__)
        return None
    return proc.stdout


def parse_jdeps(output: str, jar: str) -> list[dict[str, Any]]:
    out = []
    for line in output.splitlines():
        m = _JDEPS.match(line)
        if m:
            out.append({"tool": "jdeps", "class": m.group(1), "api": m.group(2)})
    return _items(out, jar)


def parse_jdeprscan(output: str, jar: str) -> list[dict[str, Any]]:
    out = []
    for line in output.splitlines():
        m = _JDEPRSCAN.match(line)
        if m:
            out.append(
                {"tool": "jdeprscan", "class": m.group(1).replace("/", "."), "api": m.group(2)}
            )
    return _items(out, jar)


def _items(found: list[dict[str, Any]], jar: str) -> list[dict[str, Any]]:
    seen: set[tuple[str, str]] = set()
    items = []
    for f in found:
        key = (f["tool"], f["api"])
        if key in seen:  # one finding per API per jar
            continue
        seen.add(key)
        items.append(
            {
                **f,
                "id": make_id("jdk_finding", f["tool"], jar, f["api"]),
                "name": Path(jar).name,
                "file": jar,
                "evidence": [{"file": jar}],
            }
        )
    return items


def run_jdk_tools(
    cfg: JdkToolsConfig, jars: list[tuple[str, Path]]
) -> tuple[list[dict[str, Any]], str]:
    """(items, status) for jars given as (display path, absolute path)."""
    if not cfg.enabled:
        return [], "disabled"
    jdeps, jdeprscan = find_jdk_tool(cfg, "jdeps"), find_jdk_tool(cfg, "jdeprscan")
    if not jdeps and not jdeprscan:
        return [], "missing"
    items: list[dict[str, Any]] = []
    for display, path in jars[:MAX_JARS]:
        if jdeps:
            text = _run([jdeps, "--multi-release", "base", "-jdkinternals", str(path)])
            items += parse_jdeps(text or "", display)
        if jdeprscan:
            text = _run([jdeprscan, "--for-removal", str(path)])
            items += parse_jdeprscan(text or "", display)
    return items, "ran"
