"""Lifecycle graph and backend advice, the port of
io.github.getcolors.posthog.workflow."""

from __future__ import annotations

import os

from blue import dry_run, progress, tofu
from blue.cli import par_name, read_pars
from blue.lifecycle import preflight
from blue.workflow import advice_add, failed, workflow

from . import ssh, ssh_config, tools, validate

DEFAULTS = {"provider-compute": validate.default_compute_provider, "provider-dns": "cloudflare",
            "provider-backend": "local", "compute-prevent-destroy": True,
            "workdir": ".colors"}

LIFECYCLE_EVENTS = ("create", "delete")


async def state_output(opts: dict) -> dict | None:
    """Compute params recorded in the infrastructure state; None when the state
    holds none. An unreadable backend raises — `read_state` is where the two
    are told apart, because create and delete treat them differently."""
    outputs = await tofu.outputs(tools.tool_dir(opts, tools.infrastructure_tool),
                                 tools.backend_credential_env(opts))
    return (outputs or {}).get("params")


async def read_state(opts: dict) -> dict:
    """One read of the compute state per run, shaped so a caller can tell
    nothing recorded from nothing readable: `{"params": m}` where `m` may be
    None, or `{"error": message}`. Needs backend credentials only."""
    try:
        return {"params": await state_output(opts)}
    except Exception as e:  # noqa: BLE001 — any failed read must be reported
        return {"error": str(e)}


def lifecycle_event(context: dict) -> bool:
    """A real create or delete: the two events that touch a provider."""
    return bool(context.get("real")) and context.get("event") in LIFECYCLE_EVENTS


def provider_validator(opts: dict, state: dict) -> list[str]:
    """Compute Provider Standard §4 before the credentials. The recorded
    provider is compared with the selected one first, so a mistaken provider
    edit reports the actionable error — put it back and delete — rather than a
    missing token for the provider that was just selected; validators
    aggregate, which is why a mismatch pre-empts the secrets check rather than
    sitting beside it. On a create an unreadable backend counts as no state (a
    fresh clone has none) and the credentials are checked as usual; on a
    delete `adopt_state` refuses it after validation. Blue's validators are
    synchronous, so `start_step` performs the one read up front and hands it
    in."""
    mismatch = validate.provider_state_errors(opts, state.get("params"))
    return mismatch if mismatch else validate.secret_errors(opts)


def adopt_state(opts: dict, state: dict) -> dict:
    """A real delete runs the ansible cleanup before the infrastructure step, so
    the instance address must come out of the existing state here. A readable
    state without compute params leaves :ip unset and the cleanup step skips
    itself; an unreadable backend fails loudly — swallowing it is how a live
    teardown ended up converging against 192.0.2.10. An explicit :ip
    (COLORS_PAR_IP) never skips the read or the provider guard: it only
    replaces the cleanup address once the read has succeeded, for a state
    whose recorded address is stale."""
    if state.get("error") is not None:
        return {**opts, "blue/exit": 1,
                "blue/err": ("could not read the infrastructure state for "
                             f"the delete cleanup: {state['error']}\n"
                             "fix the backend credentials and retry; a delete that "
                             "cannot see its state has nothing to address")}
    return {**ssh.with_machine_key(opts),
            **(state.get("params") or {}),
            **({"ip": opts["ip"]} if opts.get("ip") else {}),
            "blue/exit": 0}


async def after_validate(opts: dict, context: dict, state: dict) -> dict:
    """The lifecycle transition table, once the validators have passed.

    build and dry-run only render: `with_machine_key` fills the placeholder
    key paths and nothing under `~/.ssh` or `~/.ssh/config` is read. A real
    create runs the keypair's create matrix and the DigitalOcean preflight
    before any template is rendered — an unowned key on disk or at the
    provider stops the run while stopping is still free — then the
    `~/.ssh/config` ownership and placement checks. A real delete fills the
    same template values (a destroy renders before it destroys) and adopts the
    instance address from state, fail-closed; it checks no key, because its
    cleanup runs after the destroy."""
    real, event = context.get("real"), context.get("event")
    if real and event == "delete":
        return adopt_state(opts, state)
    if real and event == "create":
        async def recorded(_opts):
            return state.get("params")
        opts = await ssh.ensure_key(opts, recorded)
        if failed(opts):
            return opts
        opts = ssh.preflight(ssh.with_machine_key(opts))
        if failed(opts):
            return opts
        opts = ssh_config.preflight(opts)
        if failed(opts):
            return opts
        return {**opts, "blue/exit": 0}
    return {**ssh.with_machine_key(opts), "blue/exit": 0}


async def start_step(opts: dict, env: dict | None = None) -> dict:
    # The state is read once, up front, on the same defaulted and overlaid opts
    # the validators see — the overlay is what carries the backend credentials
    # — and only for the two events that touch a provider. The validator and
    # the after-validate share the one read.
    environment = dict(os.environ if env is None else env)
    merged = read_pars({**DEFAULTS, **opts}, environment)
    context = {"event": merged.get("blue/event"), "real": not merged.get("blue/dry-run")}
    state = await read_state(merged) if lifecycle_event(context) else {}
    return await preflight(
        opts, defaults=DEFAULTS, overlay=read_pars, env=env,
        validators=[
            lambda _o, e, _c: validate.env_errors(e),
            lambda o, _e, _c: validate.state_errors(o),
            lambda o, _e, c: provider_validator(o, state) if lifecycle_event(c) else [],
            lambda o, _e, c: ([f"compute destruction is protected; set "
                               f"{par_name('compute-prevent-destroy')}=false to delete"]
                              if c["real"] and c["event"] == "delete"
                              and o.get("compute-prevent-destroy") else []),
        ],
        after_validate=lambda o, _e, c: after_validate(o, c, state))


def wire_fn(step: str, run_opts: dict):
    if run_opts.get("blue/event") == "delete":
        return {
            "posthog/start": (start_step, "posthog/ansible"),
            "posthog/ansible": (tools.ansible_step, "posthog/dns"),
            # The `~/.ssh/config` block goes before the destroy, the opposite
            # of the keypair below. A block that outlives its host is stale but
            # harmless; a key that predeceases its host locks the operator out
            # of a machine that still exists. Both orders are deliberate; see
            # standards/ssh-config.md.
            "posthog/dns": (tools.dns_step, "posthog/ssh-config"),
            "posthog/ssh-config": (tools.ansible_local_step, "posthog/infrastructure"),
            "posthog/infrastructure": (tools.infrastructure_step, "posthog/ssh-cleanup"),
            "posthog/ssh-cleanup": (ssh.cleanup_step,),
        }.get(step)
    return {
        "posthog/start": (start_step, "posthog/infrastructure"),
        # After compute, which is where the address first exists, and before
        # the stage that converges the machine.
        "posthog/infrastructure": (tools.infrastructure_step, "posthog/ssh-config"),
        "posthog/ssh-config": (tools.ansible_local_step, "posthog/dns"),
        "posthog/dns": (tools.dns_step, "posthog/ansible"),
        "posthog/ansible": (tools.ansible_step, "posthog/acceptance"),
        "posthog/acceptance": (tools.acceptance_step,),
    }.get(step)


def backend_advice(tool: str):
    return tofu.conventional_backend_advice(
        dir=lambda o, tool=tool: tools.tool_dir(o, tool),
        key=lambda o, tool=tool: f"{o.get('profile')}/{tool}.tfstate")


side_effecting = ["posthog/infrastructure", "posthog/dns", "posthog/ssh-config",
                  "posthog/ansible", "posthog/acceptance", "posthog/ssh-cleanup"]


def create_workflow():
    wf = workflow(start="posthog/start", wire_fn=wire_fn)
    wf = advice_add(wf, "posthog/infrastructure", "before",
                    "io.github.getcolors.posthog.workflow/backend",
                    backend_advice(tools.infrastructure_tool))
    wf = advice_add(wf, "posthog/dns", "before",
                    "io.github.getcolors.posthog.workflow/backend",
                    backend_advice(tools.dns_tool))
    wf = progress.advise(wf)
    wf = dry_run.advise(wf, side_effecting)
    return wf


posthog_workflow = create_workflow()
