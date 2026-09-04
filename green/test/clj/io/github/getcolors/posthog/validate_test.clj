(ns io.github.getcolors.posthog.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.cli :as green-cli]
            [io.github.getcolors.posthog.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")
(def optout-file "test/fixtures/optout.yml")
(def vultr-file "test/fixtures/colors-vultr.yml")
(def vultr-optout-file "test/fixtures/optout-vultr.yml")

(defn- read-fixture [path overrides]
  (merge (green-cli/read-state path (str/replace (slurp path) "WORKDIR" ".colors"))
         overrides))
(defn fixture [& {:as overrides}] (read-fixture fixture-file overrides))
(defn optout [& {:as overrides}] (read-fixture optout-file overrides))
(defn vultr [& {:as overrides}] (read-fixture vultr-file overrides))
(defn vultr-optout [& {:as overrides}] (read-fixture vultr-optout-file overrides))

(deftest fixture-is-valid
  (is (= [] (validate/state-errors (fixture)))))

(deftest optout-fixture-is-valid
  (is (= [] (validate/state-errors (optout)))))

(deftest vultr-fixtures-are-valid
  (is (= [] (validate/state-errors (vultr))))
  (is (= [] (validate/state-errors (vultr-optout)))))

;; --- the registry (Compute Provider Standard §2) ----------------------------

(deftest advertised-providers
  (is (= ["digitalocean" "vultr"] (sort (keys validate/compute-providers))))
  (is (= "digitalocean" validate/default-compute-provider)))

(deftest the-spec-carries-this-packages-registry-sources-and-default
  ;; The operations are ONCE's; this is the data they run over. A colour
  ;; whose registry, sources or default drifts fails here, in that colour.
  (is (= #{"digitalocean" "vultr"} (set (keys (:registry validate/spec)))))
  (is (= validate/compute-providers (:registry validate/spec)))
  (is (= {:required [:digitalocean-region :digitalocean-size :digitalocean-image
                     :digitalocean-ssh-sources :digitalocean-http-sources]
          :secrets [:do-token]
          :tofu-env {:do-token "DIGITALOCEAN_TOKEN"}}
         (get-in validate/spec [:registry "digitalocean"])))
  (is (= {:required [:vultr-region :vultr-plan :vultr-os-id
                     :vultr-ssh-sources :vultr-http-sources]
          :secrets [:vultr-api-key]
          :tofu-env {:vultr-api-key "VULTR_API_KEY"}}
         (get-in validate/spec [:registry "vultr"])))
  (is (= {:non-empty ["ssh-sources"] :may-be-empty ["http-sources"]} (:sources validate/spec)))
  (is (= "digitalocean" (:default validate/spec)))
  (is (= validate/default-compute-provider (:default validate/spec)))
  (is (not (contains? validate/spec :name-rules)) "the name rules are ONCE's"))

(deftest unsupported-provider-is-named-with-the-alternatives
  (is (some #{":provider-compute must be one of digitalocean, vultr"}
            (validate/state-errors (fixture :provider-compute "hcloud")))))

(deftest each-provider-requires-its-own-keys-and-ignores-the-others
  (let [errors (validate/state-errors (fixture :provider-compute "vultr"))]
    (doseq [k [":vultr-region" ":vultr-plan" ":vultr-os-id" ":vultr-ssh-sources" ":vultr-http-sources"]]
      (is (some #(= (str k " is required") %) errors) k))
    (is (not-any? #(str/includes? % "digitalocean") errors)
        "keys of the unselected provider are neither required nor read"))
  (let [errors (validate/state-errors (vultr :provider-compute "digitalocean"))]
    (doseq [k [":digitalocean-region" ":digitalocean-size" ":digitalocean-image"
               ":digitalocean-ssh-sources" ":digitalocean-http-sources"]]
      (is (some #(= (str k " is required") %) errors) k))
    (is (not-any? #(str/includes? % "vultr") errors)))
  ;; Unselected-provider keys are accepted so one colors.yml stays portable.
  (is (= [] (validate/state-errors (fixture :vultr-region "ams" :vultr-ssh-keys "x")))))

(deftest secrets-and-tofu-env-follow-the-selected-provider
  (let [do-errors (str/join "\n" (validate/secret-errors (fixture)))
        vultr-errors (str/join "\n" (validate/secret-errors (vultr)))]
    (is (str/includes? do-errors "COLORS_PAR_DO_TOKEN"))
    (is (not (str/includes? do-errors "VULTR_API_KEY")))
    (is (str/includes? vultr-errors "COLORS_PAR_VULTR_API_KEY"))
    (is (not (str/includes? vultr-errors "DO_TOKEN"))))
  (is (= {:do-token "DIGITALOCEAN_TOKEN"} (validate/tofu-env (fixture) :provider-compute)))
  (is (= {:vultr-api-key "VULTR_API_KEY"} (validate/tofu-env (vultr) :provider-compute)))
  (is (= {} (validate/tofu-env (fixture :provider-compute "hcloud") :provider-compute))))

(deftest compute-key-is-provider-scoped
  (is (= :digitalocean-ssh-sources (validate/compute-key (fixture) "ssh-sources")))
  (is (= :vultr-name (validate/compute-key (vultr) "name"))))

(deftest keygen-follows-the-selected-provider-key
  (is (true? (validate/keygen? (vultr))))
  (is (false? (validate/keygen? (vultr-optout))))
  ;; A DigitalOcean key id in a Vultr deployment is an unselected key: ignored.
  (is (true? (validate/keygen? (vultr :digitalocean-ssh-keys "58495393")))))

;; --- the network contract (§5) -----------------------------------------------

(deftest ssh-sources-must-reach-someone
  (doseq [f [fixture vultr]]
    (let [opts (f)
          k (validate/compute-key opts "ssh-sources")
          errors (validate/state-errors (assoc opts k []))]
      (is (some #{(str k " must list at least one CIDR")} errors) (str k)))))

(deftest malformed-sources-are-refused-in-either-list
  (doseq [f [fixture vultr]]
    (let [opts (f)
          ssh-k (validate/compute-key opts "ssh-sources")
          http-k (validate/compute-key opts "http-sources")]
      (is (some #{(str ssh-k " entry \"10.0.0.0\" is not an IPv4 or IPv6 CIDR")}
                (validate/state-errors (assoc opts ssh-k ["10.0.0.0"]))))
      (is (some #{(str http-k " entry \"nope\" is not an IPv4 or IPv6 CIDR")}
                (validate/state-errors (assoc opts http-k ["0.0.0.0/0" "nope"]))))
      ;; An empty http list means no public HTTP and is allowed.
      (is (= [] (validate/state-errors (assoc opts http-k []))))
      ;; Overlay strings are split the way the template reads them.
      (is (= [] (validate/state-errors (assoc opts ssh-k "10.0.0.0/8, 192.0.2.0/24")))))))

(deftest machine-key-is-not-required
  ;; The standard makes absence meaningful: requiring digitalocean-ssh-keys
  ;; would make every conforming keygen deployment invalid.
  (is (not-any? #(str/includes? % "digitalocean-ssh-keys")
                (validate/state-errors (fixture)))))

(deftest absent-machine-key-selects-keygen
  (is (true? (validate/keygen? (fixture))))
  (is (false? (validate/keygen? (optout)))))

;; Compute Name Standard: the profile is the default, the name key an override.

(deftest compute-name-defaults-to-the-profile
  (is (= "posthog-fixture" (validate/compute-name (fixture))))
  (is (= "posthog-fixture" (validate/compute-name (fixture :digitalocean-name ""))))
  (is (= "posthog-fixture" (validate/compute-name (fixture :digitalocean-name "REPLACE_ME"))))
  (is (= "posthog-optout" (validate/compute-name (optout)))))

(deftest compute-name-honours-the-override
  (is (= "analytics-1" (validate/compute-name (fixture :digitalocean-name " analytics-1 ")))))

(deftest compute-name-is-not-required-but-is-validated
  (is (not-any? #(str/includes? % "digitalocean-name") (validate/state-errors (fixture))))
  (is (some #(str/includes? % "digitalocean-name")
            (validate/state-errors (fixture :digitalocean-name "not valid!"))))
  (is (= "posthog-vultr-fixture" (validate/compute-name (vultr))))
  (is (= "posthog-vultr-optout" (validate/compute-name (vultr-optout))))
  (is (some #(str/includes? % "vultr-name")
            (validate/state-errors (vultr :vultr-name "not valid!"))))
  ;; The unselected provider's name key is not read.
  (is (= "posthog-vultr-fixture" (validate/compute-name (vultr :digitalocean-name "other")))))

(deftest reports-all-errors
  (let [errors (validate/state-errors
                (fixture :posthog-host "bad" :posthog-image "floating"
                         :posthog-backup-retention-days -1
                         :provider-dns "other" :digitalocean-vpc-uuid "forbidden"))]
    (is (<= 5 (count errors)))
    (doseq [part ["host" "image" "retention" "provider-dns" "vpc-uuid"]]
      (is (some #(str/includes? % part) errors)))))

(deftest forbids-vpc-configuration
  (is (some #(str/includes? % "must be absent")
            (validate/state-errors (fixture :digitalocean-vpc-cidr "10.0.0.0/16")))))

(deftest profile-overlay-is-refused
  (is (seq (validate/env-errors {"COLORS_PAR_PROFILE" "other"})))
  (is (nil? (validate/env-errors {}))))

(deftest names-all-package-secrets
  (let [errors (str/join "\n" (validate/secret-errors (fixture)))]
    (doseq [name ["COLORS_PAR_DO_TOKEN" "COLORS_PAR_CLOUDFLARE_API_TOKEN"
                  "COLORS_PAR_R2_ACCESS_KEY_ID" "COLORS_PAR_R2_SECRET_ACCESS_KEY"
                  "COLORS_PAR_POSTHOG_BACKUP_R2_ACCESS_KEY_ID"
                  "COLORS_PAR_POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY"
                  "COLORS_PAR_POSTHOG_SECRET_KEY"
                  "COLORS_PAR_POSTHOG_POSTGRES_PASSWORD"
                  "COLORS_PAR_POSTHOG_OIDC_RSA_PRIVATE_KEY"
                  "COLORS_PAR_POSTHOG_ENCRYPTION_SALT_KEYS"]]
      (is (str/includes? errors name)))))
