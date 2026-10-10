"""``audit.yaml`` loading and validation.

Relative paths in the config are resolved against the folder holding the config file. Both
``/`` and ``\\`` are accepted as separators so a config written on Windows works anywhere.
Unknown keys are rejected with a message naming the key and its location.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path, PureWindowsPath
from typing import Annotated, Any, Literal

import yaml
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    field_validator,
    model_validator,
)

from legacy_audit.model import ComponentType

INPUT_KINDS: dict[str, str] = {
    "autosys": "Autosys JIL",
    "mq": "IBM MQ definitions (MQSC)",
    "cft": "CFT configuration export",
    "ddl": "Database DDL",
    "crontab": "Crontab entries",
    "server-config": "Server configuration",
}

# Defaults from docs/plan.md § Counting rules. Every report prints these.
DEFAULT_COUNTING_RULES: dict[str, str] = {
    "service": (
        "One independently invokable business operation: Struts action, Spring MVC handler, "
        "servlet endpoint, SOAP/REST operation, remotely exposed EJB method, CICS transaction, "
        "COBOL main program, batch job, C/C++ daemon or executable. Utility libraries and "
        "copybooks are shared components, not services."
    ),
    "screen": (
        "One rendered UI page or map: JSP/JSF/HTML page that is a forward/view target, BMS map, "
        "terminal form. JSP fragments/tiles are counted separately as partials."
    ),
    "db_query": (
        "One distinct SQL statement after normalizing whitespace and bind variables. Reported "
        "as total, distinct, by type and by PostgreSQL grade; dynamic SQL is flagged."
    ),
    "stored_procedure": (
        "One package/procedure/function, counted separately and graded manual or review."
    ),
    "integration": (
        "One unique (type, target, direction). The same FTP host used by 10 scripts is "
        "1 integration with 10 usages."
    ),
    "batch_job": (
        "One schedulable job (Autosys job, JCL job, cron entry, shell wrapper, Spring Batch "
        "job). Steps are listed under it; Autosys boxes are containers, not jobs."
    ),
    "library": "One distinct (group, artifact, version). Undeclared jars identified by "
    "manifest or hash.",
    "loc": (
        "Lines of code per file split into code, comment and blank using the language's "
        "comment syntax. A line holding both code and a comment counts as code."
    ),
}


class ConfigError(Exception):
    """Raised for an invalid or unreadable config file."""


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


def _norm_path(value: str) -> str:
    """Accept Windows separators; keep drive-absolute Windows paths recognisable."""
    return value.replace("\\", "/") if not PureWindowsPath(value).drive else value


class Application(Strict):
    name: str
    description: str | None = None
    owner: str | None = None


class LocalSource(Strict):
    type: Literal["local"] = "local"
    paths: list[str] = Field(default_factory=lambda: ["."], min_length=1)

    @field_validator("paths")
    @classmethod
    def _paths(cls, v: list[str]) -> list[str]:
        return [_norm_path(p) for p in v]


class GitRepo(Strict):
    url: str
    ref: str | None = Field(None, description="Branch, tag or commit; default branch if empty")
    paths: list[str] = Field(default_factory=list, description="Sub-paths to scan")
    name: str | None = None
    token_env: str | None = Field(
        None, description="Name of the environment variable holding a read-only token"
    )

    @field_validator("paths")
    @classmethod
    def _paths(cls, v: list[str]) -> list[str]:
        return [_norm_path(p) for p in v]


class GitSource(Strict):
    type: Literal["git"]
    repos: list[GitRepo] = Field(min_length=1)
    cache_dir: str | None = Field(None, description="Clone cache; default ~/.legacy-audit/cache")


Source = Annotated[LocalSource | GitSource, Field(discriminator="type")]


class ComponentSpec(Strict):
    name: str
    path: str
    type: ComponentType | None = None
    root: str | None = Field(None, description="Source root name when there are several")

    @field_validator("path")
    @classmethod
    def _path(cls, v: str) -> str:
        return _norm_path(v).strip("/")


class Target(Strict):
    java: str = "25"
    spring_boot: str = "4.1"
    angular: str = "21"
    database: str = "postgresql"
    cloud: str = "gcp"


class ComplexityConfig(Strict):
    weights: dict[str, float] = Field(
        default_factory=lambda: {
            "loc": 1.0,
            "cyclomatic": 2.0,
            "tables": 1.5,
            "integrations": 2.0,
            "vendor_sql": 1.5,
            "fan_out": 1.0,
        }
    )
    thresholds: dict[str, float] = Field(
        default_factory=lambda: {"medium": 10.0, "complex": 25.0, "very_complex": 50.0}
    )


class RulesConfig(Strict):
    enabled: list[str] = Field(default_factory=lambda: ["risk", "postgresql", "dependency"])


class Branding(Strict):
    theme: str = "neutral"
    logo: str | None = None
    primary_color: str = "#1f4e79"
    accent_color: str = "#2e75b6"
    font: str = "Segoe UI, Arial, sans-serif"
    pptx_template: str | None = None


class InventoryConfig(Strict):
    include_binaries: bool = Field(
        False,
        description="Inventory images, objects and executables too. Archives (jar/war/ear/zip) "
        "are always inventoried because library detection needs them.",
    )
    max_file_size_mb: float = Field(
        20.0, description="Larger files are inventoried (hash, size) but not read for LOC"
    )
    use_default_excludes: bool = True


class AuditConfig(Strict):
    application: Application
    source: Source = Field(default_factory=LocalSource)
    include: list[str] = Field(default_factory=lambda: ["**"])
    exclude: list[str] = Field(default_factory=list)
    components: list[ComponentSpec] = Field(default_factory=list)
    inputs_dir: str = "audit-inputs"
    output_dir: str = "audit-output"
    overrides_file: str = "audit-overrides.yaml"
    target: Target = Field(default_factory=Target)
    counting: dict[str, str] = Field(default_factory=lambda: dict(DEFAULT_COUNTING_RULES))
    complexity: ComplexityConfig = Field(default_factory=ComplexityConfig)
    rules: RulesConfig = Field(default_factory=RulesConfig)
    branding: Branding = Field(default_factory=Branding)
    inventory: InventoryConfig = Field(default_factory=InventoryConfig)

    @model_validator(mode="before")
    @classmethod
    def _default_source_type(cls, data: Any) -> Any:
        if isinstance(data, dict) and isinstance(data.get("source"), dict):
            src = data["source"]
            if "type" not in src:
                data = {**data, "source": {"type": "local", **src}}
            elif src["type"] not in ("local", "git"):
                raise ValueError(f"source.type must be 'local' or 'git', not {src['type']!r}")
        return data

    @field_validator("inputs_dir", "output_dir", "overrides_file")
    @classmethod
    def _dirs(cls, v: str) -> str:
        return _norm_path(v)

    @field_validator("counting")
    @classmethod
    def _counting(cls, v: dict[str, str]) -> dict[str, str]:
        # A partial counting section overrides individual rules; the rest keep their defaults.
        return {**DEFAULT_COUNTING_RULES, **v}


class LoadedConfig(BaseModel):
    """A validated config plus where it came from."""

    model_config = ConfigDict(arbitrary_types_allowed=True)

    config: AuditConfig
    path: Path
    base_dir: Path
    hash: str

    def resolve(self, value: str) -> Path:
        p = Path(value)
        return p if p.is_absolute() else (self.base_dir / p).resolve()

    @property
    def output_dir(self) -> Path:
        return self.resolve(self.config.output_dir)

    @property
    def inputs_dir(self) -> Path:
        return self.resolve(self.config.inputs_dir)

    @property
    def overrides_path(self) -> Path:
        return self.resolve(self.config.overrides_file)


def _format_errors(err: ValidationError) -> str:
    lines = []
    for e in err.errors():
        loc = ".".join(str(p) for p in e["loc"]) or "(top level)"
        if e["type"] == "extra_forbidden":
            lines.append(f"  - unknown key '{loc}'")
        elif e["type"] == "missing":
            lines.append(f"  - missing required key '{loc}'")
        else:
            lines.append(f"  - {loc}: {e['msg']}")
    return "\n".join(lines)


def parse_config(data: Any, source: str = "audit.yaml") -> AuditConfig:
    if not isinstance(data, dict):
        raise ConfigError(f"{source}: expected a mapping at the top level")
    try:
        return AuditConfig.model_validate(data)
    except ValidationError as err:
        raise ConfigError(f"{source} is invalid:\n{_format_errors(err)}") from None


def load_config(path: Path | str) -> LoadedConfig:
    path = Path(path)
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as err:
        raise ConfigError(f"Cannot read config {path}: {err.strerror}") from None
    try:
        data = yaml.safe_load(text)
    except yaml.YAMLError as err:
        raise ConfigError(f"{path} is not valid YAML: {err}") from None
    config = parse_config(data, str(path))
    canonical = json.dumps(config.model_dump(mode="json"), sort_keys=True)
    return LoadedConfig(
        config=config,
        path=path.resolve(),
        base_dir=path.resolve().parent,
        hash=hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16],
    )
