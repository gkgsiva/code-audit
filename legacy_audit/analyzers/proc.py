"""Pro*C (``.pc``): ``EXEC SQL`` blocks become ``SqlStatement``s (source ``embedded``), then the
blocks are blanked and the remaining C is handed to the C analyzer. Pro*COBOL is handled by the
COBOL analyzer, which reads ``EXEC SQL ... END-EXEC`` itself."""

from __future__ import annotations

from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.c_cpp import analyze_c
from legacy_audit.analyzers.embedded import blank_spans, extract
from legacy_audit.model import FileRecord


class ProcAnalyzer:
    name: ClassVar[str] = "proc"
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        return file.language == "proc"

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        path = file.path
        facts.coverage.lines = text.count("\n") + 1
        res = extract(text, file=path, dialect=ctx.config.sql.dialect, cobol=False)
        if res.spans:
            facts.add("tech_stack", tech("Oracle Pro*C", None, "language", path, 1))
        for stmt in res.statements:
            facts.add("sql_statements", stmt)
        for line, member in res.includes:
            facts.ref(
                kind="includes",
                source=f"file:{path}",
                source_label=path,
                target=member,
                target_kinds=["file-name"],
                file=path,
                line=line,
            )
        for start, end in res.unparsed:
            facts.coverage.gap(start, end, "embedded SQL not parsed")
        analyze_c(path, blank_spans(text, res.spans), facts)
        return facts
