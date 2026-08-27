// Lifecycle graph and backend advice, the port of
// io.github.getcolors.posthog.workflow.

import { readPars, parName } from "red/cli";
import * as dryRun from "red/dry-run";
import { preflight } from "red/lifecycle";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, workflow, type Opts, type WireDecl } from "red/workflow";
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
    afterValidate: (current, _environment, { event, real }) =>
      real && event === "delete"
        ? adoptState(current)
        : { ...current, "red/exit": 0 },
  }, env);
}

export function wireFn(step: string, runOpts: Opts): WireDecl | undefined {
  if (runOpts["red/event"] === "delete") {
    const graph: Record<string, WireDecl> = {
      "posthog/start": [startStep, "posthog/ansible"],
      "posthog/ansible": [tools.ansibleStep, "posthog/dns"],
      "posthog/dns": [tools.dnsStep, "posthog/infrastructure"],
      "posthog/infrastructure": [tools.infrastructureStep],
    };
    return graph[step];
  }
  const graph: Record<string, WireDecl> = {
    "posthog/start": [startStep, "posthog/infrastructure"],
    "posthog/infrastructure": [tools.infrastructureStep, "posthog/dns"],
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
  "posthog/infrastructure", "posthog/dns", "posthog/ansible", "posthog/acceptance",
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
