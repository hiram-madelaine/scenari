(ns scenari.v2.step-test
  (:require [clojure.test :as t]
            [scenari.v2.core :as v2]
            [scenari.v2.glue :as glue]
            [scenari.v2.step :refer [generate-step-fn]]))

(defn- ->step [step-string]
  (-> (v2/->feature-ast (format "Feature: f\nScenario: s\n %s" step-string) {} *ns*)
      :scenarios first :steps first))

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
           "(defwhen \"I create a new product with id {int}\"  [state arg0 arg1]  (do \"something\"))")))

(t/deftest ponctuation-test
  (t/testing "ponctuation et accents dans un step sans glue : le squelette est
  généré, au lieu d'une ClassCastException qui faisait échouer le chargement du
  namespace au premier step non collé"
    (t/is (= "(defgiven \"une étape, avec une virgule \\\\(et des parenthèses) !\"  [state ]  (do \"setup or assert correct tested component state\"))"
             (generate-step-fn (->step "Given une étape, avec une virgule (et des parenthèses) !"))))
    (t/is (= "(defwhen \"I pay {string} euros: c est fini.\"  [state arg0]  (do \"something\"))"
             (generate-step-fn (->step "When I pay \"10\" euros: c est fini.")))))

  (t/testing "une phrase que l'ancienne grammaire ne savait pas découper - elle
  levait, et le namespace ne se chargeait pas - donne maintenant un squelette"
    (t/is (= "(defgiven \"a < b\"  [state ]  (do \"setup or assert correct tested component state\"))"
             (generate-step-fn (->step "Given a < b"))))))

(t/deftest generated-skeleton-round-trip-test
  (t/testing "le squelette se relit et matche la phrase dont il vient : c'est ce
  qui couvre l'échappement des caractères d'expression, `(` et `/`, que le
  générateur pose et que le lecteur Clojure doit rendre intacts"
    (doseq [sentence ["l'arborescence contient les dossiers \"Commandes\" / \"A venir\""
                      "une étape, avec une virgule (et des parenthèses) !"
                      "le prix de \"iphone 6\" est 42 euros"]]
      (let [[_ expression] (read-string (generate-step-fn (->step (str "Given " sentence))))]
        (t/is (some? (glue/find-glue-by-step-regex {:sentence sentence} 'a.ns
                                                   [{:step expression :ns 'a.ns :name 'g}]))
              (str sentence " -> " expression))))))
