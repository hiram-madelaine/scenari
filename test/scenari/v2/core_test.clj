(ns scenari.v2.core-test
  (:require [clojure.test :as t :refer [is]]
            [scenari.v2.core :as v2]
            [scenari.v2.test :as sc-test]
            [kaocha.type.scenari]
            [kaocha.testable :as testable]
            [kaocha.plugin.filter :as kfilter]
            [kaocha.repl :as krepl]
            [testit.core :refer :all]))

(t/deftest find-sentence-params-test
  (t/testing "finding parameters in sentence"
    (is (= (v2/find-sentence-params "Given an id 1234") [{:type :value, :val 1234}]) "should return number value")
    (is (= (v2/find-sentence-params "Given an id \"1234\"") [{:type :value, :val "1234"}]) "should return string value")
    (is (= (v2/find-sentence-params "Given an id abc") []) "should return no parameters")
    (is (= (v2/find-sentence-params "Given an id 1234 and \"1234\" ") [{:type :value, :val 1234} {:type :value, :val "1234"}]) "should return multiple value")))

(v2/defgiven #"My duplicated step in other ns and feature ns" [state]
  state)

(t/deftest deffeature-macro-test
  (t/testing "macro definition taking different feature structure"
    (t/is (some? (macroexpand '(v2/deffeature example-feature "test/scenari/v2/example.feature"))))
    (t/is (some? (macroexpand '(v2/deffeature example-feature (slurp "test/scenari/v2/example.feature")))))
    (t/is (some? (macroexpand '(v2/deffeature example-feature (first (vector (slurp "test/scenari/v2/example.feature")))))))
    (t/is (some? (macroexpand '(v2/deffeature (symbol (str "example-feature")) (first (vector (slurp "test/scenari/v2/example.feature")))))))))

(comment
  (remove-ns 'scenari.v2.core-test)
  (meta #'scenari.v2.core-test/my-feature)
  (v2/run-features)
  (v2/run-features #'scenari.v2.core-test/my-feature)
  (sc-test/run-features #'scenari.v2.core-test/my-feature)

  (t/run-tests)

  (krepl/test-plan)
  (krepl/run-all)
  (krepl/run :scenario))

(t/deftest feature-ast-parse-failure-test
  (t/testing "an unparsable feature raises instead of yielding an empty, passing feature"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot parse feature"
                          (v2/->feature-ast "Feature: f\nScenario: s\nGiven a\n  \"\"\"\nune doc string jamais fermee" {} *ns*)))
    (is (= 1 (count (:scenarios (v2/->feature-ast "Feature: f\nScenario: s\nGiven a" {} *ns*))))
        "a valid feature is unaffected"))

  (t/testing "a feature whose keywords are all unrecognized is swallowed as description, not silently empty"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no scenario"
                          (v2/->feature-ast "Fonctionnalite: f\nScenario ! s\nSoit a" {} *ns*)))))

(t/deftest table-cell-escapes-test
  (t/testing "a cell is trimmed and unescaped"
    (is (= [{:nom "a | b" :regex "x\ny"}]
           (-> (v2/->feature-ast (str "Feature: f\nScenario: s\nGiven a\n"
                                      "| nom | regex |\n"
                                      "| a \\| b | x\\ny |")
                                 {} *ns*)
               :scenarios first :steps first :params first :val))
        "\\| stays in its cell instead of splitting it, \\n becomes a newline")))

(t/deftest scenario-outline-test
  (t/testing "a scenario outline yields one scenario per Examples row, placeholders substituted"
    (let [scenarios (:scenarios (v2/->feature-ast
                                 (str "Feature: f\n"
                                      "Scenario Outline: adding <a> and <b>\n"
                                      "Given a number <a>\n"
                                      "When I add <b>\n"
                                      "Then I get <sum>\n"
                                      "Examples:\n"
                                      "| a | b | sum |\n"
                                      "| 1 | 2 | 3   |\n"
                                      "| 5 | 5 | 10  |")
                                 {} *ns*))]
      (is (= 2 (count scenarios)))
      (is (= [" adding 1 and 2" " adding 5 and 5"] (map :scenario-name scenarios))
          "the scenario name is substituted too")
      (is (= [["a number 1" "I add 2" "I get 3"]
              ["a number 5" "I add 5" "I get 10"]]
             (map #(map :sentence (:steps %)) scenarios)))
      (is (= [[{:type :value :val 1}] [{:type :value :val 2}] [{:type :value :val 3}]]
             (map :params (:steps (first scenarios))))
          "substituted values are parsed as step params, so glues receive them"))))

(t/deftest background-test
  (t/testing "background steps are spliced at the head of every scenario"
    (let [scenarios (:scenarios (v2/->feature-ast
                                 (str "Feature: f\n"
                                      "Background: a common setup\n"
                                      "Given a logged user\n"
                                      "And a cart\n"
                                      "\n"
                                      "Scenario: s1\n"
                                      "When I buy\n"
                                      "Scenario: s2\n"
                                      "When I leave")
                                 {} *ns*))]
      (is (= [["a logged user" "a cart" "I buy"]
              ["a logged user" "a cart" "I leave"]]
             (map #(map :sentence (:steps %)) scenarios)))
      (is (= [[0 1 2] [0 1 2]] (map #(map :order (:steps %)) scenarios))
          "spliced steps are renumbered, the runner relies on :order"))))

(t/deftest rule-test
  (t/testing "Rule scenarios are lifted into the feature, with the rule's own background"
    (let [scenarios (:scenarios (v2/->feature-ast
                                 (str "Feature: f\n"
                                      "Background:\n"
                                      "Given feature setup\n"
                                      "Rule: r1\n"
                                      "  Background:\n"
                                      "  Given rule setup\n"
                                      "  Example: e1\n"
                                      "  When x\n"
                                      "Rule: r2\n"
                                      "  Example: e2\n"
                                      "  When y\n")
                                 {} *ns*))]
      (is (= [[" e1" ["feature setup" "rule setup" "x"]]
              [" e2" ["feature setup" "y"]]]
             (map (juxt :scenario-name #(map :sentence (:steps %))) scenarios))
          "the feature background comes first, then the rule's own"))))

(defn- surviving-scenarios
  "[feature scenario] pairs left after kaocha's filter marked the rest ::skip."
  [suite]
  (for [feature (when-not (::testable/skip suite) (:kaocha.test-plan/tests suite))
        :when (not (::testable/skip feature))
        scenario (:kaocha.test-plan/tests feature)
        :when (not (::testable/skip scenario))]
    [(::testable/desc feature) (::testable/desc scenario)]))

(defn- focus-meta [suite k]
  (surviving-scenarios (kfilter/filter-testable suite {:focus-meta [k]})))

(t/deftest kaocha-tag-filtering-test
  (let [suite (testable/-load {::testable/type                 :kaocha.type/scenari
                               :kaocha/source-paths            ["src"]
                               :kaocha/test-paths              ["test/scenari/v2"]
                               :kaocha.type.scenari/glue-paths ["scenari/v2"]})]
    (t/testing "every feature of the test paths is loaded"
      (is (< 1 (count (surviving-scenarios suite)))))

    (t/testing "--focus-meta on a gherkin tag of a scenario keeps that scenario alone"
      (is (= [["tagged feature" " tagged scenario"]] (focus-meta suite :scenario-annotated))))

    (t/testing "--focus-meta on a gherkin tag of a feature, or on deffeature var meta,
    keeps the whole feature"
      (is (= [["tagged feature" " tagged scenario"]] (focus-meta suite :annotated)))
      (is (= [["tagged feature" " tagged scenario"]] (focus-meta suite :var-tagged))))))
