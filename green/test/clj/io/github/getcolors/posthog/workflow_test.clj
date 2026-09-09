(ns io.github.getcolors.posthog.workflow-test
 (:require [clojure.test :refer [deftest is]]
 [io.github.getcolors.posthog.workflow :as workflow]
 [io.github.getcolors.posthog.compute :as compute]
 [io.github.getcolors.posthog.validate :as validate]
 [io.github.getcolors.posthog.validate-test :refer [fixture optout vultr vultr-optout]]))
(deftest offline-start
 (doseq [f [fixture optout vultr vultr-optout]]
  (is (= 0 (:green/exit (workflow/start-step (assoc (f) :green/event :build) {}))))))
(deftest singleton-library-contract
 (is (= [{:role nil :count 1}] compute/topology))
 (is (= ["posthog-fixture/posthog-infrastructure.tfstate"] (:legacy_state_keys (compute/requirements (fixture))))))
(deftest errors-and-observed-nodes
 (is (= "legacy compute state requires migration" (:green/err (compute/attach (fixture) {:status "error" :errors ["legacy compute state requires migration"]}))))
 (is (= "ubuntu" (:user (compute/attach (fixture) {:status "present" :cluster {:nodes [{:ip "203.0.113.7" :user "ubuntu"}]}}))))
 (is (:posthog/already-destroyed (compute/attach (fixture) {:status "destroyed"}))))

(deftest cleanup-ip-override-after-owned-state
 (let [calls (atom 0) opts (assoc (fixture) :green/event :delete :compute-prevent-destroy false :ip "203.0.113.99")]
  (with-redefs [validate/secret-errors (constantly []) compute/load-step (fn [o _] (swap! calls inc) (assoc o :green/exit 0 :ip "203.0.113.7" :user "ubuntu"))]
   (let [result (workflow/start-step opts {})] (is (= 1 @calls)) (is (= "203.0.113.99" (:ip result))) (is (= "ubuntu" (:user result)))))
  (with-redefs [validate/secret-errors (constantly []) compute/load-step (fn [o _] (assoc o :green/exit 1 :green/err "state unreadable"))]
   (is (= 1 (:green/exit (workflow/start-step opts {})))))))

(deftest library-document-keys-render-deterministically
 (is (= (#'io.github.getcolors.posthog.compute/compute-json {"a" 0 :b 1} 0)
        (#'io.github.getcolors.posthog.compute/compute-json {:a 0 "b" 1} 0))))
