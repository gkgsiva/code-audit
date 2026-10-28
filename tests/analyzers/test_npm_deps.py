"""deps analyzer: package.json, npm/yarn lock files, bower.json and .nvmrc."""

from __future__ import annotations

import json

from legacy_audit.analyzers.deps import DepsAnalyzer, node_version, npm_version
from tests.analyzers.helpers import entities, run


def test_version_specs() -> None:
    assert npm_version("^17.3.0") == "17.3.0"
    assert npm_version("~1.5.8") == "1.5.8"
    assert npm_version("latest") is None
    assert node_version(">=18.17") == "18.17"
    assert node_version("v20.11.1") == "20.11.1"


def test_package_json_dependencies_and_engines() -> None:
    pkg = json.dumps(
        {
            "name": "orders-api",
            "engines": {"node": ">=18.17"},
            "dependencies": {"express": "^4.18.2"},
            "devDependencies": {"typescript": "~5.4.2"},
        },
        indent=2,
    )
    facts = run(DepsAnalyzer(), "orders-api/package.json", pkg)
    libs = {(lib.artifact, lib.version, lib.scope) for lib in entities(facts, "libraries")}
    assert ("express", "4.18.2", "dependencies") in {(a, v, s) for a, v, s in libs}
    tech = {(t.name, t.version) for t in entities(facts, "tech_stack")}
    assert ("Node.js", "18.17") in tech


def test_package_lock_v3_is_transitive_and_not_declared() -> None:
    lock = json.dumps(
        {
            "lockfileVersion": 3,
            "packages": {
                "": {"name": "x"},
                "node_modules/express": {"version": "4.18.2"},
                "node_modules/jest": {"version": "29.7.0", "dev": True},
            },
        }
    )
    facts = run(DepsAnalyzer(), "x/package-lock.json", lock)
    libs = {lib.artifact: lib for lib in entities(facts, "libraries")}
    assert libs["express"].version == "4.18.2" and not libs["express"].declared
    assert libs["jest"].scope == "devDependencies"


def test_yarn_lock_v1() -> None:
    lock = '# yarn lockfile v1\n\n"rxjs@^7.8.0":\n  version "7.8.1"\n  resolved "x"\n'
    facts = run(DepsAnalyzer(), "web/yarn.lock", lock)
    assert [(lib.artifact, lib.version) for lib in entities(facts, "libraries")] == [
        ("rxjs", "7.8.1")
    ]
