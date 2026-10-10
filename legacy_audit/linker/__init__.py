"""Cross-file linking and classification (runs on every scan, after all files are analysed)."""

from legacy_audit.linker.link import LinkResult, classify_components, link

__all__ = ["LinkResult", "classify_components", "link"]
