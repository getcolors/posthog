# PostHog Package Skill

A reproducible Green Package Skill for deploying and operating a single-node PostHog product analytics suite on DigitalOcean.

## Architecture

- **Compute**: Single DigitalOcean Droplet (`s-4vcpu-8gb` in `ams3`) attached to the regional default VPC.
- **Ingress & TLS**: Caddy 2.11.4 terminating TLS on ports 80/443 with automated certificates and proxying to PostHog web.
- **Application**: PostHog Web UI and Celery background workers.
- **Storage & Databases**:
  - PostgreSQL 17 (`postgres:17-alpine`) for application state and metadata.
  - ClickHouse 24.8 (`clickhouse/clickhouse-server:24.8-alpine`) for columnar event data.
  - Redis 7.2 (`redis:7.2-alpine`) for caching and asynchronous task queuing.
- **Disaster Recovery**: Systemd timer `posthog-backup.timer` executing `/usr/local/sbin/posthog-backup` taking Postgres `pg_dump` and ClickHouse table backups, synced via `rclone` to Cloudflare R2 (`posthog-backup`).

## Quick Start

```sh
# Render configuration and OpenTofu/Ansible stages locally
./green build

# Dry-run execution graph without provider side-effects
./green create --dry-run

# Provision and converge live infrastructure (requires credentials)
./green create
```

## Testing & Quality Assurance

```sh
bb test
bb golden
./scripts/launcher.sh
```
