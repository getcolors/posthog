// Desired-state and credential validation, the port of
// io.github.getcolors.posthog.validate.
//
// Green renders its keys as Clojure keywords, so every message here carries the
// same leading colon — the three colours must report identical errors for one
// colors.yml.

import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { providers } from "package-once-red";
import * as compute from "./compute.ts";
import {keyMode} from "colors-compute-red";

export { providers };

export const profilePar = parName("profile");

// The shape of a state-backend registry entry in ONCE's `providers`.
interface BackendEntry {
  required?: string[];
  secrets?: string[];
  tofuEnv?: Record<string, string>;
}

export const defaultComputeProvider="digitalocean";

export const required = [
  "profile", "workdir", "provider-compute", "provider-dns", "provider-backend",
  "compute-prevent-destroy", "posthog-host", "posthog-admin-email", "posthog-image",
  "posthog-postgres-image", "posthog-clickhouse-image", "posthog-redis-image",
  "posthog-kafka-image", "posthog-temporal-image", "posthog-capture-image", "posthog-plugin-server-image", "caddy-image",
  "posthog-postgres-data-dir", "posthog-clickhouse-data-dir", "posthog-redis-data-dir",
  "posthog-kafka-data-dir",
  "posthog-backup-dir", "posthog-backup-r2-bucket", "posthog-backup-r2-endpoint",
  "posthog-backup-r2-region", "posthog-backup-oncalendar", "posthog-backup-retention-days",
];

const hostRe = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/;
// name:tag, name@sha256:..., or name:tag@sha256:... A digest is the only
// pin that cannot move under the deployment, so validation must accept it.
const imageRe = /^[^\s:@]+(?:\/[^\s:@]+)*(?::[^\s:@]+|(?::[^\s:@]+)?@sha256:[0-9a-f]{64})$/;

export const imageKeys = [
  "posthog-image", "posthog-postgres-image", "posthog-clickhouse-image",
  "posthog-redis-image", "posthog-kafka-image", "posthog-temporal-image", "posthog-capture-image", "posthog-plugin-server-image", "caddy-image",
];

export function missing(x: unknown): boolean {
  return x == null || (typeof x === "string" && !x.trim());
}

// `<provider>-<suffix>`: desired state names compute keys after the provider,
// so the shared steps reach them through the selected provider rather than a
// fixed prefix. ONCE's; named here so `tools` reads the same.
export function keygen(opts:Opts):boolean{try{return keyMode(opts).mode==='managed';}catch{return true;}}

export function envErrors(env: Record<string, string | undefined>): string[] {
  return String(env[profilePar] ?? "").length
    ? [`${profilePar} is set; profile must come from colors.yml only`]
    : [];
}

function positiveInt(x: unknown): boolean {
  return typeof x === "number" && Number.isInteger(x) && x > 0;
}

// A source list as desired state or an overlay string carries it. ONCE's, so
// the validator and the templates can never disagree about what an entry is.

// Every problem with desired state at once: the missing keys (this package's
// and the selected provider's), the package's own checks, then the Compute
// Provider Standard's — selection, the network contract and the provider
// rules — which are ONCE's over `spec`.
export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const k of required) {
    if (missing(opts[k])) errors.push(`:${k} is required`);
  }
  if (opts["provider-dns"] !== "cloudflare") {
    errors.push(":provider-dns must be cloudflare");
  }
  if (!["s3", "r2"].includes(String(opts["provider-backend"]))) {
    errors.push(":provider-backend must be s3 or r2");
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
  errors.push(...compute.errors(opts));
  return errors;
}

function backendEntry(opts: Opts): BackendEntry | undefined {
  return (providers as Record<string, Record<string, BackendEntry>>)["provider-backend"]?.[String(opts["provider-backend"])];
}

export function backendSecrets(opts: Opts): string[] {
  return backendEntry(opts)?.secrets ?? [];
}

export function secretErrors(opts: Opts): string[] {
  const keys = [
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
    case "provider-compute": return {};
    case "provider-dns": return { "cloudflare-api-token": "CLOUDFLARE_API_TOKEN" };
    case "provider-backend": return backendEntry(opts)?.tofuEnv ?? {};
    default: return {};
  }
}
