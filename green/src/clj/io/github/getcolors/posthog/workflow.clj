(ns io.github.getcolors.posthog.workflow
  (:require [clojure.walk :as walk]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.posthog.ssh :as ssh]
            [io.github.getcolors.posthog.ssh-config :as ssh-config]
            [io.github.getcolors.posthog.tools :as tools]
            [io.github.getcolors.posthog.validate :as validate]))

(def defaults {:provider-compute "digitalocean" :provider-dns "cloudflare"
               :provider-backend "local" :compute-prevent-destroy true
               :workdir ".colors"})

(defn state-output
  "Compute params recorded in the infrastructure state; nil when the state
  holds none. An unreadable backend throws — the delete path treats that as
  fatal rather than falling back to the documentation address."
  [opts]
  (some-> (tofu/outputs (tools/tool-dir opts tools/infrastructure-tool)
                        (tools/backend-credential-env opts))
          :params walk/keywordize-keys))

(defn adopt-state
  "A real delete runs the ansible cleanup before the infrastructure step, so
  the instance address must come out of the existing state here. An explicit
  :ip (COLORS_PAR_IP) skips the read; a readable state without compute params
  leaves :ip unset and the cleanup step skips itself; an unreadable backend
  fails loudly — swallowing it is how a live teardown ended up converging
  against 192.0.2.10."
  [opts]
  (if (:ip opts)
    (assoc opts :green/exit 0)
    (try (merge opts (state-output opts) {:green/exit 0})
         (catch Exception e
           (assoc opts :green/exit 1
                  :green/err (str "could not read the infrastructure state for "
                                  "the delete cleanup: " (ex-message e) "\n"
                                  "fix the backend credentials, or supply "
                                  (green-cli/par-name :ip)
                                  " to address the instance directly"))))))

(defn best-effort-state
  "`state-output` for the keypair's create matrix, which keys on a best-effort
  read: an unreadable state (a fresh clone, a missing backend) counts as absent
  on a create. The fail-closed reading above is the delete path's alone."
  [opts]
  (try (state-output opts) (catch Exception _ nil)))

(defn after-validate
  "The lifecycle transition table, once the validators have passed.

  build and dry-run only render: `with-machine-key` fills the placeholder key
  paths and nothing under `~/.ssh` or `~/.ssh/config` is read. A real create
  runs the keypair's create matrix and the DigitalOcean preflight before any
  template is rendered — an unowned key on disk or at the provider stops the
  run while stopping is still free — then the `~/.ssh/config` ownership and
  placement checks. A real delete fills the same template values (a destroy
  renders before it destroys) and adopts the instance address from state,
  fail-closed; it checks no key, because its cleanup runs after the destroy."
  [opts {:keys [event real?]}]
  (cond
    (and real? (= :delete event))
    (adopt-state (ssh/with-machine-key opts))

    (and real? (= :create event))
    (let [opts (ssh/ensure-key! opts best-effort-state)]
      (if (wf/failed? opts)
        opts
        (let [opts (ssh/preflight! (ssh/with-machine-key opts))
              opts (if (wf/failed? opts) opts (ssh-config/preflight! opts))]
          (if (wf/failed? opts) opts (assoc opts :green/exit 0)))))

    :else
    (assoc (ssh/with-machine-key opts) :green/exit 0)))

(defn start-step
  ([opts] (start-step opts (System/getenv)))
  ([opts env]
   (lifecycle/preflight
    opts {:defaults defaults :overlay green-cli/read-pars
          :validators
          [(fn [_ env _] (validate/env-errors env))
           (fn [opts _ _] (validate/state-errors opts))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (contains? #{:create :delete} event))
               (validate/secret-errors opts)))
           (fn [opts _ {:keys [event real?]}]
             (when (and real? (= :delete event) (:compute-prevent-destroy opts))
               [(str "compute destruction is protected; set "
                     (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
          :after-validate (fn [opts _ context] (after-validate opts context))} env)))

(defn wire-fn [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :posthog/start [start-step :posthog/ansible]
      :posthog/ansible [tools/ansible-step :posthog/dns]
      ;; The `~/.ssh/config` block goes before the destroy, the opposite of the
      ;; keypair below. A block that outlives its host is stale but harmless; a
      ;; key that predeceases its host locks the operator out of a machine that
      ;; still exists. Both orders are deliberate; see standards/ssh-config.md.
      :posthog/dns [tools/dns-step :posthog/ssh-config]
      :posthog/ssh-config [tools/ansible-local-step :posthog/infrastructure]
      :posthog/infrastructure [tools/infrastructure-step :posthog/ssh-cleanup]
      :posthog/ssh-cleanup [ssh/cleanup-step])
    (case step
      :posthog/start [start-step :posthog/infrastructure]
      ;; After compute, which is where the address first exists, and before the
      ;; stage that converges the machine.
      :posthog/infrastructure [tools/infrastructure-step :posthog/ssh-config]
      :posthog/ssh-config [tools/ansible-local-step :posthog/dns]
      :posthog/dns [tools/dns-step :posthog/ansible]
      :posthog/ansible [tools/ansible-step :posthog/acceptance]
      :posthog/acceptance [tools/acceptance-step])))

(defn backend-advice [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (:profile %) "/" tool ".tfstate")}))

(def side-effecting
  [:posthog/infrastructure :posthog/dns :posthog/ssh-config
   :posthog/ansible :posthog/acceptance :posthog/ssh-cleanup])

(def workflow
  (-> (wf/workflow {:start :posthog/start :wire-fn wire-fn})
      (wf/advice-add :posthog/infrastructure :before ::backend
                     (backend-advice tools/infrastructure-tool))
      (wf/advice-add :posthog/dns :before ::backend (backend-advice tools/dns-tool))
      progress/advise
      (dry-run/advise side-effecting)))
