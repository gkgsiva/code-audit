"""The ``metrics`` analyzer: code units with cyclomatic complexity, and duplication windows.

It runs per file like the other analyzers, so its results are cached; the cross-file parts
(clone matching, bands, dead code) are computed every run in ``legacy_audit.metrics.compute``.
"""

from __future__ import annotations

from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.cobol import source_lines
from legacy_audit.analyzers.embedded import blank_spans, extract
from legacy_audit.metrics.tokens import strip_comments, windows
from legacy_audit.metrics.units import cobol_units, plsql_units, script_unit, tree_units
from legacy_audit.model import Category, FileRecord

TREE = {"java", "c", "cpp", "proc"}
SCRIPTS = {"shell", "perl", "python", "batch"}
SQL = {"sql", "plsql"}
DUPLICATION = TREE | SCRIPTS | SQL | {"cobol", "procobol", "jsp", "javascript", "typescript"}


class MetricsAnalyzer:
    name: ClassVar[str] = "metrics"
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        return file.language in DUPLICATION and file.category not in (
            Category.GENERATED,
            Category.BINARY,
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        lang = file.language
        if lang in ("cobol", "procobol"):
            lines = source_lines(text)
            code = "\n".join(lines)
            facts.units = cobol_units(lines)
        else:
            code = strip_comments(text, "c" if lang == "proc" else lang)
            if lang == "proc":
                sql = extract(code, file=file.path, dialect=None, cobol=False)
                code = blank_spans(code, sql.spans)
            if lang in TREE:
                facts.units = tree_units(code, lang)
            elif lang in SQL:
                facts.units = plsql_units(code)
            elif lang in SCRIPTS:
                facts.units = script_unit(code, lang, file.path.rsplit("/", 1)[-1])
        facts.windows = windows(code, ctx.config.metrics.duplication_tokens)
        facts.coverage.lines = text.count("\n") + 1 if text else 0
        return facts
