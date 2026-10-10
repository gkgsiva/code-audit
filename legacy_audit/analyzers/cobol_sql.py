"""SQL text that COBOL builds in working storage for ``PREPARE ... FROM :var`` and
``EXECUTE IMMEDIATE :var``.

Values come from ``VALUE`` clauses, then the procedure division in source order: ``MOVE x TO
a b`` (sets; a reference-modified target ``A(P:N)`` is an append), ``STRING ... INTO a`` (sets,
or appends when ``a`` is its first operand or ``WITH POINTER`` is used) and ``INITIALIZE``.
Statements inside ``IF``/``EVALUATE``/inline ``PERFORM`` are conditional (a period ends them
all). A DB2 varying-length host variable (a group with a level-49 length and a level-49 text
item) is looked up through its text item. Operands that are not known become ``?`` holes.
"""

from __future__ import annotations

import re
from dataclasses import replace

from legacy_audit.analyzers.dynsql import Built, Tracker, concat, empty, hole, lit

_TOKEN = re.compile(
    r"'(?:[^']|'')*'?|\"(?:[^\"]|\"\")*\"?|[A-Z0-9][\w-]*(?:\s*\([^)]*\))?|\.(?=\s|$)|\S",
    re.I,
)
_ITEM = re.compile(r"^\s*(\d\d)\s+([\w-]+)\b(.*)$", re.I)
_VALUE = re.compile(r"\bVALUES?\s+(?:IS\s+|ARE\s+)?('(?:[^']|'')*'|\"(?:[^\"]|\"\")*\")", re.I)
_PIC_X = re.compile(r"\bPIC(?:TURE)?\s+(?:IS\s+)?X", re.I)
_VERBS = {
    "ACCEPT", "ADD", "CALL", "CLOSE", "COMPUTE", "CONTINUE", "DELETE", "DISPLAY", "DIVIDE",
    "ELSE", "END-EVALUATE", "END-IF", "END-PERFORM", "END-STRING", "EVALUATE", "EXEC", "EXIT",
    "GO", "GOBACK", "IF", "INITIALIZE", "INSPECT", "MOVE", "MULTIPLY", "NOT", "ON", "OPEN",
    "PERFORM", "READ", "RETURN", "REWRITE", "SET", "START", "STOP", "STRING", "SUBTRACT",
    "UNSTRING", "WHEN", "WRITE", "OVERFLOW",
}  # fmt: skip
_FIGURATIVE = {
    "SPACE": " ", "SPACES": " ", "QUOTE": "'", "QUOTES": "'", "LOW-VALUE": "",
    "LOW-VALUES": "", "HIGH-VALUE": "", "HIGH-VALUES": "", "ZERO": "0", "ZEROS": "0",
    "ZEROES": "0",
}  # fmt: skip
_OPENERS = {"IF", "EVALUATE"}
_CLOSERS = {"END-IF", "END-EVALUATE", "END-PERFORM"}


def _literal(token: str) -> str | None:
    if token[:1] in ("'", '"'):
        q = token[0]
        body = token[1:-1] if len(token) > 1 and token.endswith(q) else token[1:]
        return body.replace(q + q, q)
    return None


def _name(token: str) -> str:
    return token.split("(", 1)[0].strip().upper()


class CobolSql:
    """Walks the code area of a program once; see the module docstring."""

    def __init__(self) -> None:
        self.tracker = Tracker()
        self.text_item: dict[str, str] = {}  # varying-length group -> its text item

    def value_at(self, name: str, line: int) -> Built | None:
        key = name.lstrip(":").split(":")[0].strip().upper()
        got = self.tracker.at(key, line)
        if got is None and key in self.text_item:
            got = self.tracker.at(self.text_item[key], line)
        return got

    def run(self, lines: list[str]) -> None:
        """``lines``: the code area, one entry per source line, ``EXEC`` blocks blanked."""
        proc = next(
            (i for i, ln in enumerate(lines) if re.match(r"^\s*PROCEDURE\s+DIVISION\b", ln, re.I)),
            len(lines),
        )
        self._data(lines[:proc])
        tokens = [
            (i + 1, m.group(0)) for i in range(proc, len(lines)) for m in _TOKEN.finditer(lines[i])
        ]
        self._procedure(tokens)

    # ---- data division ------------------------------------------------------------------------
    def _data(self, lines: list[str]) -> None:
        group: tuple[str, int] | None = None  # (name, level) of the last group item
        i = 0
        while i < len(lines):
            m = _ITEM.match(lines[i])
            if not m:
                i += 1
                continue
            start = i
            entry = lines[i]
            while "." not in re.sub(r"'(?:[^']|'')*'|\"[^\"]*\"", "", entry) and i + 1 < len(lines):
                i += 1
                nxt = lines[i]
                entry = entry.rstrip() + (nxt[1:] if entry.count("'") % 2 else " " + nxt.strip())
            i += 1
            level, name = int(m.group(1)), m.group(2).upper()
            if level == 49 and group is not None and _PIC_X.search(entry):
                self.text_item[group[0]] = name
            elif level != 49:
                group = (name, level) if not re.search(r"\bPIC", entry, re.I) else None
            v = _VALUE.search(entry)
            if v:
                text = _literal(v.group(1)) or ""
                self.tracker.assign(name, lit(text, start + 1), start + 1, False, False)

    # ---- procedure division -------------------------------------------------------------------
    def _procedure(self, tokens: list[tuple[int, str]]) -> None:
        depth = 0
        i = 0
        while i < len(tokens):
            line, tok = tokens[i]
            word = tok.upper()
            if tok == ".":
                depth = 0
            elif word in _OPENERS:
                depth += 1
            elif word in _CLOSERS:
                depth = max(depth - 1, 0)
            elif word == "PERFORM" and i + 1 < len(tokens) and tokens[i + 1][1].upper() in (
                "UNTIL", "VARYING", "WITH", "TEST",
            ):  # fmt: skip
                depth += 1  # inline PERFORM ... END-PERFORM
            elif word == "MOVE":
                i = self._move(tokens, i + 1, depth > 0)
                continue
            elif word == "STRING":
                i = self._string(tokens, i + 1, depth > 0, line)
                continue
            elif word == "INITIALIZE":
                j = i + 1
                while self._operand_at(tokens, j):
                    if depth == 0:
                        self.tracker.assign(_name(tokens[j][1]), empty(line), line, False, False)
                    j += 1
                i = j
                continue
            i += 1

    @staticmethod
    def _operand_at(tokens: list[tuple[int, str]], j: int) -> bool:
        """True while ``tokens[j]`` still belongs to the current statement."""
        return j < len(tokens) and tokens[j][1] != "." and tokens[j][1].upper() not in _VERBS

    def _operand(self, tok: str, before: str, line: int) -> Built:
        text = _literal(tok)
        if text is not None:
            return lit(text, line)
        word = _name(tok)
        if word in _FIGURATIVE:
            return Built(_FIGURATIVE[word], line, fragments=0)
        if word.isdigit():
            return lit(word, line)
        got = self.tracker.get(word)
        if got is not None:
            return got
        return hole(before, line)

    def _move(self, tokens: list[tuple[int, str]], i: int, cond: bool) -> int:
        if i + 2 > len(tokens):
            return i
        line, src = tokens[i]
        if src.upper() in ("CORRESPONDING", "CORR"):
            return i + 1
        j = i + 1
        if j >= len(tokens) or tokens[j][1].upper() != "TO":
            return j
        j += 1
        while self._operand_at(tokens, j):
            target = tokens[j][1]
            name = _name(target)
            append = "(" in target  # reference modification: writes into the middle/end
            prev = self.tracker.get(name)
            value = self._operand(src, prev.text if prev is not None and append else "", line)
            value = replace(value, lines=value.lines | {line})
            if value.value_holes + value.structural_holes == 0 or prev is not None:
                self.tracker.assign(name, value, line, cond, append and prev is not None)
            j += 1
        return j

    def _string(self, tokens: list[tuple[int, str]], i: int, cond: bool, line: int) -> int:
        parts: list[str] = []
        j = i
        while j < len(tokens) and tokens[j][1].upper() != "INTO" and tokens[j][1] != ".":
            word = tokens[j][1].upper()
            if word == "DELIMITED":
                j += 2 if j + 1 < len(tokens) and tokens[j + 1][1].upper() == "BY" else 1
                j += 1  # the delimiter itself
                continue
            parts.append(tokens[j][1])
            j += 1
        if j >= len(tokens) or tokens[j][1].upper() != "INTO" or j + 1 >= len(tokens):
            return j
        target = _name(tokens[j + 1][1])
        k = j + 2
        pointer = False
        while self._operand_at(tokens, k):
            pointer = pointer or tokens[k][1].upper() == "POINTER"
            k += 1
        if k < len(tokens) and tokens[k][1].upper() in ("ON", "NOT", "OVERFLOW"):
            while k < len(tokens) and tokens[k][1].upper() != "END-STRING" and tokens[k][1] != ".":
                k += 1
        prev = self.tracker.get(target)
        append = pointer or bool(parts and _name(parts[0]) == target)
        if parts and _name(parts[0]) == target:
            parts = parts[1:]
        out = empty(line)
        before = prev.text if prev is not None and append else ""
        for p in parts:
            out = concat(out, self._operand(p, before + out.text, line))
        out.fragments = max(out.fragments, 1)
        out.how.add("string-statement")
        self.tracker.assign(target, out, line, cond, append and prev is not None)
        return k
