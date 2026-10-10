"""The local GUI: a FastAPI app over the same functions the CLI uses (no logic of its own).

Bound to 127.0.0.1 only. Requests whose Host is not localhost are refused (DNS rebinding), and
every change (POST/PUT/DELETE) needs the ``X-Legacy-Audit`` header, which a page on another site
cannot send without a CORS pre-flight this server never grants. Run ids, file names and evidence
paths are checked so nothing outside the workspace, the run folders and the scanned source can be
read or written.
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
from legacy_audit.gui.jobs import Busy, Job, Jobs
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
REPORT_TYPES = ("tech", "mgmt", "pptx", "all")
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


class ReportBody(BaseModel):
    type: str = "tech"


class OpenBody(BaseModel):
    target: str  # workspace | inputs | output | run
    run: str | None = None


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
    folder: Path, port: int = 8765, opener: Callable[[Path], None] = open_in_os
) -> FastAPI:
    ws = Workspace(folder)
    jobs = Jobs()
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
    allowed_hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}
    allowed_origins = {f"http://127.0.0.1:{port}", f"http://localhost:{port}"}

    @app.middleware("http")
    async def guard(request: Request, call_next: Any) -> Response:
        host = request.headers.get("host", "")
        if host not in allowed_hosts:
            return JSONResponse({"detail": "Host not allowed"}, status_code=403)
        if request.method not in ("GET", "HEAD", "OPTIONS"):
            origin = request.headers.get("origin")
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

    app.mount("/static", StaticFiles(directory=STATIC), name="static")
    app.mount("/vendor", StaticFiles(directory=VENDOR), name="vendor")
    app.mount("/assets", StaticFiles(directory=VENDOR.parent), name="assets")

    @app.get("/runs/{run}/reports/{path:path}", include_in_schema=False)
    def report_file(run: str, path: str) -> FileResponse:
        base = (ws.run_dir(run) / "reports").resolve()
        target = (base / path).resolve()
        if not target.is_relative_to(base) or not target.is_file():
            raise HTTPException(404, "No such report file")
        return FileResponse(target)

    # ---- workspace and configuration ----

    @app.get("/api/workspace")
    def workspace() -> dict[str, Any]:
        out: dict[str, Any] = {
            "folder": str(ws.folder),
            "config": ws.config_path.exists(),
            "version": __version__,
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

        ws.loaded()
        changes = body.model_dump(exclude_none=True)
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

    def _start(kind: str, title: str, work: Callable[[Job], dict[str, Any]]) -> dict[str, Any]:
        try:
            return jobs.start(kind, title, work).info()
        except Busy as err:
            raise HTTPException(409, str(err)) from None

    @app.post("/api/scan")
    def scan(body: ScanBody) -> dict[str, Any]:
        from legacy_audit.scan import run_scan
        from legacy_audit.store.lock import ScanBusy

        loaded = ws.loaded()

        def work(job: Job) -> dict[str, Any]:
            try:
                result = run_scan(
                    load_config(loaded.path),
                    full=body.full,
                    progress=job.progress,
                    cancel=job.cancel,
                )
            except ScanBusy as err:
                raise RuntimeError(str(err)) from None
            run = result.run
            return {
                "run_id": run.run_id,
                "files": run.files_total,
                "analyzed": run.files_analyzed,
                "cached": run.files_cached,
                "warnings": run.warnings,
            }

        return _start("scan", "Full rescan" if body.full else "Re-run", work)

    @app.get("/api/jobs/current")
    def current_job() -> dict[str, Any] | None:
        return jobs.current.info() if jobs.current else None

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
        out["vscode"] = _vscode_link(path, line) if path else None
        return out

    @app.get("/api/runs/{run}/enrichment")
    def enrichment(run: str) -> dict[str, Any]:
        from legacy_audit.enrich.stages import STAGES
        from legacy_audit.enrich.status import compute_status

        run_dir = ws.run_dir(run)
        status = compute_status(run_dir)
        invalid = invalid_stages.get(run_dir.name, set())
        rows = []
        for st in STAGES:
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
        return {"run_id": run_dir.name, "stages": rows, "next_prompt": _next_prompt(status)}

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
        found = {"tech": "tech/index.html", "mgmt": "mgmt/deck.html", "pptx": "deck.pptx"}
        return {
            "run_id": rd.parent.name,
            "reports": {
                k: f"/runs/{rd.parent.name}/reports/{p}" if (rd / p).is_file() else None
                for k, p in found.items()
            },
        }

    @app.post("/api/runs/{run}/reports")
    def build_reports(run: str, body: ReportBody) -> dict[str, Any]:
        from legacy_audit.report.mgmt import build_mgmt_report
        from legacy_audit.report.pptx import build_pptx
        from legacy_audit.report.tech import build_tech_report

        if body.type not in REPORT_TYPES:
            raise HTTPException(400, f"Report type must be one of {', '.join(REPORT_TYPES)}")
        run_dir, loaded = ws.run_dir(run), ws.loaded()
        branding, config_dir = loaded.config.branding, loaded.path.parent

        def work(job: Job) -> dict[str, Any]:
            out: dict[str, Any] = {"run_id": run_dir.name, "messages": []}
            if body.type in ("tech", "all"):
                job.emit("progress", stage="technical report", n=0, total=0)
                build_tech_report(run_dir, loaded.inputs_dir)
            if body.type in ("mgmt", "all"):
                job.emit("progress", stage="management deck", n=0, total=0)
                build_mgmt_report(run_dir, branding, config_dir)
            if body.type in ("pptx", "all"):
                job.emit("progress", stage="PowerPoint", n=0, total=0)
                res = build_pptx(run_dir, branding, config_dir)
                out["messages"].append(res.message)
            out.update(reports(run_dir.name))
            return out

        return _start("report", f"Build {body.type} report", work)

    @app.get("/api/runs/{run}/datapack.zip")
    def datapack(run: str) -> Response:
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
