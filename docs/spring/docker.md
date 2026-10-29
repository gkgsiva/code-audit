# Running the Spring Boot and Angular version with Docker

This is the new version of legacy-audit, still being built phase by phase. In Phase 0 it shows
the login page and a status page; projects, scans and reports arrive in the next phases. The
Python version (`docker-compose.yml`, port 8765) keeps working beside it.

## 1. What you need

- **Windows**: Docker Desktop, started (the whale icon in the taskbar is steady).
- **WSL2 (Ubuntu)**: Docker Desktop with WSL integration turned on, or Docker Engine installed in
  Ubuntu.
- This repository opened in VS Code.

## 2. Settings (once)

The new version reads the same `.env` file as the Python version.

1. If there is no `.env` file next to `docker-compose.spring.yml` yet, copy `docker/env.example`
   to `.env`.
2. Set `AUDITS_DIR` and `SOURCES_DIR` as for the Python version (forward slashes on Windows, for
   example `C:/legacy-audit/audits`). Create the audits folder first.
3. On WSL2 with Docker Engine, set `AUDIT_UID` and `AUDIT_GID` to the output of `id -u` and
   `id -g`.
4. Optional: `WEB_PORT` (default 8770) if that port is taken, and `LEGACY_AUDIT_DB_PASSWORD` for
   the database password.

## 3. Start, open, stop

1. In VS Code: **Terminal > Run Task... > `audit: 1. start the web app (Docker)`**.
   The first build downloads Java, Maven, Node.js and PostgreSQL images and takes a few minutes.
2. When it ends, the list shows three containers: `db` (healthy), `backend` and `frontend`.
3. Open http://localhost:8770 and log in as **admin**.
4. To stop: **`audit: 2. stop the web app (Docker)`**. Users stay in the database
   volume `legacy-audit-spring_legacy-audit-db` and are there after the next start.

The same from a terminal:

```bash
docker compose -f docker-compose.spring.yml up -d --build
docker compose -f docker-compose.spring.yml down
```

## 4. Building at the office

If the build stops, run **`audit: 3. build the web app and save build-spring.log`**.
The end of `build-spring.log` shows a box "legacy-audit build stopped: ..." with what to set.
These `.env` values help (ask IT for the addresses):

| Setting | When |
| --- | --- |
| `BUILD_PROXY`, `BUILD_NO_PROXY` | Downloads need the company proxy. Used by Maven (written into its `settings.xml`) and npm |
| `docker/certs/*.crt` | The proxy inspects HTTPS. The certificates are added to Java's trust store and to Node.js |
| `MAVEN_MIRROR` | Maven Central is blocked; the company Maven repository (Nexus, Artifactory) |
| `NPM_REGISTRY` | The npm registry is blocked; the company npm mirror |
| `POSTGRES_IMAGE`, `BACKEND_BUILD_IMAGE`, `BACKEND_BASE_IMAGE`, `FRONTEND_BUILD_IMAGE`, `FRONTEND_BASE_IMAGE` | Docker Hub is blocked; the same images from the company registry |
| `RUN_PROXY`, `RUN_NO_PROXY` | The running app needs the proxy (Git clones and vulnerability lookups, later phases) |

Send `build-spring.log` if it still fails.
