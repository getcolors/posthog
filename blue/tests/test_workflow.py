import os

from conftest import make_fixture, make_optout
from package_once_blue import ssh as once_ssh
from package_posthog_blue import ssh, ssh_config, workflow


async def test_build_and_dry_run_never_touch_ssh(monkeypatch):
    # The standard forbids reading, creating, or requiring anything under
    # ~/.ssh on a build or dry-run: they render from desired state alone.
    def forbidden(*_args, **_kwargs):
        raise RuntimeError("touched ~/.ssh")
    monkeypatch.setattr(ssh_config, "adopt_error", forbidden)
    monkeypatch.setattr(ssh_config, "placement_error", forbidden)
    monkeypatch.setattr(once_ssh, "ensure_key", forbidden)
    for opts in [make_fixture(**{"blue/event": "build"}),
                 make_fixture(**{"blue/event": "create", "blue/dry-run": True}),
                 make_fixture(**{"blue/event": "delete", "blue/dry-run": True})]:
        r = await workflow.start_step(opts, env={})
        assert r["blue/exit"] == 0
        assert str(r["ssh-public-key-path"]).startswith("/home/build-placeholder"), \
            "a build must not name the operator's home directory"
        assert r["digitalocean-ssh-keys"] == r["ssh-public-key-path"]


async def test_opt_out_renders_the_historical_shape_on_every_rendered_event():
    for opts in [make_optout(**{"blue/event": "build"}),
                 make_optout(**{"blue/event": "create", "blue/dry-run": True})]:
        r = await workflow.start_step(opts, env={})
        assert r["blue/exit"] == 0
        assert r["digitalocean-ssh-keys"] == "58495393"
        assert r.get("ssh-keygen") is None


async def test_build_and_dry_run_need_no_credentials():
    r = await workflow.start_step(make_fixture(**{"blue/event": "build"}), env={})
    assert r["blue/exit"] == 0
    r = await workflow.start_step(
        make_fixture(**{"blue/event": "create", "blue/dry-run": True}), env={})
    assert r["blue/exit"] == 0


async def test_real_create_requires_credentials():
    r = await workflow.start_step(make_fixture(**{"blue/event": "create"}), env={})
    assert r["blue/exit"] == 2
    assert "COLORS_PAR_DO_TOKEN" in r["blue/err"]
    assert "COLORS_PAR_POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY" in r["blue/err"]


async def test_delete_is_protected():
    r = await workflow.start_step(make_fixture(**{"blue/event": "delete"}), env={})
    assert r["blue/exit"] == 2
    assert "COMPUTE_PREVENT_DESTROY" in r["blue/err"]


def deletable_fixture(**overrides) -> dict:
    """A fixture that passes real-delete preflight: guard lifted, secrets present."""
    return make_fixture(**{"compute-prevent-destroy": False,
                           "do-token": "t", "cloudflare-api-token": "t",
                           "posthog-secret-key": "s", "posthog-postgres-password": "p",
                           "posthog-oidc-rsa-private-key": "k",
                           "posthog-encryption-salt-keys": "k",
                           "posthog-admin-password": "p",
                           "posthog-backup-r2-access-key-id": "k",
                           "posthog-backup-r2-secret-access-key": "s",
                           "r2-access-key-id": "k", "r2-secret-access-key": "s",
                           **overrides})


async def test_delete_fails_loudly_when_state_is_unreadable(monkeypatch):
    # Swallowing a failed state read is how a live teardown ended up pointing
    # the cleanup playbook at 192.0.2.10: stale backend credentials made
    # `tofu output` fail, nil was merged, and the inventory fell back to
    # TEST-NET. The failure must surface here, before any playbook runs.
    async def unauthorized(_opts):
        raise Exception("Unauthorized")
    monkeypatch.setattr(workflow, "state_output", unauthorized)
    r = await workflow.start_step(deletable_fixture(**{"blue/event": "delete"}), env={})
    assert r["blue/exit"] == 1
    assert "Unauthorized" in r["blue/err"]
    assert "COLORS_PAR_IP" in r["blue/err"]


async def test_delete_with_explicit_ip_skips_the_state_read(monkeypatch):
    # COLORS_PAR_IP is the operator's escape hatch when the state backend is
    # unreachable; it must not require the read it exists to replace.
    async def boom(_opts):
        raise AssertionError("must not be called")
    monkeypatch.setattr(workflow, "state_output", boom)
    r = await workflow.start_step(deletable_fixture(**{"blue/event": "delete",
                                                       "ip": "203.0.113.7"}), env={})
    assert r["blue/exit"] == 0
    assert r["ip"] == "203.0.113.7"


async def test_delete_with_empty_state_proceeds_without_an_address(monkeypatch):
    # State readable, no compute recorded: the instance is already gone, the
    # cleanup step skips itself, and the rest of the teardown still runs.
    async def empty(_opts):
        return None
    monkeypatch.setattr(workflow, "state_output", empty)
    r = await workflow.start_step(deletable_fixture(**{"blue/event": "delete"}), env={})
    assert r["blue/exit"] == 0
    assert r.get("ip") is None


async def test_real_delete_fills_the_real_key_paths_and_adopts_state(monkeypatch, tmp_path):
    # The transition table's last row: a destroy renders before it destroys, so
    # the template values are the real ones, merged with the adopted state. No
    # key check runs — the cleanup comes after the destroy.
    monkeypatch.setenv("HOME", str(tmp_path))

    async def recorded(_opts):
        return {"ip": "203.0.113.9", "ssh_key_id": "77"}
    monkeypatch.setattr(workflow, "state_output", recorded)
    r = await workflow.start_step(deletable_fixture(**{"blue/event": "delete"}), env={})
    assert r["blue/exit"] == 0
    assert r["ip"] == "203.0.113.9"
    assert r["ssh-private-key-path"] == str(tmp_path / ".ssh" / "posthog-fixture")
    assert r["ssh-keygen"] is True


def creatable_fixture(**overrides) -> dict:
    """A fixture that passes real-create preflight: secrets present."""
    return deletable_fixture(**{"compute-prevent-destroy": True, **overrides})


async def test_real_create_runs_the_key_matrix_then_both_preflights(monkeypatch):
    # Row three of the transition table, in order: ensure_key against the
    # best-effort state read, the provider preflight, then the ~/.ssh/config
    # checks. Each stops the run on its own error.
    calls = []

    async def no_state(_opts):
        return None

    async def ensure(opts, state_fn):
        calls.append(["ensure", await state_fn(opts)])
        return opts
    monkeypatch.setattr(workflow, "state_output", no_state)
    monkeypatch.setattr(ssh, "ensure_key", ensure)
    monkeypatch.setattr(ssh, "preflight", lambda o, *_: (calls.append("preflight"), o)[1])
    monkeypatch.setattr(ssh_config, "preflight", lambda o: (calls.append("ssh-config"), o)[1])
    r = await workflow.start_step(creatable_fixture(**{"blue/event": "create"}), env={})
    assert r["blue/exit"] == 0
    assert calls == [["ensure", None], "preflight", "ssh-config"]
    assert r["ssh-keygen"] is True, "the real key path is filled for the templates"

    # An unreadable backend counts as no state on a create.
    async def unauthorized(_opts):
        raise Exception("Unauthorized")

    async def record_state(opts, state_fn):
        return {**opts, "state": await state_fn(opts)}
    monkeypatch.setattr(workflow, "state_output", unauthorized)
    monkeypatch.setattr(ssh, "ensure_key", record_state)
    monkeypatch.setattr(ssh, "preflight", lambda o, *_: o)
    monkeypatch.setattr(ssh_config, "preflight", lambda o: o)
    r = await workflow.start_step(creatable_fixture(**{"blue/event": "create"}), env={})
    assert r["blue/exit"] == 0
    assert r["state"] is None

    # The key matrix stops the run.
    async def half(opts, _state_fn):
        return {**opts, "blue/exit": 1, "blue/err": "half a keypair"}

    def must_not_run(*_a):
        raise AssertionError("must not run")
    monkeypatch.setattr(workflow, "state_output", no_state)
    monkeypatch.setattr(ssh, "ensure_key", half)
    monkeypatch.setattr(ssh, "preflight", must_not_run)
    monkeypatch.setattr(ssh_config, "preflight", must_not_run)
    r = await workflow.start_step(creatable_fixture(**{"blue/event": "create"}), env={})
    assert r["blue/exit"] == 1
    assert "half a keypair" in r["blue/err"]

    # The provider preflight stops the run.
    async def passthrough(opts, _state_fn):
        return opts
    monkeypatch.setattr(ssh, "ensure_key", passthrough)
    monkeypatch.setattr(ssh, "preflight",
                        lambda o, *_: {**o, "blue/exit": 1, "blue/err": "already has an SSH key"})
    r = await workflow.start_step(creatable_fixture(**{"blue/event": "create"}), env={})
    assert r["blue/exit"] == 1
    assert "already has an SSH key" in r["blue/err"]

    # The ~/.ssh/config checks stop the run.
    monkeypatch.setattr(ssh, "preflight", lambda o, *_: o)
    monkeypatch.setattr(ssh_config, "preflight",
                        lambda o: {**o, "blue/exit": 1, "blue/err": "refusing to manage"})
    r = await workflow.start_step(creatable_fixture(**{"blue/event": "create"}), env={})
    assert r["blue/exit"] == 1
    assert "refusing to manage" in r["blue/err"]


async def test_opt_out_create_skips_the_key_matrix(monkeypatch, tmp_path):
    # Presence of the explicit key is the only switch: the package then
    # generates, validates and deletes nothing. ONCE's own short-circuit is the
    # thing under test, so it is not stubbed: instead everything it would reach
    # in keygen mode is made to fail.
    monkeypatch.setenv("HOME", str(tmp_path))

    async def no_state(_opts):
        return None

    def must_not_run(*_a, **_k):
        raise AssertionError("must not run")
    monkeypatch.setattr(workflow, "state_output", no_state)
    monkeypatch.setattr(once_ssh, "fetch_account_keys", must_not_run)
    monkeypatch.setattr(ssh_config, "preflight", lambda o: o)
    r = await workflow.start_step(creatable_fixture(**{"blue/event": "create", **make_optout()}), env={})
    assert r["blue/exit"] == 0
    assert r["digitalocean-ssh-keys"] == "58495393"
    assert not os.path.exists(tmp_path / ".ssh")


def test_graph_orders_private_stack():
    create = {"blue/event": "create"}
    assert workflow.wire_fn("posthog/start", create)[1:] == ("posthog/infrastructure",)
    assert workflow.wire_fn("posthog/infrastructure", create)[1:] == ("posthog/ssh-config",)
    assert workflow.wire_fn("posthog/ssh-config", create)[1:] == ("posthog/dns",)
    assert workflow.wire_fn("posthog/dns", create)[1:] == ("posthog/ansible",)
    assert workflow.wire_fn("posthog/ansible", create)[1:] == ("posthog/acceptance",)
    assert workflow.wire_fn("posthog/start", {"blue/event": "delete"})[1:] == ("posthog/ansible",)


def test_delete_removes_the_key_after_the_compute_destroy():
    # The ordering is what makes "key present ⇔ deployment exists" hold: a
    # failed destroy never reaches the cleanup step, and correctly leaves the
    # key that is still the only credential to whatever survived.
    delete = {"blue/event": "delete"}
    assert workflow.wire_fn("posthog/ansible", delete)[1:] == ("posthog/dns",)
    assert workflow.wire_fn("posthog/dns", delete)[1:] == ("posthog/ssh-config",)
    assert workflow.wire_fn("posthog/ssh-config", delete)[1:] == ("posthog/infrastructure",)
    assert workflow.wire_fn("posthog/infrastructure", delete)[1:] == ("posthog/ssh-cleanup",)
    assert workflow.wire_fn("posthog/ssh-cleanup", delete)[1:] == ()
