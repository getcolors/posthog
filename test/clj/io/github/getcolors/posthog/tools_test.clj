(ns io.github.getcolors.posthog.tools-test
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.posthog.tools :as tools]
            [io.github.getcolors.posthog.validate-test :refer [fixture]]))

(deftest infrastructure-discovers-default-vpc
  (let [data (tools/infrastructure-data (fixture))]
    (is (= ["0.0.0.0/0" "::/0"] (tools/cidrs data :digitalocean-http-sources)))))

(deftest dns-is-apex-and-proxied
  (let [json (tools/dns-json (assoc (fixture) :ip "192.0.2.10" :posthog-zone "example.com"))]
    (is (str/includes? json "posthog.example.com"))
    (is (str/includes? json "192.0.2.10"))
    (is (str/includes? json "proxied"))))

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
    (is (not (str/includes? compose "POSTHOG_SKIP_MIGRATION_CHECKS")))))

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
