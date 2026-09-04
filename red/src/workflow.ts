// Lifecycle graph and backend advice, the port of
// io.github.getcolors.posthog.workflow.

import { readPars, parName } from "red/cli";
import * as dryRun from "red/dry-run";
import { preflight } from "red/lifecycle";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, failed, workflow, type Opts, type WireDecl } from "red/workflow";
import * as ssh from "./ssh.ts";
import * as sshConfig from "./ssh-config.ts";
import * as tools from "./tools.ts";
import * as validate from "./validate.ts";

export const defaults: Opts = {
  "provider-compute": "digitalocean", "provider-dns": "cloudflare",
  "provider-backend": "local", "compute-prevent-destroy": true,
  workdir: ".colors",
};

export const lifecycleEvents = ["create", "delete"];

// Compute params recorded in the infrastructure state; undefined when the
// state holds none. An unreadable backend throws — the delete path treats that
// as fatal rather than falling back to the documentation address.
export async function stateOutput(opts: Opts): Promise<Opts | undefined> {
  const outputs = await tofu.outputs(tools.toolDir(opts, tools.infrastructureTool),
    tools.backendCredentialEnv(opts));
  return outputs?.params as Opts | undefined;
}

// A real delete runs the ansible cleanup before the infrastructure step, so
// the instance address must come out of the existing state here. An explicit
// :ip (COLORS_PAR_IP) skips the read; a readable state without compute params
// leaves :ip unset and the cleanup step skips itself; an unreadable backend
// fails loudly — swallowing it is how a live teardown ended up converging
// against 192.0.2.10.
export async function adoptState(opts: Opts): Promise<Opts> {
  if (opts.ip) return { ...opts, "red/exit": 0 };
  try {
    return { ...opts, ...((await stateOutput(opts)) ?? {}), "red/exit": 0 };
  } catch (e) {
    return {
      ...opts, "red/exit": 1,
      "red/err": "could not read the infrastructure state for " +
        `the delete cleanup: ${e instanceof Error ? e.message : String(e)}\n` +
        "fix the backend credentials, or supply " +
        parName("ip") +
        " to address the instance directly",
    };
  }
}

// `stateOutput` for the keypair's create matrix, which keys on a best-effort
// read: an unreadable state (a fresh clone, a missing backend) counts as absent
// on a create. The fail-closed reading above is the delete path's alone.
export async function bestEffortState(opts: Opts): Promise<Opts | undefined> {
  try {
    return await stateOutput(opts);
  } catch {
    return undefined;
  }
}

// The lifecycle transition table, once the validators have passed.
//
// build and dry-run only render: `withMachineKey` fills the placeholder key
// paths and nothing under `~/.ssh` or `~/.ssh/config` is read. A real create
// runs the keypair's create matrix and the DigitalOcean preflight before any
// template is rendered — an unowned key on disk or at the provider stops the
// run while stopping is still free — then the `~/.ssh/config` ownership and
// placement checks. A real delete fills the same template values (a destroy
// renders before it destroys) and adopts the instance address from state,
// fail-closed; it checks no key, because its cleanup runs after the destroy.
export async function afterValidate(
  opts: Opts, { event, real }: { event?: string; real?: boolean },
): Promise<Opts> {
  if (real && event === "delete") return adoptState(ssh.withMachineKey(opts));
  if (real && event === "create") {
    let next = await ssh.ensureKey(opts, bestEffortState);
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
): Promise<Opts> {
  return preflight(opts, {
    defaults,
    overlay: readPars,
    validators: [
      (_opts, environment) => validate.envErrors(environment),
      (current) => validate.stateErrors(current),
      (current, _environment, { event, real }) =>
        real && lifecycleEvents.includes(String(event))
          ? validate.secretErrors(current)
          : [],
      (current, _environment, { event, real }) =>
        real && event === "delete" && current["compute-prevent-destroy"]
          ? [`compute destruction is protected; set ${parName("compute-prevent-destroy")}=false to delete`]
          : [],
    ],
    afterValidate: (current, _environment, context) => afterValidate(current, context),
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
