"""Desired-state and credential validation, the port of
io.github.getcolors.posthog.validate.

Green renders its keys as Clojure keywords, so every message here carries the
same leading colon — the three colours must report identical errors for one
colors.yml.
"""

from __future__ import annotations

import re

from blue.cli import par_name
from . import compute
from colors_compute.ssh import _mode
from package_once_blue.validate import providers

__all__ = ["providers"]

profile_par = par_name("profile")

default_compute_provider = "digitalocean"

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


def keygen(opts):
    try:
        return _mode(opts)['mode'] == 'managed'
    except ValueError:
        return True


def env_errors(env: dict) -> list[str]:
    if str(env.get(profile_par) or ""):
        return [f"{profile_par} is set; profile must come from colors.yml only"]
    return []


def _positive_int(x) -> bool:
    return isinstance(x, int) and not isinstance(x, bool) and x > 0


# A source list as desired state or an overlay string carries it. ONCE's, so
# the validator and the templates can never disagree about what an entry is.


def state_errors(opts: dict) -> list[str]:
    """Every problem with desired state at once: the missing keys (this
    package's and the selected provider's), the package's own checks, then the
    Compute Provider Standard's — selection, the network contract and the
    provider rules — which are ONCE's over `spec`."""
    errors: list[str] = []
    for k in required:
        if missing(opts.get(k)):
            errors.append(f":{k} is required")
    if opts.get("provider-dns") != "cloudflare":
        errors.append(":provider-dns must be cloudflare")
    if opts.get("provider-backend") not in ("s3", "r2"):
        errors.append(":provider-backend must be s3 or r2")
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
    errors += compute.errors(opts)
    return errors


def _backend_entry(opts: dict) -> dict:
    return providers.get("provider-backend", {}).get(opts.get("provider-backend")) or {}


def backend_secrets(opts: dict) -> list[str]:
    return _backend_entry(opts).get("secrets", [])


def secret_errors(opts: dict) -> list[str]:
    keys = [
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
        return {}
    if slot == "provider-dns":
        return {"cloudflare-api-token": "CLOUDFLARE_API_TOKEN"}
    if slot == "provider-backend":
        return _backend_entry(opts).get("tofu-env", {})
    return {}
