# Configuration

Required non-secret keys are demonstrated in the package `colors.yml`.
Validation accumulates every problem and exits 2, so a fresh `./green build`
lists all of them at once rather than one per run.

## Private environment variables

The stack carries no default for any of these. A real `create` fails before the
first provider call rather than falling back to a value published here.

```text
COLORS_PAR_DO_TOKEN            # provider-compute: digitalocean
COLORS_PAR_VULTR_API_KEY       # provider-compute: vultr
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

Never set `COLORS_PAR_PROFILE`. Only the selected compute provider's token is
required.

## Compute ownership

The pinned `colors-compute` library owns provider selection, remote S3/R2
state, deployment coordination, machine keys, network policy and the single
node. This package supplies singleton topology and SSH/HTTP ingress, then
uses the returned address, login user and SSH identity for its application
steps. New provider support belongs in the library; consumers update its pin.
The application needs a supported Ubuntu image and sufficient memory for
PostHog and its data services. Build first to check adapter capabilities.

Use `posthog-ssh-sources` and `posthog-http-sources` for neutral CIDR
allowlists. Existing selected-provider source options remain compatible.
External account key references require `ssh-private-key-path`; external
private keys are never generated or removed. The local SSH block writes
`IdentityFile` only for a managed deployment key.

Existing `<profile>/posthog-infrastructure.tfstate` is refused before
compute mutation. Do not remove it to bypass this check: migrate ownership
explicitly or destroy the old deployment through its original version first.
Unreadable state and provider mismatches fail closed.

The default compute provider remains `digitalocean`. An explicit `COLORS_PAR_IP`
changes only the delete-cleanup target after a successful owned-state read;
it cannot bypass unreadable state or provider identity checks.

Remote state must use `provider-backend: r2` or `s3`. R2 uses
`COLORS_PAR_R2_ACCESS_KEY_ID` and `COLORS_PAR_R2_SECRET_ACCESS_KEY`; S3 uses
the ambient AWS credential chain. Other adapter credentials and capabilities
are maintained in the library. New provider support is a library pin update.
No additional private network is requested by default. An explicit supported
network reference is discovered and validated by the library without owning it.

## The machine keypair

With `<provider>-ssh-keys` absent the deployment owns its key, per the
workspace SSH Keypair Standard:

- The first real `create` generates `~/.ssh/<profile>` (ed25519, no passphrase,
  comment `<profile> managed by Colors`) and enforces `700` on `~/.ssh` and
  `600` on the private key on every real run.
- The compute stack declares the account key resource (`digitalocean_ssh_key`
  or `vultr_ssh_key`) named `<profile>` and references it by attribute, so
  ownership is decidable from state rather than from a name.
- Before applying, a REST preflight lists the provider account's keys with the
  selected provider's token. A key named after the profile that this
  deployment's state does not own stops the run.
- Convergence and the acceptance checks reach the host with that key
  explicitly (`private_key_file` in the rendered `ansible.cfg`, `-i` on every
  `ssh`), so nothing depends on an agent holding it.
- `delete` removes the local keypair **last**, only after the compute destroy
  succeeded. A failed delete leaves it, because it is still needed.
- `build` and `--dry-run` never read or create anything under `~/.ssh`; they
  render a fixed placeholder path so output stays byte-identical everywhere.

External key references require `ssh-private-key-path`; the library never
replaces or removes that private key.

## Reaching the host

Convergence writes a `~/.ssh/config` block per the workspace SSH Config
Standard — alias `<profile>`, the observed address and login user, and in keygen mode the
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
| Legacy compute state requires migration | The old `<profile>/posthog-infrastructure.tfstate` still exists | Migrate ownership explicitly or delete with the original package version; do not erase state to bypass the guard |
| Compute lifecycle refused | Ownership, provider identity, state or key access could not be established | Reconcile the library diagnostic before retrying |
| `must list at least one CIDR` / `not an IPv4 or IPv6 CIDR` | An empty or malformed `<provider>-ssh-sources` / `-http-sources` entry | Fix the list; an empty ssh list is a machine no one can reach |
| `already has an SSH key named …` and it does not match | A foreign key shares the name | Do not delete it. Investigate, or change `profile` |
| `refusing to manage ~/.ssh/config` | A hand-written `Host <profile>` stanza, or a global option above the first `Host` line | Remove or rename the stanza, or move the option below the managed block or into a `Host *` stanza at the end |
| `could not read the infrastructure state for the delete cleanup` | The backend is unreadable on a real `delete` | Fix the backend credentials and retry. `COLORS_PAR_IP` does not bypass the read or the provider guard; it only replaces a stale recorded address for the cleanup once the state has been read |

## Backups

The systemd timer named by `posthog-backup-oncalendar` runs a logical Postgres
`pg_dump` and a native ClickHouse `BACKUP DATABASE`, uploads both to R2 under
the profile prefix, and prunes local archives older than
`posthog-backup-retention-days`. ClickHouse is never captured with a hot `tar`:
that races the server's merges and produces an archive that cannot be restored.
