"""Desired-state and credential validation, the port of
io.github.getcolors.posthog.validate.

Green renders its keys as Clojure keywords, so every message here carries the
same leading colon — the three colours must report identical errors for one
colors.yml.
"""

from __future__ import annotations

import re

from blue.cli import par_name
from package_once_blue import compute as once_compute
from package_once_blue import ssh as once_ssh
from package_once_blue.validate import providers

__all__ = ["providers"]

profile_par = par_name("profile")

# provider-compute -> what that choice implies (Compute Provider Standard §2).
#
# `required` are the non-secret keys that provider's template interpolates,
# `secrets` the credentials it needs through COLORS_PAR_*, and `tofu-env` the
# subset OpenTofu reads from the process environment itself. Keeping the three
# together is what stops a provider being validated against one set of keys
# and run with another — a stage exporting a credential nobody checked for, or
# a check demanding a key no template uses. The keys of this map are the
# advertised providers; a provider without a template directory and a golden
# is not advertised.
#
# Two keys are deliberately absent from every entry: `<provider>-ssh-keys`,
# because per the SSH Keypair Standard its *absence* selects keygen mode, and
# `<provider>-name`, because per the Compute Name Standard the profile is the
# default and the key is only an override. Requiring either would make
# conforming deployments invalid. Keys of an unselected provider are accepted
# and ignored, so one colors.yml stays portable.
compute_providers: once_compute.Registry = {
    "digitalocean": {
        "required": ["digitalocean-region", "digitalocean-size", "digitalocean-image",
                     "digitalocean-ssh-sources", "digitalocean-http-sources"],
        "secrets": ["do-token"],
        "tofu-env": {"do-token": "DIGITALOCEAN_TOKEN"},
    },
    "vultr": {
        "required": ["vultr-region", "vultr-plan", "vultr-os-id",
                     "vultr-ssh-sources", "vultr-http-sources"],
        "secrets": ["vultr-api-key"],
        "tofu-env": {"vultr-api-key": "VULTR_API_KEY"},
    },
}

# The provider a deployment created before `params.provider` was recorded is
# assumed to run: every such deployment was created on DigitalOcean, the only
# provider this package had.
default_compute_provider = "digitalocean"

# How this package describes itself to ONCE's `compute`, the Compute Provider
# Standard's operations over a package-owned registry. The registry and the
# default are the data above; `sources` names the firewall lists the templates
# read — SSH must list at least one CIDR, an empty HTTP list means no public
# HTTP. The name rules are ONCE's.
spec: once_compute.ComputeSpec = {
    "registry": compute_providers,
    "default": default_compute_provider,
    "sources": {"non_empty": ["ssh-sources"], "may_be_empty": ["http-sources"]},
}

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
    "r2-bucket", "r2-endpoint",
]

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


# `<provider>-<suffix>`: desired state names compute keys after the provider,
# so the shared steps reach them through the selected provider rather than a
# fixed prefix. ONCE's; named here so `tools` reads the same.
compute_key = once_compute.compute_key

# What this deployment calls its machine: `<provider>-name` when present, else
# the profile (Compute Name Standard). ONCE's; every label, including the
# firewall's, derives from this one answer and never from the raw override key
# or a second copy of the profile (§3).
compute_name = once_compute.compute_name


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


# A source list as desired state or an overlay string carries it. ONCE's, so
# the validator and the templates can never disagree about what an entry is.
cidrs = once_compute.cidrs


def state_errors(opts: dict) -> list[str]:
    """Every problem with desired state at once: the missing keys (this
    package's and the selected provider's), the package's own checks, then the
    Compute Provider Standard's — selection, the network contract and the
    provider rules — which are ONCE's over `spec`."""
    errors: list[str] = []
    for k in [*required, *once_compute.required_keys(spec, opts)]:
        if missing(opts.get(k)):
            errors.append(f":{k} is required")
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
    errors += once_compute.state_errors(spec, opts)
    return errors


def _backend_entry(opts: dict) -> dict:
    return providers.get("provider-backend", {}).get(opts.get("provider-backend")) or {}


def backend_secrets(opts: dict) -> list[str]:
    return _backend_entry(opts).get("secrets", [])


def secret_errors(opts: dict) -> list[str]:
    keys = [*once_compute.secrets(spec, opts),
            "cloudflare-api-token",
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
        return once_compute.tofu_env(spec, opts)
    if slot == "provider-dns":
        return {"cloudflare-api-token": "CLOUDFLARE_API_TOKEN"}
    if slot == "provider-backend":
        return _backend_entry(opts).get("tofu-env", {})
    return {}
