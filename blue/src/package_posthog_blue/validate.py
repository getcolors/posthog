"""Desired-state and credential validation, the port of
io.github.getcolors.posthog.validate.

Green renders its keys as Clojure keywords, so every message here carries the
same leading colon — the three colours must report identical errors for one
colors.yml.
"""

from __future__ import annotations

import re

from blue.cli import par_name
from package_once_blue import ssh as once_ssh
from package_once_blue.validate import providers

__all__ = ["providers"]

profile_par = par_name("profile")

# Every key desired state must carry. Two DigitalOcean keys are deliberately
# absent: `digitalocean-ssh-keys`, because per the SSH Keypair Standard its
# *absence* selects keygen mode, and `digitalocean-name`, because per the
# Compute Name Standard the profile is the default and the key is only an
# override. Requiring either would make conforming deployments invalid.
required = [
    "profile", "workdir", "provider-compute", "provider-dns", "provider-backend",
    "compute-prevent-destroy", "posthog-host", "posthog-admin-email", "posthog-image",
    "posthog-postgres-image", "posthog-clickhouse-image", "posthog-redis-image",
    "posthog-kafka-image", "posthog-temporal-image", "posthog-capture-image",
    "posthog-plugin-server-image", "caddy-image",
    "posthog-postgres-data-dir", "posthog-clickhouse-data-dir", "posthog-redis-data-dir",
    "posthog-kafka-data-dir",
    "posthog-backup-dir", "posthog-backup-r2-bucket", "posthog-backup-r2-endpoint",
    "posthog-backup-r2-region", "posthog-backup-oncalendar", "posthog-backup-retention-days",
    "digitalocean-region", "digitalocean-size", "digitalocean-image",
    "digitalocean-ssh-sources", "digitalocean-http-sources",
    "r2-bucket", "r2-endpoint",
]

# DigitalOcean droplet names: letters, digits, dots and hyphens, up to 63
# characters, starting and ending alphanumeric.
_digitalocean_name_re = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9.-]{0,61}[A-Za-z0-9])?")

_host_re = re.compile(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
# name:tag, name@sha256:..., or name:tag@sha256:... A digest is the only
# pin that cannot move under the deployment, so validation must accept it.
_image_re = re.compile(
    r"[^\s:@]+(?:/[^\s:@]+)*(?::[^\s:@]+|(?::[^\s:@]+)?@sha256:[0-9a-f]{64})")

image_keys = [
    "posthog-image", "posthog-postgres-image", "posthog-clickhouse-image",
    "posthog-redis-image", "posthog-kafka-image", "posthog-temporal-image",
    "posthog-capture-image", "posthog-plugin-server-image", "caddy-image",
]


def missing(x) -> bool:
    return x is None or (isinstance(x, str) and not x.strip())


def placeholder(value) -> bool:
    """Absent, blank or REPLACE_ME all mean 'use the profile' (Compute Name
    Standard §2: presence is the only switch)."""
    return missing(value) or str(value).strip() == "REPLACE_ME"


def compute_name(opts: dict) -> str:
    """What this deployment calls its machine. The one function that answers it
    — every label, including the firewall's, derives from this and never from
    the raw override key or a second copy of the profile (§3)."""
    override = opts.get("digitalocean-name")
    if placeholder(override):
        return str("" if opts.get("profile") is None else opts.get("profile"))
    return str(override).strip()


def keygen(opts: dict) -> bool:
    """Whether this deployment owns its machine keypair. Delegates to ONCE, the
    standard's reference implementation, so one rule decides it everywhere."""
    return once_ssh.keygen(opts)


def env_errors(env: dict) -> list[str]:
    if str(env.get(profile_par) or ""):
        return [f"{profile_par} is set; profile must come from colors.yml only"]
    return []


def _positive_int(x) -> bool:
    return isinstance(x, int) and not isinstance(x, bool) and x > 0


def state_errors(opts: dict) -> list[str]:
    errors: list[str] = []
    for k in required:
        if missing(opts.get(k)):
            errors.append(f":{k} is required")
    if opts.get("provider-compute") != "digitalocean":
        errors.append(":provider-compute must be digitalocean")
    if opts.get("provider-dns") != "cloudflare":
        errors.append(":provider-dns must be cloudflare")
    if opts.get("provider-backend") not in ("local", "s3", "r2"):
        errors.append(":provider-backend must be local, s3, or r2")
    if not isinstance(opts.get("compute-prevent-destroy"), bool):
        errors.append(":compute-prevent-destroy must be true or false")
    if not (missing(opts.get("posthog-host"))
            or _host_re.fullmatch(str(opts.get("posthog-host")))):
        errors.append(":posthog-host must be a fully qualified hostname")
    for k in image_keys:
        v = opts.get(k)
        if not missing(v) and not _image_re.fullmatch(str(v)):
            errors.append(f":{k} must carry an explicit image tag")
    for k in ["posthog-backup-retention-days"]:
        if not missing(opts.get(k)) and not _positive_int(opts.get(k)):
            errors.append(f":{k} must be a positive integer")
    if not (placeholder(opts.get("digitalocean-name"))
            or _digitalocean_name_re.fullmatch(compute_name(opts))):
        errors.append(":digitalocean-name must be a valid DigitalOcean droplet name")
    if "digitalocean-vpc-uuid" in opts:
        errors.append(":digitalocean-vpc-uuid must be absent; "
                      "the default regional VPC is discovered at runtime")
    if "digitalocean-vpc-cidr" in opts:
        errors.append(":digitalocean-vpc-cidr must be absent; "
                      "this package must not create a VPC")
    return errors


def _backend_entry(opts: dict) -> dict:
    return providers.get("provider-backend", {}).get(opts.get("provider-backend")) or {}


def backend_secrets(opts: dict) -> list[str]:
    return _backend_entry(opts).get("secrets", [])


def secret_errors(opts: dict) -> list[str]:
    keys = ["do-token", "cloudflare-api-token",
            # The compose template interpolates these at run time and
            # carries no fallback; the Django signing key in
            # particular must never be a value published here.
            "posthog-secret-key", "posthog-postgres-password",
            "posthog-oidc-rsa-private-key",
            "posthog-encryption-salt-keys",
            "posthog-admin-password",
            "posthog-backup-r2-access-key-id",
            "posthog-backup-r2-secret-access-key",
            *backend_secrets(opts)]
    return [f"required credential is not set: {par_name(k)}"
            for k in dict.fromkeys(keys) if missing(opts.get(k))]


def tofu_env(opts: dict, slot: str) -> dict[str, str]:
    if slot == "provider-compute":
        return {"do-token": "DIGITALOCEAN_TOKEN"}
    if slot == "provider-dns":
        return {"cloudflare-api-token": "CLOUDFLARE_API_TOKEN"}
    if slot == "provider-backend":
        return _backend_entry(opts).get("tofu-env", {})
    return {}
