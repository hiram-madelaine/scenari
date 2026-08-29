(ns scenari.v2.parallel-test
  "Preuve que :kaocha.type.scenari/parallel? fait vraiment tourner des
  features en parallele (et pas en sequentiel deguise), et que sans l'option
  le comportement reste sequentiel."
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

(v2/defwhen "the parallel step sleeps a bit" [state]
  (Thread/sleep 200)
  state)

(v2/deffeature parallel-feature-a
  "Feature: parallel a
  Scenario: sleep a
      When the parallel step sleeps a bit"
  {:pre-run [#'pre-a] :post-run [#'post-a]})

(v2/deffeature parallel-feature-b
  "Feature: parallel b
  Scenario: sleep b
      When the parallel step sleeps a bit"
  {:pre-run [#'pre-b] :post-run [#'post-b]})

(defn- loaded-suite [parallel?]
  (testable/load {:kaocha.testable/type           :kaocha.type/scenari
                  :kaocha.testable/id             :parallel-suite
                  :kaocha/source-paths            ["src"]
                  :kaocha/test-paths              ["test/scenari/v2"]
                  :kaocha.type.scenari/glue-paths ["test/scenari/v2"]
                  :kaocha.type.scenari/parallel?  parallel?}))

(defn- window [id]
  (let [entries (filter #(= id (:id %)) @timing-journal)]
    [(:at (first (filter #(= :start (:phase %)) entries)))
     (:at (first (filter #(= :end (:phase %)) entries)))]))

(defn- overlap? [[s1 e1] [s2 e2]]
  (< (max s1 s2) (min e1 e2)))

(deftest features-run-concurrently-when-parallel-test
  (testing "two features, each timestamped at pre-run/post-run, overlap iff parallel? is set"
    (reset! timing-journal [])
    (testable/-run (loaded-suite true) {})
    (is (overlap? (window :a) (window :b))
        "with parallel? true, feature A and B windows must overlap"))
  (testing "and stay sequential when parallel? is not set"
    (reset! timing-journal [])
    (testable/-run (loaded-suite false) {})
    (is (not (overlap? (window :a) (window :b)))
        "with parallel? false (default), features run one after another")))
