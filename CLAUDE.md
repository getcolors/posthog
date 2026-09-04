# CLAUDE.md

## Repository

`posthog` is a tri-colour Package Skill (green, red, blue) for a single-node
PostHog analytics suite on DigitalOcean or Vultr. It manages OpenTofu
compute/firewall (with the regional default VPC lookup on DigitalOcean),
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

## The two-provider golden and parity axis

The package supports two compute providers, `digitalocean` (the default) and
`vultr`, per the workspace Compute Provider Standard
(`../workspace/standards/compute-provider.md`). They are selected by template
directory (`tools/infrastructure/<provider>/main.tf`), never by conditionals
inside one file, so a build is the only thing that proves a provider's tree
renders at all. `validate/compute-providers` is the registry: the advertised
names, each one's required keys, secret and `tofu-env`; per-provider checks
(the DigitalOcean VPC bans, the Vultr numeric os id) run only for the selected
provider and keys of the unselected provider are accepted and ignored.
`compute-key` scopes every provider key (`<provider>-ssh-sources`,
`<provider>-name`) and the CIDR validator refuses an empty ssh list or any
entry that is not a syntactically valid v4 or v6 CIDR before a provider is
contacted.

Switching providers is a rebuild, never an apply. Every compute template
records `provider` in its `params` output, and on every real create and delete
the start step reads the recorded params with backend credentials only and
refuses a mismatch — before provider-secret validation, and pre-empting it, so
a mistaken edit reports `state holds a <recorded> machine; set
provider-compute back to <recorded> and delete first` rather than a missing
token for the new provider. Params without a provider (a deployment created
before adoption) are held to `digitalocean`, and any other selection is
refused with `state holds a machine with no recorded provider … which makes
it a digitalocean machine; set provider-compute back to digitalocean and
delete first`. An unreadable backend is no state on a create and, through
`adopt-state`, fatal on a delete.

The operations behind all of that are not this package's code. Since the
delegation, ONCE's `compute` namespace (`io.github.getcolors.once.compute`,
the `compute` export of `package-once-red`, `package_once_blue.compute`)
implements the Compute Provider Standard: selection, the CIDR grammar and the
network contract, the name rules (checked on the *resolved* name, profile or
override), the switch and legacy-state refusals, the missing-`ip` refusal,
the state read and its adoption. What lives here is the data and the wiring —
the registry, the default provider, the `spec` value in each colour's
`validate` that hands both plus the sources map to ONCE, the templates, the
fixtures and goldens, `state-output`, and the `start-step` preflight that
calls ONCE's functions in the order above. `compute-name`, `compute-key`,
`cidrs`, `fallback-params` and `resolved-compute` remain as package-named
aliases so `tools` and the tests read as before. One thing is posthog's own
and deliberately not ONCE's: the `adopt-state` wrapper that applies the
`COLORS_PAR_IP` override after ONCE's adoption succeeded, so no other package
gains a way to point a delete's cleanup at an arbitrary host. The
pure-function matrix (CIDR table, name rules, per-provider checks, the switch
rules) is tested in ONCE, in all three colours and by its parity drivers;
this repository tests the wiring — one test per safety boundary through
`start-step` — and one spec-content test per colour, so a colour whose spec
drifts fails in that colour. The delegation also replaced posthog's own
wording: the CIDR errors are now ONCE's `:<key> must list at least one CIDR`
and `:<key> entry "<entry>" is not an IPv4 or IPv6 CIDR`, and the legacy
state above is refused with ONCE's two-clause message rather than the plain
switch message.

There are four fixtures — one per provider per keypair mode —
`test/fixtures/colors.yml` (`posthog-fixture`), `optout.yml`
(`posthog-optout`), `colors-vultr.yml` (`posthog-vultr-fixture`) and
`optout-vultr.yml` (`posthog-vultr-optout`), each with a committed golden
tree. `scripts/golden.sh` checks green against all four; `scripts/parity.sh`
renders all four through every colour and diffs the trees — and the colour
template trees — byte for byte. A provider without a golden is not advertised.
The live-verified matrix is recorded here once each provider has been through
a real create: DigitalOcean by `../posthog-digitalocean`; Vultr pending
`../posthog-vultr`.

## The SSH keypair

This package conforms to the workspace SSH Keypair Standard
(`../workspace/standards/ssh-keypair.md`). Read that document before touching
`green/src/clj/io/github/getcolors/posthog/ssh.clj` or its red/blue
counterparts.

The behaviour is ONCE's — `io.github.getcolors.once.ssh` — deliberately reused
rather than reimplemented, so one standard has one implementation. Absent
`<provider>-ssh-keys` in desired state means keygen mode: the package
generates `~/.ssh/<profile>`, declares the account key resource
(`digitalocean_ssh_key` or `vultr_ssh_key`) named after the profile and
references it by attribute, runs the provider REST preflight before applying
with the selected provider's token, names the key explicitly for Ansible
(`private_key_file`) and for every acceptance `ssh`, and removes the local key
last, only after the compute destroy succeeded. Present `<provider>-ssh-keys`
means opt-out: the package touches no key material and renders the historical
shape byte for byte.

What this repository adds is the build placeholder. ONCE derives key paths from
`$HOME` and commits no rendered output; posthog commits goldens, so `build` and
`--dry-run` render `/home/build-placeholder/.ssh/<profile>` instead. That is
why `ssh/rendered-only?` tests `:green/dry-run` as well as the event — a
dry-run that fell through to the real path would read `~/.ssh`, which the
standard forbids, and `bb test` covers exactly that.

The lifecycle integration is `start-step`, not the helper modules. The compute
state is read **once per run** (ONCE's `compute/read-state` over this
package's `state-output`, `{:params m}` or `{:error msg}`; only the SDK's
step error — the shape `tofu output` fails with — reads as unreadable, any
other exception propagates as a defect), on real create and real delete only,
before the validators, and that one read serves the provider guard,
`ensure-key!` and the adoption. Build and dry-run fill the placeholder; a real
create runs `ensure-key!` against the read, then the provider preflight, then
the `~/.ssh/config` checks; a real delete fills the real paths and adopts the
instance address through the fail-closed `adopt-state` — ONCE's
`compute/adopt-state` (a read error exits 1) inside this package's wrapper,
which is where an explicit `COLORS_PAR_IP` is honoured: it never skips the
read or the guard, it only replaces a stale recorded address after the read
succeeded. The create
matrix itself (leftover key, foreign key, interrupted create) is ONCE's and is
tested there; this package tests the delegation.

`bb golden` renders both keypair modes for every provider because the standard
has two modes. A change that only holds in one of them is not conforming.

## The `~/.ssh/config` block and the compute name

The `ansible-local` stage implements the workspace SSH Config Standard
(`../workspace/standards/ssh-config.md`): one `blockinfile` task giving the
operator `ssh <profile>`. The play is **this package's own copy** (standard
§7), the opposite choice from `ssh.clj` above, because it writes into a file
the operator shares with every host they reach. Address, user, alias and
`block_state` arrive as **Ansible extra-vars, never through Selmer**, which is
what keeps `build` byte-identical across workstations; `scripts/golden.sh`
fails if a dotted quad ever appears under `posthog-ansible-local`. Create
writes the block after compute and before convergence; delete removes it
*before* the destroy, the reverse of the keypair.

`<provider>-name` is optional per the Compute Name Standard
(`../workspace/standards/compute-name.md`): `validate/compute-name` (ONCE's
`compute/name`) resolves the profile or the override once, and every template interpolates
`<{ compute-name }>` for the machine and its firewall.

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

`colors.yml` is flat, non-secret desired state. Validation accumulates errors
and, on DigitalOcean, rejects every configurable VPC identifier: the OpenTofu
data source looks up the existing default VPC by `digitalocean-region`.

The root `colors.yml` is the only desired state no suite exercises — `bb test`
is unit tests and `bb golden` uses `test/fixtures/colors.yml` — so it drifts
silently. It went six required keys stale once, which made the
`cd green && ./green build` this file documents exit 2. Run it here after
changing either file.

Two image constraints are load-bearing rather than tidiness. `posthog-image` and
`posthog-plugin-server-image` must be **one commit**, because they share a
Postgres schema. `posthog-clickhouse-image` must be the version upstream pins:
PostHog's schema puts TTLs on `DateTime64` columns, which 24.8 rejects outright.

Eleven `COLORS_PAR_*` credentials are required, not six — the five application
secrets have no defaults, and `secret-errors` fails a real `create` before the
first provider call rather than falling back to a published value. The compute
token is the selected provider's alone: `COLORS_PAR_DO_TOKEN` or
`COLORS_PAR_VULTR_API_KEY`.

## Coupling

The package pins Green and ONCE in `green/deps.edn`, the Red SDK and
`package-once-red` in `red/package.json`, and the Blue SDK and
`package-once-blue` in `blue/pyproject.toml`. All three colours pin ONCE at the
**same rev** (`eea43c2`) — ONCE's own parity is what guarantees its colours
agree per commit. ONCE supplies the state-backend provider registry (backend
secrets and `tofu-env`), the whole SSH Keypair Standard implementation, and
the Compute Provider Standard's operations (`compute`), so the pin can never
go below `417d5f7`, the commit that added `compute`, itself above `bc06f2f`,
the commit that moved the machine keypair into the operator's `~/.ssh`; a
bump is its own change. The same ONCE rev is
also written by hand into the red launcher's `PINS` and the blue launcher's
PEP 723 header (through `green/tasks/pin.clj`), because a copied payload
resolves ONCE from there, not from these manifests. `blue/pyproject.toml`
carries a `[tool.uv] override-dependencies` block because `package-once-blue`
pins an older Blue rev; the override makes this package's Blue pin win.

Deployment launchers are copies of the skill payloads. Develop with
`POSTHOG_LIB_ROOT` (the repository root, for every colour; red also accepts the
`red/` dir directly), plus `GREEN_LIB_ROOT` and `ONCE_LIB_ROOT` for green;
after pushing package code run `bb pin` (in `green/`), which stamps all three
payloads from their unpinned birth forms, commit and push the stamped
launchers, then synchronize the installed payloads and root copies. Never
invent or hand-edit a SHA.

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
