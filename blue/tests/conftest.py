from __future__ import annotations

from pathlib import Path

import pytest
from blue.cli import load_yaml

FIXTURE_FILE = Path(__file__).parent.parent.parent / "test" / "fixtures" / "colors.yml"


def make_fixture(**overrides) -> dict:
    text = FIXTURE_FILE.read_text().replace("WORKDIR", ".colors")
    return {**load_yaml(text), "blue/state-file": str(FIXTURE_FILE), **overrides}


@pytest.fixture
def fixture():
    return make_fixture
