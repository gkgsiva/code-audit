# Decisions

Decisions taken while planning. Newest last. These override `docs/plan.md` where they differ.

| # | Date | Decision | Reason |
| --- | --- | --- | --- |
| 1 | 2026-10-10 | Hybrid architecture: deterministic Python engine + Copilot agent + local GUI | Counts must be reproducible; AI only for interpretation |
| 2 | 2026-10-10 | No external LLM anywhere (no Vertex AI, no API calls). GUI hands off to Copilot Chat only | Client/organisation policy |
| 3 | 2026-10-10 | Autosys (JIL), IBM MQ and CFT are first-class analyzers, built in phase 2 | They appear in most of the team's applications |
| 4 | 2026-10-10 | Supplementary inputs (JIL, MQSC, CFT exports, DDL, crontab) arrive later and go in `audit-inputs/`; the tool must re-run any number of times and refresh reports | Team receives these exports after the code |
| 5 | 2026-10-10 | Branding is a config slot with a neutral default theme; corporate PPT template added later | Template exists but is not available yet |
| 6 | 2026-10-10 | Optional Git source: tool clones repo URLs itself into a cache; local folder stays the default | Analysts may not want to clone by hand |
| 7 | 2026-10-10 | Spring family (core, MVC, Web Flow, Boot 1–3, Batch, data access, Integration/JMS, Security) and Hibernate/MyBatis are in scope | Many legacy Java apps are older Spring |
| 8 | 2026-10-10 | Dependency management section: inventory + CycloneDX SBOM, OWASP Dependency-Check (OSV-Scanner optional), rule-based upgrade recommendations | Requested; security exposure is a migration driver |
| 9 | 2026-10-10 | Target versions: Java 25, Spring Boot 4.1 (Spring Framework 7, Jakarta EE 11), Angular 21 (TypeScript 5.9, Node 22/24), PostgreSQL — all configurable | Boot 4.0 OSS support ends 31 Dec 2026; Angular 21 requested |
| 10 | 2026-10-10 | The tool itself is built with Claude Code; the *audited* teams use GitHub Copilot | Build vs. use are separate |

## Open

- Typical estate size (LOC, number of apps) — needed for phase 6 performance targets. Default target until answered: 2M LOC scanned in under 15 minutes on a laptop.
- Angular 21 fixed, or "latest Angular LTS"? Default: 21, configurable.
- Further frameworks (EJB 2.x, JSF, GWT, Tapestry, .NET)? Default: EJB and JSF covered at the level in the plan; others not in scope.
