---
name: package-posthog-green
description: Provisions and operates a single-node PostHog product analytics suite with PostgreSQL 17, ClickHouse, Redis 7.2, and Caddy on one DigitalOcean Droplet or Vultr instance.
license: MIT
---

# PostHog with Green

Operate one PostHog deployment from non-secret `colors.yml`. Read
[references/configuration.md](references/configuration.md) before changing
configuration or running a lifecycle operation.

## Compute providers

`provider-compute` selects `digitalocean` (the default; `COLORS_PAR_DO_TOKEN`,
`digitalocean-region`, `-size`, `-image`, `-ssh-sources`, `-http-sources`) or
`vultr` (`COLORS_PAR_VULTR_API_KEY`, `vultr-region`, `-plan`, `-os-id`,
`-ssh-sources`, `-http-sources`). Keys of the unselected provider are ignored,
so one `colors.yml` can carry both. Switching providers is a rebuild, never an
apply: with a machine in state the package refuses both `create` and `delete`
until `provider-compute` is set back to the recorded provider and the
deployment deleted first.

## Safety

- Keep credentials in gitignored `.envrc.private` as `COLORS_PAR_*` variables.
- Never set `COLORS_PAR_PROFILE` or edit/commit `.colors/`.
- Keep `compute-prevent-destroy: true`; deletion requires separate explicit
  authorization and a one-run environment override.
- Build and dry-run before a real create.

```sh
./green build
./green create --dry-run
./green create
```

## The machine keypair

The deployment owns its SSH key per the workspace SSH Keypair Standard. With no
`<provider>-ssh-keys` (`digitalocean-ssh-keys` or `vultr-ssh-keys`) in
`colors.yml`, the first real `create` generates `~/.ssh/<profile>`, registers
it at the provider under the profile name, and a successful `delete` removes it
last.

The key lives outside the checkout, so cloning the deployment repository
elsewhere does not carry access — copy `~/.ssh/<profile>`(`.pub`) deliberately.
A key with no state, or a provider key named after the profile that this
deployment's state does not own, stops the run: verify at the provider before
removing anything, and never delete a key whose fingerprint is not yours.
Rotation is a rebuild. Supplying `<provider>-ssh-keys` opts out and the
package then touches no key material.

The machine is named after the profile; `<provider>-name` is an optional
override, not a required key.

Convergence also writes a `~/.ssh/config` block, so reaching the host needs no
address, user or `-i` flag:

```sh
ssh <profile> 'cd /opt/posthog && docker compose ps'
```

Real create includes public HTTPS health, synthetic event capture, and backup
service verification.
