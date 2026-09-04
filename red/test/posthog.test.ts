import { afterEach, beforeEach, describe, expect, spyOn, test } from "bun:test";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { runtime } from "red/runtime";
import { renderTemplate } from "red/scaffold";
import type { Opts } from "red/workflow";
import * as ssh from "../src/ssh.ts";
import * as sshConfig from "../src/ssh-config.ts";
import * as tools from "../src/tools.ts";
import * as validate from "../src/validate.ts";
import * as workflow from "../src/workflow.ts";

const fixtureFile = resolve(import.meta.dir, "../../test/fixtures/colors.yml");
const optoutFile = resolve(import.meta.dir, "../../test/fixtures/optout.yml");

function readFixture(path: string, overrides: Opts): Opts {
  const text = readFileSync(path, "utf8").replaceAll("WORKDIR", ".colors");
  return {
    ...(Bun.YAML.parse(text) as Opts),
    "red/state-file": path,
    ...overrides,
  };
}

const vultrFile = resolve(import.meta.dir, "../../test/fixtures/colors-vultr.yml");
const vultrOptoutFile = resolve(import.meta.dir, "../../test/fixtures/optout-vultr.yml");

const fixture = (overrides: Opts = {}) => readFixture(fixtureFile, overrides);
const optout = (overrides: Opts = {}) => readFixture(optoutFile, overrides);
const vultr = (overrides: Opts = {}) => readFixture(vultrFile, overrides);
const vultrOptout = (overrides: Opts = {}) => readFixture(vultrOptoutFile, overrides);

// Every credential a real create or delete asks for, on either provider.
const secrets: Opts = {
  "do-token": "t", "vultr-api-key": "t", "cloudflare-api-token": "t",
  "posthog-secret-key": "s", "posthog-postgres-password": "p",
  "posthog-oidc-rsa-private-key": "k", "posthog-encryption-salt-keys": "k",
  "posthog-admin-password": "p", "posthog-backup-r2-access-key-id": "k",
  "posthog-backup-r2-secret-access-key": "s",
  "r2-access-key-id": "k", "r2-secret-access-key": "s",
};

// A stubbed `tofu output`: the recorded params, or a failed read.
function stateOutputs(params: Opts | undefined) {
  runtime.exec = async () => params === undefined
    ? { exit: 1, out: "", err: "Unauthorized" }
    : { exit: 0, out: JSON.stringify({ params: { value: params } }), err: "" };
}

// ~/.ssh redirection: ONCE's ssh module and this package's ssh-config both
// read $HOME at call time, exactly so tests can point them at a fresh
// temporary home.
let savedHome: string | undefined;
let home: string;
beforeEach(() => {
  savedHome = process.env.HOME;
  home = mkdtempSync(join(tmpdir(), "posthog-red-test"));
  process.env.HOME = home;
});
afterEach(() => {
  process.env.HOME = savedHome;
  rmSync(home, { recursive: true, force: true });
});

function write(path: string, content: string) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, content);
}

const resources = resolve(import.meta.dir, "../resources/tools");
const slurp = (path: string) => readFileSync(join(resources, path), "utf8");
const playbook = slurp("ansible/main.yml");
const compose = slurp("ansible/compose.yml");
const caddyfile = slurp("ansible/Caddyfile");
const backup = slurp("ansible/backup");
const checkpoint = slurp("ansible/checkpoint.sql");
const owner = slurp("ansible/owner.py");

const realExec = runtime.exec;
const tmpdirs: string[] = [];
afterEach(() => {
  runtime.exec = realExec;
  for (const dir of tmpdirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});
function tempWorkdir(): string {
  const dir = mkdtempSync(join(tmpdir(), "posthog-red-test-"));
  tmpdirs.push(dir);
  return dir;
}

// --- tools -------------------------------------------------------------------

describe("tools", () => {
  test("delete cleanup skips when state has no compute", async () => {
    // With the instance already gone the inventory would render 192.0.2.10;
    // there is no host to reach, so the step must not run the playbook and the
    // teardown must continue past it.
    runtime.exec = () => { throw new Error("playbook must not run"); };
    const r = await tools.ansibleStep(fixture({ "red/event": "delete", workdir: tempWorkdir() }));
    expect(r["red/exit"]).toBe(0);
    expect(r["posthog/cleanup"]).toBe("skipped-no-compute");
  });

  test("delete cleanup targets the adopted address", async () => {
    // When the start step recovered the instance address from state, the
    // cleanup playbook runs against it, never the documentation fallback.
    const workdir = tempWorkdir();
    let inventoryDuringRun = "";
    runtime.exec = async (_cmd, options) => {
      inventoryDuringRun = readFileSync(join(String(options?.cwd), "inventory.json"), "utf8");
      return { exit: 0, out: "", err: "" };
    };
    await tools.ansibleStep(fixture({ "red/event": "delete", ip: "203.0.113.7", workdir }));
    expect(inventoryDuringRun).toContain("203.0.113.7");
  });

  test("infrastructure discovers default vpc", () => {
    const data = tools.infrastructureData(fixture());
    expect(tools.cidrs(data, "digitalocean-http-sources")).toEqual(["0.0.0.0/0", "::/0"]);
  });

  test("template directory follows the provider", () => {
    // Providers are selected by directory, never by conditionals in one file.
    expect(tools.infrastructureTemplate(fixture()).name).toBe("infrastructure/digitalocean/main.tf");
    expect(tools.infrastructureTemplate(vultr()).name).toBe("infrastructure/vultr/main.tf");
  });

  test("infrastructure data reads the selected provider sources", () => {
    const data = tools.infrastructureData(vultr());
    expect(data["ssh-sources-hcl"]).toBe('["0.0.0.0/0", "::/0"]');
    expect(data["ssh-keygen"]).toBe(true);
    expect(data["compute-name"]).toBe("posthog-vultr-fixture");
    expect(tools.infrastructureData(vultrOptout())["ssh-keygen"]).toBe(false);
    // A DigitalOcean list is not read for a Vultr render.
    expect(tools.infrastructureData(vultr({ "vultr-ssh-sources": [],
      "digitalocean-ssh-sources": ["10.0.0.0/8"] }))["ssh-sources-hcl"]).toBe("[]");
  });

  test("fallback params are the documentation address on every provider", () => {
    for (const f of [fixture, vultr]) {
      expect(tools.fallbackParams(f())).toEqual({ ip: "192.0.2.10", user: "root", sudoer: "root", name: f().profile });
    }
    // `name` is the resolved compute name, as the templates' params output is.
    expect(tools.fallbackParams(fixture({ "digitalocean-name": "analytics-1" })).name).toBe("analytics-1");
    expect(tools.fallbackParams(optout()).name).toBe("posthog-optout");
  });

  test("empty http sources drop the public rules", () => {
    // An empty list is allowed and means no public HTTP; DigitalOcean rejects
    // an inbound rule with no sources, so the two rules are left out rather
    // than rendered empty. A non-empty list renders exactly as before.
    expect(tools.infrastructureData(fixture())["http-sources?"]).toBe(true);
    const data = tools.infrastructureData(fixture({ "digitalocean-http-sources": [] }));
    expect(data["http-sources?"]).toBe(false);
    expect(data["http-sources-hcl"]).toBe("[]");
    const render = (opts: Opts) => renderTemplate(tools.infrastructureTemplate(opts),
      tools.infrastructureData(opts), tools.templateOpts);
    const full = render(fixture());
    const none = render(fixture({ "digitalocean-http-sources": [] }));
    expect(full.match(/inbound_rule/g)?.length).toBe(3);
    expect(none.match(/inbound_rule/g)?.length).toBe(1);
    expect(none).toContain('port_range       = "22"');
    expect(none).not.toContain("source_addresses = []");
    // The Vultr rules are a for_each over the set and vanish on their own.
    const vultrNone = render(vultr({ "vultr-http-sources": [] }));
    expect(vultrNone).toContain("http_sources = []");
    expect(vultrNone).toContain("for_each          = toset(local.http_sources)");
  });

  test("infrastructure data carries the ssh mode and the compute name", () => {
    expect(tools.infrastructureData(fixture())["ssh-keygen"]).toBe(true);
    expect(tools.infrastructureData(optout())["ssh-keygen"]).toBe(false);
    expect(tools.infrastructureData(fixture())["compute-name"]).toBe("posthog-fixture");
    expect(tools.infrastructureData(optout())["compute-name"]).toBe("posthog-optout");
  });

  test("the ansible stage names the generated key in keygen mode", () => {
    // Remote Ansible must be able to use the generated key: nothing guarantees
    // an agent holds it, so ansible.cfg names it under private_key_file.
    const data = tools.ansibleData(fixture({ "ssh-keygen": true,
      "ssh-private-key-path": "/home/x/.ssh/posthog-fixture" }));
    expect(data["ssh-keygen"]).toBe(true);
    expect(data["ssh-private-key-path"]).toBe("/home/x/.ssh/posthog-fixture");
    expect(tools.ansibleData(optout())["ssh-keygen"]).toBe(false);
  });

  test("acceptance ssh selects the generated key", async () => {
    // Every ssh the acceptance step runs threads the identity arguments, so
    // the check reaches the host with the deployment's own key in keygen mode
    // and with the operator's arrangements in opt-out mode.
    let seen: string[] = [];
    runtime.exec = async (cmd) => { seen = cmd; return { exit: 0, out: "ok\n", err: "" }; };
    expect(await tools.sshOut({ ip: "203.0.113.7", "ssh-keygen": true,
      "ssh-private-key-path": "/home/x/.ssh/posthog-fixture" }, "true", 1000)).toBe("ok");
    expect(seen.slice(5, 9)).toEqual(["-o", "IdentitiesOnly=yes", "-i", "/home/x/.ssh/posthog-fixture"]);
    expect(seen[9]).toBe("root@203.0.113.7");
    await tools.sshOut({ ip: "203.0.113.7" }, "true", 1000);
    expect(seen[5]).toBe("root@203.0.113.7");
  });

  test("dns is apex and proxied", () => {
    const json = tools.dnsJson(fixture({ ip: "192.0.2.10", "posthog-zone": "example.com" }));
    expect(json).toContain("posthog.example.com");
    expect(json).toContain("192.0.2.10");
    // Assert the value, not the key: "proxied" is in the rendered record
    // either way, so this passed on an unproxied record too.
    expect(json).toContain("\"proxied\" : true");
  });

  test("dns proxying can be declined", () => {
    // It was hardcoded, so setting the key did nothing and said nothing.
    expect(tools.dnsJson(fixture({ ip: "192.0.2.10", "posthog-zone": "example.com",
      "cloudflare-proxied": false }))).toContain("\"proxied\" : false");
  });

  test("inventory keeps one private target", () => {
    const inventory = tools.inventory(fixture({ ip: "192.0.2.10" }));
    expect(inventory).toContain("192.0.2.10");
    expect(inventory).toContain("posthog-fixture");
  });

  test("convergence migrates then waits on the web service", () => {
    // Waiting on pg_isready declared the stack converged while the application
    // was still unmigrated, so the ordering here is the contract.
    const migrate = playbook.indexOf("manage.py migrate_clickhouse");
    const health = playbook.indexOf("/_health/");
    expect(migrate).toBeGreaterThanOrEqual(0);
    expect(health).toBeGreaterThanOrEqual(0);
    expect(migrate).toBeLessThan(health);
  });

  test("broker does not evict", () => {
    expect(compose).toContain("--maxmemory-policy noeviction");
    // The guarantee is not the in-container check -- which blocks on
    // run_async_migrations -- but that the playbook migrates explicitly and
    // fails the converge when that fails.
    expect(playbook).toContain("manage.py migrate && python manage.py migrate_clickhouse");
  });

  test("compose template carries no default credential", () => {
    const rendered = tools.ansibleData(fixture());
    // The Django signing key was a constant in this public repository, so a
    // rendered artefact must never be able to carry one again.
    expect(compose).not.toContain("insecure-secret-key");
    expect(/POSTGRES_PASSWORD: posthog/.test(compose)).toBe(false);
    expect(rendered["posthog-secret-key"]).toBeUndefined();
    expect(compose).toContain("urlencode | replace('/', '%2F')");
  });

  test("capture is judged by the stored row not the status", () => {
    // The previous step computed a capture result and never looked at it.
    expect(tools.ingestionVerdict("200", 4, 5)).toBe("ingested");
    expect(tools.ingestionVerdict("200", 4, 4)).toBe("dropped");
    expect(tools.ingestionVerdict("202", 4, undefined)).toBe("dropped");
    expect(tools.ingestionVerdict("401", 4, 4)).toBe("rejected");
    expect(tools.ingestionVerdict(undefined, 4, 4)).toBe("unreachable");
  });

  test("backup must be fresh and non-empty", () => {
    const since = Date.parse("2026-08-17T02:30:00Z");
    const entry = (size: number, modTime: string) => ({ Size: size, ModTime: modTime });
    expect(tools.freshBackup([entry(1024, "2026-08-17T02:30:05Z")], since)).toBe(true);
    expect(tools.freshBackup([entry(1024, "2026-08-17T04:30:05+02:00")], since)).toBe(true);
    expect(tools.freshBackup([entry(1024, "2026-08-16T02:30:05Z")], since)).toBe(false);
    expect(tools.freshBackup([entry(0, "2026-08-17T02:30:05Z")], since)).toBe(false);
    expect(tools.freshBackup([], since)).toBe(false);
    expect(tools.freshBackup(undefined, since)).toBe(false);
  });

  test("clickhouse backup is native and has no torn fallback", () => {
    // A hot tar of the data directory races running merges and produces an
    // archive that cannot be restored; a failed backup must fail the run.
    expect(backup).toContain("BACKUP DATABASE");
    expect(backup).toContain("/var/lib/clickhouse/backups/");
    expect(backup).not.toContain("tar -czf");
  });

  test("datastores start before migrations and app after", () => {
    // Bringing `web` up first put the image's own startup migration in a race
    // with the explicit one, and the loser died on "relation already exists".
    const start = playbook.indexOf("docker compose up -d db redis kafka clickhouse");
    const migrate = playbook.indexOf("manage.py migrate_clickhouse");
    const app = playbook.indexOf("Converge the application containers");
    expect(start).toBeGreaterThanOrEqual(0);
    expect(migrate).toBeGreaterThanOrEqual(0);
    expect(app).toBeGreaterThanOrEqual(0);
    expect(start).toBeLessThan(migrate);
    expect(migrate).toBeLessThan(app);
    // A handler flush must not be able to start the stack ahead of migrations.
    expect(playbook).not.toContain("Restart PostHog stack");
  });

  test("clickhouse has coordination for replicated tables", () => {
    // migrate_clickhouse passes replicated=True unconditionally, so every table
    // is a ReplicatedMergeTree and the first CREATE dies with "There is no
    // Zookeeper configuration in server config" unless Keeper is configured.
    expect(playbook).toContain("<keeper_server>");
    expect(playbook).toContain("<zookeeper>");
    // Replicated table paths substitute these; without them the DDL is invalid.
    expect(playbook).toContain("<shard>");
    expect(playbook).toContain("<replica>");
    // cluster.py selects hosts with getMacro on both of these; without them
    // migrate_clickhouse dies with "No macro hostClusterType in config".
    expect(playbook).toContain("<hostClusterType>online</hostClusterType>");
    // "data" matches callers requesting DATA and the ALL wildcard; "all" would
    // match only the latter.
    expect(playbook).toContain("<hostClusterRole>data</hostClusterRole>");
    expect(compose).toContain("config.d/keeper.xml");
  });

  test("clickhouse config changes reach the container", () => {
    // Single-file bind mounts bind to the inode, and copy replaces by rename, so
    // without a recreate the server keeps serving the previous config while the
    // host file looks correct.
    expect(playbook).toContain("--force-recreate clickhouse");
    expect(playbook).toContain("clickhouse_keeper_config.changed");
    expect(playbook).toContain("clickhouse_clusters_config.changed");
    // Change flags alone are not enough: on a converge where the files were
    // already correct, copy reports no change while the container still serves
    // the config it started with. The reload must key off the server's state.
    expect(playbook).toContain("FROM system.macros WHERE macro = 'hostClusterType'");
    expect(playbook).toContain("FROM system.named_collections WHERE name = 'msk_cluster'");
    expect(playbook).toContain("clickhouse_macros.stdout");
    // And the migration must not start before the reloaded server is answering.
    const reload = playbook.indexOf("--force-recreate clickhouse");
    const wait = playbook.indexOf("Wait for ClickHouse to accept queries");
    const migrate = playbook.indexOf("manage.py migrate_clickhouse");
    expect(reload).toBeLessThan(wait);
    expect(wait).toBeLessThan(migrate);
  });

  test("cluster hosts are reachable from every container", () => {
    // system.clusters is read by web and worker, which dial the advertised host;
    // loopback there is the client container, not ClickHouse.
    expect(playbook).not.toContain("<host>127.0.0.1</host><port>9000</port>");
    expect(playbook).toContain("<host>clickhouse</host><port>9000</port>");
    // Keeper is embedded in the ClickHouse server, so those stay on loopback.
    expect(playbook).toContain("<host>127.0.0.1</host>\n                      <port>9181</port>");
  });

  test("ingestion tier is present", () => {
    // PostHog's path is capture -> Kafka -> plugin-server -> ClickHouse, and its
    // ClickHouse migrations create Kafka engine tables, so a broker is required
    // for the schema to exist at all -- not only for events to flow.
    expect(compose).toContain("  kafka:");
    expect(compose).not.toContain("./bin/plugin-server");
    expect(compose).toContain("KAFKA_HOSTS");
    // Every named collection the migrations may reference must resolve.
    // The full set from upstream's docker/clickhouse/config.d/default.xml;
    // settings.py only reveals six of the eight.
    for (const collection of ["msk_cluster", "warpstream_ingestion", "warpstream_calculated_events",
      "warpstream_replay", "warpstream_shared", "warpstream_cyclotron",
      "warpstream_logs", "warpstream_traces"]) {
      expect(playbook).toContain(`<${collection}>`);
    }
  });

  test("system log tables exist before migrations", () => {
    // system.crash_log is created on first write, and a migration reads it.
    const flush = playbook.indexOf("SYSTEM FLUSH LOGS");
    const migrate = playbook.indexOf("manage.py migrate_clickhouse");
    expect(flush).toBeGreaterThanOrEqual(0);
    expect(flush).toBeLessThan(migrate);
  });

  test("temporal is present and gates the application", () => {
    // Django's startup connects to Temporal through the Rust SDK bridge; when
    // that fails the web process never binds, so this is a hard dependency
    // rather than a degraded feature.
    expect(compose).toContain("  temporal:");
    expect(compose).toContain("TEMPORAL_HOST: temporal");
    expect(compose).toContain("temporal: {condition: service_healthy}");
  });

  test("compose template is valid yaml", () => {
    // The multi-line PEM has to reach the container without breaking the
    // document: an unquoted {{ ... }} reads as a flow mapping and makes the file
    // unparseable, which Docker only discovers on the host.
    const parsed = Bun.YAML.parse(compose) as { services: Record<string, unknown> };
    expect("web" in parsed.services).toBe(true);
    expect(Object.keys(parsed.services).length).toBe(10);
  });

  test("temporal dynamic config exists in the image", () => {
    // Upstream mounts development-sql.yaml from its checkout; the auto-setup
    // image ships only docker.yaml, and pointing at a missing file leaves the
    // server refusing connections while schema setup reports success.
    expect(compose).toContain("DYNAMIC_CONFIG_FILE_PATH: config/dynamicconfig/docker.yaml");
    expect(compose).not.toContain("DYNAMIC_CONFIG_FILE_PATH: config/dynamicconfig/development-sql.yaml");
  });

  test("web serves without rerunning migrations", () => {
    // ./bin/docker runs ./bin/migrate first and loops on
    // schedule_temporal_workflows, so the server never binds.
    expect(compose).toContain("command: ./bin/docker-server");
  });

  test("checkpoint carries schema and migration bookkeeping", () => {
    // Without the django_migrations rows a restore would look complete and then
    // replay 0001_initial against existing tables -- the exact failure this
    // deployment hit early on.
    expect(checkpoint).toContain("CREATE TABLE");
    expect(checkpoint).toContain("COPY public.django_migrations");
    // Schema only: no other table's rows may ride along.
    expect(checkpoint.match(/^COPY public\./gm)?.length).toBe(1);
  });

  test("checkpoint restores only into an empty database and still migrates", () => {
    const restore = playbook.indexOf("Restore the schema checkpoint");
    const migrate = playbook.indexOf("manage.py migrate_clickhouse");
    expect(restore).toBeLessThan(migrate);
    // Guarded on the live schema, so it can never land on top of data.
    expect(playbook).toContain("posthog_schema.stdout");
    // Faking the migration state would make a stale checkpoint permanent
    // instead of self-healing; only the comment may mention it.
    expect(/manage\.py migrate[^\n]*--fake/.test(playbook)).toBe(false);
  });

  test("django trusts the proxy", () => {
    // Otherwise every non-exempt path 301s to itself behind Caddy and Cloudflare.
    expect(compose).toContain("IS_BEHIND_PROXY");
  });

  test("ingestion paths reach the capture service", () => {
    // Django resolves /capture/, /e/ and /i/v0/e/ to its catch-all frontend view,
    // which answers 403 via CSRF -- proxying them to the app can never ingest.
    expect(compose).toContain("  capture:");
    expect(compose).toContain("CAPTURE_V1_SINK_MSK_KAFKA_TOPIC_MAIN");
    expect(caddyfile).toContain("reverse_proxy capture:3000");
    for (const path of ["/capture", "/e", "/batch", "/i/*"]) {
      expect(caddyfile).toContain(path);
    }
  });

  test("caddy serves the current configuration", () => {
    // A single-file bind mount pins the inode, so a rewritten Caddyfile never
    // reaches the running container: ingestion routes existed on disk while
    // Caddy still proxied everything to the application.
    expect(playbook).toContain("--force-recreate caddy");
    expect(playbook).toContain("sha256sum /etc/caddy/Caddyfile");
    const reload = playbook.indexOf("--force-recreate caddy");
    const health = playbook.indexOf("Wait for the PostHog web service");
    expect(reload).toBeLessThan(health);
  });

  test("something consumes the ingestion topic", () => {
    // Capture produces to events_plugin_ingestion; ClickHouse's Kafka engine
    // tables read clickhouse_* topics. Without a consumer bridging them the API
    // accepts events that never reach the database.
    expect(compose).toContain("  plugins:");
    expect(compose).toContain("PERSONS_DATABASE_URL");
  });

  test("plugin server gets a geoip database", () => {
    // It loads one at startup and exits when it is missing; its image ships none.
    expect(playbook).toContain("GeoLite2-City.mmdb");
    expect(compose).toContain("/share/GeoLite2-City.mmdb:ro");
  });

  test("every plugin server redis client is pointed at redis", () => {
    // Only the first client reads REDIS_URL; the others default to 127.0.0.1 and
    // exit the process when they cannot connect.
    for (const v of ["CDP_REDIS_HOST", "LOGS_REDIS_HOST", "INGESTION_REDIS_HOST",
      "POSTHOG_REDIS_HOST", "COOKIELESS_REDIS_HOST"]) {
      expect(compose).toContain(`${v}: redis`);
    }
  });

  test("plugin server runs an ingestion mode", () => {
    // Without a mode it exits cleanly at startup, having consumed nothing.
    expect(compose).toContain("PLUGIN_SERVER_MODE: ingestion-v2-combined");
  });

  test("encryption keys are shared and required", () => {
    // The plugin server throws "Encryption keys are not set" and exits; below
    // debug level that looks like a clean shutdown, so it must never be optional.
    expect(compose).toContain("ENCRYPTION_SALT_KEYS");
    // In the shared anchor, so application and plugin server agree.
    expect(compose.indexOf("ENCRYPTION_SALT_KEYS")).toBeLessThan(compose.indexOf("services:"));
    expect(playbook).toContain("COLORS_PAR_POSTHOG_ENCRYPTION_SALT_KEYS");
  });

  test("application and plugin server images are pinned together", () => {
    // They share a Postgres schema. With floating tags the node process queried
    // posthog_person.last_seen_at, a column the application's migrations had
    // never created, and died in its consume loop.
    const text = readFileSync(fixtureFile, "utf8");
    const app = text.match(/posthog-image: posthog\/posthog:(\S+)/)?.[1];
    const node = text.match(/posthog-plugin-server-image: posthog\/posthog-node:(\S+)/)?.[1];
    expect(app).toBe(node!);
    expect(app).not.toBe("latest");
  });

  test("checkpoint is bound to the commit it came from", () => {
    // Behind the image on one lineage a checkpoint heals forward. From a
    // divergent commit it leaves migrations the image never had, and migrate
    // stops on orphaned migrations -- so restore only on an exact match.
    expect(checkpoint.startsWith("-- posthog-commit: ")).toBe(true);
    expect(playbook).toContain("posthog_checkpoint_commit.stdout");
    expect(playbook).toContain("posthog_image_commit.stdout");
  });

  test("person column the plugin server needs is created", () => {
    // The node image queries a column the application's migrations at the same
    // commit do not create; without it ingestion accepts events and stores none.
    expect(playbook).toContain("posthog_person ADD COLUMN IF NOT EXISTS last_seen_at");
    const alter = playbook.indexOf("ADD COLUMN IF NOT EXISTS last_seen_at");
    const migrate = playbook.indexOf("manage.py migrate_clickhouse");
    // After migrations, so a real migration adding it wins.
    expect(migrate).toBeLessThan(alter);
  });

  test("capture image is pinned not floating", () => {
    // The application and plugin server are already pinned to one commit; the
    // capture service was still on a branch tag that moves under the deployment.
    const text = readFileSync(fixtureFile, "utf8");
    expect(/posthog-capture-image: \S+@sha256:[0-9a-f]{64}/.test(text)).toBe(true);
    expect(/image:\s*\S+:(latest|master)\s*$/m.test(text)).toBe(false);
  });

  test("celery and plugin server health are addressed", () => {
    // PostHog's own setup UI reported both as errors: Celery could not start
    // until required async migrations were complete, and the plugin server was
    // probed through a Kubernetes service name that resolves nowhere here.
    expect(compose).toContain("CDP_API_URL: \"http://plugins:6738\"");
    expect(playbook).toContain("--complete-noop-migrations");
    // Backfills are only completed where there is nothing to backfill.
    expect(playbook).toContain("posthog_event_count.stdout");
  });

  test("background jobs verdict distinguishes the failures", () => {
    // The ingestion path never touches Celery, so this is the only part of
    // acceptance that can notice a worker that never started.
    expect(tools.backgroundVerdict("celery=True pending=0")).toBe("ok");
    // A pending async migration is exactly what stopped the worker booting.
    expect(tools.backgroundVerdict("celery=True pending=4")).toBe("migrations-pending");
    expect(tools.backgroundVerdict("celery=False pending=0")).toBe("celery-down");
    expect(tools.backgroundVerdict("")).toBe("unreachable");
    expect(tools.backgroundVerdict(undefined)).toBe("unreachable");
  });

  test("an owner account is provisioned", () => {
    // Without one a converge leaves an instance nobody can log into: the hosted
    // realm only lets the first user create an organization, and the acceptance
    // step's project already creates one.
    expect(playbook).toContain("owner.py");
    expect(playbook).toContain("COLORS_PAR_POSTHOG_ADMIN_PASSWORD");
    // All three states, so a converge is idempotent whatever it finds.
    for (const state of ["bootstrapped", "joined", "rotated"]) {
      expect(owner).toContain(`OWNER=${state}`);
    }
  });

  test("a missing compute output fails loudly", () => {
    // The documentation address belongs to build and dry-run. Merging it into a
    // real converge would point Ansible at TEST-NET instead of failing.
    expect(tools.resolvedCompute({}, { ip: "192.0.2.10" }, { ip: "1.2.3.4" }).ip).toBe("1.2.3.4");
    expect(tools.resolvedCompute({}, { ip: "192.0.2.10" }, undefined)["red/exit"]).toBe(1);
    expect(tools.resolvedCompute({}, { ip: "192.0.2.10" }, {})["red/exit"]).toBe(1);
    expect(tools.resolvedCompute({}, { ip: "192.0.2.10" }, { ip: "5.6.7.8" })["red/exit"]).toBeUndefined();
  });

  test("caddy access logging is on and bounded", () => {
    // Access logging is off by default in Caddy, so a successful request left no
    // trace and capture had no request-level evidence to debug from.
    expect(caddyfile).toContain("log {");
    expect(caddyfile).toContain("output stdout");
    // On, but bounded: json-file never rotates on its own and this endpoint
    // writes a line per request.
    expect(compose).toContain("max-size");
    expect(compose).toContain("max-file");
  });

  test("access log records the visitor not the proxy", () => {
    // Behind the Cloudflare proxy every connection arrives from an edge address,
    // so without trusted_proxies Caddy attributes each request to Cloudflare and
    // the access log answers "who sent this?" with the proxy. Verified against a
    // live deployment: the arm with this block logged the real client address
    // and the arm without it logged 162.158.x.
    expect(caddyfile).toContain("trusted_proxies static");
    expect(caddyfile).toContain("162.158.0.0/15");
    expect(caddyfile).toContain("2400:cb00::/32");
  });
});

// --- validate ----------------------------------------------------------------

describe("validate", () => {
  test("fixture is valid", () => {
    expect(validate.stateErrors(fixture())).toEqual([]);
  });

  test("optout fixture is valid", () => {
    expect(validate.stateErrors(optout())).toEqual([]);
  });

  test("vultr fixtures are valid", () => {
    expect(validate.stateErrors(vultr())).toEqual([]);
    expect(validate.stateErrors(vultrOptout())).toEqual([]);
  });

  // --- the registry (Compute Provider Standard §2)

  test("advertised providers", () => {
    expect(Object.keys(validate.computeProviders).sort()).toEqual(["digitalocean", "vultr"]);
    expect(validate.defaultComputeProvider).toBe("digitalocean");
  });

  test("unsupported provider is named with the alternatives", () => {
    expect(validate.stateErrors(fixture({ "provider-compute": "hcloud" })))
      .toContain(":provider-compute must be one of digitalocean, vultr");
  });

  test("each provider requires its own keys and ignores the others", () => {
    const vultrErrors = validate.stateErrors(fixture({ "provider-compute": "vultr" }));
    for (const k of ["vultr-region", "vultr-plan", "vultr-os-id", "vultr-ssh-sources", "vultr-http-sources"]) {
      expect(vultrErrors).toContain(`:${k} is required`);
    }
    expect(vultrErrors.some((e) => e.includes("digitalocean"))).toBe(false);
    const doErrors = validate.stateErrors(vultr({ "provider-compute": "digitalocean" }));
    for (const k of ["digitalocean-region", "digitalocean-size", "digitalocean-image",
                     "digitalocean-ssh-sources", "digitalocean-http-sources"]) {
      expect(doErrors).toContain(`:${k} is required`);
    }
    expect(doErrors.some((e) => e.includes("vultr"))).toBe(false);
    // Unselected-provider keys are accepted so one colors.yml stays portable.
    expect(validate.stateErrors(fixture({ "vultr-region": "ams", "vultr-ssh-keys": "x" }))).toEqual([]);
  });

  test("per-provider checks run only for the selected provider", () => {
    expect(validate.stateErrors(vultr({ "vultr-os-id": "2284" })).some((e) => e.includes("vultr-os-id"))).toBe(true);
    expect(validate.stateErrors(fixture({ "vultr-os-id": "2284" }))).toEqual([]);
    expect(validate.stateErrors(fixture({ "digitalocean-vpc-uuid": "x" })).some((e) => e.includes("vpc-uuid"))).toBe(true);
    expect(validate.stateErrors(vultr({ "digitalocean-vpc-uuid": "x" }))).toEqual([]);
  });

  test("secrets and tofu env follow the selected provider", () => {
    const doErrors = validate.secretErrors(fixture()).join("\n");
    const vultrErrors = validate.secretErrors(vultr()).join("\n");
    expect(doErrors).toContain("COLORS_PAR_DO_TOKEN");
    expect(doErrors).not.toContain("VULTR_API_KEY");
    expect(vultrErrors).toContain("COLORS_PAR_VULTR_API_KEY");
    expect(vultrErrors).not.toContain("DO_TOKEN");
    expect(validate.tofuEnv(fixture(), "provider-compute")).toEqual({ "do-token": "DIGITALOCEAN_TOKEN" });
    expect(validate.tofuEnv(vultr(), "provider-compute")).toEqual({ "vultr-api-key": "VULTR_API_KEY" });
    expect(validate.tofuEnv(fixture({ "provider-compute": "hcloud" }), "provider-compute")).toEqual({});
  });

  test("compute key is provider-scoped and keygen follows the selected provider key", () => {
    expect(validate.computeKey(fixture(), "ssh-sources")).toBe("digitalocean-ssh-sources");
    expect(validate.computeKey(vultr(), "name")).toBe("vultr-name");
    expect(validate.keygen(vultr())).toBe(true);
    expect(validate.keygen(vultrOptout())).toBe(false);
    // A DigitalOcean key id in a Vultr deployment is an unselected key: ignored.
    expect(validate.keygen(vultr({ "digitalocean-ssh-keys": "58495393" }))).toBe(true);
    expect(validate.computeName(vultr())).toBe("posthog-vultr-fixture");
    expect(validate.computeName(vultrOptout())).toBe("posthog-vultr-optout");
    expect(validate.computeName(vultr({ "digitalocean-name": "other" }))).toBe("posthog-vultr-fixture");
    expect(validate.stateErrors(vultr({ "vultr-name": "not valid!" })).some((e) => e.includes("vultr-name"))).toBe(true);
    // Provider-specific rules: DigitalOcean names are hostname-like, Vultr
    // labels are only a console string.
    expect(validate.stateErrors(fixture({ "digitalocean-name": "invalid_name" })).some((e) => e.includes("digitalocean-name"))).toBe(true);
    expect(validate.stateErrors(fixture({ "digitalocean-name": "Upper-Case" })).some((e) => e.includes("digitalocean-name"))).toBe(true);
    expect(validate.stateErrors(vultr({ "vultr-name": "invalid_name" }))).toEqual([]);
    expect(validate.stateErrors(vultr({ "vultr-name": "Upper_Case.1" }))).toEqual([]);
  });

  // --- the network contract (§5)

  test("cidr syntax", () => {
    for (const ok of ["0.0.0.0/0", "10.0.0.0/8", "203.0.113.7/32", "::/0", "2001:db8::/32",
                      "fe80::1/128", "::ffff:192.0.2.10/96", "2001:db8:0:0:0:0:0:1/64"]) {
      expect(validate.cidr(ok)).toBe(true);
    }
    for (const bad of ["", "10.0.0.0", "10.0.0.0/33", "256.0.0.1/8", "10.0.0/8", "::/129",
                       "2001:db8::/-1", "2001:db8:::1/64", "1:2:3:4:5:6:7:8:9/64", "g::1/64",
                       "10.0.0.0/8/8", "example.com/24"]) {
      expect(validate.cidr(bad)).toBe(false);
    }
  });

  test("ssh sources must reach someone and malformed entries are refused in either list", () => {
    for (const f of [fixture, vultr]) {
      const opts = f();
      const sshKey = validate.computeKey(opts, "ssh-sources");
      const httpKey = validate.computeKey(opts, "http-sources");
      expect(validate.stateErrors({ ...opts, [sshKey]: [] }).some((e) => e.includes("at least one CIDR"))).toBe(true);
      expect(validate.stateErrors({ ...opts, [sshKey]: ["10.0.0.0"] })
        .some((e) => e.includes("not an IPv4 or IPv6 CIDR: 10.0.0.0"))).toBe(true);
      expect(validate.stateErrors({ ...opts, [httpKey]: ["0.0.0.0/0", "nope"] })
        .some((e) => e.includes("not an IPv4 or IPv6 CIDR: nope"))).toBe(true);
      // An empty http list means no public HTTP and is allowed.
      expect(validate.stateErrors({ ...opts, [httpKey]: [] })).toEqual([]);
      // Overlay strings are split the way the template reads them.
      expect(validate.stateErrors({ ...opts, [sshKey]: "10.0.0.0/8, 192.0.2.0/24" })).toEqual([]);
    }
  });

  // --- provider switching is a rebuild (§4)

  test("provider state refuses a switch, accepts the recorded provider, and holds legacy params to the default", () => {
    expect(validate.providerStateErrors(fixture(), { provider: "vultr", ip: "203.0.113.7" }))
      .toEqual(["state holds a vultr machine; set provider-compute back to vultr and delete first"]);
    expect(validate.providerStateErrors(vultr(), { provider: "digitalocean", ip: "203.0.113.7" }))
      .toEqual(["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]);
    expect(validate.providerStateErrors(fixture(), { provider: "digitalocean", ip: "203.0.113.7" })).toEqual([]);
    expect(validate.providerStateErrors(vultr(), { provider: "vultr", ip: "203.0.113.7" })).toEqual([]);
    expect(validate.providerStateErrors(fixture(), undefined)).toEqual([]);
    expect(validate.providerStateErrors(vultr(), undefined)).toEqual([]);
    // Every deployment created before adoption ran the package default.
    expect(validate.providerStateErrors(fixture(), { ip: "203.0.113.7" })).toEqual([]);
    expect(validate.providerStateErrors(vultr(), { ip: "203.0.113.7" }))
      .toEqual(["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]);
  });

  test("the machine key is not required", () => {
    // The standard makes absence meaningful: requiring digitalocean-ssh-keys
    // would make every conforming keygen deployment invalid.
    expect(validate.stateErrors(fixture()).some((e) => e.includes("digitalocean-ssh-keys"))).toBe(false);
  });

  test("absent machine key selects keygen", () => {
    expect(validate.keygen(fixture())).toBe(true);
    expect(validate.keygen(optout())).toBe(false);
  });

  test("compute name defaults to the profile and honours the override", () => {
    expect(validate.computeName(fixture())).toBe("posthog-fixture");
    expect(validate.computeName(fixture({ "digitalocean-name": "" }))).toBe("posthog-fixture");
    expect(validate.computeName(fixture({ "digitalocean-name": "REPLACE_ME" }))).toBe("posthog-fixture");
    expect(validate.computeName(optout())).toBe("posthog-optout");
    expect(validate.computeName(fixture({ "digitalocean-name": " analytics-1 " }))).toBe("analytics-1");
  });

  test("compute name is not required but is validated", () => {
    expect(validate.stateErrors(fixture()).some((e) => e.includes("digitalocean-name"))).toBe(false);
    expect(validate.stateErrors(fixture({ "digitalocean-name": "not valid!" }))
      .some((e) => e.includes("digitalocean-name"))).toBe(true);
  });

  test("reports all errors", () => {
    const errors = validate.stateErrors(
      fixture({ "posthog-host": "bad", "posthog-image": "floating",
        "posthog-backup-retention-days": -1,
        "provider-dns": "other", "digitalocean-vpc-uuid": "forbidden" }));
    expect(errors.length).toBeGreaterThanOrEqual(5);
    for (const part of ["host", "image", "retention", "provider-dns", "vpc-uuid"]) {
      expect(errors.some((e) => e.includes(part))).toBe(true);
    }
  });

  test("forbids vpc configuration", () => {
    expect(validate.stateErrors(fixture({ "digitalocean-vpc-cidr": "10.0.0.0/16" }))
      .some((e) => e.includes("must be absent"))).toBe(true);
  });

  test("profile overlay is refused", () => {
    expect(validate.envErrors({ COLORS_PAR_PROFILE: "other" }).length).toBeGreaterThan(0);
    expect(validate.envErrors({})).toEqual([]);
  });

  test("names all package secrets", () => {
    const errors = validate.secretErrors(fixture()).join("\n");
    for (const name of ["COLORS_PAR_DO_TOKEN", "COLORS_PAR_CLOUDFLARE_API_TOKEN",
      "COLORS_PAR_R2_ACCESS_KEY_ID", "COLORS_PAR_R2_SECRET_ACCESS_KEY",
      "COLORS_PAR_POSTHOG_BACKUP_R2_ACCESS_KEY_ID",
      "COLORS_PAR_POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY",
      "COLORS_PAR_POSTHOG_SECRET_KEY",
      "COLORS_PAR_POSTHOG_POSTGRES_PASSWORD",
      "COLORS_PAR_POSTHOG_OIDC_RSA_PRIVATE_KEY",
      "COLORS_PAR_POSTHOG_ENCRYPTION_SALT_KEYS"]) {
      expect(errors).toContain(name);
    }
  });
});

// --- workflow ----------------------------------------------------------------

function deletableFixture(overrides: Opts = {}): Opts {
  // A fixture that passes real-delete preflight: guard lifted, secrets present.
  return fixture({
    "compute-prevent-destroy": false,
    "do-token": "t", "cloudflare-api-token": "t",
    "posthog-secret-key": "s", "posthog-postgres-password": "p",
    "posthog-oidc-rsa-private-key": "k",
    "posthog-encryption-salt-keys": "k",
    "posthog-admin-password": "p",
    "posthog-backup-r2-access-key-id": "k",
    "posthog-backup-r2-secret-access-key": "s",
    "r2-access-key-id": "k", "r2-secret-access-key": "s",
    ...overrides,
  });
}

describe("workflow", () => {
  test("build and dry-run need no credentials", async () => {
    expect((await workflow.startStep(fixture({ "red/event": "build" }), {}))["red/exit"]).toBe(0);
    expect((await workflow.startStep(
      fixture({ "red/event": "create", "red/dry-run": true }), {}))["red/exit"]).toBe(0);
  });

  test("real create requires credentials", async () => {
    const r = await workflow.startStep(fixture({ "red/event": "create" }), {});
    expect(r["red/exit"]).toBe(2);
    expect(String(r["red/err"])).toContain("COLORS_PAR_DO_TOKEN");
    expect(String(r["red/err"])).toContain("COLORS_PAR_POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY");
  });

  test("delete is protected", async () => {
    const r = await workflow.startStep(fixture({ "red/event": "delete" }), {});
    expect(r["red/exit"]).toBe(2);
    expect(String(r["red/err"])).toContain("COMPUTE_PREVENT_DESTROY");
  });

  test("delete fails loudly when state is unreadable", async () => {
    // Swallowing a failed state read is how a live teardown ended up pointing
    // the cleanup playbook at 192.0.2.10: stale backend credentials made
    // `tofu output` fail, nil was merged, and the inventory fell back to
    // TEST-NET. The failure must surface here, before any playbook runs.
    runtime.exec = async () => ({ exit: 1, out: "", err: "Unauthorized" });
    const r = await workflow.startStep(deletableFixture({ "red/event": "delete" }), {});
    expect(r["red/exit"]).toBe(1);
    expect(String(r["red/err"])).toContain("Unauthorized");
    expect(String(r["red/err"])).toContain("could not read the infrastructure state for the delete cleanup");
  });

  test("an explicit ip never skips the read or the provider guard", async () => {
    // COLORS_PAR_IP replaces a stale recorded address once the read succeeded;
    // it is not a way around the read, the fail-closed rule, or the provider
    // guard (Compute Provider Standard §4).
    stateOutputs(undefined);
    let r = await workflow.startStep(deletableFixture({ "red/event": "delete", ip: "203.0.113.7" }), {});
    expect(r["red/exit"]).toBe(1);
    expect(String(r["red/err"])).toContain("Unauthorized");
    stateOutputs({ provider: "vultr", ip: "198.51.100.4" });
    r = await workflow.startStep(deletableFixture({ "red/event": "delete", ip: "203.0.113.7" }), {});
    expect(r["red/exit"]).toBe(2);
    expect(String(r["red/err"])).toContain("state holds a vultr machine");
    stateOutputs({ provider: "digitalocean", ip: "198.51.100.4" });
    r = await workflow.startStep(deletableFixture({ "red/event": "delete", ip: "203.0.113.7" }), {});
    expect(r["red/exit"]).toBe(0);
    expect(r.ip).toBe("203.0.113.7");
  });

  test("state is read once per run", async () => {
    // One read serves the provider validator, the key matrix and the
    // adoption; a second read would be a second chance for the backend to
    // disagree.
    const ensure = spyOn(ssh, "ensureKey").mockImplementation(async (opts, stateFn) =>
      ({ ...opts, recorded: await stateFn(opts) }));
    const preflight = spyOn(ssh, "preflight").mockImplementation(async (opts) => opts);
    const config = spyOn(sshConfig, "preflight").mockImplementation((opts) => opts);
    try {
      for (const event of ["create", "delete"]) {
        let reads = 0;
        runtime.exec = async () => {
          reads += 1;
          return { exit: 0, out: JSON.stringify({ params: { value: { provider: "digitalocean", ip: "203.0.113.9" } } }), err: "" };
        };
        const r = await workflow.startStep(deletableFixture({ "red/event": event,
          "compute-prevent-destroy": event === "create" }), {});
        expect(r["red/exit"]).toBe(0);
        expect(reads).toBe(1);
        if (event === "create") expect((r.recorded as Opts).ip).toBe("203.0.113.9");
        else expect(r.ip).toBe("203.0.113.9");
      }
    } finally {
      ensure.mockRestore(); preflight.mockRestore(); config.mockRestore();
    }
  });

  test("delete with empty state proceeds without an address", async () => {
    // State readable, no compute recorded: the instance is already gone, the
    // cleanup step skips itself, and the rest of the teardown still runs.
    runtime.exec = async () => ({ exit: 0, out: "{}", err: "" });
    const r = await workflow.startStep(deletableFixture({ "red/event": "delete" }), {});
    expect(r["red/exit"]).toBe(0);
    expect(r.ip).toBeUndefined();
  });

  test("a provider switch is refused on create and delete, before the missing token", async () => {
    // Compute Provider Standard §4: all providers share one state key, so a
    // changed provider-compute on a profile with compute in state would plan a
    // cross-provider replacement. Both events refuse; delete refuses because it
    // would render and destroy the *selected* provider's template. The
    // validator order is the thing under test: no missing-token entry for the
    // newly selected provider appears beside the actionable error.
    stateOutputs({ provider: "digitalocean", ip: "203.0.113.7" });
    for (const event of ["create", "delete"]) {
      const { "vultr-api-key": _dropped, ...withoutToken } = secrets;
      const r = await workflow.startStep(vultr({ ...withoutToken, "red/event": event,
        "compute-prevent-destroy": false }), {});
      expect(r["red/exit"]).toBe(2);
      const lines = String(r["red/err"]).split("\n");
      expect(lines).toContain("state holds a digitalocean machine; set provider-compute back to digitalocean and delete first");
      expect(lines.some((l) => l.includes("COLORS_PAR_VULTR_API_KEY"))).toBe(false);
    }
    stateOutputs({ provider: "vultr", ip: "203.0.113.7" });
    for (const event of ["create", "delete"]) {
      const r = await workflow.startStep(fixture({ ...secrets, "red/event": event,
        "compute-prevent-destroy": false }), {});
      expect(String(r["red/err"])).toContain("state holds a vultr machine; set provider-compute back to vultr and delete first");
    }
  });

  test("legacy state without a provider accepts only the default", async () => {
    stateOutputs({ ip: "203.0.113.7" });
    const ensure = spyOn(ssh, "ensureKey").mockImplementation(async (opts) => opts);
    const preflight = spyOn(ssh, "preflight").mockImplementation(async (opts) => opts);
    const config = spyOn(sshConfig, "preflight").mockImplementation((opts) => opts);
    try {
      for (const event of ["create", "delete"]) {
        const ok = await workflow.startStep(fixture({ ...secrets, "red/event": event,
          "compute-prevent-destroy": false }), {});
        expect(ok["red/exit"]).toBe(0);
        const refused = await workflow.startStep(vultr({ ...secrets, "red/event": event,
          "compute-prevent-destroy": false }), {});
        expect(refused["red/exit"]).toBe(2);
        expect(String(refused["red/err"])).toContain("set provider-compute back to digitalocean");
      }
    } finally {
      ensure.mockRestore(); preflight.mockRestore(); config.mockRestore();
    }
  });

  test("an unreadable backend is no state on create and fatal on delete", async () => {
    stateOutputs(undefined);
    let seen: Opts | undefined | null = null;
    const ensure = spyOn(ssh, "ensureKey").mockImplementation(async (opts, stateFn) => {
      seen = await stateFn(opts);
      return opts;
    });
    const preflight = spyOn(ssh, "preflight").mockImplementation(async (opts) => opts);
    const config = spyOn(sshConfig, "preflight").mockImplementation((opts) => opts);
    try {
      for (const f of [fixture, vultr]) {
        const created = await workflow.startStep(f({ ...secrets, "red/event": "create" }), {});
        expect(created["red/exit"]).toBe(0);
        expect(seen).toBeUndefined();
        const deleted = await workflow.startStep(f({ ...secrets, "red/event": "delete",
          "compute-prevent-destroy": false }), {});
        expect(deleted["red/exit"]).toBe(1);
        expect(String(deleted["red/err"])).toContain("could not read the infrastructure state for the delete cleanup");
        expect(String(deleted["red/err"])).toContain("Unauthorized");
      }
    } finally {
      ensure.mockRestore(); preflight.mockRestore(); config.mockRestore();
    }
  });

  test("a real create requires the selected provider credentials", async () => {
    const r = await workflow.startStep(vultr({ "red/event": "create" }), {});
    expect(r["red/exit"]).toBe(2);
    expect(String(r["red/err"])).toContain("COLORS_PAR_VULTR_API_KEY");
    expect(String(r["red/err"])).not.toContain("COLORS_PAR_DO_TOKEN");
  });

  test("graph orders private stack", () => {
    const next = (step: string) =>
      (workflow.wireFn(step, { "red/event": "create" }) ?? []).slice(1);
    expect(next("posthog/start")).toEqual(["posthog/infrastructure"]);
    expect(next("posthog/infrastructure")).toEqual(["posthog/ssh-config"]);
    expect(next("posthog/ssh-config")).toEqual(["posthog/dns"]);
    expect(next("posthog/dns")).toEqual(["posthog/ansible"]);
    expect(next("posthog/ansible")).toEqual(["posthog/acceptance"]);
    expect((workflow.wireFn("posthog/start", { "red/event": "delete" }) ?? []).slice(1))
      .toEqual(["posthog/ansible"]);
  });

  test("delete removes the config block before the destroy and the key after it", () => {
    // The ordering is what makes "key present ⇔ deployment exists" hold: a
    // failed destroy never reaches the cleanup step, and correctly leaves the
    // key that is still the only credential to whatever survived.
    const next = (step: string) =>
      (workflow.wireFn(step, { "red/event": "delete" }) ?? []).slice(1);
    expect(next("posthog/ansible")).toEqual(["posthog/dns"]);
    expect(next("posthog/dns")).toEqual(["posthog/ssh-config"]);
    expect(next("posthog/ssh-config")).toEqual(["posthog/infrastructure"]);
    expect(next("posthog/infrastructure")).toEqual(["posthog/ssh-cleanup"]);
    expect(next("posthog/ssh-cleanup")).toEqual([]);
  });

  test("build and dry-run never touch ~/.ssh", async () => {
    // The standard forbids reading, creating, or requiring anything under
    // ~/.ssh on a build or dry-run: they render from desired state alone.
    // A poisoned config proves nothing in the build path reads it.
    write(join(home, ".ssh", "config"), "ServerAliveInterval 60\nHost posthog-fixture\n");
    runtime.exec = () => { throw new Error("ssh-keygen must not run"); };
    for (const overrides of [{ "red/event": "build" },
                             { "red/event": "create", "red/dry-run": true },
                             { "red/event": "delete", "red/dry-run": true }]) {
      const result = await workflow.startStep(fixture(overrides), {});
      expect(result["red/exit"]).toBe(0);
      expect(String(result["ssh-public-key-path"])).toStartWith("/home/build-placeholder");
      expect(result["digitalocean-ssh-keys"]).toBe(result["ssh-public-key-path"]);
    }
  });

  test("opt-out renders the historical shape on every rendered event", async () => {
    for (const overrides of [{ "red/event": "build" },
                             { "red/event": "create", "red/dry-run": true }]) {
      const result = await workflow.startStep(optout(overrides), {});
      expect(result["red/exit"]).toBe(0);
      expect(result["digitalocean-ssh-keys"]).toBe("58495393");
      expect(result["ssh-keygen"]).toBeUndefined();
    }
  });

  test("a real delete fills the real key paths and adopts state", async () => {
    // The transition table's last row: a destroy renders before it destroys,
    // so the template values are the real ones, merged with the adopted state.
    runtime.exec = async () => ({ exit: 0, out: JSON.stringify({
      params: { value: { ip: "203.0.113.9", ssh_key_id: "77" } } }), err: "" });
    const r = await workflow.startStep(deletableFixture({ "red/event": "delete" }), {});
    expect(r["red/exit"]).toBe(0);
    expect(r.ip).toBe("203.0.113.9");
    expect(r["ssh-private-key-path"]).toBe(join(home, ".ssh", "posthog-fixture"));
    expect(r["ssh-keygen"]).toBe(true);
  });

  test("a real create runs the key matrix then both preflights", async () => {
    // Row three of the transition table, in order: ensureKey against the
    // best-effort state read, the provider preflight, then the ~/.ssh/config
    // checks. Each stops the run on its own error.
    const creatable = (overrides: Opts = {}) =>
      deletableFixture({ "compute-prevent-destroy": true, ...overrides });
    const context = { event: "create", real: true };
    const state = {};
    const calls: unknown[] = [];
    const ensure = spyOn(ssh, "ensureKey").mockImplementation(async (opts, stateFn) => {
      calls.push(["ensure", await stateFn(opts)]);
      return opts;
    });
    void runtime;
    const preflight = spyOn(ssh, "preflight").mockImplementation(async (opts) => {
      calls.push("preflight");
      return opts;
    });
    const config = spyOn(sshConfig, "preflight").mockImplementation((opts) => {
      calls.push("ssh-config");
      return opts;
    });
    try {
      // All pass; the key matrix is handed the one state read's params.
      let r = await workflow.afterValidate(creatable({ "red/event": "create" }), context, { params: undefined });
      expect(r["red/exit"]).toBe(0);
      expect(calls).toEqual([["ensure", undefined], "preflight", "ssh-config"]);
      expect(r["ssh-keygen"]).toBe(true);
      // The key matrix stops the run.
      ensure.mockImplementation(async (opts) => ({ ...opts, "red/exit": 1, "red/err": "half a keypair" }));
      preflight.mockImplementation(async () => { throw new Error("must not run"); });
      r = await workflow.afterValidate(creatable({ "red/event": "create" }), context, state);
      expect(r["red/exit"]).toBe(1);
      expect(String(r["red/err"])).toContain("half a keypair");
      // The provider preflight stops the run.
      ensure.mockImplementation(async (opts) => opts);
      preflight.mockImplementation(async (opts) => ({ ...opts, "red/exit": 1, "red/err": "already has an SSH key" }));
      config.mockImplementation(() => { throw new Error("must not run"); });
      r = await workflow.afterValidate(creatable({ "red/event": "create" }), context, state);
      expect(r["red/exit"]).toBe(1);
      expect(String(r["red/err"])).toContain("already has an SSH key");
      // The ~/.ssh/config checks stop the run.
      preflight.mockImplementation(async (opts) => opts);
      config.mockImplementation((opts) => ({ ...opts, "red/exit": 1, "red/err": "refusing to manage" }));
      r = await workflow.afterValidate(creatable({ "red/event": "create" }), context, state);
      expect(r["red/exit"]).toBe(1);
      expect(String(r["red/err"])).toContain("refusing to manage");
    } finally {
      ensure.mockRestore();
      preflight.mockRestore();
      config.mockRestore();
    }
  });

  test("opt-out create skips the key matrix", async () => {
    // Presence of the explicit key is the only switch: the package then
    // generates, validates and deletes nothing.
    runtime.exec = () => { throw new Error("ssh-keygen must not run"); };
    const r = await workflow.afterValidate(
      { ...deletableFixture({ "compute-prevent-destroy": true }), ...optout({ "red/event": "create" }) },
      { event: "create", real: true }, {});
    expect(r["red/exit"]).toBe(0);
    expect(r["digitalocean-ssh-keys"]).toBe("58495393");
    expect(existsSync(join(home, ".ssh"))).toBe(false);
  });
});

// --- ssh keypair (SSH Keypair Standard) --------------------------------------

describe("ssh", () => {
  test("build renders a stable placeholder path", () => {
    const opts = ssh.withMachineKey(fixture({ "red/event": "build" }));
    expect(String(opts["ssh-public-key-path"])).toStartWith(ssh.buildPlaceholderDir);
    expect(opts["digitalocean-ssh-keys"]).toBe(opts["ssh-public-key-path"]);
    expect(String(opts["ssh-private-key-path"])).not.toContain(home);
  });

  test("a dry-run renders the placeholder too", () => {
    const opts = ssh.withMachineKey(fixture({ "red/event": "create", "red/dry-run": true }));
    expect(String(opts["ssh-public-key-path"])).toStartWith(ssh.buildPlaceholderDir);
  });

  test("real events render the real path", () => {
    const opts = ssh.withMachineKey(fixture({ "red/event": "create" }));
    expect(opts["ssh-private-key-path"]).toBe(join(home, ".ssh", "posthog-fixture"));
    expect(opts["ssh-public-key-path"]).toBe(join(home, ".ssh", "posthog-fixture.pub"));
  });

  test("opt-out passes through untouched", () => {
    for (const event of ["build", "create", "delete"]) {
      const opts = ssh.withMachineKey(optout({ "red/event": event }));
      expect(opts["digitalocean-ssh-keys"]).toBe("58495393");
      expect(opts["ssh-public-key-path"]).toBeUndefined();
      expect(opts["ssh-keygen"]).toBeUndefined();
    }
  });

  test("the placeholder lands on the selected provider key", () => {
    const opts = ssh.withMachineKey(vultr({ "red/event": "build" }));
    expect(opts["vultr-ssh-keys"]).toBe(opts["ssh-public-key-path"]);
    expect(opts["digitalocean-ssh-keys"]).toBeUndefined();
    const out = ssh.withMachineKey(vultrOptout({ "red/event": "build" }));
    expect(out["vultr-ssh-keys"]).toBe("00000000-0000-0000-0000-000000000000");
    expect(out["ssh-keygen"]).toBeUndefined();
  });

  test("the preflight uses the selected provider token", async () => {
    // do-token on DigitalOcean, vultr-api-key on Vultr — the delegation is
    // what is tested; ONCE owns the table.
    const seen: string[][] = [];
    const fetchFn = async (provider: string, token: string) => { seen.push([provider, token]); return []; };
    await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create", "do-token": "do-t", "vultr-api-key": "v-t" })), fetchFn);
    await ssh.preflight(ssh.withMachineKey(vultr({ "red/event": "create", "do-token": "do-t", "vultr-api-key": "v-t" })), fetchFn);
    expect(seen).toEqual([["digitalocean", "do-t"], ["vultr", "v-t"]]);
  });

  test("first create generates the keypair", async () => {
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }), async () => undefined);
    const prv = join(home, ".ssh", "posthog-fixture");
    const pub = `${prv}.pub`;
    expect(opts["red/err"]).toBeUndefined();
    expect(existsSync(prv)).toBe(true);
    expect(existsSync(pub)).toBe(true);
    // ed25519, no passphrase, profile-named comment
    expect(readFileSync(pub, "utf8")).toContain("ssh-ed25519");
    expect(readFileSync(pub, "utf8")).toContain("posthog-fixture managed by Colors");
    // 600 on the private key, 700 on ~/.ssh
    expect(statSync(prv).mode & 0o777).toBe(0o600);
    expect(statSync(join(home, ".ssh")).mode & 0o777).toBe(0o700);
  });

  test("converge reuses an existing key", async () => {
    write(join(home, ".ssh", "posthog-fixture"), "private");
    write(join(home, ".ssh", "posthog-fixture.pub"), "ssh-ed25519 AAAA test");
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }),
      async () => ({ ip: "192.0.2.10" }));
    expect(opts["red/err"]).toBeUndefined();
    expect(readFileSync(join(home, ".ssh", "posthog-fixture"), "utf8")).toBe("private");
  });

  test("state without a key is an error", async () => {
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }),
      async () => ({ ip: "192.0.2.10" }));
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("does not hold the machine key");
    expect(String(opts["red/err"])).toContain("rebuild");
  });

  test("a key without state is never overwritten", async () => {
    const prv = join(home, ".ssh", "posthog-fixture");
    write(prv, "irreplaceable");
    write(`${prv}.pub`, "ssh-ed25519 AAAA test");
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }), async () => undefined);
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("no compute state is readable");
    expect(String(opts["red/err"])).toContain("survives");
    expect(readFileSync(prv, "utf8")).toBe("irreplaceable");
  });

  test("half a keypair is an error", async () => {
    write(join(home, ".ssh", "posthog-fixture"), "private");
    const opts = await ssh.ensureKey(fixture({ "red/event": "create" }), async () => undefined);
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("half a keypair");
  });

  test("opt-out generates nothing", async () => {
    const opts = await ssh.ensureKey(optout({ "red/event": "create" }), async () => undefined);
    expect(opts["red/err"]).toBeUndefined();
    expect(existsSync(join(home, ".ssh"))).toBe(false);
  });

  test("preflight passes when no account key matches, or when it is ours", async () => {
    const clean = await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create" })),
      async () => [{ id: "1", name: "someone-else", public: "ssh-ed25519 BBBB" }]);
    expect(clean["red/err"]).toBeUndefined();
    const owned = await ssh.preflight(
      ssh.withMachineKey(fixture({ "red/event": "create",
        "once/ssh-state-params": { ssh_key_id: "abc" } })),
      async () => [{ id: "abc", name: "posthog-fixture", public: "ssh-ed25519 AAAA" }]);
    expect(owned["red/err"]).toBeUndefined();
  });

  test("preflight refuses our leftover key", async () => {
    write(join(home, ".ssh", "posthog-fixture.pub"), "ssh-ed25519 AAAA comment");
    const opts = await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create" })),
      async () => [{ id: "abc", name: "posthog-fixture", public: "ssh-ed25519 AAAA" }]);
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("previous delete");
    expect(String(opts["red/err"])).toContain("delete that key");
  });

  test("preflight refuses a foreign key and says do not delete it", async () => {
    write(join(home, ".ssh", "posthog-fixture.pub"), "ssh-ed25519 OURS comment");
    const opts = await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create" })),
      async () => [{ id: "abc", name: "posthog-fixture", public: "ssh-ed25519 THEIRS" }]);
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("Do not delete it");
  });

  test("preflight failure is an error, not a skip", async () => {
    const opts = await ssh.preflight(ssh.withMachineKey(fixture({ "red/event": "create" })),
      async () => { throw new Error("HTTP 500"); });
    expect(opts["red/exit"]).toBe(1);
    expect(String(opts["red/err"])).toContain("cannot list");
  });

  test("delete removes the keypair; ~/.ssh itself survives", () => {
    write(join(home, ".ssh", "posthog-fixture"), "private");
    write(join(home, ".ssh", "posthog-fixture.pub"), "public");
    ssh.cleanupStep(fixture({ "red/event": "delete", "ssh-keygen": true }));
    expect(existsSync(join(home, ".ssh", "posthog-fixture"))).toBe(false);
    expect(existsSync(join(home, ".ssh", "posthog-fixture.pub"))).toBe(false);
    expect(existsSync(join(home, ".ssh"))).toBe(true);
  });

  test("cleanup is inert on create and in opt-out mode", () => {
    write(join(home, ".ssh", "posthog-fixture"), "private");
    ssh.cleanupStep(fixture({ "red/event": "create", "ssh-keygen": true }));
    expect(existsSync(join(home, ".ssh", "posthog-fixture"))).toBe(true);
    ssh.cleanupStep(optout({ "red/event": "delete" }));
    expect(existsSync(join(home, ".ssh", "posthog-fixture"))).toBe(true);
  });
});

// --- ~/.ssh/config (SSH Config Standard) -------------------------------------

describe("ssh-config", () => {
  test("the alias is the profile and the identity file keeps the tilde", () => {
    expect(sshConfig.hostAlias(fixture())).toBe("posthog-fixture");
    expect(sshConfig.identityFile(fixture())).toBe("~/.ssh/posthog-fixture");
    expect(sshConfig.identityFile(fixture())).not.toContain(home);
  });

  test("the marker is the alias alone", () => {
    expect(sshConfig.beginMarker("posthog-digitalocean")).toBe("# BEGIN posthog-digitalocean ANSIBLE MANAGED BLOCK");
    expect(sshConfig.endMarker("posthog-digitalocean")).toBe("# END posthog-digitalocean ANSIBLE MANAGED BLOCK");
  });

  test("a foreign stanza is found; our own block is not foreign", () => {
    expect(sshConfig.foreignStanzaLine(
      ["Host other", "    HostName 192.0.2.1", "", "Host posthog-fixture"],
      "posthog-fixture")).toBe(4);
    const alias = "posthog-fixture";
    expect(sshConfig.foreignStanzaLine(
      [sshConfig.beginMarker(alias), `Host ${alias}`, "    HostName 192.0.2.1",
       sshConfig.endMarker(alias)], alias)).toBeUndefined();
  });

  test("a stanza after our block is still foreign", () => {
    const alias = "posthog-fixture";
    expect(sshConfig.foreignStanzaLine(
      [sshConfig.beginMarker(alias), `Host ${alias}`, sshConfig.endMarker(alias),
       `Host ${alias}`], alias)).toBe(4);
  });

  test("a block under a retired marker is foreign", () => {
    const alias = "posthog-digitalocean";
    expect(sshConfig.foreignStanzaLine(
      [`# BEGIN posthog ${alias} ANSIBLE MANAGED BLOCK`, `Host ${alias}`,
       `# END posthog ${alias} ANSIBLE MANAGED BLOCK`], alias)).toBe(2);
  });

  test("multi-pattern host lines count; unrelated files are left alone", () => {
    expect(sshConfig.foreignStanzaLine(["Host web posthog-fixture db"], "posthog-fixture")).toBe(1);
    expect(sshConfig.foreignStanzaLine(["Host build", "Host posthog-other"], "posthog-fixture"))
      .toBeUndefined();
  });

  test("an option above the first Host is refused; comments and Host openers are fine", () => {
    expect(sshConfig.leadingOptionLine(["ServerAliveInterval 60", "Host a"])).toBe(1);
    expect(sshConfig.leadingOptionLine(["# comment", "", "IdentitiesOnly yes", "Host a"])).toBe(3);
    expect(sshConfig.leadingOptionLine(["Host a", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["# lead comment", "", "Host a", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["Match host b", "    User root"])).toBeUndefined();
    expect(sshConfig.leadingOptionLine(["# nothing here", ""])).toBeUndefined();
  });

  test("preflight refuses rather than overwrites", () => {
    const refused = sshConfig.preflight(fixture(), {
      adoptError: () => "already declares `Host x`",
      placementError: () => undefined,
    });
    expect(refused["red/exit"]).toBe(1);
    expect(String(refused["red/err"])).toContain("already declares");
    const clean = sshConfig.preflight(fixture(), {
      adoptError: () => undefined,
      placementError: () => undefined,
    });
    expect(clean["red/exit"]).toBeUndefined();
  });

  test("adopt and placement errors read the real file and mention the recovery", () => {
    write(join(home, ".ssh", "config"), "ServerAliveInterval 60\nHost posthog-fixture\n");
    expect(String(sshConfig.adoptError(fixture()))).toContain("Host posthog-fixture");
    expect(String(sshConfig.placementError(fixture()))).toContain("Host *");
  });

  test("the local play renders no address and follows keygen mode", () => {
    const data = tools.ansibleLocalData(fixture({ ip: "203.0.113.7" }));
    expect(data["ssh-config-identity-file"]).toBe("~/.ssh/posthog-fixture");
    expect(data["ssh-keygen"]).toBe(true);
    expect(tools.ansibleLocalData(optout())["ssh-keygen"]).toBe(false);
  });

  test("the local stage renders three files", () => {
    const targets = tools.ansibleLocalSpecs(fixture()).map((s) => String(s.target));
    for (const file of ["/ansible.cfg", "/inventory.ini", "/main.yml"]) {
      expect(targets.some((t) => t.endsWith(file))).toBe(true);
    }
    expect(targets.every((t) => t.includes("posthog-ansible-local"))).toBe(true);
  });
});
