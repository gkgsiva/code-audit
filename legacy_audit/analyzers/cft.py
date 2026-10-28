"""Axway CFT: configuration exports (``CFTPART``, ``CFTTCP``, ``CFTSEND``, ``CFTRECV``) and
``CFTUTIL SEND/RECV`` calls (the shell analyzer calls :func:`cftutil_integration`).

A CFT integration is one (partner/flow, direction). Flows defined in an export but never sent or
received by a scanned script are kept with partner ``*``; the linker merges them into the
matching script usage when there is one.
"""

from __future__ import annotations

import re
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.integrations import integration
from legacy_audit.analyzers.textutil import split_lines
from legacy_audit.model import FileRecord, Integration

_CMD = re.compile(r"^\s*(CFT[A-Z]+)\b(.*)$", re.I)
_PARAM = re.compile(r"(\w+)\s*=\s*(\([^)]*\)|'[^']*'|\"[^\"]*\"|[^,\s]+)")


def parse_params(text: str) -> dict[str, str]:
    return {m.group(1).upper(): m.group(2).strip("'\"()") for m in _PARAM.finditer(text)}


def cftutil_integration(args: str, file: str, line: int) -> Integration | None:
    """``SEND PART=X, IDF=Y, FNAME=Z`` (the text after ``CFTUTIL``) -> integration."""
    m = re.match(r"\s*(SEND|RECV)\b(.*)$", args, re.I | re.S)
    if not m:
        return None
    params = parse_params(m.group(2))
    partner = params.get("PART", "?")
    idf = params.get("IDF", "?")
    direction = "outbound" if m.group(1).upper() == "SEND" else "inbound"
    details = {"partner": partner, "idf": idf}
    if params.get("FNAME"):
        details["file"] = params["FNAME"]
    return integration(
        type="CFT",
        target=f"{partner}/{idf}",
        direction=direction,
        file=file,
        line=line,
        protocol="CFT",
        details=details,
    )


class CftAnalyzer:
    name: ClassVar[str] = "cft"
    version: ClassVar[str] = "2"

    def supports(self, file: FileRecord) -> bool:
        p = file.path.lower()
        return (
            p.startswith("audit-inputs/cft/")
            or p.endswith((".cft", ".cfg.cft"))
            or (
                (file.language in ("text", "ini", "data") or file.language.startswith("other ("))
                and "/cft" in p
            )
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        lines = split_lines(text)
        facts.coverage.lines = len(lines)
        commands: list[tuple[int, str, str]] = []
        current: list[str] = []
        cur_cmd = ""
        cur_line = 0
        for no, raw in enumerate(lines, 1):
            s = raw.strip()
            if not s or s.startswith(("/*", "*", "#")):
                continue
            m = _CMD.match(s)
            if m:
                if cur_cmd:
                    commands.append((cur_line, cur_cmd, " ".join(current)))
                cur_cmd, cur_line, current = m.group(1).upper(), no, [m.group(2)]
            elif cur_cmd:
                current.append(s.lstrip("+"))
            else:
                facts.coverage.gap(no, no, "not a CFT command")
        if cur_cmd:
            commands.append((cur_line, cur_cmd, " ".join(current)))
        if commands:
            facts.add(
                "tech_stack", tech("Axway CFT", None, "middleware", file.path, commands[0][0])
            )
        hosts: dict[str, str] = {}
        for _, cmd, params_text in commands:
            p = parse_params(params_text)
            if cmd == "CFTTCP" and p.get("ID"):
                hosts[p["ID"]] = p.get("HOST", "")
        for line, cmd, params_text in commands:
            p = parse_params(params_text)
            ident = p.get("ID", "")
            if cmd == "CFTPART" and ident:
                facts.symbol(
                    "cft-partner",
                    ident,
                    file.path,
                    line,
                    data={"host": hosts.get(ident, ""), "nspart": p.get("NSPART", "")},
                )
            elif cmd in ("CFTSEND", "CFTRECV") and ident:
                direction = "outbound" if cmd == "CFTSEND" else "inbound"
                details = {"partner": "*", "idf": ident, "defined_by_export": "true"}
                if p.get("FNAME"):
                    details["file"] = p["FNAME"]
                facts.add(
                    "integrations",
                    integration(
                        type="CFT",
                        target=f"*/{ident}",
                        direction=direction,
                        file=file.path,
                        line=line,
                        protocol="CFT",
                        details=details,
                    ),
                )
        return facts
