"""Small text and XML helpers shared by analyzers."""

from __future__ import annotations

import bisect
import re
import xml.etree.ElementTree as ET
from collections.abc import Iterator

LINE_ATTR = "__line__"
NS_ATTR = "__ns__"


class LineIndex:
    """Map character offsets to 1-based line numbers."""

    def __init__(self, text: str) -> None:
        self.starts = [0] + [m.end() for m in re.finditer("\n", text)]

    def line(self, offset: int) -> int:
        return bisect.bisect_right(self.starts, offset)


class _Builder(ET.TreeBuilder):
    def __init__(self) -> None:
        super().__init__()
        self.parser: ET.XMLParser | None = None

    def start(self, tag: str, attrs: dict[str, str]) -> ET.Element:
        ns = ""
        if tag.startswith("{"):
            ns, tag = tag[1:].split("}", 1)
        clean = {k.split("}", 1)[-1]: v for k, v in attrs.items()}
        if self.parser is not None:
            clean[LINE_ATTR] = str(self.parser.parser.CurrentLineNumber)
        if ns:
            clean[NS_ATTR] = ns
        return super().start(tag, clean)

    def end(self, tag: str) -> ET.Element:
        return super().end(tag.split("}", 1)[-1])


def parse_xml(text: str) -> ET.Element:
    """Parse XML without fetching DTDs; tags lose namespaces, elements carry their line."""
    builder = _Builder()
    parser = ET.XMLParser(target=builder)
    builder.parser = parser
    # Unknown HTML entities (&nbsp;) are common in legacy XML; neutralise them.
    text = re.sub(r"&(?!(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)", "&amp;", text)
    text = text.lstrip("\ufeff")
    # Drop the declaration (its encoding no longer applies) but keep line numbers.
    text = re.sub(r"^\s*<\?xml[^>]*\?>", lambda m: "\n" * m.group(0).count("\n"), text)
    parser.feed(text)
    return parser.close()


def line_of(el: ET.Element) -> int | None:
    v = el.get(LINE_ATTR)
    return int(v) if v else None


def ns_of(el: ET.Element) -> str:
    return el.get(NS_ATTR, "")


def attrs(el: ET.Element) -> dict[str, str]:
    return {k: v for k, v in el.attrib.items() if k not in (LINE_ATTR, NS_ATTR)}


def text_of(el: ET.Element) -> str:
    return "".join(el.itertext()).strip()


def iter_tag(root: ET.Element, *tags: str) -> Iterator[ET.Element]:
    wanted = set(tags)
    for el in root.iter():
        if el.tag in wanted:
            yield el


def child_text(el: ET.Element, tag: str) -> str | None:
    c = el.find(tag)
    return text_of(c) if c is not None else None


def basename(path: str) -> str:
    return re.split(r"[\\/]", path.rstrip("/\\"))[-1]


def stem(path: str) -> str:
    b = basename(path)
    return b.rsplit(".", 1)[0] if "." in b[1:] else b
