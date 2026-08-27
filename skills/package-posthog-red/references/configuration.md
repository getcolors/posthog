# Configuration

Required non-secret keys are demonstrated in the package `colors.yml`.
Validation accumulates every problem and exits 2, so a fresh `./green build`
lists all of them at once rather than one per run.

## Private environment variables

The stack carries no default for any of these. A real `create` fails before the
first provider call rather than falling back to a value published here.

```text
COLORS_PAR_DO_TOKEN
COLORS_PAR_CLOUDFLARE_API_TOKEN
COLORS_PAR_R2_ACCESS_KEY_ID
COLORS_PAR_R2_SECRET_ACCESS_KEY
COLORS_PAR_POSTHOG_BACKUP_R2_ACCESS_KEY_ID
COLORS_PAR_POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY
COLORS_PAR_POSTHOG_SECRET_KEY
COLORS_PAR_POSTHOG_POSTGRES_PASSWORD
COLORS_PAR_POSTHOG_OIDC_RSA_PRIVATE_KEY
COLORS_PAR_POSTHOG_ENCRYPTION_SALT_KEYS
COLORS_PAR_POSTHOG_ADMIN_PASSWORD
```

What the application secrets are for, since none is optional:

| Variable | Why the stack will not start without it |
|---|---|
| `POSTHOG_SECRET_KEY` | Django signing key. Never a value committed to a public repository. |
| `POSTHOG_POSTGRES_PASSWORD` | Percent-encoded into `DATABASE_URL`; also Temporal's store. |
| `POSTHOG_OIDC_RSA_PRIVATE_KEY` | OAuth setup aborts the web process without it, in a restart loop that reports as running. |
| `POSTHOG_ENCRYPTION_SALT_KEYS` | Shared by application and plugin server. Missing means the plugin server exits during startup and nothing is ingested. |
| `POSTHOG_ADMIN_PASSWORD` | The owner account. PostHog only lets the first user create an organization, so the deployment provisions it. |

Never set `COLORS_PAR_PROFILE`. No VPC UUID or CIDR is accepted: the package
looks up the default VPC for `digitalocean-region` at runtime and never creates
one.

## Image pins

Every image key is an exact pin, and two of them are constrained:

- `posthog-image` and `posthog-plugin-server-image` **must be the same commit**.
  They share a Postgres schema, so a floating tag on either side leaves the node
  process querying columns the application's migrations never created.
- `posthog-clickhouse-image` must be the version upstream develops against.
  PostHog's schema puts TTLs on `DateTime64` columns, which 24.8 rejects.
- `posthog-capture-image` is pinned by digest, because its published tag moves.

`posthog-postgres-image`, `posthog-redis-image`, `posthog-kafka-image`,
`posthog-temporal-image` and `caddy-image` have no PostHog-specific constraint.

## Backups

The systemd timer named by `posthog-backup-oncalendar` runs a logical Postgres
`pg_dump` and a native ClickHouse `BACKUP DATABASE`, uploads both to R2 under
the profile prefix, and prunes local archives older than
`posthog-backup-retention-days`. ClickHouse is never captured with a hot `tar`:
that races the server's merges and produces an archive that cannot be restored.
