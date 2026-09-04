# PostHog Package Skill

A reproducible tri-colour Package Skill — Clojure/Babashka (green),
TypeScript/Bun (red), and Python/uv (blue) — for deploying and operating a
single-node PostHog product analytics suite on DigitalOcean. Green is the
canonical implementation; red and blue render byte-identical artifacts,
verified by `scripts/parity.sh`.

## Architecture

Ten containers, and none of them is optional. Upstream's own single-server
topology is roughly thirty-five services; this is a deliberate reduction to the
set that has to be present, not a copy.

- **Compute** — one DigitalOcean Droplet (`s-4vcpu-8gb` in `ams3`) attached to
  the regional default VPC, discovered at runtime, behind a provider firewall
  opening 22/80/443, and — in keygen mode — the account SSH key named after
  the profile. The droplet is named after the profile unless
  `digitalocean-name` overrides it.
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
cd green && ./green build      # or: cd red && ./red build · cd blue && ./blue build

# Walk the DAG, skipping every side effect
./green create --dry-run

# Provision and converge live infrastructure (requires credentials)
./green create
```

Exit code 2 is a validation or usage failure and lists every problem at once.

## The SSH keypair

The deployment owns its machine key, per the workspace
[SSH Keypair Standard](https://github.com/getcolors/workspace/blob/main/standards/ssh-keypair.md).
Leave `digitalocean-ssh-keys` out of `colors.yml` and the package generates
`~/.ssh/<profile>` on the first real `create`, registers it at DigitalOcean
under the profile name, and deletes it after a successful `delete` — never
before.

Consequences worth knowing before you clone a deployment elsewhere:

- The keypair lives in `~/.ssh`, not the checkout, so cloning a deployment
  repository does not carry machine access with it. Copy
  `~/.ssh/<profile>`(`.pub`) deliberately when access should move.
- A key on disk with no state is an error, never overwritten — it may be the
  only credential to a host that is still alive.
- A DigitalOcean key named after the profile that this deployment's state does
  not own is an error too. If its fingerprint differs from yours, **do not
  delete it**.
- Rotation is a rebuild: DigitalOcean key lists are ForceNew.

Supplying `digitalocean-ssh-keys` opts out entirely; the package then
generates, validates, and deletes nothing.

Convergence also writes a `~/.ssh/config` block per the
[SSH Config Standard](https://github.com/getcolors/workspace/blob/main/standards/ssh-config.md),
so `ssh <profile>` reaches the host with no address, user or identity flag. It
is written after compute and before convergence, and removed by `delete`
before the droplet is destroyed.

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
cd green && bb test
cd green && bb golden
cd red && bun test && bun run typecheck
cd blue && uv run pytest
./scripts/parity.sh
./scripts/launcher.sh
```

`bb golden` renders two fixtures, because the SSH Keypair Standard has two
modes: keygen (`test/fixtures/colors.yml`) and opt-out
(`test/fixtures/optout.yml`). Read every golden diff after a pin bump. Never
run `bb golden:accept` merely to make the suite pass.
