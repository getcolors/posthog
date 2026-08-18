# PostHog Package Skill

A reproducible Green Package Skill for deploying and operating a single-node
PostHog product analytics suite on DigitalOcean.

## Architecture

Ten containers, and none of them is optional. Upstream's own single-server
topology is roughly thirty-five services; this is a deliberate reduction to the
set that has to be present, not a copy.

- **Compute** — one DigitalOcean Droplet (`s-4vcpu-8gb` in `ams3`) attached to
  the regional default VPC, discovered at runtime.
- **Ingress & TLS** — Caddy terminating TLS on 80/443 with automated
  certificates. It splits ingestion paths to the capture service and everything
  else to the application.
- **Application** — the PostHog web process and a Celery worker.
- **Ingestion** — a standalone Rust `capture` service and the Node plugin
  server. Event capture is not part of the Django application: `/capture/`
  resolves to its catch-all view and is rejected by CSRF. The plugin server is
  the only bridge from `events_plugin_ingestion` to the `clickhouse_*` topics
  ClickHouse subscribes to.
- **Streaming** — Redpanda. PostHog's ClickHouse migrations create Kafka engine
  tables, so a broker is required for the schema to exist at all, not merely for
  events to flow.
- **Workflow** — Temporal. Django connects to it on startup through the Rust SDK
  bridge, and a failure there leaves the process alive with nothing listening.
- **Databases** — PostgreSQL for application state and Temporal's store,
  ClickHouse with **embedded Keeper** for event data, and Redis as the Celery
  broker. Keeper is not optional: `migrate_clickhouse` creates every table as a
  `ReplicatedMergeTree`, which needs coordination metadata.
- **Disaster recovery** — a systemd timer taking a Postgres `pg_dump` and a
  native ClickHouse `BACKUP`, uploaded to Cloudflare R2 under the profile
  prefix.

Exact image pins live in `colors.yml`. The application and plugin server must
come from one commit, because they share a Postgres schema.

## Quick start

```sh
# Render configuration and OpenTofu/Ansible stages locally. No credentials,
# no provider calls -- the safe way to check a colors.yml edit.
./green build

# Walk the DAG, skipping every side effect
./green create --dry-run

# Provision and converge live infrastructure (requires credentials)
./green create
```

Exit code 2 is a validation or usage failure and lists every problem at once.

## Acceptance

A real `create` ends by verifying the deployment rather than the gate. It checks
HTTPS health with a valid certificate, posts a synthetic event and **polls
ClickHouse until the row appears**, asks PostHog whether Celery is alive and
whether any async migration is pending, and confirms the backup by finding a
fresh object in R2. Each of these exists because a shallower check passed
against a broken deployment: a 200 from `/capture/` says the event reached
capture, and nothing about whether it was stored.

## Testing

```sh
bb test
bb golden
./scripts/launcher.sh
```

Read every golden diff after a pin bump. Never run `bb golden:accept` merely to
make the suite pass.
