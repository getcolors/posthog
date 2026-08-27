(ns io.github.getcolors.posthog.workflow
  (:require [clojure.walk :as walk]
            [green.cli :as green-cli]
            [green.dry-run :as dry-run]
            [green.lifecycle :as lifecycle]
            [green.progress :as progress]
            [green.tofu :as tofu]
            [green.workflow :as wf]
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
          :after-validate
          (fn [opts _ {:keys [event real?]}]
            (if (and real? (= :delete event))
              (adopt-state opts)
              (assoc opts :green/exit 0)))} env)))

(defn wire-fn [step run-opts]
  (if (= :delete (:green/event run-opts))
    (case step
      :posthog/start [start-step :posthog/ansible]
      :posthog/ansible [tools/ansible-step :posthog/dns]
      :posthog/dns [tools/dns-step :posthog/infrastructure]
      :posthog/infrastructure [tools/infrastructure-step])
    (case step
      :posthog/start [start-step :posthog/infrastructure]
      :posthog/infrastructure [tools/infrastructure-step :posthog/dns]
      :posthog/dns [tools/dns-step :posthog/ansible]
      :posthog/ansible [tools/ansible-step :posthog/acceptance]
      :posthog/acceptance [tools/acceptance-step])))

(defn backend-advice [tool]
  (tofu/conventional-backend-advice
   {:dir-fn #(tools/tool-dir % tool)
    :key-fn #(str (:profile %) "/" tool ".tfstate")}))

(def side-effecting [:posthog/infrastructure :posthog/dns :posthog/ansible :posthog/acceptance])

(def workflow
  (-> (wf/workflow {:start :posthog/start :wire-fn wire-fn})
      (wf/advice-add :posthog/infrastructure :before ::backend
                     (backend-advice tools/infrastructure-tool))
      (wf/advice-add :posthog/dns :before ::backend (backend-advice tools/dns-tool))
      progress/advise
      (dry-run/advise side-effecting)))
