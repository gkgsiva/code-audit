"""Enrichment stages (thirteen; stage 8 writes two files) and what each is built from.

``inputs`` are fact files in ``data/`` (an entity name, ``effort`` or ``target-mapping``);
``after`` are earlier stages whose output the stage reads. A stage is stale when the hash of one
of them changed since it was built (see ``status.py``). Keep ``inputs`` to what the stage really
reads: adding Autosys JIL changes services, batch jobs and the tech stack, so flows, risks, plan
and narratives go stale, but classification (components and entry points only) does not.
"""

from __future__ import annotations

from dataclasses import dataclass

from legacy_audit.enrich import models as m


@dataclass(frozen=True)
class Stage:
    number: int
    name: str
    title: str
    model: type[m.StageFile]
    inputs: tuple[str, ...]
    after: tuple[str, ...] = ()

    @property
    def file(self) -> str:
        return f"{self.name}.json"


STAGES: list[Stage] = [
    Stage(1, "overview", "Orient", m.Overview,
          ("components", "services", "screens", "sql_statements", "integrations", "batch_jobs",
           "libraries", "tech_stack")),
    Stage(2, "classification", "Classify", m.Classification, ("components", "entry_points")),
    Stage(3, "architecture", "Architecture", m.Architecture,
          ("components", "dependencies", "integrations", "tech_stack"), ("classification",)),
    Stage(4, "flows", "Flows", m.Flows,
          ("services", "screens", "batch_jobs", "integrations", "dependencies"),
          ("classification",)),
    Stage(5, "service_mapping", "Services", m.ServiceMapping,
          ("services", "components", "target-mapping"), ("classification",)),
    Stage(6, "data_migration", "Data", m.DataMigration, ("sql_statements", "db_objects")),
    Stage(7, "dependency_plan", "Dependencies", m.DependencyPlan, ("libraries",)),
    Stage(8, "risks", "Risks", m.Risks,
          ("findings", "libraries", "integrations", "batch_jobs", "input_coverage")),
    Stage(8, "assumptions", "Assumptions", m.Assumptions, ("input_coverage", "findings")),
    Stage(9, "target_architecture", "Target architecture", m.TargetArchitecture,
          ("components", "integrations", "tech_stack", "target-mapping"),
          ("architecture", "service_mapping")),
    Stage(10, "plan", "Plan", m.Plan, ("components", "effort"),
          ("risks", "dependency_plan", "target_architecture", "service_mapping",
           "data_migration")),
    Stage(11, "narratives", "Narratives", m.Narratives, (),
          ("overview", "classification", "architecture", "flows", "service_mapping",
           "data_migration", "dependency_plan", "risks", "assumptions", "target_architecture",
           "plan")),
    Stage(12, "functional", "Functional", m.Functional,
          ("components", "services", "screens", "batch_jobs", "integrations", "web_services",
           "db_objects", "sql_statements", "dependencies"),
          ("classification", "flows")),
    Stage(13, "component_docs", "Component documentation", m.ComponentDocs,
          ("components", "services", "screens", "sql_statements", "db_objects", "integrations",
           "dependencies", "metrics", "findings", "libraries"),
          ("classification",)),
]  # fmt: skip
BY_NAME = {s.name: s for s in STAGES}
FACT_FILES = {"effort": "effort.json", "target-mapping": "target-mapping.json"}


def fact_file(kind: str) -> str:
    return FACT_FILES.get(kind, f"{kind}.json")
