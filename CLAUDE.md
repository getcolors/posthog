# CLAUDE.md

## Repository

`posthog` is a tri-colour Package Skill (green, red, blue) for a single-node
PostHog analytics suite on one library-provisioned VM. It manages
Cloudflare DNS, and converges a ten-container Docker Compose stack:
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

## Shared compute ownership

All three colors depend on `colors-compute`, currently pinned to `422c3f39d22be93efa703da09eb192490942ede3`.
Read `../workspace/standards/compute-provider.md`, `compute-name.md` and
`compute-cluster.md` before changing this boundary. This package owns only
application requirements and singleton topology: role null, count 1. Its
`compute` module delegates to library `plan_deployment`, `orchestrate` and
`read_deployment`; do not add a provider registry, provider dispatch, compute
OpenTofu templates, backend implementation, state writer or key lifecycle here.
A newly supported provider requires only a library dependency update in consumers.
The default remains `digitalocean`; provider capabilities and option validation
are defined by the library. Neutral `posthog-ssh-sources` and
`posthog-http-sources` are accepted alongside the selected adapter's legacy keys.

Build writes library documents under `compute/shared` and `compute/nodes/0`.
Each stage receives the library `backend_plan` configuration. Remote state keys
are `<profile>/compute/shared.tfstate` and `<profile>/compute/nodes/0.tfstate`;
S3 uses ambient AWS credentials, R2 binds its explicit backend credentials in
private configuration. The deployment journal serializes mutations. Compute
credential checks occur inside the library after ownership/state inspection.
DNS remains an application stage with its separate `<profile>/posthog-dns.tfstate`.

The library refuses existing `<profile>/posthog-infrastructure.tfstate` before
mutation. That old monolithic state needs explicit ownership migration or
teardown using the original package version. Never delete a state object to
bypass this refusal. Unreadable state, identity mismatches, ambiguous resource
ownership and live results without an address fail closed. Build-only planned
addresses must never become fallback targets for create/delete.

The joined node supplies the address, login user, provider identity and SSH
identity for downstream application steps. Do not assume the user is root.
No private network is requested by default. Explicit network references and
adapter capabilities are library concerns. The ingress policy is TCP22/80/443;
empty HTTP sources close HTTP ingress.

An explicit `COLORS_PAR_IP` only changes the delete-cleanup target after a
successful owned-state read; it cannot bypass the read or its provider guard.

## SSH lifecycle and local configuration

Read `../workspace/standards/ssh-keypair.md` and `ssh-config.md` before edits.
The library owns key mode, registration preflight, journaled generation,
fingerprint checks and cleanup. Managed keys live at `~/.ssh/<profile>` and
are removed only after owned compute resources are destroyed. External provider
key references may use `ssh-private-key-path` or operator/agent SSH configuration; external key material is never
generated, rotated or deleted. There is no package `ssh-cleanup` step.

The package SSH helper only formats identities and deterministic build paths.
Build/dry-run use `/home/build-placeholder/.ssh/<profile>` and never inspect
operator key files or `~/.ssh/config`. Application Ansible uses the returned
login and explicit identity for both managed and external keys.

The package-owned `ansible-local/main.yml` contains the workspace locked,
atomic SSH-config updater. Keep its Python implementation identical across
colors. Runtime alias, address, user and removal mode arrive as Ansible
extra-vars, never rendered machine addresses. The managed block uses the profile
alias and includes `IdentityFile`/`IdentitiesOnly` only in managed mode. The
updater refuses conflicting unmanaged stanzas and leading global options.
Create updates the block after compute and before DNS/convergence; delete
removes it before compute destruction. Never replace this with `blockinfile`
or move key cleanup ahead of resource destruction.

## Build and migration checks

The four shared fixtures exercise managed/external keys on two adapters;
they are regression examples, not a package provider allowlist. Run native
Blue/Red/Green tests, Red typecheck, `scripts/parity.sh`, `scripts/golden.sh`
and `scripts/launcher.sh`. Golden acceptance requires reviewing the generated
application changes first. `scripts/check-compute-plan.py` checks singleton
stages, exact backend keys, absence of inline backend secrets and absence of
the old compute stage. Run the root example build with its workdir directed
to a temporary directory; it is separate from fixture coverage.

After dependency changes, build actual copied standalone payloads with no
`*_LIB_ROOT` overrides. Local tests alone do not prove their dependency pins.
Keep unrelated untracked compute-matrix artifacts out of migration commits.
Do not claim live deployment verification from an offline build.

## Layout and commands

The three implementations live in the tri-colour layout, matching `netbird` and
`clickstack`: canonical Clojure in `green/` (`green/bb.edn`, `green/deps.edn`,
`green/src/`, `green/tasks/`, tests under `green/test/clj`), TypeScript/Bun in
`red/`, and Python/uv in `blue/`. Green is canonical: a behavioural change lands
in all three colours in the same commit and passes `scripts/parity.sh`, which
renders all four fixtures through every colour and diffs the trees — and the colour
template trees (`red/resources`, blue's embedded `resources/`) — byte for byte.
The fixtures and the goldens are shared across colours at the repository root —
`test/fixtures/` and `test/resources/golden/` — with `green/test/fixtures` and
`green/test/resources` symlinks pointing at them. Each colour dir holds a
launcher symlink to its skill payload (`green/green`, `red/red`, `blue/blue`).

```sh
cd green && bb test
cd green && bb golden
cd green && bb golden:accept   # regenerate after an intended change — read the diff first
cd red && bun test && bun run typecheck
cd blue && uv run pytest
./scripts/parity.sh            # three colours, four fixtures, byte for byte
./scripts/launcher.sh          # from the repository root
cd green && ./green build
cd green && ./green create --dry-run
cd green && ./green create     # requires explicit authorization
cd green && ./green delete     # guarded and destructive
```

Never read or edit `.colors/`, read `.envrc.private`, export `COLORS_PAR_PROFILE`,
or weaken `compute-prevent-destroy`. Build and dry-run are credential-free and
must not touch `~/.ssh`.

## Invariants

`colors.yml` is flat, non-secret desired state. Validation accumulates application errors; the library validates compute
requirements and supported explicit network references.

The root `colors.yml` is the only desired state no suite exercises — `bb test`
is unit tests and `bb golden` uses `test/fixtures/colors.yml` — so it drifts
silently. It went six required keys stale once, which made the
`cd green && ./green build` this file documents exit 2. Run it here after
changing either file.

Two image constraints are load-bearing rather than tidiness. `posthog-image` and
`posthog-plugin-server-image` must be **one commit**, because they share a
Postgres schema. `posthog-clickhouse-image` must be the version upstream pins:
PostHog's schema puts TTLs on `DateTime64` columns, which 24.8 rejects outright.

The five application secrets have no defaults, and `secret-errors` fails a real `create` before the
first provider call rather than falling back to a published value. The compute
credentials are the selected library adapter's alone.

## Dependency pins and launchers

Keep colors-compute's revision aligned in all three manifests/locks, the root
Red manifest, Blue PEP723 payload metadata and `green/tasks/pin.clj`. ONCE is
still pinned at `38e3cd66674a32fb96605e1b17ae6791086ad5c1` for application DNS
backend credential mapping and utility helpers; it no longer owns compute or
machine keys for this package. S3 credentials stay ambient. Preserve the DNS
R2 credential mapping when changing ONCE helpers.

Use `POSTHOG_LIB_ROOT` for repository development. Canonical `bb pin` in
`green/` stamps the three launchers only after the source commit is pushed.
Use a clean temporary worktree if unrelated untracked files prevent pinning;
never fabricate a SHA or include those files merely to satisfy the guard.
Then test the copied payloads, commit and push the stamps. Deployment
launchers are copies, not symlinks. Avoid duplicate transitive Git package
entries in Red's standalone PINS: Bun can fail before package loading.

## Documentation

`index.html` is this repository's landing page and carries two analytics tags:
GA4 measurement ID `G-4VKP1WY4QJ`, whose explicit `page_title` must exactly
equal the decoded HTML `<title>` and stay distinct and stable so one Analytics
property can separate repositories, and the self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`,
which shares one site ID across every page because `getcolors.github.io/<repo>/`
paths already encode the repository. Never add one tag without the other.

## Git

Work on the current branch. Do not commit or push unless explicitly authorized.
