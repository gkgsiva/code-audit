"""``legacy-audit doctor``: what is installed, what is optional, and what each missing tool costs.

Only Python and the package itself are required. Everything else adds an output (PPTX, diagram
pictures, vulnerability data, JDK checks, Git sources) and its absence is reported, not fatal.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from dataclasses import dataclass
from importlib import metadata
from pathlib import Path

from legacy_audit import __version__
from legacy_audit.config import AuditConfig, GitSource

MIN_PYTHON = (3, 11)
MIN_NODE = 18


@dataclass
class Check:
    name: str
    status: str  # ok | missing | old | error
    required: bool
    detail: str
    needed_for: str

    @property
    def ok(self) -> bool:
        return self.status == "ok"


def _version(cmd: list[str]) -> str | None:
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=30, check=False)
    except (OSError, subprocess.SubprocessError):
        return None
    text = (out.stdout or out.stderr).strip()
    return text.splitlines()[0] if text else ""


def _python() -> Check:
    v = sys.version_info
    ok = (v.major, v.minor) >= MIN_PYTHON
    return Check(
        "Python",
        "ok" if ok else "old",
        True,
        f"{v.major}.{v.minor}.{v.micro} at {sys.executable}",
        "everything (3.11 or later)",
    )


def _packages() -> Check:
    names = ("typer", "pydantic", "sqlglot", "tree-sitter", "Jinja2", "fastapi", "uvicorn")
    missing = []
    for n in names:
        try:
            metadata.version(n)
        except metadata.PackageNotFoundError:
            missing.append(n)
    return Check(
        "Python packages",
        "missing" if missing else "ok",
        True,
        f"missing: {', '.join(missing)} (reinstall legacy-audit)"
        if missing
        else f"legacy-audit {__version__} and its dependencies",
        "scanning, reports and the GUI",
    )


def _node() -> Check:
    from legacy_audit.report.pptx import _node as node_path

    exe = node_path()
    if exe is None:
        return Check(
            "Node.js", "missing", False, "not on PATH", "PowerPoint deck and diagram pictures"
        )
    raw = _version([exe, "--version"]) or ""
    try:
        major = int(raw.lstrip("v").split(".")[0])
    except ValueError:
        major = 0
    status = "ok" if major >= MIN_NODE else "old"
    detail = f"{raw or 'unknown version'} at {exe}" + (
        "" if status == "ok" else f" (need {MIN_NODE}+)"
    )
    return Check("Node.js", status, False, detail, "PowerPoint deck and diagram pictures")


def _pptxgenjs() -> Check:
    from legacy_audit.report.pptx import builder_dir

    pdir = builder_dir()
    if pdir is None:
        return Check("pptxgenjs", "missing", False, "PPTX builder not found", "PowerPoint deck")
    pkg = pdir / "node_modules" / "pptxgenjs" / "package.json"
    if not pkg.is_file():
        return Check(
            "pptxgenjs", "missing", False, f"run 'npm install' in {pdir}", "PowerPoint deck"
        )
    version = json.loads(pkg.read_text(encoding="utf-8")).get("version", "?")
    return Check("pptxgenjs", "ok", False, f"{version} in {pdir}", "PowerPoint deck")


def _mmdc() -> Check:
    from legacy_audit.report.pptx import _mmdc as mmdc_path
    from legacy_audit.report.pptx import builder_dir

    pdir = builder_dir()
    exe = mmdc_path(pdir) if pdir else shutil.which("mmdc")
    return Check(
        "Mermaid CLI (mmdc)",
        "ok" if exe else "missing",
        False,
        exe or "not installed (npm install -g @mermaid-js/mermaid-cli)",
        "diagram pictures in the PPTX (the HTML reports draw diagrams themselves)",
    )


def _tool(name: str, exe: str | None, needed_for: str, hint: str) -> Check:
    return Check(name, "ok" if exe else "missing", False, exe or hint, needed_for)


def _writable(path: Path) -> Check:
    target = path
    while not target.exists() and target != target.parent:
        target = target.parent
    probe = target / ".legacy-audit-write-test"
    try:
        probe.write_text("x", encoding="utf-8")
        probe.unlink()
        status, detail = "ok", str(path)
    except OSError as err:
        status, detail = "error", f"{path}: {err.strerror or err}"
    return Check("Output folder", status, True, detail, "writing runs and reports")


def run_checks(cfg: AuditConfig | None = None, output_dir: Path | None = None) -> list[Check]:
    from legacy_audit.security.jdk import find_jdk_tool
    from legacy_audit.security.vulns import find_tool

    cfg = cfg or AuditConfig.model_validate({"application": {"name": "doctor"}})
    sec = cfg.security
    out = [_python(), _packages()]
    if output_dir is not None:
        out.append(_writable(output_dir))
    node = _node()
    out += [node, _pptxgenjs(), _mmdc()]
    dc = find_tool(
        sec.dependency_check.path,
        ("dependency-check", "dependency-check.sh", "dependency-check.bat"),
    )
    out.append(
        _tool(
            "OWASP Dependency-Check",
            dc,
            "known vulnerabilities in libraries (or import its JSON into audit-inputs/)",
            "not installed; vulnerability data only from imported reports",
        )
    )
    if dc and sec.dependency_check.data_dir and not Path(sec.dependency_check.data_dir).is_dir():
        out[-1].status = "error"
        out[-1].detail = f"data_dir {sec.dependency_check.data_dir} does not exist"
    osv = find_tool(sec.osv_scanner.path, ("osv-scanner", "osv-scanner.exe"))
    out.append(_tool("OSV-Scanner", osv, "known vulnerabilities (alternative)", "not installed"))
    jdeps = find_jdk_tool(sec.jdk_tools, "jdeps")
    out.append(
        _tool(
            "JDK tools (jdeps)",
            jdeps,
            "Java 25 internal-API checks on jars (security.jdk_tools.enabled)",
            "not found" + (f" in {sec.jdk_tools.java_home}" if sec.jdk_tools.java_home else ""),
        )
    )
    git = shutil.which("git")
    out.append(
        Check(
            "Git",
            "ok" if git else "missing",
            isinstance(cfg.source, GitSource),
            git or "not on PATH",
            "Git sources (source.type: git)",
        )
    )
    out.append(_ai(cfg))
    return out


def _ai(cfg: AuditConfig) -> Check:
    """The model `legacy-audit enrich` would use; the key's variable is checked, not its value."""
    import os

    from legacy_audit.ai.providers import AiError, settings

    name, needed = "AI model", "AI enrichment outside Copilot (legacy-audit enrich)"
    try:
        s = settings(cfg.ai)
    except AiError as err:
        return Check(name, "error", False, str(err), needed)
    if s.provider == "copilot":
        pin = f" ({cfg.ai.copilot_model})" if cfg.ai.copilot_model else ""
        return Check(name, "ok", False, f"Copilot in VS Code{pin}", needed)
    if not s.model:
        return Check(name, "missing", False, f"{s.provider}: set ai.model", needed)
    env = s.key_env
    keyless = s.provider == "ollama" or (s.provider == "openai" and s.base_url)
    if env and not os.environ.get(env) and not (keyless and not s.api_key_env):
        return Check(
            name, "missing", False, f"{s.label}: environment variable {env} not set", needed
        )
    return Check(
        name, "ok", False, f"{s.label} at {s.url} (test: legacy-audit enrich --check)", needed
    )
