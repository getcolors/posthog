(ns io.github.getcolors.posthog.workflow-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [io.github.getcolors.posthog.validate-test :refer [fixture]]
            [io.github.getcolors.posthog.workflow :as workflow]))

(deftest build-and-dry-run-need-no-credentials
  (is (= 0 (:green/exit (workflow/start-step (assoc (fixture) :green/event :build) {}))))
  (is (= 0 (:green/exit (workflow/start-step
                         (assoc (fixture) :green/event :create :green/dry-run true) {})))))

(deftest real-create-requires-credentials
  (let [r (workflow/start-step (assoc (fixture) :green/event :create) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COLORS_PAR_DO_TOKEN"))
    (is (str/includes? (:green/err r) "COLORS_PAR_POSTHOG_BACKUP_R2_SECRET_ACCESS_KEY"))))

(deftest delete-is-protected
  (let [r (workflow/start-step (assoc (fixture) :green/event :delete) {})]
    (is (= 2 (:green/exit r)))
    (is (str/includes? (:green/err r) "COMPUTE_PREVENT_DESTROY"))))

(deftest graph-orders-private-stack
  (is (= [:posthog/infrastructure]
         (vec (rest (workflow/wire-fn :posthog/start {:green/event :create})))))
  (is (= [:posthog/dns]
         (vec (rest (workflow/wire-fn :posthog/infrastructure {:green/event :create})))))
  (is (= [:posthog/ansible]
         (vec (rest (workflow/wire-fn :posthog/start {:green/event :delete}))))))
