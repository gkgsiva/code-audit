# Rules: how to add, change or switch them off

The checks behind findings, PostgreSQL grades, end-of-life and upgrade advice are data, not code.
They live in YAML rule packs in `rules/` (inside an installed tool: `legacy_audit/_rules/`):

| Pack (name in `rules.enabled`) | File | What it holds |
| --- | --- | --- |
| `risk` | `risk-rules.yaml` | Findings: hard-coded config, credentials, platform calls, Java 25 issues, SQL smells, code quality |
| `postgresql` | `pg-rules.yaml` | Vendor SQL constructs and the grade each one gives (`auto`, `review`, `manual`) |
| `dependency` | `dependency-upgrades.yaml` | Upgrade, replace and Jakarta targets per library, and the dependency findings |
| (data) | `eol.yaml` | End-of-life dates of languages, frameworks and libraries |
| (data) | `target-mapping.yaml` | Default target per legacy element (used by the reports and the agent) |

Every report prints the counting rules, and every finding names its `rule_id`, so a number can
always be traced back to the rule that produced it.

## Switch a rule off or change its severity

In `audit.yaml` (no re-scan of files is needed; findings are rebuilt on every run):

```yaml
rules:
  disabled: [system-exit]           # rule ids
  severity:
    hardcoded-ip: low               # info | low | medium | high | critical
```

## Add or replace rules for your team

Keep team rules outside the tool, so upgrading the tool never loses them:

```yaml
rules:
  packs_dir: ../team-rules          # relative to audit.yaml
```

Put a file with the **same file name** as the pack in that folder (`team-rules/risk-rules.yaml`).
A team rule with the same `id` as one of ours replaces it; a new `id` is added. Data tables
(`data:` in a pack) are merged: team entries come first, so they win.

## Rule syntax

A rule matches **facts** (evaluated every run over the linked facts) or **text** (a regex run
over each file once and cached with the file):

```yaml
pack: risk
rules:
  - id: no-ftp-in-batch                  # unique; shown in every finding
    severity: high                        # info | low | medium | high | critical
    category: integration                 # groups findings in the reports
    message: "Plain FTP from a batch job: {target}"   # {field} = a field of the matched fact
    remediation: Move the transfer to Cloud Storage or SFTP.
    match:
      entity: integrations                # any fact kind: services, sql_statements, libraries, ...
      where:
        type: FTP                         # plain value = equal
        usages: {gte: 2}                  # eq ne in not_in regex gt gte lt lte exists empty contains

  - id: thread-sleep
    severity: low
    category: quality
    message: "Thread.sleep in application code: {match}"   # {match} = the matched text
    pattern:
      regex: '\bThread\.sleep\s*\('
      languages: [java, jsp]              # empty = every language
      ignore: null                        # regex of matched text to skip
      unless_file: null                   # regex: skip files that contain it
      max_per_file: 20
```

Text rules mask secrets: name a group `secret` (or `secret2`) and its text is replaced by `****`
in findings, reports and logs.

Fact fields are listed in `schemas/facts/*.schema.json` (or `legacy-audit facts <kind> --limit 1`).
Besides the fact kinds, `match.entity` can be `units` (functions and methods with their
complexity), `dead_code` and `binaries`.

## PostgreSQL constructs

`pg-rules.yaml` lists constructs found in SQL text with their grade and reason:

```yaml
data:
  constructs:
    - {id: nvl, regex: '\bNVL\s*\(', grade: review, reason: "NVL → COALESCE"}
```

A team pack can add a construct or change a grade by giving the same `id`. Changing constructs
re-grades every statement on the next run (cached grades are keyed by the rule set).

## Check a change

1. `legacy-audit scan -c audit.yaml`. A pack that is not valid YAML, or a rule with an unknown
   key, a bad severity or a regex that does not compile, stops the scan with an error.
2. `legacy-audit facts findings -f rule_id=<your id>` lists what the rule found.
3. Add a small fixture and a test under `tests/` when the rule ships with the tool
   (`tests/test_rules.py` has examples).
