// Desired-state and credential validation, the port of
// io.github.getcolors.posthog.validate.
//
// Green renders its keys as Clojure keywords, so every message here carries the
// same leading colon — the three colours must report identical errors for one
// colors.yml.

import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { providers } from "package-once-red";
import { onceSsh } from "./once.ts";

export { providers };

export const profilePar = parName("profile");

interface ProviderEntry {
  required?: string[];
  secrets?: string[];
  tofuEnv?: Record<string, string>;
}

// provider-compute -> what that choice implies (Compute Provider Standard §2).
//
// `required` are the non-secret keys that provider's template interpolates,
// `secrets` the credentials it needs through COLORS_PAR_*, and `tofuEnv` the
// subset OpenTofu reads from the process environment itself. Keeping the three
// together is what stops a provider being validated against one set of keys
// and run with another — a stage exporting a credential nobody checked for, or
// a check demanding a key no template uses. The keys of this map are the
// advertised providers; a provider without a template directory and a golden
// is not advertised.
//
// Two keys are deliberately absent from every entry: `<provider>-ssh-keys`,
// because per the SSH Keypair Standard its *absence* selects keygen mode, and
// `<provider>-name`, because per the Compute Name Standard the profile is the
// default and the key is only an override. Requiring either would make
// conforming deployments invalid. Keys of an unselected provider are accepted
// and ignored, so one colors.yml stays portable.
export const computeProviders: Record<string, ProviderEntry> = {
  digitalocean: {
    required: ["digitalocean-region", "digitalocean-size", "digitalocean-image",
               "digitalocean-ssh-sources", "digitalocean-http-sources"],
    secrets: ["do-token"],
    tofuEnv: { "do-token": "DIGITALOCEAN_TOKEN" },
  },
  vultr: {
    required: ["vultr-region", "vultr-plan", "vultr-os-id",
               "vultr-ssh-sources", "vultr-http-sources"],
    secrets: ["vultr-api-key"],
    tofuEnv: { "vultr-api-key": "VULTR_API_KEY" },
  },
};

// The provider a deployment created before `params.provider` was recorded is
// assumed to run: every such deployment was created on DigitalOcean, the only
// provider this package had.
export const defaultComputeProvider = "digitalocean";

export const required = [
  "profile", "workdir", "provider-compute", "provider-dns", "provider-backend",
  "compute-prevent-destroy", "posthog-host", "posthog-admin-email", "posthog-image",
  "posthog-postgres-image", "posthog-clickhouse-image", "posthog-redis-image",
  "posthog-kafka-image", "posthog-temporal-image", "posthog-capture-image", "posthog-plugin-server-image", "caddy-image",
  "posthog-postgres-data-dir", "posthog-clickhouse-data-dir", "posthog-redis-data-dir",
  "posthog-kafka-data-dir",
  "posthog-backup-dir", "posthog-backup-r2-bucket", "posthog-backup-r2-endpoint",
  "posthog-backup-r2-region", "posthog-backup-oncalendar", "posthog-backup-retention-days",
  "r2-bucket", "r2-endpoint",
];

const hostRe = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/;
// name:tag, name@sha256:..., or name:tag@sha256:... A digest is the only
// pin that cannot move under the deployment, so validation must accept it.
const imageRe = /^[^\s:@]+(?:\/[^\s:@]+)*(?::[^\s:@]+|(?::[^\s:@]+)?@sha256:[0-9a-f]{64})$/;

// Provider naming rules for the compute name override. DigitalOcean droplet
// names are hostname-like: lowercase letters, digits, dots and hyphens, up to
// 63 characters, starting and ending alphanumeric. Vultr labels are only a
// console string: letters of either case, digits, dot, underscore and hyphen,
// up to 63 characters.
const computeNameRes: Record<string, RegExp> = {
  digitalocean: /^[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?$/,
  vultr: /^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$/,
};

export const imageKeys = [
  "posthog-image", "posthog-postgres-image", "posthog-clickhouse-image",
  "posthog-redis-image", "posthog-kafka-image", "posthog-temporal-image", "posthog-capture-image", "posthog-plugin-server-image", "caddy-image",
];

export function missing(x: unknown): boolean {
  return x == null || (typeof x === "string" && !x.trim());
}

// Absent, blank or REPLACE_ME all mean 'use the profile' (Compute Name
// Standard §2: presence is the only switch).
export function placeholder(value: unknown): boolean {
  return missing(value) || String(value).trim() === "REPLACE_ME";
}

export function computeProvider(opts: Opts): ProviderEntry | undefined {
  return computeProviders[String(opts["provider-compute"])];
}

// The selected provider's key for `suffix`: `digitalocean-ssh-sources`,
// `vultr-name`, and so on. Provider keys stay provider-scoped so an existing
// colors.yml keeps meaning what it meant.
export function computeKey(opts: Opts, suffix: string): string {
  return `${opts["provider-compute"]}-${suffix}`;
}

// What this deployment calls its machine. The one function that answers it —
// every label, including the firewall's, derives from this and never from the
// raw override key or a second copy of the profile (§3).
export function computeName(opts: Opts): string {
  const override = opts[computeKey(opts, "name")];
  return placeholder(override) ? String(opts.profile ?? "") : String(override).trim();
}

// Whether this deployment owns its machine keypair. Delegates to ONCE, the
// standard's reference implementation, so one rule decides it everywhere.
export function keygen(opts: Opts): boolean {
  return onceSsh.keygen(opts);
}

export function envErrors(env: Record<string, string | undefined>): string[] {
  return String(env[profilePar] ?? "").length
    ? [`${profilePar} is set; profile must come from colors.yml only`]
    : [];
}

function positiveInt(x: unknown): boolean {
  return typeof x === "number" && Number.isInteger(x) && x > 0;
}

// --- CIDR syntax (Compute Provider Standard §5) ------------------------------
//
// Hand-rolled rather than delegated to a runtime library so the three colours
// accept exactly the same set of strings: an address that one colour's parser
// tolerates and another's rejects would be a parity bug at the firewall.

function ipv4Address(s: string): boolean {
  const parts = s.split(".");
  return parts.length === 4 &&
    parts.every((part) => /^\d{1,3}$/.test(part) && Number(part) <= 255);
}

function ipv6Address(s: string): boolean {
  const halves = s.split("::");
  const groups = (half: string) => (half === "" ? [] : half.split(":"));
  const hexGroup = (g: string) => /^[0-9A-Fa-f]{1,4}$/.test(g);
  // An embedded dotted quad may close the address: ::ffff:192.0.2.10.
  const embedded = (gs: string[]) => gs.length > 0 && ipv4Address(gs[gs.length - 1]!);
  const countGroups = (gs: string[]) => (embedded(gs) ? gs.length + 1 : gs.length);
  const wellFormed = (gs: string[]) => (embedded(gs) ? gs.slice(0, -1) : gs).every(hexGroup);
  if (halves.length === 1) {
    const gs = groups(s);
    return wellFormed(gs) && countGroups(gs) === 8;
  }
  if (halves.length === 2) {
    const a = groups(halves[0]!);
    const b = groups(halves[1]!);
    return wellFormed(a) && wellFormed(b) && !embedded(a) &&
      countGroups(a) + countGroups(b) < 8;
  }
  return false;
}

// Whether `s` is a syntactically valid IPv4 or IPv6 CIDR: an address, a slash,
// and a prefix length within the family's range.
export function cidr(s: unknown): boolean {
  const parts = String(s).split("/");
  if (parts.length !== 2) return false;
  const [addr, prefix] = parts as [string, string];
  if (!/^\d{1,3}$/.test(prefix)) return false;
  const n = Number(prefix);
  return (ipv4Address(addr) && n <= 32) || (ipv6Address(addr) && n <= 128);
}

// The entries of a source list, whether desired state supplied a YAML list or
// an overlay string.
export function cidrList(v: unknown): string[] {
  const xs = Array.isArray(v) ? v : String(v ?? "").split(/[,\s]+/);
  return xs.map((x) => String(x).trim()).filter((x) => x.length > 0);
}

// The network contract: `<provider>-ssh-sources` must reach someone, and every
// entry of both lists must be a CIDR — before any provider call. An empty
// `<provider>-http-sources` is allowed and means no public HTTP.
function sourceErrors(opts: Opts): string[] {
  if (!computeProvider(opts)) return [];
  const sshKey = computeKey(opts, "ssh-sources");
  const httpKey = computeKey(opts, "http-sources");
  const errors: string[] = [];
  if (!missing(opts[sshKey]) && cidrList(opts[sshKey]).length === 0) {
    errors.push(`:${sshKey} must list at least one CIDR; an empty list is a machine no one can reach`);
  }
  for (const key of [sshKey, httpKey]) {
    if (missing(opts[key])) continue;
    for (const entry of cidrList(opts[key])) {
      if (!cidr(entry)) errors.push(`:${key} entry is not an IPv4 or IPv6 CIDR: ${entry}`);
    }
  }
  return errors;
}

// Checks that only make sense for the selected provider. Keys of an unselected
// provider are never read.
function providerErrors(opts: Opts): string[] {
  const errors: string[] = [];
  switch (opts["provider-compute"]) {
    case "digitalocean":
      if ("digitalocean-vpc-uuid" in opts) {
        errors.push(":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime");
      }
      if ("digitalocean-vpc-cidr" in opts) {
        errors.push(":digitalocean-vpc-cidr must be absent; this package must not create a VPC");
      }
      break;
    case "vultr": {
      const osId = opts["vultr-os-id"];
      if (!(missing(osId) || (typeof osId === "number" && Number.isInteger(osId)))) {
        errors.push(":vultr-os-id must be Vultr's numeric operating-system id");
      }
      break;
    }
    default:
      break;
  }
  return errors;
}

export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const k of [...required, ...(computeProvider(opts)?.required ?? [])]) {
    if (missing(opts[k])) errors.push(`:${k} is required`);
  }
  if (!computeProvider(opts)) {
    errors.push(`:provider-compute must be one of ${Object.keys(computeProviders).sort().join(", ")}`);
  }
  if (opts["provider-dns"] !== "cloudflare") {
    errors.push(":provider-dns must be cloudflare");
  }
  if (!["local", "s3", "r2"].includes(String(opts["provider-backend"]))) {
    errors.push(":provider-backend must be local, s3, or r2");
  }
  if (typeof opts["compute-prevent-destroy"] !== "boolean") {
    errors.push(":compute-prevent-destroy must be true or false");
  }
  if (!(missing(opts["posthog-host"]) || hostRe.test(String(opts["posthog-host"])))) {
    errors.push(":posthog-host must be a fully qualified hostname");
  }
  for (const k of imageKeys) {
    const v = opts[k];
    if (!missing(v) && !imageRe.test(String(v))) {
      errors.push(`:${k} must carry an explicit image tag`);
    }
  }
  for (const k of ["posthog-backup-retention-days"]) {
    if (!missing(opts[k]) && !positiveInt(opts[k])) {
      errors.push(`:${k} must be a positive integer`);
    }
  }
  const nameRe = computeNameRes[String(opts["provider-compute"])];
  if (nameRe && !(placeholder(opts[computeKey(opts, "name")]) || nameRe.test(computeName(opts)))) {
    errors.push(`:${computeKey(opts, "name")} must be a valid ${opts["provider-compute"]} machine name`);
  }
  errors.push(...sourceErrors(opts));
  errors.push(...providerErrors(opts));
  return errors;
}

// Provider switching is a rebuild, never an apply (Compute Provider Standard
// §4). All providers share one state key, so a changed provider-compute on a
// profile with compute in state would plan a cross-provider replacement.
// `recorded` is the compute stage's applied `params` (undefined when no state
// is readable): a recorded provider that differs from the selected one
// refuses, and params without a provider — a deployment created before
// adoption — are accepted only for the package default. Pure, so the read
// stays with the lifecycle and the rule is testable without a backend.
export function providerStateErrors(opts: Opts, recorded: Opts | undefined): string[] {
  if (!recorded) return [];
  const selected = opts["provider-compute"];
  const held = String(recorded.provider ?? "") || defaultComputeProvider;
  return held === selected
    ? []
    : [`state holds a ${held} machine; set provider-compute back to ${held} and delete first`];
}

function backendEntry(opts: Opts): ProviderEntry | undefined {
  return (providers as Record<string, Record<string, ProviderEntry>>)["provider-backend"]?.[String(opts["provider-backend"])];
}

export function backendSecrets(opts: Opts): string[] {
  return backendEntry(opts)?.secrets ?? [];
}

export function secretErrors(opts: Opts): string[] {
  const keys = [...(computeProvider(opts)?.secrets ?? []),
    "cloudflare-api-token",
    // The compose template interpolates these at run time and
    // carries no fallback; the Django signing key in
    // particular must never be a value published here.
    "posthog-secret-key", "posthog-postgres-password",
    "posthog-oidc-rsa-private-key",
    "posthog-encryption-salt-keys",
    "posthog-admin-password",
    "posthog-backup-r2-access-key-id",
    "posthog-backup-r2-secret-access-key",
    ...backendSecrets(opts)];
  return [...new Set(keys)]
    .filter((k) => missing(opts[k]))
    .map((k) => `required credential is not set: ${parName(k)}`);
}

export function tofuEnv(opts: Opts, slot: string): Record<string, string> {
  switch (slot) {
    case "provider-compute": return computeProvider(opts)?.tofuEnv ?? {};
    case "provider-dns": return { "cloudflare-api-token": "CLOUDFLARE_API_TOKEN" };
    case "provider-backend": return backendEntry(opts)?.tofuEnv ?? {};
    default: return {};
  }
}
