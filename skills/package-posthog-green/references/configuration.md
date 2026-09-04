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

## Compute providers

`provider-compute` selects the provider; its template lives in its own
directory and every provider key is provider-scoped, so keys of the unselected
provider are accepted and ignored and one `colors.yml` stays portable.

| Provider | Credential | Keys |
|---|---|---|
| `digitalocean` (default) | `COLORS_PAR_DO_TOKEN` | `digitalocean-region`, `digitalocean-size`, `digitalocean-image`, `digitalocean-ssh-sources`, `digitalocean-http-sources`; optional `digitalocean-name`, `digitalocean-ssh-keys` |
| `vultr` | `COLORS_PAR_VULTR_API_KEY` | `vultr-region`, `vultr-plan`, `vultr-os-id` (numeric; 2284 is Ubuntu 24.04 LTS x64), `vultr-ssh-sources`, `vultr-http-sources`; optional `vultr-name`, `vultr-ssh-keys` |

Both providers put a provider firewall in front of the host — inbound 22 from
`<provider>-ssh-sources`, 80 and 443 from `<provider>-http-sources`, nothing
else — and Ansible never manages `ufw` for those ports. `<provider>-ssh-sources`
must list at least one entry and every entry of both lists must be a valid
IPv4 or IPv6 CIDR; validation refuses anything else before a provider is
contacted. An empty `<provider>-http-sources` is allowed and means no public
HTTP. No VPC is created: on DigitalOcean the region's `default-<region>` VPC is
discovered at runtime and `digitalocean-vpc-uuid` / `digitalocean-vpc-cidr` are
refused; on Vultr the instance attaches no VPC.

**Switching providers is a rebuild, never an apply.** All providers share one
state key, so a changed `provider-compute` on a profile with a machine in state
would plan a cross-provider replacement. Every real `create` and `delete` reads
the recorded compute output first and refuses a mismatch with
`state holds a <recorded> machine; set provider-compute back to <recorded> and
delete first`; a deployment created before the provider was recorded is held
to `digitalocean`. Delete refuses too, because it would render and destroy the
selected provider's template. An unreadable backend counts as no state on a
create and fails a delete loudly.

| Key | Meaning |
|---|---|
| `<provider>-name` | **Optional.** The machine (and its firewall's) name; absent, blank or `REPLACE_ME` means the profile, per the Compute Name Standard. Changing it renames the resource at the provider but not the running guest's hostname. On Vultr the label updates in place; the package never sets a Vultr hostname, which is ForceNew. |
| `<provider>-ssh-keys` | **Optional, and meaningful by its absence.** Omit it for keygen mode (below). Supplying an existing account key id opts out: the package then generates, validates and deletes no key material and renders the historical shape. |
| `<provider>-ssh-sources` / `<provider>-http-sources` | CIDR allowlists for the firewall (22 and 80/443). |

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

There is no rotation verb: machine key lists are ForceNew on both providers,
so rotation is `delete` then `create`.

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
| `no compute state is readable` | Key on disk, no state — an interrupted create or an incomplete delete | Verify at the provider that no machine survives, then remove `~/.ssh/<profile>`(`.pub`) and retry |
| `already has an SSH key named …` and it matches yours | A previous delete left the provider key | Verify no machine survives, delete that key at the provider, retry |
| `state holds a <provider> machine; set provider-compute back …` | `provider-compute` was changed on a profile with a machine in state | Set it back to the recorded provider, `delete`, then `create` on the new one |
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
