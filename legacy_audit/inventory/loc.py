"""Code / comment / blank line counts per language comment syntax.

A line holding code and a comment counts as code. Blank lines inside block comments count as
blank. Comment markers inside string literals are not recognised (a known approximation; it
can only move a line from comment to code, never lose it).
"""

from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass

from legacy_audit.analyzers.textutil import split_lines
from legacy_audit.inventory.language import NO_LOC_LANGUAGES

C_BLOCK = ("/*", "*/")


@dataclass(frozen=True)
class Syntax:
    line: tuple[str, ...] = ()
    block: tuple[tuple[str, str], ...] = ()


SYNTAX: dict[str, Syntax] = {
    "java": Syntax(("//",), (C_BLOCK,)),
    "c": Syntax(("//",), (C_BLOCK,)),
    "cpp": Syntax(("//",), (C_BLOCK,)),
    "proc": Syntax(("//",), (C_BLOCK,)),
    "javascript": Syntax(("//",), (C_BLOCK,)),
    "typescript": Syntax(("//",), (C_BLOCK,)),
    "groovy": Syntax(("//",), (C_BLOCK,)),
    "css": Syntax((), (C_BLOCK,)),
    "sql": Syntax(("--",), (C_BLOCK,)),
    "plsql": Syntax(("--",), (C_BLOCK,)),
    "shell": Syntax(("#",)),
    "perl": Syntax(("#",)),
    "python": Syntax(("#",)),
    "make": Syntax(("#",)),
    "yaml": Syntax(("#",)),
    "crontab": Syntax(("#",)),
    "dockerfile": Syntax(("#",)),
    "awk": Syntax(("#",)),
    "sed": Syntax(("#",)),
    "tcl": Syntax(("#",)),
    "ruby": Syntax(("#",)),
    "powershell": Syntax(("#",), (("<#", "#>"),)),
    "vbscript": Syntax(("'",)),
    "vb6": Syntax(("'",)),
    "vbnet": Syntax(("'",)),
    "sqlloader": Syntax(("--",)),
    "kotlin": Syntax(("//",), (C_BLOCK,)),
    "scala": Syntax(("//",), (C_BLOCK,)),
    "sqlj": Syntax(("//",), (C_BLOCK,)),
    "csharp": Syntax(("//",), (C_BLOCK,)),
    "go": Syntax(("//",), (C_BLOCK,)),
    "php": Syntax(("//", "#"), (C_BLOCK,)),
    "properties": Syntax(("#", "!")),
    "ini": Syntax(("#", ";")),
    "jil": Syntax(("#",), (C_BLOCK,)),
    "xml": Syntax((), (("<!--", "-->"),)),
    "html": Syntax((), (("<!--", "-->"),)),
    "msbuild": Syntax((), (("<!--", "-->"),)),
    "xaml": Syntax((), (("<!--", "-->"),)),
    "fxml": Syntax((), (("<!--", "-->"),)),
    "aspx": Syntax((), (("<%--", "--%>"), ("<!--", "-->"))),
    "aspnet": Syntax((), (("<%--", "--%>"),)),
    "razor": Syntax((), (("@*", "*@"), ("<!--", "-->"))),
    "jsp": Syntax((), (("<%--", "--%>"), ("<!--", "-->"))),
    "velocity": Syntax(("##",), (("#*", "*#"), ("<!--", "-->"))),
    "freemarker": Syntax((), (("<#--", "-->"), ("<!--", "-->"))),
}


@dataclass
class Loc:
    code: int = 0
    comment: int = 0
    blank: int = 0

    def add(self, kind: str) -> None:
        setattr(self, kind, getattr(self, kind) + 1)


def _count_generic(lines: list[str], syn: Syntax) -> Loc:
    loc = Loc()
    in_block: str | None = None
    for raw in lines:
        s = raw.strip()
        if not s:
            loc.blank += 1
            continue
        code = comment = False
        i, n = 0, len(s)
        while i < n:
            if in_block is not None:
                comment = True
                end = s.find(in_block, i)
                if end < 0:
                    i = n
                    break
                i = end + len(in_block)
                in_block = None
                continue
            # Nearest comment start from position i; on a tie the longer marker wins.
            found: list[tuple[int, int, str, str]] = []
            for prefix in syn.line:
                j = s.find(prefix, i)
                if j >= 0:
                    found.append((j, -len(prefix), prefix, ""))
            for start, end_mark in syn.block:
                j = s.find(start, i)
                if j >= 0:
                    found.append((j, -len(start), start, end_mark))
            if not found:
                code = code or bool(s[i:].strip())
                break
            j, _, marker, end_mark = min(found)
            if s[i:j].strip():
                code = True
            comment = True
            if not end_mark:
                break
            in_block = end_mark
            i = j + len(marker)
        loc.add("code" if code else "comment" if comment else "blank")
    return loc


def _count_by_line(lines: list[str], kind_of: Callable[[str], str]) -> Loc:
    loc = Loc()
    for raw in lines:
        loc.add(kind_of(raw))
    return loc


_FREE_FORMAT = re.compile(r">>\s*SOURCE\s+FORMAT\s+(IS\s+)?FREE", re.IGNORECASE)


def _cobol(lines: list[str]) -> Loc:
    free = any(_FREE_FORMAT.search(line) for line in lines[:20])

    def kind(raw: str) -> str:
        line = raw.rstrip("\r\n")
        if not free:
            if len(line) > 6 and line[6] in "*/":
                return "comment"
            line = line[7:72]
        s = line.strip()
        if not s:
            return "blank"
        return "comment" if s.startswith("*>") else "code"

    return _count_by_line(lines, kind)


def _jcl(raw: str) -> str:
    s = raw.strip()
    if not s:
        return "blank"
    return "comment" if s.startswith("//*") else "code"


def _batch(raw: str) -> str:
    s = raw.strip()
    if not s:
        return "blank"
    low = s.lower()
    if low.startswith(("rem ", "@rem ", "::")) or low in ("rem", "@rem"):
        return "comment"
    return "code"


def _basic(raw: str) -> str:
    """Visual Basic 6 and VB.NET: a line starting with ``'`` or ``REM`` is a comment (a ``'``
    after code leaves the line code; ``'`` inside a string is not a comment)."""
    s = raw.strip()
    if not s:
        return "blank"
    low = s.lower()
    return "comment" if s.startswith("'") or low == "rem" or low.startswith("rem ") else "code"


def _star(raw: str) -> str:
    s = raw.strip()
    if not s:
        return "blank"
    return "comment" if s.startswith("*") else "code"


def count_loc(text: str, language: str) -> Loc:
    if language in NO_LOC_LANGUAGES:
        return Loc()  # data files and backup copies are listed, not measured
    lines = split_lines(text)
    if language in ("cobol", "procobol"):
        return _cobol(lines)
    if language == "jcl":
        return _count_by_line(lines, _jcl)
    if language == "batch":
        return _count_by_line(lines, _batch)
    if language in ("mqsc", "bms"):
        return _count_by_line(lines, _star)
    if language in ("vb6", "vbnet"):
        return _count_by_line(lines, _basic)
    if language in ("markdown", "text"):
        # Documentation: every non-blank line is prose, counted as comment.
        return _count_by_line(lines, lambda raw: "comment" if raw.strip() else "blank")
    return _count_generic(lines, SYNTAX.get(language, Syntax()))
