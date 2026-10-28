# Contributing to legacy-audit

Thank you for working on the tool. This page is the short version; the developer guide in
[`docs/developer/`](docs/developer/README.md) has the details, and
[`.github/copilot-instructions.md`](.github/copilot-instructions.md) has the rules every change
follows (GitHub Copilot reads that file on every request; people should read it once too).

## Set up

You need Python 3.11 or newer and Git. Node.js 18+ is optional (diagram images in the
PowerPoint).

Windows (PowerShell), in the repository folder:

```
python -m venv .venv
.venv\Scripts\activate
pip install -e ".[dev]"
pytest
```

WSL2 or Linux:

```
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
pytest
```

In VS Code: open the folder, accept the recommended extensions, then **Terminal > Run Task...**
`audit: 1. set up (venv + install)`, `audit: 2. tests (pytest)` and
`audit: 3. lint + type check`. Pick `.venv` with **Python: Select Interpreter**.

## Make a change

1. Read the related part of [`docs/developer/`](docs/developer/README.md) and search
   [`docs/decisions.md`](docs/decisions.md) for decisions about the area.
2. Create a branch from the development branch.
3. Change the code with tests. Follow the recipe in
   [`docs/developer/extending.md`](docs/developer/extending.md) if one fits.
4. Run the checks:

   ```
   pytest
   ruff check .
   ruff format --check .
   mypy legacy_audit
   ```

   and regenerate what is generated when it applies:

   ```
   python -m legacy_audit.model.schemas      # a model in model/ or enrich/models.py changed
   python scripts/gen_dev_reference.py       # modules, CLI, config, entities, GUI routes, analyzers, stages or tasks changed
   ```
5. Update the docs in the same change: `docs/decisions.md`, `docs/progress.md`,
   `CHANGELOG.md`, and for a user-visible feature a VS Code task, a section in
   `docs/validate-in-vscode.md` and the user guides.
6. Open a pull request; the template lists the checks. Pull requests are squash-merged.

## Commit messages

Plain descriptions of the change: a short first line (what), then why if it is not obvious.
Mention a decision number when there is one ("Group MQ queues by queue manager (decision
177)"). No AI-tool attribution lines or links.

## Where to ask

Open an issue in the repository with what you tried, the command, and the run log
(`audit-output/runs/<run-id>/logs/scan.log`) with any secret removed.
