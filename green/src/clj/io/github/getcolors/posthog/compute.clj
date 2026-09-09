(ns io.github.getcolors.posthog.compute
  (:require [clojure.string :as str] [clojure.walk :as walk] [cheshire.core :as json] [babashka.fs :as fs]
            [io.github.getcolors.compute :as compute]
            [io.github.getcolors.compute-deployment-request :as request]
            [io.github.getcolors.compute-planning :as planning]
            [io.github.getcolors.compute-orchestration :as orchestration]
            [io.github.getcolors.compute-inspection :as inspection]))
(def topology [{:role nil :count 1}])
(defn requirements [opts]
  (let [ssh (request/source-cidrs opts "ssh-sources" "posthog-ssh-sources") http (request/source-cidrs opts "http-sources" "posthog-http-sources")]
    {:single_host true :legacy_state_keys [(str (:profile opts) "/posthog-infrastructure.tfstate")]
     :security {:ingress (into [{:id "ssh" :protocol "tcp" :from_port 22 :to_port 22 :sources ssh}]
                              (when (seq http) (for [port [80 443]] {:id (str "http-" port) :protocol "tcp" :from_port port :to_port port :sources http}))) :egress "all" :private_filter false}}))
(defn errors [opts] (try (planning/validate-deployment opts topology (requirements opts)) (compute/backend-plan opts (str (:profile opts) "/compute/shared.tfstate")) [] (catch Exception _ ["invalid compute deployment requirements"])))
(defn planned [opts] (planning/plan-deployment opts topology (requirements opts)))
(defn node [result] (let [nodes (get-in result [:cluster :nodes])] (when-not (and (= 1 (count nodes)) (:ip (first nodes))) (throw (ex-info "compute node unavailable" {}))) (first nodes)))
(defn attach [opts result]
  (cond
    (not (contains? #{"planned" "ready" "present" "destroyed"} (:status result))) (assoc opts :green/exit 1 :green/err (if (seq (:errors result)) (str/join "\n" (:errors result)) "compute lifecycle refused"))
    (= "destroyed" (:status result)) (assoc opts :green/exit 0 :posthog/already-destroyed true)
    :else (let [path (get-in result [:key :private_key_path]) path (if (and path (or (= :build (:green/event opts)) (:green/dry-run opts))) (str/replace path "$HOME" "/home/build-placeholder") path)]
            (cond-> (merge opts (node result) {:colors-compute/cluster (:cluster result) :green/exit 0}) path (assoc :ssh-private-key-path path)))))
(defn- compute-json [value indent]
  (let [padding #(apply str (repeat % " "))]
    (cond
      (map? value) (if (empty? value) "{}"
                      (str "{\n" (str/join ",\n" (for [[key item] (sort-by (comp name key) value)]
                                                       (str (padding (+ indent 2)) (json/generate-string key) ": " (compute-json item (+ indent 2)))))
                           "\n" (padding indent) "}"))
      (sequential? value) (if (empty? value) "[]"
                              (str "[\n" (str/join ",\n" (map #(str (padding (+ indent 2)) (compute-json % (+ indent 2))) value)) "\n" (padding indent) "]"))
      :else (json/generate-string value))))

(defn infrastructure-step [opts]
  (try (let [planning? (or (= :build (:green/event opts)) (:green/dry-run opts)) result (if planning? (planned opts) (orchestration/orchestrate opts topology (requirements opts)))]
         (when planning? (let [root (fs/path (:workdir opts) (:profile opts) "compute") stages (assoc (into {} (for [[id docs] (get-in result [:documents :nodes])] [(str "nodes/" (name id)) docs])) "shared" (get-in result [:documents :shared]))]
                           (doseq [[stage docs] stages
                                   :let [state-key (if (= stage "shared") (get-in result [:state_keys :shared]) (get-in result [:state_keys :nodes (subs stage 6)]))
                                         docs (assoc docs "backend.tf.json" (:config (compute/backend-plan opts state-key))) ]
                                   [filename document] docs]
                             (fs/create-dirs (fs/path root stage))
                             (spit (str (fs/path root stage (name filename))) (str (compute-json document 0) "\n")))))
         (attach opts result)) (catch Exception _ (assoc opts :green/exit 1 :green/err "invalid compute deployment requirements"))))
(defn load-step [opts env] (attach opts (inspection/read-deployment opts env nil (requirements opts))))
