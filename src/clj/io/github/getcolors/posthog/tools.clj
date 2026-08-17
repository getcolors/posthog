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
         :posthog-web-port (or (:posthog-web-port opts) 8000)
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
     (spec (template "ansible" "checkpoint.sql") (str dir "/checkpoint.sql") data)
     (spec (template "ansible" "owner.py") (str dir "/owner.py") data)
     (raw-spec (str dir "/inventory.json") (inventory data))]))

(defn ansible-step [opts]
  (let [dir (tool-dir opts ansible-tool)]
    (ansible/ansible-with-spec opts
      {:dir dir :inventory "inventory.json"
       :playbooks {:create "main.yml" :delete "cleanup.yml"}
       :host-key-checking false}
      (ansible-specs opts))))

;; --- Acceptance --------------------------------------------------------------
;;
;; Every claim this step reports must be one it checked. TLS is verified (the
;; previous check passed `curl -k`, so a broken certificate would have gone
;; unnoticed), a captured event is read back out of ClickHouse rather than
;; inferred from a status code, and the backup drill is confirmed by a fresh
;; object in R2 rather than by systemd reporting that it started something.

(defn http-status [args]
  (let [r (process/run-with-timeout
           (into ["curl" "-sS" "-o" "/dev/null" "-w" "%{http_code}"] args) {} 20000)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn ssh-out [ip command timeout]
  (let [r (process/run-with-timeout
           ["ssh" "-o" "StrictHostKeyChecking=no" "-o" "ConnectTimeout=10"
            (str "root@" ip) command] {} timeout)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn psql [ip query]
  (not-empty
   (str (ssh-out ip (str "cd /opt/posthog && docker compose exec -T db psql -U posthog"
                         " -d posthog -tAc '" query "'")
                 30000))))

(defn clickhouse
  "Resolve the events table from system.tables so the check does not hardcode a
   database name PostHog's migrations own, then run `query` against it."
  [ip query]
  (not-empty
   (str (ssh-out ip (str "cd /opt/posthog && "
                         "t=$(docker compose exec -T clickhouse clickhouse-client"
                         " --query \"SELECT database || '.' || name FROM system.tables"
                         " WHERE name = 'events' AND database NOT IN ('system')"
                         " ORDER BY database LIMIT 1\" | tr -d '\\r'); "
                         "[ -n \"$t\" ] && docker compose exec -T clickhouse clickhouse-client"
                         " --query \"" query "\"")
                 30000))))

(defn event-count [ip]
  (some-> (clickhouse ip "SELECT count() FROM $t") parse-long))

(defn project-api-key [ip]
  (psql ip "select api_token from posthog_team order by id limit 1"))

(defn wait-health [url attempts]
  (loop [n attempts]
    (let [r (process/run-with-timeout ["curl" "-fsS" (str url "/_health/")] {} 10000)]
      (cond (zero? (:exit r)) true
            (pos? n) (do (Thread/sleep 5000) (recur (dec n)))
            :else false))))

(defn send-event [base api-key]
  (http-status ["-X" "POST" "-H" "content-type: application/json"
                "--data" (json/generate-string
                          {:api_key api-key
                           :event "colors_acceptance"
                           :distinct_id "colors-acceptance"
                           :properties {:source "colors"}})
                (str base "/capture/")]))

(defn ingestion-verdict [status before after]
  (cond (nil? status) :unreachable
        (and (integer? before) (integer? after) (> after before)) :ingested
        (re-matches #"2\d\d" (str status)) :dropped
        :else :rejected))

(defn wait-ingested
  "Capture is asynchronous through the Celery worker, so poll rather than
   sampling once."
  [ip baseline attempts]
  (loop [n attempts]
    (let [after (event-count ip)]
      (cond (and (integer? after) (> after baseline)) after
            (pos? n) (do (Thread/sleep 5000) (recur (dec n)))
            :else after))))

(def rclone-env
  (str "RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare "
       "RCLONE_CONFIG_R2_REGION=auto RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true"))

(defn backup-listing [opts ip]
  (some-> (ssh-out ip (str "set -a; . /etc/posthog-backup.env; set +a; " rclone-env
                           " RCLONE_CONFIG_R2_ACCESS_KEY_ID=\"$POSTHOG_BACKUP_R2_ACCESS_KEY_ID\""
                           " RCLONE_CONFIG_R2_SECRET_ACCESS_KEY=\"$POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY\""
                           " RCLONE_CONFIG_R2_ENDPOINT=\"" (:posthog-backup-r2-endpoint opts) "\""
                           " rclone lsjson --files-only r2:" (:posthog-backup-r2-bucket opts)
                           "/" (:profile opts))
                   120000)
          not-empty
          (json/parse-string true)))

(defn parse-instant [s]
  (try (.toInstant (java.time.OffsetDateTime/parse (str s))) (catch Exception _ nil)))

(defn fresh-backup? [entries since]
  (boolean (some (fn [{:keys [Size ModTime]}]
                   (and (pos? (or Size 0))
                        (when-let [t (parse-instant ModTime)]
                          (not (.isBefore t since)))))
                 entries)))

(defn run-backup [ip]
  (ssh-out ip "systemctl start posthog-backup.service && systemctl is-active posthog-backup.timer"
           600000))

(defn background-jobs
  "PostHog's own answers, not ours: whether Celery is alive, and whether any
   async migration is still pending. A pending one stops the worker starting at
   all, and the ingestion path this step already exercises never touches Celery
   -- so background jobs can be entirely dead while capture works."
  [ip]
  (ssh-out ip (str "cd /opt/posthog && docker compose exec -T web python manage.py shell -c "
                   "\"from posthog.utils import is_celery_alive; "
                   "from posthog.models.async_migration import AsyncMigration; "
                   "print('celery=%s pending=%d' % (is_celery_alive(), "
                   "AsyncMigration.objects.exclude(status=2).count()))\"")
           120000))

(defn background-verdict [out]
  (let [s (str out)]
    (cond
      (str/blank? s) :unreachable
      (not (re-find #"celery=True" s)) :celery-down
      (not (re-find #"pending=0\b" s)) :migrations-pending
      :else :ok)))

(defn acceptance-step [opts]
  (if (not= :create (:green/event opts))
    (assoc opts :green/exit 0)
    (let [base (str "https://" (:posthog-host opts))
          ip (:ip opts)
          since (.minusSeconds (java.time.Instant/now) 120)]
      (if-not (wait-health base 60)
        (assoc opts :green/exit 1
               :green/err "HTTPS health did not become ready with a valid certificate")
        (let [api-key (project-api-key ip)
              before (event-count ip)]
          (if-not (integer? before)
            (assoc opts :green/exit 1
                   :green/err "could not read the ClickHouse events table to verify capture")
            (let [verdict (if-not api-key
                            :not-configured
                            (let [status (send-event base api-key)
                                  after (wait-ingested ip before 12)]
                              (ingestion-verdict status before after)))
                  background (background-verdict (background-jobs ip))]
              (cond
                (contains? #{:dropped :rejected :unreachable} verdict)
                (assoc opts :green/exit 1
                       :green/err (str "synthetic event was not captured: " (name verdict)))

                (not= :ok background)
                (assoc opts :green/exit 1
                       :green/err (str "background jobs are not healthy: " (name background)))

                (nil? (run-backup ip))
                (assoc opts :green/exit 1 :green/err "backup unit or timer is not healthy")

                (not (fresh-backup? (backup-listing opts ip) since))
                (assoc opts :green/exit 1
                       :green/err (str "no backup object newer than this run under r2:"
                                       (:posthog-backup-r2-bucket opts) "/" (:profile opts)))

                :else
                (assoc opts :green/exit 0
                       :posthog/acceptance {:health :ok :event verdict
                                            :background :ok
                                            :backup :verified-in-r2})))))))))
