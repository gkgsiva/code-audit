"""The ``metrics`` analyzer: code units with cyclomatic complexity, and duplication windows.

It runs per file like the other analyzers, so its results are cached; the cross-file parts
(clone matching, bands, dead code) are computed every run in ``legacy_audit.metrics.compute``.
"""

from __future__ import annotations

from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.basic import strip_comments_basic
from legacy_audit.analyzers.cobol import source_lines
from legacy_audit.analyzers.embedded import blank_spans, extract
from legacy_audit.metrics.tokens import strip_comments, windows
from legacy_audit.metrics.units import (
    cobol_units,
    cs_units,
    plsql_units,
    script_unit,
    tree_units,
    ts_units,
    vb_units,
)
from legacy_audit.model import Category, FileRecord

TREE = {"java", "c", "cpp", "proc"}
SCRIPT_TREE = {"typescript", "javascript"}
SCRIPTS = {"shell", "perl", "python", "batch"}
SQL = {"sql", "plsql"}
BASIC = {"vb6", "vbnet"}
DUPLICATION = (
    TREE | SCRIPTS | SQL | BASIC | {"cobol", "procobol", "jsp", "javascript", "typescript", "csharp"}
)


class MetricsAnalyzer:
    name: ClassVar[str] = "metrics"
    version: ClassVar[str] = "3"  # 2: TypeScript/JavaScript units; 3: C#, VB6 and VB.NET

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
        elif lang in BASIC:
            code = strip_comments_basic(text)
            facts.units = vb_units(text)
        else:
            code = strip_comments(text, "c" if lang == "proc" else lang)
            if lang == "proc":
                sql = extract(code, file=file.path, dialect=None, cobol=False)
                code = blank_spans(code, sql.spans)
            if lang in TREE:
                facts.units = tree_units(code, lang)
            elif lang in SCRIPT_TREE and "node_modules/" not in file.path:
                facts.units = ts_units(text, file.path)  # tree-sitter skips comments
            elif lang == "csharp":
                facts.units = cs_units(text)  # tree-sitter skips comments
            elif lang in SQL:
                facts.units = plsql_units(code)
            elif lang in SCRIPTS:
                facts.units = script_unit(code, lang, file.path.rsplit("/", 1)[-1])
        facts.windows = windows(code, ctx.config.metrics.duplication_tokens)
        facts.coverage.lines = text.count("\n") + 1 if text else 0
        return facts
