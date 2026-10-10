from __future__ import annotations

import re

from legacy_audit.config import GitSource, LoadedConfig
from legacy_audit.source.base import SourceError, SourceRoot
from legacy_audit.source.git import fetch_repo, repo_slug

_NAME_RE = re.compile(r"[^A-Za-z0-9._-]+")


def _unique(name: str, taken: set[str]) -> str:
    base = _NAME_RE.sub("_", name).strip("_") or "source"
    candidate, n = base, 2
    while candidate in taken:
        candidate, n = f"{base}-{n}", n + 1
    taken.add(candidate)
    return candidate


def resolve_sources(loaded: LoadedConfig) -> list[SourceRoot]:
    """Turn the config's ``source`` section into folders on disk (cloning Git repos)."""
    src = loaded.config.source
    roots: list[SourceRoot] = []
    taken: set[str] = set()
    if isinstance(src, GitSource):
        for repo in src.repos:
            path, commit, clean_url = fetch_repo(repo, src.cache_dir)
            name = _unique(repo.name or repo_slug(repo.url)[2], taken)
            roots.append(
                SourceRoot(
                    name=name,
                    path=path,
                    type="git",
                    url=clean_url,
                    ref=repo.ref,
                    commit=commit,
                    subpaths=list(repo.paths),
                )
            )
        return roots
    for p in src.paths:
        path = loaded.resolve(p)
        if not path.is_dir():
            raise SourceError(f"Source folder not found: {path}")
        roots.append(SourceRoot(name=_unique(path.name, taken), path=path))
    return roots
