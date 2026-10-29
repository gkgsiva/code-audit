"""The admin's common settings applied to every project's ``audit.yaml`` on each load.

Order: the admin ``defaults`` first, the project's own file over them, then every ``locked`` key
put back to the admin's value (or removed, so the built-in default applies). On top of that the
hosted site keeps each project inside its folder: output and inputs stay in the project, a local
source must sit under an allowed source folder, and a Git source clones into the site's cache.
"""

from __future__ import annotations

import copy
from pathlib import Path, PureWindowsPath
from typing import Any

from legacy_audit.config import ConfigError
from legacy_audit.hosted.settings import AdminStore

GIT_SCHEMES = ("https://", "http://", "ssh://", "git@")
MISSING = object()


def deep_merge(base: dict[str, Any], over: dict[str, Any]) -> dict[str, Any]:
    """``over`` wins; mappings merge key by key, anything else (lists too) is replaced."""
    out = copy.deepcopy(base)
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = deep_merge(out[k], v)
        else:
            out[k] = copy.deepcopy(v)
    return out


def get_path(data: dict[str, Any], dotted: str) -> Any:
    node: Any = data
    for part in dotted.split("."):
        if not isinstance(node, dict) or part not in node:
            return MISSING
        node = node[part]
    return node


def set_path(data: dict[str, Any], dotted: str, value: Any) -> None:
    parts = dotted.split(".")
    node = data
    for part in parts[:-1]:
        if not isinstance(node.get(part), dict):
            node[part] = {}
        node = node[part]
    if value is MISSING:
        node.pop(parts[-1], None)
    else:
        node[parts[-1]] = copy.deepcopy(value)


def _inside(path: Path, roots: list[Path]) -> bool:
    return any(path == r or path.is_relative_to(r) for r in roots)


class AdminLayer:
    """Callable registered with ``legacy_audit.config.add_config_layer``."""

    def __init__(self, admin: AdminStore) -> None:
        self.admin = admin

    @property
    def audits_root(self) -> Path:
        return self.admin.host.audits_root.resolve()

    def source_roots(self) -> list[Path]:
        s = self.admin.get()
        roots = [*self.admin.host.sources_roots, *(Path(p) for p in s.paths.extra_source_roots)]
        return [r.resolve() for r in roots]

    def git_cache(self) -> Path:
        s = self.admin.get()
        if s.paths.git_cache_dir:
            return Path(s.paths.git_cache_dir).resolve()
        return (self.admin.host.admin_dir / "git").resolve()

    def __call__(self, data: dict[str, Any], path: Path) -> dict[str, Any]:
        project = path.parent
        if not project.is_relative_to(self.audits_root) or project == self.audits_root:
            return data  # not a hosted project (a CLI run in the same process)
        s = self.admin.get()
        merged = deep_merge(s.defaults, data)
        for key in s.locked:
            set_path(merged, key, get_path(s.defaults, key))
        self._confine(merged, project)
        return merged

    def _confine(self, data: dict[str, Any], project: Path) -> None:
        for key in ("inputs_dir", "output_dir", "overrides_file"):
            value = data.get(key)
            if not isinstance(value, str):
                continue
            p = Path(value.replace("\\", "/"))
            if p.is_absolute() or PureWindowsPath(value).drive or ".." in p.parts:
                raise ConfigError(
                    f"{key}: '{value}' must be a folder inside the project on the hosted site"
                )
        source = data.get("source")
        if not isinstance(source, dict):
            source = {"type": "local", "paths": ["."]}
        if source.get("type", "local") == "git":
            for repo in source.get("repos") or []:
                url = str((repo or {}).get("url", ""))
                if not url.startswith(GIT_SCHEMES):
                    raise ConfigError(
                        f"source.repos: '{url}' must be an https://, ssh:// or git@ address"
                    )
            source["cache_dir"] = str(self.git_cache())
            data["source"] = source
            return
        roots = [*self.source_roots(), project.resolve()]
        for value in source.get("paths") or ["."]:
            raw = str(value).replace("\\", "/")
            p = Path(raw)
            resolved = (p if p.is_absolute() else project / p).resolve()
            if not _inside(resolved, roots):
                allowed = ", ".join(str(r) for r in roots[:-1]) or "(none)"
                raise ConfigError(
                    f"source.paths: '{value}' is outside the folders this site may scan "
                    f"({allowed}, or the project folder)"
                )
