"""The ``patterns`` analyzer: runs the text rules of the enabled rule packs over one file.

Hits are cached with the file; which hits become findings (and with what severity) is decided
every run by the rule engine. Named groups ``secret...`` in a pattern are masked before the hit
is stored, so credentials never reach the cache, the outputs or the logs.
"""

from __future__ import annotations

import re
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.model import Category, FileRecord, Origin
from legacy_audit.rules.packs import Rule, text_rules

MAX_TEXT = 120


def _mask(m: re.Match[str]) -> str:
    text = m.group(0)
    spans = sorted(
        (m.start(g) - m.start(0), m.end(g) - m.start(0))
        for g in m.re.groupindex
        if g.startswith("secret") and m.group(g) is not None
    )
    for start, end in reversed(spans):
        text = text[:start] + "****" + text[end:]
    text = " ".join(text.split())
    return text if len(text) <= MAX_TEXT else text[: MAX_TEXT - 3] + "..."


class PatternsAnalyzer:
    name: ClassVar[str] = "patterns"
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        return file.origin == Origin.SOURCE and file.category not in (
            Category.BINARY,
            Category.GENERATED,
            Category.DOC,
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text
        if not text:
            return facts
        rules: list[Rule] = (
            ctx.text_rules
            if ctx.text_rules is not None
            else text_rules(["risk", "postgresql", "dependency"], None)
        )
        starts: list[int] | None = None
        for rule in rules:
            pat = rule.pattern
            if pat is None or (pat.languages and file.language not in pat.languages):
                continue
            if pat.unless_file and re.search(pat.unless_file, text):
                continue
            ignore = re.compile(pat.ignore) if pat.ignore else None
            count = 0
            for m in pat.compiled().finditer(text):
                if ignore is not None and ignore.search(m.group(0)):
                    continue
                if starts is None:
                    starts = [0] + [i + 1 for i, ch in enumerate(text) if ch == "\n"]
                line = _line(starts, m.start())
                facts.hits.append([rule.id, line, _mask(m)])
                count += 1
                if count >= pat.max_per_file:
                    break
        return facts


def _line(starts: list[int], offset: int) -> int:
    lo, hi = 0, len(starts) - 1
    while lo < hi:
        mid = (lo + hi + 1) // 2
        if starts[mid] <= offset:
            lo = mid
        else:
            hi = mid - 1
    return lo + 1
