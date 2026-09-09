(ns io.github.getcolors.posthog.workflow
  (:require [clojure.walk :as walk]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.posthog.compute :as compute]
            [io.github.getcolors.posthog.ssh :as ssh]
            [io.github.getcolors.posthog.ssh-config :as ssh-config]
            [io.github.getcolors.posthog.tools :as tools]
            [io.github.getcolors.posthog.validate :as validate]))

(def defaults {:provider-compute validate/default-compute-provider
               :provider-dns "cloudflare"
               :provider-backend "r2" :compute-prevent-destroy true
               :workdir ".colors"})

(defn start-step
 ([opts] (start-step opts (System/getenv)))
 ([opts env]
  (lifecycle/preflight opts {:env env :defaults defaults :overlay green-cli/read-pars
    :validators [(fn [_ env _] (validate/env-errors env)) (fn [o _ _] (validate/state-errors o))
                 (fn [o _ c] (when (and (:real? c) (contains? #{:create :delete} (:event c))) (validate/secret-errors o)))
                 (fn [o _ c] (when (and (:real? c) (= :delete (:event c)) (:compute-prevent-destroy o)) ["compute destruction is protected; set COLORS_PAR_COMPUTE_PREVENT_DESTROY=false to delete"]))]
    :after-validate (fn [o env c] (cond (and (:real? c) (= :delete (:event c))) (let [result (compute/load-step o env)] (if (and (not (wf/failed? result)) (:ip o) (not (:posthog/already-destroyed result))) (assoc result :ip (:ip o)) result))
                                      (and (:real? c) (= :create (:event c))) (ssh-config/preflight! o)
                                      :else (assoc (ssh/with-machine-key o) :green/exit 0)))})))

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
      :posthog/infrastructure [tools/infrastructure-step]
      nil)
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
   :posthog/ansible :posthog/acceptance])

(def workflow
  (-> (wf/workflow {:start :posthog/start :wire-fn wire-fn :next-fn (fn [_ successors opts] (if (or (:posthog/already-destroyed opts) (wf/failed? opts)) [] (mapv #(vector % opts) successors)))})
      (wf/advice-add :posthog/dns :before ::backend (backend-advice tools/dns-tool))
      progress/advise
      (dry-run/advise side-effecting)))
