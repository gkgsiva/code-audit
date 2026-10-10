"""Rebuilding SQL text that code assembles at run time, shared by the C/Pro*C and COBOL
analyzers (the Java analyzer keeps its own tree-based version with the same fields).

A ``Built`` value is the text a variable holds at some point: every fragment (``text``), only
the fragments added on every code path (``minimal``), the run-time pieces shown as ``?`` and
classified by :func:`hole_kind` (``value`` or ``structural``), and whole alternative texts
(``variants``) from ternaries and branch-only reassignments. ``Tracker`` keeps each variable's
history in source order so a ``PREPARE ... FROM :var`` or an API call can look up the value
the variable had on that line.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field, replace
from typing import Protocol

from legacy_audit.analyzers.sqltools import hole_kind
from legacy_audit.model import Evidence, SqlStatement

MAX_VARIANTS = 5
MAX_EVIDENCE = 10

# printf-style conversions (C ``sprintf``); ``*`` width/precision consume an argument.
C_FORMAT = re.compile(r"%([-+ #0]*)(\*|\d+)?(?:\.(\*|\d+))?(?:hh|h|ll|l|L|z|j|t|q)?([a-zA-Z%])")


@dataclass
class Built:
    text: str
    line: int
    minimal: str | None = None
    value_holes: int = 0
    structural_holes: int = 0
    fragments: int = 1
    optional: int = 0
    how: set[str] = field(default_factory=set)
    lines: set[int] = field(default_factory=set)
    variants: list[str] = field(default_factory=list)

    @property
    def base(self) -> str:
        return self.text if self.minimal is None else self.minimal

    @property
    def dynamic(self) -> bool:
        return bool(
            self.value_holes
            or self.structural_holes
            or self.optional
            or self.variants
            or self.fragments > 1
        )


def lit(text: str, line: int) -> Built:
    return Built(text, line, lines={line})


def empty(line: int) -> Built:
    return Built("", line, fragments=0)


def hole(before: str, line: int) -> Built:
    kind = hole_kind(before)
    return Built(
        "?",
        line,
        value_holes=1 if kind == "value" else 0,
        structural_holes=1 if kind == "structural" else 0,
        fragments=0,
        lines={line},
    )


def concat(a: Built, b: Built, optional: bool = False) -> Built:
    return Built(
        text=a.text + b.text,
        line=a.line,
        minimal=a.base + ("" if optional else b.base),
        value_holes=a.value_holes + b.value_holes,
        structural_holes=a.structural_holes + b.structural_holes,
        fragments=a.fragments + b.fragments,
        optional=a.optional + b.optional + (b.fragments if optional else 0),
        how=a.how | b.how | ({"conditional"} if optional else set()),
        lines=a.lines | b.lines,
        # Variants are whole alternative texts: combine each side's alternatives with the other.
        variants=[*(v + b.text for v in a.variants), *(a.text + v for v in b.variants)][
            :MAX_VARIANTS
        ],
    )


def join(parts: list[Built], line: int) -> Built:
    out = empty(line)
    for p in parts:
        out = concat(out, p)
    return out


def choice(a: Built, b: Built) -> Built:
    """``cond ? a : b``: the text of ``a``, with ``b`` as a variant."""
    out = concat(empty(a.line), a)
    out.how.add("ternary")
    out.variants = [b.text, *a.variants, *b.variants][:MAX_VARIANTS]
    out.fragments = a.fragments + b.fragments
    out.lines |= b.lines
    return out


def update(prev: Built | None, new: Built, cond: bool, append: bool) -> Built:
    """The value of a variable after an assignment (``append`` = added to the end); ``cond``
    when the statement runs on some code paths only."""
    if prev is None:
        return new
    if append:
        return concat(prev, new, optional=cond)
    if not cond:
        return new  # a plain reassignment replaces the value
    out = concat(prev, empty(prev.line))  # reassigned on some paths only: keep both
    out.how |= {"reassignment"} | new.how
    out.variants = [*prev.variants, new.text][:MAX_VARIANTS]
    out.fragments += new.fragments
    out.lines |= new.lines
    return out


def format_c(fmt: Built, args: list[Built | None], line: int, before: str = "") -> Built:
    """``sprintf`` text: each conversion takes the next argument (its rebuilt text, or a hole).
    ``before`` is the text already in the buffer when the result is appended to it."""
    out = Built("", fmt.line, fragments=0, how=set(fmt.how), lines=set(fmt.lines))
    pos, k = 0, 0
    for m in C_FORMAT.finditer(fmt.text):
        out = concat(out, Built(fmt.text[pos : m.start()], line, fragments=0))
        pos = m.end()
        if m.group(4) == "%":
            out = concat(out, Built("%", line, fragments=0))
            continue
        k += (m.group(2) == "*") + (m.group(3) == "*")  # width/precision arguments
        value = args[k] if k < len(args) else None
        k += 1
        if m.group(4) == "s" and value is not None:
            out = concat(out, value)
        elif m.group(4) in "sc":
            out = concat(out, hole(before + out.text, line))
        else:  # a number is always a value, never a name or a clause
            out = concat(out, hole("=", line))
    out = concat(out, Built(fmt.text[pos:], line, fragments=0))
    out.fragments += 1  # the format string itself
    out.how.add("string-format")
    return out


class Tracker:
    """Values of text variables in source order; ``at`` answers "what did it hold here"."""

    def __init__(self) -> None:
        self.current: dict[str, Built] = {}
        self.history: dict[str, list[tuple[int, Built]]] = {}

    def get(self, name: str) -> Built | None:
        return self.current.get(name)

    def assign(self, name: str, new: Built, line: int, cond: bool, append: bool) -> None:
        new = replace(new, lines=new.lines | {line})  # the statement is evidence too
        value = update(self.current.get(name), new, cond, append)
        self.current[name] = value
        self.history.setdefault(name, []).append((line, value))

    def at(self, name: str, line: int) -> Built | None:
        """The value just before ``line``; a variable only built further down the source (a
        paragraph or function that runs earlier) gives its last value."""
        entries = self.history.get(name)
        if not entries:
            return None
        before = [b for ln, b in entries if ln <= line]
        return before[-1] if before else entries[-1][1]


class Rebuilt(Protocol):
    @property
    def base(self) -> str: ...

    fragments: int
    optional: int
    value_holes: int
    structural_holes: int
    how: set[str]
    lines: set[int]
    variants: list[str]


def fill(fact: SqlStatement, built: Rebuilt) -> SqlStatement:
    """Copy how the text was rebuilt onto the statement, with evidence for each fragment."""
    fact.built_with = sorted(set(fact.built_with) | built.how)
    fact.fragments = max(built.fragments, 1)
    fact.optional_fragments = built.optional
    fact.value_holes = built.value_holes
    fact.structural_holes = built.structural_holes
    variants = [built.base.strip(), *(v.strip() for v in built.variants)]
    fact.variants = list(dict.fromkeys(v for v in variants if v and v != fact.text))[:MAX_VARIANTS]
    known = {e.line for e in fact.evidence}
    for ln in sorted(built.lines - known)[:MAX_EVIDENCE]:  # where the other fragments are
        fact.evidence.append(Evidence(file=fact.file, line=ln))
    return fact
