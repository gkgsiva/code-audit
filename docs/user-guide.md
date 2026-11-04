# User guide: auditing an application end to end

This guide walks through one audit on the web app, step by step. Each step also shows the command
that does the same thing on a laptop (`legacy-audit ...`), for scripts and for the Copilot agent.

The web app runs on a server (or on your laptop with Docker): see [admin-guide.md](admin-guide.md)
for how to install it. You need only a browser.

**Finding your way around.** The menu on the left has **Projects**, **Status** and **Logs** (and
**Admin** and **Dashboard** for admins). When you open a project, its pages are listed under
**This project**: Overview, Results, Reports, AI enrichment, Inventory, Runs and compare,
Settings, Input files and Components to scan. The bar at the top shows which project you are in
and who is logged in. On a narrow window or a phone, the menu opens with the button at the top
left. The site follows your computer's light or dark setting.

## 1. Log in and create a project

1. Open the site's address (for example http://localhost:8770) and type your name. The admin may
   have added you already; otherwise the site may refuse the name and say so.
2. On **Projects**, click **New project**.
3. Type the application name and, if you like, a short description.
4. Under **Where the code is**, pick one:
   - **A folder on the server**: click through the folders until the line "Selected:" shows the
     folder that holds the code;
   - **A Git repository**: paste its address (https://, ssh:// or git@), the branch or tag
     (empty: the default branch), the folders to scan (empty: all) and the *name* of the server
     setting that holds a read-only token (for example `GIT_TOKEN`). The token itself is never
     typed or stored.
5. Click **Create project**. The project page opens.

Command line: `legacy-audit init` writes `audit.yaml`, `audit-overrides.yaml`, the
`audit-inputs/` folders and the Copilot files in the current folder.

## 2. Settings

**Settings** (in the menu on the left) shows `audit.yaml` as a form: the application, where the code is
(folders, or Git repositories as `address | branch | folders | token variable` lines), the
migration target and the files to include and leave out. **Save audit.yaml** changes only those
keys and keeps every other line and comment. **Advanced: edit audit.yaml as text** edits the whole
file; it is checked before it is saved, and a wrong file is not saved.

**Tools on the server** lists which tools the server has (Java, the browser for the PDF copies,
OSV-Scanner, Dependency-Check, jdeps, Git, the AI model) and what a missing one costs.

Command line: `legacy-audit doctor`.

## 3. Input files

Exports that usually arrive after the code: Autosys JIL, MQSC, CFT, database schemas (DDL),
crontab, server configuration and vulnerability reports. On **Input files**, choose a file under
the right kind; **Remove** takes it away. A missing input is shown as a gap in every report, never
as an error. Scan again after adding one.

On a laptop, copy the files into `audit-inputs/<kind>/` next to `audit.yaml`.

## 4. Components to scan

**Components to scan** lists the components a scan will find, from file names only (fast): one
per build file (`pom.xml`, `build.xml`, `Makefile`, Eclipse `.project` ...), and without build
files one per application folder. Untick a component to leave it out, or pick a type to correct
the detection, then **Save choices**. The choices go to `audit-overrides.yaml` and are used on
every scan.

## 5. Scan

- **Start scan** reads only the files that changed since the last scan; the rest comes from the
  cache. Linking, metrics, rules and totals are always worked out again.
- **Full rescan** reads every file again.
- For a Git project, **Branch or tag** scans another branch once; the project keeps its own.
- The page shows the step (inventory, analysis, linking, rules and grades, writing the results),
  a progress bar and the last log lines. **Cancel scan** stops it; the unfinished run is removed.
- Only one scan per project runs at a time. When the site is busy the scan waits its turn:
  **Status** (menu on the left) shows what is running and waiting.

Command line: `legacy-audit scan` and `legacy-audit scan --full`.

## 6. Results and facts

**Results** shows the headline numbers of a run (lines of code, components, services, screens,
SQL statements, integrations, batch jobs, vulnerable libraries), which inputs were there, the
effort estimate and charts by language, complexity, PostgreSQL grade and finding severity. Each
number links to its facts. With more than one run, a **Run** picker shows an older one.

**All facts** shows every kind of fact (components, services, screens, SQL statements, batch
jobs, integrations, libraries, findings, files ...) as a table with search and filters. A
component name opens the component's page (its services, screens, SQL, batch jobs and
integrations); a file opens the source lines around the evidence, with passwords masked.

Command line: `legacy-audit facts <kind> -f field=value`.

## 7. AI enrichment

The explanations (architecture, flows, risks, plan, functional stories) are written by an AI
model: by default GitHub Copilot in VS Code with the **legacy-auditor** agent, or the model set by
the admin or in the `ai:` section of `audit.yaml` (OpenAI, Azure OpenAI, Anthropic, Ollama).

The **AI enrichment** page shows each stage as missing, valid, stale (the scan changed since it
was written) or invalid, and:

- with Copilot: the prompt to paste into Copilot Chat (**Copy**), then **Validate now** once
  Copilot has written the files (see [copilot-agent.md](copilot-agent.md));
- with another model: **Run here** runs the missing and stale stages (or all of them) on the
  server and checks each one.

Command line: `legacy-audit validate`, `legacy-audit enrich` (`--check` tests the model settings).

## 8. Reports

On **Reports**, **Build all reports** builds, for the newest run:

- **Technical report**, for architects and developers: overview with readiness gauges, one page
  per component, catalogues, the flows of every screen and batch, and the data model (who creates,
  reads, updates and deletes each table);
- **Functional document**, for business teams, in plain language: capabilities, users, a flow
  for every screen and batch, business rules, data, interfaces and a glossary. The pictures are
  there after a scan; the explanations appear once the enrichment is written;
- **Management deck**, an HTML slide show;
- **Summary report**, one page with the main numbers.

Each opens in a new tab. **Build all reports and PDFs** also makes PDF copies. **Download the
data pack (ZIP)** gives `run.json`, every fact file (JSON, CSV, SQLite) and the enrichment, for
Excel or another tool. The admin may switch some reports off for the site, and a project owner
may hide some.

Command line: `legacy-audit report --type tech|functional|mgmt|summary|pdf|all --open`.

## 9. Runs and compare

**Runs and compare** lists every run. Pick a **Before** and an **After** run: the table shows the
counts per kind and which records were added (+), removed (−) or changed (~). Typical use: after
adding the Autosys export, it shows the new batch jobs.

Command line: `legacy-audit diff previous latest`.

## 10. Sharing a project

The project owner (or an admin) adds colleagues under **Who has access**: a **contributor** can
scan, add input files and build reports; a **viewer** can only look. **Project settings** changes
the name, description and owner, archives the project, and hides reports or summary sections when
the admin allows it.

## A typical late-input cycle

1. **Input files**: add `billing.jil` under Autosys JIL.
2. **Start scan**: only the new file is read.
3. **Results**: batch jobs go up; the Autosys input shows as present.
4. **AI enrichment**: flows, risks, plan and stories are now **stale**. Paste
   `/audit-enrich stale` into Copilot Chat (or **Run here**), then **Validate now**.
5. **Reports**: **Build all reports**.
6. **Runs and compare**: the previous run against the latest shows what the new input added.
