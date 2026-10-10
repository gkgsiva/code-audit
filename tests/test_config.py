from pathlib import Path

import pytest
import yaml

from legacy_audit.config import (
    DEFAULT_COUNTING_RULES,
    ConfigError,
    GitSource,
    LocalSource,
    load_config,
    parse_config,
)
from tests.conftest import REPO, write


def test_minimal_config_gets_defaults():
    cfg = parse_config({"application": {"name": "x"}})
    assert isinstance(cfg.source, LocalSource)
    assert cfg.source.paths == ["."]
    assert cfg.target.java == "25"
    assert cfg.target.spring_boot == "4.1"
    assert cfg.target.angular == "21"
    assert cfg.output_dir == "audit-output"
    assert cfg.counting == DEFAULT_COUNTING_RULES


def test_unknown_key_is_a_clear_error():
    with pytest.raises(ConfigError, match=r"unknown key 'target\.jaav'"):
        parse_config({"application": {"name": "x"}, "target": {"jaav": "25"}})
    with pytest.raises(ConfigError, match="unknown key 'colour'"):
        parse_config({"application": {"name": "x"}, "colour": "red"})


def test_missing_application_name():
    with pytest.raises(ConfigError, match="missing required key 'application'"):
        parse_config({})


def test_partial_counting_keeps_other_defaults():
    cfg = parse_config({"application": {"name": "x"}, "counting": {"screen": "custom"}})
    assert cfg.counting["screen"] == "custom"
    assert cfg.counting["service"] == DEFAULT_COUNTING_RULES["service"]


def test_git_source():
    cfg = parse_config(
        {
            "application": {"name": "x"},
            "source": {"type": "git", "repos": [{"url": "https://h/o/r.git", "token_env": "T"}]},
        }
    )
    assert isinstance(cfg.source, GitSource)
    assert cfg.source.repos[0].token_env == "T"


def test_invalid_component_type():
    with pytest.raises(ConfigError, match=r"components\.0\.type"):
        parse_config(
            {
                "application": {"name": "x"},
                "components": [{"name": "a", "path": "a", "type": "gui"}],
            }
        )


def test_windows_style_paths_resolve(tmp_path: Path):
    (tmp_path / "code" / "web").mkdir(parents=True)
    write(
        tmp_path / "audit.yaml",
        yaml.safe_dump(
            {
                "application": {"name": "x"},
                "source": {"paths": ["code\\web"]},
                "output_dir": "out\\audit",
                "components": [{"name": "w", "path": "web\\src\\"}],
            }
        ),
    )
    loaded = load_config(tmp_path / "audit.yaml")
    assert loaded.config.source.paths == ["code/web"]  # type: ignore[union-attr]
    assert loaded.resolve(loaded.config.source.paths[0]) == (tmp_path / "code" / "web").resolve()  # type: ignore[union-attr]
    assert loaded.output_dir == (tmp_path / "out" / "audit").resolve()
    assert loaded.config.components[0].path == "web/src"


def test_windows_drive_path_kept():
    cfg = parse_config({"application": {"name": "x"}, "source": {"paths": ["C:\\legacy\\app"]}})
    assert cfg.source.paths == ["C:\\legacy\\app"]  # type: ignore[union-attr]


def test_invalid_yaml(tmp_path: Path):
    write(tmp_path / "audit.yaml", "application: [unclosed")
    with pytest.raises(ConfigError, match="not valid YAML"):
        load_config(tmp_path / "audit.yaml")


def test_example_config_is_valid_and_matches_template():
    loaded = load_config(REPO / "audit.example.yaml")
    assert loaded.config.application.name == "my-application"
    template = (REPO / "legacy_audit" / "templates" / "audit.yaml").read_text(encoding="utf-8")
    assert template.format(name='"my-application"') == (REPO / "audit.example.yaml").read_text(
        encoding="utf-8"
    )


def test_config_hash_is_stable(tmp_path: Path):
    write(tmp_path / "a.yaml", "application: {name: x}\n")
    write(tmp_path / "b.yaml", "# comment\napplication:\n  name: x\n")
    assert load_config(tmp_path / "a.yaml").hash == load_config(tmp_path / "b.yaml").hash
