"""Conformance tests for the SSH Keypair Standard.

These cover the paths a real create can otherwise only reach by breaking a
live deployment: an existing key with no state, state with no key, half a
keypair, and a provider-side key this deployment does not own.
"""

import os
import stat
from pathlib import Path

import pytest
from conftest import fixture, make_vultr, make_vultr_optout, optout
from package_posthog_blue import ssh


@pytest.fixture
def home(tmp_path, monkeypatch):
    """Redirect `~/.ssh` into a fresh temporary home."""
    monkeypatch.setenv("HOME", str(tmp_path))
    return tmp_path


async def none_state(_opts):
    return None


def state(params):
    async def f(_opts):
        return params
    return f


def write(path: Path, content: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)


# ------------------------------------------------------------------ mode


def test_build_renders_a_stable_placeholder_path(home):
    # Goldens are committed, so a build must not name the operator's home.
    opts = ssh.with_machine_key({**fixture(), "blue/event": "build"})
    assert opts["ssh-public-key-path"].startswith(ssh.build_placeholder_dir)
    assert opts["ssh-public-key-path"] == opts["digitalocean-ssh-keys"]
    assert str(home) not in opts["ssh-private-key-path"]


def test_a_dry_run_renders_the_placeholder_too(home):
    opts = ssh.with_machine_key({**fixture(), "blue/event": "create", "blue/dry-run": True})
    assert opts["ssh-public-key-path"].startswith(ssh.build_placeholder_dir)


def test_real_events_render_the_real_path(home):
    opts = ssh.with_machine_key({**fixture(), "blue/event": "create"})
    assert opts["ssh-private-key-path"] == str(home / ".ssh" / "posthog-fixture")
    assert opts["ssh-public-key-path"] == str(home / ".ssh" / "posthog-fixture.pub")


def test_opt_out_passes_through_untouched(home):
    for event in ["build", "create", "delete"]:
        opts = ssh.with_machine_key({**optout(), "blue/event": event})
        assert opts["digitalocean-ssh-keys"] == "58495393"
        assert opts.get("ssh-public-key-path") is None, event
        assert opts.get("ssh-keygen") is None, event


# ---------------------------------------------------------- per provider


def test_the_placeholder_lands_on_the_selected_provider_key(home):
    opts = ssh.with_machine_key({**make_vultr(), "blue/event": "build"})
    assert opts["vultr-ssh-keys"] == opts["ssh-public-key-path"]
    assert opts.get("digitalocean-ssh-keys") is None
    out = ssh.with_machine_key({**make_vultr_optout(), "blue/event": "build"})
    assert out["vultr-ssh-keys"] == "00000000-0000-0000-0000-000000000000"
    assert out.get("ssh-keygen") is None


def test_the_preflight_uses_the_selected_provider_token(home):
    # do-token on DigitalOcean, vultr-api-key on Vultr — the delegation is what
    # is tested; ONCE owns the table.
    seen = []

    def fetch(provider, token):
        seen.append([provider, token])
        return []
    ssh.preflight(ssh.with_machine_key({**fixture(), "blue/event": "create",
                                        "do-token": "do-t", "vultr-api-key": "v-t"}), fetch)
    ssh.preflight(ssh.with_machine_key({**make_vultr(), "blue/event": "create",
                                        "do-token": "do-t", "vultr-api-key": "v-t"}), fetch)
    assert seen == [["digitalocean", "do-t"], ["vultr", "v-t"]]


# -------------------------------------------------------- the create matrix


async def test_first_create_generates_the_keypair(home):
    opts = await ssh.ensure_key({**fixture(), "blue/event": "create"}, none_state)
    prv = home / ".ssh" / "posthog-fixture"
    pub = home / ".ssh" / "posthog-fixture.pub"
    assert "blue/err" not in opts, opts.get("blue/err")
    assert prv.exists()
    assert pub.exists()
    # ed25519, no passphrase, profile-named comment
    assert "ssh-ed25519" in pub.read_text()
    assert "posthog-fixture managed by Colors" in pub.read_text()
    # 600 on the private key, 700 on ~/.ssh
    assert stat.S_IMODE(os.stat(prv).st_mode) == 0o600
    assert stat.S_IMODE(os.stat(home / ".ssh").st_mode) == 0o700


async def test_converge_reuses_an_existing_key(home):
    write(home / ".ssh" / "posthog-fixture", "private")
    write(home / ".ssh" / "posthog-fixture.pub", "ssh-ed25519 AAAA test")
    opts = await ssh.ensure_key({**fixture(), "blue/event": "create"},
                                state({"ip": "192.0.2.10"}))
    assert "blue/err" not in opts
    assert (home / ".ssh" / "posthog-fixture").read_text() == "private", \
        "an existing key is reused, never regenerated"


async def test_state_without_a_key_is_an_error(home):
    opts = await ssh.ensure_key({**fixture(), "blue/event": "create"},
                                state({"ip": "192.0.2.10"}))
    assert opts["blue/exit"] == 1
    assert "does not hold the machine key" in opts["blue/err"]
    assert "rebuild" in opts["blue/err"]


async def test_a_key_without_state_is_never_overwritten(home):
    prv = home / ".ssh" / "posthog-fixture"
    write(prv, "irreplaceable")
    write(home / ".ssh" / "posthog-fixture.pub", "ssh-ed25519 AAAA test")
    opts = await ssh.ensure_key({**fixture(), "blue/event": "create"}, none_state)
    assert opts["blue/exit"] == 1
    assert "no compute state is readable" in opts["blue/err"]
    # The message must make the human the authorization boundary.
    assert "survives" in opts["blue/err"]
    assert prv.read_text() == "irreplaceable", "the key on disk is left alone"


async def test_half_a_keypair_is_an_error(home):
    write(home / ".ssh" / "posthog-fixture", "private")
    opts = await ssh.ensure_key({**fixture(), "blue/event": "create"}, none_state)
    assert opts["blue/exit"] == 1
    assert "half a keypair" in opts["blue/err"]


async def test_opt_out_generates_nothing(home):
    opts = await ssh.ensure_key({**optout(), "blue/event": "create"}, none_state)
    assert "blue/err" not in opts
    assert not list(home.iterdir()), "opt-out mode must not touch ~/.ssh"


# ------------------------------------------------------------- preflight


def preflight(opts, account_keys):
    return ssh.preflight(opts, lambda _provider, _token: account_keys)


def test_preflight_passes_when_no_account_key_matches(home):
    opts = preflight(ssh.with_machine_key({**fixture(), "blue/event": "create"}),
                     [{"id": "1", "name": "someone-else", "public": "ssh-ed25519 BBBB"}])
    assert "blue/err" not in opts


def test_preflight_passes_when_the_account_key_is_ours(home):
    opts = preflight(
        ssh.with_machine_key({**fixture(), "blue/event": "create",
                              "once/ssh-state-params": {"ssh_key_id": "abc"}}),
        [{"id": "abc", "name": "posthog-fixture", "public": "ssh-ed25519 AAAA"}])
    assert "blue/err" not in opts


def test_preflight_refuses_our_leftover_key(home):
    write(home / ".ssh" / "posthog-fixture.pub", "ssh-ed25519 AAAA comment")
    opts = preflight(ssh.with_machine_key({**fixture(), "blue/event": "create"}),
                     [{"id": "abc", "name": "posthog-fixture", "public": "ssh-ed25519 AAAA"}])
    assert opts["blue/exit"] == 1
    assert "previous delete" in opts["blue/err"]
    assert "delete that key" in opts["blue/err"]


def test_preflight_refuses_a_foreign_key_and_says_do_not_delete_it(home):
    write(home / ".ssh" / "posthog-fixture.pub", "ssh-ed25519 OURS comment")
    opts = preflight(ssh.with_machine_key({**fixture(), "blue/event": "create"}),
                     [{"id": "abc", "name": "posthog-fixture", "public": "ssh-ed25519 THEIRS"}])
    assert opts["blue/exit"] == 1
    assert "Do not delete it" in opts["blue/err"]


def test_preflight_failure_is_an_error_not_a_skip(home):
    def boom(_provider, _token):
        raise RuntimeError("HTTP 500")
    opts = ssh.preflight(ssh.with_machine_key({**fixture(), "blue/event": "create"}), boom)
    assert opts["blue/exit"] == 1
    assert "cannot list" in opts["blue/err"]


# --------------------------------------------------------------- cleanup


def test_delete_removes_the_keypair(home):
    write(home / ".ssh" / "posthog-fixture", "private")
    write(home / ".ssh" / "posthog-fixture.pub", "public")
    ssh.cleanup_step({**fixture(), "blue/event": "delete", "ssh-keygen": True})
    assert not (home / ".ssh" / "posthog-fixture").exists()
    assert not (home / ".ssh" / "posthog-fixture.pub").exists()
    assert (home / ".ssh").exists(), "~/.ssh itself is the operator's, never removed"


def test_cleanup_is_inert_on_create_and_in_opt_out_mode(home):
    write(home / ".ssh" / "posthog-fixture", "private")
    ssh.cleanup_step({**fixture(), "blue/event": "create", "ssh-keygen": True})
    assert (home / ".ssh" / "posthog-fixture").exists()
    ssh.cleanup_step({**optout(), "blue/event": "delete"})
    assert (home / ".ssh" / "posthog-fixture").exists()
