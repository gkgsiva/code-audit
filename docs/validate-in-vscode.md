# Validating legacy-audit from VS Code

Every phase of the Spring Boot and Angular version ends with a section here: how to check, from
VS Code on a Windows laptop or in WSL2, that what the phase built works. The repository ships
`.vscode/` with tasks and recommended extensions, so no typing of commands is needed. The steps
for the earlier Python tool are on the `office-main` branch; it is no longer used.

## Once: open and set up

1. Get the branch. **Windows**: clone your office repository, **File > Open Folder** on it
   (trust the folder when asked), click the branch name at the bottom left and pick
   `origin/spring-boot-angular`. **WSL2**: in the Ubuntu terminal run
   `cd ~/code-audit && git fetch origin spring-boot-angular && git switch spring-boot-angular`,
   then `code .`.
2. Accept the recommended extensions (Java, Spring Boot, Angular, Docker).
3. What each task needs on the machine:
   - Tasks 1 to 3: Docker only (Docker Desktop on Windows, Docker Engine or Docker Desktop in
     WSL2).
   - Tasks 4 and 6: Java 25 and Maven. **Windows**: install a Java 25 JDK and Maven and put
     both on PATH. **WSL2**: `sudo apt install openjdk-25-jdk maven`.
   - Task 5: Node.js 22 (it includes npm).

Run a task with **Terminal > Run Task...** and pick it from the list.

## Phase 0: skeleton and parser proof

You only need Docker for steps 1 to 4; steps 5 to 7 also need Java 25 with Maven and
Node.js 22 on the machine.

1. Settings: if you have no `.env` yet, copy `docker/env.example` to `.env` and set
   `AUDITS_DIR` and `SOURCES_DIR` (see [docs/spring/docker.md](spring/docker.md)). On WSL2 with
   Docker Engine also set `AUDIT_UID` and `AUDIT_GID` (`id -u`, `id -g`). At the office, set
   `BUILD_PROXY` (and the mirrors if IT gave you any).
2. Run **`audit: 1. start the web app (Docker)`**. The first build takes a few
   minutes. At the end the list shows `db` (healthy), `backend` and `frontend` running.
3. Open http://localhost:8770 (on WSL2 open it in the Windows browser). The login page shows the
   yellow "Test login" bar and the version. Type **siva** and **Log in**: the page shows
   "Welcome, siva", your role **owner** at the top right, **Backend ok** and **Database up**.
   **Log out** goes back to the login page; log in as **admin** and the role shows **admin**.
   Type `../x` as the name: the page says to use letters, digits and `. _ @ -` only.
4. Run **`audit: 2. stop the web app (Docker)`**, then task 1 again and log in as
   **siva**: still **owner** (users are kept in the database volume). If the build stops at the
   office, run **`audit: 3`** and follow [docs/spring/docker.md](spring/docker.md), section 4.
5. Backend tests (Java 25 and Maven installed; on WSL2 `sudo apt install openjdk-25-jdk maven`):
   run **`audit: 4. backend build and tests (Java 25, Maven)`**. It ends with
   `BUILD SUCCESS`; the four modules report 16 tests, none failing.
6. Run **`audit: 6. parser proof report`** (after task 4). It prints three tables:
   JavaParser 47 of 47 files, tree-sitter with one Pro*C file with errors (the same as the
   Python tool), and JSqlParser 67 of 67 statements with the same tables as sqlglot. Compare
   with [docs/spring/phase-0-proofs.md](spring/phase-0-proofs.md).
7. Frontend (Node.js 22 installed): run **`audit: 5. frontend build and tests (Node.js)`**.
   It shows `Tests 2 passed` and ends with `Application bundle generation complete`.
