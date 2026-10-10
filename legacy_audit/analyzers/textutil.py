"""Small text and XML helpers shared by analyzers."""

from __future__ import annotations

import bisect
import re
import xml.etree.ElementTree as ET
from xml.parsers import expat
from collections.abc import Iterator

LINE_ATTR = "__line__"
NS_ATTR = "__ns__"


class LineIndex:
    """Map character offsets to 1-based line numbers."""

    def __init__(self, text: str) -> None:
        self.starts = [0] + [m.end() for m in re.finditer("\n", text)]

    def line(self, offset: int) -> int:
        return bisect.bisect_right(self.starts, offset)


def parse_xml(text: str) -> ET.Element:
    """Parse XML without fetching DTDs; tags lose namespaces, elements carry their line."""
    # Unknown HTML entities (&nbsp;) are common in legacy XML; neutralise them.
    text = re.sub(r"&(?!(amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)", "&amp;", text)
    text = text.lstrip("\ufeff")
    # Drop the declaration (its encoding no longer applies) but keep line numbers.
    text = re.sub(r"^\s*<\?xml[^>]*\?>", lambda m: "\n" * m.group(0).count("\n"), text)
    builder = ET.TreeBuilder()
    parser = expat.ParserCreate(namespace_separator="}")

    def start(tag: str, attributes: dict[str, str]) -> None:
        ns = ""
        if "}" in tag:
            ns, tag = tag.split("}", 1)
        clean = {k.split("}", 1)[-1]: v for k, v in attributes.items()}
        clean[LINE_ATTR] = str(parser.CurrentLineNumber)
        if ns:
            clean[NS_ATTR] = ns
        builder.start(tag, clean)

    def end(tag: str) -> None:
        builder.end(tag.split("}", 1)[-1])

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = builder.data
    try:
        parser.Parse(text, True)
    except expat.ExpatError as err:
        raise ET.ParseError(str(err)) from None
    return builder.close()


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
