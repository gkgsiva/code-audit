# Hosting legacy-audit for project owners

The hosted site is one web site for the whole team. A project owner logs in, opens or creates
an audit project, scans the code and downloads the reports, all in the browser. Nothing is
installed on their laptop.

It runs as one Docker container. Every project lives in one top-level **audits folder**
(a Docker volume); the code to scan is mounted read-only under **/sources** or cloned from Git.

```
browser ──> legacy-audit container ──> /audits/<project>/audit.yaml, audit-inputs/, audit-output/
                    │                  /audits/.legacy-audit/  (admin settings, users, logs)
                    └── reads ───────> /sources/... (read-only)  or  Git (https/ssh)
```

Login is a **test login** for now (a user name, no password). The page says so in a yellow
bar. Okta replaces it later with settings only (see [Okta later](#okta-later)).

## 1. Start it

You need Docker: **Docker Desktop** on Windows (with the WSL2 engine), or Docker Engine inside
WSL2/Linux.

1. Open the repository folder in VS Code.
2. Put the code you want to scan in a folder named `sources` next to `docker-compose.yml`
   (or change the `./sources` line in `docker-compose.yml` to your code folder, for example
   `C:/code` on Windows or `/home/siva/code` on WSL2).
3. Copy `docker/env.example` to `.env` (same folder as `docker-compose.yml`) and set
   `LEGACY_AUDIT_SESSION_SECRET` to a long random text. Add API keys only if you use AI
   enrichment with a cloud model.
4. Run the VS Code task **`audit: 36. run the hosted site (Docker)`**
   (Terminal > Run Task...). Or in a terminal: `docker compose up --build -d`.
   The first build takes a few minutes (it downloads Chromium, Node.js and OSV-Scanner).
5. Open **http://localhost:8765**. Log in as **admin** (the name in `LEGACY_AUDIT_ADMINS`).

Stop it with `docker compose down`. Your projects stay in the `audits` volume.

**No Docker on your laptop?** Task **`audit: 37. run the hosted site without Docker (samples)`**
starts the same site from your normal install, with the sample apps as the code to scan and
the projects in `audits-hosted/`. Open http://127.0.0.1:8765.

## 2. What a project owner does

1. Log in with your name.
2. On **Projects**, fill in **Create a project**: a name, then where the code is. Either click
   through the folders under `/sources` until the box shows your application's folder, or
   choose **A Git repository** and paste its address.
3. **Create project** opens the project. Check **3. Components**, then **4. Run** > **Re-run**.
4. Follow the scan on **Status** (progress and about how long is left). If other scans are
   running, yours waits in line and shows its place.
5. Build the reports on **8. Reports** and open or download them.
6. To let colleagues in, go back to **Projects** > **Access and reports** and add them as
   contributors (can scan and build reports) or viewers (can read and download).

**Logs** shows the log of every scan on your projects, with a filter for warnings and errors.

## 3. Settings: environment variables

Set them in `docker-compose.yml` (or with `docker run -e NAME=value`) and restart with
`docker compose up -d`. Values set here win over the admin page and show there as locked.

| Variable | Default | What it sets |
| --- | --- | --- |
| `LEGACY_AUDIT_AUDITS_ROOT` | `/audits` | Top-level audits folder; one sub-folder per project |
| `LEGACY_AUDIT_SOURCES_ROOT` | `/sources` | Folders projects may scan (comma separated); mount them read-only |
| `LEGACY_AUDIT_PORT` | `8765` | Port the site listens on |
| `LEGACY_AUDIT_HOST` | `0.0.0.0` | Address the site listens on inside the container |
| `LEGACY_AUDIT_PUBLIC_URL` | empty | Address people type, e.g. `https://audit.example.com`. When set, other host names are refused and cookies are marked secure over https |
| `LEGACY_AUDIT_AUTH` | `dummy` | `dummy` = test login. `okta` is reserved for the Okta step |
| `LEGACY_AUDIT_ADMINS` | `admin` | User names that are always admins (comma separated) |
| `LEGACY_AUDIT_SESSION_SECRET` | random at each start | Signs the login cookie. Set it, or everyone is logged out at each restart |
| `LEGACY_AUDIT_SESSION_HOURS` | `12` | How long a login lasts |
| `LEGACY_AUDIT_MAX_SCANS` | admin page (2) | Jobs running at once across all projects |
| `LEGACY_AUDIT_KEEP_RUNS` | admin page (10) | Runs kept per project |
| `LEGACY_AUDIT_TEMPLATES_DIR` | built-in | Folder with the corporate PowerPoint templates |
| `OPENAI_API_KEY`, `AZURE_OPENAI_API_KEY`, `ANTHROPIC_API_KEY` | empty | AI keys, read by name only, never stored or shown |
| `GIT_TOKEN` (or any name) | empty | Read-only Git token; a project names the variable, never the token |
| `HTTPS_PROXY`, `NO_PROXY` | empty | Company proxy for Git clones and osv.dev lookups |

## 4. The admin page

Log in as an admin and open **Admin**. Settings are saved in
`/audits/.legacy-audit/admin.yaml` and apply at once.

| Tab | What you set |
| --- | --- |
| Reports | Which reports anyone can build and see (summary, management deck, PowerPoint, functional, technical, PDF, data pack), which summary-report sections appear, and whether owners may hide more on their project |
| Paths | More folders projects may scan, the Git clone cache, the PowerPoint templates folder, Chrome/Edge for PDFs, scanner programs and databases |
| Project defaults | The common `audit.yaml` settings every project inherits on each scan: migration target, exclude patterns, SQL dialect, component grouping, PowerPoint template, effort, PDF, rules. Tick **lock** so projects cannot change a setting. The advanced box takes any `audit.yaml` section as YAML |
| Users and roles | Open sign-up on or off, the role of new users, who can create projects; add, disable or remove users |
| Projects | Every project folder: set an owner (a folder copied in by hand has none), manage access, delete (type the folder name to confirm; this cannot be undone) |
| AI | Allow AI enrichment or not; provider, model, endpoint and the **name** of the key variable |
| Scanners | OSV-Scanner and Dependency-Check on or off, online or offline, timeouts; JDK tools |
| Limits | Jobs at once (others wait in line), runs kept per project, scan time limit |
| Activity | Who logged in, created projects, scanned, built reports or changed settings; download as CSV |
| Health | Version, disk space, space per project, and the tool checks (Python, Chrome, Node, scanners, Git) |

Roles: **admin** (everything), **owner** (creates projects; runs and manages their own),
**contributor** (scans and builds reports on projects they are added to), **viewer** (reads
results and downloads reports).

## 5. Where things are

Everything lives in the audits volume, so backing it up backs up the site:

- `/audits/<project>/` one folder per project: `audit.yaml` (only what is the project's own;
  the rest comes from the project defaults), `audit-overrides.yaml`, `audit-inputs/`,
  `audit-output/runs/<run-id>/` (facts, reports, `logs/scan.log`).
- `/audits/.legacy-audit/admin.yaml` admin settings, `users.yaml`, `projects.yaml` (owner,
  members, hidden reports), `activity.jsonl`, `logs/server.log`, `git/` (clones).

A project folder made with the CLI on a laptop can be copied into the audits folder; it shows
on **Admin > Projects** without an owner until you set one. Its `source` must point under the
sources folder.

## 6. Company network

- **Proxy**: build with `docker build --build-arg HTTPS_PROXY=http://proxy:8080 ...` (or set
  `HTTPS_PROXY` in your Docker Desktop settings), and set `HTTPS_PROXY`/`NO_PROXY` in
  `docker-compose.yml` for Git and osv.dev at run time.
- **Company root certificate** (a proxy that inspects HTTPS): copy the `.crt` file into
  `docker/certs/` before building. It is added to the image's trust store for pip, npm, Git,
  curl and Python.
- **Python package mirror**: `--build-arg PIP_INDEX_URL=https://nexus.example.com/pypi/simple`.
- **Smaller image**: `--build-arg WITH_MERMAID=false` (no diagram pictures in PowerPoint),
  `WITH_CHROMIUM=false` (no PDF copies), `WITH_OSV=false` (no OSV-Scanner).

## 7. Updating

Pull the repository, then `docker compose up --build -d`. Projects, users and settings stay in
the volume. After an update that changes scanning, owners click **Re-run**.

## Okta later

The login sits behind one small interface (`legacy_audit/hosted/auth.py`). The Okta step adds
an OpenID Connect provider: set `LEGACY_AUDIT_AUTH=okta`, `OKTA_ISSUER`, `OKTA_CLIENT_ID`,
`OKTA_CLIENT_SECRET` and `LEGACY_AUDIT_PUBLIC_URL` (the callback is
`<public URL>/auth/callback`), and Okta groups map to the four roles. Until then
`LEGACY_AUDIT_AUTH=okta` stops the site at start with a clear message.

## Troubleshooting

| You see | Do this |
| --- | --- |
| The page does not open | `docker compose ps` should say `healthy`; `docker compose logs` shows why not. Check nothing else uses port 8765 |
| "Host not allowed" | `LEGACY_AUDIT_PUBLIC_URL` is set to another address than the one you typed; fix it or leave it empty |
| Everyone is logged out after a restart | Set `LEGACY_AUDIT_SESSION_SECRET` in `.env` |
| "is outside the folders this site may scan" | The project's folder is not under `/sources` (or a folder added on Admin > Paths). Mount it in `docker-compose.yml` |
| No folders in the picker | Nothing is mounted under `/sources`; check the `volumes:` lines in `docker-compose.yml` |
| A scan waits a long time | Other scans are running; see **Status**. An admin can raise **Limits > Jobs at once** (needs CPU and memory) |
| PDF copies skipped | The image was built with `WITH_CHROMIUM=false`, or set **Admin > Paths > Chrome or Edge** |
| Permission denied writing /audits | A bind mount instead of the named volume must be writable by user id 10001 |
