(ns io.github.getcolors.posthog.tools-test
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.ansible :as ansible]
            [io.github.getcolors.posthog.tools :as tools]
            [io.github.getcolors.posthog.validate-test :refer [fixture]]))

(deftest delete-cleanup-skips-when-state-has-no-compute
  ;; With the instance already gone the inventory would render 192.0.2.10;
  ;; there is no host to reach, so the step must not run the playbook and the
  ;; teardown must continue past it.
  (with-redefs [ansible/ansible-with-spec
                (fn [& _] (throw (ex-info "playbook must not run" {})))]
    (let [r (tools/ansible-step (fixture :green/event :delete))]
      (is (= 0 (:green/exit r)))
      (is (= :skipped-no-compute (:posthog/cleanup r))))))

(deftest delete-cleanup-targets-the-adopted-address
  ;; When the start step recovered the instance address from state, the
  ;; cleanup playbook runs against it, never the documentation fallback.
  (with-redefs [ansible/ansible-with-spec
                (fn [opts _ _] (assoc opts :green/exit 0 ::ran-against (:ip opts)))]
    (let [r (tools/ansible-step (fixture :green/event :delete :ip "203.0.113.7"))]
      (is (= "203.0.113.7" (::ran-against r))))))

(deftest infrastructure-discovers-default-vpc
  (let [data (tools/infrastructure-data (fixture))]
    (is (= ["0.0.0.0/0" "::/0"] (tools/cidrs data :digitalocean-http-sources)))))

(deftest dns-is-apex-and-proxied
  (let [json (tools/dns-json (assoc (fixture) :ip "192.0.2.10" :posthog-zone "example.com"))]
    (is (str/includes? json "posthog.example.com"))
    (is (str/includes? json "192.0.2.10"))
    ;; Assert the value, not the key: "proxied" is in the rendered record
    ;; either way, so this passed on an unproxied record too.
    (is (str/includes? json "\"proxied\" : true"))))

(deftest dns-proxying-can-be-declined
  ;; It was hardcoded, so setting the key did nothing and said nothing.
  (is (str/includes? (tools/dns-json (assoc (fixture) :ip "192.0.2.10"
                                            :posthog-zone "example.com"
                                            :cloudflare-proxied false))
                     "\"proxied\" : false")))

(deftest inventory-keeps-one-private-target
  (let [inventory (tools/inventory (assoc (fixture) :ip "192.0.2.10"))]
    (is (str/includes? inventory "192.0.2.10"))
    (is (str/includes? inventory "posthog-fixture"))))

(def playbook
  (delay (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/main.yml")))

(deftest convergence-migrates-then-waits-on-the-web-service
  ;; Waiting on pg_isready declared the stack converged while the application
  ;; was still unmigrated, so the ordering here is the contract.
  (let [migrate (str/index-of @playbook "manage.py migrate_clickhouse")
        health (str/index-of @playbook "/_health/")]
    (is (some? migrate))
    (is (some? health))
    (is (< migrate health))))

(deftest broker-does-not-evict
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (is (str/includes? compose "--maxmemory-policy noeviction"))
    ;; The guarantee is not the in-container check -- which blocks on
    ;; run_async_migrations -- but that the playbook migrates explicitly and
    ;; fails the converge when that fails.
    (is (str/includes? @playbook "manage.py migrate && python manage.py migrate_clickhouse"))))

(deftest compose-template-carries-no-default-credential
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")
        rendered (tools/ansible-data (fixture))]
    ;; The Django signing key was a constant in this public repository, so a
    ;; rendered artefact must never be able to carry one again.
    (is (not (str/includes? compose "insecure-secret-key")))
    (is (not (re-find #"POSTGRES_PASSWORD: posthog" compose)))
    (is (nil? (:posthog-secret-key rendered)))
    (is (str/includes? compose "urlencode | replace('/', '%2F')"))))

(deftest capture-is-judged-by-the-stored-row-not-the-status
  ;; The previous step computed a capture result and never looked at it.
  (is (= :ingested (tools/ingestion-verdict "200" 4 5)))
  (is (= :dropped (tools/ingestion-verdict "200" 4 4)))
  (is (= :dropped (tools/ingestion-verdict "202" 4 nil)))
  (is (= :rejected (tools/ingestion-verdict "401" 4 4)))
  (is (= :unreachable (tools/ingestion-verdict nil 4 4))))

(deftest backup-must-be-fresh-and-non-empty
  (let [since (java.time.Instant/parse "2026-08-17T02:30:00Z")
        entry (fn [size mod-time] {:Size size :ModTime mod-time})]
    (is (tools/fresh-backup? [(entry 1024 "2026-08-17T02:30:05Z")] since))
    (is (tools/fresh-backup? [(entry 1024 "2026-08-17T04:30:05+02:00")] since))
    (is (not (tools/fresh-backup? [(entry 1024 "2026-08-16T02:30:05Z")] since)))
    (is (not (tools/fresh-backup? [(entry 0 "2026-08-17T02:30:05Z")] since)))
    (is (not (tools/fresh-backup? [] since)))
    (is (not (tools/fresh-backup? nil since)))))

(deftest clickhouse-backup-is-native-and-has-no-torn-fallback
  ;; A hot tar of the data directory races running merges and produces an
  ;; archive that cannot be restored; a failed backup must fail the run.
  (let [backup (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/backup")]
    (is (str/includes? backup "BACKUP DATABASE"))
    (is (str/includes? backup "/var/lib/clickhouse/backups/"))
    (is (not (str/includes? backup "tar -czf")))))

(deftest datastores-start-before-migrations-and-app-after
  ;; Bringing `web` up first put the image's own startup migration in a race
  ;; with the explicit one, and the loser died on "relation already exists".
  (let [start (str/index-of @playbook "docker compose up -d db redis kafka clickhouse")
        migrate (str/index-of @playbook "manage.py migrate_clickhouse")
        app (str/index-of @playbook "Converge the application containers")]
    (is (and start migrate app))
    (is (< start migrate app))
    ;; A handler flush must not be able to start the stack ahead of migrations.
    (is (not (str/includes? @playbook "Restart PostHog stack")))))

(deftest clickhouse-has-coordination-for-replicated-tables
  ;; migrate_clickhouse passes replicated=True unconditionally, so every table
  ;; is a ReplicatedMergeTree and the first CREATE dies with "There is no
  ;; Zookeeper configuration in server config" unless Keeper is configured.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (is (str/includes? @playbook "<keeper_server>"))
    (is (str/includes? @playbook "<zookeeper>"))
    ;; Replicated table paths substitute these; without them the DDL is invalid.
    (is (str/includes? @playbook "<shard>"))
    (is (str/includes? @playbook "<replica>"))
    ;; cluster.py selects hosts with getMacro on both of these; without them
    ;; migrate_clickhouse dies with "No macro hostClusterType in config".
    (is (str/includes? @playbook "<hostClusterType>online</hostClusterType>"))
    ;; "data" matches callers requesting DATA and the ALL wildcard; "all" would
    ;; match only the latter.
    (is (str/includes? @playbook "<hostClusterRole>data</hostClusterRole>"))
    (is (str/includes? compose "config.d/keeper.xml"))))

(deftest clickhouse-config-changes-reach-the-container
  ;; Single-file bind mounts bind to the inode, and copy replaces by rename, so
  ;; without a recreate the server keeps serving the previous config while the
  ;; host file looks correct.
  (is (str/includes? @playbook "--force-recreate clickhouse"))
  (is (str/includes? @playbook "clickhouse_keeper_config.changed"))
  (is (str/includes? @playbook "clickhouse_clusters_config.changed"))
  ;; Change flags alone are not enough: on a converge where the files were
  ;; already correct, copy reports no change while the container still serves
  ;; the config it started with. The reload must key off the server's state.
  (is (str/includes? @playbook "FROM system.macros WHERE macro = 'hostClusterType'"))
  (is (str/includes? @playbook "FROM system.named_collections WHERE name = 'msk_cluster'"))
  (is (str/includes? @playbook "clickhouse_macros.stdout"))
  ;; And the migration must not start before the reloaded server is answering.
  (let [reload (str/index-of @playbook "--force-recreate clickhouse")
        wait (str/index-of @playbook "Wait for ClickHouse to accept queries")
        migrate (str/index-of @playbook "manage.py migrate_clickhouse")]
    (is (< reload wait migrate))))

(deftest cluster-hosts-are-reachable-from-every-container
  ;; system.clusters is read by web and worker, which dial the advertised host;
  ;; loopback there is the client container, not ClickHouse.
  (is (not (str/includes? @playbook "<host>127.0.0.1</host><port>9000</port>")))
  (is (str/includes? @playbook "<host>clickhouse</host><port>9000</port>"))
  ;; Keeper is embedded in the ClickHouse server, so those stay on loopback.
  (is (str/includes? @playbook "<host>127.0.0.1</host>\n                      <port>9181</port>")))

(deftest ingestion-tier-is-present
  ;; PostHog's path is capture -> Kafka -> plugin-server -> ClickHouse, and its
  ;; ClickHouse migrations create Kafka engine tables, so a broker is required
  ;; for the schema to exist at all -- not only for events to flow.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (is (str/includes? compose "  kafka:"))
    ;; posthog/posthog no longer ships a Node plugin-server; ClickHouse
    ;; consumes the ingestion topics itself through its Kafka engine tables,
    ;; which is what the named collections above are for.
    (is (not (str/includes? compose "./bin/plugin-server")))
    (is (str/includes? compose "KAFKA_HOSTS"))
    ;; Every named collection the migrations may reference must resolve.
    ;; The full set from upstream's docker/clickhouse/config.d/default.xml;
    ;; settings.py only reveals six of the eight.
    (doseq [collection ["msk_cluster" "warpstream_ingestion" "warpstream_calculated_events"
                        "warpstream_replay" "warpstream_shared" "warpstream_cyclotron"
                        "warpstream_logs" "warpstream_traces"]]
      (is (str/includes? @playbook (str "<" collection ">"))))))

(deftest system-log-tables-exist-before-migrations
  ;; system.crash_log is created on first write, and a migration reads it.
  (let [flush (str/index-of @playbook "SYSTEM FLUSH LOGS")
        migrate (str/index-of @playbook "manage.py migrate_clickhouse")]
    (is (some? flush))
    (is (< flush migrate))))

(deftest temporal-is-present-and-gates-the-application
  ;; Django's startup connects to Temporal through the Rust SDK bridge; when
  ;; that fails the web process never binds, so this is a hard dependency
  ;; rather than a degraded feature.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (is (str/includes? compose "  temporal:"))
    (is (str/includes? compose "TEMPORAL_HOST: temporal"))
    (is (str/includes? compose "temporal: {condition: service_healthy}"))))

(deftest compose-template-is-valid-yaml
  ;; The multi-line PEM has to reach the container without breaking the
  ;; document: an unquoted {{ ... }} reads as a flow mapping and makes the file
  ;; unparseable, which Docker only discovers on the host.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")
        parsed (yaml/parse-string compose)]
    (is (contains? (:services parsed) :web))
    (is (= 10 (count (:services parsed))))))

(deftest temporal-dynamic-config-exists-in-the-image
  ;; Upstream mounts development-sql.yaml from its checkout; the auto-setup
  ;; image ships only docker.yaml, and pointing at a missing file leaves the
  ;; server refusing connections while schema setup reports success.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (is (str/includes? compose "DYNAMIC_CONFIG_FILE_PATH: config/dynamicconfig/docker.yaml"))
    (is (not (str/includes? compose "DYNAMIC_CONFIG_FILE_PATH: config/dynamicconfig/development-sql.yaml")))))

(deftest web_serves_without_rerunning_migrations
  ;; ./bin/docker runs ./bin/migrate first and loops on
  ;; schedule_temporal_workflows, so the server never binds.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (is (str/includes? compose "command: ./bin/docker-server"))))

(def checkpoint
  (delay (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/checkpoint.sql")))

(deftest checkpoint-carries-schema-and-migration-bookkeeping
  ;; Without the django_migrations rows a restore would look complete and then
  ;; replay 0001_initial against existing tables -- the exact failure this
  ;; deployment hit early on.
  (is (str/includes? @checkpoint "CREATE TABLE"))
  (is (str/includes? @checkpoint "COPY public.django_migrations"))
  ;; Schema only: no other table's rows may ride along.
  (is (= 1 (count (re-seq #"(?m)^COPY public\." @checkpoint)))))

(deftest checkpoint-restores-only-into-an-empty-database-and-still-migrates
  (let [restore (str/index-of @playbook "Restore the schema checkpoint")
        migrate (str/index-of @playbook "manage.py migrate_clickhouse")]
    (is (< restore migrate))
    ;; Guarded on the live schema, so it can never land on top of data.
    (is (str/includes? @playbook "posthog_schema.stdout"))
    ;; Faking the migration state would make a stale checkpoint permanent
    ;; instead of self-healing; only the comment may mention it.
    (is (not (re-find #"manage\.py migrate[^\n]*--fake" @playbook)))))

(deftest django-trusts-the-proxy
  ;; Otherwise every non-exempt path 301s to itself behind Caddy and Cloudflare.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (is (str/includes? compose "IS_BEHIND_PROXY"))))

(deftest ingestion-paths-reach-the-capture-service
  ;; Django resolves /capture/, /e/ and /i/v0/e/ to its catch-all frontend view,
  ;; which answers 403 via CSRF -- proxying them to the app can never ingest.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")
        caddyfile (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/Caddyfile")]
    (is (str/includes? compose "  capture:"))
    (is (str/includes? compose "CAPTURE_V1_SINK_MSK_KAFKA_TOPIC_MAIN"))
    (is (str/includes? caddyfile "reverse_proxy capture:3000"))
    (doseq [path ["/capture" "/e" "/batch" "/i/*"]]
      (is (str/includes? caddyfile path)))))

(deftest caddy-serves-the-current-configuration
  ;; A single-file bind mount pins the inode, so a rewritten Caddyfile never
  ;; reaches the running container: ingestion routes existed on disk while
  ;; Caddy still proxied everything to the application.
  (is (str/includes? @playbook "--force-recreate caddy"))
  (is (str/includes? @playbook "sha256sum /etc/caddy/Caddyfile"))
  (let [reload (str/index-of @playbook "--force-recreate caddy")
        health (str/index-of @playbook "Wait for the PostHog web service")]
    (is (< reload health))))

(deftest something-consumes-the-ingestion-topic
  ;; Capture produces to events_plugin_ingestion; ClickHouse's Kafka engine
  ;; tables read clickhouse_* topics. Without a consumer bridging them the API
  ;; accepts events that never reach the database.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (is (str/includes? compose "  plugins:"))
    (is (str/includes? compose "PERSONS_DATABASE_URL"))))

(deftest plugin-server-gets-a-geoip-database
  ;; It loads one at startup and exits when it is missing; its image ships none.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (is (str/includes? @playbook "GeoLite2-City.mmdb"))
    (is (str/includes? compose "/share/GeoLite2-City.mmdb:ro"))))

(deftest every-plugin-server-redis-client-is-pointed-at-redis
  ;; Only the first client reads REDIS_URL; the others default to 127.0.0.1 and
  ;; exit the process when they cannot connect.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (doseq [v ["CDP_REDIS_HOST" "LOGS_REDIS_HOST" "INGESTION_REDIS_HOST"
               "POSTHOG_REDIS_HOST" "COOKIELESS_REDIS_HOST"]]
      (is (str/includes? compose (str v ": redis"))))))

(deftest plugin-server-runs-an-ingestion-mode
  ;; Without a mode it exits cleanly at startup, having consumed nothing.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (is (str/includes? compose "PLUGIN_SERVER_MODE: ingestion-v2-combined"))))

(deftest encryption-keys-are-shared-and-required
  ;; The plugin server throws "Encryption keys are not set" and exits; below
  ;; debug level that looks like a clean shutdown, so it must never be optional.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (is (str/includes? compose "ENCRYPTION_SALT_KEYS"))
    ;; In the shared anchor, so application and plugin server agree.
    (is (< (str/index-of compose "ENCRYPTION_SALT_KEYS") (str/index-of compose "services:")))
    (is (str/includes? @playbook "COLORS_PAR_POSTHOG_ENCRYPTION_SALT_KEYS"))))

(deftest application-and-plugin-server-images-are-pinned-together
  ;; They share a Postgres schema. With floating tags the node process queried
  ;; posthog_person.last_seen_at, a column the application's migrations had
  ;; never created, and died in its consume loop.
  (let [fixture (slurp "test/fixtures/colors.yml")
        app (second (re-find #"posthog-image: posthog/posthog:(\S+)" fixture))
        node (second (re-find #"posthog-plugin-server-image: posthog/posthog-node:(\S+)" fixture))]
    (is (= app node))
    (is (not= "latest" app))))

(deftest checkpoint-is-bound-to-the-commit-it-came-from
  ;; Behind the image on one lineage a checkpoint heals forward. From a
  ;; divergent commit it leaves migrations the image never had, and migrate
  ;; stops on orphaned migrations -- so restore only on an exact match.
  (is (str/starts-with? @checkpoint "-- posthog-commit: "))
  (is (str/includes? @playbook "posthog_checkpoint_commit.stdout"))
  (is (str/includes? @playbook "posthog_image_commit.stdout")))

(deftest person-column-the-plugin-server-needs-is-created
  ;; The node image queries a column the application's migrations at the same
  ;; commit do not create; without it ingestion accepts events and stores none.
  (is (str/includes? @playbook "posthog_person ADD COLUMN IF NOT EXISTS last_seen_at"))
  (let [alter (str/index-of @playbook "ADD COLUMN IF NOT EXISTS last_seen_at")
        migrate (str/index-of @playbook "manage.py migrate_clickhouse")]
    ;; After migrations, so a real migration adding it wins.
    (is (< migrate alter))))

(deftest capture-image-is-pinned-not-floating
  ;; The application and plugin server are already pinned to one commit; the
  ;; capture service was still on a branch tag that moves under the deployment.
  (let [fixture (slurp "test/fixtures/colors.yml")]
    (is (re-find #"posthog-capture-image: \S+@sha256:[0-9a-f]{64}" fixture))
    (is (not (re-find #"image:\s*\S+:(latest|master)\s*$" fixture)))))

(deftest celery-and-plugin-server-health-are-addressed
  ;; PostHog's own setup UI reported both as errors: Celery could not start
  ;; until required async migrations were complete, and the plugin server was
  ;; probed through a Kubernetes service name that resolves nowhere here.
  (let [compose (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")]
    (is (str/includes? compose "CDP_API_URL: \"http://plugins:6738\""))
    (is (str/includes? @playbook "--complete-noop-migrations"))
    ;; Backfills are only completed where there is nothing to backfill.
    (is (str/includes? @playbook "posthog_event_count.stdout"))))

(deftest background-jobs-verdict-distinguishes-the-failures
  ;; The ingestion path never touches Celery, so this is the only part of
  ;; acceptance that can notice a worker that never started.
  (is (= :ok (tools/background-verdict "celery=True pending=0")))
  ;; A pending async migration is exactly what stopped the worker booting.
  (is (= :migrations-pending (tools/background-verdict "celery=True pending=4")))
  (is (= :celery-down (tools/background-verdict "celery=False pending=0")))
  (is (= :unreachable (tools/background-verdict "")))
  (is (= :unreachable (tools/background-verdict nil))))

(deftest an-owner-account-is-provisioned
  ;; Without one a converge leaves an instance nobody can log into: the hosted
  ;; realm only lets the first user create an organization, and the acceptance
  ;; step's project already creates one.
  (let [owner (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/owner.py")]
    (is (str/includes? @playbook "owner.py"))
    (is (str/includes? @playbook "COLORS_PAR_POSTHOG_ADMIN_PASSWORD"))
    ;; All three states, so a converge is idempotent whatever it finds.
    (doseq [state ["bootstrapped" "joined" "rotated"]]
      (is (str/includes? owner (str "OWNER=" state))))))

(deftest a-missing-compute-output-fails-loudly
  ;; The documentation address belongs to build and dry-run. Merging it into a
  ;; real converge would point Ansible at TEST-NET instead of failing.
  (is (= "1.2.3.4" (:ip (tools/resolved-compute {} {:ip "192.0.2.10"} {:ip "1.2.3.4"}))))
  (is (= 1 (:green/exit (tools/resolved-compute {} {:ip "192.0.2.10"} nil))))
  (is (= 1 (:green/exit (tools/resolved-compute {} {:ip "192.0.2.10"} {}))))
  (is (nil? (:green/exit (tools/resolved-compute {} {:ip "192.0.2.10"} {:ip "5.6.7.8"})))))

(def caddyfile
  (delay (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/Caddyfile")))

(def compose
  (delay (slurp "src/resources/io/github/getcolors/posthog/tools/ansible/compose.yml")))

(deftest caddy-access-logging-is-on-and-bounded
  ;; Access logging is off by default in Caddy, so a successful request left no
  ;; trace and capture had no request-level evidence to debug from.
  (is (str/includes? @caddyfile "log {"))
  (is (str/includes? @caddyfile "output stdout"))
  ;; On, but bounded: json-file never rotates on its own and this endpoint
  ;; writes a line per request.
  (is (str/includes? @compose "max-size"))
  (is (str/includes? @compose "max-file")))

(deftest access-log-records-the-visitor-not-the-proxy
  ;; Behind the Cloudflare proxy every connection arrives from an edge address,
  ;; so without trusted_proxies Caddy attributes each request to Cloudflare and
  ;; the access log answers "who sent this?" with the proxy. Verified against a
  ;; live deployment: the arm with this block logged the real client address
  ;; and the arm without it logged 162.158.x.
  (is (str/includes? @caddyfile "trusted_proxies static"))
  (is (str/includes? @caddyfile "162.158.0.0/15"))
  (is (str/includes? @caddyfile "2400:cb00::/32")))
