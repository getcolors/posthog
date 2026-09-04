(ns io.github.getcolors.posthog.validate
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
            [io.github.getcolors.once.compute :as compute]
            [io.github.getcolors.once.ssh :as once-ssh]
            [io.github.getcolors.once.validate :as once-validate]))

(def profile-par (green-cli/par-name :profile))

(def compute-providers
  "provider-compute -> what that choice implies (Compute Provider Standard §2).

  `:required` are the non-secret keys that provider's template interpolates,
  `:secrets` the credentials it needs through COLORS_PAR_*, and `:tofu-env`
  the subset OpenTofu reads from the process environment itself. Keeping the
  three together is what stops a provider being validated against one set of
  keys and run with another — a stage exporting a credential nobody checked
  for, or a check demanding a key no template uses. The keys of this map are
  the advertised providers; a provider without a template directory and a
  golden is not advertised.

  Two keys are deliberately absent from every entry: `<provider>-ssh-keys`,
  because per the SSH Keypair Standard its *absence* selects keygen mode, and
  `<provider>-name`, because per the Compute Name Standard the profile is the
  default and the key is only an override. Requiring either would make
  conforming deployments invalid. Keys of an unselected provider are accepted
  and ignored, so one colors.yml stays portable."
  {"digitalocean"
   {:required [:digitalocean-region :digitalocean-size :digitalocean-image
               :digitalocean-ssh-sources :digitalocean-http-sources]
    :secrets [:do-token]
    :tofu-env {:do-token "DIGITALOCEAN_TOKEN"}}
   "vultr"
   {:required [:vultr-region :vultr-plan :vultr-os-id
               :vultr-ssh-sources :vultr-http-sources]
    :secrets [:vultr-api-key]
    :tofu-env {:vultr-api-key "VULTR_API_KEY"}}})

(def default-compute-provider
  "The provider a deployment created before `params.provider` was recorded is
  assumed to run: every such deployment was created on DigitalOcean, the only
  provider this package had."
  "digitalocean")

(def spec
  "How this package describes itself to ONCE's `compute`, the Compute Provider
  Standard's operations over a package-owned registry. The registry and the
  default are the data above; `:sources` names the firewall lists the
  templates read — SSH must list at least one CIDR, an empty HTTP list means
  no public HTTP. The name rules are ONCE's."
  {:registry compute-providers
   :default default-compute-provider
   :sources {:non-empty ["ssh-sources"] :may-be-empty ["http-sources"]}})

(def required
  [:profile :workdir :provider-compute :provider-dns :provider-backend
   :compute-prevent-destroy :posthog-host :posthog-admin-email :posthog-image
   :posthog-postgres-image :posthog-clickhouse-image :posthog-redis-image
   :posthog-kafka-image :posthog-temporal-image :posthog-capture-image :posthog-plugin-server-image :caddy-image
   :posthog-postgres-data-dir :posthog-clickhouse-data-dir :posthog-redis-data-dir
   :posthog-kafka-data-dir
   :posthog-backup-dir :posthog-backup-r2-bucket :posthog-backup-r2-endpoint
   :posthog-backup-r2-region :posthog-backup-oncalendar :posthog-backup-retention-days
   :r2-bucket :r2-endpoint])

(def host-re #"^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$")
(def image-re
  ;; name:tag, name@sha256:..., or name:tag@sha256:... A digest is the only
  ;; pin that cannot move under the deployment, so validation must accept it.
  #"^[^\s:@]+(?:/[^\s:@]+)*(?::[^\s:@]+|(?::[^\s:@]+)?@sha256:[0-9a-f]{64})$")

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(def compute-key
  "The selected provider's key for `suffix`: `:digitalocean-ssh-sources`,
  `:vultr-name`, and so on. Provider keys stay provider-scoped so an existing
  colors.yml keeps meaning what it meant. ONCE's; named here so `tools` reads
  the same."
  compute/key)

(def compute-name
  "What this deployment calls its machine: `<provider>-name` when present,
  else the profile (Compute Name Standard). ONCE's; every label, including the
  firewall's, derives from this one answer and never from the raw override
  key or a second copy of the profile (§3)."
  compute/name)

(defn keygen?
  "Whether this deployment owns its machine keypair. Delegates to ONCE, the
  standard's reference implementation, so one rule decides it everywhere."
  [opts]
  (once-ssh/keygen? opts))

(def cidrs
  "A source list as desired state or an overlay string carries it. ONCE's, so
  the validator and the templates can never disagree about what an entry is."
  compute/cidrs)

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

(defn state-errors
  "Every problem with desired state at once: the missing keys (this package's
  and the selected provider's), the package's own checks, then the Compute
  Provider Standard's — selection, the network contract and the provider
  rules — which are ONCE's over `spec`."
  [opts]
  (vec
   (concat
    (for [k (concat required (compute/required-keys spec opts))
          :when (missing? (get opts k))]
      (str k " is required"))
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
    (compute/state-errors spec opts))))

(defn backend-secrets [opts]
  (:secrets (get-in once-validate/providers
                    [:provider-backend (:provider-backend opts)])))

(defn secret-errors [opts]
  (let [keys (concat (compute/secrets spec opts)
                     [:cloudflare-api-token
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
    :provider-compute (compute/tofu-env spec opts)
    :provider-dns {:cloudflare-api-token "CLOUDFLARE_API_TOKEN"}
    :provider-backend (:tofu-env (get-in once-validate/providers
                                         [:provider-backend (:provider-backend opts)]) {})
    {}))
