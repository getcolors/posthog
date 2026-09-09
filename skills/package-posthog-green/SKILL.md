---
name: package-posthog-green
description: Provisions and operates a single-node PostHog product analytics suite with PostgreSQL 17, ClickHouse, Redis 7.2, and Caddy on one VM through the shared colors-compute library.
license: MIT
---

# PostHog with Green

Operate one PostHog deployment from non-secret `colors.yml`. Read
[references/configuration.md](references/configuration.md) before changing
configuration or running a lifecycle operation.

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
External account key references may use `ssh-private-key-path` or operator/agent SSH configuration; external
private keys are never generated or removed. The local SSH block writes
`IdentityFile` only for a managed deployment key.

Existing `<profile>/posthog-infrastructure.tfstate` is refused before
compute mutation. Do not remove it to bypass this check: migrate ownership
explicitly or destroy the old deployment through its original version first.
Unreadable state and provider mismatches fail closed.

The default compute provider remains `digitalocean`. An explicit `COLORS_PAR_IP`
changes only the delete-cleanup target after a successful owned-state read;
it cannot bypass unreadable state or provider identity checks.

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
library never generates or deletes external key material. Supply
`ssh-private-key-path` explicitly.

The machine is named after the profile; `<provider>-name` is an optional
override, not a required key.

Convergence also writes a `~/.ssh/config` block, so reaching the host needs no
address, user or `-i` flag:

```sh
ssh <profile> 'cd /opt/posthog && docker compose ps'
```

Real create includes public HTTPS health, synthetic event capture, and backup
service verification.
