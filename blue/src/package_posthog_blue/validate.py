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
compute_providers = {
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

# Provider naming rules for the compute name override. DigitalOcean droplet
# names are hostname-like: lowercase letters, digits, dots and hyphens, up to
# 63 characters, starting and ending alphanumeric. Vultr labels are only a
# console string: letters of either case, digits, dot, underscore and hyphen,
# up to 63 characters.
_compute_name_res = {
    "digitalocean": re.compile(r"[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?"),
    "vultr": re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,62}"),
}

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


def compute_provider(opts: dict) -> dict | None:
    return compute_providers.get(opts.get("provider-compute"))


def compute_key(opts: dict, suffix: str) -> str:
    """The selected provider's key for `suffix`: `digitalocean-ssh-sources`,
    `vultr-name`, and so on. Provider keys stay provider-scoped so an existing
    colors.yml keeps meaning what it meant."""
    return f"{opts.get('provider-compute')}-{suffix}"


def compute_name(opts: dict) -> str:
    """What this deployment calls its machine. The one function that answers it
    — every label, including the firewall's, derives from this and never from
    the raw override key or a second copy of the profile (§3)."""
    override = opts.get(compute_key(opts, "name"))
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


# --- CIDR syntax (Compute Provider Standard §5) ------------------------------
#
# Hand-rolled rather than delegated to a runtime library so the three colours
# accept exactly the same set of strings: an address that one colour's parser
# tolerates and another's rejects would be a parity bug at the firewall.


def _ipv4_address(s: str) -> bool:
    parts = s.split(".")
    return len(parts) == 4 and all(
        re.fullmatch(r"\d{1,3}", part) is not None and int(part) <= 255 for part in parts)


def _ipv6_address(s: str) -> bool:
    halves = s.split("::")

    def groups(half: str) -> list[str]:
        return [] if half == "" else half.split(":")

    def hex_group(g: str) -> bool:
        return re.fullmatch(r"[0-9A-Fa-f]{1,4}", g) is not None

    # An embedded dotted quad may close the address: ::ffff:192.0.2.10.
    def embedded(gs: list[str]) -> bool:
        return len(gs) > 0 and _ipv4_address(gs[-1])

    def count_groups(gs: list[str]) -> int:
        return len(gs) + 1 if embedded(gs) else len(gs)

    def well_formed(gs: list[str]) -> bool:
        return all(hex_group(g) for g in (gs[:-1] if embedded(gs) else gs))

    if len(halves) == 1:
        gs = groups(s)
        return well_formed(gs) and count_groups(gs) == 8
    if len(halves) == 2:
        a, b = groups(halves[0]), groups(halves[1])
        return (well_formed(a) and well_formed(b) and not embedded(a)
                and count_groups(a) + count_groups(b) < 8)
    return False


def cidr(s) -> bool:
    """Whether `s` is a syntactically valid IPv4 or IPv6 CIDR: an address, a
    slash, and a prefix length within the family's range."""
    parts = str(s).split("/")
    if len(parts) != 2:
        return False
    addr, prefix = parts
    if re.fullmatch(r"\d{1,3}", prefix) is None:
        return False
    n = int(prefix)
    return (_ipv4_address(addr) and n <= 32) or (_ipv6_address(addr) and n <= 128)


def cidr_list(v) -> list[str]:
    """The entries of a source list, whether desired state supplied a YAML list
    or an overlay string."""
    xs = v if isinstance(v, (list, tuple)) else re.split(r"[,\s]+", "" if v is None else str(v))
    return [x for x in (str(item).strip() for item in xs) if x]


def _source_errors(opts: dict) -> list[str]:
    """The network contract: `<provider>-ssh-sources` must reach someone, and
    every entry of both lists must be a CIDR — before any provider call. An
    empty `<provider>-http-sources` is allowed and means no public HTTP."""
    if not compute_provider(opts):
        return []
    ssh_key = compute_key(opts, "ssh-sources")
    http_key = compute_key(opts, "http-sources")
    errors: list[str] = []
    if not missing(opts.get(ssh_key)) and not cidr_list(opts.get(ssh_key)):
        errors.append(f":{ssh_key} must list at least one CIDR; "
                      "an empty list is a machine no one can reach")
    for key in (ssh_key, http_key):
        if missing(opts.get(key)):
            continue
        for entry in cidr_list(opts.get(key)):
            if not cidr(entry):
                errors.append(f":{key} entry is not an IPv4 or IPv6 CIDR: {entry}")
    return errors


def _provider_errors(opts: dict) -> list[str]:
    """Checks that only make sense for the selected provider. Keys of an
    unselected provider are never read."""
    errors: list[str] = []
    provider = opts.get("provider-compute")
    if provider == "digitalocean":
        if "digitalocean-vpc-uuid" in opts:
            errors.append(":digitalocean-vpc-uuid must be absent; "
                          "the default regional VPC is discovered at runtime")
        if "digitalocean-vpc-cidr" in opts:
            errors.append(":digitalocean-vpc-cidr must be absent; "
                          "this package must not create a VPC")
    elif provider == "vultr":
        os_id = opts.get("vultr-os-id")
        if not (missing(os_id) or (isinstance(os_id, int) and not isinstance(os_id, bool))):
            errors.append(":vultr-os-id must be Vultr's numeric operating-system id")
    return errors


def state_errors(opts: dict) -> list[str]:
    errors: list[str] = []
    provider = compute_provider(opts) or {}
    for k in [*required, *provider.get("required", [])]:
        if missing(opts.get(k)):
            errors.append(f":{k} is required")
    if not compute_provider(opts):
        errors.append(":provider-compute must be one of "
                      + ", ".join(sorted(compute_providers)))
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
    name_re = _compute_name_res.get(str(opts.get("provider-compute")))
    if name_re and not (placeholder(opts.get(compute_key(opts, "name")))
                        or name_re.fullmatch(compute_name(opts))):
        errors.append(f":{compute_key(opts, 'name')} must be a valid "
                      f"{opts.get('provider-compute')} machine name")
    errors += _source_errors(opts)
    errors += _provider_errors(opts)
    return errors


def provider_state_errors(opts: dict, recorded: dict | None) -> list[str]:
    """Provider switching is a rebuild, never an apply (Compute Provider
    Standard §4). All providers share one state key, so a changed
    provider-compute on a profile with compute in state would plan a
    cross-provider replacement. `recorded` is the compute stage's applied
    `params` (None when no state is readable): a recorded provider that differs
    from the selected one refuses, and params without a provider — a deployment
    created before adoption — are accepted only for the package default. Pure,
    so the read stays with the lifecycle and the rule is testable without a
    backend."""
    if not recorded:
        return []
    selected = opts.get("provider-compute")
    held = str(recorded.get("provider") or "") or default_compute_provider
    if held == selected:
        return []
    return [f"state holds a {held} machine; set provider-compute back to {held} and delete first"]


def _backend_entry(opts: dict) -> dict:
    return providers.get("provider-backend", {}).get(opts.get("provider-backend")) or {}


def backend_secrets(opts: dict) -> list[str]:
    return _backend_entry(opts).get("secrets", [])


def secret_errors(opts: dict) -> list[str]:
    keys = [*(compute_provider(opts) or {}).get("secrets", []),
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
        return (compute_provider(opts) or {}).get("tofu-env", {})
    if slot == "provider-dns":
        return {"cloudflare-api-token": "CLOUDFLARE_API_TOKEN"}
    if slot == "provider-backend":
        return _backend_entry(opts).get("tofu-env", {})
    return {}
