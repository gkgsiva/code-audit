"""Hosting settings: what the environment (Dockerfile, compose) sets, and what the admin page
saves to ``<audits root>/.legacy-audit/admin.yaml``.

Environment values win over the admin file and show as locked on the admin page. Neither holds a
secret: API keys and Git tokens are named by their environment variable only.
"""

from __future__ import annotations

import os
import secrets
import threading
from pathlib import Path
from typing import Any, Literal

import yaml
from pydantic import BaseModel, ConfigDict, Field, ValidationError

ENV_PREFIX = "LEGACY_AUDIT_"
ADMIN_DIR = ".legacy-audit"
ADMIN_FILE = "admin.yaml"

# Report switches: key -> label. The keys are the GUI's report types (plus the data pack).
REPORTS: dict[str, str] = {
    "summary": "Summary report (HTML and PowerPoint)",
    "mgmt": "Management deck (HTML)",
    "pptx": "Management PowerPoint (PPTX)",
    "functional": "Functional document (HTML)",
    "tech": "Technical report (HTML)",
    "pdf": "PDF copies",
    "datapack": "Data pack (ZIP of the facts)",
}
SUMMARY_SECTIONS: dict[str, str] = {
    "project": "Project, tech stack and modules",
    "data": "Database and queries",
    "integrations": "Integrations",
    "batch": "Batches",
    "security": "Dependency vulnerabilities",
}
Role = Literal["admin", "owner", "contributor", "viewer"]
ROLES: tuple[str, ...] = ("admin", "owner", "contributor", "viewer")


class SettingsError(Exception):
    pass


def _split(value: str) -> list[str]:
    """A list from one variable: ``,`` or ``;`` separated (``:`` would break Windows paths)."""
    return [p.strip() for p in value.replace(";", ",").split(",") if p.strip()]


class HostSettings(BaseModel):
    """Read once at start from ``LEGACY_AUDIT_*`` variables."""

    model_config = ConfigDict(extra="forbid")

    audits_root: Path = Path("/audits")
    sources_roots: list[Path] = Field(default_factory=lambda: [Path("/sources")])
    host: str = "0.0.0.0"
    port: int = 8765
    public_url: str | None = None
    auth: Literal["dummy", "okta"] = "dummy"
    session_secret: str = Field(default_factory=lambda: secrets.token_urlsafe(32), repr=False)
    session_hours: float = 12.0
    admins: list[str] = Field(default_factory=lambda: ["admin"])
    # None: the admin page decides; a value: fixed by the environment
    max_scans: int | None = None
    keep_runs: int | None = None
    templates_dir: Path | None = None
    okta_issuer: str | None = None
    okta_client_id: str | None = None

    @property
    def admin_dir(self) -> Path:
        return self.audits_root / ADMIN_DIR

    @property
    def secret_from_env(self) -> bool:
        return bool(os.environ.get(ENV_PREFIX + "SESSION_SECRET"))

    def allowed_hosts(self) -> set[str] | None:
        """Host headers accepted; None accepts any (no public URL set)."""
        if not self.public_url:
            return None
        from urllib.parse import urlsplit

        u = urlsplit(self.public_url)
        if not u.hostname:
            return None
        port = u.port
        hosts = {u.hostname if port is None else f"{u.hostname}:{port}"}
        hosts |= {f"localhost:{self.port}", f"127.0.0.1:{self.port}"}
        return hosts

    @property
    def secure_cookies(self) -> bool:
        return bool(self.public_url and self.public_url.lower().startswith("https://"))

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> HostSettings:
        env = dict(os.environ if env is None else env)

        def get(name: str) -> str | None:
            v = env.get(ENV_PREFIX + name)
            return v.strip() if v and v.strip() else None

        data: dict[str, Any] = {}
        simple = {
            "AUDITS_ROOT": "audits_root",
            "HOST": "host",
            "PORT": "port",
            "PUBLIC_URL": "public_url",
            "AUTH": "auth",
            "SESSION_SECRET": "session_secret",
            "SESSION_HOURS": "session_hours",
            "MAX_SCANS": "max_scans",
            "KEEP_RUNS": "keep_runs",
            "TEMPLATES_DIR": "templates_dir",
        }
        for var, key in simple.items():
            if (v := get(var)) is not None:
                data[key] = v
        if (v := get("SOURCES_ROOT")) is not None:
            data["sources_roots"] = _split(v)
        if (v := get("ADMINS")) is not None:
            data["admins"] = _split(v)
        for var, key in (("OKTA_ISSUER", "okta_issuer"), ("OKTA_CLIENT_ID", "okta_client_id")):
            if env.get(var):
                data[key] = env[var]
        try:
            return cls.model_validate(data)
        except ValidationError as err:
            lines = [
                f"  - {ENV_PREFIX}{'.'.join(str(p) for p in e['loc']).upper()}: {e['msg']}"
                for e in err.errors()
            ]
            raise SettingsError("Hosting settings are invalid:\n" + "\n".join(lines)) from None

    def env_locked(self) -> dict[str, Any]:
        """Admin settings the environment fixes (shown read-only on the admin page)."""
        out: dict[str, Any] = {}
        if self.max_scans is not None:
            out["limits.max_scans"] = self.max_scans
        if self.keep_runs is not None:
            out["limits.keep_runs"] = self.keep_runs
        if self.templates_dir is not None:
            out["paths.templates_dir"] = str(self.templates_dir)
        return out


# ---- admin.yaml -------------------------------------------------------------------------------


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ReportSettings(Strict):
    enabled: dict[str, bool] = Field(default_factory=lambda: dict.fromkeys(REPORTS, True))
    summary_sections: dict[str, bool] = Field(
        default_factory=lambda: dict.fromkeys(SUMMARY_SECTIONS, True)
    )
    owners_may_hide: bool = Field(
        True, description="Project owners may hide more reports on their project (never show one)"
    )


class PathSettings(Strict):
    extra_source_roots: list[str] = Field(
        default_factory=list, description="More folders projects may scan, beside the sources root"
    )
    git_cache_dir: str | None = Field(
        None, description="Where Git sources are cloned; default <audits root>/.legacy-audit/git"
    )
    templates_dir: str | None = Field(None, description="Corporate PowerPoint templates folder")


class UserSettings(Strict):
    open_signup: bool = Field(True, description="A new name at the login page becomes a user")
    signup_role: Literal["owner", "contributor", "viewer"] = "owner"
    who_can_create: Literal["owners", "admins"] = "owners"


class AiSettings(Strict):
    allowed: bool = Field(True, description="Allow 'Run AI enrichment' from the site")


class LimitSettings(Strict):
    max_scans: int = Field(2, ge=1, le=32, description="Scans and builds running at once")
    keep_runs: int = Field(10, ge=1, le=500, description="Runs kept per project")
    scan_timeout_minutes: int = Field(240, ge=5, description="A scan running longer is stopped")
    clone_keep_days: int = Field(
        14, ge=1, le=3650, description="A Git clone not used for this many days is deleted"
    )
    min_free_gb: float = Field(
        5, ge=0, le=100000, description="Below this free space, the oldest clones are deleted"
    )


class AdminSettings(Strict):
    reports: ReportSettings = Field(default_factory=ReportSettings)
    paths: PathSettings = Field(default_factory=PathSettings)
    users: UserSettings = Field(default_factory=UserSettings)
    ai: AiSettings = Field(default_factory=AiSettings)
    limits: LimitSettings = Field(default_factory=LimitSettings)
    defaults: dict[str, Any] = Field(
        default_factory=dict,
        description="audit.yaml settings every project starts with and inherits on each scan",
    )
    locked: list[str] = Field(
        default_factory=list,
        description="Dotted keys of 'defaults' a project cannot change (e.g. security.osv_scanner)",
    )


class AdminStore:
    """``admin.yaml`` with a lock; the environment's fixed values are applied on every read."""

    def __init__(self, host: HostSettings) -> None:
        self.host = host
        self.path = host.admin_dir / ADMIN_FILE
        self._lock = threading.Lock()
        self._cache: AdminSettings | None = None

    def get(self) -> AdminSettings:
        with self._lock:
            if self._cache is None:
                self._cache = self._read()
            s = self._cache.model_copy(deep=True)
        if self.host.max_scans is not None:
            s.limits.max_scans = self.host.max_scans
        if self.host.keep_runs is not None:
            s.limits.keep_runs = self.host.keep_runs
        if self.host.templates_dir is not None:
            s.paths.templates_dir = str(self.host.templates_dir)
        return s

    def _read(self) -> AdminSettings:
        if not self.path.exists():
            return AdminSettings()
        try:
            data = yaml.safe_load(self.path.read_text(encoding="utf-8")) or {}
            return AdminSettings.model_validate(data)
        except (yaml.YAMLError, ValidationError) as err:
            raise SettingsError(f"{self.path} is invalid: {err}") from None

    def save(self, data: dict[str, Any]) -> AdminSettings:
        try:
            s = AdminSettings.model_validate(data)
        except ValidationError as err:
            msgs = "; ".join(
                f"{'.'.join(str(p) for p in e['loc'])}: {e['msg']}" for e in err.errors()
            )
            raise SettingsError(msgs) from None
        validate_defaults(s.defaults)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        text = yaml.safe_dump(s.model_dump(mode="json"), sort_keys=False, allow_unicode=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(
            "# legacy-audit hosting settings, written by the admin page. No secrets here.\n" + text,
            encoding="utf-8",
        )
        tmp.replace(self.path)
        with self._lock:
            self._cache = s
        return self.get()


def validate_defaults(defaults: dict[str, Any]) -> None:
    """The defaults must be valid audit.yaml settings (checked with a placeholder application)."""
    from legacy_audit.config import ConfigError, parse_config

    if not isinstance(defaults, dict):
        raise SettingsError("defaults must be a mapping of audit.yaml settings")
    for key in ("application", "source", "components", "inputs_dir", "output_dir"):
        if key in defaults:
            raise SettingsError(f"'{key}' is set per project, not in the defaults")
    try:
        parse_config({"application": {"name": "check"}, **defaults}, "defaults")
    except ConfigError as err:
        raise SettingsError(str(err)) from None
