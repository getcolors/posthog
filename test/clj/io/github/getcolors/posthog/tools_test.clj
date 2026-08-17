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
