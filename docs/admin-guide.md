# Admin guide

For whoever installs legacy-audit for a team, runs the web app, keeps the vulnerability data
current and maintains the rule packs. Analysts start with [user-guide.md](user-guide.md).

There are two ways to use the tool:

- **The web app** (Docker): one site for the whole team, with a login, projects, roles, an
  admin page, status and logs. Install it once on a server or on a laptop with Docker.
- **The `legacy-audit` command** (a Java program) on a laptop: scans, reports, validation and
  the Copilot files, for one analyst working in VS Code.

## The web app (Docker)

Step by step in [spring/docker.md](spring/docker.md): install Docker, copy
`docker/env.example` to `.env`, fill in the folders, then run **`audit: 1. start the web app
(Docker)`** (or `docker compose up -d --build`) and open http://localhost:8770.

What is in the images:

| Image | Contents |
| --- | --- |
| `backend` | Debian with the Java 25 runtime, the server, Chromium (PDF copies), Git and ssh (Git projects), fonts and OSV-Scanner; runs as user `audit` (uid 10001), never as root |
| `frontend` | nginx serving the Angular app and passing `/api` to the backend |
| `db` | PostgreSQL 17 (users, projects, scans, the activity log) |

The projects, their scan results and the server log are in the audits folder (`AUDITS_DIR` in
`.env`); back it up together with the database volume. The code to scan is read from
`SOURCES_DIR` (read only), or cloned from Git.

At the office, when the build cannot download something, see the table in
[spring/docker.md](spring/docker.md#4-building-at-the-office) (`BUILD_PROXY`, company
certificates, `MAVEN_MIRROR`, `NPM_REGISTRY`, `APT_MIRROR`, company images). The extras can be
left out: `WITH_CHROMIUM=false` (no PDF copies), `WITH_OSV=false` (no OSV-Scanner),
`WITH_SYSTEM_PACKAGES=false` (no Git, no PDFs).

### The Admin page

Log in with a name listed in `LEGACY_AUDIT_ADMINS` (`.env`). **Admin** in the top bar has:

- **Site settings**: which reports can be built (and which summary sections show), whether
  project owners may hide reports, whether the AI model may run on the server, the limits (jobs
  at the same time, runs kept per project, scan time limit, Git clone clean-up), sign-up (may a
  new name log in, with which role, and who may create projects), more folders projects may scan,
  the Git clone folder, and **locked settings** (audit.yaml keys every project must keep as in the
  defaults). `MAX_SCANS` and `KEEP_RUNS` in `.env` fix those two limits; leave them empty to set
  them here.
- **Project defaults**: an audit.yaml part every project starts from (for example `security:` or
  `ai:`). A project's own audit.yaml wins, except for locked keys.
- **Users**: add a user with a role (admin, owner, contributor, viewer), change the role, disable
  or remove. The names in `LEGACY_AUDIT_ADMINS` are always admins.
- **All projects** with their owner, runs, size and any problem in their audit.yaml.
- **Activity**: logins, project changes, scans, report builds, AI runs and admin changes;
  **Download all as CSV**.
- **Git clones**: the copies of Git projects, cleaned up every hour and after each scan.
- **Health**: version, disk, the size of each project and the tools the server has.

**Dashboard** shows the use of the site (logins, active users, scans, reports, AI runs per day,
the busiest projects and users, failures) and the server's memory, CPU and disk.
**Status** shows the jobs running and waiting; **Logs** the server log and each scan's log.

## The `legacy-audit` command on a laptop

Needs **Java 25** (for example Eclipse Temurin 25). Build it once from a checkout (Maven 3.9):

```
cd backend
mvn -B -DskipTests package -pl audit-cli -am
```

`backend/audit-cli/target/` then holds `legacy-audit.jar` and the launchers `legacy-audit`
(Linux, WSL2, macOS) and `legacy-audit.cmd` (Windows). Copy the three files to a folder of your
choice (for example `C:\tools\legacy-audit`) and add that folder to `PATH`; then
`legacy-audit doctor` in any folder says what is installed. Without `PATH`:
`java -jar <folder>\legacy-audit.jar doctor`.

Commands: `init`, `scan`, `report`, `validate`, `enrich`, `facts`, `diff`, `mcp`, `doctor`,
`purge-cache`, `version` (`legacy-audit <command> --help` for the options).

Optional tools (the `doctor` command says what each missing one costs):

| Tool | Needed for | Without it |
| --- | --- | --- |
| Java 25 | everything | — |
| Microsoft Edge, Google Chrome or Chromium | PDF copies of the reports | the HTML reports still work |
| OSV-Scanner or OWASP Dependency-Check | vulnerability data during the scan | import their JSON reports into `audit-inputs/security/` |
| A JDK (`jdeps`) | Java internal-API checks on jars (`security.jdk_tools.enabled`) | the check is skipped and listed as a gap |
| Git | Git sources (`source.type: git`) | use local folders |

## Vulnerability data

The scan downloads nothing itself. Vulnerability data comes from tools you run or reports you
import.

- **OSV-Scanner** (version 2.x), the quickest setup (in the Docker image too):
  `security.osv_scanner.path` (or on `PATH`) and `offline: false` (the default). The scan runs
  `osv-scanner --experimental-plugins java/archive`, which asks the osv.dev service about package
  names and versions only (no source). Needs outbound HTTPS to `api.osv.dev` (in Docker:
  `RUN_PROXY` in `.env` at the office).
- **OSV-Scanner offline**: add `data_dir` and `offline: true`; refresh the local database on a
  schedule with `osv-scanner scan source --offline-vulnerabilities
  --download-offline-databases --experimental-plugins java/archive -r <code folder>` and
  `OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY` set to `data_dir`.
- **Dependency-Check on the hosted site** (in the Docker image since November 2026): nothing to
  install. The NVD database is not in the image; the site keeps one copy for every project in
  `<audits folder>/.legacy-audit/vulndb/dependency-check`, so it survives a rebuild. To fill it:
  1. Get a free NVD API key at https://nvd.nist.gov/developers/request-an-api-key (without one
     the first download can take hours) and put it in `.env` as `NVD_API_KEY=...`. Or, if your
     company keeps a copy of the NVD data feed, set `NVD_MIRROR_URL` to it (for example
     `https://nexus.example.com/nvd/nvdcve-{0}.json.gz`).
  2. At the office set `RUN_PROXY` (and `RUN_NO_PROXY`) in `.env`; the update uses that proxy.
  3. Restart the site (`docker compose up -d`). With a key or a mirror it downloads the database
     at start; otherwise go to **Admin > Vulnerability database** and click **Update the
     database now**. The panel shows the version, the database size and date, and the last
     lines of the tool's output while it runs.
  4. It refreshes every night at `VULNDB_REFRESH` (default `02:00`, `off` for never).
  Scans only read the database (`offline: true`); while it is empty they list Dependency-Check
  as a coverage gap and carry on. The key and a proxy password go to the tool in a temporary file,
  never on its command line or in a log. A project can still name its own `data_dir`.
  `WITH_DEPENDENCY_CHECK=false` builds the image without it.
- **Dependency-Check on a laptop**: `security.dependency_check.path`, `data_dir` (local NVD copy),
  `nvd_mirror_url`, `offline: true` (default). Update the database outside the scans, for example
  weekly: `dependency-check --updateonly --data <data_dir> --nvdApiKey <key>`. Offline scans add
  `--disableOssIndex --disableCentral`, so they make no network calls.
- **Imported reports**: put Dependency-Check or OSV-Scanner JSON output in
  `audit-inputs/security/`.

Each run records the date of the vulnerability data in `run.json`, and the reports print it.

## Branding

```yaml
branding:
  theme: neutral
  logo: branding/logo.png          # relative to audit.yaml; shown on the reports and the deck
  primary_color: "#1f4e79"
  accent_color: "#2e75b6"
  font: "Segoe UI, Arial, sans-serif"
```

On the web app, put the `branding:` block in **Project defaults** to apply it to every project.
Colours and font are checked before they reach the reports. (`pptx_template` is still accepted
and ignored: there is no PowerPoint report.)

## Rule packs

See [rules.md](rules.md). Keep team packs in a shared folder and set `rules.packs_dir` so tool
upgrades never overwrite them. Review `rules/eol.yaml` and `rules/dependency-upgrades.yaml` at
least every quarter.

## Large estates

- `inventory.workers`: threads used to analyse files and grade SQL. `0` (default) uses one per
  CPU less one, at most 8.
- `inventory.max_file_size_mb` (default 20): larger files are counted but not read.
- `inventory.skip_build_output` (default `true`): compiler and IDE output is left out.
- Scans again reuse `audit-output/cache/`; `legacy-audit purge-cache -c audit.yaml` clears it
  (and the Git clone cache).
- Memory: the launchers give Java up to half the computer's memory, enough for about 2 million
  lines on an 8 GB laptop. For more, set `LEGACY_AUDIT_JAVA_OPTS=-Xmx6g` (times and details in
  [performance.md](performance.md)).
- On the web app, **Limits** on the Admin page sets how many jobs run at once (the rest wait),
  how many runs each project keeps and the time limit of one scan.

## Logs and secrets

Each run writes `audit-output/runs/<id>/logs/scan.log`; the web app also writes the server log
(`LEGACY_AUDIT_LOG_FILE`, in Docker `<audits>/.legacy-audit/logs/server.log`). Git tokens and
AI keys are read from environment variables (`.env`) and never written to settings, outputs or
logs; passwords found in scanned files are masked (`****`) in every output, the data pack and
the source viewer.
