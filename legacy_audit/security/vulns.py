"""Known vulnerabilities per library from OWASP Dependency-Check and OSV-Scanner.

Reports are imported from ``audit-inputs/security/*.json`` (the kind is detected from the
content) or produced by running the tools when they are installed. Both work offline: the
Dependency-Check run uses ``--noupdate`` and OSV-Scanner (2.x) ``--offline`` unless the config says
otherwise. A tool that is not installed, fails or has no database is a coverage gap
(:class:`VulnerabilitySource` with status ``missing``/``failed``), never a crash and never a
silent "no vulnerabilities".
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from legacy_audit.config import DependencyCheckConfig, OsvScannerConfig
from legacy_audit.model import Library, Vulnerability, VulnerabilitySource

log = logging.getLogger("legacy_audit")

DC = "dependency-check"
OSV = "osv-scanner"
SEVERITY_ORDER = ("unknown", "low", "medium", "high", "critical")


@dataclass
class ReportEntry:
    """Vulnerabilities reported for one dependency, with the keys used to match a library."""

    sha1: str | None
    purls: list[str]
    file_name: str | None
    vulnerabilities: list[Vulnerability]


@dataclass
class Report:
    tool: str
    path: str
    database_date: str | None = None
    entries: list[ReportEntry] = field(default_factory=list)


# ---- parsing -------------------------------------------------------------------------------------


def _severity_from_score(score: float | None) -> str:
    if score is None:
        return "unknown"
    if score >= 9.0:
        return "critical"
    if score >= 7.0:
        return "high"
    if score >= 4.0:
        return "medium"
    return "low" if score > 0 else "unknown"


def _norm_severity(value: Any) -> str:
    s = str(value or "").strip().lower()
    if s == "moderate":
        return "medium"
    return s if s in SEVERITY_ORDER else "unknown"


def detect_kind(data: Any) -> str | None:
    if isinstance(data, dict):
        if "dependencies" in data and ("reportSchema" in data or "scanInfo" in data):
            return DC
        if isinstance(data.get("results"), list):
            return OSV
    return None


def parse_dependency_check(data: dict[str, Any], path: str) -> Report:
    dates = [
        str(s.get("timestamp"))
        for s in (data.get("scanInfo") or {}).get("dataSource") or []
        if s.get("timestamp")
    ]
    report = Report(tool=DC, path=path, database_date=max(dates)[:10] if dates else None)
    for dep in data.get("dependencies") or []:
        vulns: list[Vulnerability] = []
        for v in dep.get("vulnerabilities") or []:
            v3 = v.get("cvssv3") or {}
            v2 = v.get("cvssv2") or {}
            score = v3.get("baseScore", v2.get("score"))
            severity = _norm_severity(v.get("severity") or v3.get("baseSeverity"))
            if severity == "unknown":
                severity = _severity_from_score(score)
            fixed = set()
            for sw in v.get("vulnerableSoftware") or []:
                soft = sw.get("software") or {}
                if soft.get("versionEndExcluding"):
                    fixed.add(str(soft["versionEndExcluding"]))
            vulns.append(
                Vulnerability(
                    id=str(v.get("name")),
                    severity=severity,
                    cvss=float(score) if score is not None else None,
                    fixed_versions=sorted(fixed),
                    sources=[DC],
                    description=(v.get("description") or "")[:300] or None,
                )
            )
        if vulns:
            report.entries.append(
                ReportEntry(
                    sha1=(dep.get("sha1") or "").lower() or None,
                    purls=[str(p["id"]) for p in dep.get("packages") or [] if p.get("id")],
                    file_name=dep.get("fileName"),
                    vulnerabilities=vulns,
                )
            )
    return report


def _osv_fixed(vuln: dict[str, Any], name: str) -> list[str]:
    out = set()
    for aff in vuln.get("affected") or []:
        if (aff.get("package") or {}).get("name") not in (None, name):
            continue
        for rng in aff.get("ranges") or []:
            for ev in rng.get("events") or []:
                if ev.get("fixed"):
                    out.add(str(ev["fixed"]))
    return sorted(out)


def parse_osv(data: dict[str, Any], path: str) -> Report:
    report = Report(tool=OSV, path=path)
    for result in data.get("results") or []:
        for pkg in result.get("packages") or []:
            info = pkg.get("package") or {}
            name, version = str(info.get("name") or ""), info.get("version")
            eco = str(info.get("ecosystem") or "").lower()
            scores: dict[str, float] = {}
            for g in pkg.get("groups") or []:
                try:
                    score = float(g.get("max_severity"))
                except (TypeError, ValueError):
                    continue
                for i in g.get("ids") or []:
                    scores[i] = score
            vulns = []
            for v in pkg.get("vulnerabilities") or []:
                vid = str(v.get("id"))
                cvss = scores.get(vid)
                sev = _norm_severity((v.get("database_specific") or {}).get("severity"))
                if sev == "unknown":
                    sev = _severity_from_score(cvss)
                vulns.append(
                    Vulnerability(
                        id=vid,
                        aliases=[str(a) for a in v.get("aliases") or []],
                        severity=sev,
                        cvss=cvss,
                        fixed_versions=_osv_fixed(v, name),
                        sources=[OSV],
                        description=(v.get("summary") or "")[:300] or None,
                    )
                )
            if not vulns:
                continue
            purl = None
            if eco == "maven" and ":" in name:
                g, a = name.split(":", 1)
                purl = f"pkg:maven/{g}/{a}@{version}"
            elif eco == "npm":
                purl = f"pkg:npm/{name}@{version}"
            report.entries.append(
                ReportEntry(
                    sha1=None, purls=[purl] if purl else [], file_name=None, vulnerabilities=vulns
                )
            )
    return report


def load_report(path: Path, display: str) -> Report | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as err:
        log.warning("Vulnerability report %s could not be read: %s", display, err)
        return None
    kind = detect_kind(data)
    if kind == DC:
        return parse_dependency_check(data, display)
    if kind == OSV:
        return parse_osv(data, display)
    log.warning("Vulnerability report %s: not a Dependency-Check or OSV-Scanner JSON", display)
    return None


# ---- running the tools ---------------------------------------------------------------------------


def find_tool(configured: str | None, names: tuple[str, ...]) -> str | None:
    if configured:
        p = Path(configured)
        return str(p) if p.is_file() else shutil.which(configured)
    for n in names:
        found = shutil.which(n)
        if found:
            return found
    return None


def run_dependency_check(
    cfg: DependencyCheckConfig, scan_dirs: list[Path], out_dir: Path, project: str
) -> tuple[Report | None, VulnerabilitySource]:
    exe = find_tool(cfg.path, ("dependency-check", "dependency-check.sh", "dependency-check.bat"))
    if exe is None:
        return None, VulnerabilitySource(name=DC, status="missing", detail="not installed")
    out_dir.mkdir(parents=True, exist_ok=True)
    cmd = [exe, "--project", project, "--format", "JSON", "--out", str(out_dir)]
    for d in scan_dirs:
        cmd += ["--scan", str(d)]
    if cfg.data_dir:
        cmd += ["--data", cfg.data_dir]
    if cfg.offline:
        cmd.append("--noupdate")
    elif cfg.nvd_mirror_url:
        cmd += ["--nvdDatafeed", cfg.nvd_mirror_url]
    return _run(cmd, cfg.timeout_minutes, DC, out_dir / "dependency-check-report.json")


def run_osv_scanner(
    cfg: OsvScannerConfig, scan_dirs: list[Path], out_dir: Path
) -> tuple[Report | None, VulnerabilitySource]:
    exe = find_tool(cfg.path, ("osv-scanner", "osv-scanner.exe"))
    if exe is None:
        return None, VulnerabilitySource(name=OSV, status="missing", detail="not installed")
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / "osv-scanner-report.json"
    # OSV-Scanner 2.x: --offline uses the downloaded databases; java/archive adds jar files to
    # the build files it reads by default. Exit code 1 means vulnerabilities were found.
    cmd = [exe, "--format", "json", "--output", str(out), "--recursive"]
    cmd += ["--experimental-plugins", "java/archive"]
    if cfg.offline:
        cmd.append("--offline")
    env = None
    if cfg.data_dir:  # 2.x takes the database folder from the environment only
        env = {**os.environ, "OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY": cfg.data_dir}
    cmd += [str(d) for d in scan_dirs]
    return _run(cmd, cfg.timeout_minutes, OSV, out, ok_codes=(0, 1), env=env)


# Known scanner failures, in plain words with the fix (searched in the tool's whole output).
FAILURE_HINTS: tuple[tuple[str, str, str], ...] = (
    (
        OSV,
        "no offline version of the OSV database",
        "offline mode but no OSV database downloaded: set security.osv_scanner.offline: false "
        "to ask osv.dev, or download the database",
    ),
    (
        OSV,
        "flag provided but not defined",
        "OSV-Scanner 1.x is too old: install version 2",
    ),
    (
        OSV,
        "no package sources found",
        "no build files (pom.xml, Gradle) or .jar files found in the code folders",
    ),
    (
        DC,
        "No documents exist",
        "its NVD database is not downloaded yet: run dependency-check --updateonly with an NVD "
        "API key once, or set security.dependency_check.enabled: false",
    ),
)


def failure_hint(tool: str, output: str) -> str | None:
    low = output.lower()
    return next((h for t, needle, h in FAILURE_HINTS if t == tool and needle.lower() in low), None)


def _run(
    cmd: list[str],
    minutes: int,
    tool: str,
    out: Path,
    ok_codes: tuple[int, ...] = (0,),
    env: dict[str, str] | None = None,
) -> tuple[Report | None, VulnerabilitySource]:
    log.info("Running %s", " ".join(cmd))
    try:
        proc = subprocess.run(
            cmd, capture_output=True, text=True, timeout=minutes * 60, check=False, env=env
        )
    except (OSError, subprocess.TimeoutExpired) as err:
        return None, VulnerabilitySource(name=tool, status="failed", detail=type(err).__name__)
    if proc.returncode not in ok_codes or not out.is_file():
        text = f"{proc.stderr or ''}\n{proc.stdout or ''}"
        tail = (proc.stderr or proc.stdout or "").strip().splitlines()[-1:] or [""]
        detail = re.sub(r"\s+", " ", f"exit code {proc.returncode}: {tail[0]}")[:200]
        hint = failure_hint(tool, text)
        if hint:
            detail = f"{hint} ({detail})"
        return None, VulnerabilitySource(name=tool, status="failed", detail=detail)
    report = load_report(out, out.name)
    if report is None:
        return None, VulnerabilitySource(name=tool, status="failed", detail="unreadable report")
    return report, VulnerabilitySource(
        name=tool, status="ran", database_date=report.database_date, reports=[out.name]
    )


# ---- matching ------------------------------------------------------------------------------------


def purl(lib: Library) -> str | None:
    if not lib.version:
        return None
    if lib.language == "javascript" or lib.source in ("npm", "js-file"):
        return f"pkg:npm/{lib.artifact}@{lib.version}"
    if lib.group:
        return f"pkg:maven/{lib.group}/{lib.artifact}@{lib.version}"
    return None


def _file_name(lib: Library) -> str:
    return f"{lib.artifact}-{lib.version}.jar" if lib.version else f"{lib.artifact}.jar"


def _purl_key(p: str) -> str:
    return p.split("?", 1)[0].lower()


def apply_reports(libraries: list[Library], reports: list[Report]) -> int:
    """Attach vulnerabilities to libraries (by SHA-1, then package URL, then jar file name);
    merge duplicates by CVE id. Returns how many report entries matched no library."""
    by_sha1 = {lib.sha1.lower(): lib for lib in libraries if lib.sha1}
    by_purl = {_purl_key(p): lib for lib in libraries if (p := purl(lib))}
    by_file = {_file_name(lib).lower(): lib for lib in libraries}
    unmatched = 0
    for report in reports:
        for entry in report.entries:
            lib = by_sha1.get(entry.sha1 or "")
            if lib is None:
                lib = next(
                    (by_purl[k] for p in entry.purls if (k := _purl_key(p)) in by_purl), None
                )
            if lib is None and entry.file_name:
                lib = by_file.get(entry.file_name.lower())
            if lib is None:
                unmatched += 1
                continue
            _merge(lib, entry.vulnerabilities)
    for lib in libraries:
        if lib.vulnerabilities:
            lib.vulnerabilities.sort(key=lambda v: (-SEVERITY_ORDER.index(v.severity), v.id))
            lib.cves = sorted({v.id for v in lib.vulnerabilities} | set(lib.cves))
            lib.max_severity = max(
                (v.severity for v in lib.vulnerabilities), key=SEVERITY_ORDER.index
            )
    return unmatched


def _merge(lib: Library, vulns: list[Vulnerability]) -> None:
    known: dict[str, Vulnerability] = {}
    for v in lib.vulnerabilities:
        for key in (v.id, *v.aliases):
            known[key] = v
    for v in vulns:
        match = next((known[k] for k in (v.id, *v.aliases) if k in known), None)
        if match is None:
            # Prefer the CVE id as the main id so both tools agree.
            cve = next((a for a in v.aliases if a.startswith("CVE-")), None)
            if cve and not v.id.startswith("CVE-"):
                v = v.model_copy(update={"id": cve, "aliases": sorted({*v.aliases, v.id} - {cve})})
            lib.vulnerabilities.append(v)
            for key in (v.id, *v.aliases):
                known[key] = v
            continue
        match.sources = sorted({*match.sources, *v.sources})
        match.aliases = sorted({*match.aliases, *v.aliases, v.id} - {match.id})
        match.fixed_versions = sorted({*match.fixed_versions, *v.fixed_versions})
        if match.cvss is None:
            match.cvss = v.cvss
        if SEVERITY_ORDER.index(v.severity) > SEVERITY_ORDER.index(match.severity):
            match.severity = v.severity
        match.description = match.description or v.description
