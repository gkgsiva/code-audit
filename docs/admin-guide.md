# Admin guide

For whoever installs legacy-audit for a team, keeps the vulnerability data current, sets the
branding and maintains the rule packs. Analysts start with [user-guide.md](user-guide.md).

## Install

Only Python 3.11+ is required. Everything else is optional and `legacy-audit doctor` says what
each missing tool costs.

| Tool | Needed for | Without it |
| --- | --- | --- |
| Python 3.11+ | everything | — |
| Node.js 18+ and `npm install` in the PPTX builder | `deck.pptx` | the PPTX is skipped with a message; the HTML deck still works |
| Mermaid CLI (`mmdc`) | diagram pictures inside the PPTX | the PPTX slide carries the Mermaid text in its notes |
| OWASP Dependency-Check or OSV-Scanner | vulnerability data during the scan | import their JSON reports into `audit-inputs/security/` instead |
| JDK 17+ (`jdeps`) | Java 25 internal-API checks on jars (`security.jdk_tools.enabled`) | the check is skipped and listed as a coverage gap |
| Git | Git sources (`source.type: git`) | use local folders |

### With pipx (recommended on laptops)

```
pipx install legacy_audit-<version>-py3-none-any.whl
legacy-audit doctor
```

`pipx` keeps the tool in its own environment and puts `legacy-audit` on PATH. Build the wheel from
a checkout with `python -m pip wheel . --no-deps -w dist` (or `python -m build`).

### With a virtual environment

```
python -m venv .venv
.venv\Scripts\pip install legacy_audit-<version>-py3-none-any.whl      (Windows)
.venv/bin/pip install legacy_audit-<version>-py3-none-any.whl          (Linux/macOS)
```

### Offline install (no access to PyPI or npm)

On a machine with access, collect everything once:

```
pip wheel legacy_audit-<version>-py3-none-any.whl -w wheelhouse
cd <site-packages>/legacy_audit/_pptx  (or pptx/ in a checkout)  &&  npm ci  &&  cd ..
```

Copy `wheelhouse/` (and, for the PPTX, the `_pptx/node_modules` folder) to the offline machine:

```
pip install --no-index --find-links wheelhouse legacy-audit
```

Wheels with compiled parts (`tree-sitter`, `pydantic-core`) are per platform and Python version:
build the wheelhouse on the same OS and Python minor version as the laptops (Windows x64,
Python 3.11 or 3.13). For the PPTX builder, either copy `node_modules` next to `build-pptx.js`
or point `LEGACY_AUDIT_PPTX_DIR` at a folder that has it. `LEGACY_AUDIT_NODE` and
`LEGACY_AUDIT_MMDC` override where Node and Mermaid CLI are found.

## Vulnerability database

The scan never downloads anything. Vulnerability data comes from tools you run or reports you
import (decision 8).

- **Dependency-Check**: install it, then point the config at it and at a local NVD copy:

  ```yaml
  security:
    dependency_check:
      path: C:/tools/dependency-check/bin/dependency-check.bat   # or on PATH
      data_dir: D:/nvd                  # local database (--data)
      nvd_mirror_url: https://nvd-mirror.example.internal/      # team mirror, for syncing
      offline: true                     # never update during a scan (default)
  ```

  Sync the database explicitly, outside a scan, for example weekly:
  `dependency-check --updateonly --data D:/nvd` (add `--nvdDatafeed <mirror>` for a mirror).
  Each run records the database date in `run.json` (`vulnerability_sources`) and the reports print
  it, so stale data is visible.
- **OSV-Scanner** (version 2.x): `security.osv_scanner.path` and, optionally, `data_dir` with
  `offline: true`. The scan runs `osv-scanner --offline --experimental-plugins java/archive`
  (jar files as well as build files); `data_dir` is passed as `OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY`.
  Refresh the local database on a schedule with the same variable set:
  `osv-scanner scan source --offline-vulnerabilities --download-offline-databases
  --experimental-plugins java/archive -r <code folder>`.
- **Imported reports**: put Dependency-Check `JSON` or OSV-Scanner JSON output in
  `audit-inputs/security/`; the run imports them and says so in the coverage list.

## Branding

```yaml
branding:
  theme: neutral
  logo: branding/logo.png          # relative to audit.yaml; shown on the decks
  primary_color: "#1f4e79"
  accent_color: "#2e75b6"
  font: "Segoe UI, Arial, sans-serif"
  pptx_template: null              # reserved: pptxgenjs cannot load a corporate master yet
```

Share one `branding:` block across teams by copying it into each `audit.yaml`. Colours and font
are checked before they reach the HTML (no CSS can be injected through them).

## Rule packs

See [rules.md](rules.md). Keep team packs in a shared folder and set `rules.packs_dir` so tool
upgrades never overwrite them. Review `rules/eol.yaml` and `rules/dependency-upgrades.yaml` at
least every quarter: end-of-life dates and upgrade targets change (`verify: true` marks targets to
confirm).

## Performance and large estates

- `inventory.workers`: processes used to analyse files and grade SQL. `0` (default) uses one per
  CPU less one, at most 8. `1` runs in a single process (useful on a shared build server).
- `inventory.max_file_size_mb` (default 20): larger files are hashed and counted but not read;
  each becomes a `file-too-large` finding.
- Re-runs reuse `audit-output/cache/` (per-file results and SQL grades). `legacy-audit
  purge-cache -c audit.yaml` clears it; the next run is a full scan.
- Measured numbers are in [performance.md](performance.md).

## The GUI on a shared machine

`legacy-audit serve` listens on `127.0.0.1` only and refuses requests from other host names and
cross-site pages. It is meant for one analyst on their own laptop; to share results, share the
reports folder or the data pack (ZIP), not the GUI.

## Logs and secrets

Each run writes `audit-output/runs/<id>/logs/scan.log`. Git tokens are read from the environment
variable named in `token_env` and never written to config, outputs or logs; credentials found in
scanned files are masked (`****`) in every output, the data pack and the GUI's source viewer.
