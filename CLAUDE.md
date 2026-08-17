# CLAUDE.md

## Repository

`posthog` is a Green-only Package Skill for a single-node PostHog analytics suite
on DigitalOcean. It manages OpenTofu compute/firewall, dynamic regional default
VPC lookup, Cloudflare apex/subdomain DNS, and converges Docker Compose (PostHog
Web UI, Celery worker, PostgreSQL 17, ClickHouse 24.8, Redis 7.2, and Caddy 2.11.4).
The first consumer is `../posthog-digitalocean`.

The stack enforces private container networking for internal databases and queues;
only SSH, HTTP, and HTTPS ports are open externally. Scheduled disaster recovery
is orchestrated via systemd timer `posthog-backup.timer` running
`/usr/local/sbin/posthog-backup` to capture Postgres `pg_dump` and ClickHouse table
backups, uploaded to Cloudflare R2.

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

The deployment launcher is a copy of the skill payload. Develop with
`POSTHOG_LIB_ROOT=../posthog`; after pushing package code run `bb pin`, commit
and push the stamped launcher, then synchronize the installed payload and root
copy. Never invent or hand-edit a SHA.

## Git

Work on the current branch. Do not commit or push unless explicitly authorized.
