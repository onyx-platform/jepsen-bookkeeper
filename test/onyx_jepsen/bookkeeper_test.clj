(ns onyx-jepsen.bookkeeper-test
  (:require [clojure.test :refer :all]
            [jepsen.core :as jc]
            [jepsen.generator :as gen]
            [onyx-jepsen.bookkeeper :as bk]))

(def version
  "What onyx version should we test?"
  "version-not-supplied")

(def test-name 
  "onyx-basic-test")

(def env-config
  (-> "resources/prod-env-config.edn" slurp read-string))

(def peer-config
  (-> "resources/prod-peer-config.edn" slurp read-string))

(def test-setup 
  {:version version
   :nemesis (first (shuffle [:bridge-shuffle :random-halves])) ; :bridge-shuffle or :random-halves
   :awake-secs 200
   :stopped-secs 100
   :time-limit 300})

(deftest basic-test
  (println "Running with test setup:" test-setup)
  (is (-> (bk/basic-test test-setup)
          jc/run!
          :results
          :valid?)))
