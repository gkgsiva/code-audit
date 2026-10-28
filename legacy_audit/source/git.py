"""Optional Git source: shallow clone into a cache outside the workspace.

The token is read from the environment variable named in the config and handed to git as an
HTTP header through ``GIT_CONFIG_*`` environment variables, so it never appears in a command
line, a remote URL, ``.git/config``, a log line or an output file.
"""

from __future__ import annotations

import base64
import logging
import os
import re
import shutil
import subprocess
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

from legacy_audit.config import GitRepo
from legacy_audit.source.base import SourceError

log = logging.getLogger(__name__)

_SAFE = re.compile(r"[^A-Za-z0-9._-]+")


def default_cache_dir() -> Path:
    env = os.environ.get("LEGACY_AUDIT_CACHE")
    return Path(env) if env else Path.home() / ".legacy-audit" / "cache"


def strip_credentials(url: str) -> str:
    """Remove any user:password@ part from an http(s) URL."""
    parts = urlsplit(url)
    if parts.scheme in ("http", "https") and "@" in parts.netloc:
        host = parts.netloc.rsplit("@", 1)[1]
        return urlunsplit((parts.scheme, host, parts.path, parts.query, parts.fragment))
    return url


def repo_slug(url: str) -> tuple[str, str, str]:
    """(host, owner, repo) for the cache folder; local paths use host 'local'."""
    url = strip_credentials(url)
    scp = re.match(r"^[\w.-]+@([\w.-]+):(.+)$", url)
    if scp:
        host, path = scp.group(1), scp.group(2)
    else:
        parts = urlsplit(url)
        if parts.scheme in ("http", "https", "ssh", "git"):
            host, path = parts.hostname or "unknown", parts.path
        else:
            host, path = "local", url[7:] if url.startswith("file://") else url
    segments = [s for s in path.replace("\\", "/").split("/") if s and not s.endswith(":")]
    repo = segments[-1] if segments else "repo"
    repo = repo[:-4] if repo.endswith(".git") else repo
    owner = segments[-2] if len(segments) > 1 else "_"
    return _SAFE.sub("_", host), _SAFE.sub("_", owner), _SAFE.sub("_", repo) or "repo"


def clone_dir(repo: GitRepo, cache_dir: Path) -> Path:
    host, owner, name = repo_slug(repo.url)
    ref = _SAFE.sub("_", repo.ref) if repo.ref else "HEAD"
    return cache_dir / host / owner / f"{name}@{ref}"


def _git_env(token: str | None) -> dict[str, str]:
    env = dict(os.environ)
    env["GIT_TERMINAL_PROMPT"] = "0"
    if token:
        basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
        env["GIT_CONFIG_COUNT"] = "1"
        env["GIT_CONFIG_KEY_0"] = "http.extraHeader"
        env["GIT_CONFIG_VALUE_0"] = f"Authorization: Basic {basic}"
    return env


def _mask(text: str, secrets: list[str]) -> str:
    for s in secrets:
        if s:
            text = text.replace(s, "****")
    return text


def _run(args: list[str], cwd: Path, env: dict[str, str], secrets: list[str]) -> str:
    try:
        proc = subprocess.run(
            ["git", *args], cwd=cwd, env=env, capture_output=True, text=True, check=False
        )
    except FileNotFoundError:
        raise SourceError("git is not installed or not on PATH") from None
    if proc.returncode != 0:
        msg = _mask(proc.stderr.strip() or proc.stdout.strip(), secrets)
        raise SourceError(f"git {args[0]} failed: {msg}")
    return proc.stdout.strip()


def fetch_repo(repo: GitRepo, cache_dir: str | None) -> tuple[Path, str, str]:
    """Clone or update ``repo`` in the cache. Returns (folder, commit SHA, clean URL)."""
    token = os.environ.get(repo.token_env) if repo.token_env else None
    if repo.token_env and not token:
        log.warning("Environment variable %s is not set; cloning without a token", repo.token_env)
    url = strip_credentials(repo.url)
    if url != repo.url:
        log.warning("Credentials in the repository URL were ignored; use token_env instead")
    secrets = [s for s in (token, repo.url if url != repo.url else None) if s]
    # A local path (bare repo fixture, network share) is passed to git as an absolute path.
    if urlsplit(url).scheme == "" and not re.match(r"^[\w.-]+@", url):
        url = str(Path(url).expanduser().resolve())
    dest = clone_dir(repo, Path(cache_dir) if cache_dir else default_cache_dir())
    env = _git_env(token)
    ref = repo.ref or "HEAD"

    if not (dest / ".git").is_dir():
        if dest.exists():
            shutil.rmtree(dest)
        dest.mkdir(parents=True)
        _run(["init", "-q"], dest, env, secrets)
        _run(["remote", "add", "origin", url], dest, env, secrets)
        log.info("Cloning %s (%s) into %s", url, ref, dest)
    else:
        _run(["remote", "set-url", "origin", url], dest, env, secrets)
        log.info("Fetching %s (%s) in %s", url, ref, dest)
    _run(["fetch", "-q", "--depth", "1", "origin", ref], dest, env, secrets)
    _run(["checkout", "-q", "-f", "FETCH_HEAD"], dest, env, secrets)
    _run(["clean", "-q", "-fdx"], dest, env, secrets)
    commit = _run(["rev-parse", "HEAD"], dest, env, secrets)
    return dest, commit, url


def purge_cache(cache_dir: Path | None = None) -> Path:
    target = cache_dir or default_cache_dir()
    if target.exists():
        shutil.rmtree(target)
    return target
