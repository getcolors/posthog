(ns io.github.getcolors.posthog.validate
  (:require [clojure.string :as str]
            [green.cli :as green-cli]
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

;; Provider naming rules for the compute name override. DigitalOcean droplet
;; names are hostname-like: lowercase letters, digits, dots and hyphens, up to
;; 63 characters, starting and ending alphanumeric. Vultr labels are only a
;; console string: letters of either case, digits, dot, underscore and hyphen,
;; up to 63 characters.
(def compute-name-res
  {"digitalocean" #"^[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?$"
   "vultr" #"^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$"})

(defn missing? [x] (or (nil? x) (and (string? x) (str/blank? x))))

(defn placeholder?
  "Absent, blank or REPLACE_ME all mean 'use the profile' (Compute Name
  Standard §2: presence is the only switch)."
  [v]
  (or (missing? v) (= "REPLACE_ME" (str/trim (str v)))))

(defn compute-provider [opts] (get compute-providers (:provider-compute opts)))

(defn compute-key
  "The selected provider's key for `suffix`: `:digitalocean-ssh-sources`,
  `:vultr-name`, and so on. Provider keys stay provider-scoped so an existing
  colors.yml keeps meaning what it meant."
  [opts suffix]
  (keyword (str (:provider-compute opts) "-" suffix)))

(defn compute-name
  "What this deployment calls its machine. The one function that answers it —
  every label, including the firewall's, derives from this and never from the
  raw override key or a second copy of the profile (§3)."
  [opts]
  (let [override (get opts (compute-key opts "name"))]
    (if (placeholder? override) (str (:profile opts)) (str/trim (str override)))))

(defn keygen?
  "Whether this deployment owns its machine keypair. Delegates to ONCE, the
  standard's reference implementation, so one rule decides it everywhere."
  [opts]
  (once-ssh/keygen? opts))

(defn env-errors [env]
  (when (not-empty (str (get env profile-par)))
    [(str profile-par " is set; profile must come from colors.yml only")]))

;; --- CIDR syntax (Compute Provider Standard §5) ------------------------------
;;
;; Hand-rolled rather than delegated to a runtime library so the three colours
;; accept exactly the same set of strings: an address that one colour's parser
;; tolerates and another's rejects would be a parity bug at the firewall.

(defn- ipv4-address? [s]
  (let [parts (str/split (str s) #"\." -1)]
    (and (= 4 (count parts))
         (every? #(and (re-matches #"\d{1,3}" %) (<= (parse-long %) 255)) parts))))

(defn- ipv6-address? [s]
  (let [s (str s)
        halves (str/split s #"::" -1)
        groups (fn [half] (if (str/blank? half) [] (str/split half #":" -1)))
        hex-group? #(re-matches #"[0-9A-Fa-f]{1,4}" %)
        ;; An embedded dotted quad may close the address: ::ffff:192.0.2.10.
        embedded? (fn [gs] (and (seq gs) (ipv4-address? (last gs))))
        count-groups (fn [gs] (if (embedded? gs) (+ 2 (dec (count gs))) (count gs)))
        well-formed? (fn [gs] (every? hex-group? (if (embedded? gs) (butlast gs) gs)))]
    (case (count halves)
      1 (let [gs (groups s)]
          (and (well-formed? gs) (= 8 (count-groups gs))))
      2 (let [[a b] (map groups halves)]
          (and (well-formed? a) (well-formed? b)
               (not (embedded? a))
               (< (+ (count-groups a) (count-groups b)) 8)))
      false)))

(defn cidr?
  "Whether `s` is a syntactically valid IPv4 or IPv6 CIDR: an address, a
  slash, and a prefix length within the family's range."
  [s]
  (let [parts (str/split (str s) #"/" -1)]
    (and (= 2 (count parts))
         (let [[addr prefix] parts]
           (and (re-matches #"\d{1,3}" prefix)
                (let [n (parse-long prefix)]
                  (or (and (ipv4-address? addr) (<= n 32))
                      (and (ipv6-address? addr) (<= n 128)))))))))

(defn cidr-list
  "The entries of a source list, whether desired state supplied a YAML list or
  an overlay string."
  [v]
  (let [xs (if (sequential? v) v (str/split (str v) #"[,\s]+"))]
    (->> xs (map (comp str/trim str)) (remove str/blank?) vec)))

(defn- source-errors
  "The network contract: `<provider>-ssh-sources` must reach someone, and every
  entry of both lists must be a CIDR — before any provider call. An empty
  `<provider>-http-sources` is allowed and means no public HTTP."
  [opts]
  (when (compute-provider opts)
    (let [ssh-k (compute-key opts "ssh-sources")
          http-k (compute-key opts "http-sources")]
      (concat
       (when (and (not (missing? (get opts ssh-k))) (empty? (cidr-list (get opts ssh-k))))
         [(str ssh-k " must list at least one CIDR; an empty list is a machine no one can reach")])
       (for [k [ssh-k http-k]
             :when (not (missing? (get opts k)))
             entry (cidr-list (get opts k))
             :when (not (cidr? entry))]
         (str k " entry is not an IPv4 or IPv6 CIDR: " entry))))))

(defn- provider-errors
  "Checks that only make sense for the selected provider. Keys of an
  unselected provider are never read."
  [opts]
  (case (:provider-compute opts)
    "digitalocean"
    (concat
     (when (contains? opts :digitalocean-vpc-uuid)
       [":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime"])
     (when (contains? opts :digitalocean-vpc-cidr)
       [":digitalocean-vpc-cidr must be absent; this package must not create a VPC"]))
    "vultr"
    (when-not (or (missing? (:vultr-os-id opts)) (integer? (:vultr-os-id opts)))
      [":vultr-os-id must be Vultr's numeric operating-system id"])
    nil))

(defn state-errors [opts]
  (vec
   (concat
    (for [k (concat required (:required (compute-provider opts)))
          :when (missing? (get opts k))]
      (str k " is required"))
    (when-not (compute-provider opts)
      [(str ":provider-compute must be one of "
            (str/join ", " (sort (keys compute-providers))))])
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
    (when-let [name-re (get compute-name-res (:provider-compute opts))]
      (when-not (or (placeholder? (get opts (compute-key opts "name")))
                    (re-matches name-re (compute-name opts)))
        [(str (compute-key opts "name") " must be a valid " (:provider-compute opts) " machine name")]))
    (source-errors opts)
    (provider-errors opts))))

(defn provider-state-errors
  "Provider switching is a rebuild, never an apply (Compute Provider Standard
  §4). All providers share one state key, so a changed provider-compute on a
  profile with compute in state would plan a cross-provider replacement.
  `recorded` is the compute stage's applied `params` (nil when no state is
  readable): a recorded provider that differs from the selected one refuses,
  and params without a provider — a deployment created before adoption — are
  accepted only for the package default. Pure, so the read stays with the
  lifecycle and the rule is testable without a backend."
  [opts recorded]
  (when recorded
    (let [selected (:provider-compute opts)
          held (or (some-> (:provider recorded) str not-empty) default-compute-provider)]
      (when-not (= held selected)
        [(str "state holds a " held " machine; set provider-compute back to "
              held " and delete first")]))))

(defn backend-secrets [opts]
  (:secrets (get-in once-validate/providers
                    [:provider-backend (:provider-backend opts)])))

(defn secret-errors [opts]
  (let [keys (concat (:secrets (compute-provider opts))
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
    :provider-compute (:tofu-env (compute-provider opts) {})
    :provider-dns {:cloudflare-api-token "CLOUDFLARE_API_TOKEN"}
    :provider-backend (:tofu-env (get-in once-validate/providers
                                         [:provider-backend (:provider-backend opts)]) {})
    {}))
