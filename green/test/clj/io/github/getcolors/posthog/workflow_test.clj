(ns io.github.getcolors.posthog.workflow-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [green.process :as process]
            [io.github.getcolors.once.ssh :as once-ssh]
            [io.github.getcolors.posthog.ssh :as ssh]
            [io.github.getcolors.posthog.ssh-config :as ssh-config]
            [io.github.getcolors.posthog.validate-test :refer [fixture optout vultr]]
            [io.github.getcolors.posthog.workflow :as workflow]))

(deftest build-and-dry-run-need-no-credentials
  (is (= 0 (:green/exit (workflow/start-step (assoc (fixture) :green/event :build) {}))))
  (is (= 0 (:green/exit (workflow/start-step
                         (assoc (fixture) :green/event :create :green/dry-run true) {})))))

(deftest build-and-dry-run-never-touch-ssh
  ;; The standard forbids reading, creating, or requiring anything under ~/.ssh
  ;; on a build or dry-run: they render from desired state alone.
  (with-redefs [ssh-config/adopt-error (fn [_] (throw (ex-info "read ~/.ssh/config" {})))
                ssh-config/placement-error (fn [_] (throw (ex-info "read ~/.ssh/config" {})))
                once-ssh/ensure-key! (fn [& _] (throw (ex-info "touched ~/.ssh" {})))]
    (doseq [opts [(assoc (fixture) :green/event :build)
                  (assoc (fixture) :green/event :create :green/dry-run true)
                  (assoc (fixture) :green/event :delete :green/dry-run true)]]
      (let [result (workflow/start-step opts {})]
        (is (= 0 (:green/exit result)))
        (is (str/starts-with? (str (:ssh-public-key-path result)) "/home/build-placeholder")
            "a build must not name the operator's home directory")
        (is (= (:ssh-public-key-path result) (:digitalocean-ssh-keys result)))))))

(deftest opt-out-renders-the-historical-shape-on-every-rendered-event
  (doseq [opts [(assoc (optout) :green/event :build)
                (assoc (optout) :green/event :create :green/dry-run true)]]
    (let [result (workflow/start-step opts {})]
      (is (= 0 (:green/exit result)))
      (is (= "58495393" (:digitalocean-ssh-keys result)))
      (is (nil? (:ssh-keygen result))))))

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
      (is (str/includes? (:green/err r) "could not read the infrastructure state for the delete cleanup")))))

(deftest explicit-ip-never-skips-the-read-or-the-provider-guard
  ;; COLORS_PAR_IP replaces a stale recorded address once the read succeeded;
  ;; it is not a way around the read, the fail-closed rule, or the provider
  ;; guard (Compute Provider Standard §4).
  (with-redefs [workflow/state-output (fn [_] (throw (ex-info "Unauthorized" {})))]
    (let [r (workflow/start-step (deletable-fixture :green/event :delete :ip "203.0.113.7") {})]
      (is (= 1 (:green/exit r)))
      (is (str/includes? (:green/err r) "Unauthorized"))))
  (with-redefs [workflow/state-output (fn [_] {:provider "vultr" :ip "198.51.100.4"})]
    (let [r (workflow/start-step (deletable-fixture :green/event :delete :ip "203.0.113.7") {})]
      (is (= 2 (:green/exit r)))
      (is (str/includes? (:green/err r) "state holds a vultr machine"))))
  (with-redefs [workflow/state-output (fn [_] {:provider "digitalocean" :ip "198.51.100.4"})]
    (let [r (workflow/start-step (deletable-fixture :green/event :delete :ip "203.0.113.7") {})]
      (is (= 0 (:green/exit r)))
      (is (= "203.0.113.7" (:ip r)) "the override wins over the recorded address after the read"))))

(deftest state-is-read-once-per-run
  ;; One read serves the provider validator, the key matrix and the adoption;
  ;; a second read would be a second chance for the backend to disagree.
  (doseq [event [:create :delete]]
    (let [reads (atom 0)]
      (with-redefs [workflow/state-output (fn [_] (swap! reads inc) {:provider "digitalocean" :ip "203.0.113.9"})
                    ssh/ensure-key! (fn [opts state-fn] (assoc opts ::state (state-fn opts)))
                    ssh/preflight! identity ssh-config/preflight! identity]
        (let [r (workflow/start-step (deletable-fixture :green/event event :compute-prevent-destroy (= event :create)) {})]
          (is (= 0 (:green/exit r)) (str event))
          (is (= 1 @reads) (str event))
          (when (= event :create) (is (= "203.0.113.9" (:ip (::state r)))))
          (when (= event :delete) (is (= "203.0.113.9" (:ip r)))))))))

(deftest delete-with-empty-state-proceeds-without-an-address
  ;; State readable, no compute recorded: the instance is already gone, the
  ;; cleanup step skips itself, and the rest of the teardown still runs.
  (with-redefs [workflow/state-output (fn [_] nil)]
    (let [r (workflow/start-step (deletable-fixture :green/event :delete) {})]
      (is (= 0 (:green/exit r)))
      (is (nil? (:ip r))))))

(deftest real-delete-fills-the-real-key-paths-and-adopts-state
  ;; The transition table's last row: a destroy renders before it destroys, so
  ;; the template values are the real ones, merged with the adopted state. No
  ;; key check runs — the cleanup comes after the destroy.
  (let [home (str (fs/create-temp-dir {:prefix "posthog-home"}))]
    (try
      (with-redefs [once-ssh/home-dir (constantly home)
                    workflow/state-output (fn [_] {:ip "203.0.113.9" :ssh_key_id "77"})]
        (let [r (workflow/start-step (deletable-fixture :green/event :delete) {})]
          (is (= 0 (:green/exit r)))
          (is (= "203.0.113.9" (:ip r)))
          (is (= (str (io/file home ".ssh" "posthog-fixture")) (:ssh-private-key-path r)))
          (is (true? (:ssh-keygen r)))))
      (finally (fs/delete-tree home)))))

(defn creatable-fixture
  "A fixture that passes real-create preflight: secrets present."
  [& {:as overrides}]
  (apply deletable-fixture :compute-prevent-destroy true (mapcat identity overrides)))

(deftest real-create-runs-the-key-matrix-then-both-preflights
  ;; Row three of the transition table, in order: ensure-key! against the
  ;; best-effort state read, the provider preflight, then the ~/.ssh/config
  ;; checks. Each stops the run on its own error.
  (let [calls (atom [])]
    (testing "all pass"
      (with-redefs [workflow/state-output (fn [_] nil)
                    ssh/ensure-key! (fn [opts state-fn] (swap! calls conj [:ensure (state-fn opts)]) opts)
                    ssh/preflight! (fn [opts] (swap! calls conj :preflight) opts)
                    ssh-config/preflight! (fn [opts] (swap! calls conj :ssh-config) opts)]
        (let [r (workflow/start-step (creatable-fixture :green/event :create) {})]
          (is (= 0 (:green/exit r)))
          (is (= [[:ensure nil] :preflight :ssh-config] @calls))
          (is (true? (:ssh-keygen r)) "the real key path is filled for the templates"))))
    (testing "an unreadable backend counts as no state on a create"
      (with-redefs [workflow/state-output (fn [_] (throw (ex-info "Unauthorized" {})))
                    ssh/ensure-key! (fn [opts state-fn] (assoc opts ::state (state-fn opts)))
                    ssh/preflight! identity
                    ssh-config/preflight! identity]
        (let [r (workflow/start-step (creatable-fixture :green/event :create) {})]
          (is (= 0 (:green/exit r)))
          (is (nil? (::state r))))))
    (testing "the key matrix stops the run"
      (with-redefs [workflow/state-output (fn [_] nil)
                    ssh/ensure-key! (fn [opts _] (assoc opts :green/exit 1 :green/err "half a keypair"))
                    ssh/preflight! (fn [_] (throw (ex-info "must not run" {})))
                    ssh-config/preflight! (fn [_] (throw (ex-info "must not run" {})))]
        (let [r (workflow/start-step (creatable-fixture :green/event :create) {})]
          (is (= 1 (:green/exit r)))
          (is (str/includes? (:green/err r) "half a keypair")))))
    (testing "the provider preflight stops the run"
      (with-redefs [workflow/state-output (fn [_] nil)
                    ssh/ensure-key! (fn [opts _] opts)
                    ssh/preflight! (fn [opts] (assoc opts :green/exit 1 :green/err "already has an SSH key"))
                    ssh-config/preflight! (fn [_] (throw (ex-info "must not run" {})))]
        (let [r (workflow/start-step (creatable-fixture :green/event :create) {})]
          (is (= 1 (:green/exit r)))
          (is (str/includes? (:green/err r) "already has an SSH key")))))
    (testing "the ~/.ssh/config checks stop the run"
      (with-redefs [workflow/state-output (fn [_] nil)
                    ssh/ensure-key! (fn [opts _] opts)
                    ssh/preflight! identity
                    ssh-config/preflight! (fn [opts] (assoc opts :green/exit 1 :green/err "refusing to manage"))]
        (let [r (workflow/start-step (creatable-fixture :green/event :create) {})]
          (is (= 1 (:green/exit r)))
          (is (str/includes? (:green/err r) "refusing to manage")))))))

(deftest opt-out-create-skips-the-key-matrix
  ;; Presence of the explicit key is the only switch: the package then
  ;; generates, validates and deletes nothing.
  ;; ONCE's own short-circuit is the thing under test, so it is not stubbed:
  ;; instead everything it would reach in keygen mode is made to throw.
  (with-redefs [workflow/state-output (fn [_] nil)
                process/run-with-timeout (fn [& _] (throw (ex-info "ssh-keygen must not run" {})))
                once-ssh/fetch-account-keys (fn [& _] (throw (ex-info "must not run" {})))
                ssh-config/preflight! identity]
    (let [r (workflow/start-step (apply creatable-fixture :green/event :create
                                        (mapcat identity (optout))) {})]
      (is (= 0 (:green/exit r)))
      (is (= "58495393" (:digitalocean-ssh-keys r))))))

(defn- with-secrets [opts]
  (merge opts {:do-token "t" :vultr-api-key "t" :cloudflare-api-token "t"
               :posthog-secret-key "s" :posthog-postgres-password "p"
               :posthog-oidc-rsa-private-key "k" :posthog-encryption-salt-keys "k"
               :posthog-admin-password "p" :posthog-backup-r2-access-key-id "k"
               :posthog-backup-r2-secret-access-key "s"
               :r2-access-key-id "k" :r2-secret-access-key "s"}))

(deftest provider-switch-is-refused-on-create-and-delete
  ;; Compute Provider Standard §4: all providers share one state key, so a
  ;; changed provider-compute on a profile with compute in state would plan a
  ;; cross-provider replacement. Both events refuse, and delete refuses because
  ;; it would render and destroy the *selected* provider's template.
  (with-redefs [workflow/state-output (fn [_] {:provider "digitalocean" :ip "203.0.113.7"})
                ssh/ensure-key! (fn [opts _] opts) ssh/preflight! identity ssh-config/preflight! identity]
    (doseq [opts [(with-secrets (assoc (vultr) :green/event :create))
                  (with-secrets (assoc (vultr) :green/event :delete :compute-prevent-destroy false))]]
      (let [r (workflow/start-step opts {})]
        (is (= 2 (:green/exit r)) (str (:green/event opts)))
        (is (str/includes? (:green/err r)
                           "state holds a digitalocean machine; set provider-compute back to digitalocean and delete first")))))
  (with-redefs [workflow/state-output (fn [_] {:provider "vultr" :ip "203.0.113.7"})]
    (doseq [opts [(with-secrets (assoc (fixture) :green/event :create))
                  (with-secrets (assoc (fixture) :green/event :delete :compute-prevent-destroy false))]]
      (is (str/includes? (:green/err (workflow/start-step opts {}))
                         "state holds a vultr machine; set provider-compute back to vultr and delete first")))))

(deftest provider-switch-reports-before-the-missing-token
  ;; The validator order is the thing under test: a mistaken provider edit
  ;; must report the actionable error, not a missing credential for the
  ;; newly selected provider.
  (with-redefs [workflow/state-output (fn [_] {:provider "digitalocean" :ip "203.0.113.7"})]
    (doseq [event [:create :delete]]
      (let [opts (dissoc (with-secrets (assoc (vultr) :green/event event :compute-prevent-destroy false))
                         :vultr-api-key)
            r (workflow/start-step opts {})
            lines (str/split-lines (str (:green/err r)))]
        (is (= 2 (:green/exit r)))
        (is (some #(str/includes? % "state holds a digitalocean machine") lines))
        (is (not-any? #(str/includes? % "required credential is not set: COLORS_PAR_VULTR_API_KEY") lines)
            (str event))))))

(deftest legacy-state-without-a-provider-accepts-only-the-default
  (with-redefs [workflow/state-output (fn [_] {:ip "203.0.113.7"})
                ssh/ensure-key! (fn [opts _] opts) ssh/preflight! identity ssh-config/preflight! identity]
    (doseq [event [:create :delete]]
      (is (= 0 (:green/exit (workflow/start-step
                             (with-secrets (assoc (fixture) :green/event event :compute-prevent-destroy false)) {})))
          (str "default provider " event))
      (let [r (workflow/start-step
               (with-secrets (assoc (vultr) :green/event event :compute-prevent-destroy false)) {})]
        (is (= 2 (:green/exit r)) (str "non-default " event))
        (is (str/includes? (:green/err r) "set provider-compute back to digitalocean"))))))

(deftest unreadable-backend-is-no-state-on-create-and-fatal-on-delete
  (with-redefs [workflow/state-output (fn [_] (throw (ex-info "Unauthorized" {})))
                ssh/ensure-key! (fn [opts state-fn] (assoc opts ::state (state-fn opts)))
                ssh/preflight! identity ssh-config/preflight! identity]
    (doseq [f [fixture vultr]]
      (let [r (workflow/start-step (with-secrets (assoc (f) :green/event :create)) {})]
        (is (= 0 (:green/exit r)))
        (is (nil? (::state r))))
      (let [r (workflow/start-step (with-secrets (assoc (f) :green/event :delete :compute-prevent-destroy false)) {})]
        (is (= 1 (:green/exit r)))
        (is (str/includes? (:green/err r) "could not read the infrastructure state for the delete cleanup"))
        (is (str/includes? (:green/err r) "Unauthorized"))))))

(deftest real-create-requires-the-selected-provider-credentials
  (let [r (workflow/start-step (assoc (vultr) :green/event :create) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COLORS_PAR_VULTR_API_KEY"))
    (is (not (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN")))))

(deftest graph-orders-private-stack
  (is (= [:posthog/infrastructure]
         (vec (rest (workflow/wire-fn :posthog/start {:green/event :create})))))
  (is (= [:posthog/ssh-config]
         (vec (rest (workflow/wire-fn :posthog/infrastructure {:green/event :create})))))
  (is (= [:posthog/dns]
         (vec (rest (workflow/wire-fn :posthog/ssh-config {:green/event :create})))))
  (is (= [:posthog/ansible]
         (vec (rest (workflow/wire-fn :posthog/dns {:green/event :create})))))
  (is (= [:posthog/acceptance]
         (vec (rest (workflow/wire-fn :posthog/ansible {:green/event :create})))))
  (is (= [:posthog/ansible]
         (vec (rest (workflow/wire-fn :posthog/start {:green/event :delete}))))))

(deftest delete-removes-the-key-after-the-compute-destroy
  ;; The ordering is what makes "key present ⇔ deployment exists" hold: a
  ;; failed destroy never reaches the cleanup step, and correctly leaves the
  ;; key that is still the only credential to whatever survived.
  (is (= [:posthog/dns]
         (vec (rest (workflow/wire-fn :posthog/ansible {:green/event :delete})))))
  (is (= [:posthog/ssh-config]
         (vec (rest (workflow/wire-fn :posthog/dns {:green/event :delete})))))
  (is (= [:posthog/infrastructure]
         (vec (rest (workflow/wire-fn :posthog/ssh-config {:green/event :delete})))))
  (is (= [:posthog/ssh-cleanup]
         (vec (rest (workflow/wire-fn :posthog/infrastructure {:green/event :delete})))))
  (is (empty? (rest (workflow/wire-fn :posthog/ssh-cleanup {:green/event :delete})))))
