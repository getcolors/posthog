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

(deftest per-provider-checks-run-only-for-the-selected-provider
  (is (some #(str/includes? % "vultr-os-id") (validate/state-errors (vultr :vultr-os-id "2284"))))
  (is (= [] (validate/state-errors (fixture :vultr-os-id "2284"))))
  (is (some #(str/includes? % "vpc-uuid") (validate/state-errors (fixture :digitalocean-vpc-uuid "x"))))
  (is (= [] (validate/state-errors (vultr :digitalocean-vpc-uuid "x")))))

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

(deftest cidr-syntax
  (doseq [ok ["0.0.0.0/0" "10.0.0.0/8" "203.0.113.7/32" "::/0" "2001:db8::/32"
              "fe80::1/128" "::ffff:192.0.2.10/96" "2001:db8:0:0:0:0:0:1/64"]]
    (is (validate/cidr? ok) ok))
  (doseq [bad ["" "10.0.0.0" "10.0.0.0/33" "256.0.0.1/8" "10.0.0/8" "::/129"
               "2001:db8::/-1" "2001:db8:::1/64" "1:2:3:4:5:6:7:8:9/64" "g::1/64"
               "10.0.0.0/8/8" "example.com/24"]]
    (is (not (validate/cidr? bad)) bad)))

(deftest ssh-sources-must-reach-someone
  (doseq [f [fixture vultr]]
    (let [opts (f)
          k (validate/compute-key opts "ssh-sources")
          errors (validate/state-errors (assoc opts k []))]
      (is (some #(str/includes? % "at least one CIDR") errors) (str k)))))

(deftest malformed-sources-are-refused-in-either-list
  (doseq [f [fixture vultr]]
    (let [opts (f)
          ssh-k (validate/compute-key opts "ssh-sources")
          http-k (validate/compute-key opts "http-sources")]
      (is (some #(str/includes? % "not an IPv4 or IPv6 CIDR: 10.0.0.0")
                (validate/state-errors (assoc opts ssh-k ["10.0.0.0"]))))
      (is (some #(str/includes? % "not an IPv4 or IPv6 CIDR: nope")
                (validate/state-errors (assoc opts http-k ["0.0.0.0/0" "nope"]))))
      ;; An empty http list means no public HTTP and is allowed.
      (is (= [] (validate/state-errors (assoc opts http-k []))))
      ;; Overlay strings are split the way the template reads them.
      (is (= [] (validate/state-errors (assoc opts ssh-k "10.0.0.0/8, 192.0.2.0/24")))))))

;; --- provider switching is a rebuild (§4) ------------------------------------

(deftest provider-state-refuses-a-switch
  (is (= ["state holds a vultr machine; set provider-compute back to vultr and delete first"]
         (validate/provider-state-errors (fixture) {:provider "vultr" :ip "203.0.113.7"})))
  (is (= ["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]
         (validate/provider-state-errors (vultr) {:provider "digitalocean" :ip "203.0.113.7"}))))

(deftest provider-state-accepts-the-recorded-provider-and-no-state
  (is (nil? (validate/provider-state-errors (fixture) {:provider "digitalocean" :ip "203.0.113.7"})))
  (is (nil? (validate/provider-state-errors (vultr) {:provider "vultr" :ip "203.0.113.7"})))
  (is (nil? (validate/provider-state-errors (fixture) nil)))
  (is (nil? (validate/provider-state-errors (vultr) nil))))

(deftest legacy-params-without-a-provider-accept-only-the-default
  ;; Every deployment created before adoption ran the package default.
  (is (nil? (validate/provider-state-errors (fixture) {:ip "203.0.113.7"})))
  (is (= ["state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]
         (validate/provider-state-errors (vultr) {:ip "203.0.113.7"}))))

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
  ;; Provider-specific rules: DigitalOcean names are hostname-like, Vultr
  ;; labels are only a console string.
  (is (some #(str/includes? % "digitalocean-name")
            (validate/state-errors (fixture :digitalocean-name "invalid_name"))))
  (is (some #(str/includes? % "digitalocean-name")
            (validate/state-errors (fixture :digitalocean-name "Upper-Case"))))
  (is (= [] (validate/state-errors (vultr :vultr-name "invalid_name"))))
  (is (= [] (validate/state-errors (vultr :vultr-name "Upper_Case.1"))))
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
