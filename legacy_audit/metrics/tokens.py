"""Comment stripping and token windows for duplication detection.

A window is ``window`` consecutive tokens starting at the first token of a line. Copy-pasted
blocks start at a line in both copies, so hashing one window per line finds them while storing
at most one hash per line in the file cache.
"""

from __future__ import annotations

import hashlib
import re

from legacy_audit.inventory.loc import SYNTAX, Syntax

_TOKEN = re.compile(r"[A-Za-z_$#@][\w$#@-]*|\d[\w.]*|'(?:[^'\n]|'')*'|\"(?:[^\"\\\n]|\\.)*\"|\S")


def strip_comments(text: str, language: str) -> str:
    """Comments replaced by spaces; line breaks kept so line numbers stay valid."""
    syn = SYNTAX.get(language)
    if syn is None:
        return text
    return _strip(text, syn)


def _strip(text: str, syn: Syntax) -> str:
    out: list[str] = []
    i, n = 0, len(text)
    quote: str | None = None
    while i < n:
        ch = text[i]
        if quote is not None:
            out.append(ch)
            if ch == "\\" and i + 1 < n and text[i + 1] != "\n":
                out.append(text[i + 1])
                i += 2
                continue
            if ch == quote or ch == "\n":
                quote = None
            i += 1
            continue
        if ch in "\"'":
            quote = ch
            out.append(ch)
            i += 1
            continue
        matched = False
        for start, end in syn.block:
            if text.startswith(start, i):
                j = text.find(end, i + len(start))
                j = n if j < 0 else j + len(end)
                out.append(re.sub(r"[^\n]", " ", text[i:j]))
                i, matched = j, True
                break
        if matched:
            continue
        for marker in syn.line:
            if text.startswith(marker, i):
                j = text.find("\n", i)
                j = n if j < 0 else j
                out.append(" " * (j - i))
                i, matched = j, True
                break
        if matched:
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _hash(tokens: list[str]) -> int:
    digest = hashlib.blake2b("\x1f".join(tokens).encode("utf-8"), digest_size=8).digest()
    return int.from_bytes(digest, "big") >> 1  # 63 bits: fits SQLite and JSON readers


def windows(code: str, window: int) -> list[list[int]]:
    """``[hash, first line, last line]`` for each window of ``window`` tokens that starts at
    the first token of a line."""
    tokens: list[str] = []
    lines: list[int] = []
    starts: list[int] = []
    for no, line in enumerate(code.splitlines(), 1):
        found = _TOKEN.findall(line)
        if not found:
            continue
        starts.append(len(tokens))
        tokens.extend(found)
        lines.extend([no] * len(found))
    out: list[list[int]] = []
    for s in starts:
        e = s + window
        if e > len(tokens):
            break
        out.append([_hash(tokens[s:e]), lines[s], lines[e - 1]])
    return out
