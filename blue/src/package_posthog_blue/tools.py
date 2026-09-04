"""OpenTofu and Ansible stages for the single-node PostHog suite, the port of
io.github.getcolors.posthog.tools."""

from __future__ import annotations

import asyncio
import json
import math
import re
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path

from blue import tofu
from blue.ansible import ansible_with_spec
from blue.cli import stage_dir
from blue.runtime import runtime
from blue.scaffold import PRESERVE_JINJA_DELIMITERS, content_spec, scaffold
from blue.workflow import failed
from package_once_blue import compute as once_compute

from . import ssh, ssh_config, utils, validate

infrastructure_tool = "posthog-infrastructure"
dns_tool = "posthog-dns"
ansible_tool = "posthog-ansible"
ansible_local_tool = "posthog-ansible-local"

ROOT = Path(__file__).parent / "resources"
template_opts = PRESERVE_JINJA_DELIMITERS


def tool_dir(opts: dict, tool: str) -> str:
    return stage_dir(opts, tool, default_profile="posthog")


def template(path: str, file: str) -> dict:
    name = f"tools/{path.replace('.', '/')}/{file}"
    return {"name": name, "content": (ROOT / name).read_text()}


def spec(source: dict, target: str, data: dict) -> dict:
    return {"template": source, "target": target, "data": data, "opts": template_opts}


def raw_spec(target: str, content: str) -> dict:
    return content_spec(target, content)


def cidrs(opts: dict, k: str) -> list[str]:
    return validate.cidrs(opts, k)


def credential_env(opts: dict, *slots: str) -> dict[str, str] | None:
    merged: dict[str, str] = {}
    for slot in [*slots, "provider-backend"]:
        merged.update(validate.tofu_env(opts, slot))
    env = {}
    for k, env_var in merged.items():
        v = str(opts.get(k) if opts.get(k) is not None else "")
        if v:
            env[env_var] = v
    return env or None


def backend_credential_env(opts: dict) -> dict[str, str] | None:
    return credential_env(opts)


# What `build` and `--dry-run` render in place of a compute output: the
# documentation address, shaped like the selected provider's real `params` so
# every later stage sees the same keys either way. ONCE's.
fallback_params = once_compute.fallback_params


def infrastructure_data(opts: dict) -> dict:
    """Template values for the compute stage. The source lists are read
    through `compute_key`, so the same data serves every provider's
    template."""
    http_sources = cidrs(opts, validate.compute_key(opts, "http-sources"))
    return {**opts,
            "ssh-keygen": validate.keygen(opts),
            "compute-name": validate.compute_name(opts),
            "ssh-sources-hcl": tofu.hcl_list(cidrs(opts, validate.compute_key(opts, "ssh-sources"))),
            "http-sources-hcl": tofu.hcl_list(http_sources),
            # An empty http list means no public HTTP: the 80/443 rules are
            # left out rather than rendered with an empty source list, which
            # the DigitalOcean API rejects. Vultr's rules are a for_each over
            # the set and vanish on their own.
            "http-sources?": len(http_sources) > 0}


def infrastructure_template(opts: dict) -> dict:
    """Providers are selected by template directory, never by conditionals
    inside one file (Compute Provider Standard §3):
    `tools/infrastructure/<provider>/`."""
    return template(f"infrastructure.{opts.get('provider-compute')}", "main.tf")


# Refuse to hand 192.0.2.10 to Ansible on a real converge whose compute output
# carries no `ip`. ONCE's; `infrastructure_step` is what wires it.
resolved_compute = once_compute.resolved_compute


async def infrastructure_step(opts: dict) -> dict:
    dir = tool_dir(opts, infrastructure_tool)
    specs = [spec(infrastructure_template(opts), f"{dir}/main.tf",
                  infrastructure_data(opts))]
    result = await tofu.tofu_with_spec(opts, specs, dir=dir,
                                       env=credential_env(opts, "provider-compute"))
    if failed(result):
        return result
    if opts.get("blue/event") == "build":
        return {**result, **fallback_params(opts)}
    if opts.get("blue/event") == "delete":
        return result
    return resolved_compute(result, fallback_params(opts), once_compute.output_params(result))


def zone_id(zone) -> str:
    return "${data.cloudflare_zone.zone.id}"


def dns_json(opts: dict) -> str:
    return tofu.constructs_json(
        [tofu.construct("resource", "cloudflare_dns_record", "posthog",
                        {"zone_id": zone_id(opts.get("posthog-zone")),
                         "name": opts.get("posthog-host"),
                         "content": opts.get("ip"), "type": "A",
                         # Proxied by default: an unproxied record publishes the
                         # droplet's address. This was hardcoded true, so a
                         # cloudflare-proxied key in colors.yml was read by
                         # nothing and changed nothing -- no effect, no error,
                         # exit 0. Honour it, and keep the safe value as the
                         # default. The application trusts forwarded addresses
                         # through IS_BEHIND_PROXY, so client IPs survive the edge.
                         "proxied": (bool(opts.get("cloudflare-proxied"))
                                     if opts.get("cloudflare-proxied") is not None
                                     else True),
                         "ttl": 1})])


async def dns_step(opts: dict) -> dict:
    dir = tool_dir(opts, dns_tool)
    zone = opts.get("posthog-zone") or utils.registrable_domain(opts.get("posthog-host"))
    data = {**opts,
            "ip": opts.get("ip") or fallback_params(opts)["ip"],
            "posthog-zone": zone}
    specs = [spec(template("dns", "main.tf"), f"{dir}/main.tf", data),
             raw_spec(f"{dir}/record.tf.json", dns_json(data))]
    return await tofu.tofu_with_spec(opts, specs, dir=dir,
                                     env=credential_env(opts, "provider-dns"))


# --- ~/.ssh/config (local) ---------------------------------------------------


def ansible_local_data(opts: dict) -> dict:
    """Only what a `build` genuinely knows. The address, the user and the alias
    are run-time facts and reach the play as extra-vars instead, so the
    rendered playbook carries no IP and is identical on every workstation (SSH
    Config Standard §6)."""
    return {**opts,
            "ssh-keygen": validate.keygen(opts),
            "ssh-config-identity-file": ssh_config.identity_file(opts)}


def ansible_local_specs(opts: dict) -> list[dict]:
    dir = tool_dir(opts, ansible_local_tool)
    data = ansible_local_data(opts)
    return [spec(template("ansible-local", name), f"{dir}/{name}", data)
            for name in ["ansible.cfg", "inventory.ini", "main.yml"]]


async def ansible_local_step(opts: dict) -> dict:
    """Write or remove the `~/.ssh/config` block. The same playbook serves both
    events; `block_state` is what distinguishes them."""
    dir = tool_dir(opts, ansible_local_tool)
    delete = opts.get("blue/event") == "delete"
    return await ansible_with_spec(
        opts, ansible_local_specs(opts),
        dir=dir, inventory="inventory.ini",
        playbooks={"create": "main.yml", "delete": "main.yml"},
        extra_vars={"host_alias": ssh_config.host_alias(opts),
                    "ip": opts.get("ip") or fallback_params(opts)["ip"],
                    "user": opts.get("user") or "root",
                    "block_state": "absent" if delete else "present"})


# --- Ansible -----------------------------------------------------------------


def _java_double(x: float) -> str:
    """Java's Double.toString, which is what Green's cheshire JSON emits for
    floats: decimal between 1e-3 and 1e7, `d.dddE±e` scientific outside it.
    Python's own repr disagrees exactly where scientific notation starts
    (0.0001 -> "1.0E-4"), and the goldens carry the Java form."""
    if math.isnan(x):
        return "NaN"
    if math.isinf(x):
        return "Infinity" if x > 0 else "-Infinity"
    negative = math.copysign(1.0, x) < 0
    magnitude = abs(x)
    if magnitude == 0.0:
        return "-0.0" if negative else "0.0"
    _sign, digits, exponent = Decimal(repr(magnitude)).as_tuple()
    digit_str = "".join(map(str, digits)).rstrip("0") or "0"
    dec_exp = exponent + len(digits) - 1
    if -3 <= dec_exp < 7:
        if dec_exp >= 0:
            whole = digit_str[:dec_exp + 1].ljust(dec_exp + 1, "0")
            frac = digit_str[dec_exp + 1:] or "0"
        else:
            whole = "0"
            frac = "0" * (-dec_exp - 1) + digit_str
        rendered = f"{whole}.{frac}"
    else:
        mantissa = digit_str[0] + "." + (digit_str[1:] or "0")
        rendered = f"{mantissa}E{dec_exp}"
    return ("-" if negative else "") + rendered


def _pretty(value, indent=0):
    """Cheshire's pretty JSON, byte for byte — Green's artifact contract."""
    if isinstance(value, list):
        if not value:
            return "[ ]"
        return "[ " + ", ".join(_pretty(item, indent) for item in value) + " ]"
    if isinstance(value, dict):
        if not value:
            return "{ }"
        pad = " " * (indent + 2)
        body = ",\n".join(f"{pad}{json.dumps(str(k))} : {_pretty(v, indent + 2)}"
                          for k, v in value.items())
        return "{\n" + body + "\n" + " " * indent + "}"
    if isinstance(value, float) and not isinstance(value, bool):
        return _java_double(value)
    return json.dumps(value)


def inventory(opts: dict) -> str:
    return _pretty(
        {"all": {"children": {"posthog": {"hosts": {
            str(opts.get("profile")): {
                "ansible_host": opts.get("ip") or "192.0.2.10",
                "ansible_user": "root"}}}}}})


def ansible_data(opts: dict) -> dict:
    """Template values for the Ansible stage. `ssh-private-key-path` reaches
    ansible.cfg so convergence uses the deployment's own key in keygen mode,
    where nothing guarantees an agent holds it."""
    return {**opts,
            "ip": opts.get("ip") or "192.0.2.10",
            "ssh-keygen": validate.keygen(opts),
            "posthog-web-port": opts.get("posthog-web-port") or 8000,
            "posthog-backup-access-key":
                "{{ lookup('env','COLORS_PAR_POSTHOG_BACKUP_R2_ACCESS_KEY_ID') }}",
            "posthog-backup-secret-key":
                "{{ lookup('env','COLORS_PAR_POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY') }}"}


def ansible_specs(opts: dict) -> list[dict]:
    dir = tool_dir(opts, ansible_tool)
    data = ansible_data(opts)
    return [spec(template("ansible", "ansible.cfg"), f"{dir}/ansible.cfg", data),
            spec(template("ansible", "main.yml"), f"{dir}/main.yml", data),
            spec(template("ansible", "cleanup.yml"), f"{dir}/cleanup.yml", data),
            spec(template("ansible", "compose.yml"), f"{dir}/compose.yml", data),
            spec(template("ansible", "Caddyfile"), f"{dir}/Caddyfile", data),
            spec(template("ansible", "backup"), f"{dir}/backup", data),
            spec(template("ansible", "checkpoint.sql"), f"{dir}/checkpoint.sql", data),
            spec(template("ansible", "owner.py"), f"{dir}/owner.py", data),
            raw_spec(f"{dir}/inventory.json", inventory(data))]


async def ansible_step(opts: dict) -> dict:
    dir = tool_dir(opts, ansible_tool)
    if opts.get("blue/event") == "delete" and not opts.get("ip"):
        # No compute in state: there is no host to clean up, and the rendered
        # inventory would fall back to 192.0.2.10. Remove the rendered tree the
        # way a completed cleanup would and let the teardown continue.
        return {**scaffold(opts, ansible_specs(opts)),
                "blue/exit": 0, "posthog/cleanup": "skipped-no-compute"}
    return await ansible_with_spec(opts, ansible_specs(opts),
                                   dir=dir, inventory="inventory.json",
                                   playbooks={"create": "main.yml",
                                              "delete": "cleanup.yml"},
                                   host_key_checking=False)


# --- Acceptance --------------------------------------------------------------
#
# Every claim this step reports must be one it checked. TLS is verified (the
# previous check passed `curl -k`, so a broken certificate would have gone
# unnoticed), a captured event is read back out of ClickHouse rather than
# inferred from a status code, and the backup drill is confirmed by a fresh
# object in R2 rather than by systemd reporting that it started something.


def _parse_long(s) -> int | None:
    if isinstance(s, str) and re.fullmatch(r"[+-]?\d+", s):
        return int(s)
    return None


async def http_status(args: list[str]) -> str | None:
    r = await runtime.exec(
        ["curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}", *args],
        timeout_ms=20000)
    return r.out.strip() if r.exit == 0 else None


async def ssh_out(opts: dict, command: str, timeout: int) -> str | None:
    """Run ``command`` on the instance over ssh. In keygen mode the
    deployment's own key is selected explicitly (``ssh.identity_args``),
    because nothing guarantees an agent holds it."""
    r = await runtime.exec(
        ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=10",
         *ssh.identity_args(opts), f"root@{opts.get('ip')}", command],
        timeout_ms=timeout)
    return r.out.strip() if r.exit == 0 else None


async def psql(opts: dict, query: str) -> str | None:
    out = await ssh_out(opts, "cd /opt/posthog && docker compose exec -T db psql -U posthog"
                        f" -d posthog -tAc '{query}'", 30000)
    s = str("" if out is None else out)
    return s or None


async def clickhouse(opts: dict, query: str) -> str | None:
    """Resolve the events table from system.tables so the check does not
    hardcode a database name PostHog's migrations own, then run ``query``
    against it."""
    out = await ssh_out(opts, "cd /opt/posthog && "
                        "t=$(docker compose exec -T clickhouse clickhouse-client"
                        " --query \"SELECT database || '.' || name FROM system.tables"
                        " WHERE name = 'events' AND database NOT IN ('system')"
                        " ORDER BY database LIMIT 1\" | tr -d '\\r'); "
                        "[ -n \"$t\" ] && docker compose exec -T clickhouse clickhouse-client"
                        f" --query \"{query}\"", 30000)
    s = str("" if out is None else out)
    return s or None


async def event_count(opts: dict) -> int | None:
    return _parse_long(await clickhouse(opts, "SELECT count() FROM $t"))


async def project_api_key(opts: dict) -> str | None:
    return await psql(opts, "select api_token from posthog_team order by id limit 1")


async def wait_health(url: str, attempts: int) -> bool:
    n = attempts
    while True:
        r = await runtime.exec(["curl", "-fsS", f"{url}/_health/"], timeout_ms=10000)
        if r.exit == 0:
            return True
        if n <= 0:
            return False
        await asyncio.sleep(5)
        n -= 1


async def send_event(base: str, api_key: str) -> str | None:
    return await http_status(
        ["-X", "POST", "-H", "content-type: application/json",
         "--data", json.dumps({"api_key": api_key,
                               "event": "colors_acceptance",
                               "distinct_id": "colors-acceptance",
                               "properties": {"source": "colors"}},
                              separators=(",", ":")),
         f"{base}/capture/"])


def ingestion_verdict(status, before, after) -> str:
    if status is None:
        return "unreachable"
    if (isinstance(before, int) and not isinstance(before, bool)
            and isinstance(after, int) and not isinstance(after, bool)
            and after > before):
        return "ingested"
    if re.fullmatch(r"2\d\d", str(status)):
        return "dropped"
    return "rejected"


async def wait_ingested(opts: dict, baseline: int, attempts: int):
    """Capture is asynchronous through the Celery worker, so poll rather than
    sampling once."""
    n = attempts
    while True:
        after = await event_count(opts)
        if isinstance(after, int) and after > baseline:
            return after
        if n <= 0:
            return after
        await asyncio.sleep(5)
        n -= 1


rclone_env = ("RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare "
              "RCLONE_CONFIG_R2_REGION=auto RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true")


async def backup_listing(opts: dict) -> list[dict] | None:
    out = await ssh_out(
        opts, "set -a; . /etc/posthog-backup.env; set +a; " + rclone_env +
        " RCLONE_CONFIG_R2_ACCESS_KEY_ID=\"$POSTHOG_BACKUP_R2_ACCESS_KEY_ID\""
        " RCLONE_CONFIG_R2_SECRET_ACCESS_KEY=\"$POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY\""
        f" RCLONE_CONFIG_R2_ENDPOINT=\"{opts.get('posthog-backup-r2-endpoint')}\""
        f" rclone lsjson --files-only r2:{opts.get('posthog-backup-r2-bucket')}"
        f"/{opts.get('profile')}", 120000)
    if not out:
        return None
    return json.loads(out)


def parse_instant(s) -> datetime | None:
    """The port of green's OffsetDateTime parse, which requires an explicit
    offset and answers nil for anything else."""
    try:
        t = datetime.fromisoformat(str(s))
        return t if t.tzinfo is not None else None
    except ValueError:
        return None


def fresh_backup(entries, since: datetime) -> bool:
    if not entries:
        return False
    for entry in entries:
        t = parse_instant(entry.get("ModTime"))
        if (entry.get("Size") or 0) > 0 and t is not None and t >= since:
            return True
    return False


async def run_backup(opts: dict) -> str | None:
    return await ssh_out(
        opts, "systemctl start posthog-backup.service && systemctl is-active posthog-backup.timer",
        600000)


async def background_jobs(opts: dict) -> str | None:
    """PostHog's own answers, not ours: whether Celery is alive, and whether any
    async migration is still pending. A pending one stops the worker starting at
    all, and the ingestion path this step already exercises never touches Celery
    -- so background jobs can be entirely dead while capture works."""
    return await ssh_out(
        opts, "cd /opt/posthog && docker compose exec -T web python manage.py shell -c "
        "\"from posthog.utils import is_celery_alive; "
        "from posthog.models.async_migration import AsyncMigration; "
        "print('celery=%s pending=%d' % (is_celery_alive(), "
        "AsyncMigration.objects.exclude(status=2).count()))\"",
        120000)


def background_verdict(out) -> str:
    s = str("" if out is None else out)
    if not s.strip():
        return "unreachable"
    if not re.search(r"celery=True", s):
        return "celery-down"
    if not re.search(r"pending=0\b", s):
        return "migrations-pending"
    return "ok"


async def acceptance_step(opts: dict) -> dict:
    if opts.get("blue/event") != "create":
        return {**opts, "blue/exit": 0}
    base = f"https://{opts.get('posthog-host')}"
    since = datetime.now(timezone.utc) - timedelta(seconds=120)
    if not await wait_health(base, 60):
        return {**opts, "blue/exit": 1,
                "blue/err": "HTTPS health did not become ready with a valid certificate"}
    api_key = await project_api_key(opts)
    before = await event_count(opts)
    if not (isinstance(before, int) and not isinstance(before, bool)):
        return {**opts, "blue/exit": 1,
                "blue/err": "could not read the ClickHouse events table to verify capture"}
    if not api_key:
        verdict = "not-configured"
    else:
        status = await send_event(base, api_key)
        after = await wait_ingested(opts, before, 12)
        verdict = ingestion_verdict(status, before, after)
    background = background_verdict(await background_jobs(opts))
    if verdict in ("dropped", "rejected", "unreachable"):
        return {**opts, "blue/exit": 1,
                "blue/err": f"synthetic event was not captured: {verdict}"}
    if background != "ok":
        return {**opts, "blue/exit": 1,
                "blue/err": f"background jobs are not healthy: {background}"}
    if await run_backup(opts) is None:
        return {**opts, "blue/exit": 1,
                "blue/err": "backup unit or timer is not healthy"}
    if not fresh_backup(await backup_listing(opts), since):
        return {**opts, "blue/exit": 1,
                "blue/err": ("no backup object newer than this run under r2:"
                             f"{opts.get('posthog-backup-r2-bucket')}/{opts.get('profile')}")}
    return {**opts, "blue/exit": 0,
            "posthog/acceptance": {"health": "ok", "event": verdict,
                                   "background": "ok",
                                   "backup": "verified-in-r2"}}
