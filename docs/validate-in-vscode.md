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
   - Tasks 4 and 6 to 16: Java 25 and Maven. **Windows**: install a Java 25 JDK and Maven and put
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

## Phase 4: web, front-end and desktop analyzers

What is new: the scan now also reads web service definitions (WSDL, Axis, JAX-WS, CXF), .NET
project, solution and configuration files, VB6 and VB.NET (forms and their controls), C#
(Windows Forms, WPF, ASP.NET, WCF, Entity Framework) and TypeScript/JavaScript (Angular,
AngularJS, Node.js with Express or NestJS). With this phase every analyzer of the Python tool
is ported except `metrics` and `patterns`, which come with Phase 5. The results are checked
against the earlier Python tool.

1. Get the new code: in the Source Control panel click **Sync Changes** (or on WSL2
   `git pull origin spring-boot-angular`).
2. Run **`audit: 4. backend build and tests (Java 25, Maven)`**. It ends with `BUILD SUCCESS`
   (64 tests). The first run downloads one new library (the TSX grammar); that needs the
   internet or your company's Maven mirror, like the first build did.
3. Run **`audit: 8. compare the analyzers with the Python tool`**. It ends with
   `Tests run: 32, Failures: 0` and `BUILD SUCCESS`.
4. Run **`audit: 7. scan a sample app (command line)`** and pick **angular-node-app**. Below
   the files line it prints
   `analyzers: config 14 files, deps 10 files, dotnet 9 files, servers 12 files, spring 9 files, typescript 28 files, web 10 files, webservices 9 files`.
   Run it again with **desktop-dotnet-app**:
   `analyzers: basic 10 files, config 4 files, csharp 15 files, dotnet 22 files, servers 2 files, shell 1 file, spring 3 files, webservices 4 files`.
   With **web-services-app** the line includes `webservices 8 files`. The other samples now
   also list `dotnet` and `webservices` (they read the XML configuration files too, as in the
   Python tool).
5. Web app: run **`audit: 1. start the web app (Docker)`**, open http://localhost:8770, log in
   as **siva**, open your project and click **Full rescan**. When it is **done**, the
   `Analyzers:` line in the log also counts these new kinds of files when the project has them.

## Phase 5: linking, findings, effort and the results pages

What is new: the scan now joins what it found across files and works out the results: complexity
and duplication per component, SQL graded for PostgreSQL (with the suggested PostgreSQL text),
end-of-life and vulnerable libraries with upgrade advice, the SBOM, the findings and the effort
estimate, plus `audit-summary.json`. Every result file now matches the earlier Python tool on all
ten sample apps. The web app gets a **Results** page and a **Facts** page, and the command line
gets `diff` to compare two scans.

1. Get the new code: in the Source Control panel click **Sync Changes** (or on WSL2
   `git pull origin spring-boot-angular`).
2. Run **`audit: 4. backend build and tests (Java 25, Maven)`**. It ends with `BUILD SUCCESS`
   (114 tests).
3. Run **`audit: 10. compare whole runs with the Python tool`**. It ends with
   `Tests run: 23, Failures: 0` and `BUILD SUCCESS`. This scans all ten samples and compares
   findings, libraries, effort, SBOM and the summary with what the Python tool wrote.
4. Run **`audit: 7. scan a sample app (command line)`** and pick **struts-oracle-app**. The last
   lines now also say `OWASP Dependency-Check: 1 file(s)` (the report in the sample's
   `audit-inputs/security` folder) and `OSV-Scanner (not installed): not provided` (unless you
   have OSV-Scanner installed).
5. Run task 7 again with **struts-oracle-app**, then run
   **`audit: 11. compare the last two scans of a sample app`** and pick **struts-oracle-app**.
   It prints a table with `findings 21 21 0 0 0`: the same code gives the same results.
6. Web app: run **`audit: 1. start the web app (Docker)`**, open http://localhost:8770, log in
   as **siva**, open your project and click **Full rescan**. When it is **done**, click
   **Open the results**:
   - the tiles show lines of code, components, services, screens, SQL statements, integrations,
     batch jobs and vulnerable libraries; click a number to see the rows behind it;
   - **Effort to migrate** shows the likely person-days with a low and high figure;
   - the charts show lines per language, complexity, PostgreSQL grades and findings by severity;
   - **Top findings**, **Vulnerability check** and **Inputs provided** follow.
7. Click **All facts**. Pick a kind (for example **libraries**), type part of a name in the
   search box and click **Search**. To filter on one field, pick it in **Any field** and type a
   value (start it with `~` to match part of the text).

## Phase 6: reports and PDFs

What is new: the four reports of the Python tool now come from the new version: the
**technical report**, the **functional document**, the **management deck** (slides in the
browser) and the **summary report**, plus **PDF copies** of each. They are the same as the
Python tool's, page for page. The web app gets a **Reports** page; the command line gets
`report`. There is no PowerPoint any more.

PDFs need Microsoft Edge, Google Chrome or Chromium on the computer (or in the container) that
builds them. On a Windows laptop Edge is already there. On WSL2 the tool also finds the Windows
Edge or Chrome under `/mnt/c`. Without a browser the PDFs are skipped with a message and the
HTML reports still work.

1. Get the new code: in the Source Control panel click **Sync Changes** (or on WSL2
   `git pull origin spring-boot-angular`).
2. Run **`audit: 4. backend build and tests (Java 25, Maven)`**. It ends with `BUILD SUCCESS`
   (132 tests).
3. Run **`audit: 12. compare the reports with the Python tool`**. It ends with
   `Tests run: 17, Failures: 0, Errors: 0, Skipped: 1` and `BUILD SUCCESS`. It builds the
   reports of 11 sample scans and compares every page with what the Python tool made. (The one
   skipped test prints real PDFs; it runs only on the build server.)
4. Run **`audit: 7. scan a sample app (command line)`** and pick **struts-oracle-app**, then run
   **`audit: 13. build the reports of a sample app`** and pick **struts-oracle-app**. It prints
   one line per report, for example
   `Technical report -> ...\target\sample-scans\struts-oracle-app\runs\<run>\reports\tech\index.html`,
   then `Printing the PDF copies (this takes a minute)...` and one line per PDF.
   - If it says `PDF skipped: no Microsoft Edge, Google Chrome or Chromium found`, install one
     of them (or set `pdf.browser_path` in the sample's `audit.yaml`) and run task 13 again.
   - It also says `Enrichment: 14 stage(s) missing`: the AI part of the reports comes in Phase 7;
     until then those slides and pages say **Not enriched yet**.
5. Open the reports: in the Explorer panel open
   `backend/target/sample-scans/struts-oracle-app/runs/<newest>/reports/`, right-click
   `tech/index.html` and pick **Reveal in File Explorer** (on WSL2: **Open in Integrated
   Terminal** and type `explorer.exe .`), then double-click `index.html`. Check:
   - **Technical report**: the menu on the left opens Overview, Components, Architecture, Flows,
     Data model, SQL, PostgreSQL, Integrations, Batch jobs, Libraries, Security, Findings and
     Appendix; the diagrams and charts are drawn; a table's search box filters its rows.
   - `functional/index.html`: Summary, Users and use cases, Business flows, Screens, Batch,
     Rules, Data, Interfaces, Glossary.
   - `mgmt/index.html`: the slides; use the arrow keys, **N** shows the speaker notes,
     **Theme** switches light and dark.
   - `summary/index.html`: one page with the project, database, integrations, batches and
     vulnerabilities, each with charts and **CSV** downloads; there is no PowerPoint button.
   - `pdf/`: `summary.pdf`, `management.pdf`, `functional.pdf` and `technical.pdf`. The
     technical and functional PDFs start with a contents page; click an entry to jump to it.
6. Web app: run **`audit: 1. start the web app (Docker)`**, open http://localhost:8770, log in
   as **siva** and open your project. After a finished scan click **Open the reports**:
   - click **Build all reports**. The step and a progress bar show; after a few seconds the
     **Open** links appear. Click **Open** beside **Technical report**: it opens in a new tab;
   - **Build all reports and PDFs** also makes the PDF links (the backend container has no
     browser yet, so it says `PDF skipped: ...`; the container image gets one in Phase 9);
   - **Download the data pack (ZIP)** saves the scan's facts as a ZIP file;
   - log in as another user who is a **viewer** of the project: the reports open, but there are
     no **Build** buttons.

## Phase 7: AI enrichment and Copilot

What is new: the AI part of the Python tool now comes from the new version. An AI model writes
the **enrichment** (the explanations, flows, risks, plan and documents in the reports) and the
tool checks every number and reference in it against the scan. The model is **GitHub Copilot in
VS Code** (with the new `init` command and the Copilot agent) or a model your company gives you
access to (OpenAI, Azure OpenAI, Anthropic or Ollama) with the new `enrich` command. The command
line also gets `validate`, `facts` and `mcp`, and the web app gets an **AI enrichment** page.
A new scan takes over the enrichment of the previous one and marks what has to be redone.

1. Get the new code: in the Source Control panel click **Sync Changes** (or on WSL2
   `git pull origin spring-boot-angular`).
2. Run **`audit: 4. backend build and tests (Java 25, Maven)`**. It ends with `BUILD SUCCESS`
   (177 tests).
3. Run **`audit: 14. compare the AI and Copilot parts with the Python tool`**. It ends with
   `Tests run: 36, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`. It checks that the
   validator gives the same messages as the Python tool, that the questions sent to a model are
   the same, letter for letter, that the Copilot files are the same, and that a whole enrichment
   passes with each kind of model (a small fake model on your machine; nothing is sent out).
4. Run **`audit: 7. scan a sample app (command line)`** and pick **struts-oracle-app**, then run
   **`audit: 15. check the AI enrichment of a sample app`** and pick **struts-oracle-app**. It
   prints a table of the 14 stages, all `missing` (nobody has written them yet), and ends with
   `0 checked, 0 error(s), 0 warning(s); status -> ...status.json`.
5. Optional, only if you have a model and its key. Set the key once:
   - **Windows**: in the Start menu search **Edit environment variables for your account**,
     click **New**, name `OPENAI_API_KEY` (or `AZURE_OPENAI_API_KEY`, `ANTHROPIC_API_KEY`),
     paste the key, **OK**, then close and reopen VS Code.
   - **WSL2**: run `echo 'export OPENAI_API_KEY=<your key>' >> ~/.bashrc` (with the right name),
     then close VS Code, run `wsl --shutdown` in a Windows terminal and open VS Code again.

   Run **`audit: 16. write the AI enrichment of a sample app with a model`**, pick
   **struts-oracle-app**, pick where the model runs, type the model name (for Azure the
   deployment name) and the address (empty for OpenAI and Anthropic; for Azure
   `https://<resource>.openai.azure.com`). It prints one line per stage, for example
   `Stage 1 overview: valid after 1 round(s)`, and ends with `14 stage(s) valid, 0 with errors`.
   It takes several minutes. Then run task 15 again: the stages say `valid`; and task 13: the
   reports now show the explanations instead of **Not enriched yet**.
   - `Error: Set the environment variable OPENAI_API_KEY to your API key.`: the key is not
     visible to VS Code; redo the setting above and restart VS Code.
   - `Error: Cannot reach ...`: the address is wrong or the office proxy blocks it; ask IT for
     the model address.
6. Copilot (needs GitHub Copilot in VS Code). Put the tool on the PATH once so Copilot can run it:
   - **Windows**: in **Edit environment variables for your account** select **Path**, **Edit**,
     **New**, and add the full path of `backend\audit-cli\target` in your clone; reopen VS Code.
     In a new terminal `legacy-audit version` prints the version.
   - **WSL2**: `echo 'export PATH="$HOME/code-audit/backend/audit-cli/target:$PATH"' >> ~/.bashrc`,
     open a new terminal; `legacy-audit version` prints the version.

   Then try it on a copy of a sample:
   1. Copy the folder `samples/struts-oracle-app` to a new place outside the clone, for example
      `C:\work\orders-try` (WSL2: `cp -r samples/struts-oracle-app ~/orders-try`).
   2. In a terminal in that folder run `legacy-audit init .`. It lists the files it made
      (`.github/agents/legacy-auditor.agent.md`, the prompts, `.vscode/mcp.json`). The sample's
      own `audit.yaml` is kept, so it is not in the list.
   3. **File > Open Folder** on the copy. Open `.vscode/mcp.json`: click **Start** above
      `legacy-audit` (trust it when asked); it shows **Running** and 5 tools.
   4. Open Copilot Chat, switch to **Agent** mode and pick **legacy-auditor**. Type
      `/audit-scan`; Copilot runs the scan. Then type `/audit-enrich all`; Copilot writes the
      stages one by one and runs `legacy-audit validate` after each. Then `/audit-report`
      builds the reports and PDFs.
7. Carry-over: in the copy run `legacy-audit scan` again (or task 7 on the sample after step 5).
   It prints `Enrichment carried from <previous run>`; task 15 (or `legacy-audit validate`)
   shows the stages `valid` or `stale (...)` with what changed, never `missing`.
8. Facts from the command line: in the copy run `legacy-audit facts services --limit 2`. It
   prints the first two services as JSON (`"kind": "services"`, `"total": 4`).
9. Web app: run **`audit: 1. start the web app (Docker)`**, open http://localhost:8770, log in as
   **siva** and open your project. After a finished scan click **AI enrichment**:
   - the page lists the 14 stages with their state, and under **Next step in Copilot Chat** the
     prompt to type there (`/audit-enrich all` when nothing is written yet);
   - **Validate now** checks the stages and lists errors and warnings;
   - to let the site run a model itself, set `LEGACY_AUDIT_AI_PROVIDER`, `LEGACY_AUDIT_AI_MODEL`
     (and `LEGACY_AUDIT_AI_BASE_URL` for Azure or a gateway) and the key variable in `.env`, then
     task 1 again. The page then shows **Run here with openai:&lt;model&gt;** with **Run missing
     and stale stages** and **Run all stages**, and the progress of each stage;
   - log in as a **viewer** of the project: the states show, but there are no buttons.
10. Run **`audit: 5. frontend build and tests (Node.js)`**: `Tests 12 passed`.

## Phase 8a: runs, compare, settings, input files and components to scan

What is new: the web app now does what the Python screens did before a scan and between scans.
Each project has a **Settings** page (the `audit.yaml` form, the file as text, and the tools
found on the server), an **Input files** page, a **Components to scan** page, and a **Runs and
compare** page. The results and facts of any older run can be opened, a component has its own
page, and a file in the facts opens the source lines around it. The command line gets `doctor`.

1. Get the new code: in the Source Control panel click **Sync Changes** (or on WSL2
   `git pull origin spring-boot-angular`).
2. Run **`audit: 4. backend build and tests (Java 25, Maven)`**. It ends with `BUILD SUCCESS`
   (183 tests).
3. Run **`audit: 17. compare the settings page and component picker with the Python tool`**. It
   ends with `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.
4. Run **`audit: 18. check the tools on this computer`** and pick **struts-oracle-app**. It
   prints a table: **Java** and **Output folder** say `ok`; the others say `ok` or `missing`
   (they are optional). The last line is `Ready to scan.` followed by the optional tools that
   are missing, if any.
5. Web app: run **`audit: 1. start the web app (Docker)`**, open http://localhost:8770, log in
   as **siva** and open your project. Under the project's details there are four new buttons:
   **Settings**, **Input files**, **Components to scan** and **Runs and compare**.
6. Click **Settings**:
   - the form shows the application name, the source folders, the migration target and the
     include and exclude lists; type a description and click **Save audit.yaml**. It says
     `audit.yaml saved`. Open **Advanced: edit audit.yaml as text**: your description is there
     and every other line and comment is unchanged;
   - in the text, type `[` at the end of the first line and click **Check and save**. A red
     message starting with `Not valid YAML` appears and the file is not changed (reload the page
     to see the old text);
   - **Tools on the server** lists the same checks as step 4, for the server.
7. Click **Back to the project**, then **Input files**. Each kind of input shows **missing** or
   **present**. Choose a small file under **Database schemas** (any `.sql` file): it shows
   `Added to Database schemas` and the file with its size. Click **Remove** and confirm.
8. Click **Back to the project**, then **Components to scan**. It lists the components found
   (with files, languages and the Maven group). Untick one and click **Save choices**: it says
   `Saved to audit-overrides.yaml`. Start a new scan from the project page; that component is
   not in the results. Tick it again and save to bring it back.
9. After two scans, click **Runs and compare**. The runs are listed newest first with
   **Results** and **Facts** links. Under **Compare runs** pick a **Before** and an **After**
   run: the table shows the counts per kind and the changes (after step 8 the component you
   left out is under **components: ... change(s)**).
10. Click **Results** on an older run: the page shows that run, with a **Run** picker at the top.
    Click **All facts**, pick **sql_statements**: the **component** column links to the
    component's page and the **file** column to the source lines, with the line highlighted.

