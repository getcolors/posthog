(ns io.github.getcolors.posthog.utils
  (:require [clojure.string :as str]))

(def contract 1)

(defn registrable-domain [host]
  (str/join "." (take-last 2 (str/split (str host) #"\."))))
