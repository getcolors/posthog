# CLAUDE.md

## Repository

`posthog` is a tri-colour Package Skill (green, red, blue) for a single-node
PostHog analytics suite on DigitalOcean. It manages OpenTofu compute/firewall,
dynamic regional default VPC lookup, Cloudflare DNS, and converges a
ten-container Docker Compose stack:
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

## Layout and commands

The three implementations live in the tri-colour layout, matching `netbird` and
`clickstack`: canonical Clojure in `green/` (`green/bb.edn`, `green/deps.edn`,
`green/src/`, `green/tasks/`, tests under `green/test/clj`), TypeScript/Bun in
`red/`, and Python/uv in `blue/`. Green is canonical: a behavioural change lands
in all three colours in the same commit and passes `scripts/parity.sh`, which
renders the fixture through every colour and diffs the trees — and the colour
template trees (`red/resources`, blue's embedded `resources/`) — byte for byte.
The fixture and the goldens are shared across colours at the repository root —
`test/fixtures/` and `test/resources/golden/` — with `green/test/fixtures` and
`green/test/resources` symlinks pointing at them. Each colour dir holds a
launcher symlink to its skill payload (`green/green`, `red/red`, `blue/blue`).

```sh
cd green && bb test
cd green && bb golden
cd green && bb golden:accept   # regenerate after an intended change — read the diff first
cd red && bun test && bun run typecheck
cd blue && uv run pytest
./scripts/parity.sh            # three colours, byte for byte
./scripts/launcher.sh          # from the repository root
cd green && ./green build
cd green && ./green create --dry-run
cd green && ./green create     # requires explicit authorization
cd green && ./green delete     # guarded and destructive
```

Never read or edit `.colors/`, read `.envrc.private`, export `COLORS_PAR_PROFILE`,
or weaken `compute-prevent-destroy`. Build and dry-run are credential-free.

## Invariants

`colors.yml` is flat, non-secret desired state. Validation accumulates errors
and rejects every configurable VPC identifier: the OpenTofu data source looks up
the existing default VPC by `digitalocean-region`.

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
first provider call rather than falling back to a published value.

## Coupling

The package pins Green and ONCE in `green/deps.edn`, the Red SDK and
`package-once-red` in `red/package.json`, and the Blue SDK and
`package-once-blue` in `blue/pyproject.toml`. All three colours pin ONCE at the
**same rev** (`98d3cfa`) — ONCE's own parity is what guarantees its colours
agree per commit. ONCE supplies only the state-backend provider registry here
(backend secrets and `tofu-env`); the pin is deliberately frozen, and a bump is
its own change. `blue/pyproject.toml` carries a `[tool.uv] override-dependencies`
block because `package-once-blue@98d3cfa` pins an older Blue rev (`369c5aa`);
the override makes this package's Blue pin win.

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
