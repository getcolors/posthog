(ns io.github.getcolors.posthog.validate
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.once.ssh :as once-ssh]
            [io.github.getcolors.once.validate :as once-validate]))

(def profile-par (green-cli/par-name :profile))

(def required
  "Every key desired state must carry. Two DigitalOcean keys are deliberately
  absent: `digitalocean-ssh-keys`, because per the SSH Keypair Standard its
  *absence* selects keygen mode, and `digitalocean-name`, because per the
  Compute Name Standard the profile is the default and the key is only an
  override. Requiring either would make conforming deployments invalid."
  [:profile :workdir :provider-compute :provider-dns :provider-backend
   :compute-prevent-destroy :posthog-host :posthog-admin-email :posthog-image
   :posthog-postgres-image :posthog-clickhouse-image :posthog-redis-image
   :posthog-kafka-image :posthog-temporal-image :posthog-capture-image :posthog-plugin-server-image :caddy-image
   :posthog-postgres-data-dir :posthog-clickhouse-data-dir :posthog-redis-data-dir
   :posthog-kafka-data-dir
   :posthog-backup-dir :posthog-backup-r2-bucket :posthog-backup-r2-endpoint
   :posthog-backup-r2-region :posthog-backup-oncalendar :posthog-backup-retention-days
   :digitalocean-region :digitalocean-size :digitalocean-image
   :digitalocean-ssh-sources :digitalocean-http-sources
   :r2-bucket :r2-endpoint])

;; DigitalOcean droplet names: letters, digits, dots and hyphens, up to 63
;; characters, starting and ending alphanumeric.
(def digitalocean-name-re #"^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,61}[A-Za-z0-9])?$")

(def host-re #"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
(def image-re
  ;; name:tag, name@sha256:..., or name:tag@sha256:... A digest is the only
  ;; pin that cannot move under the deployment, so validation must accept it.
  #"^[^\s:@]+(?:/[^\s:@]+)*(?::[^\s:@]+|(?::[^\s:@]+)?@sha256:[0-9a-f]{64})$")

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(defn placeholder?
  "Absent, blank or REPLACE_ME all mean 'use the profile' (Compute Name
  Standard §2: presence is the only switch)."
  [v]
  (or (missing? v) (= "REPLACE_ME" (str/trim (str v)))))

(defn compute-name
  "What this deployment calls its machine. The one function that answers it —
  every label, including the firewall's, derives from this and never from the
  raw override key or a second copy of the profile (§3)."
  [opts]
  (let [override (:digitalocean-name opts)]
    (if (placeholder? override) (str (:profile opts)) (str/trim (str override)))))

(defn keygen?
  "Whether this deployment owns its machine keypair. Delegates to ONCE, the
  standard's reference implementation, so one rule decides it everywhere."
  [opts]
  (once-ssh/keygen? opts))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn state-errors [opts]
  (vec
   (concat
    (for [k required :when (missing? (get opts k))] (str k " is required"))
    (when-not (= "digitalocean" (:provider-compute opts))
      [":provider-compute must be digitalocean"])
    (when-not (= "cloudflare" (:provider-dns opts))
      [":provider-dns must be cloudflare"])
    (when-not (contains? #{"local" "s3" "r2"} (:provider-backend opts))
      [":provider-backend must be local, s3, or r2"])
    (when-not (boolean? (:compute-prevent-destroy opts))
      [":compute-prevent-destroy must be true or false"])
    (when-not (or (missing? (:posthog-host opts))
                  (re-matches host-re (str (:posthog-host opts))))
      [":posthog-host must be a fully qualified hostname"])
    (for [k [:posthog-image :posthog-postgres-image :posthog-clickhouse-image
             :posthog-redis-image :posthog-kafka-image :posthog-temporal-image :posthog-capture-image :posthog-plugin-server-image :caddy-image]
          :let [v (get opts k)]
          :when (and (not (missing? v)) (not (re-matches image-re (str v))))]
      (str k " must carry an explicit image tag"))
    (for [k [:posthog-backup-retention-days]
          :when (and (not (missing? (get opts k)))
                     (not (and (integer? (get opts k)) (pos? (get opts k)))))]
      (str k " must be a positive integer"))
    (when-not (or (placeholder? (:digitalocean-name opts))
                  (re-matches digitalocean-name-re (compute-name opts)))
      [":digitalocean-name must be a valid DigitalOcean droplet name"])
    (when (contains? opts :digitalocean-vpc-uuid)
      [":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime"])
    (when (contains? opts :digitalocean-vpc-cidr)
      [":digitalocean-vpc-cidr must be absent; this package must not create a VPC"]))))

(defn backend-secrets [opts]
  (:secrets (get-in once-validate/providers
                    [:provider-backend (:provider-backend opts)])))

(defn secret-errors [opts]
  (let [keys (concat [:do-token :cloudflare-api-token
                      ;; The compose template interpolates these at run time and
                      ;; carries no fallback; the Django signing key in
                      ;; particular must never be a value published here.
                      :posthog-secret-key :posthog-postgres-password
                      :posthog-oidc-rsa-private-key
                      :posthog-encryption-salt-keys
                      :posthog-admin-password
                      :posthog-backup-r2-access-key-id
                      :posthog-backup-r2-secret-access-key]
                     (backend-secrets opts))]
    (for [k (distinct keys) :when (missing? (get opts k))]
      (str "required credential is not set: " (green-cli/par-name k)))))

(defn tofu-env [opts slot]
  (case slot
    :provider-compute {:do-token "DIGITALOCEAN_TOKEN"}
    :provider-dns {:cloudflare-api-token "CLOUDFLARE_API_TOKEN"}
    :provider-backend (:tofu-env (get-in once-validate/providers
                                         [:provider-backend (:provider-backend opts)]) {})
    {}))
