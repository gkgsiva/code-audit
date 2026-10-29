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
   - Tasks 4 and 6 to 9: Java 25 and Maven. **Windows**: install a Java 25 JDK and Maven and put
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
   yellow "Test login" bar and the version. Type **siva** and **Log in**: the **Projects** page
   opens with your role **owner** at the top right (since Phase 1; Phase 0 showed a welcome page).
   **Log out** goes back to the login page; log in as **admin** and the role shows **admin**.
   Type `../x` as the name: the page says to use letters, digits and `. _ @ -` only.
4. Run **`audit: 2. stop the web app (Docker)`**, then task 1 again and log in as
   **siva**: still **owner** (users are kept in the database volume). If the build stops at the
   office, run **`audit: 3`** and follow [docs/spring/docker.md](spring/docker.md), section 4.
5. Backend tests (Java 25 and Maven installed; on WSL2 `sudo apt install openjdk-25-jdk maven`):
   run **`audit: 4. backend build and tests (Java 25, Maven)`**. It ends with
   `BUILD SUCCESS` and no test fails (32 tests since Phase 1).
6. Run **`audit: 6. parser proof report`** (after task 4). It prints three tables:
   JavaParser 47 of 47 files, tree-sitter with one Pro*C file with errors (the same as the
   earlier Python tool), and JSqlParser 67 of 67 statements with the same tables. Compare
   with [docs/spring/phase-0-proofs.md](spring/phase-0-proofs.md).
7. Frontend (Node.js 22 installed): run **`audit: 5. frontend build and tests (Node.js)`**.
   It shows `Tests 5 passed` (since Phase 1) and ends with `Application bundle generation complete`.

## Phase 1: projects, scans and the inventory

What is new: create a project on a code folder, scan it, follow the scan live, and look at
every file with its language and lines of code. The counts are the same as the earlier Python
tool's.

1. Get the new code: in the Source Control panel click **Sync Changes** (or on WSL2
   `git pull origin spring-boot-angular`).
2. Put some code to scan in your sources folder (`SOURCES_DIR` in `.env`, for example
   `C:/legacy-audit/sources` or `~/legacy-audit/sources`). To try it with the sample apps, copy
   the repository's `samples` folder into it. New in `.env` (optional): `MAX_SCANS`, the number of
   scans that may run at the same time (default 2).
3. Run **`audit: 1. start the web app (Docker)`** (it rebuilds the images; a few minutes).
4. Open http://localhost:8770 and log in as **siva**. The **Projects** page says there are no
   projects yet. Click **New project**.
5. Type the name **Struts orders**, then open folders in the list (for example **sources**, then
   **samples**, then **struts-oracle-app**) until the line "Selected:" shows the code folder.
   Click **Create project**. The project page opens.
6. Click **Start scan**. The badge shows **queued**, then **running** with the step and a bar,
   and the log below fills. After a few seconds it shows **done** with the number of files,
   components and lines of code, and a **Components** table.
7. Click **Open the inventory**. The table "Lines of code per language" lists each language with
   files, code, comments and blank lines, and a total. Pick **java** in the first list: only Java
   files remain. Type part of a file name and click **Search**. **Clear** shows all files again.
8. Back on the project, under **Who has access**, add **anna** as **viewer**. Log out, log in
   as **anna**: she sees the project and the inventory but has no **Start scan** button. Log in
   as **bob**: he does not see the project. Log in as **admin**: the admin sees every project.
9. Start a scan and click **Cancel scan** at once: it ends as **cancelled**. Click
   **Full rescan**: it reads every file again (the past scans table shows the count under
   "Read again").
10. Your audits folder (`AUDITS_DIR`) now has a folder `struts-orders` with `audit.yaml`,
    `audit-overrides.yaml`, `audit-inputs/` and `audit-output/runs/<run>/` holding
    `inventory.json`, `components.json`, CSV files and `facts.sqlite`.
11. Command line (Java 25 and Maven installed): run **`audit: 4`**, then
    **`audit: 7. scan a sample app (command line)`** and pick **struts-oracle-app**. It prints
    `files: 37, analyzed: 37, from cache: 0, components: 1` and the late-input lines. Run it again:
    `from cache` is now 37.
12. Run **`audit: 5. frontend build and tests (Node.js)`**: `Tests 5 passed`.

The web project shows a few more files than the command line on the samples because the
sample's own `audit.yaml` and `audit-inputs` sit inside its code folder; on real code they do not.

## Phase 2: Java, SQL and configuration analyzers

What is new: the scan now reads what is inside the files: build files and libraries,
configuration files, Java classes, Struts/JSF/JSP pages, Spring XML, SQL scripts and the SQL
inside Java code, and application server files. The results are checked against the earlier
Python tool. They are shown as screens in Phase 5; for now the scan log and the command line
list how many files each analyzer read.

1. Get the new code: in the Source Control panel click **Sync Changes** (or on WSL2
   `git pull origin spring-boot-angular`).
2. Run **`audit: 4. backend build and tests (Java 25, Maven)`**. It ends with `BUILD SUCCESS`
   (51 tests).
3. Run **`audit: 8. compare the analyzers with the Python tool`**. It ends with
   `Tests run: 19, Failures: 0` and `BUILD SUCCESS`: every analyzer gives the same results as
   the Python tool on the 10 sample apps and on more than 2,000 recorded files.
4. Run **`audit: 7. scan a sample app (command line)`** and pick **struts-oracle-app**. Below
   the files line it now prints
   `analyzers: build 1 file, config 7 files, deps 5 files, java 11 files, servers 11 files, spring 3 files, sql 4 files, web 9 files`.
   Run it again with **spring-mvc-batch-app**: `build 4 files, config 8 files, java 11 files,
   servers 12 files, spring 6 files, web 6 files` (from Phase 3 on the line also lists the
   `autosys`, `mq` and `shell` files).
5. Web app: run **`audit: 1. start the web app (Docker)`**, open http://localhost:8770, log in
   as **siva**, open your project and click **Full rescan**. When it is **done**, the log shows a
   line starting with `Analyzers:` with the same kind of counts.
6. Developer reference: run **`audit: 9. build the developer reference (Javadoc)`**. Open
   `backend/target/reports/apidocs/index.html` in a browser (in VS Code: right-click the file,
   **Reveal in File Explorer**, then double-click it; on WSL2 run
   `explorer.exe "$(wslpath -w backend/target/reports/apidocs/index.html)"`). It lists every package and class
   with its description. The guides per module are in `docs/developer/` (start with
   `docs/developer/README.md`).

## Phase 3: mainframe, C and batch analyzers

What is new: the scan now also reads COBOL programs and copybooks (with CICS and BMS screens),
JCL, C, C++ and Pro*C, the SQL inside COBOL, C and Pro*C, shell scripts and crontabs, Autosys
jobs, IBM MQ definitions and CFT transfers. The results are checked against the earlier Python
tool.

1. Get the new code: in the Source Control panel click **Sync Changes** (or on WSL2
   `git pull origin spring-boot-angular`).
2. Run **`audit: 4. backend build and tests (Java 25, Maven)`**. It ends with `BUILD SUCCESS`
   (59 tests).
3. Run **`audit: 8. compare the analyzers with the Python tool`**. It ends with
   `Tests run: 27, Failures: 0` and `BUILD SUCCESS`.
4. Run **`audit: 7. scan a sample app (command line)`** and pick **c-proc-batch**. Below the
   files line it prints
   `analyzers: build 1 file, c_cpp 3 files, cft 1 file, proc 1 file, servers 2 files, shell 3 files, sql 1 file`.
   Run it again with **cobol-cics-db2**: `analyzers: cobol 8 files, jcl 2 files, sql 1 file`.
   With **spring-mvc-batch-app** the line now also has `autosys 1 file`, `mq 1 file` and
   `shell 1 file`, and with **struts-oracle-app** `shell 1 file`.
5. Web app: run **`audit: 1. start the web app (Docker)`**, open http://localhost:8770, log in
   as **siva**, open your project and click **Full rescan**. When it is **done**, the
   `Analyzers:` line in the log also counts these new kinds of files when the project has them.

