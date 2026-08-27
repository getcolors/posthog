// Launcher contract and small helpers, the port of
// io.github.getcolors.posthog.utils.

// Bump on any change a launcher pinned to an older commit could not survive.
export const contract = 1;

export function registrableDomain(host: unknown): string {
  return String(host ?? "").split(".").slice(-2).join(".");
}
