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


# ---- 12 Functional -------------------------------------------------------------------------------
class Capability(Claim):
    name: str
    description: str = Field(description="What the business does here, in business words")
    service_ids: list[str] = Field(default_factory=list)
    screen_ids: list[str] = Field(default_factory=list)
    job_ids: list[str] = Field(default_factory=list, description="batch_job ids")


class Domain(_Base):
    name: str = Field(description="Business domain, e.g. Orders, Billing, Settlement")
    description: str = ""
    components: list[str] = Field(default_factory=list)
    capabilities: list[Capability] = Field(default_factory=list)


class Actor(_Base):
    name: str = Field(description="A user role, an external system, a scheduler or an operator")
    kind: Literal["user", "external-system", "scheduler", "operator"]
    description: str = ""
    screen_ids: list[str] = Field(default_factory=list)
    integration_ids: list[str] = Field(default_factory=list)


class UseCase(Claim):
    id: str = Field(description="UC-01, UC-02...")
    name: str = Field(description="Verb + object, e.g. 'Search orders'")
    actor: str = Field(description="Name of an actor above")
    goal: str
    domain: str = ""
    screen_ids: list[str] = Field(default_factory=list)
    service_ids: list[str] = Field(default_factory=list)
    steps: list[str] = Field(default_factory=list, description="What the user does, in order")
    outcome: str = ""


class BusinessRule(Claim):
    id: str = Field(description="BR-01, BR-02...")
    name: str
    description: str = Field(description="The rule in business words: when..., then...")
    kind: Literal[
        "validation", "calculation", "eligibility", "status-change", "limit", "notification",
        "other",
    ]  # fmt: skip
    domain: str = ""
    fact_ids: list[str] = Field(default_factory=list, description="Services, screens, SQL...")


class DataEntity(Claim):
    name: str = Field(description="Business name, e.g. 'Customer invoice'")
    description: str = ""
    db_object_ids: list[str] = Field(default_factory=list, description="Tables that hold it")
    domain: str = ""


class ScreenStory(Claim):
    screen_id: str
    purpose: str = Field(description="What the user does on this screen, in plain words")
    user_actions: list[str] = Field(default_factory=list)
    data_shown: str = ""
    next_steps: str = Field("", description="Where the user goes next and why")


class BatchStep(_Base):
    order: int
    description: str = Field(description="What the step does, in business words")
    fact_ids: list[str] = Field(default_factory=list)


class BatchStory(Claim):
    job_id: str = Field(description="The box or job id (batch_job:...)")
    purpose: str = Field(description="Why the batch exists, in business words")
    when: str = Field("", description="When it runs, in business words")
    steps: list[BatchStep] = Field(default_factory=list)
    inputs: list[str] = Field(default_factory=list, description="Data it reads, business words")
    outputs: list[str] = Field(default_factory=list, description="Data it produces")
    on_failure: str = Field("", description="What happens and who acts when it fails")
    business_impact: str = Field("", description="What the business feels if it does not run")


class Interface(Claim):
    integration_id: str
    partner: str = Field(description="Who is on the other side, in business words")
    purpose: str
    data_exchanged: str = ""


class GlossaryTerm(_Base):
    term: str
    meaning: str


class Functional(StageFile):
    summary: str = Field(description="What the application does for the business, one paragraph")
    domains: list[Domain] = Field(default_factory=list)
    actors: list[Actor] = Field(default_factory=list)
    use_cases: list[UseCase] = Field(default_factory=list)
    business_rules: list[BusinessRule] = Field(default_factory=list)
    data_entities: list[DataEntity] = Field(default_factory=list)
    screens: list[ScreenStory] = Field(default_factory=list)
    batches: list[BatchStory] = Field(default_factory=list)
    interfaces: list[Interface] = Field(default_factory=list)
    glossary: list[GlossaryTerm] = Field(default_factory=list)


# ---- 13 Component documentation ------------------------------------------------------------------
class KeyElement(_Base):
    name: str = Field(description="Class, program, script or file")
    role: str
    file: str | None = None


class ComponentDoc(Claim):
    component: str
    purpose: str
    responsibilities: list[str] = Field(default_factory=list)
    key_elements: list[KeyElement] = Field(default_factory=list)
    inputs: list[str] = Field(default_factory=list, description="Requests, files, messages in")
    outputs: list[str] = Field(default_factory=list, description="Pages, files, messages out")
    db_object_ids: list[str] = Field(default_factory=list, description="Tables it reads/writes")
    tech_debt: list[str] = Field(default_factory=list)
    migration_notes: str = ""


class ComponentDocs(StageFile):
    components: list[ComponentDoc]
