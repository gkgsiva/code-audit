"""Code units (functions, methods, paragraphs, procedures, scripts) and their cyclomatic
complexity: 1 + the number of decision points, counted per language.

- Java: ``if``, loops, ``case`` labels (not ``default``), ``catch``, ``?:``, ``&&``, ``||``.
- TypeScript/JavaScript: per function, method or arrow function (a short arrow function used
  as an argument, ``x => x.id``, is part of its enclosing unit): the same decision points as
  Java, plus ``??``.
- C/C++/Pro*C: the same, with ``case`` statements and range ``for``.
- COBOL: per paragraph or section: ``IF``, ``WHEN`` (not ``WHEN OTHER``), ``UNTIL``, ``AND``,
  ``OR``, and the conditional phrases ``AT END``, ``INVALID KEY``, ``ON SIZE ERROR``,
  ``ON EXCEPTION``, ``ON OVERFLOW``.
- PL/SQL: per procedure, function, trigger or package body: ``IF``, ``ELSIF``, ``WHEN``,
  ``LOOP``/``WHILE``/``FOR`` loops.
- Shell and other scripts: one unit per file: ``if``, ``elif``, loops, ``case`` branches,
  ``&&``, ``||``.
"""

from __future__ import annotations

import re
from collections.abc import Iterator

import tree_sitter_c
import tree_sitter_cpp
import tree_sitter_java
import tree_sitter_typescript
from tree_sitter import Language, Node, Parser

from legacy_audit.model import CodeUnit

_JAVA = Language(tree_sitter_java.language())
_C = Language(tree_sitter_c.language())
_CPP = Language(tree_sitter_cpp.language())
_TS = Language(tree_sitter_typescript.language_typescript())
_TSX = Language(tree_sitter_typescript.language_tsx())
_PARSERS: dict[str, Parser] = {}

JAVA_UNITS = {"method_declaration", "constructor_declaration", "compact_constructor_declaration"}
JAVA_DECISIONS = {
    "if_statement",
    "for_statement",
    "enhanced_for_statement",
    "while_statement",
    "do_statement",
    "catch_clause",
    "ternary_expression",
}
C_UNITS = {"function_definition"}
C_DECISIONS = {
    "if_statement",
    "for_statement",
    "for_range_loop",
    "while_statement",
    "do_statement",
    "catch_clause",
    "conditional_expression",
}


TS_UNITS = {
    "function_declaration",
    "generator_function_declaration",
    "method_definition",
    "function_expression",
    "arrow_function",
}
TS_DECISIONS = {
    "if_statement",
    "for_statement",
    "for_in_statement",
    "while_statement",
    "do_statement",
    "catch_clause",
    "ternary_expression",
    "switch_case",
}


def _parser(lang: str) -> Parser:
    p = _PARSERS.get(lang)
    if p is None:
        p = _PARSERS[lang] = Parser(
            {"java": _JAVA, "c": _C, "cpp": _CPP, "ts": _TS, "tsx": _TSX}[lang]
        )
    return p


def _nonblank(lines: list[str], start: int, end: int) -> int:
    return sum(1 for ln in lines[start - 1 : end] if ln.strip())


def _is_logical(node: Node) -> bool:
    if node.type != "binary_expression":
        return False
    op = node.child_by_field_name("operator")
    return op is not None and op.type in ("&&", "||", "??")


def _decisions(node: Node, kinds: set[str], units: set[str]) -> int:
    """Decision points under ``node``, not descending into nested units or classes."""
    count = 0
    stack = list(node.children)
    while stack:
        n = stack.pop()
        if n.type in units or n.type in ("class_body", "enum_body", "interface_body"):
            continue
        if n.type in kinds or _is_logical(n):
            count += 1
        elif (n.type == "switch_label" and n.child_count and n.children[0].type == "case") or (
            n.type == "case_statement" and n.child_by_field_name("value") is not None
        ):
            # Java "case X" / C "case X:"; a bare "default" adds no path.
            count += 1
        stack.extend(n.children)
    return count


def _walk(node: Node, kinds: set[str]) -> Iterator[Node]:
    stack = [node]
    while stack:
        n = stack.pop()
        if n.type in kinds:
            yield n
        stack.extend(reversed(n.children))


def _name(node: Node, text: bytes) -> str:
    if node.type == "function_definition":
        decl = node.child_by_field_name("declarator")
        while decl is not None and decl.type not in ("identifier", "field_identifier"):
            inner = decl.child_by_field_name("declarator")
            if inner is None:
                found = next(
                    (c for c in decl.children if c.type in ("identifier", "qualified_identifier")),
                    None,
                )
                decl = found
                break
            decl = inner
        return text[decl.start_byte : decl.end_byte].decode("utf-8", "replace") if decl else "?"
    name = node.child_by_field_name("name")
    return text[name.start_byte : name.end_byte].decode("utf-8", "replace") if name else "?"


def _ts_name(node: Node, text: bytes) -> str | None:
    """Name of a TypeScript/JavaScript unit; None for an anonymous function that is part of
    its enclosing unit (a callback passed as an argument)."""
    name = node.child_by_field_name("name")
    if name is not None:
        return text[name.start_byte : name.end_byte].decode("utf-8", "replace")
    parent = node.parent
    if parent is None:
        return None
    if parent.type in ("variable_declarator", "public_field_definition", "pair",
                       "assignment_expression"):  # fmt: skip
        key = (
            parent.child_by_field_name("name")
            or parent.child_by_field_name("key")
            or parent.child_by_field_name("left")
        )
        if key is not None:
            return text[key.start_byte : key.end_byte].decode("utf-8", "replace")
    if parent.type == "array" and parent.parent is not None:  # ['$scope', function (...) {}]
        parent = parent.parent
    if parent.type == "arguments" and node.end_point[0] - node.start_point[0] >= 2:
        # router.get('/x', async (req, res) => {...}) -> "get /x";
        # .controller('AccountsCtrl', [...]) -> "controller AccountsCtrl"
        call = parent.parent
        fn = call.child_by_field_name("function") if call is not None else None
        label = text[fn.start_byte : fn.end_byte].decode("utf-8", "replace") if fn else ""
        label = label.rsplit(".", 1)[-1] or "callback"
        first = next((c for c in parent.named_children), None)
        if first is not None and first.type == "string" and first is not node:
            value = text[first.start_byte + 1 : first.end_byte - 1].decode("utf-8", "replace")
            return f"{label} {value}"
        return f"{label} callback"
    return None


def ts_units(code: str, path: str) -> list[CodeUnit]:
    """Units of TypeScript or JavaScript code: named functions, methods, and arrow functions
    or function expressions that are assigned or are multi-line callbacks."""
    tsx = not path.lower().endswith((".ts", ".mts", ".cts"))
    data = code.encode("utf-8")
    tree = _parser("tsx" if tsx else "ts").parse(data)
    lines = code.splitlines()
    named: list[tuple[Node, str]] = []
    for node in _walk(tree.root_node, TS_UNITS):
        name = _ts_name(node, data)
        if name is not None:
            named.append((node, name))
    keep = {n.id for n, _ in named}
    out = []
    for node, name in named:
        start, end = node.start_point[0] + 1, node.end_point[0] + 1
        out.append(
            CodeUnit(
                name=name,
                kind="method" if node.type == "method_definition" else "function",
                line=start,
                end_line=end,
                loc=_nonblank(lines, start, end),
                cyclomatic=1 + _ts_decisions(node, keep),
            )
        )
    return sorted(out, key=lambda u: (u.line, u.name))


def _ts_decisions(node: Node, units: set[int]) -> int:
    """Decision points of a unit, not counting those of the units nested in it (anonymous
    one-line callbacks are part of the unit)."""
    count = 0
    stack = list(node.children)
    while stack:
        n = stack.pop()
        if n.id in units or n.type == "class_body":
            continue
        if n.type in TS_DECISIONS or _is_logical(n):
            count += 1
        stack.extend(n.children)
    return count


def tree_units(code: str, language: str) -> list[CodeUnit]:
    """Units of Java or C/C++ code (Pro*C callers pass the text with EXEC SQL blanked)."""
    lang = "java" if language == "java" else ("cpp" if language == "cpp" else "c")
    data = code.encode("utf-8")
    tree = _parser(lang).parse(data)
    unit_kinds = JAVA_UNITS if lang == "java" else C_UNITS
    kinds = JAVA_DECISIONS if lang == "java" else C_DECISIONS
    lines = code.splitlines()
    out = []
    for node in _walk(tree.root_node, unit_kinds):
        start, end = node.start_point[0] + 1, node.end_point[0] + 1
        out.append(
            CodeUnit(
                name=_name(node, data),
                kind="method" if lang == "java" else "function",
                line=start,
                end_line=end,
                loc=_nonblank(lines, start, end),
                cyclomatic=1 + _decisions(node, kinds, unit_kinds),
            )
        )
    return sorted(out, key=lambda u: u.line)


# ---- COBOL ---------------------------------------------------------------------------------
_COBOL_LABEL = re.compile(r"^ {0,3}([A-Z0-9][A-Z0-9-]*)(\s+SECTION)?\s*\.\s*$", re.I)
# COBOL words contain hyphens: "END-IF" is not an IF.
_COBOL_DECISION = re.compile(
    r"(?<![\w-])(?:IF|UNTIL|AND|OR|AT\s+END|INVALID\s+KEY|ON\s+SIZE\s+ERROR|ON\s+EXCEPTION"
    r"|ON\s+OVERFLOW|WHEN(?!\s+OTHER(?![\w-])))(?![\w-])",
    re.I,
)
_COBOL_NOT_LABELS = {"END-IF", "END-EVALUATE", "END-PERFORM", "END-READ", "EXIT", "GOBACK"}
_QUOTED = re.compile(r"'[^']*'|\"[^\"]*\"")


def cobol_units(code_lines: list[str]) -> list[CodeUnit]:
    """Paragraphs and sections of the PROCEDURE DIVISION (``code_lines`` from
    ``cobol.source_lines``: code area only, comments blanked)."""
    start = next(
        (i for i, ln in enumerate(code_lines) if re.search(r"\bPROCEDURE\s+DIVISION\b", ln, re.I)),
        None,
    )
    if start is None:
        return []
    labels: list[tuple[int, str, str]] = []
    for i in range(start + 1, len(code_lines)):
        m = _COBOL_LABEL.match(code_lines[i])
        if m and m.group(1).upper() not in _COBOL_NOT_LABELS:
            labels.append((i, m.group(1).upper(), "section" if m.group(2) else "paragraph"))
    if not labels or any(ln.strip() for ln in code_lines[start + 1 : labels[0][0]]):
        labels.insert(0, (start, "PROCEDURE DIVISION", "paragraph"))
    out = []
    for k, (i, name, kind) in enumerate(labels):
        j = labels[k + 1][0] if k + 1 < len(labels) else len(code_lines)
        body = "\n".join(_QUOTED.sub("''", ln) for ln in code_lines[i + 1 : j])
        out.append(
            CodeUnit(
                name=name,
                kind=kind,
                line=i + 1,
                end_line=j,
                loc=_nonblank(code_lines, i + 1, j),
                cyclomatic=1 + len(_COBOL_DECISION.findall(body)),
            )
        )
    return out


# ---- PL/SQL --------------------------------------------------------------------------------
_PLSQL_UNIT = re.compile(
    r"^\s*(?:CREATE\s+(?:OR\s+REPLACE\s+)?)?(?:(PACKAGE\s+BODY)|(PROCEDURE)|(FUNCTION)|(TRIGGER))"
    r"\s+([\w.$#\"]+)",
    re.I | re.M,
)
# Every loop (LOOP, WHILE .. LOOP, FOR .. LOOP) has one LOOP keyword besides its END LOOP.
_PLSQL_DECISION = re.compile(r"(?<!END\s)\b(?:IF|LOOP)\b|\bELSIF\b|\bWHEN\b", re.I)
_SQL_STRING = re.compile(r"'(?:[^']|'')*'")


def plsql_units(code: str) -> list[CodeUnit]:
    """Procedures, functions, triggers and package bodies; inside a package body each
    procedure/function is its own unit. Plain SQL scripts have none."""
    text = _SQL_STRING.sub("''", code)
    lines = text.splitlines()
    heads = [
        (m, text.count("\n", 0, m.start()) + 1)
        for m in _PLSQL_UNIT.finditer(text)
        if not m.group(1)  # package bodies are containers
    ]
    out = []
    for k, (m, line) in enumerate(heads):
        end = heads[k + 1][1] - 1 if k + 1 < len(heads) else len(lines)
        slash = next((i for i in range(line, end + 1) if lines[i - 1].strip() == "/"), None)
        end = slash - 1 if slash else end
        body = "\n".join(lines[line - 1 : end])
        # A declaration in a package spec has no body ("... RETURN x;" then the next one).
        if not re.search(r"\bBEGIN\b", body, re.I):
            continue
        kind = "procedure" if m.group(2) else ("function" if m.group(3) else "trigger")
        out.append(
            CodeUnit(
                name=m.group(5).strip('"').upper(),
                kind=kind,
                line=line,
                end_line=end,
                loc=_nonblank(lines, line, end),
                cyclomatic=1 + len(_PLSQL_DECISION.findall(body)),
            )
        )
    return out


# ---- Scripts -------------------------------------------------------------------------------
_SCRIPT_DECISION = {
    "shell": re.compile(
        r"\b(?:if|elif|while|until|for)\b|&&|\|\||^\s*[^\s()#]+(?:\|[^\s()]+)*\)\s", re.M
    ),
    "perl": re.compile(r"\b(?:if|elsif|unless|while|until|for|foreach)\b|&&|\|\||\band\b|\bor\b"),
    "python": re.compile(r"\b(?:if|elif|while|for|except)\b|\band\b|\bor\b"),
    "batch": re.compile(r"\b(?:if|for)\b|&&|\|\|", re.I),
}
_SCRIPT_STRING = re.compile(r"'[^'\n]*'|\"(?:[^\"\\\n]|\\.)*\"")


def script_unit(code: str, language: str, name: str) -> list[CodeUnit]:
    rx = _SCRIPT_DECISION.get(language)
    lines = code.splitlines()
    if rx is None or not any(ln.strip() for ln in lines):
        return []
    body = _SCRIPT_STRING.sub('""', code)
    return [
        CodeUnit(
            name=name,
            kind="script",
            line=1,
            end_line=max(len(lines), 1),
            loc=_nonblank(lines, 1, len(lines)),
            cyclomatic=1 + len(rx.findall(body)),
        )
    ]
