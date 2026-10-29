# Development history

One line per pull request, in merge order, so the development activity is easy to follow.
Up to PR #33 the work was done in an earlier repository and came into `office-main` as one
commit, "Initial import of legacy-audit". The branch `office-history` holds the same code with
one commit per line of this list, for anyone who wants the history in `git log`. From PR #34
on, each PR is one squash commit whose title is its line here. Feature details are in
`CHANGELOG.md`, design choices in `docs/decisions.md`.

Rule for every new PR: the squash commit title is a single plain line describing the activity,
ending with the PR number, and the same line is added at the bottom of this list.

| PR | Merged | Activity |
|---:|---|---|
| — | 2026-10-10 | Add the plan, decisions, rules and phase prompts |
| #1 | 2026-10-10 | Phase 1: foundations (inventory, runs, cache, diff, CLI) |
| #2 | 2026-10-10 | Phase 2: core analyzers and linker |
| #3 | 2026-10-10 | Phase 3: rules, metrics and dependency security |
| #4 | 2026-10-10 | Phase 4: technical HTML report |
| #5 | 2026-10-10 | Phase 5: Copilot agent, validated enrichment, management deck and PPTX |
| #6 | 2026-10-10 | Phase 6: local GUI, doctor, parallel scans and hardening |
| #7 | 2026-10-10 | Add a step-by-step HTML usage guide for non-technical users |
| #8 | 2026-10-10 | Usage guide: WSL2 steps in every section; set-up task uses python3 on Linux/WSL2 |
| #9 | 2026-10-10 | Usage guide: install Dependency-Check and OSV-Scanner; fix the OSV-Scanner 2.x call |
| #10 | 2026-10-10 | Phase 7: functional document, screen and batch flows, more graphics |
| #11 | 2026-10-12 | List batches without scheduler inputs and read what their code says |
| #12 | 2026-10-12 | Make the AI enrichment model agnostic |
| — | 2026-10-12 | Add the corporate presentation templates |
| #13 | 2026-10-12 | Build the PowerPoint strictly on the corporate templates |
| #14 | 2026-10-12 | Classify components by application layout, skip build output, count batches per component |
| #15 | 2026-10-20 | Name every file's language, consolidate integrations and batches, add a summary report |
| #16 | 2026-10-21 | Summary report: use the Copilot analysis in the Database section, mark AI batch text |
| #17 | 2026-10-21 | Add a Dependency vulnerabilities section to the summary report |
| #18 | 2026-10-21 | Make OSV-Scanner online mode the default and the quick vulnerability setup |
| #19 | 2026-10-21 | Explain common scanner failures in plain words |
| #20 | 2026-10-21 | Angular, AngularJS, Node.js/TypeScript and application server support |
| #22 | 2026-10-21 | VB6, VB.NET, C# and Java Swing (desktop and .NET) support |
| #21 | 2026-10-21 | Web services: REST and SOAP, offered and called |
| #23 | 2026-10-22 | AI enrichment for desktop, .NET, Angular, Node.js, servers and web services |
| #24 | 2026-10-22 | Count lines the same way in the scan, the validator and VS Code |
| #25 | 2026-10-22 | Explain the batch count to Copilot when there is no scheduler input |
| #26 | 2026-10-23 | Choose the migration target: Spring Boot, WildFly on GKE, or audit only |
| #27 | 2026-10-23 | Components from Maven modules; one batch per entry point |
| #28 | 2026-10-23 | PDF copies of the reports |
| #29 | 2026-10-23 | Copilot agent and /audit prompts load in current VS Code |
| #30 | 2026-10-23 | PDF: print wide tables whole |
| #31 | 2026-10-23 | PDF: clickable contents page |
| #32 | 2026-10-26 | Group components per deployable / technology and integrations per external service |
| #33 | 2026-10-28 | Keep scans running when a step fails; fix duplicate SOAP web service ids |
| #34 | 2026-10-29 | Hosted site: Docker image, login, projects under one audits folder, admin, status and logs |
| #35 | 2026-10-29 | Docker build behind a company proxy, data in host folders, admin dashboard, Git branches and clone cleanup |
