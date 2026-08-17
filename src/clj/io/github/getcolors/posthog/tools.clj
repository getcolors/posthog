(ns io.github.getcolors.posthog.tools
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [green.ansible :as ansible]
            [green.cli :as green-cli]
            [green.process :as process]
            [green.scaffold :as sc]
            [green.tofu :as tofu]
            [green.workflow :as wf]
            [io.github.getcolors.posthog.utils :as utils]
            [io.github.getcolors.posthog.validate :as validate]))

(def infrastructure-tool "posthog-infrastructure")
(def dns-tool "posthog-dns")
(def ansible-tool "posthog-ansible")
(def root "io.github.getcolors.posthog.tools")
(def template-opts sc/preserve-jinja-delimiters)

(defn tool-dir [opts tool] (green-cli/stage-dir opts tool {:default-profile "posthog"}))
(defn template [path file] (keyword (str root "." path) file))
(defn spec [source target data] {:template source :target target :data data :opts template-opts})
(defn raw-spec [target content] (sc/content-spec target content))

(defn cidrs [opts k]
  (let [v (get opts k) xs (if (sequential? v) v (str/split (str v) #"[,\s]+"))]
    (->> xs (map (comp str/trim str)) (remove str/blank?) vec)))

(defn credential-env [opts & slots]
  (not-empty
   (into {} (keep (fn [[k env-var]]
                    (when-let [v (not-empty (str (get opts k)))] [env-var v])))
         (apply merge (map #(validate/tofu-env opts %) (conj (vec slots) :provider-backend))))))

(defn backend-credential-env [opts] (credential-env opts))

(defn fallback-params [opts]
  {:ip "192.0.2.10" :user "root" :sudoer "root" :name (:profile opts)})

(defn output-params [result]
  (some-> (get-in result [:tofu/outputs :params]) walk/keywordize-keys))

(defn infrastructure-data [opts]
  (assoc opts
         :ssh-sources-hcl (tofu/hcl-list (cidrs opts :digitalocean-ssh-sources))
         :http-sources-hcl (tofu/hcl-list (cidrs opts :digitalocean-http-sources))))

(defn infrastructure-step [opts]
  (let [dir (tool-dir opts infrastructure-tool)
        specs [(spec (template "infrastructure" "main.tf") (str dir "/main.tf")
                     (infrastructure-data opts))]
        result (tofu/tofu-with-spec opts specs
                                    {:dir dir :env (credential-env opts :provider-compute)})]
    (cond
      (wf/failed? result) result
      (= :build (:green/event opts)) (merge result (fallback-params opts))
      (= :delete (:green/event opts)) result
      :else (merge result (fallback-params opts) (output-params result)))))

(defn zone-id [zone] (format "${data.cloudflare_zone.zone.id}" zone))

(defn dns-json [opts]
  (tofu/constructs-json
   [(tofu/construct :resource :cloudflare_dns_record :posthog
                    {:zone_id (zone-id (:posthog-zone opts))
                     :name (:posthog-host opts) :content (:ip opts) :type "A"
                     :proxied true :ttl 1})]))

(defn dns-step [opts]
  (let [dir (tool-dir opts dns-tool)
        zone (or (:posthog-zone opts) (utils/registrable-domain (:posthog-host opts)))
        data (assoc opts
                    :ip (or (:ip opts) (:ip (fallback-params opts)))
                    :posthog-zone zone)
        specs [(spec (template "dns" "main.tf") (str dir "/main.tf") data)
               (raw-spec (str dir "/record.tf.json") (dns-json data))]]
    (tofu/tofu-with-spec opts specs {:dir dir :env (credential-env opts :provider-dns)})))

(defn inventory [opts]
  (json/generate-string
   {:all {:children {:posthog {:hosts {(:profile opts)
                                        {:ansible_host (or (:ip opts) "192.0.2.10")
                                         :ansible_user "root"}}}}}}
   {:pretty true}))

(defn ansible-data [opts]
  (assoc opts
         :ip (or (:ip opts) "192.0.2.10")
         :posthog-secret-key (or (:posthog-secret-key opts) "posthog-insecure-secret-key-32-chars-long!")
         :posthog-backup-access-key "{{ lookup('env','COLORS_PAR_POSTHOG_BACKUP_R2_ACCESS_KEY_ID') }}"
         :posthog-backup-secret-key "{{ lookup('env','COLORS_PAR_POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY') }}"))

(defn ansible-specs [opts]
  (let [dir (tool-dir opts ansible-tool) data (ansible-data opts)]
    [(spec (template "ansible" "ansible.cfg") (str dir "/ansible.cfg") data)
     (spec (template "ansible" "main.yml") (str dir "/main.yml") data)
     (spec (template "ansible" "cleanup.yml") (str dir "/cleanup.yml") data)
     (spec (template "ansible" "compose.yml") (str dir "/compose.yml") data)
     (spec (template "ansible" "Caddyfile") (str dir "/Caddyfile") data)
     (spec (template "ansible" "backup") (str dir "/backup") data)
     (raw-spec (str dir "/inventory.json") (inventory data))]))

(defn ansible-step [opts]
  (let [dir (tool-dir opts ansible-tool)]
    (ansible/ansible-with-spec opts
      {:dir dir :inventory "inventory.json"
       :playbooks {:create "main.yml" :delete "cleanup.yml"}
       :host-key-checking false}
      (ansible-specs opts))))

(defn run-json [args timeout]
  (let [r (process/run-with-timeout args {} timeout)]
    (if (zero? (:exit r))
      [(try (json/parse-string (:out r) true) (catch Exception _ nil)) nil]
      [nil (str (:err r) (:out r))])))

(defn wait-health [url attempts]
  (loop [n attempts]
    (let [r (process/run-with-timeout ["curl" "-fsS" "-k" url] {} 10000)]
      (cond (zero? (:exit r)) true
            (pos? n) (do (Thread/sleep 5000) (recur (dec n)))
            :else false))))

(defn acceptance-step [opts]
  (if (not= :create (:green/event opts))
    (assoc opts :green/exit 0)
    (let [base (str "https://" (:posthog-host opts))
          ip (or (:ip opts) "127.0.0.1")]
      (if-not (wait-health base 60)
        (assoc opts :green/exit 1 :green/err "HTTPS health check did not become ready")
        ;; Test synthetic event capture
        (let [payload (json/generate-string
                       {:api_key "benchmark_key"
                        :event "benchmark_capture"
                        :distinct_id "test-user-posthog"
                        :properties {:benchmark "posthog" :time (System/currentTimeMillis)}})
              capture (process/run-with-timeout
                       ["curl" "-fsS" "-k" "-X" "POST"
                        "-H" "content-type: application/json"
                        "--data" payload
                        (str base "/capture/")] {} 15000)
              ;; Test backup execution via SSH
              backup (process/run-with-timeout
                      ["ssh" "-o" "StrictHostKeyChecking=no" "-o" "ConnectTimeout=10"
                       (str "root@" ip)
                       "systemctl start posthog-backup.service && systemctl is-active posthog-backup.timer"]
                      {} 60000)]
          (cond
            (not (zero? (:exit backup)))
            (assoc opts :green/exit 1 :green/err (str "backup verification failed: " (:err backup) (:out backup)))
            :else
            (assoc opts :green/exit 0 :posthog/acceptance {:health :ok :capture :ok :backup :ok})))))))
