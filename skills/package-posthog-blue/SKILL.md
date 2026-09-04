---
name: package-posthog-blue
description: Provisions and operates a single-node PostHog product analytics suite with PostgreSQL 17, ClickHouse 24.8, Redis 7.2, and Caddy on DigitalOcean.
license: MIT
---

# PostHog with Blue

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
./blue build
./blue create --dry-run
./blue create
```

## The machine keypair

The deployment owns its SSH key per the workspace SSH Keypair Standard. With no
`digitalocean-ssh-keys` in `colors.yml`, the first real `create` generates
`~/.ssh/<profile>`, registers it at DigitalOcean under the profile name, and a
successful `delete` removes it last.

The key lives outside the checkout, so cloning the deployment repository
elsewhere does not carry access — copy `~/.ssh/<profile>`(`.pub`) deliberately.
A key with no state, or a DigitalOcean key named after the profile that this
deployment's state does not own, stops the run: verify at the provider before
removing anything, and never delete a key whose fingerprint is not yours.
Rotation is a rebuild. Supplying `digitalocean-ssh-keys` opts out and the
package then touches no key material.

The droplet is named after the profile; `digitalocean-name` is an optional
override, not a required key.

Convergence also writes a `~/.ssh/config` block, so reaching the host needs no
address, user or `-i` flag:

```sh
ssh <profile> 'cd /opt/posthog && docker compose ps'
```

Real create includes public HTTPS health, synthetic event capture, and backup
service verification.
