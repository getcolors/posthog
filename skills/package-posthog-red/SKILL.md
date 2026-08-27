---
name: package-posthog-red
description: Provisions and operates a single-node PostHog product analytics suite with PostgreSQL 17, ClickHouse 24.8, Redis 7.2, and Caddy on DigitalOcean.
license: MIT
---

# PostHog with Red

Operate one PostHog deployment from non-secret `colors.yml`. Read
[references/configuration.md](references/configuration.md) before changing
configuration or running a lifecycle operation.

## Safety

- Keep credentials in gitignored `.envrc.private` as `COLORS_PAR_*` variables.
- Never set `COLORS_PAR_PROFILE` or edit/commit `.colors/`.
- Keep `compute-prevent-destroy: true`; deletion requires separate explicit
  authorization and a one-run environment override.
- Build and dry-run before a real create.

```sh
./red build
./red create --dry-run
./red create
```

Real create includes public HTTPS health, synthetic event capture, and backup service verification.
