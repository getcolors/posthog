// OpenTofu and Ansible stages for the single-node PostHog suite, the port of
// io.github.getcolors.posthog.tools.

import * as ansible from "red/ansible";
import { stageDir } from "red/cli";
import { PRESERVE_JINJA_DELIMITERS, contentSpec, scaffold, type Spec, type Template } from "red/scaffold";
import * as tofu from "red/tofu";
import { runtime } from "red/runtime";
import type { Opts } from "red/workflow";
import { StepError, failed } from "red/workflow";
import * as ssh from "./ssh.ts";
import * as sshConfig from "./ssh-config.ts";
import * as utils from "./utils.ts";
import * as validate from "./validate.ts";

import ansibleLocalCfg from "../resources/tools/ansible-local/ansible.cfg" with { type: "text" };
import ansibleLocalInventory from "../resources/tools/ansible-local/inventory.ini" with { type: "text" };
import ansibleLocalMain from "../resources/tools/ansible-local/main.yml" with { type: "text" };
import ansibleCaddyfile from "../resources/tools/ansible/Caddyfile" with { type: "text" };
import ansibleCfg from "../resources/tools/ansible/ansible.cfg" with { type: "text" };
import ansibleBackup from "../resources/tools/ansible/backup" with { type: "text" };
import ansibleCheckpoint from "../resources/tools/ansible/checkpoint.sql" with { type: "text" };
import ansibleCleanup from "../resources/tools/ansible/cleanup.yml" with { type: "text" };
import ansibleCompose from "../resources/tools/ansible/compose.yml" with { type: "text" };
import ansibleMain from "../resources/tools/ansible/main.yml" with { type: "text" };
import ansibleOwner from "../resources/tools/ansible/owner.py" with { type: "text" };
import dnsMainTf from "../resources/tools/dns/main.tf" with { type: "text" };
import infrastructureDigitaloceanTf from "../resources/tools/infrastructure/digitalocean/main.tf" with { type: "text" };
import infrastructureVultrTf from "../resources/tools/infrastructure/vultr/main.tf" with { type: "text" };

export const infrastructureTool = "posthog-infrastructure";
export const dnsTool = "posthog-dns";
export const ansibleTool = "posthog-ansible";
export const ansibleLocalTool = "posthog-ansible-local";

export const templateOpts = PRESERVE_JINJA_DELIMITERS;

export function toolDir(opts: Opts, tool: string): string {
  return stageDir(opts, tool, { defaultProfile: "posthog" });
}

// The template tree this colour carries, keyed the way green names its
// classpath resources: "<path>/<file>" with dots as directories.
const templates: Record<string, string> = {
  "ansible-local/ansible.cfg": ansibleLocalCfg,
  "ansible-local/inventory.ini": ansibleLocalInventory,
  "ansible-local/main.yml": ansibleLocalMain,
  "ansible/Caddyfile": ansibleCaddyfile,
  "ansible/ansible.cfg": ansibleCfg,
  "ansible/backup": ansibleBackup,
  "ansible/checkpoint.sql": ansibleCheckpoint,
  "ansible/cleanup.yml": ansibleCleanup,
  "ansible/compose.yml": ansibleCompose,
  "ansible/main.yml": ansibleMain,
  "ansible/owner.py": ansibleOwner,
  "dns/main.tf": dnsMainTf,
  "infrastructure/digitalocean/main.tf": infrastructureDigitaloceanTf,
  "infrastructure/vultr/main.tf": infrastructureVultrTf,
};

export function template(path: string, file: string): Template {
  const name = `${path.replaceAll(".", "/")}/${file}`;
  const content = templates[name];
  if (content === undefined) throw new StepError(`template not found: ${name}`);
  return { name, content };
}

function spec(source: Template, target: string, data: Opts): Spec {
  return { template: source, target, data, opts: templateOpts };
}

const rawSpec = (target: string, content: string): Spec => contentSpec(target, content);

export function cidrs(opts: Opts, k: string): string[] {
  return validate.cidrList(opts[k]);
}

export function credentialEnv(opts: Opts, ...slots: string[]): Record<string, string> | undefined {
  const merged: Record<string, string> = {};
  for (const slot of [...slots, "provider-backend"]) {
    Object.assign(merged, validate.tofuEnv(opts, slot));
  }
  const env: Record<string, string> = {};
  for (const [k, envVar] of Object.entries(merged)) {
    const v = String(opts[k] ?? "");
    if (v.length) env[envVar] = v;
  }
  return Object.keys(env).length ? env : undefined;
}

export function backendCredentialEnv(opts: Opts): Record<string, string> | undefined {
  return credentialEnv(opts);
}

export function fallbackParams(opts: Opts): Opts {
  return { ip: "192.0.2.10", user: "root", sudoer: "root", name: validate.computeName(opts) };
}

export function outputParams(result: Opts): Opts | undefined {
  const output = result["tofu/outputs"] as Record<string, unknown> | undefined;
  return output?.params as Opts | undefined;
}

// Template values for the compute stage. The source lists are read through
// `computeKey`, so the same data serves every provider's template.
export function infrastructureData(opts: Opts): Opts {
  const httpSources = cidrs(opts, validate.computeKey(opts, "http-sources"));
  return {
    ...opts,
    "ssh-keygen": validate.keygen(opts),
    "compute-name": validate.computeName(opts),
    "ssh-sources-hcl": tofu.hclList(cidrs(opts, validate.computeKey(opts, "ssh-sources"))),
    "http-sources-hcl": tofu.hclList(httpSources),
    // An empty http list means no public HTTP: the 80/443 rules are left out
    // rather than rendered with an empty source list, which the DigitalOcean
    // API rejects. Vultr's rules are a for_each over the set and vanish on
    // their own.
    "http-sources?": httpSources.length > 0,
  };
}

// Providers are selected by template directory, never by conditionals inside
// one file (Compute Provider Standard §3): `tools/infrastructure/<provider>/`.
export function infrastructureTemplate(opts: Opts): Template {
  return template(`infrastructure.${opts["provider-compute"]}`, "main.tf");
}

// Refuse to hand 192.0.2.10 to Ansible. That is the documentation address the
// credential-free build and dry-run paths render with; on a real converge a
// missing compute output must fail loudly rather than quietly point the whole
// playbook at TEST-NET.
export function resolvedCompute(result: Opts, fallback: Opts, outputs: Opts | undefined): Opts {
  if (outputs?.ip) return { ...result, ...fallback, ...outputs };
  return {
    ...result, "red/exit": 1,
    "red/err": "compute produced no ip output; refusing to converge " +
      "against the documentation address",
  };
}

export async function infrastructureStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, infrastructureTool);
  const specs = [spec(infrastructureTemplate(opts), `${dir}/main.tf`,
    infrastructureData(opts))];
  const result = await tofu.tofuWithSpec(opts, specs,
    { dir, env: credentialEnv(opts, "provider-compute") });
  if (failed(result)) return result;
  if (opts["red/event"] === "build") return { ...result, ...fallbackParams(opts) };
  if (opts["red/event"] === "delete") return result;
  return resolvedCompute(result, fallbackParams(opts), outputParams(result));
}

export function zoneId(_zone: unknown): string {
  return "${data.cloudflare_zone.zone.id}";
}

export function dnsJson(opts: Opts): string {
  return tofu.constructsJson(
    [tofu.construct("resource", "cloudflare_dns_record", "posthog",
      {
        zone_id: zoneId(opts["posthog-zone"]),
        name: opts["posthog-host"], content: opts.ip, type: "A",
        // Proxied by default: an unproxied record publishes the
        // droplet's address. This was hardcoded true, so a
        // cloudflare-proxied key in colors.yml was read by
        // nothing and changed nothing -- no effect, no error,
        // exit 0. Honour it, and keep the safe value as the
        // default. The application trusts forwarded addresses
        // through IS_BEHIND_PROXY, so client IPs survive the edge.
        proxied: opts["cloudflare-proxied"] != null
          ? Boolean(opts["cloudflare-proxied"])
          : true,
        ttl: 1,
      })]);
}

export async function dnsStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, dnsTool);
  const zone = opts["posthog-zone"] ?? utils.registrableDomain(opts["posthog-host"]);
  const data = {
    ...opts,
    ip: opts.ip ?? fallbackParams(opts).ip,
    "posthog-zone": zone,
  };
  const specs = [spec(template("dns", "main.tf"), `${dir}/main.tf`, data),
    rawSpec(`${dir}/record.tf.json`, dnsJson(data))];
  return tofu.tofuWithSpec(opts, specs, { dir, env: credentialEnv(opts, "provider-dns") });
}

// Java's Double.toString, which is what Cheshire renders floats through and
// therefore what green's committed inventory bytes would carry. Integral
// numbers print as longs. JS's shortest-round-trip digits are the same digits
// Java chooses; only the layout differs.
function javaNumber(value: number): string {
  if (Number.isInteger(value)) return String(value);
  const negative = value < 0;
  const [mantissa, exponentPart] = Math.abs(value).toExponential().split("e");
  const exponent = Number(exponentPart);
  const digits = mantissa!.replace(".", "");
  let body: string;
  if (exponent >= -3 && exponent < 7) {
    if (exponent >= 0) {
      const intPart = digits.padEnd(exponent + 1, "0").slice(0, exponent + 1);
      const fracPart = digits.slice(exponent + 1);
      body = `${intPart}.${fracPart.length > 0 ? fracPart : "0"}`;
    } else {
      body = `0.${"0".repeat(-exponent - 1)}${digits}`;
    }
  } else {
    const rest = digits.slice(1);
    body = `${digits[0]}.${rest.length > 0 ? rest : "0"}E${exponent}`;
  }
  return negative ? `-${body}` : body;
}

// Cheshire's pretty printer, byte for byte: spaces around colons, arrays
// inline, nested objects newline-indented, floats in Java notation.
function pretty(value: unknown, indent = 0): string {
  if (Array.isArray(value)) {
    if (value.length === 0) return "[ ]";
    return `[ ${value.map((item) => pretty(item, indent)).join(", ")} ]`;
  }
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) return "{ }";
    const pad = " ".repeat(indent + 2);
    return `{\n${entries
      .map(([key, nested]) => `${pad}${JSON.stringify(key)} : ${pretty(nested, indent + 2)}`)
      .join(",\n")}\n${" ".repeat(indent)}}`;
  }
  if (typeof value === "number") return javaNumber(value);
  return JSON.stringify(value ?? null);
}

// --- ~/.ssh/config (local) ---------------------------------------------------

// Only what a `build` genuinely knows. The address, the user and the alias are
// run-time facts and reach the play as extra-vars instead, so the rendered
// playbook carries no IP and is identical on every workstation (SSH Config
// Standard §6).
export function ansibleLocalData(opts: Opts): Opts {
  return {
    ...opts,
    "ssh-keygen": validate.keygen(opts),
    "ssh-config-identity-file": sshConfig.identityFile(opts),
  };
}

export function ansibleLocalSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleLocalTool);
  const data = ansibleLocalData(opts);
  return [
    spec(template("ansible-local", "ansible.cfg"), `${dir}/ansible.cfg`, data),
    spec(template("ansible-local", "inventory.ini"), `${dir}/inventory.ini`, data),
    spec(template("ansible-local", "main.yml"), `${dir}/main.yml`, data),
  ];
}

// Write or remove the `~/.ssh/config` block. The same playbook serves both
// events; `block_state` is what distinguishes them.
export async function ansibleLocalStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, ansibleLocalTool);
  const isDelete = opts["red/event"] === "delete";
  return ansible.ansibleWithSpec(opts, {
    dir,
    inventory: "inventory.ini",
    playbooks: { create: "main.yml", delete: "main.yml" },
    extraVars: {
      host_alias: sshConfig.hostAlias(opts),
      ip: opts.ip ?? fallbackParams(opts).ip,
      user: opts.user ?? "root",
      block_state: isDelete ? "absent" : "present",
    },
  }, ansibleLocalSpecs(opts));
}

// --- Ansible -----------------------------------------------------------------

export function inventory(opts: Opts): string {
  return pretty({
    all: {
      children: {
        posthog: {
          hosts: {
            [String(opts.profile)]: {
              ansible_host: opts.ip ?? "192.0.2.10",
              ansible_user: "root",
            },
          },
        },
      },
    },
  });
}

// Template values for the Ansible stage. `ssh-private-key-path` reaches
// ansible.cfg so convergence uses the deployment's own key in keygen mode,
// where nothing guarantees an agent holds it.
export function ansibleData(opts: Opts): Opts {
  return {
    ...opts,
    ip: opts.ip ?? "192.0.2.10",
    "ssh-keygen": validate.keygen(opts),
    "posthog-web-port": opts["posthog-web-port"] ?? 8000,
    "posthog-backup-access-key": "{{ lookup('env','COLORS_PAR_POSTHOG_BACKUP_R2_ACCESS_KEY_ID') }}",
    "posthog-backup-secret-key": "{{ lookup('env','COLORS_PAR_POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY') }}",
  };
}

export function ansibleSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleTool);
  const data = ansibleData(opts);
  return [
    spec(template("ansible", "ansible.cfg"), `${dir}/ansible.cfg`, data),
    spec(template("ansible", "main.yml"), `${dir}/main.yml`, data),
    spec(template("ansible", "cleanup.yml"), `${dir}/cleanup.yml`, data),
    spec(template("ansible", "compose.yml"), `${dir}/compose.yml`, data),
    spec(template("ansible", "Caddyfile"), `${dir}/Caddyfile`, data),
    spec(template("ansible", "backup"), `${dir}/backup`, data),
    spec(template("ansible", "checkpoint.sql"), `${dir}/checkpoint.sql`, data),
    spec(template("ansible", "owner.py"), `${dir}/owner.py`, data),
    rawSpec(`${dir}/inventory.json`, inventory(data)),
  ];
}

export async function ansibleStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, ansibleTool);
  if (opts["red/event"] === "delete" && !opts.ip) {
    // No compute in state: there is no host to clean up, and the rendered
    // inventory would fall back to 192.0.2.10. Remove the rendered tree the
    // way a completed cleanup would and let the teardown continue.
    return {
      ...scaffold(opts, ansibleSpecs(opts)),
      "red/exit": 0, "posthog/cleanup": "skipped-no-compute",
    };
  }
  return ansible.ansibleWithSpec(opts, {
    dir, inventory: "inventory.json",
    playbooks: { create: "main.yml", delete: "cleanup.yml" },
    hostKeyChecking: false,
  }, ansibleSpecs(opts));
}

// --- Acceptance --------------------------------------------------------------
//
// Every claim this step reports must be one it checked. TLS is verified (the
// previous check passed `curl -k`, so a broken certificate would have gone
// unnoticed), a captured event is read back out of ClickHouse rather than
// inferred from a status code, and the backup drill is confirmed by a fresh
// object in R2 rather than by systemd reporting that it started something.

function parseLong(s: unknown): number | undefined {
  return typeof s === "string" && /^[+-]?\d+$/.test(s) ? Number(s) : undefined;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export async function httpStatus(args: string[]): Promise<string | undefined> {
  const r = await runtime.exec(
    ["curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}", ...args],
    { timeoutMs: 20000 });
  return r.exit === 0 ? r.out.trim() : undefined;
}

// Run `command` on the instance over ssh. In keygen mode the deployment's own
// key is selected explicitly (`ssh.identityArgs`), because nothing guarantees
// an agent holds it.
export async function sshOut(opts: Opts, command: string, timeout: number): Promise<string | undefined> {
  const r = await runtime.exec(
    ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=10",
      ...ssh.identityArgs(opts), `root@${opts.ip}`, command], { timeoutMs: timeout });
  return r.exit === 0 ? r.out.trim() : undefined;
}

export async function psql(opts: Opts, query: string): Promise<string | undefined> {
  const s = String((await sshOut(opts, "cd /opt/posthog && docker compose exec -T db psql -U posthog" +
    ` -d posthog -tAc '${query}'`, 30000)) ?? "");
  return s.length ? s : undefined;
}

// Resolve the events table from system.tables so the check does not hardcode a
// database name PostHog's migrations own, then run `query` against it.
export async function clickhouse(opts: Opts, query: string): Promise<string | undefined> {
  const s = String((await sshOut(opts, "cd /opt/posthog && " +
    "t=$(docker compose exec -T clickhouse clickhouse-client" +
    " --query \"SELECT database || '.' || name FROM system.tables" +
    " WHERE name = 'events' AND database NOT IN ('system')" +
    " ORDER BY database LIMIT 1\" | tr -d '\\r'); " +
    "[ -n \"$t\" ] && docker compose exec -T clickhouse clickhouse-client" +
    ` --query "${query}"`, 30000)) ?? "");
  return s.length ? s : undefined;
}

export async function eventCount(opts: Opts): Promise<number | undefined> {
  return parseLong(await clickhouse(opts, "SELECT count() FROM $t"));
}

export async function projectApiKey(opts: Opts): Promise<string | undefined> {
  return psql(opts, "select api_token from posthog_team order by id limit 1");
}

export async function waitHealth(url: string, attempts: number): Promise<boolean> {
  for (let n = attempts; ; n -= 1) {
    const r = await runtime.exec(["curl", "-fsS", `${url}/_health/`], { timeoutMs: 10000 });
    if (r.exit === 0) return true;
    if (n <= 0) return false;
    await sleep(5000);
  }
}

export async function sendEvent(base: string, apiKey: string): Promise<string | undefined> {
  return httpStatus(["-X", "POST", "-H", "content-type: application/json",
    "--data", JSON.stringify({
      api_key: apiKey,
      event: "colors_acceptance",
      distinct_id: "colors-acceptance",
      properties: { source: "colors" },
    }),
    `${base}/capture/`]);
}

export function ingestionVerdict(status: unknown, before: unknown, after: unknown): string {
  if (status == null) return "unreachable";
  if (typeof before === "number" && Number.isInteger(before) &&
      typeof after === "number" && Number.isInteger(after) && after > before) {
    return "ingested";
  }
  if (/^2\d\d$/.test(String(status))) return "dropped";
  return "rejected";
}

// Capture is asynchronous through the Celery worker, so poll rather than
// sampling once.
export async function waitIngested(opts: Opts, baseline: number, attempts: number): Promise<number | undefined> {
  for (let n = attempts; ; n -= 1) {
    const after = await eventCount(opts);
    if (typeof after === "number" && after > baseline) return after;
    if (n <= 0) return after;
    await sleep(5000);
  }
}

export const rcloneEnv =
  "RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare " +
  "RCLONE_CONFIG_R2_REGION=auto RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true";

export interface BackupEntry { Size?: number; ModTime?: string }

export async function backupListing(opts: Opts): Promise<BackupEntry[] | undefined> {
  const out = await sshOut(opts, "set -a; . /etc/posthog-backup.env; set +a; " + rcloneEnv +
    " RCLONE_CONFIG_R2_ACCESS_KEY_ID=\"$POSTHOG_BACKUP_R2_ACCESS_KEY_ID\"" +
    " RCLONE_CONFIG_R2_SECRET_ACCESS_KEY=\"$POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY\"" +
    ` RCLONE_CONFIG_R2_ENDPOINT="${opts["posthog-backup-r2-endpoint"]}"` +
    ` rclone lsjson --files-only r2:${opts["posthog-backup-r2-bucket"]}` +
    `/${opts.profile}`, 120000);
  if (out == null || !out.length) return undefined;
  return JSON.parse(out) as BackupEntry[];
}

// Epoch milliseconds, or undefined — the port of green's OffsetDateTime parse,
// which requires an explicit offset and answers nil for anything else.
export function parseInstant(s: unknown): number | undefined {
  if (typeof s !== "string" || !/(?:Z|[+-]\d\d:?\d\d)$/.test(s)) return undefined;
  const t = Date.parse(s);
  return Number.isNaN(t) ? undefined : t;
}

export function freshBackup(entries: BackupEntry[] | undefined, since: number): boolean {
  return Boolean(entries?.some(({ Size, ModTime }) => {
    if (!((Size ?? 0) > 0)) return false;
    const t = parseInstant(ModTime);
    return t !== undefined && t >= since;
  }));
}

export async function runBackup(opts: Opts): Promise<string | undefined> {
  return sshOut(opts, "systemctl start posthog-backup.service && systemctl is-active posthog-backup.timer",
    600000);
}

// PostHog's own answers, not ours: whether Celery is alive, and whether any
// async migration is still pending. A pending one stops the worker starting at
// all, and the ingestion path this step already exercises never touches Celery
// -- so background jobs can be entirely dead while capture works.
export async function backgroundJobs(opts: Opts): Promise<string | undefined> {
  return sshOut(opts, "cd /opt/posthog && docker compose exec -T web python manage.py shell -c " +
    "\"from posthog.utils import is_celery_alive; " +
    "from posthog.models.async_migration import AsyncMigration; " +
    "print('celery=%s pending=%d' % (is_celery_alive(), " +
    "AsyncMigration.objects.exclude(status=2).count()))\"",
    120000);
}

export function backgroundVerdict(out: unknown): string {
  const s = out == null ? "" : String(out);
  if (!s.trim()) return "unreachable";
  if (!/celery=True/.test(s)) return "celery-down";
  if (!/pending=0\b/.test(s)) return "migrations-pending";
  return "ok";
}

export async function acceptanceStep(opts: Opts): Promise<Opts> {
  if (opts["red/event"] !== "create") return { ...opts, "red/exit": 0 };
  const base = `https://${opts["posthog-host"]}`;
  const since = Date.now() - 120000;
  if (!(await waitHealth(base, 60))) {
    return {
      ...opts, "red/exit": 1,
      "red/err": "HTTPS health did not become ready with a valid certificate",
    };
  }
  const apiKey = await projectApiKey(opts);
  const before = await eventCount(opts);
  if (typeof before !== "number") {
    return {
      ...opts, "red/exit": 1,
      "red/err": "could not read the ClickHouse events table to verify capture",
    };
  }
  const verdict = !apiKey
    ? "not-configured"
    : ingestionVerdict(await sendEvent(base, apiKey), before, await waitIngested(opts, before, 12));
  const background = backgroundVerdict(await backgroundJobs(opts));
  if (["dropped", "rejected", "unreachable"].includes(verdict)) {
    return {
      ...opts, "red/exit": 1,
      "red/err": `synthetic event was not captured: ${verdict}`,
    };
  }
  if (background !== "ok") {
    return {
      ...opts, "red/exit": 1,
      "red/err": `background jobs are not healthy: ${background}`,
    };
  }
  if ((await runBackup(opts)) == null) {
    return { ...opts, "red/exit": 1, "red/err": "backup unit or timer is not healthy" };
  }
  if (!freshBackup(await backupListing(opts), since)) {
    return {
      ...opts, "red/exit": 1,
      "red/err": `no backup object newer than this run under r2:${opts["posthog-backup-r2-bucket"]}/${opts.profile}`,
    };
  }
  return {
    ...opts, "red/exit": 0,
    "posthog/acceptance": {
      health: "ok", event: verdict,
      background: "ok",
      backup: "verified-in-r2",
    },
  };
}
