(ns io.github.getcolors.posthog.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.cli :as green-cli]
            [io.github.getcolors.posthog.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")
(def optout-file "test/fixtures/optout.yml")

(defn- read-fixture [path overrides]
  (merge (green-cli/read-state path (str/replace (slurp path) "WORKDIR" ".colors"))
         overrides))
(defn fixture [& {:as overrides}] (read-fixture fixture-file overrides))
(defn optout [& {:as overrides}] (read-fixture optout-file overrides))

(deftest fixture-is-valid
  (is (= [] (validate/state-errors (fixture)))))

(deftest optout-fixture-is-valid
  (is (= [] (validate/state-errors (optout)))))

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
            (validate/state-errors (fixture :digitalocean-name "not valid!")))))

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
