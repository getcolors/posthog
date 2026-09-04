from __future__ import annotations

from pathlib import Path

import pytest
from blue.cli import load_yaml

FIXTURES = Path(__file__).parent.parent.parent / "test" / "fixtures"
FIXTURE_FILE = FIXTURES / "colors.yml"
OPTOUT_FILE = FIXTURES / "optout.yml"


def _load(path: Path, overrides: dict) -> dict:
    text = path.read_text().replace("WORKDIR", ".colors")
    return {**load_yaml(text), "blue/state-file": str(path), **overrides}


def make_fixture(**overrides) -> dict:
    return _load(FIXTURE_FILE, overrides)


def make_optout(**overrides) -> dict:
    return _load(OPTOUT_FILE, overrides)


# The names the copied conformance suites (test_ssh, test_ssh_config) import.
def fixture(overrides: dict | None = None) -> dict:
    return make_fixture(**(overrides or {}))


def optout(overrides: dict | None = None) -> dict:
    return make_optout(**(overrides or {}))
