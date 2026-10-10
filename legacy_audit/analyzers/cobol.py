"""COBOL and Pro*COBOL (fixed or free format), copybooks and CICS BMS maps.

Programs: ``PROGRAM-ID`` (a ``cobol-program`` service, or a ``cics-transaction`` service when
the program runs under CICS), paragraphs, ``CALL`` (literal or a variable with a ``VALUE``),
``COPY``, ``EXEC SQL`` (dialect ``cobol.sql_dialect``; dynamic SQL rebuilt by
:mod:`legacy_audit.analyzers.cobol_sql`), ``EXEC CICS`` (``SEND/RECEIVE MAP`` to
BMS maps, ``LINK/XCTL``, ``START/RETURN TRANSID``, file commands), file ``SELECT ... ASSIGN``
and IBM MQ (MQI) calls. A program that is only ever CALLed is not counted as a service; the
linker removes it once every file is known.

BMS mapsets: every ``DFHMDI`` map is a ``Screen``; its fields are the named ``DFHMDF`` macros.
"""

from __future__ import annotations

import re
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.cobol_sql import CobolSql
from legacy_audit.analyzers.embedded import extract
from legacy_audit.analyzers.integrations import integration
from legacy_audit.analyzers.textutil import stem
from legacy_audit.model import Evidence, FileRecord, Screen, Service, make_id

_PROGRAM_ID = re.compile(r"\bPROGRAM-ID\.?\s+['\"]?([\w-]+)", re.I)
_PARAGRAPH = re.compile(r"^([\w-]+)(\s+SECTION)?\s*\.\s*$", re.I)
_CALL = re.compile(r"\bCALL\s+(?:'([\w-]+)'|\"([\w-]+)\"|([A-Z][\w-]*))", re.I)
_COPY = re.compile(r"\bCOPY\s+['\"]?([\w-]+)['\"]?(?:\s+(?:OF|IN)\s+([\w-]+))?", re.I)
_VALUE = re.compile(r"^\s*\d\d\s+([\w-]+)\s+.*?\bVALUE\s+(?:IS\s+)?['\"]([^'\"]+)['\"]", re.I)
_MOVE = re.compile(r"\bMOVE\s+['\"]([^'\"]+)['\"]\s+TO\s+([\w-]+)", re.I)
_SELECT = re.compile(
    r"\bSELECT\s+(?:OPTIONAL\s+)?([\w-]+)\s+ASSIGN\s+(?:TO\s+)?(?:'([^']+)'|\"([^\"]+)\"|([\w-]+))",
    re.I,
)
_OPEN = re.compile(r"\bOPEN\s+((?:(?:INPUT|OUTPUT|I-O|EXTEND)\s+(?:[\w-]+\s*)+)+)", re.I)
_CICS = re.compile(r"\bEXEC\s+CICS\s+([\s\S]*?)\bEND-EXEC\b", re.I)
_CICS_ARG = re.compile(r"\b([A-Z]+)\s*\(\s*(?:'([^']*)'|\"([^\"]*)\"|([\w-]+))\s*\)", re.I)
_MQ_CALLS = {"MQCONN", "MQCONNX", "MQOPEN", "MQPUT", "MQPUT1", "MQGET", "MQCLOSE", "MQDISC"}
_NOT_PARAGRAPHS = {
    "END-IF",
    "END-PERFORM",
    "END-EVALUATE",
    "EXIT",
    "GOBACK",
    "END-EXEC",
    "CONTINUE",
    "STOP RUN",
}
_DIVISION = re.compile(r"^\s*(IDENTIFICATION|ID|ENVIRONMENT|DATA|PROCEDURE)\s+DIVISION\b", re.I)


def source_lines(text: str) -> list[str]:
    """Code part of each line (sequence area, indicator and identification area removed,
    comments blanked). Fixed format unless the source says ``>>SOURCE FORMAT FREE``."""
    free = bool(re.search(r">>\s*SOURCE\s+(FORMAT\s+)?(IS\s+)?FREE", text[:2000], re.I))
    raw = text.splitlines()
    if not free:
        # Sources without a sequence area (code starting in column 1-7) are treated as free.
        coded = [ln for ln in raw if ln.strip()]
        starts_left = sum(
            1
            for ln in coded
            if re.match(r"^\s{0,6}[A-Z]", ln, re.I) and not re.match(r"^[\d ]{6}[ *\-/D]", ln)
        )
        free = bool(coded) and starts_left > len(coded) * 0.6
    out = []
    for line in raw:
        if free:
            code = line.split("*>", 1)[0]
            out.append("" if code.lstrip().startswith("*") else code)
            continue
        if len(line) < 7:
            out.append("")
            continue
        indicator = line[6]
        if indicator in "*/":
            out.append("")
        elif indicator == "-":
            out.append(" " + line[7:72].lstrip().lstrip("'\""))  # continuation
        else:
            out.append(line[7:72].split("*>", 1)[0])
    return out


class CobolAnalyzer:
    name: ClassVar[str] = "cobol"
    version: ClassVar[str] = "3"

    def supports(self, file: FileRecord) -> bool:
        return file.language in ("cobol", "procobol", "bms")

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1
        if file.language == "bms":
            self._bms(file.path, text, facts)
        else:
            self._cobol(file, text, ctx, facts)
        return facts

    # ---- COBOL --------------------------------------------------------------------------------
    def _cobol(self, file: FileRecord, text: str, ctx: AnalyzerContext, facts: Facts) -> None:
        path = file.path
        lines = source_lines(text)
        code = "\n".join(lines)
        program_m = _PROGRAM_ID.search(code)
        copybook = program_m is None
        src = f"file:{path}"
        if copybook:
            facts.symbol("cobol-copybook", stem(path).upper(), path, 1)
        else:
            facts.add("tech_stack", tech("COBOL", None, "language", path, 1))
        program = program_m.group(1).upper() if program_m else stem(path).upper()
        prog_line = code.count("\n", 0, program_m.start()) + 1 if program_m else 1

        # Blank EXEC blocks in the code view so their words are not read as COBOL.
        code_wo_exec = re.sub(
            r"\bEXEC\s+(SQL|CICS|DLI)\b[\s\S]*?\bEND-EXEC\b",
            lambda m: re.sub(r"[^\n]", " ", m.group(0)),
            code,
            flags=re.I,
        )
        # Embedded SQL, from the code area only (one line per source line, so lines match);
        # PREPARE ... FROM :var is rebuilt from the MOVE/STRING statements that fill the var.
        dialect = ctx.config.cobol.sql_dialect
        built = CobolSql()
        built.run(code_wo_exec.split("\n"))
        res = extract(code, file=path, dialect=dialect, cobol=True, resolve=built.value_at)
        if res.statements:
            facts.add(
                "tech_stack",
                tech(
                    "DB2" if dialect == "db2" else dialect.upper(),
                    None,
                    "database",
                    path,
                    res.statements[0].line,
                ),
            )
        for stmt in res.statements:
            facts.add("sql_statements", stmt)
        for line, member in res.includes:
            facts.ref(
                kind="includes",
                source=src,
                source_label=program,
                target=member.upper(),
                target_kinds=["cobol-copybook", "file-name"],
                file=path,
                line=line,
            )
        for start, end in res.unparsed:
            facts.coverage.gap(start, end, "embedded SQL not parsed")

        values: dict[str, str] = {}
        for text_line in lines:
            v = _VALUE.match(text_line)
            if v:
                values[v.group(1).upper()] = v.group(2).strip()
        moves = [
            (m.group(2).upper(), m.group(1).strip(), code.count("\n", 0, m.start()) + 1)
            for m in _MOVE.finditer(code_wo_exec)
        ]
        for var, val, _ in moves:
            values.setdefault(var, val)

        for m in _COPY.finditer(code_wo_exec):
            line = code.count("\n", 0, m.start()) + 1
            facts.ref(
                kind="includes",
                source=src,
                source_label=program,
                target=m.group(1).upper(),
                target_kinds=["cobol-copybook", "file-name"],
                file=path,
                line=line,
            )

        mq_used: set[str] = set()
        for m in _CALL.finditer(code_wo_exec):
            line = code.count("\n", 0, m.start()) + 1
            literal = m.group(1) or m.group(2)
            target = literal or values.get((m.group(3) or "").upper())
            if not target:
                facts.coverage.gap(line, line, f"dynamic CALL {m.group(3)} not resolved")
                continue
            target = target.upper()
            if target in _MQ_CALLS:
                mq_used.add(target)
                continue
            if target.startswith(("CEE", "DSN", "ILBO", "IGZ")) or target in ("CBLTDLI", "AIBTDLI"):
                continue  # runtime / DB2 / IMS services, not application programs
            facts.ref(
                kind="calls",
                source=src,
                source_label=program,
                target=target,
                target_kinds=["cobol-program", "c-function", "c-executable"],
                file=path,
                line=line,
                data={"dynamic": "false" if literal else "true"},
            )

        if mq_used:
            facts.add("tech_stack", tech("IBM MQ (MQI)", None, "middleware", path, prog_line))
            queues = [(val, line) for var, val, line in moves if var.endswith("OBJECTNAME")]
            queues += [
                (val, 1)
                for var, val in values.items()
                if var.endswith("OBJECTNAME") and all(q != val for q, _ in queues)
            ]
            role = None
            if mq_used & {"MQPUT", "MQPUT1"} and "MQGET" not in mq_used:
                role = "producer"
            elif "MQGET" in mq_used and not mq_used & {"MQPUT", "MQPUT1"}:
                role = "consumer"
            for q, line in queues:
                facts.add(
                    "integrations",
                    integration(
                        type="MQ",
                        target=q,
                        direction="unknown",
                        file=path,
                        line=line,
                        role=role,
                        protocol="MQI",
                    ),
                )

        self._files(path, code_wo_exec, code, facts)
        cics = self._cics(path, program, text, facts)

        if copybook:
            return
        paragraphs = self._paragraphs(lines)
        transid = cics.get("transid")
        kind = "cics-transaction" if cics.get("used") else "cobol-program"
        sid = make_id("service", "cobol", path, program)
        facts.add(
            "services",
            Service(
                id=sid,
                name=transid or program,
                kind=kind,
                file=path,
                handler=program,
                evidence=[Evidence(file=path, line=prog_line, snippet=f"PROGRAM-ID. {program}")],
            ),
        )
        facts.symbol(
            "cobol-program",
            program,
            path,
            prog_line,
            owner=sid,
            data={
                "paragraphs": str(len(paragraphs)),
                "cics": "true" if cics.get("used") else "false",
            },
        )
        if cics.get("used"):
            facts.add("tech_stack", tech("CICS", None, "middleware", path, prog_line))
        if not _DIVISION.search(code):
            facts.coverage.gap(1, len(lines), "no COBOL division found")

    def _paragraphs(self, lines: list[str]) -> list[str]:
        out = []
        in_proc = False
        for line in lines:
            if re.match(r"^\s*PROCEDURE\s+DIVISION\b", line, re.I):
                in_proc = True
                continue
            if not in_proc or not line.strip() or len(line) - len(line.lstrip()) > 3:
                continue  # paragraph names start in area A
            m = _PARAGRAPH.match(line.strip())
            if m and m.group(1).upper() not in _NOT_PARAGRAPHS:
                out.append(m.group(1).upper())
        return out

    def _files(self, path: str, code: str, full: str, facts: Facts) -> None:
        assigns: dict[str, tuple[str, int]] = {}
        for m in _SELECT.finditer(code):
            target = m.group(2) or m.group(3) or m.group(4)
            assigns[m.group(1).upper()] = (target, full.count("\n", 0, m.start()) + 1)
        modes: dict[str, set[str]] = {}
        for m in _OPEN.finditer(code):
            mode = "INPUT"
            for tok in m.group(1).split():
                t = tok.upper()
                if t in ("INPUT", "OUTPUT", "I-O", "EXTEND"):
                    mode = t
                else:
                    modes.setdefault(t, set()).add(mode)
        for fd, (target, line) in assigns.items():
            used = modes.get(fd, set())
            if not used:
                direction = "unknown"
            elif used <= {"INPUT"}:
                direction = "inbound"
            elif used & {"I-O"} or ("INPUT" in used and used & {"OUTPUT", "EXTEND"}):
                direction = "both"
            else:
                direction = "outbound"
            name = re.sub(r"^(UT-S-|UT-|S-|DA-S-)", "", target.upper())
            facts.add(
                "integrations",
                integration(
                    type="FILE",
                    target=f"DD:{name}",
                    direction=direction,
                    file=path,
                    line=line,
                    details={"select": fd},
                ),
            )

    def _cics(self, path: str, program: str, text: str, facts: Facts) -> dict[str, str]:
        info: dict[str, str] = {}
        src = f"file:{path}"
        lines = source_lines(text)
        code = "\n".join(lines)
        for m in _CICS.finditer(code):
            info["used"] = "true"
            line = code.count("\n", 0, m.start()) + 1
            body = " ".join(m.group(1).split())
            verb = " ".join(body.split()[:2]).upper()
            args = {
                a.group(1).upper(): (a.group(2) or a.group(3) or a.group(4) or "")
                for a in _CICS_ARG.finditer(body)
            }
            literal = {
                a.group(1).upper() for a in _CICS_ARG.finditer(body) if a.group(2) or a.group(3)
            }
            if verb.startswith(("SEND MAP", "RECEIVE MAP")) and "MAP" in args:
                target = args["MAP"].upper()
                facts.ref(
                    kind="forwards" if verb.startswith("SEND") else "reads",
                    source=src,
                    source_label=program,
                    target=target,
                    target_kinds=["bms-map"],
                    file=path,
                    line=line,
                    report_unresolved="MAP" in literal,
                    data={"mapset": args.get("MAPSET", "").upper()},
                )
            elif verb.split()[0] in ("LINK", "XCTL") and "PROGRAM" in args:
                facts.ref(
                    kind="calls",
                    source=src,
                    source_label=program,
                    target=args["PROGRAM"].upper(),
                    target_kinds=["cobol-program"],
                    file=path,
                    line=line,
                    report_unresolved="PROGRAM" in literal,
                )
            elif verb.split()[0] in ("RETURN",) and "TRANSID" in args and "TRANSID" in literal:
                info.setdefault("transid", args["TRANSID"].upper())
            elif verb.split()[0] == "START" and "TRANSID" in args:
                facts.ref(
                    kind="triggers",
                    source=src,
                    source_label=program,
                    target=args["TRANSID"].upper(),
                    target_kinds=["cics-transaction"],
                    file=path,
                    line=line,
                    report_unresolved=False,
                )
            elif verb.split()[0] in ("READ", "WRITE", "REWRITE", "DELETE", "STARTBR") and (
                "FILE" in args or "DATASET" in args
            ):
                ds = (args.get("FILE") or args.get("DATASET") or "").upper()
                direction = "inbound" if verb.split()[0] in ("READ", "STARTBR") else "outbound"
                facts.add(
                    "integrations",
                    integration(
                        type="FILE",
                        target=f"CICS:{ds}",
                        direction=direction,
                        file=path,
                        line=line,
                        protocol="CICS",
                    ),
                )
            elif verb.startswith(("WEB ", "INVOKE ")):
                endpoint = args.get("URIMAP") or args.get("WEBSERVICE") or args.get("HOST") or "?"
                facts.add(
                    "integrations",
                    integration(
                        type="HTTP",
                        target=f"CICS {verb.split()[0]} {endpoint}",
                        direction="outbound",
                        file=path,
                        line=line,
                        protocol="CICS",
                    ),
                )
        return info

    # ---- BMS ----------------------------------------------------------------------------------
    def _bms(self, path: str, text: str, facts: Facts) -> None:
        statements: list[tuple[int, str, str, str]] = []  # (line, label, macro, operands)
        buf = ""
        start = 0
        for no, raw in enumerate(text.splitlines(), 1):
            if raw.startswith("*") or not raw.strip():
                continue
            body = raw[:71]
            cont = (len(raw) > 71 and raw[71] != " ") or body.rstrip().endswith(",")
            if not buf:
                start = no
            buf += body.rstrip() if not buf else body.strip()
            if cont:
                continue
            m = re.match(r"^(\S+)?\s+(DFHMSD|DFHMDI|DFHMDF|PRINT|END|TITLE)\b\s*(.*)$", buf, re.I)
            if m:
                statements.append(
                    (start, (m.group(1) or "").upper(), m.group(2).upper(), m.group(3))
                )
            elif buf.strip():
                facts.coverage.gap(start, no, "not a BMS macro")
            buf = ""
        mapset = stem(path).upper()
        current: dict[str, object] | None = None
        maps: list[dict[str, object]] = []
        for line, label, macro, operands in statements:
            if macro == "DFHMSD" and label and "TYPE=FINAL" not in operands.upper():
                mapset = label
            elif macro == "DFHMDI":
                current = {"name": label, "line": line, "fields": 0}
                maps.append(current)
            elif macro == "DFHMDF" and current is not None and label:
                current["fields"] = int(str(current["fields"])) + 1
        if maps:
            facts.add("tech_stack", tech("CICS BMS", None, "framework", path, maps[0]["line"]))  # type: ignore[arg-type]
        for mp in maps:
            name = str(mp["name"]) or mapset
            line = int(str(mp["line"]))
            sid = make_id("screen", path, name)
            facts.add(
                "screens",
                Screen(
                    id=sid,
                    name=name,
                    technology="BMS",
                    file=path,
                    fields_count=int(str(mp["fields"])),
                    evidence=[Evidence(file=path, line=line)],
                ),
            )
            facts.symbol("bms-map", name, path, line, owner=sid, data={"mapset": mapset})
