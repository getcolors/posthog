"""Launcher contract and small helpers, the port of
io.github.getcolors.posthog.utils."""

from __future__ import annotations

# Bump on any change a launcher pinned to an older commit could not survive.
CONTRACT = 1


def registrable_domain(host) -> str:
    return ".".join(str("" if host is None else host).split(".")[-2:])
