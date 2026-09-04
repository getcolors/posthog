from __future__ import annotations

from pathlib import Path

import pytest
from blue.cli import load_yaml

FIXTURES = Path(__file__).parent.parent.parent / "test" / "fixtures"
FIXTURE_FILE = FIXTURES / "colors.yml"
OPTOUT_FILE = FIXTURES / "optout.yml"
VULTR_FILE = FIXTURES / "colors-vultr.yml"
VULTR_OPTOUT_FILE = FIXTURES / "optout-vultr.yml"

# Every credential a real create or delete asks for, on either provider.
SECRETS = {
    "do-token": "t", "vultr-api-key": "t", "cloudflare-api-token": "t",
    "posthog-secret-key": "s", "posthog-postgres-password": "p",
    "posthog-oidc-rsa-private-key": "k", "posthog-encryption-salt-keys": "k",
    "posthog-admin-password": "p", "posthog-backup-r2-access-key-id": "k",
    "posthog-backup-r2-secret-access-key": "s",
    "r2-access-key-id": "k", "r2-secret-access-key": "s",
}


def _load(path: Path, overrides: dict) -> dict:
    text = path.read_text().replace("WORKDIR", ".colors")
    return {**load_yaml(text), "blue/state-file": str(path), **overrides}


def make_fixture(**overrides) -> dict:
    return _load(FIXTURE_FILE, overrides)


def make_optout(**overrides) -> dict:
    return _load(OPTOUT_FILE, overrides)


def make_vultr(**overrides) -> dict:
    return _load(VULTR_FILE, overrides)


def make_vultr_optout(**overrides) -> dict:
    return _load(VULTR_OPTOUT_FILE, overrides)


# The names the copied conformance suites (test_ssh, test_ssh_config) import.
def fixture(overrides: dict | None = None) -> dict:
    return make_fixture(**(overrides or {}))


def optout(overrides: dict | None = None) -> dict:
    return make_optout(**(overrides or {}))
