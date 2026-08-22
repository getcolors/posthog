(ns io.github.getcolors.posthog.workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.posthog.validate-test :refer [fixture]]
            [io.github.getcolors.posthog.workflow :as workflow]))

(deftest build-and-dry-run-need-no-credentials
  (is (= 0 (:green/exit (workflow/start-step (assoc (fixture) :green/event :build) {}))))
  (is (= 0 (:green/exit (workflow/start-step
                         (assoc (fixture) :green/event :create :green/dry-run true) {})))))

(deftest real-create-requires-credentials
  (let [r (workflow/start-step (assoc (fixture) :green/event :create) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))
    (is (str/includes? (:green/err r) "COLORS_PAR_POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY"))))

(deftest delete-is-protected
  (let [r (workflow/start-step (assoc (fixture) :green/event :delete) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COMPUTE_PREVENT_DESTROY"))))

(defn deletable-fixture
  "A fixture that passes real-delete preflight: guard lifted, secrets present."
  [& {:as overrides}]
  (merge (fixture :compute-prevent-destroy false
                  :do-token "t" :cloudflare-api-token "t"
                  :posthog-secret-key "s" :posthog-postgres-password "p"
                  :posthog-oidc-rsa-private-key "k"
                  :posthog-encryption-salt-keys "k"
                  :posthog-admin-password "p"
                  :posthog-backup-r2-access-key-id "k"
                  :posthog-backup-r2-secret-access-key "s"
                  :r2-access-key-id "k" :r2-secret-access-key "s")
         overrides))

(deftest delete-fails-loudly-when-state-is-unreadable
  ;; Swallowing a failed state read is how a live teardown ended up pointing
  ;; the cleanup playbook at 192.0.2.10: stale backend credentials made
  ;; `tofu output` fail, nil was merged, and the inventory fell back to
  ;; TEST-NET. The failure must surface here, before any playbook runs.
  (with-redefs [workflow/state-output (fn [_] (throw (ex-info "Unauthorized" {})))]
    (let [r (workflow/start-step (deletable-fixture :green/event :delete) {})]
      (is (= 1 (:green/exit r)))
      (is (str/includes? (:green/err r) "Unauthorized"))
      (is (str/includes? (:green/err r) "COLORS_PAR_IP")))))

(deftest delete-with-explicit-ip-skips-the-state-read
  ;; COLORS_PAR_IP is the operator's escape hatch when the state backend is
  ;; unreachable; it must not require the read it exists to replace.
  (with-redefs [workflow/state-output (fn [_] (throw (ex-info "must not be called" {})))]
    (let [r (workflow/start-step (deletable-fixture :green/event :delete
                                                    :ip "203.0.113.7") {})]
      (is (= 0 (:green/exit r)))
      (is (= "203.0.113.7" (:ip r))))))

(deftest delete-with-empty-state-proceeds-without-an-address
  ;; State readable, no compute recorded: the instance is already gone, the
  ;; cleanup step skips itself, and the rest of the teardown still runs.
  (with-redefs [workflow/state-output (fn [_] nil)]
    (let [r (workflow/start-step (deletable-fixture :green/event :delete) {})]
      (is (= 0 (:green/exit r)))
      (is (nil? (:ip r))))))

(deftest graph-orders-private-stack
  (is (= [:posthog/infrastructure]
         (vec (rest (workflow/wire-fn :posthog/start {:green/event :create})))))
  (is (= [:posthog/dns]
         (vec (rest (workflow/wire-fn :posthog/infrastructure {:green/event :create})))))
  (is (= [:posthog/ansible]
         (vec (rest (workflow/wire-fn :posthog/start {:green/event :delete}))))))
