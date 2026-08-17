(ns io.github.getcolors.posthog.tools-test
  (:require [clojure.string :as str]
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
    (is (str/includes? compose "  plugin-server:"))
    (is (str/includes? compose "./bin/plugin-server"))
    (is (str/includes? compose "KAFKA_HOSTS"))
    ;; Every named collection the migrations may reference must resolve.
    (doseq [collection ["msk_cluster" "warpstream_ingestion" "warpstream_calculated_events"
                        "warpstream_replay" "warpstream_shared" "warpstream_cyclotron"]]
      (is (str/includes? @playbook (str "<" collection ">"))))))
