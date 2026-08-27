import re
import tempfile
from datetime import datetime
from pathlib import Path

import pytest
from blue.cli import load_yaml
from blue.runtime import runtime
from blue.runtime import ExecResult

from conftest import FIXTURE_FILE, make_fixture
from package_posthog_blue import tools

RESOURCES = Path(tools.__file__).parent / "resources" / "tools"
playbook = (RESOURCES / "ansible" / "main.yml").read_text()
compose = (RESOURCES / "ansible" / "compose.yml").read_text()
caddyfile = (RESOURCES / "ansible" / "Caddyfile").read_text()
backup = (RESOURCES / "ansible" / "backup").read_text()
checkpoint = (RESOURCES / "ansible" / "checkpoint.sql").read_text()
owner = (RESOURCES / "ansible" / "owner.py").read_text()


async def test_delete_cleanup_skips_when_state_has_no_compute(monkeypatch):
    # With the instance already gone the inventory would render 192.0.2.10;
    # there is no host to reach, so the step must not run the playbook and the
    # teardown must continue past it.
    async def boom(*_args, **_kwargs):
        raise AssertionError("playbook must not run")
    monkeypatch.setattr(runtime, "exec", boom)
    with tempfile.TemporaryDirectory() as workdir:
        r = await tools.ansible_step(make_fixture(**{"blue/event": "delete",
                                                     "workdir": workdir}))
    assert r["blue/exit"] == 0
    assert r["posthog/cleanup"] == "skipped-no-compute"


async def test_delete_cleanup_targets_the_adopted_address(monkeypatch):
    # When the start step recovered the instance address from state, the
    # cleanup playbook runs against it, never the documentation fallback.
    seen = {}

    async def record(_cmd, cwd=None, env=None, timeout_ms=None):
        seen["inventory"] = (Path(cwd) / "inventory.json").read_text()
        return ExecResult(exit=0, out="", err="")
    monkeypatch.setattr(runtime, "exec", record)
    with tempfile.TemporaryDirectory() as workdir:
        await tools.ansible_step(make_fixture(**{"blue/event": "delete",
                                                 "ip": "203.0.113.7",
                                                 "workdir": workdir}))
    assert "203.0.113.7" in seen["inventory"]


def test_infrastructure_discovers_default_vpc():
    data = tools.infrastructure_data(make_fixture())
    assert tools.cidrs(data, "digitalocean-http-sources") == ["0.0.0.0/0", "::/0"]


def test_dns_is_apex_and_proxied():
    json_text = tools.dns_json(make_fixture(**{"ip": "192.0.2.10",
                                               "posthog-zone": "example.com"}))
    assert "posthog.example.com" in json_text
    assert "192.0.2.10" in json_text
    # Assert the value, not the key: "proxied" is in the rendered record
    # either way, so this passed on an unproxied record too.
    assert '"proxied" : true' in json_text


def test_dns_proxying_can_be_declined():
    # It was hardcoded, so setting the key did nothing and said nothing.
    assert '"proxied" : false' in tools.dns_json(
        make_fixture(**{"ip": "192.0.2.10", "posthog-zone": "example.com",
                        "cloudflare-proxied": False}))


def test_inventory_keeps_one_private_target():
    inventory = tools.inventory(make_fixture(ip="192.0.2.10"))
    assert "192.0.2.10" in inventory
    assert "posthog-fixture" in inventory


def test_convergence_migrates_then_waits_on_the_web_service():
    # Waiting on pg_isready declared the stack converged while the application
    # was still unmigrated, so the ordering here is the contract.
    migrate = playbook.index("manage.py migrate_clickhouse")
    health = playbook.index("/_health/")
    assert migrate < health


def test_broker_does_not_evict():
    assert "--maxmemory-policy noeviction" in compose
    # The guarantee is not the in-container check -- which blocks on
    # run_async_migrations -- but that the playbook migrates explicitly and
    # fails the converge when that fails.
    assert "manage.py migrate && python manage.py migrate_clickhouse" in playbook


def test_compose_template_carries_no_default_credential():
    rendered = tools.ansible_data(make_fixture())
    # The Django signing key was a constant in this public repository, so a
    # rendered artefact must never be able to carry one again.
    assert "insecure-secret-key" not in compose
    assert not re.search(r"POSTGRES_PASSWORD: posthog", compose)
    assert rendered.get("posthog-secret-key") is None
    assert "urlencode | replace('/', '%2F')" in compose


def test_capture_is_judged_by_the_stored_row_not_the_status():
    # The previous step computed a capture result and never looked at it.
    assert tools.ingestion_verdict("200", 4, 5) == "ingested"
    assert tools.ingestion_verdict("200", 4, 4) == "dropped"
    assert tools.ingestion_verdict("202", 4, None) == "dropped"
    assert tools.ingestion_verdict("401", 4, 4) == "rejected"
    assert tools.ingestion_verdict(None, 4, 4) == "unreachable"


def test_backup_must_be_fresh_and_non_empty():
    since = datetime.fromisoformat("2026-08-17T02:30:00+00:00")
    def entry(size, mod_time):
        return {"Size": size, "ModTime": mod_time}
    assert tools.fresh_backup([entry(1024, "2026-08-17T02:30:05Z")], since)
    assert tools.fresh_backup([entry(1024, "2026-08-17T04:30:05+02:00")], since)
    assert not tools.fresh_backup([entry(1024, "2026-08-16T02:30:05Z")], since)
    assert not tools.fresh_backup([entry(0, "2026-08-17T02:30:05Z")], since)
    assert not tools.fresh_backup([], since)
    assert not tools.fresh_backup(None, since)


def test_clickhouse_backup_is_native_and_has_no_torn_fallback():
    # A hot tar of the data directory races running merges and produces an
    # archive that cannot be restored; a failed backup must fail the run.
    assert "BACKUP DATABASE" in backup
    assert "/var/lib/clickhouse/backups/" in backup
    assert "tar -czf" not in backup


def test_datastores_start_before_migrations_and_app_after():
    # Bringing `web` up first put the image's own startup migration in a race
    # with the explicit one, and the loser died on "relation already exists".
    start = playbook.index("docker compose up -d db redis kafka clickhouse")
    migrate = playbook.index("manage.py migrate_clickhouse")
    app = playbook.index("Converge the application containers")
    assert start < migrate < app
    # A handler flush must not be able to start the stack ahead of migrations.
    assert "Restart PostHog stack" not in playbook


def test_clickhouse_has_coordination_for_replicated_tables():
    # migrate_clickhouse passes replicated=True unconditionally, so every table
    # is a ReplicatedMergeTree and the first CREATE dies with "There is no
    # Zookeeper configuration in server config" unless Keeper is configured.
    assert "<keeper_server>" in playbook
    assert "<zookeeper>" in playbook
    # Replicated table paths substitute these; without them the DDL is invalid.
    assert "<shard>" in playbook
    assert "<replica>" in playbook
    # cluster.py selects hosts with getMacro on both of these; without them
    # migrate_clickhouse dies with "No macro hostClusterType in config".
    assert "<hostClusterType>online</hostClusterType>" in playbook
    # "data" matches callers requesting DATA and the ALL wildcard; "all" would
    # match only the latter.
    assert "<hostClusterRole>data</hostClusterRole>" in playbook
    assert "config.d/keeper.xml" in compose


def test_clickhouse_config_changes_reach_the_container():
    # Single-file bind mounts bind to the inode, and copy replaces by rename, so
    # without a recreate the server keeps serving the previous config while the
    # host file looks correct.
    assert "--force-recreate clickhouse" in playbook
    assert "clickhouse_keeper_config.changed" in playbook
    assert "clickhouse_clusters_config.changed" in playbook
    # Change flags alone are not enough: on a converge where the files were
    # already correct, copy reports no change while the container still serves
    # the config it started with. The reload must key off the server's state.
    assert "FROM system.macros WHERE macro = 'hostClusterType'" in playbook
    assert "FROM system.named_collections WHERE name = 'msk_cluster'" in playbook
    assert "clickhouse_macros.stdout" in playbook
    # And the migration must not start before the reloaded server is answering.
    reload = playbook.index("--force-recreate clickhouse")
    wait = playbook.index("Wait for ClickHouse to accept queries")
    migrate = playbook.index("manage.py migrate_clickhouse")
    assert reload < wait < migrate


def test_cluster_hosts_are_reachable_from_every_container():
    # system.clusters is read by web and worker, which dial the advertised host;
    # loopback there is the client container, not ClickHouse.
    assert "<host>127.0.0.1</host><port>9000</port>" not in playbook
    assert "<host>clickhouse</host><port>9000</port>" in playbook
    # Keeper is embedded in the ClickHouse server, so those stay on loopback.
    assert "<host>127.0.0.1</host>\n                      <port>9181</port>" in playbook


def test_ingestion_tier_is_present():
    # PostHog's path is capture -> Kafka -> plugin-server -> ClickHouse, and its
    # ClickHouse migrations create Kafka engine tables, so a broker is required
    # for the schema to exist at all -- not only for events to flow.
    assert "  kafka:" in compose
    assert "./bin/plugin-server" not in compose
    assert "KAFKA_HOSTS" in compose
    # Every named collection the migrations may reference must resolve.
    # The full set from upstream's docker/clickhouse/config.d/default.xml;
    # settings.py only reveals six of the eight.
    for collection in ["msk_cluster", "warpstream_ingestion", "warpstream_calculated_events",
                       "warpstream_replay", "warpstream_shared", "warpstream_cyclotron",
                       "warpstream_logs", "warpstream_traces"]:
        assert f"<{collection}>" in playbook


def test_system_log_tables_exist_before_migrations():
    # system.crash_log is created on first write, and a migration reads it.
    flush = playbook.index("SYSTEM FLUSH LOGS")
    migrate = playbook.index("manage.py migrate_clickhouse")
    assert flush < migrate


def test_temporal_is_present_and_gates_the_application():
    # Django's startup connects to Temporal through the Rust SDK bridge; when
    # that fails the web process never binds, so this is a hard dependency
    # rather than a degraded feature.
    assert "  temporal:" in compose
    assert "TEMPORAL_HOST: temporal" in compose
    assert "temporal: {condition: service_healthy}" in compose


def test_compose_template_is_valid_yaml():
    # The multi-line PEM has to reach the container without breaking the
    # document: an unquoted {{ ... }} reads as a flow mapping and makes the file
    # unparseable, which Docker only discovers on the host.
    parsed = load_yaml(compose)
    assert "web" in parsed["services"]
    assert len(parsed["services"]) == 10


def test_temporal_dynamic_config_exists_in_the_image():
    # Upstream mounts development-sql.yaml from its checkout; the auto-setup
    # image ships only docker.yaml, and pointing at a missing file leaves the
    # server refusing connections while schema setup reports success.
    assert "DYNAMIC_CONFIG_FILE_PATH: config/dynamicconfig/docker.yaml" in compose
    assert "DYNAMIC_CONFIG_FILE_PATH: config/dynamicconfig/development-sql.yaml" not in compose


def test_web_serves_without_rerunning_migrations():
    # ./bin/docker runs ./bin/migrate first and loops on
    # schedule_temporal_workflows, so the server never binds.
    assert "command: ./bin/docker-server" in compose


def test_checkpoint_carries_schema_and_migration_bookkeeping():
    # Without the django_migrations rows a restore would look complete and then
    # replay 0001_initial against existing tables -- the exact failure this
    # deployment hit early on.
    assert "CREATE TABLE" in checkpoint
    assert "COPY public.django_migrations" in checkpoint
    # Schema only: no other table's rows may ride along.
    assert len(re.findall(r"(?m)^COPY public\.", checkpoint)) == 1


def test_checkpoint_restores_only_into_an_empty_database_and_still_migrates():
    restore = playbook.index("Restore the schema checkpoint")
    migrate = playbook.index("manage.py migrate_clickhouse")
    assert restore < migrate
    # Guarded on the live schema, so it can never land on top of data.
    assert "posthog_schema.stdout" in playbook
    # Faking the migration state would make a stale checkpoint permanent
    # instead of self-healing; only the comment may mention it.
    assert not re.search(r"manage\.py migrate[^\n]*--fake", playbook)


def test_django_trusts_the_proxy():
    # Otherwise every non-exempt path 301s to itself behind Caddy and Cloudflare.
    assert "IS_BEHIND_PROXY" in compose


def test_ingestion_paths_reach_the_capture_service():
    # Django resolves /capture/, /e/ and /i/v0/e/ to its catch-all frontend view,
    # which answers 403 via CSRF -- proxying them to the app can never ingest.
    assert "  capture:" in compose
    assert "CAPTURE_V1_SINK_MSK_KAFKA_TOPIC_MAIN" in compose
    assert "reverse_proxy capture:3000" in caddyfile
    for path in ["/capture", "/e", "/batch", "/i/*"]:
        assert path in caddyfile


def test_caddy_serves_the_current_configuration():
    # A single-file bind mount pins the inode, so a rewritten Caddyfile never
    # reaches the running container: ingestion routes existed on disk while
    # Caddy still proxied everything to the application.
    assert "--force-recreate caddy" in playbook
    assert "sha256sum /etc/caddy/Caddyfile" in playbook
    reload = playbook.index("--force-recreate caddy")
    health = playbook.index("Wait for the PostHog web service")
    assert reload < health


def test_something_consumes_the_ingestion_topic():
    # Capture produces to events_plugin_ingestion; ClickHouse's Kafka engine
    # tables read clickhouse_* topics. Without a consumer bridging them the API
    # accepts events that never reach the database.
    assert "  plugins:" in compose
    assert "PERSONS_DATABASE_URL" in compose


def test_plugin_server_gets_a_geoip_database():
    # It loads one at startup and exits when it is missing; its image ships none.
    assert "GeoLite2-City.mmdb" in playbook
    assert "/share/GeoLite2-City.mmdb:ro" in compose


def test_every_plugin_server_redis_client_is_pointed_at_redis():
    # Only the first client reads REDIS_URL; the others default to 127.0.0.1 and
    # exit the process when they cannot connect.
    for v in ["CDP_REDIS_HOST", "LOGS_REDIS_HOST", "INGESTION_REDIS_HOST",
              "POSTHOG_REDIS_HOST", "COOKIELESS_REDIS_HOST"]:
        assert f"{v}: redis" in compose


def test_plugin_server_runs_an_ingestion_mode():
    # Without a mode it exits cleanly at startup, having consumed nothing.
    assert "PLUGIN_SERVER_MODE: ingestion-v2-combined" in compose


def test_encryption_keys_are_shared_and_required():
    # The plugin server throws "Encryption keys are not set" and exits; below
    # debug level that looks like a clean shutdown, so it must never be optional.
    assert "ENCRYPTION_SALT_KEYS" in compose
    # In the shared anchor, so application and plugin server agree.
    assert compose.index("ENCRYPTION_SALT_KEYS") < compose.index("services:")
    assert "COLORS_PAR_POSTHOG_ENCRYPTION_SALT_KEYS" in playbook


def test_application_and_plugin_server_images_are_pinned_together():
    # They share a Postgres schema. With floating tags the node process queried
    # posthog_person.last_seen_at, a column the application's migrations had
    # never created, and died in its consume loop.
    text = FIXTURE_FILE.read_text()
    app = re.search(r"posthog-image: posthog/posthog:(\S+)", text).group(1)
    node = re.search(r"posthog-plugin-server-image: posthog/posthog-node:(\S+)", text).group(1)
    assert app == node
    assert app != "latest"


def test_checkpoint_is_bound_to_the_commit_it_came_from():
    # Behind the image on one lineage a checkpoint heals forward. From a
    # divergent commit it leaves migrations the image never had, and migrate
    # stops on orphaned migrations -- so restore only on an exact match.
    assert checkpoint.startswith("-- posthog-commit: ")
    assert "posthog_checkpoint_commit.stdout" in playbook
    assert "posthog_image_commit.stdout" in playbook


def test_person_column_the_plugin_server_needs_is_created():
    # The node image queries a column the application's migrations at the same
    # commit do not create; without it ingestion accepts events and stores none.
    assert "posthog_person ADD COLUMN IF NOT EXISTS last_seen_at" in playbook
    alter = playbook.index("ADD COLUMN IF NOT EXISTS last_seen_at")
    migrate = playbook.index("manage.py migrate_clickhouse")
    # After migrations, so a real migration adding it wins.
    assert migrate < alter


def test_capture_image_is_pinned_not_floating():
    # The application and plugin server are already pinned to one commit; the
    # capture service was still on a branch tag that moves under the deployment.
    text = FIXTURE_FILE.read_text()
    assert re.search(r"posthog-capture-image: \S+@sha256:[0-9a-f]{64}", text)
    assert not re.search(r"(?m)image:\s*\S+:(latest|master)\s*$", text)


def test_celery_and_plugin_server_health_are_addressed():
    # PostHog's own setup UI reported both as errors: Celery could not start
    # until required async migrations were complete, and the plugin server was
    # probed through a Kubernetes service name that resolves nowhere here.
    assert 'CDP_API_URL: "http://plugins:6738"' in compose
    assert "--complete-noop-migrations" in playbook
    # Backfills are only completed where there is nothing to backfill.
    assert "posthog_event_count.stdout" in playbook


def test_background_jobs_verdict_distinguishes_the_failures():
    # The ingestion path never touches Celery, so this is the only part of
    # acceptance that can notice a worker that never started.
    assert tools.background_verdict("celery=True pending=0") == "ok"
    # A pending async migration is exactly what stopped the worker booting.
    assert tools.background_verdict("celery=True pending=4") == "migrations-pending"
    assert tools.background_verdict("celery=False pending=0") == "celery-down"
    assert tools.background_verdict("") == "unreachable"
    assert tools.background_verdict(None) == "unreachable"


def test_an_owner_account_is_provisioned():
    # Without one a converge leaves an instance nobody can log into: the hosted
    # realm only lets the first user create an organization, and the acceptance
    # step's project already creates one.
    assert "owner.py" in playbook
    assert "COLORS_PAR_POSTHOG_ADMIN_PASSWORD" in playbook
    # All three states, so a converge is idempotent whatever it finds.
    for state in ["bootstrapped", "joined", "rotated"]:
        assert f"OWNER={state}" in owner


def test_a_missing_compute_output_fails_loudly():
    # The documentation address belongs to build and dry-run. Merging it into a
    # real converge would point Ansible at TEST-NET instead of failing.
    assert tools.resolved_compute({}, {"ip": "192.0.2.10"}, {"ip": "1.2.3.4"})["ip"] == "1.2.3.4"
    assert tools.resolved_compute({}, {"ip": "192.0.2.10"}, None)["blue/exit"] == 1
    assert tools.resolved_compute({}, {"ip": "192.0.2.10"}, {})["blue/exit"] == 1
    assert tools.resolved_compute({}, {"ip": "192.0.2.10"}, {"ip": "5.6.7.8"}).get("blue/exit") is None


def test_caddy_access_logging_is_on_and_bounded():
    # Access logging is off by default in Caddy, so a successful request left no
    # trace and capture had no request-level evidence to debug from.
    assert "log {" in caddyfile
    assert "output stdout" in caddyfile
    # On, but bounded: json-file never rotates on its own and this endpoint
    # writes a line per request.
    assert "max-size" in compose
    assert "max-file" in compose


def test_access_log_records_the_visitor_not_the_proxy():
    # Behind the Cloudflare proxy every connection arrives from an edge address,
    # so without trusted_proxies Caddy attributes each request to Cloudflare and
    # the access log answers "who sent this?" with the proxy. Verified against a
    # live deployment: the arm with this block logged the real client address
    # and the arm without it logged 162.158.x.
    assert "trusted_proxies static" in caddyfile
    assert "162.158.0.0/15" in caddyfile
    assert "2400:cb00::/32" in caddyfile
