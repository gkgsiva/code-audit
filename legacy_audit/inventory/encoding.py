"""Encoding detection: UTF-8, EBCDIC (cp037) heuristics, then charset-normalizer."""

from __future__ import annotations

from charset_normalizer import from_bytes

_SAMPLE = 64 * 1024

# EBCDIC (cp037) space, letters and digits.
_EBCDIC_TEXT = (
    {0x40, 0x4B, 0x4D, 0x5D, 0x6B, 0x7D, 0x7E, 0x5E, 0x60, 0x61, 0x25, 0x15}
    | set(range(0xC1, 0xCA))
    | set(range(0xD1, 0xDA))
    | set(range(0xE2, 0xEA))
    | set(range(0x81, 0x8A))
    | set(range(0x91, 0x9A))
    | set(range(0xA2, 0xAA))
    | set(range(0xF0, 0xFA))
)

_LATIN = {"latin_1", "iso8859_1", "iso8859_15", "cp1250", "cp1252", "windows-1252", "cp850"}
_JAPANESE = {"shift_jis", "cp932", "shift_jis_2004", "shift_jisx0213"}


def is_binary(data: bytes) -> bool:
    sample = data[:8192]
    if sample.startswith((b"\xff\xfe", b"\xfe\xff")):
        return False  # UTF-16 text
    return b"\x00" in sample


def _looks_ebcdic(sample: bytes) -> bool:
    if len(sample) < 16:
        return False
    ebcdic = sum(1 for b in sample if b in _EBCDIC_TEXT)
    ascii_letters = sum(1 for b in sample if 0x41 <= b <= 0x5A or 0x61 <= b <= 0x7A)
    return ebcdic / len(sample) > 0.7 and ascii_letters / len(sample) < 0.15


def detect_encoding(data: bytes) -> str:
    """Return a Python codec name for text ``data``."""
    if data.startswith(b"\xef\xbb\xbf"):
        return "utf-8-sig"
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):
        return "utf-16"
    sample = data[:_SAMPLE]
    try:
        sample.decode("utf-8")
        return "utf-8"
    except UnicodeDecodeError as err:
        # A multi-byte character cut by the sample boundary is still UTF-8.
        if err.start >= len(sample) - 3 and len(data) > len(sample):
            return "utf-8"
    if _looks_ebcdic(sample):
        return "cp037"
    best = from_bytes(sample).best()
    name = best.encoding.lower() if best else ""
    if name in _JAPANESE:
        return "shift_jis"
    if name in _LATIN or not name:
        try:
            sample.decode("cp1252")
            return "cp1252"
        except UnicodeDecodeError:
            return "latin-1"
    return name


def decode(data: bytes, encoding: str) -> str:
    return data.decode(encoding, errors="replace")
