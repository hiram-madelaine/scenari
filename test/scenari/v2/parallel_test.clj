(ns scenari.v2.parallel-test
  "Preuve que le tag gherkin @parallel fait vraiment tourner une feature en
  arriere-plan pendant que la suite continue, sans affecter les features non
  taguees (qui restent sequentielles, comme avant)."
  (:require [clojure.test :as t :refer [deftest testing is]]
            [scenari.v2.core :as v2]
            [kaocha.type.scenari]
            [kaocha.testable :as testable]))

(def timing-journal (atom []))

(defn- record! [id phase]
  (swap! timing-journal conj {:id id :phase phase :at (System/nanoTime)}))

(defn- pre-a [] (record! :a :start))
(defn- post-a [] (record! :a :end))
(defn- pre-b [] (record! :b :start))
(defn- post-b [] (record! :b :end))
(defn- pre-c [] (record! :c :start))
(defn- post-c [] (record! :c :end))
(defn- pre-d [] (record! :d :start))
(defn- post-d [] (record! :d :end))

(v2/defwhen "the parallel step sleeps a bit" [state]
  (Thread/sleep 200)
  state)

(v2/deffeature parallel-feature-a
  "@parallel
Feature: parallel a
  Scenario: sleep a
      When the parallel step sleeps a bit"
  {:pre-run [#'pre-a] :post-run [#'post-a]})

(v2/deffeature parallel-feature-b
  "@parallel
Feature: parallel b
  Scenario: sleep b
      When the parallel step sleeps a bit"
  {:pre-run [#'pre-b] :post-run [#'post-b]})

(v2/deffeature sequential-feature-c
  "Feature: sequential c
  Scenario: sleep c
      When the parallel step sleeps a bit"
  {:pre-run [#'pre-c] :post-run [#'post-c]})

(v2/deffeature sequential-feature-d
  "Feature: sequential d
  Scenario: sleep d
      When the parallel step sleeps a bit"
  {:pre-run [#'pre-d] :post-run [#'post-d]})

(defn- loaded-suite []
  (testable/load {:kaocha.testable/type           :kaocha.type/scenari
                  :kaocha.testable/id             :parallel-suite
                  :kaocha/source-paths            ["src"]
                  :kaocha/test-paths              ["test/scenari/v2"]
                  :kaocha.type.scenari/glue-paths ["test/scenari/v2"]}))

(defn- window [id]
  (let [entries (filter #(= id (:id %)) @timing-journal)]
    [(:at (first (filter #(= :start (:phase %)) entries)))
     (:at (first (filter #(= :end (:phase %)) entries)))]))

(defn- overlap? [[s1 e1] [s2 e2]]
  (< (max s1 s2) (min e1 e2)))

(deftest at-parallel-tag-runs-concurrently-test
  (reset! timing-journal [])
  (testable/-run (loaded-suite) {})
  (testing "@parallel-tagged features overlap"
    (is (overlap? (window :a) (window :b))
        "parallel-feature-a and -b are both @parallel, their windows must overlap"))
  (testing "untagged features stay sequential, unaffected by the others"
    (is (not (overlap? (window :c) (window :d)))
        "sequential-feature-c and -d carry no tag, one must finish before the other starts")))
