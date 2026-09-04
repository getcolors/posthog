import os

from conftest import SECRETS, make_fixture, make_optout, make_vultr
from package_once_blue import ssh as once_ssh
from package_posthog_blue import ssh, ssh_config, workflow


def _stub_lifecycle(monkeypatch):
    """Everything a real create or delete would do after the validators."""
    async def passthrough(opts, _state_fn):
        return opts
    monkeypatch.setattr(ssh, "ensure_key", passthrough)
    monkeypatch.setattr(ssh, "preflight", lambda o, *_: o)
    monkeypatch.setattr(ssh_config, "preflight", lambda o: o)


async def test_provider_switch_is_refused_on_create_and_delete_before_the_missing_token(monkeypatch):
    # Compute Provider Standard §4: all providers share one state key, so a
    # changed provider-compute on a profile with compute in state would plan a
    # cross-provider replacement. Both events refuse; delete refuses because it
    # would render and destroy the *selected* provider's template. The
    # validator order is the thing under test: no missing-token entry for the
    # newly selected provider appears beside the actionable error.
    _stub_lifecycle(monkeypatch)

    async def held_do(_opts):
        return {"provider": "digitalocean", "ip": "203.0.113.7"}
    monkeypatch.setattr(workflow, "state_output", held_do)
    without_token = {k: v for k, v in SECRETS.items() if k != "vultr-api-key"}
    for event in ("create", "delete"):
        r = await workflow.start_step(make_vultr(**{**without_token, "blue/event": event,
                                                    "compute-prevent-destroy": False}), env={})
        assert r["blue/exit"] == 2, event
        lines = r["blue/err"].split("\n")
        assert "state holds a digitalocean machine; set provider-compute back to digitalocean and delete first" in lines
        assert not any("COLORS_PAR_VULTR_API_KEY" in line for line in lines), event

    async def held_vultr(_opts):
        return {"provider": "vultr", "ip": "203.0.113.7"}
    monkeypatch.setattr(workflow, "state_output", held_vultr)
    for event in ("create", "delete"):
        r = await workflow.start_step(make_fixture(**{**SECRETS, "blue/event": event,
                                                      "compute-prevent-destroy": False}), env={})
        assert "state holds a vultr machine; set provider-compute back to vultr and delete first" in r["blue/err"]


async def test_legacy_state_without_a_provider_accepts_only_the_default(monkeypatch):
    _stub_lifecycle(monkeypatch)

    async def legacy(_opts):
        return {"ip": "203.0.113.7"}
    monkeypatch.setattr(workflow, "state_output", legacy)
    for event in ("create", "delete"):
        ok = await workflow.start_step(make_fixture(**{**SECRETS, "blue/event": event,
                                                       "compute-prevent-destroy": False}), env={})
        assert ok["blue/exit"] == 0, event
        refused = await workflow.start_step(make_vultr(**{**SECRETS, "blue/event": event,
                                                          "compute-prevent-destroy": False}), env={})
        assert refused["blue/exit"] == 2, event
        assert "set provider-compute back to digitalocean" in refused["blue/err"]


async def test_unreadable_backend_is_no_state_on_create_and_fatal_on_delete(monkeypatch):
    seen = {}

    async def unauthorized(_opts):
        raise Exception("Unauthorized")

    async def record_state(opts, state_fn):
        seen["state"] = await state_fn(opts)
        return opts
    monkeypatch.setattr(workflow, "state_output", unauthorized)
    monkeypatch.setattr(ssh, "ensure_key", record_state)
    monkeypatch.setattr(ssh, "preflight", lambda o, *_: o)
    monkeypatch.setattr(ssh_config, "preflight", lambda o: o)
    for make in (make_fixture, make_vultr):
        created = await workflow.start_step(make(**{**SECRETS, "blue/event": "create"}), env={})
        assert created["blue/exit"] == 0
        assert seen["state"] is None
        deleted = await workflow.start_step(make(**{**SECRETS, "blue/event": "delete",
                                                    "compute-prevent-destroy": False}), env={})
        assert deleted["blue/exit"] == 1
        assert "could not read the infrastructure state for the delete cleanup" in deleted["blue/err"]
        assert "Unauthorized" in deleted["blue/err"]


async def test_real_create_requires_the_selected_provider_credentials():
    r = await workflow.start_step(make_vultr(**{"blue/event": "create"}), env={})
    assert r["blue/exit"] == 2
    assert "COLORS_PAR_VULTR_API_KEY" in r["blue/err"]
    assert "COLORS_PAR_DO_TOKEN" not in r["blue/err"]


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
    assert "could not read the infrastructure state for the delete cleanup" in r["blue/err"]


async def test_explicit_ip_never_skips_the_read_or_the_provider_guard(monkeypatch):
    # COLORS_PAR_IP replaces a stale recorded address once the read succeeded;
    # it is not a way around the read, the fail-closed rule, or the provider
    # guard (Compute Provider Standard §4).
    async def unauthorized(_opts):
        raise Exception("Unauthorized")
    monkeypatch.setattr(workflow, "state_output", unauthorized)
    r = await workflow.start_step(deletable_fixture(**{"blue/event": "delete", "ip": "203.0.113.7"}), env={})
    assert r["blue/exit"] == 1
    assert "Unauthorized" in r["blue/err"]

    async def held_vultr(_opts):
        return {"provider": "vultr", "ip": "198.51.100.4"}
    monkeypatch.setattr(workflow, "state_output", held_vultr)
    r = await workflow.start_step(deletable_fixture(**{"blue/event": "delete", "ip": "203.0.113.7"}), env={})
    assert r["blue/exit"] == 2
    assert "state holds a vultr machine" in r["blue/err"]

    async def held_do(_opts):
        return {"provider": "digitalocean", "ip": "198.51.100.4"}
    monkeypatch.setattr(workflow, "state_output", held_do)
    r = await workflow.start_step(deletable_fixture(**{"blue/event": "delete", "ip": "203.0.113.7"}), env={})
    assert r["blue/exit"] == 0
    assert r["ip"] == "203.0.113.7", "the override wins over the recorded address after the read"


async def test_state_is_read_once_per_run(monkeypatch):
    # One read serves the provider validator, the key matrix and the adoption;
    # a second read would be a second chance for the backend to disagree.
    for event in ("create", "delete"):
        reads = {"n": 0}

        async def counted(_opts):
            reads["n"] += 1
            return {"provider": "digitalocean", "ip": "203.0.113.9"}

        async def record(opts, state_fn):
            return {**opts, "recorded": await state_fn(opts)}
        monkeypatch.setattr(workflow, "state_output", counted)
        monkeypatch.setattr(ssh, "ensure_key", record)
        monkeypatch.setattr(ssh, "preflight", lambda o, *_: o)
        monkeypatch.setattr(ssh_config, "preflight", lambda o: o)
        r = await workflow.start_step(deletable_fixture(**{"blue/event": event,
                                                           "compute-prevent-destroy": event == "create"}), env={})
        assert r["blue/exit"] == 0, event
        assert reads["n"] == 1, event
        if event == "create":
            assert r["recorded"]["ip"] == "203.0.113.9"
        else:
            assert r["ip"] == "203.0.113.9"


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
