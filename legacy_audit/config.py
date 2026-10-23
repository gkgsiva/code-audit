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
    "security": "Vulnerability reports (Dependency-Check / OSV-Scanner JSON)",
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
        "One interface per (type, target), whatever direction or spelling each usage has: "
        "the same FTP host used by 10 scripts is 1 integration with 10 usages; a target both "
        "read and written is one row with direction both; host case, a default port, a "
        "trailing slash or a queue:/// prefix do not make a second row, and HTTP and SOAP "
        "calls to the same URL are one SOAP integration. Calls to an identity provider "
        "(Okta, Entra ID, ...) have purpose 'login / authentication'."
    ),
    "batch_job": (
        "One schedulable job (Autosys job, JCL job, cron entry, shell wrapper, Spring Batch "
        "job). Steps are listed under it; Autosys boxes are containers, not jobs. A component "
        "doing batch work that no scheduler definition covers counts as one batch, however "
        "many ksh scripts and programs it holds (they are its steps). The All batches list "
        "shows each once, with when it runs from the scheduler or, without one, from what the "
        "code states (cron expressions, @Scheduled, comments)."
    ),
    "library": "One distinct (group, artifact, version). Undeclared jars identified by "
    "manifest or hash.",
    "loc": (
        "Lines of code per file split into code, comment and blank using the language's "
        "comment syntax. A line holding both code and a comment counts as code. Every text "
        "file is counted under the language its extension names; a text extension no rule "
        "knows is listed as 'other (.ext)'. Binary files (by extension, a NUL byte or mostly "
        "control bytes, e.g. .class, .o, .exe) are left out, data files (.dat, .csv) and "
        "backup copies (.bak, .old, ~, .java_old, .sql.20190312) are listed with no lines."
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


DEFAULT_COMPLEXITY_WEIGHTS: dict[str, float] = {
    "loc": 1.0,
    "cyclomatic": 2.0,
    "tables": 1.5,
    "integrations": 2.0,
    "vendor_sql": 1.5,
    "fan_out": 1.0,
    "fields": 0.2,
}
DEFAULT_COMPLEXITY_SCALE: dict[str, float] = {"loc": 100.0, "cyclomatic": 10.0}
DEFAULT_COMPLEXITY_THRESHOLDS: dict[str, float] = {
    "medium": 10.0,
    "complex": 25.0,
    "very_complex": 50.0,
}

# Person-days (low, likely, high) per item and level. Labelled as an estimate everywhere.
DEFAULT_EFFORT: dict[str, dict[str, list[float]]] = {
    "service": {
        "simple": [1, 2, 3],
        "medium": [2, 4, 6],
        "complex": [5, 8, 12],
        "very_complex": [10, 15, 25],
    },
    "screen": {
        "simple": [0.5, 1, 2],
        "medium": [1, 2, 3],
        "complex": [2, 4, 6],
        "very_complex": [4, 7, 10],
    },
    "batch_job": {
        "simple": [1, 2, 3],
        "medium": [2, 3, 5],
        "complex": [4, 6, 9],
        "very_complex": [8, 12, 18],
    },
    "sql": {"auto": [0, 0.05, 0.1], "review": [0.25, 0.5, 1], "manual": [1, 2, 3]},
    "stored_procedure": {"review": [1, 2, 4], "manual": [2, 4, 8]},
    "integration": {"each": [2, 4, 8]},
    "library": {
        "keep": [0, 0, 0],
        "use-bom": [0, 0.1, 0.25],
        "upgrade": [0.25, 0.5, 1],
        "migrate-jakarta": [0.5, 1, 2],
        "remove": [0.5, 1, 2],
        "replace": [3, 5, 10],
        "rewrite": [10, 20, 40],
    },
}


class ComplexityConfig(Strict):
    weights: dict[str, float] = Field(
        default_factory=lambda: dict(DEFAULT_COMPLEXITY_WEIGHTS),
        description="Score = sum of weight x (feature / scale); missing keys keep defaults",
    )
    scale: dict[str, float] = Field(
        default_factory=lambda: dict(DEFAULT_COMPLEXITY_SCALE),
        description="Divisor per feature before weighting (default 1)",
    )
    thresholds: dict[str, float] = Field(
        default_factory=lambda: dict(DEFAULT_COMPLEXITY_THRESHOLDS),
        description="Lowest score of each band above Simple",
    )
    high_unit: int = Field(
        20, description="Cyclomatic complexity from which a function is reported as too complex"
    )

    @field_validator("weights")
    @classmethod
    def _weights(cls, v: dict[str, float]) -> dict[str, float]:
        return {**DEFAULT_COMPLEXITY_WEIGHTS, **v}

    @field_validator("scale")
    @classmethod
    def _scale(cls, v: dict[str, float]) -> dict[str, float]:
        return {**DEFAULT_COMPLEXITY_SCALE, **v}

    @field_validator("thresholds")
    @classmethod
    def _thresholds(cls, v: dict[str, float]) -> dict[str, float]:
        return {**DEFAULT_COMPLEXITY_THRESHOLDS, **v}


class MetricsConfig(Strict):
    duplication_tokens: int = Field(
        50, ge=10, description="Tokens in a duplicated window (smaller finds more clones)"
    )


class EffortConfig(Strict):
    enabled: bool = True
    weights: dict[str, dict[str, list[float]]] = Field(
        default_factory=lambda: {k: dict(v) for k, v in DEFAULT_EFFORT.items()},
        description="Person-days [low, likely, high] per item and level; merged with defaults",
    )

    @field_validator("weights")
    @classmethod
    def _weights(cls, v: dict[str, dict[str, list[float]]]) -> dict[str, dict[str, list[float]]]:
        merged = {k: dict(d) for k, d in DEFAULT_EFFORT.items()}
        for item, levels in v.items():
            merged.setdefault(item, {}).update(levels)
        for item, levels in merged.items():
            for level, triple in levels.items():
                if len(triple) != 3 or not triple[0] <= triple[1] <= triple[2]:
                    raise ValueError(
                        f"effort.weights.{item}.{level} must be [low, likely, high], ascending"
                    )
        return merged


class RulesConfig(Strict):
    enabled: list[str] = Field(
        default_factory=lambda: ["risk", "postgresql", "dependency"],
        description="Rule packs to apply: risk, postgresql, dependency",
    )
    disabled: list[str] = Field(default_factory=list, description="Rule ids to switch off")
    severity: dict[str, str] = Field(
        default_factory=dict, description="Rule id -> severity (info/low/medium/high/critical)"
    )
    packs_dir: str | None = Field(
        None, description="Folder with team rule packs; a rule with the same id replaces ours"
    )

    @field_validator("severity")
    @classmethod
    def _severity(cls, v: dict[str, str]) -> dict[str, str]:
        allowed = ("info", "low", "medium", "high", "critical")
        for rule, sev in v.items():
            if sev not in allowed:
                raise ValueError(f"rules.severity.{rule}: {sev!r} is not one of {allowed}")
        return v


class DependencyCheckConfig(Strict):
    enabled: bool = True
    path: str | None = Field(None, description="dependency-check script; found on PATH when empty")
    data_dir: str | None = Field(None, description="Local NVD database folder (--data)")
    nvd_mirror_url: str | None = Field(None, description="Team NVD mirror, used when syncing")
    offline: bool = Field(True, description="Never update the database during a scan")
    timeout_minutes: int = 30


class OsvScannerConfig(Strict):
    enabled: bool = True
    path: str | None = Field(None, description="osv-scanner binary; found on PATH when empty")
    data_dir: str | None = Field(None, description="Local OSV database folder")
    offline: bool = Field(
        False, description="false: ask osv.dev during the scan; true: local database only"
    )
    timeout_minutes: int = 15


class JdkToolsConfig(Strict):
    enabled: bool = Field(False, description="Run jdeps/jdeprscan on jars (slow; opt-in)")
    java_home: str | None = Field(None, description="JDK holding jdeps/jdeprscan; else PATH")


class SecurityConfig(Strict):
    dependency_check: DependencyCheckConfig = Field(default_factory=DependencyCheckConfig)
    osv_scanner: OsvScannerConfig = Field(default_factory=OsvScannerConfig)
    jdk_tools: JdkToolsConfig = Field(default_factory=JdkToolsConfig)


class Branding(Strict):
    theme: str = "neutral"
    logo: str | None = None
    primary_color: str = "#1f4e79"
    accent_color: str = "#2e75b6"
    font: str = "Segoe UI, Arial, sans-serif"
    pptx_template: str | None = Field(
        None,
        description="PowerPoint template: a name from templates/ (blue, orange, purple), a file "
        "name or a .potx path relative to audit.yaml; None means the first template found",
    )


class PdfConfig(Strict):
    enabled: bool = Field(
        True, description="Write PDF copies of the reports when a browser is found"
    )
    browser_path: str | None = Field(
        None, description="Edge, Chrome or Chromium program; found automatically when empty"
    )
    paper: Literal["A4", "Letter"] = "A4"
    max_table_rows: int = Field(
        500, ge=10, description="Rows printed per table; the full lists stay in the HTML and CSV"
    )
    detail_pages: bool = Field(
        True, description="Add each component and flow page to the technical/functional PDFs"
    )
    timeout_seconds: int = Field(180, ge=10, description="Per page printed")


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
    skip_build_output: bool = Field(
        True,
        description="Leave out compiler and IDE output: bin/, classes/, obj/, Debug/, Release/ "
        "folders holding compiled files and no source or script, Eclipse output folders "
        "(.classpath), and files a precompiler generated next to their source (foo.c beside "
        "foo.pc, foo.cob beside foo.pco, Foo.java beside Foo.sqlj). Each is reported.",
    )
    workers: int = Field(
        0,
        ge=0,
        description="Processes used to analyse files and grade SQL; 0 = one per CPU less one "
        "(at most 8), 1 = no extra processes",
    )


class SqlConfig(Strict):
    dialect: str = Field(
        "oracle",
        description="Dialect of SQL found in code: oracle, db2, sqlserver, sybase, mysql, "
        "postgresql, generic. Embedded SQL in COBOL uses cobol.sql_dialect.",
    )


class CobolConfig(Strict):
    copybook_paths: list[str] = Field(
        default_factory=list,
        description="Folders (relative to the source root) searched first for COPY members",
    )
    sql_dialect: str = "db2"

    @field_validator("copybook_paths")
    @classmethod
    def _paths(cls, v: list[str]) -> list[str]:
        return [_norm_path(p).strip("/") for p in v]


AiProvider = Literal["copilot", "openai", "anthropic", "azure-openai", "ollama"]


class AiConfig(Strict):
    """Which model writes the enrichment. Only `legacy-audit enrich` uses it; scans never do."""

    provider: AiProvider = Field(
        "copilot",
        description="copilot (VS Code chat, model picked there), openai (any OpenAI-compatible "
        "endpoint), anthropic, azure-openai or ollama (local)",
    )
    model: str | None = Field(
        None, description="Model name, or the deployment name for azure-openai"
    )
    base_url: str | None = Field(
        None, description="Endpoint; default per provider (OpenAI, Anthropic, Ollama on localhost)"
    )
    api_key_env: str | None = Field(
        None,
        description="Name of the environment variable holding the API key (never the key); "
        "default OPENAI_API_KEY, ANTHROPIC_API_KEY or AZURE_OPENAI_API_KEY",
    )
    api_version: str = Field("2024-10-21", description="azure-openai API version")
    temperature: float | None = Field(0.1, description="null leaves the model's default")
    max_output_tokens: int = Field(16000, ge=256)
    json_mode: bool = Field(True, description="Ask the endpoint for JSON output when it can")
    context_chars: int = Field(
        150_000,
        ge=10_000,
        description="Facts and source sent per stage (characters, about 4 per token)",
    )
    timeout_seconds: int = Field(600, ge=10)
    repair_rounds: int = Field(2, ge=0, le=5, description="Retries with the validator's errors")
    ca_bundle: str | None = Field(None, description="Corporate CA certificates file (PEM)")
    copilot_model: str | None = Field(
        None,
        description="Model the Copilot agent asks for (agent file `model:`), e.g. 'Claude "
        "Sonnet 4.5 (copilot)'; empty = whatever is picked in the chat",
    )


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
    pdf: PdfConfig = Field(default_factory=PdfConfig)
    inventory: InventoryConfig = Field(default_factory=InventoryConfig)
    sql: SqlConfig = Field(default_factory=SqlConfig)
    cobol: CobolConfig = Field(default_factory=CobolConfig)
    metrics: MetricsConfig = Field(default_factory=MetricsConfig)
    effort: EffortConfig = Field(default_factory=EffortConfig)
    security: SecurityConfig = Field(default_factory=SecurityConfig)
    ai: AiConfig = Field(default_factory=AiConfig)

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
