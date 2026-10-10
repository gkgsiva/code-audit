"""Optional Git source, using a local bare repository as the remote."""

import subprocess
from pathlib import Path

import pytest
import yaml

from legacy_audit.config import GitRepo, load_config
from legacy_audit.scan import run_scan
from legacy_audit.source.git import _git_env, clone_dir, repo_slug, strip_credentials
from tests.conftest import invoke, write

TOKEN = "ghp_SECRET_token_value_123456"


def git(*args: str, cwd: Path) -> str:
    return subprocess.run(
        ["git", "-c", "user.name=t", "-c", "user.email=t@example.com", *args],
        cwd=cwd,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


@pytest.fixture
def bare_repo(tmp_path: Path) -> tuple[Path, Path]:
    work = tmp_path / "work"
    write(work / "app" / "build.xml", "<project name='a'/>\n")
    write(work / "app" / "src" / "A.java", "class A {}\n")
    write(work / "batch" / "run.ksh", "#!/bin/ksh\necho run\n")
    git("init", "-q", "-b", "main", cwd=work)
    git("add", ".", cwd=work)
    git("commit", "-q", "-m", "first", cwd=work)
    bare = tmp_path / "remote" / "team" / "orders.git"
    bare.parent.mkdir(parents=True)
    git("clone", "-q", "--bare", str(work), str(bare), cwd=tmp_path)
    return work, bare


def _config(tmp_path: Path, url: str, **repo) -> Path:
    cfg = {
        "application": {"name": "git-sample"},
        "source": {
            "type": "git",
            "repos": [{"url": url, "ref": "main", "token_env": "GIT_TOKEN", **repo}],
            "cache_dir": str(tmp_path / "clone-cache"),
        },
        "output_dir": str(tmp_path / "ws" / "audit-output"),
    }
    return write(tmp_path / "ws" / "audit.yaml", yaml.safe_dump(cfg))


def _all_text(*roots: Path) -> str:
    chunks = []
    for root in roots:
        for p in root.rglob("*"):
            if p.is_file():
                chunks.append(p.read_bytes().decode("utf-8", errors="replace"))
    return "\n".join(chunks)


def test_git_clone_scan_records_sha_and_hides_token(tmp_path, bare_repo, monkeypatch, runner):
    work, bare = bare_repo
    monkeypatch.setenv("GIT_TOKEN", TOKEN)
    cfg = _config(tmp_path, str(bare))
    result = invoke(runner, "scan", "--config", str(cfg), "--verbose")
    assert result.exit_code == 0, result.output
    assert TOKEN not in result.output

    loaded = load_config(cfg)
    run_dir = loaded.output_dir / "latest"
    import json

    run = json.loads((run_dir / "run.json").read_text())
    sha = git("rev-parse", "HEAD", cwd=work)
    assert run["sources"][0]["commit"] == sha
    assert run["sources"][0]["type"] == "git"
    inv = json.loads((run_dir / "data" / "inventory.json").read_text())
    assert sorted(r["path"] for r in inv) == ["app/build.xml", "app/src/A.java", "batch/run.ksh"]

    # The token must not appear in any output, log or the clone's git config.
    assert TOKEN not in _all_text(loaded.output_dir, tmp_path / "clone-cache")

    # Clone lives in the cache, laid out as <host>/<owner>/<repo>@<ref>.
    clone = tmp_path / "clone-cache" / "local" / "team" / "orders@main"
    assert (clone / ".git").is_dir()

    # A new commit is fetched on re-run and its SHA recorded.
    write(work / "batch" / "two.ksh", "echo two\n")
    git("add", ".", cwd=work)
    git("commit", "-q", "-m", "second", cwd=work)
    git("push", "-q", str(bare), "main", cwd=work)
    second = run_scan(loaded)
    assert second.run.sources[0].commit == git("rev-parse", "HEAD", cwd=work)
    assert second.run.files_analyzed == 1


def test_git_subpaths(tmp_path, bare_repo):
    _, bare = bare_repo
    cfg = _config(tmp_path, str(bare), paths=["batch"])
    result = run_scan(load_config(cfg))
    assert [r.path for r in result.facts["inventory"]] == ["batch/run.ksh"]  # type: ignore[attr-defined]


def test_credentials_in_url_never_stored():
    url = f"https://user:{TOKEN}@git.example.com/team/orders.git"
    assert TOKEN not in strip_credentials(url)
    assert repo_slug(url) == ("git.example.com", "team", "orders")


def test_git_failure_is_a_clean_error(tmp_path, runner, monkeypatch):
    monkeypatch.setenv("GIT_TOKEN", TOKEN)
    cfg = _config(tmp_path, str(tmp_path / "does-not-exist.git"))
    result = invoke(runner, "scan", "--config", str(cfg))
    assert result.exit_code == 1
    assert "git fetch failed" in result.output
    assert TOKEN not in result.output


def test_token_passed_as_header_not_argument(monkeypatch):
    env = _git_env(TOKEN)
    assert env["GIT_CONFIG_KEY_0"] == "http.extraHeader"
    assert TOKEN not in env["GIT_CONFIG_VALUE_0"]  # base64 encoded, never the raw value
    assert env["GIT_TERMINAL_PROMPT"] == "0"


@pytest.mark.parametrize(
    ("url", "slug"),
    [
        ("https://github.com/acme/billing.git", ("github.com", "acme", "billing")),
        ("git@gitlab.example.com:group/sub/app.git", ("gitlab.example.com", "sub", "app")),
        ("https://dev.azure.com/org/proj/_git/repo", ("dev.azure.com", "_git", "repo")),
        ("C:\\repos\\legacy\\orders.git", ("local", "legacy", "orders")),
        ("file:///srv/git/orders.git", ("local", "git", "orders")),
    ],
)
def test_repo_slug(url, slug):
    assert repo_slug(url) == slug


def test_clone_dir_is_filesystem_safe(tmp_path):
    d = clone_dir(GitRepo(url="https://h:8443/o/r.git", ref="feature/x"), tmp_path)
    assert d == tmp_path / "h" / "o" / "r@feature_x"


def test_purge_cache(runner, tmp_path):
    cache = tmp_path / "c"
    write(cache / "local" / "o" / "r@main" / "f.txt", "x")
    result = invoke(runner, "purge-cache", "--cache-dir", str(cache))
    assert result.exit_code == 0
    assert not cache.exists()
