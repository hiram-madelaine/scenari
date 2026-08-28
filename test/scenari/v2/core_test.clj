(ns scenari.v2.core-test
  (:require [clojure.test :as t :refer [is]]
            [scenari.v2.core :as v2]
            [scenari.v2.test :as sc-test]
            [kaocha.type.scenari]
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
