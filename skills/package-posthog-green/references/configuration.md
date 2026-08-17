# Configuration

Required non-secret keys are demonstrated in the package `colors.yml`.

The enabled deployment requires these private environment variables:

```text
COLORS_PAR_DO_TOKEN
COLORS_PAR_CLOUDFLARE_API_TOKEN
COLORS_PAR_R2_ACCESS_KEY_ID
COLORS_PAR_R2_SECRET_ACCESS_KEY
COLORS_PAR_POSTHOG_BACKUP_R2_ACCESS_KEY_ID
COLORS_PAR_POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY
```

Never set `COLORS_PAR_PROFILE`. No VPC UUID or CIDR is accepted: the package
looks up `default-<digitalocean-region>` at runtime and never creates a VPC.

`posthog-image`, `posthog-postgres-image`, `posthog-clickhouse-image`, `posthog-redis-image`,
and `caddy-image` are exact pins. Backups take logical Postgres dumps and ClickHouse snapshots
and retain local archives according to `posthog-backup-retention-days`.
