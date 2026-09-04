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

(def defaults {:provider-compute validate/default-compute-provider
               :provider-dns "cloudflare"
               :provider-backend "local" :compute-prevent-destroy true
               :workdir ".colors"})

(defn state-output
  "Compute params recorded in the infrastructure state; nil when the state
  holds none. An unreadable backend throws — `read-state` is where the two are
  told apart, because create and delete treat them differently."
  [opts]
  (some-> (tofu/outputs (tools/tool-dir opts tools/infrastructure-tool)
                        (tools/backend-credential-env opts))
          :params walk/keywordize-keys))

(defn read-state
  "One read of the compute state per run, shaped so a caller can tell nothing
  recorded from nothing readable: `{:params m}` where `m` may be nil, or
  `{:error message}`. Needs backend credentials only."
  [opts]
  (try {:params (state-output opts)}
       (catch Exception e {:error (ex-message e)})))

(defn lifecycle-event?
  "A real create or delete: the two events that touch a provider."
  [{:keys [event real?]}]
  (and real? (contains? #{:create :delete} event)))

(defn provider-validator
  "Compute Provider Standard §4 before the credentials. The recorded provider
  is compared with the selected one first, so a mistaken provider edit reports
  the actionable error — put it back and delete — rather than a missing token
  for the provider that was just selected; validators aggregate, which is why
  a mismatch pre-empts the secrets check rather than sitting beside it. On a
  create an unreadable backend counts as no state (a fresh clone has none) and
  the credentials are checked as usual; on a delete `adopt-state` refuses it
  after validation."
  [opts {:keys [params]}]
  (let [mismatch (validate/provider-state-errors opts params)]
    (if (seq mismatch) mismatch (validate/secret-errors opts))))

(defn adopt-state
  "A real delete runs the ansible cleanup before the infrastructure step, so
  the instance address must come out of the existing state here. A readable
  state without compute params leaves :ip unset and the cleanup step skips
  itself; an unreadable backend fails loudly — swallowing it is how a live
  teardown ended up converging against 192.0.2.10. An explicit :ip
  (COLORS_PAR_IP) never skips the read or the provider guard: it only replaces
  the cleanup address once the read has succeeded, for a state whose recorded
  address is stale."
  [opts {:keys [params error]}]
  (if error
    (assoc opts :green/exit 1
           :green/err (str "could not read the infrastructure state for the "
                           "delete cleanup: " error "\n"
                           "fix the backend credentials and retry; a delete that "
                           "cannot see its state has nothing to address"))
    (merge (ssh/with-machine-key opts)
           params
           (when (:ip opts) {:ip (:ip opts)})
           {:green/exit 0})))

(defn after-validate
  "The lifecycle transition table, once the validators have passed.

  build and dry-run only render: `with-machine-key` fills the placeholder key
  paths and nothing under `~/.ssh` or `~/.ssh/config` is read. A real create
  runs the keypair's create matrix against the one state read, then the
  provider preflight, before any template is rendered — an unowned key on disk
  or at the provider stops the run while stopping is still free — then the
  `~/.ssh/config` ownership and placement checks. A real delete fills the same
  template values (a destroy renders before it destroys) and adopts the
  instance address from the same read, fail-closed; it checks no key, because
  its cleanup runs after the destroy."
  [opts {:keys [event real?]} state]
  (cond
    (and real? (= :delete event))
    (adopt-state opts state)

    (and real? (= :create event))
    (let [opts (ssh/ensure-key! opts (fn [_] (:params state)))]
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
   ;; The state is read once, up front, on the same defaulted and overlaid
   ;; opts the validators see — the overlay is what carries the backend
   ;; credentials — and only for the two events that touch a provider. The
   ;; validator and the after-validate share the one read.
   (let [overlaid (green-cli/read-pars (merge defaults opts) env)
         context {:event (:green/event overlaid) :real? (lifecycle/real-run? overlaid)}
         state (when (lifecycle-event? context) (read-state overlaid))]
     (lifecycle/preflight
      opts {:defaults defaults :overlay green-cli/read-pars
            :validators
            [(fn [_ env _] (validate/env-errors env))
             (fn [opts _ _] (validate/state-errors opts))
             (fn [opts _ ctx]
               (when (lifecycle-event? ctx) (provider-validator opts state)))
             (fn [opts _ {:keys [event real?]}]
               (when (and real? (= :delete event) (:compute-prevent-destroy opts))
                 [(str "compute destruction is protected; set "
                       (green-cli/par-name :compute-prevent-destroy) "=false to delete")]))]
            :after-validate (fn [opts _ ctx] (after-validate opts ctx state))} env))))

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
