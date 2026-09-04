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

## Compute keys

| Key | Meaning |
|---|---|
| `digitalocean-region` / `digitalocean-size` / `digitalocean-image` | Region, droplet size and image. |
| `digitalocean-name` | **Optional.** The droplet (and its firewall's) name; absent, blank or `REPLACE_ME` means the profile, per the Compute Name Standard. Changing it renames the droplet at the provider but not the running guest's hostname. |
| `digitalocean-ssh-keys` | **Optional, and meaningful by its absence.** Omit it for keygen mode (below). Supplying an existing account key id opts out: the package then generates, validates and deletes no key material and renders the historical shape. |
| `digitalocean-ssh-sources` / `digitalocean-http-sources` | CIDR allowlists for the firewall (22 and 80/443). |

## The machine keypair

With `digitalocean-ssh-keys` absent the deployment owns its key, per the
workspace SSH Keypair Standard:

- The first real `create` generates `~/.ssh/<profile>` (ed25519, no passphrase,
  comment `<profile> managed by Colors`) and enforces `700` on `~/.ssh` and
  `600` on the private key on every real run.
- The compute stack declares `digitalocean_ssh_key` named `<profile>` and
  references it by attribute, so ownership is decidable from state rather than
  from a name.
- Before applying, a REST preflight lists the DigitalOcean account's keys. A
  key named after the profile that this deployment's state does not own stops
  the run.
- Convergence and the acceptance checks reach the host with that key
  explicitly (`private_key_file` in the rendered `ansible.cfg`, `-i` on every
  `ssh`), so nothing depends on an agent holding it.
- `delete` removes the local keypair **last**, only after the compute destroy
  succeeded. A failed delete leaves it, because it is still needed.
- `build` and `--dry-run` never read or create anything under `~/.ssh`; they
  render a fixed placeholder path so output stays byte-identical everywhere.

There is no rotation verb: DigitalOcean key lists are ForceNew, so rotation is
`delete` then `create`.

## Reaching the host

Convergence writes a `~/.ssh/config` block per the workspace SSH Config
Standard — alias `<profile>`, the address, `User root`, and in keygen mode the
identity file — so operations need no address, no user and no `-i` flag:

```sh
ssh <profile> 'cd /opt/posthog && docker compose ps'
ssh <profile> 'cd /opt/posthog && docker compose logs --tail=50 plugins'
ssh <profile> 'systemctl status posthog-backup.timer'
```

The block is inserted at the top of the file and removed by `delete` before
the droplet is destroyed. A hand-written `Host <profile>` stanza outside the
managed markers, or an option standing above the first `Host` line, stops a
real `create` rather than being rewritten; the message names the line.

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

## Recovery

| Symptom | Cause | Action |
|---|---|---|
| `does not hold the machine key` | State exists, `~/.ssh/<profile>` does not — a fresh clone or a new workstation | Copy the keypair from where the deployment was created; a regenerated key cannot reach the existing host |
| `no compute state is readable` | Key on disk, no state — an interrupted create or an incomplete delete | Verify at DigitalOcean that no droplet survives, then remove `~/.ssh/<profile>`(`.pub`) and retry |
| `already has an SSH key named …` and it matches yours | A previous delete left the provider key | Verify no droplet survives, delete that key at DigitalOcean, retry |
| `already has an SSH key named …` and it does not match | A foreign key shares the name | Do not delete it. Investigate, or change `profile` |
| `refusing to manage ~/.ssh/config` | A hand-written `Host <profile>` stanza, or a global option above the first `Host` line | Remove or rename the stanza, or move the option below the managed block or into a `Host *` stanza at the end |
| `could not read the infrastructure state for the delete cleanup` | The backend is unreadable on a real `delete` | Fix the backend credentials, or supply `COLORS_PAR_IP` to address the droplet directly |

## Backups

The systemd timer named by `posthog-backup-oncalendar` runs a logical Postgres
`pg_dump` and a native ClickHouse `BACKUP DATABASE`, uploads both to R2 under
the profile prefix, and prunes local archives older than
`posthog-backup-retention-days`. ClickHouse is never captured with a hot `tar`:
that races the server's merges and produces an archive that cannot be restored.
