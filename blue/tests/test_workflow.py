from conftest import make_fixture
from package_posthog_blue import workflow


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


def test_graph_orders_private_stack():
    assert workflow.wire_fn("posthog/start", {"blue/event": "create"})[1:] == \
        ("posthog/infrastructure",)
    assert workflow.wire_fn("posthog/infrastructure", {"blue/event": "create"})[1:] == \
        ("posthog/dns",)
    assert workflow.wire_fn("posthog/start", {"blue/event": "delete"})[1:] == \
        ("posthog/ansible",)
