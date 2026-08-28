(ns scenari.v2.step-test
  (:require [clojure.test :as t]
            [scenari.v2.step :refer [generate-step-fn]]))

(defn- ->step [step-string]
  (let [[_spec
         [_narrative]
         [_scenarios
          [_scenario
           [_scenario_sentence]
           [_steps step]]]]
        (scenari.v2.parser/gherkin (format "Feature: \n Scenario: \n %s" step-string))]
    (scenari.v2.core/step->map step)))

(t/deftest generate-step-fn-test
           (t/is (= (generate-step-fn (->step "When I create a new product with name \"iphone 6\""))
                    "(defwhen \"I create a new product with name {string}\"  [state arg0]  (do \"something\"))"))
           (t/is (= (generate-step-fn (->step "When I create a new product with name \"iphone 6\" and description \"awesome phone\""))
                    "(defwhen \"I create a new product with name {string} and description {string}\"  [state arg0 arg1]  (do \"something\"))"))
           (t/is (= (generate-step-fn (->step "When I create a new products
                                               | size | weight |
                                               | 6    |    2   |"))
                    "(defwhen \"I create a new products\"  [state arg0]  (do \"something\"))"))
           (t/is (= (generate-step-fn (->step "When I create a new products
                                               \"\"\"
                                               this is markdown
                                               \"\"\""))
                    "(defwhen \"I create a new products\"  [state arg0]  (do \"something\"))"))
           (t/is (= (generate-step-fn (->step "When I create a new product with name \"iPhone 6\" and others
                                               | product name | product desc |
                                               | iPhone 7     | telephone    |"))
                    "(defwhen \"I create a new product with name {string} and others\"  [state arg0 arg1]  (do \"something\"))"))
           (t/is (= (generate-step-fn (->step "When I create a new product with id 1234
                                               | product name | product desc |
                                               | iPhone 7     | telephone    |"))
                    "(defwhen \"I create a new product with id {number}\"  [state arg0 arg1]  (do \"something\"))")))

(t/deftest ponctuation-test
  (t/testing "ponctuation et accents dans un step sans glue : le squelette est
  généré, au lieu d'une ClassCastException qui faisait échouer le chargement du
  namespace au premier step non collé"
    (t/is (= "(defgiven \"une étape, avec une virgule (et des parenthèses) !\"  [state ]  (do \"setup or assert correct tested component state\"))"
             (generate-step-fn (->step "Given une étape, avec une virgule (et des parenthèses) !"))))
    (t/is (= "(defwhen \"I pay {string} euros: c est fini.\"  [state arg0]  (do \"something\"))"
             (generate-step-fn (->step "When I pay \"10\" euros: c est fini.")))))

  (t/testing "une phrase que la grammaire ne sait vraiment pas découper lève un
  message lisible"
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot parse the step sentence"
                            (generate-step-fn {:raw "Given a < b" :params []})))))
