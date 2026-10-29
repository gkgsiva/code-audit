"""The hosted site: login, projects under the audits root, roles, admin settings and defaults,
report switches, the shared queue with time left, status and logs pages."""

from __future__ import annotations

import shutil
import threading
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from legacy_audit.config import ConfigError, load_config
from legacy_audit.gui.jobs import Gate, Job, Jobs
from legacy_audit.hosted.app import create_site
from legacy_audit.hosted.auth import Sessions
from legacy_audit.hosted.layer import AdminLayer, deep_merge
from legacy_audit.hosted.settings import AdminStore, HostSettings, SettingsError
from legacy_audit.security import vulns

SAMPLE = Path(__file__).parents[1] / "samples" / "struts-oracle-app" / "orders-web"
H = {"X-Legacy-Audit": "1"}
BASE = "http://audit.example.com"


@pytest.fixture(autouse=True)
def _no_security_tools(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)


@pytest.fixture
def site(tmp_path: Path) -> Iterator[Any]:
    sources = tmp_path / "sources"
    shutil.copytree(SAMPLE, sources / "orders-web")
    settings = HostSettings(
        audits_root=tmp_path / "audits",
        sources_roots=[sources],
        admins=["boss"],
        session_secret="test-secret",
        public_url=BASE,
    )
    app = create_site(settings)
    yield app
    app.close()


def _client(app: Any, user: str | None = None) -> TestClient:
    c = TestClient(app, base_url=BASE)
    if user:
        r = c.post("/auth/login", json={"name": user}, headers=H)
        assert r.status_code == 200, r.text
    return c


def _ok(resp: Any) -> Any:
    assert resp.status_code == 200, resp.text
    return resp.json()


def _create(c: TestClient, title: str = "Orders", **extra: Any) -> dict[str, Any]:
    body = {"title": title, "source": {"type": "local", "path": extra.pop("path", None)}, **extra}
    return _ok(c.post("/api/projects", json=body, headers=H))  # type: ignore[no-any-return]


def _wait_job(app: Any, project: str, timeout: float = 600) -> Job:
    jobs = app.site.project_app(project).state.jobs
    job = jobs.wait(timeout)
    assert job is not None
    return job  # type: ignore[no-any-return]


# ---- settings, sessions, layer ---------------------------------------------------------------


def test_settings_from_environment() -> None:
    s = HostSettings.from_env(
        {
            "LEGACY_AUDIT_AUDITS_ROOT": "/data/audits",
            "LEGACY_AUDIT_SOURCES_ROOT": "/src/a; /src/b",
            "LEGACY_AUDIT_PORT": "9000",
            "LEGACY_AUDIT_ADMINS": "siva,ops",
            "LEGACY_AUDIT_MAX_SCANS": "3",
            "LEGACY_AUDIT_PUBLIC_URL": "https://audit.corp",
        }
    )
    assert s.audits_root == Path("/data/audits")
    assert s.sources_roots == [Path("/src/a"), Path("/src/b")]
    assert s.port == 9000 and s.admins == ["siva", "ops"] and s.max_scans == 3
    assert s.secure_cookies and "audit.corp" in (s.allowed_hosts() or set())
    assert s.env_locked() == {"limits.max_scans": 3}
    with pytest.raises(SettingsError, match="PORT"):
        HostSettings.from_env({"LEGACY_AUDIT_PORT": "many"})
    with pytest.raises(SettingsError, match="okta"):
        create_site(HostSettings(audits_root=Path("/tmp/x"), auth="okta"))


def test_audits_folder_that_cannot_be_written_stops_with_what_to_do(tmp_path: Path) -> None:
    # A file where the folder should be fails on every platform, also when running as root.
    blocked = tmp_path / "audits"
    blocked.write_text("not a folder", encoding="utf-8")
    with pytest.raises(SettingsError, match=r"cannot be written.*AUDIT_UID"):
        create_site(HostSettings(audits_root=blocked))


def test_sessions_are_signed_and_expire() -> None:
    s = Sessions("k", 1)
    token = s.issue("siva")
    assert s.read(token) == "siva"
    assert s.read(token[:-2] + "xx") is None
    assert Sessions("other", 1).read(token) is None
    assert Sessions("k", -1).read(Sessions("k", -1).issue("siva")) is None
    assert s.read(None) is None and s.read("garbage") is None


def test_layer_merges_defaults_locks_and_confines(tmp_path: Path) -> None:
    host = HostSettings(audits_root=tmp_path / "audits", sources_roots=[tmp_path / "src"])
    (tmp_path / "src" / "app").mkdir(parents=True)
    admin = AdminStore(host)
    admin.save(
        {
            "defaults": {
                "target": {"profile": "wildfly-gke"},
                "sql": {"dialect": "db2"},
                "security": {"osv_scanner": {"offline": True}},
            },
            "locked": ["security.osv_scanner", "inventory.workers"],
        }
    )
    layer = AdminLayer(admin)
    project = tmp_path / "audits" / "p1"
    project.mkdir(parents=True)
    raw = {
        "application": {"name": "x"},
        "source": {"type": "local", "paths": [str(tmp_path / "src" / "app")]},
        "sql": {"dialect": "oracle"},
        "security": {"osv_scanner": {"offline": False, "enabled": False}},
        "inventory": {"workers": 4},
    }
    out = layer(raw, project / "audit.yaml")
    assert out["target"]["profile"] == "wildfly-gke"  # inherited
    assert out["sql"]["dialect"] == "oracle"  # the project's own value wins
    assert out["security"]["osv_scanner"] == {"offline": True}  # locked: the admin's
    assert "workers" not in out["inventory"]  # locked with no default: built-in
    # outside the allowed folders, or output outside the project
    with pytest.raises(ConfigError, match="outside the folders"):
        layer({**raw, "source": {"paths": ["/etc"]}}, project / "audit.yaml")
    with pytest.raises(ConfigError, match="inside the project"):
        layer({**raw, "output_dir": "../elsewhere"}, project / "audit.yaml")
    with pytest.raises(ConfigError, match="https"):
        layer(
            {**raw, "source": {"type": "git", "repos": [{"url": "/srv/repo"}]}},
            project / "audit.yaml",
        )
    git = layer(
        {**raw, "source": {"type": "git", "repos": [{"url": "https://git/x.git"}]}},
        project / "audit.yaml",
    )
    assert git["source"]["cache_dir"].endswith("git")
    # a config outside the audits root is left alone
    assert layer(raw, tmp_path / "elsewhere" / "audit.yaml") is raw
    # defaults are checked as audit.yaml settings
    with pytest.raises(SettingsError, match="unknown key"):
        admin.save({"defaults": {"nonsense": 1}})
    with pytest.raises(SettingsError, match="per project"):
        admin.save({"defaults": {"source": {"paths": ["."]}}})
    assert deep_merge({"a": {"b": 1, "c": [1]}}, {"a": {"c": [2]}}) == {"a": {"b": 1, "c": [2]}}


def test_gate_runs_n_at_once_in_order() -> None:
    gate = Gate(lambda: 1)
    jobs = [Jobs(gate) for _ in range(3)]
    release = threading.Event()
    order: list[str] = []

    def work(name: str) -> Any:
        def run(job: Job) -> dict[str, Any]:
            order.append(name)
            release.wait(10)
            return {}

        return run

    started = [j.start("scan", f"job{i}", work(f"job{i}")) for i, j in enumerate(jobs)]
    deadline = time.monotonic() + 5
    while started[2].queue_position != 2 and time.monotonic() < deadline:
        time.sleep(0.05)
    assert started[0].state == "running"
    assert started[1].state == "queued" and started[1].queue_position == 1
    assert started[2].queue_position == 2
    info = started[1].info()
    assert info["state"] == "queued" and info["elapsed_seconds"] is None
    release.set()
    for j in jobs:
        j.wait(10)
    assert order == ["job0", "job1", "job2"]
    assert all(j.state == "done" for j in started)


def test_estimate_uses_last_run_then_pace() -> None:
    job = Job("scan", "Re-run", expected_seconds=100)
    job._began = time.monotonic() - 25
    est = job.estimate()
    assert est["eta_basis"] == "last run" and 70 <= est["eta_seconds"] <= 76
    assert 24 <= est["percent"] <= 26
    pace = Job("scan", "Full rescan")
    pace._began = time.monotonic() - 10
    pace.emit("progress", stage="analysis", n=0, total=100)
    pace._stage = ("analysis", time.monotonic() - 10, 25, 100)
    est = pace.estimate()
    assert est["eta_basis"] == "pace of the analysis step"
    assert 28 <= est["eta_seconds"] <= 32 and est["stage_percent"] == 25.0


# ---- the site --------------------------------------------------------------------------------


def test_login_required_and_dummy_login(site: Any) -> None:
    anon = _client(site)
    assert anon.get("/healthz").json()["ok"] is True
    r = anon.get("/", follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/login"
    assert anon.get("/api/projects").status_code == 401
    assert anon.get("/api/auth").json()["provider"] == "dummy"
    # writes need the header, and a foreign Host is refused
    assert anon.post("/auth/login", json={"name": "x"}).status_code == 403
    assert anon.get("/api/auth", headers={"Host": "evil.example"}).status_code == 403
    c = _client(site, "siva")
    me = _ok(c.get("/api/me"))
    assert me["name"] == "siva" and me["role"] == "owner" and me["can_create"]
    assert "Test login" in me["banner"]
    assert _ok(_client(site, "boss").get("/api/me"))["role"] == "admin"
    assert c.get("/", follow_redirects=False).status_code == 200


def test_create_open_scan_and_report_as_owner(site: Any, tmp_path: Path) -> None:
    c = _client(site, "siva")
    src = _ok(c.get("/api/sources"))
    root = src["folders"][0]["path"]
    sub = _ok(c.get("/api/sources", params={"path": root}))
    assert [f["name"] for f in sub["folders"]] == ["orders-web"]
    assert c.get("/api/sources", params={"path": "/etc"}).status_code == 404
    # a folder outside the sources roots is refused and nothing is left behind
    bad = c.post(
        "/api/projects",
        json={"title": "Bad", "source": {"type": "local", "path": "/etc"}},
        headers=H,
    )
    assert bad.status_code == 422 and not (tmp_path / "audits" / "bad").exists()
    p = _create(c, path=sub["folders"][0]["path"], description="Struts")
    assert p["id"] == "orders" and p["owner"] == "siva" and p["role"] == "owner"
    assert (
        c.post(
            "/api/projects",
            json={"title": "Orders", "source": {"type": "local", "path": root}},
            headers=H,
        ).status_code
        == 409
    )
    # the project GUI under /p/orders/
    assert c.get("/p/orders", follow_redirects=False).status_code == 307
    page = c.get("/p/orders/")
    assert page.status_code == 200 and 'src="static/app.js"' in page.text
    assert c.get("/p/orders/static/app.js").status_code == 200
    ws = _ok(c.get("/p/orders/api/workspace"))
    assert ws["hosted"]["user"] == {"name": "siva", "role": "owner"}
    assert ws["folder"] == "orders" and ws["output_dir"] == "audit-output"
    assert c.post("/p/orders/api/open", json={"target": "output"}, headers=H).status_code == 400
    # scan through the queue, status shows it, the log is readable
    job = _ok(c.post("/p/orders/api/scan", json={"full": False}, headers=H))
    assert job["project"] == "orders" and job["user"] == "siva"
    done = _wait_job(site, "orders")
    assert done.state == "done", done.error
    status = _ok(c.get("/api/status"))
    assert status["jobs"][0]["project"] == "orders" and status["jobs"][0]["state"] == "done"
    assert status["slots"] == 2
    logs = _ok(c.get("/api/logs"))
    assert logs and logs[0]["project"] == "orders" and logs[0]["id"].endswith("scan.log")
    view = _ok(c.get("/api/logs/view", params={"id": logs[0]["id"]}))
    assert view["total"] > 0
    assert c.get("/api/logs/view", params={"id": "server/server.log"}).status_code == 403
    assert c.get("/api/logs/download", params={"id": logs[0]["id"]}).status_code == 200
    # reports, with the admin switching the technical report off
    boss = _client(site, "boss")
    settings = _ok(boss.get("/api/admin"))["settings"]
    settings["reports"]["enabled"]["tech"] = False
    settings["reports"]["summary_sections"]["security"] = False
    _ok(boss.put("/api/admin", json=settings, headers=H))
    assert (
        c.post("/p/orders/api/runs/latest/reports", json={"type": "tech"}, headers=H).status_code
        == 403
    )
    _ok(c.post("/p/orders/api/runs/latest/reports", json={"type": "summary"}, headers=H))
    assert _wait_job(site, "orders").state == "done"
    reports = _ok(c.get("/p/orders/api/runs/latest/reports"))
    assert "tech" not in reports["reports"] and "pdf_tech" not in reports["reports"]
    url = reports["reports"]["summary"]
    assert url.startswith("runs/")
    html = c.get("/p/orders/" + url)
    assert html.status_code == 200 and "Dependency vulnerabilities" not in html.text
    assert "Integrations" in html.text
    # a second scan computes its time left from the first
    _ok(c.post("/p/orders/api/scan", json={"full": False}, headers=H))
    info = _ok(c.get("/p/orders/api/jobs/current"))
    assert info["expected_seconds"] is not None
    _wait_job(site, "orders")
    activity = _ok(boss.get("/api/admin/activity"))
    actions = [e["action"] for e in activity]
    assert "create project" in actions and "scan" in actions and "build reports" in actions
    assert boss.get("/api/admin/activity.csv").text.startswith("time,user,action")
    health = _ok(boss.get("/api/admin/health"))
    assert health["projects"][0]["project"] == "orders" and health["checks"]


def test_roles_and_project_access(site: Any) -> None:
    owner = _client(site, "siva")
    root = _ok(owner.get("/api/sources"))["folders"][0]["path"]
    _create(owner, path=root + "/orders-web")
    viewer = _client(site, "vera")
    helper = _client(site, "carl")
    # not a member: the project is invisible
    assert _ok(viewer.get("/api/projects")) == []
    assert viewer.get("/p/orders/api/workspace").status_code == 404
    members = {"vera": "viewer", "carl": "contributor"}
    _ok(owner.put("/api/projects/orders", json={"members": members}, headers=H))
    assert _ok(viewer.get("/api/projects"))[0]["role"] == "viewer"
    assert viewer.get("/p/orders/api/workspace").status_code == 200
    assert viewer.post("/p/orders/api/scan", json={}, headers=H).status_code == 403
    assert viewer.put("/api/projects/orders", json={"title": "x"}, headers=H).status_code == 403
    # a contributor scans but cannot edit audit.yaml
    assert (
        helper.put("/p/orders/api/config/text", json={"text": "x: 1"}, headers=H).status_code == 403
    )
    assert helper.post("/p/orders/api/scan", json={}, headers=H).status_code == 200
    _wait_job(site, "orders")
    # cross-site writes into a project are refused
    evil = {**H, "Origin": "http://evil.example"}
    assert owner.post("/p/orders/api/scan", json={}, headers=evil).status_code == 403
    assert owner.post("/p/orders/api/scan", json={}).status_code == 403
    # the owner hides the data pack; archived projects are read-only
    _ok(owner.put("/api/projects/orders", json={"hidden_reports": ["datapack"]}, headers=H))
    assert owner.get("/p/orders/api/runs/latest/datapack.zip").status_code == 403
    _ok(owner.put("/api/projects/orders", json={"archived": True}, headers=H))
    assert helper.post("/p/orders/api/scan", json={}, headers=H).status_code == 403
    # only admins delete, with the folder name typed
    boss = _client(site, "boss")
    assert (
        owner.post("/api/projects/orders/delete", json={"confirm": "orders"}, headers=H).status_code
        == 403
    )
    assert (
        boss.post("/api/projects/orders/delete", json={"confirm": "nope"}, headers=H).status_code
        == 400
    )
    _ok(boss.post("/api/projects/orders/delete", json={"confirm": "orders"}, headers=H))
    assert _ok(owner.get("/api/projects")) == []


def test_admin_users_signup_and_defaults(site: Any) -> None:
    boss = _client(site, "boss")
    assert _client(site, "siva").get("/api/admin").status_code == 403
    a = _ok(boss.get("/api/admin"))
    assert a["host"]["auth"] == "dummy" and a["settings"]["limits"]["max_scans"] == 2
    s = a["settings"]
    s["users"]["open_signup"] = False
    s["users"]["who_can_create"] = "admins"
    _ok(boss.put("/api/admin", json=s, headers=H))
    stranger = TestClient(site, base_url=BASE)
    assert stranger.post("/auth/login", json={"name": "nobody"}, headers=H).status_code == 403
    _ok(boss.put("/api/admin/users", json={"name": "ann", "role": "viewer"}, headers=H))
    ann = _client(site, "ann")
    assert _ok(ann.get("/api/me"))["can_create"] is False
    assert _ok(_client(site, "siva").get("/api/me"))["can_create"] is False  # admins only now
    _ok(
        boss.put(
            "/api/admin/users", json={"name": "ann", "role": "viewer", "disabled": True}, headers=H
        )
    )
    assert ann.get("/api/me").status_code == 401  # a disabled user's session stops working
    assert (
        boss.put("/api/admin/users", json={"name": "boss", "role": "viewer"}, headers=H).status_code
        == 400
    )
    users = {u["name"]: u for u in _ok(boss.get("/api/admin/users"))}
    assert users["boss"]["fixed"] and users["ann"]["disabled"]
    # defaults: YAML editor, validated
    yaml_text = "target:\n  profile: none\nsql:\n  dialect: db2\n"
    out = _ok(boss.put("/api/admin/defaults", json={"text": yaml_text}, headers=H))
    assert out["settings"]["defaults"]["target"]["profile"] == "none"
    assert "dialect: db2" in out["defaults_yaml"]
    bad = boss.put("/api/admin/defaults", json={"text": "target:\n  profile: mars\n"}, headers=H)
    assert bad.status_code == 422
    # a project created now inherits the defaults on load
    root = _ok(boss.get("/api/sources"))["folders"][0]["path"]
    p = _create(boss, path=root + "/orders-web")
    cfg = load_config(site.site.host.audits_root / p["id"] / "audit.yaml").config
    assert cfg.target.profile == "none" and cfg.sql.dialect == "db2"
