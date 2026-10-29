"""The local GUI: a FastAPI app over the same functions the CLI uses (no logic of its own).

Bound to 127.0.0.1 only. Requests whose Host is not localhost are refused (DNS rebinding), and
every change (POST/PUT/DELETE) needs the ``X-Legacy-Audit`` header, which a page on another site
cannot send without a CORS pre-flight this server never grants. Run ids, file names and evidence
paths are checked so nothing outside the workspace, the run folders and the scanned source can be
read or written.

The hosted site (``legacy_audit.hosted``) serves this same app once per project under
``/p/<project>/`` and passes ``HostedHooks``: it checks the login and the host itself, and the
hooks add the shared scan queue, the admin's report switches, run retention and the scan timeout.
All URLs the page uses are relative, so the same files work at ``/`` and under a prefix.
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import re
import subprocess
import sys
import zipfile
from collections.abc import AsyncIterator, Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import anyio
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import (
    FileResponse,
    HTMLResponse,
    JSONResponse,
    Response,
    StreamingResponse,
)
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from legacy_audit import __version__
from legacy_audit.config import INPUT_KINDS, ConfigError, LoadedConfig, load_config
from legacy_audit.gui.jobs import Busy, Gate, Job, Jobs
from legacy_audit.model import ENTITIES
from legacy_audit.store.runs import RunNotFound, list_runs, resolve_run
from legacy_audit.workspace import CONFIG_FILE

HERE = Path(__file__).resolve().parent
STATIC = HERE / "static"
VENDOR = HERE.parent / "report" / "assets" / "vendor"
HEADER = "X-Legacy-Audit"
RUN_ID = re.compile(r"^(latest|previous|\d{8}-\d{6}-[0-9a-f]{6})$")
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._ -]{0,120}$")
UPLOAD_KINDS = {**INPUT_KINDS, "security": "Vulnerability reports (JSON)"}
MAX_UPLOAD = 200 * 1024 * 1024
DIFF_LIST = 200
REPORT_TYPES = ("tech", "functional", "mgmt", "pptx", "summary", "pdf", "all")
HTML_REPORTS = ("tech", "functional", "mgmt", "summary")
# GUI report link -> the report switches it needs (hosted site)
REPORT_NEEDS: dict[str, tuple[str, ...]] = {
    "tech": ("tech",),
    "functional": ("functional",),
    "mgmt": ("mgmt",),
    "pptx": ("pptx",),
    "summary": ("summary",),
    "summary_pptx": ("summary",),
    "pdf_summary": ("pdf", "summary"),
    "pdf_mgmt": ("pdf", "mgmt"),
    "pdf_functional": ("pdf", "functional"),
    "pdf_tech": ("pdf", "tech"),
}
FIELDS = ("application", "source", "target", "include", "exclude")


# ---- request bodies -----------------------------------------------------------------------------


class InitBody(BaseModel):
    name: str | None = None


class FieldsBody(BaseModel):
    application: dict[str, Any] | None = None
    source: dict[str, Any] | None = None
    target: dict[str, Any] | None = None
    include: list[str] | None = None
    exclude: list[str] | None = None


class TextBody(BaseModel):
    text: str


class ComponentsBody(BaseModel):
    included: dict[str, bool] = Field(default_factory=dict)
    types: dict[str, str | None] = Field(default_factory=dict)


class ScanBody(BaseModel):
    full: bool = False


class EnrichBody(BaseModel):
    stale: bool = True
    stages: list[str] = []


class ReportBody(BaseModel):
    type: str = "tech"
    template: str | None = None  # PowerPoint template id; None: audit.yaml, else the first


class OpenBody(BaseModel):
    target: str  # workspace | inputs | output | run
    run: str | None = None


@dataclass
class HostedHooks:
    """What the hosted site plugs into one project's app. Callables are read on every request,
    so an admin change applies at once."""

    project: str
    gate: Gate
    on_job: Callable[[Job], None]
    reports_allowed: Callable[[], set[str]]  # keys of settings.REPORTS
    hidden_sections: Callable[[], set[str]]  # summary-report sections left out
    ai_allowed: Callable[[], bool]
    keep_runs: Callable[[], int]
    scan_timeout_minutes: Callable[[], int]
    user: Callable[[Request], dict[str, Any]]  # {"name", "role"} of the logged-in user
    after_scan: Callable[[str, dict[str, Any]], None] = lambda project, result: None
    on_job_end: Callable[[Job], None] = lambda job: None


def report_file_needs(path: str) -> tuple[str, ...]:
    """The report switches a file under ``reports/`` belongs to."""
    first = path.split("/", 1)[0]
    if first == "pdf":
        name = path.rsplit("/", 1)[-1]
        part = {"summary.pdf": "summary", "management.pdf": "mgmt"}.get(name)
        part = part or {"functional.pdf": "functional", "technical.pdf": "tech"}.get(name)
        return ("pdf", part) if part else ("pdf",)
    if first == "deck.pptx":
        return ("pptx",)
    if first in HTML_REPORTS:
        return (first,)
    return ()


# ---- helpers -------------------------------------------------------------------------------------


def open_in_os(path: Path) -> None:
    """Show a folder or file in Explorer / Finder / the desktop's file manager."""
    if sys.platform == "win32":
        os.startfile(path)
    else:
        cmd = "open" if sys.platform == "darwin" else "xdg-open"
        subprocess.Popen([cmd, str(path)])


class Workspace:
    def __init__(self, folder: Path) -> None:
        self.folder = folder.resolve()
        self.config_path = self.folder / CONFIG_FILE

    def loaded(self) -> LoadedConfig:
        if not self.config_path.exists():
            raise HTTPException(409, "No audit.yaml in the workspace yet: set up the project")
        try:
            return load_config(self.config_path)
        except ConfigError as err:
            raise HTTPException(422, str(err)) from None

    def run_dir(self, ref: str) -> Path:
        if not RUN_ID.match(ref):
            raise HTTPException(400, f"'{ref}' is not a run id")
        try:
            return resolve_run(self.loaded().output_dir, ref)
        except RunNotFound as err:
            raise HTTPException(404, str(err)) from None


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def _vscode_link(path: Path, line: int | None) -> str:
    p = path.resolve().as_posix()
    if not p.startswith("/"):
        p = "/" + p
    return f"vscode://file{p}" + (f":{line}" if line else "")


def _run_row(run_dir: Path, latest: str | None) -> dict[str, Any]:
    run = _read_json(run_dir / "run.json") or {}
    summary = _read_json(run_dir / "data" / "audit-summary.json") or {}
    totals = summary.get("totals", {})
    return {
        "run_id": run_dir.name,
        "started_at": run.get("started_at"),
        "finished_at": run.get("finished_at"),
        "full_scan": run.get("full_scan"),
        "files": run.get("files_total"),
        "analyzed": run.get("files_analyzed"),
        "cached": run.get("files_cached"),
        "loc_code": totals.get("loc_code"),
        "components": totals.get("components"),
        "findings": totals.get("findings"),
        "warnings": len(run.get("warnings", [])),
        "latest": run_dir.name == latest,
        "seconds": round(sum((run.get("timings") or {}).values()), 1),
    }


def _dashboard(run_dir: Path) -> dict[str, Any]:
    s = _read_json(run_dir / "data" / "audit-summary.json") or {}
    run = _read_json(run_dir / "run.json") or {}
    totals, counts = s.get("totals", {}), s.get("counts", {})
    bands: dict[str, int] = {}
    for per_kind in counts.get("complexity", {}).values():
        for band, n in per_kind.items():
            bands[band] = bands.get(band, 0) + n

    def count(path: str) -> Any:
        node: Any = counts
        for part in path.split("."):
            node = node.get(part, {}) if isinstance(node, dict) else {}
        return node if isinstance(node, int) else 0

    kpis = [
        {"label": label, "value": totals.get(key, 0), "metric": f"totals.{key}"}
        for label, key in (("Lines of code", "loc_code"), ("Components", "components"))
    ]
    for label, path in (
        ("Services", "services.total"),
        ("Screens", "screens.total"),
        ("SQL statements", "sql.total"),
        ("Integrations", "integrations.total"),
        ("Batch jobs", "batch_jobs.total"),
        ("Vulnerable libraries", "vulnerable_libraries.total"),
    ):
        kpis.append({"label": label, "value": count(path), "metric": f"counts.{path}"})
    sql_grades = counts.get("pg_grades", {})
    return {
        "run_id": run_dir.name,
        "application": run.get("application"),
        "started_at": run.get("started_at"),
        "kpis": kpis,
        "charts": {
            "technology": {
                k: v.get("loc_code", 0)
                for k, v in sorted(
                    s.get("by_language", {}).items(), key=lambda kv: -kv[1].get("loc_code", 0)
                )
                if v.get("loc_code")
            },
            "complexity": _ordered(bands, COMPLEXITY_ORDER),
            "pg_grade": _ordered(sql_grades, GRADE_ORDER),
            "severity": _ordered(counts.get("findings_by_severity", {}), SEVERITY_ORDER),
        },
        "coverage": s.get("coverage", {}),
        "warnings": run.get("warnings", []),
        "components": s.get("components", []),
        "top_findings": s.get("top_findings", [])[:10],
        "effort": s.get("effort"),
        "timings": run.get("timings", {}),
    }


SEVERITY_ORDER = ("critical", "high", "medium", "low", "info")
COMPLEXITY_ORDER = ("Simple", "Medium", "Complex", "Very complex")
GRADE_ORDER = ("auto", "review", "manual")


def _ordered(data: dict[str, int], order: tuple[str, ...]) -> dict[str, int]:
    """Categories in their natural order (severity, band, grade), unknown ones last."""
    rank = {k: i for i, k in enumerate(order)}
    return {k: data[k] for k in sorted(data, key=lambda k: (rank.get(k, len(order)), k))}


def _next_prompt(status: dict[str, Any]) -> str | None:
    if status["stale"]:
        return "/audit-enrich stale"
    missing = status["missing"]
    if len(missing) == len(status["stages"]):
        return "/audit-enrich all"
    if missing:
        return f"/audit-enrich {missing[0]}"
    return None


# ---- the app -------------------------------------------------------------------------------------


def create_app(
    folder: Path,
    port: int = 8765,
    opener: Callable[[Path], None] = open_in_os,
    hosted: HostedHooks | None = None,
) -> FastAPI:
    ws = Workspace(folder)
    jobs = Jobs(hosted.gate, hosted.on_job, hosted.on_job_end) if hosted else Jobs()
    durations: dict[str, float] = {}  # job kind + title -> seconds the last one took
    invalid_stages: dict[str, set[str]] = {}  # run id -> stages that failed the last validate
    app = FastAPI(
        title="legacy-audit",
        version=__version__,
        description="Local GUI API for legacy-audit. The same functions as the CLI.",
        docs_url="/api/docs",
        openapi_url="/api/openapi.json",
        redoc_url=None,
    )
    app.state.workspace = ws
    app.state.jobs = jobs
    app.state.hosted = hosted
    allowed_hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}
    allowed_origins = {f"http://127.0.0.1:{port}", f"http://localhost:{port}"}

    @app.middleware("http")
    async def guard(request: Request, call_next: Any) -> Response:
        host = request.headers.get("host", "")
        if hosted is None and host not in allowed_hosts:
            return JSONResponse({"detail": "Host not allowed"}, status_code=403)
        if request.method not in ("GET", "HEAD", "OPTIONS"):
            origin = request.headers.get("origin")
            if hosted is not None:
                origin = None  # the hosted site checked the origin against its public URL
            if request.headers.get(HEADER) != "1" or (origin and origin not in allowed_origins):
                return JSONResponse({"detail": "Cross-site request refused"}, status_code=403)
        response: Response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers.setdefault("Cache-Control", "no-store")
        return response

    # ---- pages and assets ----

    @app.get("/", include_in_schema=False)
    def index() -> HTMLResponse:
        return HTMLResponse((STATIC / "index.html").read_text(encoding="utf-8"))

    def allowed_reports() -> set[str]:
        if hosted is None:
            from legacy_audit.hosted.settings import REPORTS

            return set(REPORTS)
        return hosted.reports_allowed()

    def need_report(*keys: str) -> None:
        missing = [k for k in keys if k not in allowed_reports()]
        if missing:
            raise HTTPException(403, f"The admin has switched off: {', '.join(missing)}")

    app.mount("/static", StaticFiles(directory=STATIC), name="static")
    app.mount("/vendor", StaticFiles(directory=VENDOR), name="vendor")
    app.mount("/assets", StaticFiles(directory=VENDOR.parent), name="assets")

    @app.get("/runs/{run}/reports/{path:path}", include_in_schema=False)
    def report_file(run: str, path: str) -> FileResponse:
        base = (ws.run_dir(run) / "reports").resolve()
        target = (base / path).resolve()
        if not target.is_relative_to(base) or not target.is_file():
            raise HTTPException(404, "No such report file")
        need_report(*report_file_needs(target.relative_to(base).as_posix()))
        return FileResponse(target)

    # ---- workspace and configuration ----

    @app.get("/api/workspace")
    def workspace(request: Request) -> dict[str, Any]:
        out: dict[str, Any] = {
            "folder": ws.folder.name if hosted else str(ws.folder),
            "config": ws.config_path.exists(),
            "version": __version__,
            "hosted": None,
        }
        if hosted is not None:
            out["hosted"] = {
                "project": hosted.project,
                "user": hosted.user(request),
                "reports": sorted(allowed_reports()),
                "ai_allowed": hosted.ai_allowed(),
                "home": "../../",
            }
        if ws.config_path.exists():
            try:
                loaded = load_config(ws.config_path)
            except ConfigError as err:
                out["config_error"] = str(err)
                return out
            out.update(
                application=loaded.config.application.name,
                output_dir=str(loaded.output_dir),
                inputs_dir=str(loaded.inputs_dir),
                runs=len(list_runs(loaded.output_dir)),
            )
            if hosted is not None:
                out["output_dir"] = str(loaded.output_dir.relative_to(ws.folder))
                out["inputs_dir"] = str(loaded.inputs_dir.relative_to(ws.folder))
        return out

    @app.post("/api/init")
    def init(body: InitBody) -> dict[str, Any]:
        from legacy_audit.workspace import init_workspace

        done = init_workspace(ws.folder, body.name)
        return {"created": [d.__dict__ for d in done]}

    @app.get("/api/config")
    def get_config() -> dict[str, Any]:
        loaded = ws.loaded()
        cfg = loaded.config.model_dump(mode="json")
        return {
            "text": ws.config_path.read_text(encoding="utf-8"),
            "fields": {k: cfg[k] for k in FIELDS},
            "path": str(ws.config_path),
        }

    @app.put("/api/config/fields")
    def put_fields(body: FieldsBody) -> dict[str, Any]:
        from legacy_audit.workspace import update_config

        current = ws.loaded().config.model_dump(mode="json")
        changes = body.model_dump(exclude_none=True)
        if hosted is not None:
            # keep inherited site defaults inherited: write only what the form changed
            changes = {k: v for k, v in changes.items() if v != current.get(k)}
        try:
            update_config(ws.config_path, changes)
        except ConfigError as err:
            raise HTTPException(422, str(err)) from None
        return get_config()

    @app.put("/api/config/text")
    def put_text(body: TextBody) -> dict[str, Any]:
        from legacy_audit.workspace import write_config_text

        try:
            write_config_text(ws.config_path, body.text)
        except ConfigError as err:
            raise HTTPException(422, str(err)) from None
        return get_config()

    # ---- inputs ----

    @app.get("/api/inputs")
    def inputs() -> dict[str, Any]:
        loaded = ws.loaded()
        base = loaded.inputs_dir
        kinds = []
        for kind, label in UPLOAD_KINDS.items():
            folder = base / kind
            present = (
                sorted(p for p in folder.iterdir() if p.is_file() and not p.name.startswith("."))
                if folder.is_dir()
                else []
            )
            kinds.append(
                {
                    "kind": kind,
                    "label": label,
                    "folder": str(folder),
                    "files": [{"name": p.name, "size": p.stat().st_size} for p in present],
                }
            )
        return {"inputs_dir": str(base), "kinds": kinds}

    def _input_path(kind: str, name: str) -> Path:
        if kind not in UPLOAD_KINDS:
            raise HTTPException(400, f"Unknown input kind '{kind}'")
        if not SAFE_NAME.match(name) or name.strip() != name or ".." in name:
            raise HTTPException(400, f"'{name}' is not an allowed file name")
        return ws.loaded().inputs_dir / kind / name

    @app.put("/api/inputs/{kind}/{name}")
    async def upload(kind: str, name: str, request: Request) -> dict[str, Any]:
        target = _input_path(kind, name)
        data = await request.body()
        if len(data) > MAX_UPLOAD:
            raise HTTPException(413, "File too large")
        target.parent.mkdir(parents=True, exist_ok=True)
        await anyio.Path(target).write_bytes(data)
        return {"saved": str(target), "size": len(data)}

    @app.delete("/api/inputs/{kind}/{name}")
    def delete_input(kind: str, name: str) -> dict[str, Any]:
        target = _input_path(kind, name)
        if not target.is_file():
            raise HTTPException(404, "No such input file")
        target.unlink()
        return {"deleted": str(target)}

    @app.post("/api/open")
    def open_folder(body: OpenBody) -> dict[str, Any]:
        if hosted is not None:
            raise HTTPException(400, "Folders live on the server; download the files instead")
        targets = {"workspace": lambda: ws.folder}
        if ws.config_path.exists():
            targets["inputs"] = lambda: ws.loaded().inputs_dir
            targets["output"] = lambda: ws.loaded().output_dir
        if body.target == "run" and body.run:
            path = ws.run_dir(body.run)
        elif body.target in targets:
            path = targets[body.target]()
        else:
            raise HTTPException(400, f"Cannot open '{body.target}'")
        path.mkdir(parents=True, exist_ok=True)
        opener(path)
        return {"opened": str(path)}

    # ---- components ----

    @app.get("/api/components")
    def components() -> dict[str, Any]:
        from legacy_audit.source import SourceError
        from legacy_audit.workspace import prescan

        loaded = ws.loaded()
        try:
            out = prescan(loaded)
        except SourceError as err:
            raise HTTPException(422, str(err)) from None
        # the pre-scan reads no file, so its types are a first guess; the last scan knows better
        scanned: dict[str, str] = {}
        with contextlib.suppress(RunNotFound):
            data = _read_json(resolve_run(loaded.output_dir, "latest") / "data" / "components.json")
            scanned = {c["name"]: c["type"] for c in data or []}
        for c in out["components"]:
            c["scan_type"] = scanned.get(c["name"])
        return out

    @app.put("/api/components")
    def put_components(body: ComponentsBody) -> dict[str, Any]:
        from legacy_audit.workspace import set_component_choices

        loaded = ws.loaded()
        try:
            set_component_choices(loaded.overrides_path, body.included, body.types)
        except Exception as err:  # validation of a type name
            raise HTTPException(422, str(err)) from None
        return components()

    # ---- jobs ----

    def _start(
        kind: str,
        title: str,
        work: Callable[[Job], dict[str, Any]],
        request: Request | None = None,
        expected: float | None = None,
    ) -> dict[str, Any]:
        key = f"{kind}:{title}"

        def timed(job: Job) -> dict[str, Any]:
            result = work(job)
            if job._began is not None:
                import time

                durations[key] = time.monotonic() - job._began
            return result

        extra: dict[str, Any] = {"expected_seconds": expected or durations.get(key)}
        if hosted is not None:
            extra["project"] = hosted.project
            if request is not None:
                extra["user"] = hosted.user(request).get("name")
        try:
            return jobs.start(kind, title, timed, **extra).info()
        except Busy as err:
            raise HTTPException(409, str(err)) from None

    def _last_scan_seconds(output_dir: Path) -> float | None:
        ids = list_runs(output_dir)
        if not ids:
            return None
        row = _run_row(output_dir / "runs" / ids[-1], None)
        return float(row["seconds"]) if row["seconds"] else None

    @app.post("/api/scan")
    def scan(body: ScanBody, request: Request) -> dict[str, Any]:
        import threading

        from legacy_audit.scan import run_scan
        from legacy_audit.store.lock import ScanBusy
        from legacy_audit.store.runs import prune_runs

        loaded = ws.loaded()

        def work(job: Job) -> dict[str, Any]:
            timer = None
            if hosted is not None:
                minutes = hosted.scan_timeout_minutes()

                def stop() -> None:
                    job.emit("warning", message=f"Stopped: the scan ran over {minutes} minutes")
                    job.cancel.set()

                timer = threading.Timer(minutes * 60, stop)
                timer.daemon = True
                timer.start()
            try:
                result = run_scan(
                    load_config(loaded.path),
                    full=body.full,
                    progress=job.progress,
                    cancel=job.cancel,
                )
            except ScanBusy as err:
                raise RuntimeError(str(err)) from None
            finally:
                if timer is not None:
                    timer.cancel()
            run = result.run
            out: dict[str, Any] = {
                "run_id": run.run_id,
                "files": run.files_total,
                "analyzed": run.files_analyzed,
                "cached": run.files_cached,
                "warnings": run.warnings,
            }
            if hosted is not None:
                out["removed_runs"] = prune_runs(loaded.output_dir, hosted.keep_runs())
                hosted.after_scan(hosted.project, out)
            return out

        title = "Full rescan" if body.full else "Re-run"
        expected = None if body.full else _last_scan_seconds(loaded.output_dir)
        return _start("scan", title, work, request, expected)

    @app.get("/api/jobs/current")
    def current_job() -> dict[str, Any] | None:
        return jobs.current.info() if jobs.current else None

    @app.get("/api/jobs")
    def all_jobs() -> list[dict[str, Any]]:
        return [j.info() for j in jobs.history]

    def _job(job_id: str) -> Job:
        job = jobs.get(job_id)
        if job is None:
            raise HTTPException(404, "No such job")
        return job

    @app.get("/api/jobs/{job_id}")
    def job_info(job_id: str) -> dict[str, Any]:
        job = _job(job_id)
        return {**job.info(), "log": job.since(0)[-200:]}

    @app.post("/api/jobs/{job_id}/cancel")
    def cancel_job(job_id: str) -> dict[str, Any]:
        job = _job(job_id)
        job.cancel.set()
        return job.info()

    @app.get("/api/jobs/{job_id}/events")
    async def job_events(job_id: str, request: Request) -> StreamingResponse:
        job = _job(job_id)
        start = int(request.headers.get("last-event-id", "-1")) + 1

        async def stream() -> AsyncIterator[str]:
            seq = start
            while True:
                for ev in job.since(seq):
                    seq = ev["seq"] + 1
                    yield f"id: {ev['seq']}\ndata: {json.dumps(ev, default=str)}\n\n"
                    if ev["type"] == "end":
                        return
                if await request.is_disconnected():
                    return
                await anyio.sleep(0.2)

        return StreamingResponse(stream(), media_type="text/event-stream")

    # ---- runs and results ----

    @app.get("/api/runs")
    def runs() -> list[dict[str, Any]]:
        out_dir = ws.loaded().output_dir
        ids = list_runs(out_dir)
        latest = ids[-1] if ids else None
        with contextlib.suppress(RunNotFound):
            latest = resolve_run(out_dir, "latest").name
        return [_run_row(out_dir / "runs" / r, latest) for r in reversed(ids)]

    @app.get("/api/runs/{run}/dashboard")
    def dashboard(run: str) -> dict[str, Any]:
        return _dashboard(ws.run_dir(run))

    @app.get("/api/kinds")
    def kinds() -> dict[str, list[str]]:
        return {k: list(m.model_fields) for k, m in ENTITIES.items()}

    def _facts(run: str) -> Any:
        from legacy_audit.mcp.facts import Facts

        run_dir = ws.run_dir(run)
        return Facts(lambda: run_dir)

    @app.get("/api/runs/{run}/facts/{kind}")
    def facts(
        run: str, kind: str, request: Request, q: str = "", limit: int = 50, offset: int = 0
    ) -> dict[str, Any]:
        from legacy_audit.mcp.facts import FactsError

        reserved = {"q", "limit", "offset"}
        filters = {k: v for k, v in request.query_params.items() if k not in reserved}
        try:
            return _facts(run).query_facts(  # type: ignore[no-any-return]
                kind, filters, limit, offset=offset, search=q or None
            )
        except FactsError as err:
            raise HTTPException(400, str(err)) from None

    @app.get("/api/runs/{run}/component/{name}")
    def component(run: str, name: str) -> dict[str, Any]:
        from legacy_audit.mcp.facts import FactsError

        try:
            return _facts(run).get_component(name)  # type: ignore[no-any-return]
        except FactsError as err:
            raise HTTPException(404, str(err)) from None

    @app.get("/api/runs/{run}/evidence")
    def evidence(run: str, file: str, line: int | None = None) -> dict[str, Any]:
        from legacy_audit.mcp.facts import FactsError
        from legacy_audit.report.data import RunData

        run_dir = ws.run_dir(run)
        try:
            out: dict[str, Any] = _facts(run).read_evidence(file, line, 15)
        except FactsError as err:
            raise HTTPException(404, str(err)) from None
        path = RunData.load(run_dir).abs_path(file)
        out["vscode"] = _vscode_link(path, line) if path and hosted is None else None
        return out

    @app.get("/api/runs/{run}/enrichment")
    def enrichment(run: str) -> dict[str, Any]:
        from legacy_audit.enrich.status import active_stages, compute_status

        run_dir = ws.run_dir(run)
        status = compute_status(run_dir)
        invalid = invalid_stages.get(run_dir.name, set())
        rows = []
        for st in active_stages(run_dir):
            e = status["stages"][st.name]
            state = "missing"
            if e["present"]:
                state = "invalid" if st.name in invalid else "stale" if e.get("stale") else "valid"
                if state == "valid" and not e.get("validated"):
                    state = "not validated"
            why = e.get("changed", []) + [f"stage:{u}" for u in e.get("stale_upstream", [])]
            rows.append(
                {
                    "number": st.number,
                    "name": st.name,
                    "title": st.title,
                    "file": st.file,
                    "state": state,
                    "why": why,
                }
            )
        return {
            "run_id": run_dir.name,
            "stages": rows,
            "next_prompt": _next_prompt(status),
            "ai": _ai_info(),
            "ai_allowed": hosted.ai_allowed() if hosted else True,
        }

    def _ai_info() -> dict[str, Any]:
        from legacy_audit.ai.providers import AiError, settings

        try:
            s = settings(ws.loaded().config.ai)
        except AiError as err:
            return {"provider": "?", "label": "?", "problem": str(err)}
        return {"provider": s.provider, "label": s.label, "problem": None}

    @app.post("/api/runs/{run}/enrich")
    def run_enrichment(run: str, body: EnrichBody, request: Request) -> dict[str, Any]:
        from legacy_audit.ai.providers import AiError, Client, settings
        from legacy_audit.ai.runner import enrich, pick_stages

        if hosted is not None and not hosted.ai_allowed():
            raise HTTPException(403, "The admin has switched off AI enrichment on this site")
        run_dir = ws.run_dir(run)
        try:
            s = settings(ws.loaded().config.ai)
            client = Client(s)
            stages = pick_stages(run_dir, body.stages or None, body.stale)
        except AiError as err:
            raise HTTPException(400, str(err)) from None

        def work(job: Job) -> dict[str, Any]:
            def say(text: str) -> None:
                job.emit("progress", stage=text, n=0, total=0)

            results = enrich(run_dir, s, stages, client, progress=say)
            invalid_stages[run_dir.name] = {r.stage for r in results if not r.ok}
            failed = [r for r in results if not r.ok]
            if failed and not any(r.ok for r in results):
                raise RuntimeError(f"{failed[0].stage}: {failed[0].errors[0]}")
            return {
                "run_id": run_dir.name,
                "valid": [r.stage for r in results if r.ok],
                "invalid": [r.stage for r in failed],
            }

        return _start("enrich", f"AI enrichment with {s.label}", work, request)

    @app.post("/api/runs/{run}/validate")
    def validate(run: str) -> dict[str, Any]:
        from legacy_audit.enrich.validate import validate_run

        run_dir = ws.run_dir(run)
        rep = validate_run(run_dir)
        invalid_stages[run_dir.name] = {m.stage for m in rep.errors if m.stage}
        return {
            "checked": rep.checked,
            "messages": [
                {"level": m.level, "stage": m.stage, "where": m.where, "text": m.text}
                for m in rep.messages
            ],
            **enrichment(run_dir.name),
        }

    @app.get("/api/runs/{run}/reports")
    def reports(run: str) -> dict[str, Any]:
        rd = ws.run_dir(run) / "reports"
        found = {
            "tech": "tech/index.html",
            "functional": "functional/index.html",
            "mgmt": "mgmt/index.html",
            "pptx": "deck.pptx",
            "summary": "summary/index.html",
            "summary_pptx": "summary/summary.pptx",
            "pdf_summary": "pdf/summary.pdf",
            "pdf_mgmt": "pdf/management.pdf",
            "pdf_functional": "pdf/functional.pdf",
            "pdf_tech": "pdf/technical.pdf",
        }
        deck = _read_json(rd / "mgmt" / "deck.json") or {}
        used = deck.get("pptx_template") or {}
        allowed = allowed_reports()
        shown = {k: p for k, p in found.items() if all(n in allowed for n in REPORT_NEEDS[k])}
        return {
            "run_id": rd.parent.name,
            "reports": {
                k: f"runs/{rd.parent.name}/reports/{p}" if (rd / p).is_file() else None
                for k, p in shown.items()
            },
            "pptx_template": used.get("label") if (rd / "deck.pptx").is_file() else None,
            "allowed": sorted(allowed),
        }

    @app.get("/api/pptx-templates")
    def pptx_templates() -> dict[str, Any]:
        from legacy_audit.report.pptx_templates import (
            TemplateError,
            list_templates,
            resolve_template,
        )

        found = list_templates()
        default = found[0].id if found else None
        if ws.config_path.exists():
            with contextlib.suppress(HTTPException, TemplateError):
                loaded = ws.loaded()
                default = resolve_template(
                    loaded.config.branding.pptx_template, loaded.path.parent
                ).id
        return {
            "templates": [
                {
                    "id": t.id,
                    "label": t.label,
                    "file": t.path.name,
                    "thumbnail": f"api/pptx-templates/{t.id}/thumbnail.jpg",
                }
                for t in found
            ],
            "default": default,
        }

    @app.get("/api/pptx-templates/{template_id}/thumbnail.jpg")
    def pptx_template_thumbnail(template_id: str) -> Response:
        from legacy_audit.report.pptx_templates import list_templates, thumbnail

        for t in list_templates():
            if t.id == template_id:
                data = thumbnail(t.path)
                if data is None:
                    break
                return Response(data, media_type="image/jpeg")
        raise HTTPException(404, f"No preview for template '{template_id}'")

    @app.post("/api/runs/{run}/reports")
    def build_reports(run: str, body: ReportBody, request: Request) -> dict[str, Any]:
        from legacy_audit.report.functional import build_functional_report
        from legacy_audit.report.mgmt import build_mgmt_report
        from legacy_audit.report.pptx import build_pptx
        from legacy_audit.report.tech import build_tech_report

        if body.type not in REPORT_TYPES:
            raise HTTPException(400, f"Report type must be one of {', '.join(REPORT_TYPES)}")
        allowed = allowed_reports()
        if body.type != "all":
            need_report(body.type)
        run_dir, loaded = ws.run_dir(run), ws.loaded()
        branding, config_dir = loaded.config.branding, loaded.path.parent
        hide = hosted.hidden_sections() if hosted else set()

        def wanted(kind: str) -> bool:
            return kind in allowed and (body.type in (kind, "all") or body.type == "pdf")

        def work(job: Job) -> dict[str, Any]:
            out: dict[str, Any] = {"run_id": run_dir.name, "messages": []}
            steps = [k for k in ("tech", "functional", "mgmt", "summary") if wanted(k)]
            if body.type in ("pptx", "all") and "pptx" in allowed:
                steps.append("pptx")
            pdf = "pdf" in allowed and (
                body.type == "pdf" or (body.type == "all" and loaded.config.pdf.enabled)
            )
            total = len(steps) + int(pdf)
            done = 0

            def step(label: str) -> None:
                job.emit("progress", stage=label, n=done, total=total)

            if "tech" in steps:
                step("technical report")
                build_tech_report(run_dir, loaded.inputs_dir)
                done += 1
            if "functional" in steps:
                step("functional document")
                build_functional_report(run_dir, loaded.inputs_dir)
                done += 1
            if "mgmt" in steps:
                step("management deck")
                build_mgmt_report(run_dir, branding, config_dir)
                done += 1
            if "summary" in steps:
                from legacy_audit.report.summary import build_summary_report

                step("summary report")
                sres = build_summary_report(
                    run_dir, branding, config_dir, body.template, hide_sections=hide
                )
                out["messages"].append(sres.message)
                done += 1
            if "pptx" in steps:
                step("PowerPoint")
                res = build_pptx(run_dir, branding, config_dir, body.template)
                out["messages"].append(res.message)
                done += 1
            if pdf:
                from legacy_audit.report.pdf import build_pdfs

                step("PDF copies")
                which = tuple(k for k in ("summary", "mgmt", "functional", "tech") if k in allowed)
                pres = build_pdfs(run_dir, loaded.config.pdf, which)
                if pres.files:
                    out["messages"].append(f"PDF copies written: {len(pres.files)}.")
                out["messages"] += pres.messages
            out.update(reports(run_dir.name))
            return out

        return _start("report", f"Build {body.type} report", work, request)

    @app.get("/api/runs/{run}/datapack.zip")
    def datapack(run: str) -> Response:
        need_report("datapack")
        run_dir = ws.run_dir(run)
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
            zf.write(run_dir / "run.json", f"{run_dir.name}/run.json")
            for p in sorted((run_dir / "data").rglob("*")):
                if p.is_file():
                    zf.write(p, f"{run_dir.name}/{p.relative_to(run_dir).as_posix()}")
            for p in sorted((run_dir / "enrichment").rglob("*.json")):
                zf.write(p, f"{run_dir.name}/{p.relative_to(run_dir).as_posix()}")
        name = f"legacy-audit-{run_dir.name}-data.zip"
        return Response(
            buf.getvalue(),
            media_type="application/zip",
            headers={"Content-Disposition": f'attachment; filename="{name}"'},
        )

    @app.post("/api/runs/{run}/open")
    def open_run(run: str) -> dict[str, Any]:
        return open_folder(OpenBody(target="run", run=run))

    @app.get("/api/compare")
    def compare(a: str = "previous", b: str = "latest") -> dict[str, Any]:
        from legacy_audit.store.diff import diff_runs

        result = diff_runs(ws.run_dir(a), ws.run_dir(b))
        for d in result["entities"].values():
            for key in ("added", "removed", "changed"):
                d[f"{key}_count"] = len(d[key])
                d[key] = d[key][:DIFF_LIST]
        return result

    @app.get("/api/doctor")
    def doctor() -> list[dict[str, Any]]:
        from legacy_audit.doctor import run_checks

        cfg, out_dir = None, None
        if ws.config_path.exists():
            try:
                loaded = load_config(ws.config_path)
                cfg, out_dir = loaded.config, loaded.output_dir
            except ConfigError:
                pass
        return [c.__dict__ for c in run_checks(cfg, out_dir)]

    return app
