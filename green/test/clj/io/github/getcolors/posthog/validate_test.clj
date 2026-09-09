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

(deftest keygen-follows-the-selected-provider-key
  (is (true? (validate/keygen? (vultr))))
  (is (false? (validate/keygen? (vultr-optout))))
  ;; A DigitalOcean key id in a Vultr deployment is an unselected key: ignored.
  (is (true? (validate/keygen? (vultr :digitalocean-ssh-keys "58495393")))))

;; --- the network contract (§5) -----------------------------------------------

(deftest machine-key-is-not-required
  ;; The standard makes absence meaningful: requiring digitalocean-ssh-keys
  ;; would make every conforming keygen deployment invalid.
  (is (not-any? #(str/includes? % "digitalocean-ssh-keys")
                (validate/state-errors (fixture)))))

(deftest absent-machine-key-selects-keygen
  (is (true? (validate/keygen? (fixture))))
  (is (false? (validate/keygen? (optout)))))

;; Compute Name Standard: the profile is the default, the name key an override.

(deftest reports-all-errors
  (let [errors (validate/state-errors
                (fixture :posthog-host "bad" :posthog-image "floating"
                         :posthog-backup-retention-days -1
                         :provider-dns "other" :digitalocean-vpc-uuid "forbidden"))]
    (is (<= 4 (count errors)))
    (doseq [part ["host" "image" "retention" "provider-dns"]]
      (is (some #(str/includes? % part) errors)))))

(deftest profile-overlay-is-refused
  (is (seq (validate/env-errors {"COLORS_PAR_PROFILE" "other"})))
  (is (nil? (validate/env-errors {}))))
