"""Command-line interface: ``legacy-audit init | scan | report | validate | facts | mcp | diff |
purge-cache | version``."""

from __future__ import annotations

import json
import logging
from importlib.resources import files
from pathlib import Path
from typing import Annotated

import typer
from rich.console import Console
from rich.logging import RichHandler
from rich.progress import BarColumn, MofNCompleteColumn, Progress, TextColumn
from rich.table import Table

from legacy_audit import __version__
from legacy_audit.config import INPUT_KINDS, ConfigError, load_config
from legacy_audit.inventory.inputs import coverage_line
from legacy_audit.source import SourceError
from legacy_audit.source.git import default_cache_dir, purge_cache
from legacy_audit.store.diff import diff_runs
from legacy_audit.store.runs import RunNotFound, resolve_run
from legacy_audit.store.writer import write_json

app = typer.Typer(
    add_completion=False,
    no_args_is_help=True,
    help="Audit a legacy codebase before migration. Scripts count, AI interprets.",
)
console = Console()
err_console = Console(stderr=True)

ConfigOpt = Annotated[
    Path, typer.Option("--config", "-c", help="Path to audit.yaml", dir_okay=False)
]


def _setup_logging(verbose: bool) -> None:
    logger = logging.getLogger("legacy_audit")
    if not any(isinstance(h, RichHandler) for h in logger.handlers):
        handler = RichHandler(console=err_console, show_path=False, show_time=False)
        handler.setLevel(logging.INFO if verbose else logging.WARNING)
        logger.addHandler(handler)


def _fail(message: str) -> typer.Exit:
    err_console.print(f"[red]Error:[/red] {message}")
    return typer.Exit(code=1)


def _template(name: str) -> str:
    return files("legacy_audit.templates").joinpath(name).read_text(encoding="utf-8")


@app.command()
def version() -> None:
    """Print the tool version."""
    console.print(f"legacy-audit {__version__}")


@app.command()
def init(
    folder: Annotated[Path, typer.Argument(help="Legacy workspace folder")] = Path("."),
    name: Annotated[str | None, typer.Option(help="Application name")] = None,
    force: Annotated[bool, typer.Option(help="Overwrite existing starter files")] = False,
    copilot_layout: Annotated[
        str,
        typer.Option(
            help="Copilot agent files: agents (.agent.md), chatmodes (older VS Code), both, none"
        ),
    ] = "both",
) -> None:
    """Create audit.yaml, audit-overrides.yaml, audit-inputs/ and the Copilot agent files."""
    from legacy_audit.copilot.install import LAYOUTS, install

    if copilot_layout not in LAYOUTS:
        raise _fail(f"--copilot-layout must be one of {', '.join(LAYOUTS)}")
    folder = folder.resolve()
    folder.mkdir(parents=True, exist_ok=True)
    app_name = name or folder.name or "application"
    created: list[str] = []
    for fname, content in (
        ("audit.yaml", _template("audit.yaml").format(name=json.dumps(app_name))),
        ("audit-overrides.yaml", _template("audit-overrides.yaml")),
    ):
        target = folder / fname
        if target.exists() and not force:
            console.print(f"[yellow]Kept existing {fname}[/yellow] (use --force to overwrite)")
            continue
        target.write_text(content, encoding="utf-8", newline="\n")
        created.append(fname)
    for kind in INPUT_KINDS:
        d = folder / "audit-inputs" / kind
        if not d.exists():
            d.mkdir(parents=True)
            (d / ".gitkeep").touch()
            created.append(f"audit-inputs/{kind}/")
    for item in created:
        console.print(f"[green]created[/green] {item}")
    kept = []
    for done in install(folder, copilot_layout):
        if done.action in ("created", "updated"):
            console.print(f"[green]{done.action}[/green] {done.path}")
        elif done.action == "kept":
            kept.append(done.path)
    for path in kept:
        console.print(
            f"[yellow]kept your edits[/yellow] {path} (new version written to {path}.new)"
        )
    console.print(
        "Next: review audit.yaml, then run [bold]legacy-audit scan[/bold], or in VS Code open "
        "Copilot Chat, pick the [bold]legacy-auditor[/bold] agent and run /audit-scan."
    )


@app.command()
def scan(
    config: ConfigOpt = Path("audit.yaml"),
    full: Annotated[bool, typer.Option("--full", help="Ignore the file cache")] = False,
    output_dir: Annotated[
        Path | None, typer.Option("--output-dir", "-o", help="Override output_dir")
    ] = None,
    verbose: Annotated[bool, typer.Option("--verbose", "-v")] = False,
) -> None:
    """Scan the configured sources and write a new run."""
    from legacy_audit.scan import run_scan

    _setup_logging(verbose)
    try:
        loaded = load_config(config)
    except ConfigError as err:
        raise _fail(str(err)) from None
    try:
        with Progress(
            TextColumn("[bold]{task.description}"),
            BarColumn(),
            MofNCompleteColumn(),
            console=err_console,
            transient=True,
            disable=not err_console.is_terminal,
        ) as bar:
            task = bar.add_task("scanning", total=None)

            def progress(stage: str, n: int, total: int) -> None:
                bar.update(task, description=stage, completed=n, total=total)

            result = run_scan(loaded, full=full, output_dir=output_dir, progress=progress)
    except (ConfigError, SourceError) as err:
        raise _fail(str(err)) from None

    run = result.run
    totals = {
        "files": run.files_total,
        "analyzed": run.files_analyzed,
        "from cache": run.files_cached,
        "components": len(result.facts.get("components", [])),
    }
    console.print(f"[bold green]Run {run.run_id}[/bold green] -> {result.run_dir}")
    console.print("  " + ", ".join(f"{k}: {v}" for k, v in totals.items()))
    for c in run.coverage:
        console.print(f"  {coverage_line(c)}")
    for w in run.warnings:
        console.print(f"  [yellow]warning:[/yellow] {w}")


@app.command()
def diff(
    run_a: Annotated[str, typer.Argument(help="Run id, 'previous', 'latest' or folder")],
    run_b: Annotated[str, typer.Argument(help="Run id, 'previous', 'latest' or folder")] = "latest",
    config: ConfigOpt = Path("audit.yaml"),
    output_dir: Annotated[Path | None, typer.Option("--output-dir", "-o")] = None,
    json_out: Annotated[
        Path | None, typer.Option("--json", help="Write the full diff as JSON here")
    ] = None,
    show: Annotated[int, typer.Option(help="Records listed per entity")] = 10,
) -> None:
    """Compare two runs: counts and added / removed / changed records per entity."""
    out_dir = output_dir
    if out_dir is None:
        try:
            out_dir = load_config(config).output_dir
        except ConfigError as err:
            raise _fail(str(err)) from None
    try:
        a, b = resolve_run(out_dir, run_a), resolve_run(out_dir, run_b)
    except RunNotFound as err:
        raise _fail(str(err)) from None
    result = diff_runs(a, b)

    table = Table(title=f"{result['run_a']}  ->  {result['run_b']}")
    for col in ("entity", "before", "after", "added", "removed", "changed"):
        table.add_column(col, justify="left" if col == "entity" else "right")
    for entity, d in result["entities"].items():
        if d["count_a"] or d["count_b"]:
            table.add_row(
                entity,
                str(d["count_a"]),
                str(d["count_b"]),
                str(len(d["added"])),
                str(len(d["removed"])),
                str(len(d["changed"])),
            )
    console.print(table)
    for entity, d in result["entities"].items():
        lines = [f"  + {r['label']}" for r in d["added"]]
        lines += [f"  - {r['label']}" for r in d["removed"]]
        for c in d["changed"]:
            fields = ", ".join(
                f"{k}: {v[0]} -> {v[1]}"
                for k, v in c["fields"].items()
                if not isinstance(v[0], list | dict)
            )
            lines.append(f"  ~ {c['label']}" + (f" ({fields})" if fields else ""))
        if lines:
            console.print(f"[bold]{entity}[/bold]")
            for line in lines[:show]:
                console.print(line, markup=False, highlight=False)
            if len(lines) > show:
                console.print(f"  ... {len(lines) - show} more (see --json)")
    if json_out:
        write_json(json_out, result)
        console.print(f"Diff written to {json_out}")


REPORT_TYPES = ("tech", "mgmt", "pptx", "all")


@app.command()
def report(
    type_: Annotated[str, typer.Option("--type", "-t", help="tech, mgmt, pptx or all")] = "tech",
    run: Annotated[str, typer.Option("--run", help="Run id, 'latest' or 'previous'")] = "latest",
    config: ConfigOpt = Path("audit.yaml"),
    output_dir: Annotated[Path | None, typer.Option("--output-dir", "-o")] = None,
    open_: Annotated[bool, typer.Option("--open", help="Open the report in the browser")] = False,
) -> None:
    """Build reports from a run (default: the latest) into <run>/reports/."""
    from legacy_audit.report.mgmt import build_mgmt_report
    from legacy_audit.report.pptx import build_pptx
    from legacy_audit.report.tech import build_tech_report

    if type_ not in REPORT_TYPES:
        raise _fail(f"Unknown report type '{type_}' (use {', '.join(REPORT_TYPES)})")
    try:
        loaded = load_config(config)
    except ConfigError as err:
        raise _fail(str(err)) from None
    try:
        run_dir = resolve_run(output_dir or loaded.output_dir, run)
    except RunNotFound as err:
        raise _fail(f"{err}. Run 'legacy-audit scan' first.") from None
    branding, config_dir = loaded.config.branding, loaded.path.parent
    to_open: Path | None = None
    if type_ in ("tech", "all"):
        index = build_tech_report(run_dir, loaded.inputs_dir)
        console.print(f"[bold green]Technical report[/bold green] -> {index}")
        to_open = index
    if type_ in ("mgmt", "all"):
        deck = build_mgmt_report(run_dir, branding, config_dir)
        console.print(f"[bold green]Management deck[/bold green] -> {deck}")
        to_open = to_open or deck
    if type_ in ("pptx", "all"):
        result = build_pptx(run_dir, branding, config_dir)
        if result.path is not None:
            console.print(f"[bold green]PowerPoint[/bold green] -> {result.path}")
            if result.message != "PPTX written.":
                console.print(f"  {result.message.removeprefix('PPTX written. ')}")
        else:
            console.print(f"[yellow]{result.message}[/yellow]")
            if type_ == "pptx":
                raise typer.Exit(code=1)
    if type_ in ("mgmt", "pptx", "all"):
        from legacy_audit.enrich.status import compute_status

        status = compute_status(run_dir)
        if status["missing"] or status["stale"]:
            console.print(
                f"  Enrichment: {len(status['missing'])} stage(s) missing, "
                f"{len(status['stale'])} stale; their slides show a placeholder or a warning "
                "(run /audit-enrich in Copilot, then legacy-audit validate)."
            )
    if open_ and to_open is not None:
        import webbrowser

        webbrowser.open(to_open.resolve().as_uri())


def _resolve(config: Path, run: str, output_dir: Path | None) -> tuple[Path, Path]:
    """(run folder, inputs folder) for a command that reads an existing run."""
    try:
        loaded = load_config(config)
    except ConfigError as err:
        raise _fail(str(err)) from None
    try:
        return resolve_run(output_dir or loaded.output_dir, run), loaded.inputs_dir
    except RunNotFound as err:
        raise _fail(f"{err}. Run 'legacy-audit scan' first.") from None


@app.command()
def validate(
    run: Annotated[str, typer.Option("--run", help="Run id, 'latest' or 'previous'")] = "latest",
    stage: Annotated[
        list[str] | None, typer.Option("--stage", "-s", help="Only these stages (repeatable)")
    ] = None,
    restamp: Annotated[
        bool,
        typer.Option(help="Record the current facts as the stages' inputs (after re-running them)"),
    ] = False,
    config: ConfigOpt = Path("audit.yaml"),
    output_dir: Annotated[Path | None, typer.Option("--output-dir", "-o")] = None,
) -> None:
    """Check the Copilot enrichment against the scan; mark stale stages in status.json."""
    from legacy_audit.enrich.stages import STAGES
    from legacy_audit.enrich.validate import validate_run

    run_dir, _ = _resolve(config, run, output_dir)
    rep = validate_run(run_dir, stage, restamp=restamp)
    for m in rep.messages:
        colour = "red" if m.level == "error" else "yellow"
        console.print(f"[{colour}]{m}[/{colour}]")
    table = Table("#", "stage", "file", "state")
    for st in STAGES:
        e = rep.status["stages"][st.name]
        if not e["present"]:
            state = "[dim]missing[/dim]"
        elif any(m.stage == st.name and m.level == "error" for m in rep.messages):
            state = "[red]invalid[/red]"
        elif e.get("stale"):
            why = ", ".join(
                e.get("changed", []) + [f"stage:{u}" for u in e.get("stale_upstream", [])]
            )
            state = f"[yellow]stale[/yellow] ({why})"
        else:
            state = "[green]valid[/green]"
        table.add_row(str(st.number), st.name, st.file, state)
    console.print(table)
    console.print(
        f"{len(rep.checked)} checked, {len(rep.errors)} error(s), {len(rep.warnings)} "
        f"warning(s); status -> {run_dir / 'enrichment' / 'status.json'}"
    )
    if rep.errors:
        raise typer.Exit(code=1)


@app.command()
def facts(
    kind: Annotated[str, typer.Argument(help="services, screens, sql_statements, ...")],
    filter_: Annotated[
        list[str] | None,
        typer.Option("--filter", "-f", help="field=value (repeatable); field=~text for contains"),
    ] = None,
    fields: Annotated[str | None, typer.Option(help="Comma-separated fields to show")] = None,
    limit: Annotated[int, typer.Option(help="At most this many rows (max 500)")] = 50,
    run: Annotated[str, typer.Option("--run", help="Run id, 'latest' or 'previous'")] = "latest",
    config: ConfigOpt = Path("audit.yaml"),
    output_dir: Annotated[Path | None, typer.Option("--output-dir", "-o")] = None,
) -> None:
    """Print facts of one kind as JSON (read-only; for the Copilot agent and for people)."""
    from legacy_audit.mcp.facts import Facts, FactsError

    run_dir, _ = _resolve(config, run, output_dir)
    filters: dict[str, str] = {}
    for f in filter_ or []:
        key, sep, value = f.partition("=")
        if not sep:
            raise _fail(f"--filter needs field=value, got '{f}'")
        filters[key.strip()] = value
    cols = [c.strip() for c in fields.split(",")] if fields else None
    try:
        result = Facts(lambda: run_dir).query_facts(kind, filters, limit, cols)
    except FactsError as err:
        raise _fail(str(err)) from None
    typer.echo(json.dumps(result, indent=2, ensure_ascii=False))


@app.command()
def mcp(
    config: ConfigOpt = Path("audit.yaml"),
    output_dir: Annotated[Path | None, typer.Option("--output-dir", "-o")] = None,
) -> None:
    """Serve the latest run's facts to Copilot as a read-only MCP server (stdio)."""
    import sys

    from legacy_audit.mcp.facts import Facts, FactsError
    from legacy_audit.mcp.server import Server

    try:
        loaded = load_config(config)
    except ConfigError as err:
        raise _fail(str(err)) from None
    out = output_dir or loaded.output_dir

    def latest() -> Path:
        try:
            return resolve_run(out, "latest")
        except RunNotFound as err:
            raise FactsError(f"{err}. Run 'legacy-audit scan' first.") from None

    for stream in (sys.stdin, sys.stdout):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8")
    Server(Facts(latest)).serve()


@app.command("purge-cache")
def purge_cache_cmd(
    config: Annotated[
        Path | None,
        typer.Option("--config", "-c", help="Also clear this config's per-file scan cache"),
    ] = None,
    cache_dir: Annotated[Path | None, typer.Option(help="Git clone cache folder")] = None,
) -> None:
    """Delete cloned Git sources (and, with --config, the per-file scan cache)."""
    target = purge_cache(cache_dir or default_cache_dir())
    console.print(f"Removed Git clone cache {target}")
    if config is not None:
        try:
            loaded = load_config(config)
        except ConfigError as err:
            raise _fail(str(err)) from None
        file_cache = loaded.output_dir / "cache"
        if file_cache.exists():
            import shutil

            shutil.rmtree(file_cache)
        console.print(f"Removed file cache {file_cache}")


if __name__ == "__main__":
    app()
