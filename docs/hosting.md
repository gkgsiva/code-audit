# Hosting legacy-audit for project owners

The hosted site is one web site for the whole team. A project owner logs in, opens or creates
an audit project, scans the code and downloads the reports, all in the browser. Nothing is
installed on their laptop.

It runs as one Docker container. Every project lives in one top-level **audits folder**, a
folder on your machine that the container uses as `/audits`; the code to scan is a folder on
your machine shown read-only as **/sources**, or is cloned from Git. Nothing is kept inside the
container, so rebuilding or removing it never loses a project.

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
2. Copy `docker/env.example` to `.env` (same folder as `docker-compose.yml`) and open it.
3. Set `LEGACY_AUDIT_SESSION_SECRET` to a long random text.
4. Set the two folders (use forward slashes, also on Windows):
   - `AUDITS_DIR`: where projects, reports, logs and settings are kept, for example
     `C:/legacy-audit/audits` (Windows) or `/home/siva/legacy-audit/audits` (WSL2).
     Create the folder first.
   - `SOURCES_DIR`: the code you want to scan, for example `C:/code` or `/home/siva/code`.
   Left as they are, both are folders next to `docker-compose.yml` (`audits`, `sources`).
5. **Docker Engine in WSL2 or Linux only**: set `AUDIT_UID` and `AUDIT_GID` to your own ids
   (type `id -u` and `id -g` in the terminal; usually `1000`). Docker Desktop on Windows: leave
   them at `10001`.
6. At the office: if the build needs the company proxy, set `BUILD_PROXY` and `RUN_PROXY` too
   (see [Building at the office](#6-building-at-the-office)).
7. Add API keys only if you use AI enrichment with a cloud model.
8. Run the VS Code task **`audit: 36. run the hosted site (Docker)`**
   (Terminal > Run Task...). Or in a terminal: `docker compose up --build -d`.
   The first build takes a few minutes (it downloads Chromium, Node.js and OSV-Scanner).
9. Open **http://localhost:8765**. Log in as **admin** (the name in `LEGACY_AUDIT_ADMINS`).

Stop it with `docker compose down`. Your projects stay in the `AUDITS_DIR` folder.

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

### Git projects: another branch

A Git project scans the branch you typed when you created it (empty: the repository's default
branch).

- **Scan another branch once**: on **4. Run**, type the branch or tag in **Branch or tag** (for
  example `release/2.1`), then **Re-run**. The project keeps its own branch for later scans.
- **Change the project's branch for good**: change `ref` under `source` on **1. Project setup**.
- The **Runs** table shows the branch and commit of every run, and so do the summary and
  technical reports at the top. Each run is kept, so you can compare branches run by run.

The site keeps one copy (clone) per repository and branch, with the last commit only. Every
scan updates it. An admin sees all clones on **Admin > Health > Git clones**.

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
| `RUN_PROXY`, `RUN_NO_PROXY` (in `.env`) | empty | Company proxy for Git clones and osv.dev lookups; compose passes them as `HTTPS_PROXY`/`HTTP_PROXY`/`NO_PROXY` |
| `AUDITS_DIR`, `SOURCES_DIR` (in `.env`) | `./audits`, `./sources` | The folders on your machine used as `/audits` and `/sources` |
| `AUDIT_UID`, `AUDIT_GID` (in `.env`) | `10001` | User id the site runs as; on Linux/WSL2 Docker Engine set your own so it can write `AUDITS_DIR` |

## 4. The admin page

Log in as an admin and open **Admin**. Settings are saved in
`/audits/.legacy-audit/admin.yaml` and apply at once.

| Tab | What you set |
| --- | --- |
| Dashboard | Opens first. **Usage** for the last 7, 30 or 90 days: people with access and active people, logins, projects, scans (and how many finished), average and longest scan, average wait in line, reports built, AI enrichment runs; charts of scans per day (finished, failed, stopped), people per day, reports and AI enrichment per day, and the busiest projects; the most active people and recent failures with their error. **Server**: memory used against the container's limit, this server's own memory, CPU now and the number of CPUs, disk free and used, jobs running and waiting, uptime, version; charts of memory and CPU over the last hour. Each chart has **Show as a table**; **Download CSV** saves the numbers per day. Usage refreshes every minute, the server every 10 seconds |
| Reports | Which reports anyone can build and see (summary, management deck, PowerPoint, functional, technical, PDF, data pack), which summary-report sections appear, and whether owners may hide more on their project |
| Paths | More folders projects may scan, the Git clone cache, the PowerPoint templates folder, Chrome/Edge for PDFs, scanner programs and databases |
| Project defaults | The common `audit.yaml` settings every project inherits on each scan: migration target, exclude patterns, SQL dialect, component grouping, PowerPoint template, effort, PDF, rules. Tick **lock** so projects cannot change a setting. The advanced box takes any `audit.yaml` section as YAML |
| Users and roles | Open sign-up on or off, the role of new users, who can create projects; add, disable or remove users |
| Projects | Every project folder: set an owner (a folder copied in by hand has none), manage access, delete (type the folder name to confirm; this cannot be undone) |
| AI | Allow AI enrichment or not; provider, model, endpoint and the **name** of the key variable |
| Scanners | OSV-Scanner and Dependency-Check on or off, online or offline, timeouts; JDK tools |
| Limits | Jobs at once (others wait in line), runs kept per project, scan time limit, how long Git clones are kept (14 days unused) and how much disk to keep free (5 GB) |
| Activity | Who logged in, created projects, scanned, built reports or changed settings; download as CSV |
| Health | Version, disk space, space per project, the tool checks (Python, Chrome, Node, scanners, Git), and the Git clones: repository, branch, commit, which projects use it, last used, size, **Delete** and **Clean up now** |

Roles: **admin** (everything), **owner** (creates projects; runs and manages their own),
**contributor** (scans and builds reports on projects they are added to), **viewer** (reads
results and downloads reports).

## 5. Where things are

Everything lives in the audits folder (`AUDITS_DIR`), so copying that folder backs up the
site:

- `/audits/<project>/` one folder per project: `audit.yaml` (only what is the project's own;
  the rest comes from the project defaults), `audit-overrides.yaml`, `audit-inputs/`,
  `audit-output/runs/<run-id>/` (facts, reports, `logs/scan.log`).
- `/audits/.legacy-audit/admin.yaml` admin settings, `users.yaml`, `projects.yaml` (owner,
  members, hidden reports), `activity.jsonl`, `logs/server.log`, `git/` (clones).

A project folder made with the CLI on a laptop can be copied into the audits folder; it shows
on **Admin > Projects** without an owner until you set one. Its `source` must point under the
sources folder.

## 6. Building at the office

The build downloads from four places: Debian packages (`deb.debian.org`), Python packages
(`pypi.org`), the Mermaid CLI (`registry.npmjs.org`) and OSV-Scanner (`github.com`). A company
network often blocks one of them. When the build stops, it prints a box starting with
**legacy-audit build stopped:** that says which download failed and what apt or pip said.

Do these one at a time, and build again after each (task 36, or task
**`audit: 39. build the Docker image and save build.log`** to keep the whole output in
`build.log`):

1. **Proxy.** Ask IT for the proxy address, or look in Windows: Settings > Network & internet >
   Proxy. Put it in `.env`:
   ```
   BUILD_PROXY=http://proxy.example.com:8080
   RUN_PROXY=http://proxy.example.com:8080
   ```
   `BUILD_PROXY` is for the build, `RUN_PROXY` for Git clones and osv.dev while the site
   runs. A proxy that needs your password: set it in Docker Desktop > Settings > Resources >
   Proxies instead, so the password is not kept in the image.
2. **Company certificate.** If the box says `certificate`, `SSL` or `no longer signed`, the
   proxy looks inside HTTPS. Get the company root certificate (IT, or export it from your
   browser: the padlock > Certificate > the top one > Export, "Base-64 .cer"). Rename it to
   end with `.crt` and copy it into `docker/certs/`.
3. **Company mirrors.** If the public servers are blocked (an error like `403 Forbidden` or
   `Could not connect`), ask IT for the mirror addresses (Nexus or Artifactory) and set the ones
   you need in `.env`: `APT_MIRROR` (Debian, for example
   `https://nexus.example.com/repository/debian`, and `APT_SECURITY_MIRROR` if security updates
   have their own), `PIP_INDEX_URL` (Python), `NPM_REGISTRY` (npm), `BASE_IMAGE` (Docker Hub
   blocked: the same `python:3.12-slim` image from the company registry).
4. **Get running now.** `WITH_SYSTEM_PACKAGES=false` in `.env` builds without any Debian
   package. The site works with folders under `/sources`, but there is no Git (no Git
   projects), no PDF copies and no diagram pictures. Set it back to `true` once the proxy
   works.

The Mermaid CLI and OSV-Scanner are optional: when they cannot be downloaded the build carries
on, prints a `WARNING:` line, and **Admin > Health** shows them as missing. To leave them out:
`WITH_MERMAID=false`, `WITH_OSV=false` (and `WITH_CHROMIUM=false` for no PDF copies).

## 7. Git clones and disk space

Clones live in `/audits/.legacy-audit/git/` (or the folder on **Admin > Paths**). They are
deleted on their own:

- when not scanned for **Keep Git clones (days)** (Admin > Limits, 14 by default), checked
  every hour and after each scan;
- when the disk has less free space than **Keep this much disk free (GB)** (5 by default): the
  least recently used go first;
- when their project is deleted (unless another project scans the same repository).

A clone that a running or waiting scan may use is never deleted. To free space at once:
**Admin > Health > Git clones > Clean up now**, or **Delete** on one row. Nothing is lost: the
next scan clones it again. Old runs are a project's other big user of space: lower **Runs kept
per project** on Limits.

## 8. Updating

Pull the repository, then `docker compose up --build -d`. Projects, users and settings stay in
the audits folder. After an update that changes scanning, owners click **Re-run**.

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
| No folders in the picker | `SOURCES_DIR` in `.env` is empty or wrong; set it to your code folder and run `docker compose up -d` |
| A scan waits a long time | Other scans are running; see **Status**. An admin can raise **Limits > Jobs at once** (needs CPU and memory) |
| Disk almost full | **Admin > Health**: space per project and the Git clones. **Clean up now**, delete clones, or lower **Runs kept per project** |
| PDF copies skipped | The image was built with `WITH_CHROMIUM=false`, or set **Admin > Paths > Chrome or Edge** |
| Build stops at `apt-get` with `exit code: 100` (or the box "could not reach the Debian package servers") | The Debian packages could not be downloaded. Follow [Building at the office](#6-building-at-the-office) steps 1 to 4 |
| Build stops at `pip install` | Same steps: proxy, certificate, then `PIP_INDEX_URL` |
| The container stops at once; `docker compose logs` says "The audits folder ... cannot be written" | Linux/WSL2: set `AUDIT_UID`/`AUDIT_GID` in `.env` to `id -u`/`id -g`, or run `sudo chown -R 10001:10001` on the folder. Then `docker compose up -d` |
