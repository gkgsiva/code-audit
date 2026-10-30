# audit-server

Package root: `com.legacyaudit.server`. The web backend: Spring Boot 4 (Spring MVC, Spring
Security, `JdbcClient`, Flyway) on PostgreSQL 17. The Angular app calls it under `/api`; in
Docker, nginx in the `frontend` container passes `/api` through to it.

## Packages

| Package | Classes | Purpose |
| --- | --- | --- |
| `server` | `LegacyAuditServer`, `SecurityConfig`, `ServerSettings` | Start-up, who may call what, settings |
| `server.auth` | `AuthController`, `UserStore`, `User`, `CurrentUser` | Test login and users |
| `server.projects` | `ProjectController`, `ProjectService`, `ProjectStore`, `Project`, `Roles` | Projects, members, the folder picker |
| `server.scans` | `ScanController`, `ScanJobs`, `ScanStore`, `Scan` | Starting, following and cancelling scans |
| `server.reports` | `ReportController`, `ReportJobs` | Report links, background report builds, report files and the data pack |
| `server.enrichment` | `EnrichmentController`, `EnrichJobs` | The AI enrichment of the newest scan: stage states, validate, run the model in the background |
| `server.inventory` | `InventoryController` | Files and components of the newest scan |
| `server.web` | `HealthController`, `ApiException`, `ApiErrors` | Health check and error answers |

## Settings

`application.yml` maps environment variables (set by `docker-compose.yml` from `.env`) onto
`ServerSettings` (`legacy-audit.*`):

| Variable | Setting | Default |
| --- | --- | --- |
| `LEGACY_AUDIT_AUTH` | login provider (`dummy` = name only; Okta later) | `dummy` |
| `LEGACY_AUDIT_ADMINS` | user names that are always admins | `admin` |
| `LEGACY_AUDIT_AUDITS_ROOT` | one sub-folder per project | `./audits` |
| `LEGACY_AUDIT_SOURCES_ROOT` | folders a project may scan | `./sources` |
| `LEGACY_AUDIT_MAX_SCANS` | scans running at the same time | `2` |
| `LEGACY_AUDIT_SESSION_HOURS` | session length | `12` |
| `LEGACY_AUDIT_AI_PROVIDER`, `_MODEL`, `_BASE_URL` | the model for **Run here** on the Enrichment page; override `ai:` of each project's `audit.yaml` (the API key comes from the variable the provider names, e.g. `OPENAI_API_KEY`) | from `audit.yaml` (`copilot`) |
| `LEGACY_AUDIT_DB_URL`, `_DB_USER`, `_DB_PASSWORD` | PostgreSQL | local `legacyaudit` |

## REST API

All answers are JSON. A refusal is `{"detail": "plain words"}` with a 4xx status
(`ApiException`, turned into JSON by `ApiErrors`). Every call except health and login needs a
logged-in session; state-changing calls need the CSRF header (see Security).

| Method and path | What it does | Who |
| --- | --- | --- |
| `GET /api/health` | status, name, version, database | anyone |
| `GET /api/auth/info` | login provider and banner for the login page | anyone |
| `POST /api/auth/login` | `{"name"}`: test login | anyone |
| `POST /api/auth/logout` | ends the session | logged in |
| `GET /api/me` | name and role | logged in |
| `GET /api/projects` | projects the user can see | logged in |
| `POST /api/projects` | create a project on a source folder | owner, admin |
| `GET /api/projects/{id}` | one project with members | project viewer and up |
| `PUT /api/projects/{id}/members` | set contributors and viewers | project owner, admin |
| `GET /api/sources?path=` | folders under the sources roots, for the picker | owner, admin |
| `POST /api/projects/{id}/scans` | `{"full"}`: queue a scan | project contributor and up |
| `GET /api/projects/{id}/scans` | past and current scans | project viewer and up |
| `GET /api/projects/{id}/scans/{scanId}` | live status: stage, done/total, log | project viewer and up |
| `POST /api/projects/{id}/scans/{scanId}/cancel` | stop at the next file | project contributor and up |
| `GET /api/projects/{id}/inventory` | files of the newest scan, with filters and totals | project viewer and up |
| `GET /api/projects/{id}/components` | components of the newest scan | project viewer and up |
| `GET /api/projects/{id}/dashboard` | headline numbers, charts, effort, top findings and coverage of the newest scan (`store.Dashboard`) | project viewer and up |
| `GET /api/projects/{id}/facts/{kind}?q=&limit=&offset=&<field>=` | rows of one kind of fact of the newest scan (`store.FactsQuery`); `<field>=~text` means contains | project viewer and up |
| `GET /api/projects/{id}/reports` | run id of the newest scan, the address of each built report and PDF (or null), the data pack address, and the last report build (state, step, done/total, messages) | project viewer and up |
| `POST /api/projects/{id}/reports` | `{"type": "tech" \| "functional" \| "mgmt" \| "summary" \| "pdf" \| "all"}`: build in the background (`ReportJobs`, one build per project at a time, 409 while one runs) | project contributor and up |
| `GET /api/projects/{id}/runs/{runId}/reports/{path}` | a file of the run's reports (pages, assets, data, PDFs); relative links inside a report page resolve under the same address | project viewer and up |
| `GET /api/projects/{id}/enrichment` | the stages of the newest scan (number, name, title, file, state: valid, not validated, invalid, missing or stale, and why), the next `/audit-enrich` prompt for Copilot, the model settings (`ai`, with a `problem` text when they cannot work) and the last enrichment job | project viewer and up |
| `POST /api/projects/{id}/enrichment/validate` | runs the validator on the newest scan; answers the messages and the new stage states | project contributor and up |
| `POST /api/projects/{id}/enrichment/run` | `{"stale": true, "stages": [...]}`: runs the stages with the model in the background (`EnrichJobs`, one per project, 409 while one runs); 400 with plain words when the provider is `copilot`, the model or key is missing, or nothing is to do | project contributor and up |
| `GET /api/projects/{id}/runs/{runId}/datapack.zip` | `run.json`, `data/` and the enrichment files as a ZIP | project viewer and up |

The results calls read the newest finished scan's run folder (`<project>/audit-output/runs/<run-id>/`);
the inventory and components also live in PostgreSQL for the filters of the inventory page.

## Security (`SecurityConfig`, `auth`)

- Test login as in the Python hosted site: a user name and no password. A new name becomes an
  owner; names in `LEGACY_AUDIT_ADMINS` are always admins; a disabled user is refused.
- Roles from low to high (`Roles`): viewer, contributor, owner, admin. A user's role on a
  project (`Roles.on`): admin for admins, owner for the project's owner, otherwise the role
  given in the project's members (a global viewer stays a viewer); no membership means the
  project is not visible. Only admins and owners create projects. `ProjectService` answers
  "may this user do that on this project".
- `CurrentUser` reads the user fresh on every request, so a role change or a disable counts at
  once.
- Session cookie `LEGACY_AUDIT_SESSION`; CSRF in Spring's single-page-app mode: the server sets
  the `XSRF-TOKEN` cookie and Angular sends it back as the `X-XSRF-TOKEN` header.

## Background scans (`ScanJobs`)

- `POST .../scans` stores a `scan` row as `queued` and hands it to a fixed pool of
  `max-scans` threads. Only one scan per project runs at a time.
- A running scan calls `Scanner.run` (audit-engine) with a progress callback, a cancel flag and
  a log listener. Stage, counts and the last log lines are kept in memory for
  `GET .../scans/{scanId}`, which the Angular page asks every 1.5 s.
- When the scan ends, `ScanStore` saves the counts, the log tail, the inventory and the
  components; the run folder under the project keeps the full JSON, CSV and SQLite results.
- Cancel sets the flag; the engine stops at the next file and removes the unfinished run
  folder. Scans still running when the server stops are marked failed at the next start.

## Database (Flyway, `src/main/resources/db/migration`)

| Migration | Tables |
| --- | --- |
| `V1__users.sql` | `app_user` (name, role, disabled, last login) |
| `V2__projects_and_scans.sql` | `project`, `project_member`, `scan`, `inventory_file`, `component` |

Rules: never change a migration that has been merged; add `V<n+1>__what.sql`. Queries use
Spring's `JdbcClient` with plain SQL (no JPA). Tests run on H2 in PostgreSQL mode, so keep to
SQL both understand.

## Adding an endpoint

1. Put the controller in the package of its feature; keep SQL in a `...Store` class and rules
   in a `...Service` class.
2. Check access with `ProjectService` / `CurrentUser` and throw `ApiException` with a plain
   message when refused.
3. Add the call to `frontend/src/app/api.service.ts` with its TypeScript type.
4. Add a test (`@SpringBootTest` with H2, see the existing tests in `src/test`).
