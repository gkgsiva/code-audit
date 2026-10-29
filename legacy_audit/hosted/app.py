"""The hosted site: login, the project list, status, logs and admin pages, and each project's
GUI under ``/p/<project>/``.

``create_site`` returns an ASGI app. Requests under ``/p/<project>/`` are checked here (login,
host, origin, the user's role on that project) and handed to that project's GUI app (the same
app ``legacy-audit serve`` runs, with ``HostedHooks``); everything else is the portal (FastAPI).

Roles: admin (everything), owner (creates projects, runs and manages their own), contributor
(scans and builds reports on projects they are added to), viewer (reads results and reports).
"""

from __future__ import annotations

import csv
import io
import json
import logging
import logging.handlers
import os
import re
import shutil
from collections import deque
from collections.abc import Awaitable, Callable, MutableMapping
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any
from urllib.parse import quote, urlsplit

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import (
    FileResponse,
    HTMLResponse,
    JSONResponse,
    PlainTextResponse,
    RedirectResponse,
    Response,
)
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from legacy_audit import __version__
from legacy_audit.config import ConfigError, add_config_layer, load_config
from legacy_audit.gui.app import HEADER, VENDOR, HostedHooks, create_app
from legacy_audit.gui.app import STATIC as GUI_STATIC
from legacy_audit.gui.jobs import Gate, Job
from legacy_audit.hosted.auth import COOKIE, AuthError, Sessions, provider_for
from legacy_audit.hosted.layer import AdminLayer
from legacy_audit.hosted.metrics import Sampler, system, usage
from legacy_audit.hosted.registry import (
    PROJECT_ID,
    RANK,
    ProjectMeta,
    Registry,
    User,
    slugify,
)
from legacy_audit.hosted.settings import (
    REPORTS,
    ROLES,
    SUMMARY_SECTIONS,
    AdminStore,
    HostSettings,
    LimitSettings,
    SettingsError,
)

log = logging.getLogger("legacy_audit.hosted")

HERE = Path(__file__).resolve().parent
STATIC = HERE / "static"
PROJECT_PATH = re.compile(r"^/p/([^/]+)(/.*)?$")
# A hosted project's audit.yaml holds only what is its own; everything else comes from the
# admin's project defaults on each load, so a later change of a default reaches every project.
PROJECT_YAML = """\
# legacy-audit project on the hosted site. Settings not written here come from the site's
# project defaults (admin page); add a section to change one for this project only. Locked
# settings always come from the admin.

application:
  name: {name}
  # description: "Customer billing (Struts 1 + Oracle)"

source:
  type: local
  paths:
    - "."
"""
JOB_HISTORY = 200
LOG_TAIL = 2000
Scope = MutableMapping[str, Any]
Receive = Callable[[], Awaitable[MutableMapping[str, Any]]]
Send = Callable[[MutableMapping[str, Any]], Awaitable[None]]

# project GUI writes and the role they need there (anything not listed: contributor)
OWNER_WRITES = (
    re.compile(r"^/api/config/"),
    re.compile(r"^/api/components$"),
    re.compile(r"^/api/init$"),
)
# project GUI writes recorded in the activity log
ACTIONS = (
    (re.compile(r"^/api/scan$"), "scan"),
    (re.compile(r"^/api/runs/[^/]+/reports$"), "build reports"),
    (re.compile(r"^/api/runs/[^/]+/enrich$"), "AI enrichment"),
    (re.compile(r"^/api/config/"), "edit audit.yaml"),
    (re.compile(r"^/api/components$"), "edit components"),
    (re.compile(r"^/api/inputs/"), "change inputs"),
    (re.compile(r"^/api/jobs/[^/]+/cancel$"), "cancel job"),
)


# ---- request bodies ------------------------------------------------------------------------------


class LoginBody(BaseModel):
    name: str
    next: str | None = None


class SourceBody(BaseModel):
    type: str = "local"
    path: str | None = None  # local: a folder under a sources root
    url: str | None = None  # git
    ref: str | None = None
    paths: list[str] = Field(default_factory=list)
    token_env: str | None = None


class CreateBody(BaseModel):
    title: str
    id: str | None = None
    description: str | None = None
    source: SourceBody


class AccessBody(BaseModel):
    title: str | None = None
    owner: str | None = None
    members: dict[str, str] | None = None
    hidden_reports: list[str] | None = None
    hidden_sections: list[str] | None = None
    archived: bool | None = None


class UserBody(BaseModel):
    name: str
    role: str = "owner"
    disabled: bool = False


class DeleteBody(BaseModel):
    confirm: str


class DefaultsBody(BaseModel):
    text: str


# ---- the site ------------------------------------------------------------------------------------


def _check_writable(host: HostSettings) -> None:
    """Create the audits folder and the site's own folder in it, or stop with what to do: on
    Linux and WSL2 a folder on the host is often owned by another user id than the site's."""
    probe = host.admin_dir / ".write-test"
    try:
        host.admin_dir.mkdir(parents=True, exist_ok=True)
        probe.write_text("ok", encoding="utf-8")
        probe.unlink()
    except OSError as err:
        uid = os.getuid() if hasattr(os, "getuid") else None
        who = f" (user id {uid})" if uid is not None else ""
        raise SettingsError(
            f"The audits folder {host.audits_root} cannot be written by the site{who}: "
            f"{err.strerror or err}. With Docker on Linux or WSL2, set AUDIT_UID and AUDIT_GID "
            "in .env to the owner of the folder (run: id -u and id -g), or give the folder "
            "to user id 10001 (sudo chown -R 10001:10001 <folder>). See docs/hosting.md"
        ) from None


class Site:
    """State shared by the portal and every project app."""

    def __init__(self, host: HostSettings) -> None:
        self.host = host
        _check_writable(host)
        self.admin = AdminStore(host)
        self.registry = Registry(host)
        self.provider = provider_for(host)
        self.sessions = Sessions(host.session_secret, host.session_hours)
        self.gate = Gate(lambda: self.admin.get().limits.max_scans)
        self.layer = AdminLayer(self.admin)
        self.remove_layer = add_config_layer(self.layer)
        self.jobs: deque[Job] = deque(maxlen=JOB_HISTORY)
        self.apps: dict[str, Any] = {}
        self.sampler = Sampler()
        self.sampler.start()
        self.apply_paths()

    def apply_paths(self) -> None:
        templates = self.admin.get().paths.templates_dir
        if templates:
            os.environ["LEGACY_AUDIT_TEMPLATES_DIR"] = templates

    # ---- users ----

    def current_user(self, cookies: dict[str, str]) -> User | None:
        name = self.sessions.read(cookies.get(COOKIE))
        if name is None:
            return None
        user = self.registry.user(name)
        if user is None or user.disabled:
            return None
        return user

    def can_create(self, user: User) -> bool:
        if user.role == "admin":
            return True
        rule = self.admin.get().users.who_can_create
        return rule == "owners" and user.role == "owner"

    # ---- projects ----

    def role(self, user: User, project_id: str) -> tuple[ProjectMeta | None, str | None]:
        if not PROJECT_ID.match(project_id) or project_id.startswith("."):
            return None, None
        meta = self.registry.project(project_id)
        if meta is None:
            return None, None
        return meta, self.registry.role_in(user, meta)

    def reports_for(self, project_id: str) -> set[str]:
        s = self.admin.get()
        on = {k for k, v in s.reports.enabled.items() if v and k in REPORTS}
        meta = self.registry.project(project_id)
        if meta and s.reports.owners_may_hide:
            on -= set(meta.hidden_reports)
        return on

    def sections_hidden(self, project_id: str) -> set[str]:
        s = self.admin.get()
        off = {k for k, v in s.reports.summary_sections.items() if not v}
        meta = self.registry.project(project_id)
        if meta and s.reports.owners_may_hide:
            off |= set(meta.hidden_sections)
        return off

    def project_app(self, project_id: str) -> Any:
        app = self.apps.get(project_id)
        if app is not None:
            return app

        def on_job(job: Job) -> None:
            self.jobs.appendleft(job)

        def user_of(request: Request) -> dict[str, Any]:
            return dict(request.scope.get("state", {}).get("la_user") or {})

        def after_scan(project: str, result: dict[str, Any]) -> None:
            self.registry.log(None, "scan finished", project, run=result.get("run_id"))

        def on_job_end(job: Job) -> None:
            waited, worked = job.timings()
            self.registry.log(
                job.user,
                "job finished",
                project_id,
                kind=job.kind,
                title=job.title,
                state=job.state,
                seconds=round(worked, 1) if worked is not None else None,
                wait_seconds=round(waited, 1) if waited is not None else None,
                error=job.error,
            )

        def limits() -> LimitSettings:
            return self.admin.get().limits

        hooks = HostedHooks(
            project=project_id,
            gate=self.gate,
            on_job=on_job,
            reports_allowed=lambda: self.reports_for(project_id),
            hidden_sections=lambda: self.sections_hidden(project_id),
            ai_allowed=lambda: self.admin.get().ai.allowed,
            keep_runs=lambda: limits().keep_runs,
            scan_timeout_minutes=lambda: limits().scan_timeout_minutes,
            user=user_of,
            after_scan=after_scan,
            on_job_end=on_job_end,
        )
        app = create_app(
            self.registry.project_dir(project_id),
            self.host.port,
            opener=lambda path: None,
            hosted=hooks,
        )
        self.apps[project_id] = app
        return app

    def project_summary(self, meta: ProjectMeta, role: str | None) -> dict[str, Any]:
        from legacy_audit.store.runs import RunNotFound, list_runs, resolve_run

        folder = self.registry.project_dir(meta.id)
        out: dict[str, Any] = {
            "id": meta.id,
            "title": meta.title,
            "owner": meta.owner,
            "members": meta.members,
            "role": role,
            "archived": meta.archived,
            "created_at": meta.created_at,
            "url": f"p/{quote(meta.id)}/",
            "application": None,
            "runs": 0,
            "last_run": None,
            "problem": None,
            "job": None,
            "hidden_reports": meta.hidden_reports,
            "hidden_sections": meta.hidden_sections,
        }
        try:
            loaded = load_config(folder / "audit.yaml")
            out["application"] = loaded.config.application.name
            ids = list_runs(loaded.output_dir)
            out["runs"] = len(ids)
            if ids:
                try:
                    out["last_run"] = resolve_run(loaded.output_dir, "latest").name
                except RunNotFound:
                    out["last_run"] = ids[-1]
        except ConfigError as err:
            out["problem"] = str(err).splitlines()[0]
        app = self.apps.get(meta.id)
        if app is not None and app.state.jobs.current is not None:
            job = app.state.jobs.current
            out["job"] = {"title": job.title, "state": job.state}
        return out

    def visible(self, user: User) -> list[tuple[ProjectMeta, str]]:
        out = []
        for meta in self.registry.projects():
            role = self.registry.role_in(user, meta)
            if role is not None:
                out.append((meta, role))
        return out


def _cookies(scope: Scope) -> dict[str, str]:
    from http.cookies import SimpleCookie

    jar: SimpleCookie = SimpleCookie()
    for k, v in scope.get("headers", []):
        if k == b"cookie":
            try:
                jar.load(v.decode("latin-1"))
            except Exception:  # a malformed cookie header: no session
                return {}
    return {k: m.value for k, m in jar.items()}


def _headers(scope: Scope) -> dict[str, str]:
    return {k.decode("latin-1").lower(): v.decode("latin-1") for k, v in scope.get("headers", [])}


def _origin_ok(headers: dict[str, str], host: HostSettings) -> bool:
    """A write must carry the GUI header, and an Origin (when sent) must be this site."""
    if headers.get(HEADER.lower()) != "1":
        return False
    origin = headers.get("origin")
    if not origin:
        return True
    o = urlsplit(origin)
    if host.public_url:
        p = urlsplit(host.public_url)
        if (o.scheme, o.netloc) == (p.scheme, p.netloc):
            return True
    return o.netloc == headers.get("host", "")


def _host_ok(headers: dict[str, str], host: HostSettings) -> bool:
    allowed = host.allowed_hosts()
    return allowed is None or headers.get("host", "") in allowed


def _safe_next(value: str | None) -> str:
    if value and value.startswith("/") and not value.startswith("//") and "\\" not in value:
        return value
    return "/"


class HostedApp:
    """The ASGI entry point: projects under ``/p/<id>/``, the portal for the rest."""

    def __init__(self, site: Site, portal: FastAPI, log_handler: logging.Handler) -> None:
        self.site = site
        self.portal = portal
        self.log_handler = log_handler

    def close(self) -> None:
        """Remove the config layer and the server log handler (tests; the server just exits)."""
        self.site.remove_layer()
        self.site.sampler.stop()
        logging.getLogger("legacy_audit").removeHandler(self.log_handler)
        self.log_handler.close()

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] not in ("http", "websocket"):
            await self.portal(scope, receive, send)
            return
        path: str = scope["path"]
        m = PROJECT_PATH.match(path)
        if not m:
            await self.portal(scope, receive, send)
            return
        project_id, rest = m.group(1), m.group(2)
        resp = self._check(scope, project_id, rest)
        if resp is not None:
            await resp(scope, receive, send)
            return
        app = self.site.project_app(project_id)
        child = dict(scope)
        child["root_path"] = scope.get("root_path", "") + f"/p/{project_id}"
        await app(child, receive, send)

    def _check(self, scope: Scope, project_id: str, rest: str | None) -> Response | None:
        site = self.site
        headers = _headers(scope)
        if not _host_ok(headers, site.host):
            return JSONResponse({"detail": "Host not allowed"}, status_code=403)
        user = site.current_user(_cookies(scope))
        method = scope.get("method", "GET")
        if user is None:
            if method == "GET" and (rest in (None, "/")):
                return RedirectResponse(f"/login?next={quote(scope['path'])}", status_code=303)
            return JSONResponse({"detail": "Log in first"}, status_code=401)
        meta, role = site.role(user, project_id)
        if meta is None or role is None:
            return JSONResponse({"detail": "No such project"}, status_code=404)
        if rest is None:
            return RedirectResponse(f"/p/{quote(project_id)}/", status_code=307)
        write = method not in ("GET", "HEAD", "OPTIONS")
        if write:
            if not _origin_ok(headers, site.host):
                return JSONResponse({"detail": "Cross-site request refused"}, status_code=403)
            if meta.archived and role != "admin":
                return JSONResponse({"detail": "This project is archived"}, status_code=403)
            need = "owner" if any(p.match(rest) for p in OWNER_WRITES) else "contributor"
            if RANK[role] < RANK[need]:
                return JSONResponse(
                    {"detail": f"Your role on this project ({role}) cannot do that"},
                    status_code=403,
                )
            for pattern, action in ACTIONS:
                if pattern.match(rest):
                    site.registry.log(user.name, action, project_id, path=rest)
                    break
        scope.setdefault("state", {})["la_user"] = {"name": user.name, "role": role}
        return None


def setup_logging(host: HostSettings) -> logging.Handler:
    """The server log (``.legacy-audit/logs/server.log``, rotated at 5 MB, 5 kept)."""
    folder = host.admin_dir / "logs"
    folder.mkdir(parents=True, exist_ok=True)
    handler = logging.handlers.RotatingFileHandler(
        folder / "server.log", maxBytes=5 * 1024 * 1024, backupCount=5, encoding="utf-8"
    )
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    root = logging.getLogger("legacy_audit")
    root.addHandler(handler)
    # scans set INFO for their run log and put the level back after; keep it INFO for all
    root.setLevel(logging.INFO)
    return handler


def create_site(host: HostSettings) -> HostedApp:
    site = Site(host)
    log_handler = setup_logging(host)
    server_log = host.admin_dir / "logs" / "server.log"
    portal = FastAPI(
        title="legacy-audit (hosted)",
        version=__version__,
        docs_url="/api/docs",
        openapi_url="/api/openapi.json",
        redoc_url=None,
    )
    portal.state.site = site

    @portal.middleware("http")
    async def guard(request: Request, call_next: Any) -> Response:
        headers = {k.lower(): v for k, v in request.headers.items()}
        if request.url.path != "/healthz" and not _host_ok(headers, host):
            return JSONResponse({"detail": "Host not allowed"}, status_code=403)
        if request.method not in ("GET", "HEAD", "OPTIONS") and not _origin_ok(headers, host):
            return JSONResponse({"detail": "Cross-site request refused"}, status_code=403)
        response: Response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers.setdefault("Cache-Control", "no-store")
        return response

    def me(request: Request) -> User:
        user = site.current_user(dict(request.cookies))
        if user is None:
            raise HTTPException(401, "Log in first")
        return user

    def admin_only(request: Request) -> User:
        user = me(request)
        if user.role != "admin":
            raise HTTPException(403, "Admins only")
        return user

    def project_or_404(user: User, project_id: str) -> tuple[ProjectMeta, str]:
        meta, role = site.role(user, project_id)
        if meta is None or role is None:
            raise HTTPException(404, "No such project")
        return meta, role

    # ---- pages ----

    def page(name: str) -> HTMLResponse:
        return HTMLResponse((STATIC / name).read_text(encoding="utf-8"))

    @portal.get("/healthz", include_in_schema=False)
    def healthz() -> dict[str, Any]:
        return {"ok": True, "version": __version__}

    @portal.get("/login", include_in_schema=False)
    def login_page() -> HTMLResponse:
        return page("login.html")

    @portal.get("/", include_in_schema=False)
    def home(request: Request) -> Response:
        if site.current_user(dict(request.cookies)) is None:
            return RedirectResponse("/login", status_code=303)
        return page("portal.html")

    portal.mount("/hosted-static", StaticFiles(directory=STATIC), name="hosted-static")
    portal.mount("/static", StaticFiles(directory=GUI_STATIC), name="static")
    portal.mount("/vendor", StaticFiles(directory=VENDOR), name="vendor")
    portal.mount("/assets", StaticFiles(directory=VENDOR.parent), name="assets")

    # ---- login ----

    @portal.get("/api/auth")
    def auth_info() -> dict[str, Any]:
        s = site.admin.get()
        return {
            "provider": site.provider.name,
            "label": site.provider.label,
            "login_form": site.provider.login_form,
            "banner": site.provider.banner,
            "open_signup": s.users.open_signup,
        }

    @portal.post("/auth/login")
    def login(body: LoginBody) -> Response:
        try:
            name = site.provider.check(body.name)
        except AuthError as err:
            raise HTTPException(400, str(err)) from None
        user = site.registry.user(name)
        s = site.admin.get()
        if user is None:
            if not s.users.open_signup:
                raise HTTPException(403, "This name is not a user here; ask an admin to add you")
            user = User(name=name, role=s.users.signup_role)
        if user.disabled:
            raise HTTPException(403, "This user is disabled; ask an admin")
        from legacy_audit.hosted.registry import now

        user.last_login = now()
        try:
            user = site.registry.save_user(user)
        except SettingsError as err:
            raise HTTPException(400, str(err)) from None
        site.registry.log(user.name, "log in")
        resp = JSONResponse({"user": user.name, "role": user.role, "next": _safe_next(body.next)})
        resp.set_cookie(
            COOKIE,
            site.sessions.issue(user.name),
            max_age=int(host.session_hours * 3600),
            httponly=True,
            samesite="lax",
            secure=host.secure_cookies,
        )
        return resp

    @portal.get("/auth/logout", include_in_schema=False)
    def logout() -> Response:
        resp = RedirectResponse("/login", status_code=303)
        resp.delete_cookie(COOKIE)
        return resp

    @portal.get("/api/me")
    def whoami(request: Request) -> dict[str, Any]:
        user = me(request)
        return {
            "name": user.name,
            "role": user.role,
            "can_create": site.can_create(user),
            "banner": site.provider.banner,
            "version": __version__,
            "sources_roots": [str(p) for p in site.layer.source_roots()],
        }

    # ---- projects ----

    @portal.get("/api/projects")
    def projects(request: Request) -> list[dict[str, Any]]:
        user = me(request)
        return [site.project_summary(meta, role) for meta, role in site.visible(user)]

    @portal.get("/api/sources")
    def sources(request: Request, path: str = "") -> dict[str, Any]:
        """Folders a new project may scan: the sources roots and their sub-folders."""
        me(request)
        roots = site.layer.source_roots()
        if not path:
            return {
                "path": "",
                "parent": None,
                "folders": [{"name": str(r), "path": str(r)} for r in roots if r.is_dir()],
            }
        target = Path(path).resolve()
        if not any(target == r or target.is_relative_to(r) for r in roots) or not target.is_dir():
            raise HTTPException(404, "Not a folder this site may scan")
        folders = []
        try:
            for d in sorted(target.iterdir(), key=lambda p: p.name.lower()):
                if d.is_dir() and not d.name.startswith("."):
                    folders.append({"name": d.name, "path": str(d)})
        except OSError as err:
            raise HTTPException(403, f"Cannot read {target}: {err.strerror}") from None
        parent = None if target in roots else str(target.parent)
        return {"path": str(target), "parent": parent, "folders": folders[:500]}

    @portal.post("/api/projects")
    def create_project(body: CreateBody, request: Request) -> dict[str, Any]:
        from legacy_audit.workspace import init_workspace, update_config

        user = me(request)
        if not site.can_create(user):
            raise HTTPException(403, "Your role cannot create projects; ask an admin")
        title = body.title.strip()
        if not title:
            raise HTTPException(400, "Give the project a name")
        pid = (body.id or slugify(title)).strip()
        if not PROJECT_ID.match(pid) or pid.startswith("."):
            raise HTTPException(400, f"'{pid}' is not an allowed folder name")
        folder = host.audits_root / pid
        if folder.exists():
            raise HTTPException(409, f"A project folder '{pid}' already exists; open it instead")
        src = body.source
        if src.type == "git":
            if not src.url:
                raise HTTPException(400, "Give the Git address")
            repo: dict[str, Any] = {"url": src.url}
            if src.ref:
                repo["ref"] = src.ref
            if src.paths:
                repo["paths"] = src.paths
            if src.token_env:
                repo["token_env"] = src.token_env
            source: dict[str, Any] = {"type": "git", "repos": [repo]}
        else:
            if not src.path:
                raise HTTPException(400, "Pick the folder holding the code")
            source = {"type": "local", "paths": [src.path.replace("\\", "/")]}
        application: dict[str, Any] = {"name": title, "owner": user.name}
        if body.description:
            application["description"] = body.description
        try:
            init_workspace(folder, title)
            (folder / "audit.yaml").write_text(
                PROJECT_YAML.format(name=json.dumps(title)), encoding="utf-8", newline="\n"
            )
            update_config(folder / "audit.yaml", {"application": application, "source": source})
            load_config(folder / "audit.yaml")  # the admin layer checks the source folder
        except ConfigError as err:
            shutil.rmtree(folder, ignore_errors=True)
            raise HTTPException(422, str(err)) from None
        meta = site.registry.save_project(ProjectMeta(id=pid, title=title, owner=user.name))
        site.registry.log(user.name, "create project", pid, source=source["type"])
        return site.project_summary(meta, site.registry.role_in(user, meta))

    @portal.put("/api/projects/{project_id}")
    def update_project(project_id: str, body: AccessBody, request: Request) -> dict[str, Any]:
        user = me(request)
        meta, role = project_or_404(user, project_id)
        if RANK[role] < RANK["owner"]:
            raise HTTPException(403, "Only the owner or an admin can change this project")
        changes = body.model_dump(exclude_none=True)
        new_owner = changes.get("owner")
        if new_owner and new_owner != meta.owner and site.registry.user(new_owner) is None:
            raise HTTPException(400, f"No user '{new_owner}'")
        bad = set(changes.get("hidden_reports", [])) - set(REPORTS)
        if bad:
            raise HTTPException(400, f"Unknown report(s): {', '.join(sorted(bad))}")
        bad = set(changes.get("hidden_sections", [])) - set(SUMMARY_SECTIONS)
        if bad:
            raise HTTPException(400, f"Unknown section(s): {', '.join(sorted(bad))}")
        for name in changes.get("members") or {}:
            if site.registry.user(name) is None:
                raise HTTPException(400, f"No user '{name}'")
        for key, value in changes.items():
            setattr(meta, key, value)
        try:
            site.registry.save_project(meta)
        except SettingsError as err:
            raise HTTPException(400, str(err)) from None
        site.registry.log(user.name, "change project", project_id, fields=sorted(changes))
        return site.project_summary(meta, site.registry.role_in(user, meta))

    @portal.post("/api/projects/{project_id}/delete")
    def delete_project(project_id: str, body: DeleteBody, request: Request) -> dict[str, Any]:
        user = admin_only(request)
        meta, _ = project_or_404(user, project_id)
        if body.confirm != meta.id:
            raise HTTPException(400, f"Type the folder name '{meta.id}' to confirm")
        app = site.apps.pop(meta.id, None)
        if app is not None and app.state.jobs.current and app.state.jobs.current.active:
            site.apps[meta.id] = app
            raise HTTPException(409, "A job is running on this project; cancel it first")
        site.registry.delete_project(meta.id)
        site.registry.log(user.name, "delete project", meta.id)
        return {"deleted": meta.id}

    # ---- status ----

    @portal.get("/api/status")
    def status(request: Request) -> dict[str, Any]:
        user = me(request)
        visible = {m.id for m, _ in site.visible(user)}
        jobs = [j.info() for j in list(site.jobs) if j.project in visible]
        snap = site.gate.snapshot()
        limits = site.admin.get().limits
        return {
            "slots": limits.max_scans,
            "running": len(snap["running"]),
            "waiting": len(snap["waiting"]),
            "jobs": jobs,
        }

    # ---- logs ----

    def log_files(user: User) -> list[dict[str, Any]]:
        from legacy_audit.store.runs import list_runs

        out: list[dict[str, Any]] = []
        if user.role == "admin":
            for p in sorted(server_log.parent.glob("server.log*")):
                out.append(
                    {
                        "id": f"server/{p.name}",
                        "label": f"Server log ({p.name})",
                        "project": None,
                        "run": None,
                        "size": p.stat().st_size,
                    }
                )
        for meta, _role in site.visible(user):
            try:
                loaded = load_config(site.registry.project_dir(meta.id) / "audit.yaml")
            except ConfigError:
                continue
            for run_id in reversed(list_runs(loaded.output_dir)):
                folder = loaded.output_dir / "runs" / run_id / "logs"
                for p in sorted(folder.glob("*.log")) if folder.is_dir() else []:
                    out.append(
                        {
                            "id": f"{meta.id}/{run_id}/{p.name}",
                            "label": f"{meta.title}: run {run_id} ({p.name})",
                            "project": meta.id,
                            "run": run_id,
                            "size": p.stat().st_size,
                        }
                    )
        return out

    def log_path(user: User, log_id: str) -> Path:
        parts = log_id.split("/")
        if parts[0] == "server" and len(parts) == 2:
            if user.role != "admin":
                raise HTTPException(403, "Admins only")
            p = server_log.parent / parts[1]
            if not re.match(r"^server\.log(\.\d+)?$", parts[1]) or not p.is_file():
                raise HTTPException(404, "No such log")
            return p
        if len(parts) != 3 or not re.match(r"^[\w.-]+\.log$", parts[2]):
            raise HTTPException(404, "No such log")
        meta, _ = project_or_404(user, parts[0])
        from legacy_audit.gui.app import RUN_ID

        if not RUN_ID.match(parts[1]):
            raise HTTPException(404, "No such log")
        loaded = load_config(site.registry.project_dir(meta.id) / "audit.yaml")
        p = loaded.output_dir / "runs" / parts[1] / "logs" / parts[2]
        if not p.is_file():
            raise HTTPException(404, "No such log")
        return p

    @portal.get("/api/logs")
    def logs(request: Request) -> list[dict[str, Any]]:
        return log_files(me(request))

    @portal.get("/api/logs/view")
    def log_view(
        request: Request, id: str, level: str = "", q: str = "", tail: int = 500
    ) -> dict[str, Any]:
        p = log_path(me(request), id)
        lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
        levels = {"error": ("ERROR", "CRITICAL"), "warning": ("WARNING", "ERROR", "CRITICAL")}
        if level in levels:
            lines = [ln for ln in lines if any(f" {lv} " in ln for lv in levels[level])]
        if q:
            lines = [ln for ln in lines if q.lower() in ln.lower()]
        tail = max(1, min(tail, LOG_TAIL))
        return {"id": id, "total": len(lines), "lines": lines[-tail:]}

    @portal.get("/api/logs/download")
    def log_download(request: Request, id: str) -> FileResponse:
        p = log_path(me(request), id)
        name = id.replace("/", "_")
        return FileResponse(p, media_type="text/plain", filename=name)

    # ---- admin ----

    @portal.get("/api/admin")
    def admin_get(request: Request) -> dict[str, Any]:
        admin_only(request)
        import yaml

        settings = site.admin.get().model_dump(mode="json")
        return {
            "settings": settings,
            "defaults_yaml": yaml.safe_dump(settings["defaults"], sort_keys=False)
            if settings["defaults"]
            else "",
            "env_locked": host.env_locked(),
            "reports": REPORTS,
            "summary_sections": SUMMARY_SECTIONS,
            "roles": ROLES,
            "host": {
                "audits_root": str(host.audits_root),
                "sources_roots": [str(p) for p in host.sources_roots],
                "public_url": host.public_url,
                "auth": host.auth,
                "admins": host.admins,
                "port": host.port,
                "session_hours": host.session_hours,
                "session_secret_set": host.secret_from_env,
                "admin_dir": str(host.admin_dir),
                "git_cache": str(site.layer.git_cache()),
            },
        }

    @portal.put("/api/admin/defaults")
    def admin_defaults(body: DefaultsBody, request: Request) -> dict[str, Any]:
        """Replace the project defaults with a YAML text (the admin page's advanced editor)."""
        import yaml

        user = admin_only(request)
        try:
            data = yaml.safe_load(body.text) or {}
        except yaml.YAMLError as err:
            raise HTTPException(422, f"Not valid YAML: {err}") from None
        if not isinstance(data, dict):
            raise HTTPException(422, "The defaults must be a mapping of audit.yaml settings")
        settings = site.admin.get().model_dump(mode="json")
        settings["defaults"] = data
        try:
            site.admin.save(settings)
        except SettingsError as err:
            raise HTTPException(422, str(err)) from None
        site.registry.log(user.name, "change project defaults")
        return admin_get(request)

    @portal.put("/api/admin")
    async def admin_put(request: Request) -> dict[str, Any]:
        user = admin_only(request)
        data = await request.json()
        try:
            site.admin.save(data)
        except SettingsError as err:
            raise HTTPException(422, str(err)) from None
        site.apply_paths()
        site.registry.log(user.name, "change admin settings")
        return admin_get(request)

    @portal.get("/api/admin/users")
    def users(request: Request) -> list[dict[str, Any]]:
        admin_only(request)
        owned: dict[str, int] = {}
        for meta in site.registry.projects():
            if meta.owner:
                owned[meta.owner] = owned.get(meta.owner, 0) + 1
        return [
            {**u.__dict__, "fixed": u.name in host.admins, "projects": owned.get(u.name, 0)}
            for u in site.registry.users()
        ]

    @portal.get("/api/users")
    def user_names(request: Request) -> list[str]:
        """Names for the members picker (any logged-in user)."""
        me(request)
        return [u.name for u in site.registry.users() if not u.disabled]

    @portal.put("/api/admin/users")
    def save_user(body: UserBody, request: Request) -> dict[str, Any]:
        admin = admin_only(request)
        if body.name in host.admins:
            raise HTTPException(400, "This admin is set by LEGACY_AUDIT_ADMINS")
        existing = site.registry.user(body.name) or User(name=body.name)
        existing.role, existing.disabled = body.role, body.disabled
        try:
            saved = site.registry.save_user(existing)
        except SettingsError as err:
            raise HTTPException(400, str(err)) from None
        site.registry.log(
            admin.name,
            "change user",
            None,
            target=body.name,
            role=body.role,
            disabled=body.disabled,
        )
        return saved.__dict__

    @portal.delete("/api/admin/users/{name}")
    def delete_user(name: str, request: Request) -> dict[str, Any]:
        admin = admin_only(request)
        if name in host.admins:
            raise HTTPException(400, "This admin is set by LEGACY_AUDIT_ADMINS")
        site.registry.delete_user(name)
        site.registry.log(admin.name, "delete user", None, target=name)
        return {"deleted": name}

    @portal.get("/api/admin/projects")
    def all_projects(request: Request) -> list[dict[str, Any]]:
        admin = admin_only(request)
        return [site.project_summary(m, "admin") for m, _ in site.visible(admin)]

    @portal.get("/api/admin/activity")
    def activity(request: Request, limit: int = 500, project: str = "") -> list[dict[str, Any]]:
        admin_only(request)
        return site.registry.activity(max(1, min(limit, 5000)), project or None)

    @portal.get("/api/admin/activity.csv")
    def activity_csv(request: Request) -> PlainTextResponse:
        admin_only(request)
        buf = io.StringIO()
        w = csv.writer(buf)
        w.writerow(["time", "user", "action", "project", "detail"])
        for e in site.registry.activity(100_000):
            rest = {k: v for k, v in e.items() if k not in ("time", "user", "action", "project")}
            w.writerow(
                [
                    e.get("time"),
                    e.get("user"),
                    e.get("action"),
                    e.get("project"),
                    "; ".join(f"{k}={v}" for k, v in rest.items()),
                ]
            )
        return PlainTextResponse(
            buf.getvalue(),
            media_type="text/csv",
            headers={"Content-Disposition": 'attachment; filename="legacy-audit-activity.csv"'},
        )

    @portal.get("/api/admin/dashboard/usage")
    def dashboard_usage(request: Request, days: int = 30) -> dict[str, Any]:
        admin_only(request)
        days = max(1, min(days, 366))
        start = (datetime.now(UTC).date() - timedelta(days=days - 1)).isoformat()
        return usage(
            site.registry.activity_since(start),
            days,
            site.registry.users(),
            site.registry.projects(),
        )

    @portal.get("/api/admin/dashboard/system")
    def dashboard_system(request: Request) -> dict[str, Any]:
        admin_only(request)
        snap = site.gate.snapshot()
        jobs = {"running": len(snap["running"]), "waiting": len(snap["waiting"])}
        return {
            **system(site.sampler, host.audits_root, jobs),
            "version": __version__,
            "slots": site.admin.get().limits.max_scans,
            "now": [j.info() for j in [*snap["running"], *snap["waiting"]]],
        }

    @portal.get("/api/admin/health")
    def health(request: Request) -> dict[str, Any]:
        from legacy_audit.config import parse_config
        from legacy_audit.doctor import run_checks

        admin_only(request)
        s = site.admin.get()
        try:
            cfg = parse_config({"application": {"name": "health"}, **s.defaults}, "defaults")
        except ConfigError:
            cfg = None
        disk = shutil.disk_usage(host.audits_root)
        sizes = []
        for meta in site.registry.projects():
            sizes.append({"project": meta.id, "bytes": _folder_size(host.audits_root / meta.id)})
        return {
            "version": __version__,
            "disk": {"total": disk.total, "used": disk.used, "free": disk.free},
            "projects": sizes,
            "checks": [c.__dict__ for c in run_checks(cfg, host.audits_root)],
            "queue": {
                "slots": s.limits.max_scans,
                **{k: len(v) for k, v in site.gate.snapshot().items()},
            },
        }

    return HostedApp(site, portal, log_handler)


def _folder_size(folder: Path, limit: int = 200_000) -> int:
    """Bytes under ``folder`` (stops counting after ``limit`` entries on huge trees)."""
    total, seen = 0, 0
    stack = [folder]
    while stack and seen < limit:
        d = stack.pop()
        try:
            with os.scandir(d) as it:
                for e in it:
                    seen += 1
                    if e.is_dir(follow_symlinks=False):
                        stack.append(Path(e.path))
                    elif e.is_file(follow_symlinks=False):
                        total += e.stat(follow_symlinks=False).st_size
        except OSError:
            continue
    return total
