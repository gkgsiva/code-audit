"""z/OS JCL: jobs, steps, ``EXEC PGM=`` / procedure calls, DD datasets, in-stream procedures
and DB2 ``RUN PROGRAM(...)`` under ``IKJEFT01``. A job is a ``BatchJob`` (scheduler ``jcl``);
a catalogued procedure (``//NAME PROC``) is a ``jcl-proc`` symbol its callers link to."""

from __future__ import annotations

import re
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.textutil import split_lines
from legacy_audit.model import BatchJob, Evidence, FileRecord, make_id

UTILITIES = {
    "IEFBR14",
    "IDCAMS",
    "IEBGENER",
    "IEBCOPY",
    "IEBUPDTE",
    "IEBPTPCH",
    "SORT",
    "DFSORT",
    "ICEMAN",
    "ICETOOL",
    "SYNCSORT",
    "ADRDSSU",
    "IKJEFT01",
    "IKJEFT1A",
    "IKJEFT1B",
    "IRXJCL",
    "DSNUTILB",
    "DSNTIAUL",
    "DSNTEP2",
    "DSNTEP4",
    "DFSRRC00",
    "IEHLIST",
    "IEHMOVE",
    "IEHPROGM",
    "FTP",
    "BPXBATCH",
    "IGYCRCTL",
    "IEWL",
    "HEWL",
    "ASMA90",
    "DSNHPC",
    "FILEAID",
    "IOEAGFMT",
    "EZACFSM1",
}
_STMT = re.compile(
    r"^//([A-Z@#$][\w@#$.]*)?\s+(JOB|EXEC|DD|PROC|PEND|SET|INCLUDE|JCLLIB|OUTPUT|IF|ELSE|ENDIF|CNTL|ENDCNTL)\b\s*(.*)$",
    re.I,
)
_PARAM = re.compile(r"([A-Z]+)=(\((?:[^()]|\([^()]*\))*\)|'[^']*'|[^,\s]+)", re.I)
_RUN = re.compile(
    r"\bRUN\s+PROGRAM\s*\(\s*([\w@#$]+)\s*\)(?:\s+PLAN\s*\(\s*([\w@#$]+)\s*\))?", re.I
)


def _params(text: str) -> dict[str, str]:
    return {m.group(1).upper(): m.group(2) for m in _PARAM.finditer(text)}


def _statements(text: str, facts: Facts) -> list[tuple[int, str, str, str, list[str]]]:
    """(line, name, op, operands, in-stream data lines) with continuations joined."""
    out: list[tuple[int, str, str, str, list[str]]] = []
    lines = split_lines(text)
    i = 0
    while i < len(lines):
        raw = lines[i][:72]
        no = i + 1
        i += 1
        if raw.startswith("//*") or not raw.strip():
            continue
        if not raw.startswith("//"):
            if raw.startswith("/*"):
                continue
            facts.coverage.gap(no, no, "not a JCL statement")
            continue
        operands = raw
        # Continuation: operand field ends with a comma; the next line is "//   more".
        while (
            operands.rstrip().endswith(",")
            and i < len(lines)
            and lines[i].startswith("//")
            and not lines[i].startswith("//*")
        ):
            operands = operands.rstrip() + lines[i][2:72].strip()
            i += 1
        m = _STMT.match(operands)
        if not m:
            if operands.strip() != "//":
                facts.coverage.gap(no, no, "unrecognised JCL statement")
            continue
        data: list[str] = []
        ops = m.group(3)
        if m.group(2).upper() == "DD" and re.match(r"^(\*|DATA\b)", ops.strip(), re.I):
            delim = "/*"
            dm = re.search(r"DLM=(?:'([^']+)'|(\S\S))", ops, re.I)
            if dm:
                delim = dm.group(1) or dm.group(2)
            while (
                i < len(lines)
                and not lines[i].startswith(delim)
                and not (delim == "/*" and lines[i].startswith("//"))
            ):
                data.append(lines[i][:72])
                i += 1
            if i < len(lines) and lines[i].startswith(delim):
                i += 1
        out.append((no, (m.group(1) or "").upper(), m.group(2).upper(), ops, data))
    return out


class JclAnalyzer:
    name: ClassVar[str] = "jcl"
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        return file.language == "jcl"

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        path = file.path
        facts.coverage.lines = text.count("\n") + 1
        statements = _statements(text, facts)
        job: BatchJob | None = None
        owner = f"file:{path}"
        owner_label = path
        current_step = ""
        current_pgm = ""
        in_proc = ""
        for line, name, op, ops, data in statements:
            p = _params(ops)
            if op == "JOB":
                jid = make_id("batch_job", "jcl", name)
                job = BatchJob(
                    id=jid,
                    name=name,
                    scheduler="jcl",
                    component=None,
                    evidence=[Evidence(file=path, line=line)],
                )
                facts.add("batch_jobs", job)
                facts.symbol("jcl-job", name, path, line, owner=jid)
                owner, owner_label = f"batch_job:{jid}", name
                if "tech_stack" not in facts.entities:
                    facts.add("tech_stack", tech("JCL", None, "scheduler", path, line))
            elif op == "PROC" and name:
                in_proc = name
                facts.symbol("jcl-proc", name, path, line)
                if job is None:
                    owner, owner_label = f"file:{path}", name
            elif op == "PEND":
                in_proc = ""
            elif op == "EXEC":
                current_step = name or f"STEP@{line}"
                first = ops.split(",", 1)[0].strip()
                pgm = p.get("PGM", "").strip("'").upper()
                proc = p.get("PROC", "").upper() or ("" if "=" in first else first.upper())
                current_pgm = pgm
                if job is not None:
                    job.steps.append(current_step)
                if pgm:
                    if job is not None:
                        job.programs.append(pgm)
                    if pgm not in UTILITIES and not pgm.startswith("&"):
                        facts.ref(
                            kind="runs",
                            source=owner,
                            source_label=owner_label,
                            target=pgm,
                            target_kinds=["cobol-program", "c-executable", "file-name"],
                            file=path,
                            line=line,
                            data={"step": current_step, "proc": in_proc},
                        )
                elif proc:
                    facts.ref(
                        kind="runs",
                        source=owner,
                        source_label=owner_label,
                        target=proc,
                        target_kinds=["jcl-proc"],
                        file=path,
                        line=line,
                        data={"step": current_step},
                    )
            elif op == "DD":
                dsn = p.get("DSN") or p.get("DSNAME")
                if dsn and job is not None and not dsn.startswith("&&"):
                    ds = dsn.strip("'").split("(")[0].upper()
                    if ds not in job.datasets:
                        job.datasets.append(ds)
                if data and current_pgm in ("IKJEFT01", "IKJEFT1A", "IKJEFT1B"):
                    for k, dl in enumerate(data, 1):
                        r = _RUN.search(dl)
                        if r:
                            prog = r.group(1).upper()
                            if job is not None:
                                job.programs.append(prog)
                            facts.add("tech_stack", tech("DB2", None, "database", path, line + k))
                            facts.ref(
                                kind="runs",
                                source=owner,
                                source_label=owner_label,
                                target=prog,
                                target_kinds=["cobol-program", "c-executable"],
                                file=path,
                                line=line + k,
                                data={"step": current_step, "plan": r.group(2) or ""},
                            )
        return facts
