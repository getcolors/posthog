(ns io.github.getcolors.posthog.validate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [green.cli :as green-cli]
            [io.github.getcolors.posthog.validate :as validate]))

(def fixture-file "test/fixtures/colors.yml")

(defn fixture [& {:as overrides}]
  (merge (green-cli/read-state fixture-file
                               (str/replace (slurp fixture-file) "WORKDIR" ".colors")) overrides))

(deftest fixture-is-valid
  (is (= [] (validate/state-errors (fixture)))))

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
