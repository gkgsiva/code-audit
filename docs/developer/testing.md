# Tests and parity checks

Run everything with VS Code task 4 (`mvn -B verify` in `backend/`) and task 5 (frontend). CI
(`.github/workflows/ci.yml`) runs the backend on Ubuntu and Windows, the frontend on Ubuntu,
and starts the three containers to check health and a login.

## Backend tests

| Module | Test | What it checks |
| --- | --- | --- |
| audit-model | `AppInfoTest` | Name and version |
| audit-engine | `RulesParityTest` | Ignore rules, languages, categories, line counts, encodings equal Python's (`parity/rules.json`) |
| audit-engine | `ParityTest` | Each of the 10 samples: `inventory.json` byte for byte, components, coverage findings and input coverage equal Python's |
| audit-engine | `AnalyzerParityTest` | Each sample file: every ported analyzer returns the same facts as Python (`parity/<sample>/analyzers.json`) |
| audit-engine | `CaseParityTest` | Each ported analyzer on the cases recorded from Python (`parity/cases/<analyzer>.json.gz`, 2,000+ for Phase 2) |
| audit-engine | `SqlToolsParityTest` | SQL helpers on 1,458 statements (`parity/sqltools.json.gz`) |
| audit-engine | `ProofsTest` | The Phase 0 parser proofs |
| audit-cli | `MainTest` | The `version` command and a clear error for a missing samples folder |
| audit-server | `AuthFlowTest`, `ProjectScanFlowTest` | Login, roles, projects, members, a real scan through the API (H2 database) |

Task 8 runs only the analyzer comparisons, which is quicker while porting an analyzer.

## How the parity data was made

The Python tool on `office-main` is the reference. Its results were saved once and are kept as
test data in `backend/audit-engine/src/test/resources/parity/` (see the README there):

- **Samples**: `legacy-audit scan --full` on every sample app; the run files and the file cache
  give the inventory, components, coverage and the facts per analyzer and file.
- **Recorded cases**: the Python test suite was run with a small pytest plugin that saved
  every analyzer call (input file, config, output facts or error). These reach far more
  branches than the samples (broken files, odd syntax, every framework the tests cover).

When a later phase ports an analyzer, its cases are already there; the test picks up every
registered analyzer by name. Regenerate the data only from `office-main`, never by hand.

## Reading a failure

A parity failure lists, per case, the file and both outputs:

```
java: 1 of 459 case(s) differ
case 17 (src/main/java/com/acme/OrderDao.java):
  python: {"entities":{"sql_statements":[...]}}
  java:   {"entities":{"sql_statements":[...]}}
```

Copy both lines into a diff tool. The usual causes are a Python behaviour the Java code did not
copy (regex flags, `strip`, sorting by code point, `None` vs `null`); use the helpers listed in
[audit-engine.md](audit-engine.md).

## Frontend tests

Vitest through `npx ng test --watch=false`. Page tests use Angular's `HttpTestingController` to
answer the API calls (see `projects.spec.ts`, `project.spec.ts`, `login.spec.ts`).
