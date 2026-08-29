(ns scenari.v2.parsing-test
  "Le parser de *phrase* : la moitié de scenari que gherkin ne couvre pas, celle
  qui extrait les paramètres d'un step pour les passer au glue. Le fichier
  .feature lui-même est parsé par `io.cucumber/gherkin` — voir
  `gherkin-conformance-test`."
  (:require [clojure.test :refer :all]
            [scenari.v2.parser :refer [sentence]]))

(deftest sentence-test
  (testing "Parsing sentences with parameters"
    (is (= (sentence "I create a new product with name \"iphone 6\" and description \"awesome phone\"")
           [:SENTENCE
            [:words "I create a new product with name "]
            [:string "iphone 6"]
            [:words " and description "]
            [:string "awesome phone"]]))

    (is (= (sentence "I buy 42 products")
           [:SENTENCE
            [:words "I buy "]
            [:number "42"]
            [:words " products"]]))

    (is (= (sentence "I create a new product with <product_name> and price ${price}")
           [:SENTENCE
            [:words "I create a new product with "]
            [:parameter "product_name"]
            [:words " and price "]
            [:parameter "price"]]))

    (is (= (sentence "I create a product with map {\"name\":\"phone\",\"price\":499}")
           [:SENTENCE
            [:words "I create a product with map "]
            [:map "{\"name\":\"phone\",\"price\":499}"]]))))

(deftest unicode-character-test
  (is (= (sentence "a product with name \"Téléphone\"")
         [:SENTENCE
          [:words "a product with name "]
          [:string "Téléphone"]])))
