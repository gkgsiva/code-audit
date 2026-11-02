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
| `server.admin` | `AdminController`, `AdminStore`, `AdminSettings`, `Activity`, `Usage`, `SystemMetrics` | Site settings (`admin.yaml`), users, every project, the activity log, the usage and system dashboards, health |
| `server.jobs` | `JobGate` | The site's job slots shared by scans, report builds and AI runs, and the last 200 jobs |
| `server.status` | `StatusController` | The status page (slots and jobs) and the log pages |
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
| `LEGACY_AUDIT_MAX_SCANS` | jobs (scans, report builds, AI runs) running at the same time; when set, the admin page cannot change it | empty: `limits.max_scans` of `admin.yaml` (2) |
| `LEGACY_AUDIT_KEEP_RUNS` | runs kept per project after each scan; when set, the admin page cannot change it | empty: `limits.keep_runs` of `admin.yaml` (10) |
| `LEGACY_AUDIT_LOG_FILE` | the server log file (rolled to `server.log.1` ...) shown on the Logs page | Docker: `/audits/.legacy-audit/logs/server.log` |
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
| `GET /api/projects/{id}/runs` | every run, newest first: id, times, files, read again, lines of code, components, findings, warnings, seconds, Git commits, `latest` | project viewer and up |
| `GET /api/projects/{id}/compare?a=previous&b=latest` | two runs compared (`store.Diff`): counts per kind and the added, removed and changed records, each list cut to 200 with `*_count` the full number | project viewer and up |
| `GET /api/projects/{id}/runs/{run}/components/{name}` | one component (by name or id) with its services, screens, SQL, batch jobs and integrations (100 rows each with the total) | project viewer and up |
| `GET /api/projects/{id}/runs/{run}/evidence?file=&line=` | the 15 lines before and after (`start`, `end`, `text`), passwords masked; only files of the scanned source | project viewer and up |
| `GET /api/projects/{id}/config` | `audit.yaml` as text, the form fields (application, source, target, include, exclude) and its path | project viewer and up |
| `PUT /api/projects/{id}/config/fields` | changes only the keys that differ, keeps the rest of the file; 422 and the file unchanged when the result is not valid | project owner, admin |
| `PUT /api/projects/{id}/config/text` | `{"text"}`: the whole file; 422 and the file unchanged when not valid | project owner, admin |
| `GET /api/projects/{id}/inputs` | each input kind with its label, folder and files | project viewer and up |
| `PUT /api/projects/{id}/inputs/{kind}/{name}` | the file as the request body (200 MB at most; plain file names only) | project contributor and up |
| `DELETE /api/projects/{id}/inputs/{kind}/{name}` | removes one input file | project contributor and up |
| `GET /api/projects/{id}/component-choices` | the pre-scan (`scan.Prescan`) with the latest scan's type per component | project viewer and up |
| `PUT /api/projects/{id}/component-choices` | `{"included": {"<folders joined by \|>": true}, "types": {"<name>": "batch" \| null}}` to `audit-overrides.yaml` | project owner, admin |
| `GET /api/projects/{id}/doctor` | the tool checks (`ai.doctor.Doctor`) for this project | project viewer and up |
| `PUT /api/projects/{id}` | `{"title", "description", "owner", "archived", "hiddenReports", "hiddenSections"}`: change the project; hiding reports or summary sections needs `owners_may_hide` (or an admin) | project owner, admin |
| `POST /api/projects/{id}/delete` | `{"confirm": "<id>"}`: removes the project, its folder and its scans; 409 while a job runs | admin |
| `GET /api/users` | user names, for the owner picker | logged in |
| `GET /api/status` | job slots, running and waiting counts, the last jobs of the user's projects (all for admins) | logged in |
| `GET /api/logs` | the logs the user may read: the server log (admins) and each run's scan log | logged in |
| `GET /api/logs/view?id=&level=&q=&tail=` | the last lines of one log (at most 5,000), only warnings or errors, or lines with a text | logged in |
| `GET /api/logs/download?id=` | the whole log file | logged in |
| `GET /api/admin`, `PUT /api/admin` | the site settings (`admin.yaml`), the report and section names, roles and what the environment fixes; save with checks (422 with pydantic-style messages) | admin |
| `PUT /api/admin/defaults` | `{"text"}`: the project defaults as YAML; per-project keys (`application`, `source`, `components`, `inputs_dir`, `output_dir`) are refused | admin |
| `GET /api/admin/users`, `PUT /api/admin/users`, `DELETE /api/admin/users/{name}` | list, add or change (role, disabled), remove; the `LEGACY_AUDIT_ADMINS` names cannot be changed | admin |
| `GET /api/admin/projects` | every project with owner, runs, last run, size and any `audit.yaml` problem | admin |
| `GET /api/admin/activity?limit=&project=`, `GET /api/admin/activity.csv` | the activity log (logins, project changes, scans, report builds, AI runs, admin changes) | admin |
| `GET /api/admin/dashboard/usage?days=` | logins, active users, scans, report builds and AI runs per day, totals, report types, top projects and users, failures (`Usage`, as Python's `metrics.usage`) | admin |
| `GET /api/admin/dashboard/system` | memory and CPU (container limits when there are some), disk, uptime, threads, running jobs and the last hour of samples (`SystemMetrics`) | admin |
| `GET /api/admin/health` | version, disk, size of each project, tool checks, queue and source roots | admin |

`run` is `latest`, `previous` or a run id; the dashboard and facts calls also take `?run=` and
answer the `run_id` they read. Every `audit.yaml` under the audits folder is loaded through
`projects.HostedLayer`, which refuses output, inputs and overrides outside the project, local
sources outside `LEGACY_AUDIT_SOURCES_ROOT` and the project, and Git addresses that are not
https://, ssh:// or git@. The same layer applies the admin's project defaults (`defaults:` of
`admin.yaml`, under each project's own settings) and puts back the keys the admin locked.

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

- `POST .../scans` stores a `scan` row as `queued` and asks `JobGate` for a slot. Scans,
  report builds and AI runs share the `max_scans` slots (first come, first served). Only one
  scan per project runs at a time. A scan that runs over `scan_timeout_minutes` is stopped.
- After a scan, runs beyond `keep_runs` are removed (`Runs.prune`) and the activity log gets
  `scan finished`.
- A running scan calls `Scanner.run` (audit-engine) with a progress callback, a cancel flag and
  a log listener. Stage, counts and the last log lines are kept in memory for
  `GET .../scans/{scanId}`, which the Angular page asks every 1.5 s.
- When the scan ends, `ScanStore` saves the counts, the log tail, the inventory and the
  components; the run folder under the project keeps the full JSON, CSV and SQLite results.
- Cancel sets the flag; the engine stops at the next file and removes the unfinished run
  folder. Scans still running when the server stops are marked failed at the next start.

## Admin settings (`AdminStore`, `admin.yaml`)

`<audits root>/.legacy-audit/admin.yaml` holds what the admin page changes, with the same keys
as the Python hosted site: `reports` (`enabled` per report: summary, mgmt, functional, tech, pdf,
datapack; `summary_sections`; `owners_may_hide`), `paths` (`extra_source_roots`,
`git_cache_dir`), `users` (`open_signup`, `signup_role`, `who_can_create`), `ai.allowed`,
`limits` (`max_scans`, `keep_runs`, `scan_timeout_minutes`, `clone_keep_days`, `min_free_gb`),
`defaults` (project settings every project starts from) and `locked` (dotted keys a project
cannot change). The file is written with the PyYAML-style writer, so it reads the same as one
the Python site wrote. `MAX_SCANS` and `KEEP_RUNS` in `.env` win over the file and show as fixed.

## Database (Flyway, `src/main/resources/db/migration`)

| Migration | Tables |
| --- | --- |
| `V1__users.sql` | `app_user` (name, role, disabled, last login) |
| `V2__projects_and_scans.sql` | `project`, `project_member`, `scan`, `inventory_file`, `component` |
| `V3__admin.sql` | `activity` (time, user, action, project, details as JSON); `project.hidden_reports` and `hidden_sections` |

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
