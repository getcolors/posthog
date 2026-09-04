// Lifecycle graph and backend advice, the port of
// io.github.getcolors.posthog.workflow.

import { readPars, parName } from "red/cli";
import * as dryRun from "red/dry-run";
import { preflight } from "red/lifecycle";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, failed, workflow, type Opts, type WireDecl } from "red/workflow";
import { compute } from "package-once-red";
import * as ssh from "./ssh.ts";
import * as sshConfig from "./ssh-config.ts";
import * as tools from "./tools.ts";
import * as validate from "./validate.ts";

export const defaults: Opts = {
  "provider-compute": validate.defaultComputeProvider, "provider-dns": "cloudflare",
  "provider-backend": "local", "compute-prevent-destroy": true,
  workdir: ".colors",
};

// Compute params recorded in the infrastructure state; undefined when the
// state holds none. An unreadable backend throws the SDK's `StepError`, which
// `compute.readState` turns into `{ error }` — create and delete treat the two
// differently. Kept local, and injectable into `startStep`, so tests can stub
// it or the `tofu output` beneath it.
export async function stateOutput(opts: Opts): Promise<compute.Params | undefined> {
  const outputs = await tofu.outputs(tools.toolDir(opts, tools.infrastructureTool),
    tools.backendCredentialEnv(opts));
  const params = outputs?.params;
  return params && typeof params === "object" ? params as compute.Params : undefined;
}

// A real delete runs the ansible cleanup before the infrastructure step, so
// the instance address must come out of the existing state here. The adoption
// itself is ONCE's (`compute.adoptState`): a readable state without compute
// params leaves `ip` unset and the cleanup step skips itself; an unreadable
// backend fails loudly — swallowing it is how a live teardown ended up
// converging against 192.0.2.10. What this package adds is the address
// override: an explicit `ip` (COLORS_PAR_IP) never skips the read or the
// provider guard, it only replaces the cleanup address once the read has
// succeeded, for a state whose recorded address is stale. ONCE deliberately
// applies no such override, so no other package gains a way to point a
// delete's cleanup at an arbitrary host.
export function adoptState(opts: Opts, state: compute.StateRead): Opts {
  const adopted = compute.adoptState(opts, "delete", state);
  return !failed(adopted) && opts.ip ? { ...adopted, ip: opts.ip } : adopted;
}

// The lifecycle transition table, once the validators have passed.
//
// build and dry-run only render: `withMachineKey` fills the placeholder key
// paths and nothing under `~/.ssh` or `~/.ssh/config` is read. A real create
// runs the keypair's create matrix against the one state read, then the
// provider preflight, before any template is rendered — an unowned key on disk
// or at the provider stops the run while stopping is still free — then the
// `~/.ssh/config` ownership and placement checks. A real delete fills the same
// template values (a destroy renders before it destroys) and adopts the
// instance address from the same read, fail-closed; it checks no key, because
// its cleanup runs after the destroy.
export async function afterValidate(
  opts: Opts, { event, real }: { event?: string; real?: boolean }, state: compute.StateRead,
): Promise<Opts> {
  if (real && event === "delete") return adoptState(opts, state);
  if (real && event === "create") {
    let next = await ssh.ensureKey(opts, async () => state.params);
    if (failed(next)) return next;
    next = await ssh.preflight(ssh.withMachineKey(next));
    if (!failed(next)) next = sshConfig.preflight(next);
    return failed(next) ? next : { ...next, "red/exit": 0 };
  }
  return { ...ssh.withMachineKey(opts), "red/exit": 0 };
}

export async function startStep(
  opts: Opts,
  env: Record<string, string | undefined> = process.env,
  reader: compute.StateReader = stateOutput,
): Promise<Opts> {
  // The state is read once, up front, on the same defaulted and overlaid opts
  // the validators see — the overlay is what carries the backend credentials —
  // and only for the two events that touch a provider. The validator and the
  // after-validate share the one read.
  const merged = readPars({ ...defaults, ...opts }, env);
  const context = { event: merged["red/event"] as string | undefined, real: !merged["red/dry-run"] };
  const state: compute.StateRead = compute.lifecycleEvent(context)
    ? await compute.readState(merged, reader) : {};
  return preflight(opts, {
    defaults,
    overlay: readPars,
    validators: [
      (_opts, environment) => validate.envErrors(environment),
      (current) => validate.stateErrors(current),
      // Standard §4 before the credentials: a recorded provider that differs
      // from the selected one reports the actionable error, not a missing
      // token for the provider that was just selected.
      (current, _environment, ctx) => (compute.lifecycleEvent(ctx)
        ? compute.providerValidator(validate.spec, current, state.params, () => validate.secretErrors(current))
        : []),
      (current, _environment, { event, real }) =>
        real && event === "delete" && current["compute-prevent-destroy"]
          ? [`compute destruction is protected; set ${parName("compute-prevent-destroy")}=false to delete`]
          : [],
    ],
    afterValidate: (current, _environment, ctx) => afterValidate(current, ctx, state),
  }, env);
}

export function wireFn(step: string, runOpts: Opts): WireDecl | undefined {
  if (runOpts["red/event"] === "delete") {
    const graph: Record<string, WireDecl> = {
      "posthog/start": [startStep, "posthog/ansible"],
      "posthog/ansible": [tools.ansibleStep, "posthog/dns"],
      // The `~/.ssh/config` block goes before the destroy, the opposite of the
      // keypair below. A block that outlives its host is stale but harmless; a
      // key that predeceases its host locks the operator out of a machine that
      // still exists. Both orders are deliberate; see standards/ssh-config.md.
      "posthog/dns": [tools.dnsStep, "posthog/ssh-config"],
      "posthog/ssh-config": [tools.ansibleLocalStep, "posthog/infrastructure"],
      "posthog/infrastructure": [tools.infrastructureStep, "posthog/ssh-cleanup"],
      "posthog/ssh-cleanup": [ssh.cleanupStep],
    };
    return graph[step];
  }
  const graph: Record<string, WireDecl> = {
    "posthog/start": [startStep, "posthog/infrastructure"],
    // After compute, which is where the address first exists, and before the
    // stage that converges the machine.
    "posthog/infrastructure": [tools.infrastructureStep, "posthog/ssh-config"],
    "posthog/ssh-config": [tools.ansibleLocalStep, "posthog/dns"],
    "posthog/dns": [tools.dnsStep, "posthog/ansible"],
    "posthog/ansible": [tools.ansibleStep, "posthog/acceptance"],
    "posthog/acceptance": [tools.acceptanceStep],
  };
  return graph[step];
}

export function backendAdvice(tool: string) {
  return tofu.conventionalBackendAdvice({
    dir: (opts) => tools.toolDir(opts, tool),
    key: (opts) => `${opts.profile}/${tool}.tfstate`,
  });
}

export const sideEffecting = [
  "posthog/infrastructure", "posthog/dns", "posthog/ssh-config",
  "posthog/ansible", "posthog/acceptance", "posthog/ssh-cleanup",
];

function create() {
  let wf = workflow({ start: "posthog/start", wireFn });
  wf = adviceAdd(wf, "posthog/infrastructure", "before",
    "io.github.getcolors.posthog.workflow/backend", backendAdvice(tools.infrastructureTool));
  wf = adviceAdd(wf, "posthog/dns", "before",
    "io.github.getcolors.posthog.workflow/backend", backendAdvice(tools.dnsTool));
  wf = progress.advise(wf);
  wf = dryRun.advise(wf, sideEffecting);
  return wf;
}

export const posthogWorkflow = create();
