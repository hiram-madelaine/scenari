(ns scenari.v2.feature-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [scenari.v2.core :as v2]
            [scenari.v2.test :as sc-test]
            [kaocha.type.scenari]
            [scenari.v2.some-glue-ns]
            [kaocha.repl :as krepl]
            [kaocha.testable :as testable]
            [testit.core :refer :all]))

(def side-effect-atom (atom 0))
(def scenario-side-effect-atom (atom 0))

(v2/defwhen #"I foo" [state]
  (let [scenario-side-effect @scenario-side-effect-atom
        side-effect-atom @side-effect-atom]
    (fact 1 => scenario-side-effect)
    (fact 1 => side-effect-atom)
    state))

(v2/defgiven "a doc string"  [state doc-string] (is (= "This is markdown" doc-string)) state)

(defn init-side-effect [] (reset! side-effect-atom 1))
(defn pre-scenario-run-side-effect [] (reset! scenario-side-effect-atom 1))
(defn post-scenario-run-side-effect [] (reset! scenario-side-effect-atom 1))

(v2/deffeature my-feature "test/scenari/v2/example.feature"
  {:pre-run           [#'init-side-effect]
   :pre-scenario-run  [#'pre-scenario-run-side-effect]
   :post-scenario-run [#'post-scenario-run-side-effect]
   :post-run          [#'init-side-effect]})

(v2/defthen "My initial state contains foo"  [state] (is (= state {:foo 1})) state)

(v2/deffeature short-feature
  "Feature: feature description
  Scenario: Scenario description
      Then My initial state contains foo"
  {:default-scenario-state {:foo 1}})

;; used by kaocha filtering: `--focus-meta var-tagged` / `--focus-meta annotated`
(v2/deffeature ^:var-tagged tagged-feature
  "@annotated
Feature: tagged feature
  @scenario-annotated
  Scenario: tagged scenario
      Then My initial state contains foo"
  {:default-scenario-state {:foo 1}})

(def post-run-atom (atom 0))
(defn post-run-side-effect [] (swap! post-run-atom inc))

(v2/deffeature post-run-feature
  "Feature: teardown at the feature level
  Scenario: Scenario description
      Then My initial state contains foo"
  {:default-scenario-state {:foo 1}
   :post-run               [#'post-run-side-effect]})

(deftest post-run-test
  (testing ":post-run runs once the feature is over, in all three runners"
    (reset! post-run-atom 0)
    (v2/run-features #'scenari.v2.feature-test/post-run-feature)
    (is (= 1 @post-run-atom) "core/run-features must be eager, not a lazy map")
    (sc-test/run-features #'scenari.v2.feature-test/post-run-feature)
    (is (= 2 @post-run-atom))
    ;; the kaocha suite type is the runner ./test.sh uses, and it used to load
    ;; the feature without its :post-run at all
    (let [suite (testable/load {:kaocha.testable/type           :kaocha.type/scenari
                                :kaocha.testable/id             :scenario
                                :kaocha/source-paths            ["src"]
                                :kaocha/test-paths              ["test/scenari/v2"]
                                :kaocha.type.scenari/glue-paths ["test/scenari/v2"]})
          feature (->> (:kaocha.test-plan/tests suite)
                       (filter #(= ::post-run-feature (:kaocha.testable/id %)))
                       first)]
      (is (seq (:kaocha.type.scenari/post-run feature))
          "-load must carry the feature's :post-run onto the testable")
      (testable/-run feature {})
      (is (= 3 @post-run-atom)))))

(deftest run-hooks-test
  (testing "the teardown runs even when the body throws - a report, an ambiguous
  glue or a pre-run hook can throw past run-step's catch, and that is the very
  case :post-run exists for"
    (let [journal (atom [])
          hook    (fn [k] {:ref #(swap! journal conj k)})]
      (is (thrown? Exception
                   (v2/run-hooks {:pre-run [(hook :pre)] :post-run [(hook :post)]}
                                 #(throw (ex-info "boom" {})))))
      (is (= [:pre :post] @journal))
      (reset! journal [])
      (is (thrown? Exception
                   (v2/run-hooks {:pre-run  [{:ref #(throw (ex-info "boom" {}))}]
                                  :post-run [(hook :post)]}
                                 (constantly nil))))
      (is (= [:post] @journal) "a throwing pre-run must not skip the teardown"))))

(deftest scenari-runner-test
  (testing "Using scenari runner"
    (testing "execute success feature"
      (let [[feature-result] (v2/run-features #'scenari.v2.feature-test/short-feature)]
        (fact "return an execution tree with status :success"
              feature-result =in=> {:feature   "feature description",
                                    :scenarios [{:pre-run       [],
                                                 :post-run      [],
                                                 :default-state {:foo 1},
                                                 :scenario-name " Scenario description",
                                                 :steps         [{:sentence-keyword :then,
                                                                  :input-state      {:foo 1},
                                                                  :raw              "Then My initial state contains foo",
                                                                  :sentence         "My initial state contains foo",
                                                                  :params           [],
                                                                  :output-state     {:foo 1},
                                                                  :status           :success,
                                                                  :order            0}],
                                                 :status        :success}],
                                    :pre-run   [],
                                    :status    :success})))))

(comment
  (remove-ns 'scenari.v2.feature-test)
  (meta #'scenari.v2.feature-test/my-feature)
  (v2/run-features)
  (v2/run-features #'scenari.v2.feature-test/my-feature)
  (sc-test/run-features #'scenari.v2.feature-test/my-feature)
  (krepl/test-plan)
  (krepl/run-all)
  (krepl/run :scenario))