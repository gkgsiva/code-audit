"""IBM MQ MQSC exports (``audit-inputs/mq/``, ``*.mqsc``): ``DEFINE QLOCAL/QREMOTE/QALIAS/
QMODEL`` become ``MQ`` integrations (one per queue, merged with the producers and consumers the
code analyzers find; transmission queues are skipped), ``DEFINE CHANNEL`` becomes an
``MQ-CHANNEL`` integration whose direction follows the channel type. Each carries the queue
manager of the export (``qmgr``: named in a ``* ... queue manager QM1`` comment;
``qmgr_file``: the file name, used when a queue manager of that name is found) so the queues
and channels of one queue manager are one integration (decision 177)."""

from __future__ import annotations

import re
from pathlib import PurePosixPath
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.integrations import integration
from legacy_audit.analyzers.textutil import split_lines
from legacy_audit.model import FileRecord

_DEFINE = re.compile(
    r"^\s*(?:DEFINE|DEF)\s+(QLOCAL|QL|QREMOTE|QR|QALIAS|QA|QMODEL|QM|CHANNEL|CHL|LISTENER|"
    r"NAMELIST|PROCESS|TOPIC|SUB|AUTHINFO|SERVICE)\s*\(\s*'?([^')]+)'?\s*\)(.*)$",
    re.I | re.S,
)
_ATTR = re.compile(r"\b([A-Z]+)\s*\(\s*'?((?:[^()']|\([^()]*\))*)'?\s*\)", re.I)
_QMGR_COMMENT = re.compile(
    r"^\s*\*.*?\bqueue\s+manager\s+'?([A-Za-z0-9._%/]{1,48})'?",
    re.I | re.M,
)
_QUEUE_TYPES = {"QL": "QLOCAL", "QR": "QREMOTE", "QA": "QALIAS", "QM": "QMODEL"}
_CHANNEL_DIRECTION = {
    "SDR": "outbound",
    "SVR": "outbound",
    "CLUSSDR": "outbound",
    "CLNTCONN": "outbound",
    "RCVR": "inbound",
    "RQSTR": "inbound",
    "CLUSRCVR": "inbound",
    "SVRCONN": "inbound",
}


def mqsc_commands(text: str) -> list[tuple[int, str]]:
    """Commands with ``+``/``-`` continuations joined, as (first line, text)."""
    out: list[tuple[int, str]] = []
    buf = ""
    start = 0
    for no, raw in enumerate(split_lines(text), 1):
        line = raw.rstrip()
        if not buf and (not line.strip() or line.lstrip().startswith("*")):
            continue
        if not buf:
            start = no
        if line.endswith(("+", "-")):
            buf += line[:-1] + " "
            continue
        out.append((start, buf + line))
        buf = ""
    if buf:
        out.append((start, buf))
    return out


class MqAnalyzer:
    name: ClassVar[str] = "mq"
    version: ClassVar[str] = "2"

    def supports(self, file: FileRecord) -> bool:
        return file.language == "mqsc" or (
            file.path.lower().startswith("audit-inputs/mq/") and file.category != "binary"
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        path = file.path
        facts.coverage.lines = text.count("\n") + 1
        first = True
        qm = _QMGR_COMMENT.search(text)
        owner = {"qmgr": qm.group(1).upper()} if qm else {}
        stem = PurePosixPath(path).stem.upper()
        if not owner and re.fullmatch(r"[A-Z0-9._%]{1,48}", stem):
            owner = {"qmgr_file": stem}
        for line, cmd in mqsc_commands(text):
            m = _DEFINE.match(cmd)
            if not m:
                if not re.match(
                    r"^\s*(ALTER|SET|DISPLAY|DIS|START|STOP|END|DELETE|REFRESH)\b", cmd, re.I
                ):
                    facts.coverage.gap(line, line, "not an MQSC command")
                continue
            if first:
                facts.add("tech_stack", tech("IBM MQ", None, "middleware", path, line))
                first = False
            otype = m.group(1).upper()
            otype = _QUEUE_TYPES.get(otype, otype)
            name = m.group(2).strip()
            attrs = {a.group(1).upper(): a.group(2).strip() for a in _ATTR.finditer(m.group(3))}
            if otype.startswith("Q"):
                details = {"defined_as": otype, **owner}
                for key in ("RNAME", "RQMNAME", "XMITQ", "TARGET", "TARGQ", "USAGE", "DESCR"):
                    if attrs.get(key):
                        details[key.lower()] = attrs[key]
                if attrs.get("USAGE", "").upper() != "XMITQ":  # transmission queues are plumbing
                    facts.add(
                        "integrations",
                        integration(
                            type="MQ",
                            target=name,
                            direction="defined",
                            file=path,
                            line=line,
                            protocol="MQ",
                            details=details,
                        ),
                    )
                facts.symbol("mq-queue", name, path, line, data=details)
            elif otype in ("CHANNEL", "CHL"):
                ctype = attrs.get("CHLTYPE", "").upper()
                details = {"chltype": ctype, **owner}
                if attrs.get("CONNAME"):
                    details["conname"] = attrs["CONNAME"]
                if attrs.get("XMITQ"):
                    details["xmitq"] = attrs["XMITQ"]
                facts.add(
                    "integrations",
                    integration(
                        type="MQ-CHANNEL",
                        target=name,
                        direction=_CHANNEL_DIRECTION.get(ctype, "unknown"),
                        file=path,
                        line=line,
                        protocol="MQ",
                        details=details,
                    ),
                )
        return facts
