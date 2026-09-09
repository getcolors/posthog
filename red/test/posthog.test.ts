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
import * as compute from "../src/compute.ts";
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
    expect(r["red/exit"]).toBe(1);
    expect(r["red/err"]).toBe("compute node unavailable");
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
    await tools.ansibleStep(fixture({ "red/event": "delete", ip: "203.0.113.7", user:"root", workdir }));
    expect(inventoryDuringRun).toContain("203.0.113.7");
  });


  test("fallback params are the documentation address on every provider", () => {
    for (const f of [fixture, vultr]) {
      expect(tools.fallbackParams(f())).toMatchObject({ provider: f()["provider-compute"], ip: "192.0.2.10",
        user: "root", sudoer: "root", name: f().profile });
    }
    // `name` is the resolved compute name, as the templates' params output is.
    expect(tools.fallbackParams(fixture({ "digitalocean-name": "analytics-1" })).name).toBe("analytics-1");
    expect(tools.fallbackParams(optout()).name).toBe("posthog-optout");
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
    expect(seen.slice(5, 9)).toEqual(["-i", "/home/x/.ssh/posthog-fixture", "-o", "IdentitiesOnly=yes"]);
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


  // --- the network contract (§5)


  test("the machine key is not required", () => {
    // The standard makes absence meaningful: requiring digitalocean-ssh-keys
    // would make every conforming keygen deployment invalid.
    expect(validate.stateErrors(fixture()).some((e) => e.includes("digitalocean-ssh-keys"))).toBe(false);
  });

  test("absent machine key selects keygen", () => {
    expect(validate.keygen(fixture())).toBe(true);
    expect(validate.keygen(optout())).toBe(false);
  });


  test("compute name is not required but is validated", () => {
    expect(validate.stateErrors(fixture()).some((e) => e.includes("digitalocean-name"))).toBe(false);
    expect(validate.stateErrors(fixture({ "digitalocean-name": "not valid!" }))
      .length).toBeGreaterThan(0);
  });

  test("reports all errors", () => {
    const errors = validate.stateErrors(
      fixture({ "posthog-host": "bad", "posthog-image": "floating",
        "posthog-backup-retention-days": -1,
        "provider-dns": "other", "digitalocean-vpc-uuid": "forbidden" }));
    expect(errors.length).toBeGreaterThanOrEqual(4);
    for (const part of ["host", "image", "retention", "provider-dns"]) {
      expect(errors.some((e) => e.includes(part))).toBe(true);
    }
  });


  test("profile overlay is refused", () => {
    expect(validate.envErrors({ COLORS_PAR_PROFILE: "other" }).length).toBeGreaterThan(0);
    expect(validate.envErrors({})).toEqual([]);
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
describe("library compute", () => {
  test("all fixtures validate and use one library node", () => {
    for(const f of [fixture,optout,vultr,vultrOptout]) expect(validate.stateErrors(f())).toEqual([]);
    expect(compute.topology).toEqual([{role:null,count:1}]);
    expect(compute.requirements(fixture()).legacy_state_keys).toEqual(['posthog-fixture/posthog-infrastructure.tfstate']);
  });
  test("invalid compute inputs fail before execution", () => {
    for(const update of [{'provider-compute':'unsupported'},{'digitalocean-size':null},{'digitalocean-ssh-sources':[]},{'digitalocean-http-sources':['bad']}]) expect(validate.stateErrors(fixture(update)).length).toBeGreaterThan(0);
  });
  test("compute credentials are deferred to library state inspection", () => {
    const errors=validate.secretErrors(fixture()).join('\n');
    expect(errors).toContain('COLORS_PAR_CLOUDFLARE_API_TOKEN');
    expect(errors).not.toContain('COLORS_PAR_VULTR_API_KEY');
    expect(validate.tofuEnv(fixture(),'provider-compute')).toEqual({});
  });
  test("failed lifecycle diagnostics and observed node identity survive", () => {
    expect(compute.attach(fixture(),{status:'error',errors:['legacy compute state requires migration']})['red/err']).toBe('legacy compute state requires migration');
    const result=compute.attach(fixture(),{status:'present',cluster:{nodes:[{ip:'203.0.113.7',user:'ubuntu'}]},key:{private_key_path:'/tmp/explicit'}});
    expect(result.user).toBe('ubuntu');expect(result['ssh-private-key-path']).toBe('/tmp/explicit');
    expect(compute.attach(fixture(),{status:'destroyed'})['posthog/already-destroyed']).toBe(true);
    expect(()=>compute.node({cluster:{nodes:[]}})).toThrow();
  });
  test("offline start needs no credentials", async()=> {
    for(const f of [fixture,optout,vultr,vultrOptout]) expect((await workflow.startStep(f({'red/event':'build'}),{}))['red/exit']).toBe(0);
  });
  test("managed build and external SSH identities are deterministic",()=> {
    expect(ssh.withMachineKey(fixture({'red/event':'build'}))['ssh-private-key-path']).toBe('/home/build-placeholder/.ssh/posthog-fixture');
    expect(ssh.withMachineKey(optout({'red/event':'build'}))).toEqual(optout({'red/event':'build'}));
    expect(ssh.identityArgs(optout())[1]).toBe('/home/build-placeholder/.ssh/operator-key');
  });
});

test("observed non-root login reaches acceptance with sudo",async()=>{
 let seen:string[]=[];runtime.exec=async(cmd)=>{seen=cmd;return {exit:0,out:'ok',err:''};};
 await tools.sshOut({ip:'203.0.113.7',user:'ubuntu','ssh-private-key-path':'/tmp/key'},"docker ps --format '{{.Names}}'",1000);
 expect(seen.at(-2)).toBe('ubuntu@203.0.113.7');expect(seen.at(-1)).toStartWith('sudo -n -- sh -c ');
});
test("cleanup IP override follows successful owned-state inspection",async()=>{
 const stub=spyOn(compute,'load').mockImplementation(async(o)=>({...o,'red/exit':0,ip:'203.0.113.7',user:'ubuntu'}));
 try{const opts=fixture({...secrets,'red/event':'delete','compute-prevent-destroy':false,ip:'203.0.113.99'});
 const result=await workflow.startStep(opts,{});expect(stub).toHaveBeenCalled();expect(result.ip).toBe('203.0.113.99');expect(result.user).toBe('ubuntu');
 stub.mockImplementation(async(o)=>({...o,'red/exit':1,'red/err':'state unreadable'}));expect((await workflow.startStep(opts,{}))['red/exit']).toBe(1);
 }finally{stub.mockRestore();}
});
