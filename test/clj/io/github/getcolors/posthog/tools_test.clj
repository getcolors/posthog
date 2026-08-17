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
