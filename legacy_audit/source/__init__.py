"""Source roots: local folders (default) or Git repositories cloned into a cache."""

from legacy_audit.source.base import SourceError, SourceRoot
from legacy_audit.source.resolve import resolve_sources

__all__ = ["SourceError", "SourceRoot", "resolve_sources"]
