import { readPars, parName } from "red/cli";
import * as dryRun from "red/dry-run";
import { preflight, type PreflightContext } from "red/lifecycle";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, failed, workflow, type Opts, type WireDecl } from "red/workflow";
import * as compute from "./compute.ts";
import * as ssh from "./ssh.ts";
import * as sshConfig from "./ssh-config.ts";
import * as tools from "./tools.ts";
import * as validate from "./validate.ts";

export const defaults: Opts = {
  "provider-compute": validate.defaultComputeProvider, "provider-dns": "cloudflare",
  "provider-backend": "r2", "compute-prevent-destroy": true,
  workdir: ".colors",
};

export async function startStep(opts:Opts,env:Record<string,string|undefined>=process.env):Promise<Opts>{return preflight(opts,{defaults,overlay:readPars,validators:[(_o,e)=>validate.envErrors(e),o=>validate.stateErrors(o),(o,_e,c)=>c.real&&['create','delete'].includes(c.event??'')?validate.secretErrors(o):[],(o,_e,c)=>c.real&&c.event==='delete'&&o['compute-prevent-destroy']?['compute destruction is protected; set COLORS_PAR_COMPUTE_PREVENT_DESTROY=false to delete']:[]],afterValidate:async(o,e,c)=>{if(c.real&&c.event==='delete'){const result=await compute.load(o,e);return !failed(result)&&o.ip&&!result['posthog/already-destroyed']?{...result,ip:o.ip}:result;}if(c.real&&c.event==='create')return sshConfig.preflight(o);return {...ssh.withMachineKey(o),'red/exit':0};}},env);}

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
      "posthog/infrastructure": [tools.infrastructureStep],
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
    key: (opts) => `${opts.profile ?? ""}/${tool}.tfstate`,
  });
}

export const sideEffecting = [
  "posthog/infrastructure", "posthog/dns", "posthog/ssh-config",
  "posthog/ansible", "posthog/acceptance",
];

function create() {
  let wf = workflow({ start: "posthog/start", wireFn, nextFn:(_step, successors, opts)=>opts["posthog/already-destroyed"]||failed(opts)?[]:(successors??[]).map(step=>[step,opts]) });
  wf = adviceAdd(wf, "posthog/dns", "before", "posthog.workflow/backend",
    backendAdvice(tools.dnsTool));
  return dryRun.advise(progress.advise(wf), sideEffecting);
}

export const posthogWorkflow = create();
