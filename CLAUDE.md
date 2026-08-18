# CLAUDE.md

## Repository

`posthog` is a Green-only Package Skill for a single-node PostHog analytics suite
on DigitalOcean. It manages OpenTofu compute/firewall, dynamic regional default
VPC lookup, Cloudflare DNS, and converges a ten-container Docker Compose stack:
the PostHog web process and Celery worker, a standalone Rust `capture` service,
the Node plugin server, Redpanda, Temporal, PostgreSQL, ClickHouse with embedded
Keeper, Redis, and Caddy. The first consumer is `../posthog-digitalocean`.

**None of the ten is optional, and most of what makes this stack hard fails
silently.** Capture is not part of Django; the plugin server is the only bridge
from `events_plugin_ingestion` to the `clickhouse_*` topics; the ClickHouse
migrations create Kafka engine tables, so the broker is required for the schema
to exist; Django will not bind without Temporal; and `migrate_clickhouse` makes
every table a `ReplicatedMergeTree`, so Keeper and the `hostClusterType` macros
must be present. The reasoning is kept inline in `compose.yml` and `main.yml` —
read it before removing anything that looks redundant.

Only SSH, HTTP, and HTTPS are open externally. Scheduled disaster recovery is a
systemd timer running `/usr/local/sbin/posthog-backup`, taking a Postgres
`pg_dump` and a native ClickHouse `BACKUP` — never a hot `tar`, which races the
server's merges — uploaded to Cloudflare R2.

## Commands

```sh
bb test
bb golden
./scripts/launcher.sh
./green build
./green create --dry-run
./green create                 # requires explicit authorization
./green delete                 # guarded and destructive
```

Never read or edit `.colors/`, read `.envrc.private`, export `COLORS_PAR_PROFILE`,
or weaken `compute-prevent-destroy`. Build and dry-run are credential-free.

## Invariants

`colors.yml` is flat, non-secret desired state. Validation accumulates errors
and rejects every configurable VPC identifier: the OpenTofu data source looks up
the existing default VPC by `digitalocean-region`.

The root `colors.yml` is the only desired state no suite exercises — `bb test`
is unit tests and `bb golden` uses `test/fixtures/colors.yml` — so it drifts
silently. It went six required keys stale once, which made the `./green build`
this file documents exit 2. Run `./green build` here after changing either file.

Two image constraints are load-bearing rather than tidiness. `posthog-image` and
`posthog-plugin-server-image` must be **one commit**, because they share a
Postgres schema. `posthog-clickhouse-image` must be the version upstream pins:
PostHog's schema puts TTLs on `DateTime64` columns, which 24.8 rejects outright.

Eleven `COLORS_PAR_*` credentials are required, not six — the five application
secrets have no defaults, and `secret-errors` fails a real `create` before the
first provider call rather than falling back to a published value.

The deployment launcher is a copy of the skill payload. Develop with
`POSTHOG_LIB_ROOT=../posthog`; after pushing package code run `bb pin`, commit
and push the stamped launcher, then synchronize the installed payload and root
copy. Never invent or hand-edit a SHA.

## Git

Work on the current branch. Do not commit or push unless explicitly authorized.
