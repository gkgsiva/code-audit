"""Enrichment files the Copilot agent writes, one pydantic model per stage.

The agent interprets; it never counts. So every enrichment file follows three conventions that
``legacy-audit validate`` checks:

- **Figures** (``{metric, value, estimate}``) carry every number. ``metric`` is a path into
  ``audit-summary.json`` (``counts.services.total``) or ``effort.json`` (``effort.likely``); the
  value must equal the scan's unless ``estimate: true``.
- **Claims** (risks, flows, classifications...) cite ``evidence: [{file, line}]`` or say
  ``assumption: true``.
- **References** to facts use their ids in fields ending ``_id`` / ``_ids``, and component names
  in ``component`` / ``components``; both must exist in the run.

``meta.built_from`` is stamped by ``legacy-audit validate`` (the agent leaves it out); it records
the hash of every fact file and upstream stage the stage was built from, which is how a later
re-scan knows the stage is stale.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

SCHEMA_VERSION = "1"


class _Base(BaseModel):
    model_config = ConfigDict(extra="forbid")


class BuiltFrom(_Base):
    run_id: str
    facts_hash_by_input: dict[str, str] = Field(
        description="sha256 of each fact file (and 'stage:<name>' of each upstream stage)"
    )


class Meta(_Base):
    stage: str
    schema_version: str = SCHEMA_VERSION
    built_from: BuiltFrom | None = Field(
        None, description="Stamped by `legacy-audit validate`; leave it out when (re)writing"
    )
    generated_at: str | None = None
    author: str = "copilot-agent"
    overrides_noted: list[str] = Field(
        default_factory=list,
        description="Overrides from audit-overrides.yaml (run.json > overrides) this stage took "
        "into account",
    )


class Evidence(_Base):
    file: str
    line: int | None = None


class Figure(_Base):
    """A number shown to readers. Never typed by hand: copied from the scan output."""

    label: str
    metric: str | None = Field(
        None, description="Path in audit-summary.json, or effort.<key> in effort.json"
    )
    value: float | int | str
    unit: str | None = None
    estimate: bool = Field(False, description="True when the agent derived it (say how in note)")
    note: str | None = None


class Claim(_Base):
    """Something the agent asserts: it cites evidence or is labelled an assumption."""

    evidence: list[Evidence] = Field(default_factory=list)
    assumption: bool = False

    @model_validator(mode="after")
    def _cited(self) -> Claim:
        if not self.evidence and not self.assumption:
            raise ValueError("needs evidence [{file, line}] or assumption: true")
        return self


class Diagram(_Base):
    title: str
    mermaid: str


class StageFile(_Base):
    meta: Meta


# ---- 1 Orient ------------------------------------------------------------------------------------
class KeyPoint(Claim):
    text: str


class Overview(StageFile):
    purpose: str = Field(description="What the application does, in one paragraph")
    users: list[str] = Field(default_factory=list)
    figures: list[Figure] = Field(default_factory=list)
    key_points: list[KeyPoint] = Field(default_factory=list)


# ---- 2 Classify ----------------------------------------------------------------------------------
class ComponentClass(Claim):
    component: str
    capability: str = Field(description="Business capability or module, in business words")
    kind: Literal["online", "batch", "integration", "library", "data", "mixed"]
    criticality: Literal["high", "medium", "low"]
    reclassified_from: str | None = Field(None, description="Scanner type, when overridden")
    reason: str


class Classification(StageFile):
    components: list[ComponentClass]


# ---- 3 Architecture ------------------------------------------------------------------------------
class Layer(_Base):
    name: str
    components: list[str] = Field(default_factory=list)
    description: str = ""


class DeploymentUnit(_Base):
    name: str
    components: list[str] = Field(default_factory=list)
    runtime: str


class ArchNote(Claim):
    text: str


class Architecture(StageFile):
    layers: list[Layer] = Field(default_factory=list)
    deployment_units: list[DeploymentUnit] = Field(default_factory=list)
    diagrams: list[Diagram] = Field(default_factory=list)
    notes: list[ArchNote] = Field(default_factory=list)


# ---- 4 Flows -------------------------------------------------------------------------------------
class FlowStep(_Base):
    order: int
    description: str
    fact_ids: list[str] = Field(default_factory=list, description="Services, screens, jobs...")


class Flow(Claim):
    name: str
    kind: Literal["online", "batch", "integration"]
    summary: str = Field(description="Plain language, for business readers")
    trigger: str = ""
    steps: list[FlowStep] = Field(default_factory=list)
    component_ids: list[str] = Field(default_factory=list)


class Flows(StageFile):
    flows: list[Flow]


# ---- 5 Services ----------------------------------------------------------------------------------
class ServiceMap(Claim):
    service_id: str
    target: str = Field(description="e.g. Spring Boot REST controller, Spring Batch job")
    target_component: str
    approach: Literal["rehost", "replatform", "refactor", "rewrite", "retire", "replace"]
    notes: str = ""


class ServiceMapping(StageFile):
    mappings: list[ServiceMap]


# ---- 6 Data --------------------------------------------------------------------------------------
class DataObjectPlan(Claim):
    db_object_id: str
    approach: Literal["auto", "convert", "rewrite", "retire"]
    notes: str = ""


class DataMigration(StageFile):
    figures: list[Figure] = Field(default_factory=list)
    objects: list[DataObjectPlan] = Field(default_factory=list)
    strategy: str = ""


# ---- 7 Dependencies ------------------------------------------------------------------------------
class UpgradeTheme(Claim):
    name: str
    library_ids: list[str] = Field(default_factory=list)
    action: str
    wave: str | None = Field(None, description="Name of the plan wave that carries it")
    rationale: str


class DependencyPlan(StageFile):
    themes: list[UpgradeTheme]


# ---- 8 Risks and assumptions ---------------------------------------------------------------------
Level = Literal["critical", "high", "medium", "low"]


class Risk(Claim):
    id: str
    title: str
    description: str
    severity: Level
    likelihood: Literal["high", "medium", "low"]
    mitigation: str
    owner: str = "TBD"
    related_ids: list[str] = Field(default_factory=list)
    accepted: bool = False


class Risks(StageFile):
    risks: list[Risk]


class AssumptionItem(_Base):
    id: str
    statement: str
    impact: str
    owner: str = "TBD"
    validate_by: str = ""


class Assumptions(StageFile):
    assumptions: list[AssumptionItem]
    open_questions: list[str] = Field(default_factory=list)


# ---- 9 Target architecture -----------------------------------------------------------------------
class TargetComponent(_Base):
    name: str
    technology: str = Field(description="e.g. Spring Boot 4 on Java 25, Angular 21")
    platform: str = Field(description="GCP service, e.g. Cloud Run, Cloud SQL for PostgreSQL")
    replaces: list[str] = Field(default_factory=list, description="Legacy component names")
    description: str = ""


class TargetArchitecture(StageFile):
    components: list[TargetComponent]
    diagrams: list[Diagram] = Field(default_factory=list)
    principles: list[str] = Field(default_factory=list)


# ---- 10 Plan -------------------------------------------------------------------------------------
class Wave(_Base):
    name: str
    order: int
    goal: str
    components: list[str] = Field(default_factory=list)
    start_month: int = Field(ge=0)
    duration_months: int = Field(ge=1)
    effort: list[Figure] = Field(default_factory=list)
    depends_on: list[str] = Field(default_factory=list, description="Other wave names")


class TeamRole(_Base):
    role: str
    count: int = Field(ge=0)


class Plan(StageFile):
    approach: str
    waves: list[Wave]
    effort: list[Figure] = Field(default_factory=list, description="Ranges from effort.json")
    team: list[TeamRole] = Field(default_factory=list)
    decisions_needed: list[str] = Field(default_factory=list)
    next_steps: list[str] = Field(default_factory=list)


# ---- 11 Narratives -------------------------------------------------------------------------------
class Narrative(_Base):
    text: str
    key_messages: list[str] = Field(default_factory=list)


class Narratives(StageFile):
    executive: Narrative
    architect: Narrative
    functional: Narrative
    developer: Narrative
