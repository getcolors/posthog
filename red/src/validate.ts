// Desired-state and credential validation, the port of
// io.github.getcolors.posthog.validate.
//
// Green renders its keys as Clojure keywords, so every message here carries the
// same leading colon — the three colours must report identical errors for one
// colors.yml.

import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { providers } from "package-once-red";

export { providers };

export const profilePar = parName("profile");

export const required = [
  "profile", "workdir", "provider-compute", "provider-dns", "provider-backend",
  "compute-prevent-destroy", "posthog-host", "posthog-admin-email", "posthog-image",
  "posthog-postgres-image", "posthog-clickhouse-image", "posthog-redis-image",
  "posthog-kafka-image", "posthog-temporal-image", "posthog-capture-image", "posthog-plugin-server-image", "caddy-image",
  "posthog-postgres-data-dir", "posthog-clickhouse-data-dir", "posthog-redis-data-dir",
  "posthog-kafka-data-dir",
  "posthog-backup-dir", "posthog-backup-r2-bucket", "posthog-backup-r2-endpoint",
  "posthog-backup-r2-region", "posthog-backup-oncalendar", "posthog-backup-retention-days",
  "digitalocean-name", "digitalocean-region", "digitalocean-size", "digitalocean-image",
  "digitalocean-ssh-keys", "digitalocean-ssh-sources", "digitalocean-http-sources",
  "r2-bucket", "r2-endpoint",
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

export function envErrors(env: Record<string, string | undefined>): string[] {
  return String(env[profilePar] ?? "").length
    ? [`${profilePar} is set; profile must come from colors.yml only`]
    : [];
}

function positiveInt(x: unknown): boolean {
  return typeof x === "number" && Number.isInteger(x) && x > 0;
}

export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const k of required) {
    if (missing(opts[k])) errors.push(`:${k} is required`);
  }
  if (opts["provider-compute"] !== "digitalocean") {
    errors.push(":provider-compute must be digitalocean");
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
  if ("digitalocean-vpc-uuid" in opts) {
    errors.push(":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime");
  }
  if ("digitalocean-vpc-cidr" in opts) {
    errors.push(":digitalocean-vpc-cidr must be absent; this package must not create a VPC");
  }
  return errors;
}

interface ProviderEntry {
  required?: string[];
  secrets?: string[];
  tofuEnv?: Record<string, string>;
}

function backendEntry(opts: Opts): ProviderEntry | undefined {
  return (providers as Record<string, Record<string, ProviderEntry>>)["provider-backend"]?.[String(opts["provider-backend"])];
}

export function backendSecrets(opts: Opts): string[] {
  return backendEntry(opts)?.secrets ?? [];
}

export function secretErrors(opts: Opts): string[] {
  const keys = ["do-token", "cloudflare-api-token",
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
    case "provider-compute": return { "do-token": "DIGITALOCEAN_TOKEN" };
    case "provider-dns": return { "cloudflare-api-token": "CLOUDFLARE_API_TOKEN" };
    case "provider-backend": return backendEntry(opts)?.tofuEnv ?? {};
    default: return {};
  }
}
