(ns scenari.v2.gherkin-conformance-test
  "Cas de test dérivés de la spécification Gherkin
  (https://cucumber.io/docs/gherkin/reference).

  `conforme-test` : constructions du spec supportées par la grammaire.
  `non-supporte-test` : constructions du spec que la grammaire ne gère pas
  encore. Les assertions y verrouillent l'échec de parsing ; quand une
  construction est implémentée, déplacer le cas dans `conforme-test`."
  (:require [clojure.test :refer :all]
            [instaparse.core :as insta]
            [scenari.v2.parser :refer [gherkin]]))

(defn- ok? [source] (not (insta/failure? (gherkin source))))
(defn- ko? [source] (insta/failure? (gherkin source)))

(deftest conforme-test
  (testing "Feature suivie d'une description libre, terminée au premier mot-clé"
    (is (= (gherkin "Feature: f
  Une description libre,
  sur plusieurs lignes.

  Encore de la description après une ligne vide.

Scenario: s
Given a")
           [:SPEC
            [:narrative "f"]
            [:description "Une description libre,"
             "sur plusieurs lignes."
             "Encore de la description après une ligne vide."]
            [:scenarios
             [:scenario [:scenario_sentence " s"]
              [:steps [:step_sentence [:given] [:sentence "a"]]]]]])))

  (testing "narrative As a / I want to / So that"
    (is (= (gherkin "Feature: f\nAs a x\nI want to y\nSo that z\n\nScenario: s\nGiven a")
           [:SPEC
            [:narrative "f" [:as_a "x"] [:I_want_to "y"] [:so_that "z"]]
            [:scenarios
             [:scenario [:scenario_sentence " s"]
              [:steps [:step_sentence [:given] [:sentence "a"]]]]]])))

  (testing "tags au niveau Feature"
    (is (ok? "@a @b\nFeature: f\nScenario: s\nGiven a")))

  (testing "un tag accepte tout caractère non blanc"
    (is (= [:annotations [:annotation "smoke-test"] [:annotation "issue:123"]]
           (get-in (gherkin "@smoke-test @issue:123\nFeature: f\nScenario: s\nGiven a") [1]))))

  (testing "tags au niveau scénario"
    (is (= [:scenario [:annotations [:annotation "a"] [:annotation "b"]]
            [:scenario_sentence " s"]
            [:steps [:step_sentence [:given] [:sentence "x"]]]]
           (get-in (gherkin "Feature: f\n@a @b\nScenario: s\nGiven x") [2 1]))))

  (testing "mots-clés de step Given/When/Then/And, répétables"
    (is (= 5 (count (rest (get-in (gherkin "Feature: f\nScenario: s\nGiven a\nAnd b\nWhen c\nThen d\nAnd e")
                                  [2 1 2]))))))

  (testing "mots-clés français"
    (is (ok? "Feature: f\nScénario : s\nEtant donné que a\nQuand b\nAlors c\nEt d")))

  (testing "synonymes français : Plan du scénario, Exemple, Mais, Exemples, Scénarios"
    (is (ok? "Feature: f\nPlan du scénario : s\nEtant donné que a\nMais b\nExemples :\n| x |\n| 1 |"))
    (is (ok? "Feature: f\nExemple : s\nQuand x\nScénarios :\n| n |\n| 1 |")))

  (testing "commentaires en tête de fichier et entre les steps"
    (is (ok? "# un commentaire\nFeature: f\nScenario: s\n# un autre\nGiven a")))

  (testing "But et * sont des synonymes de And"
    (is (= [:steps [:step_sentence [:given] [:sentence "a"]]
            [:step_sentence [:and] [:sentence "b"]]
            [:step_sentence [:and] [:sentence "c"]]]
           (get-in (gherkin "Feature: f\nScenario: s\nGiven a\nBut b\n* c") [2 1 2]))))

  (testing "Example:, Scenario Outline:, Scenario Template: sont des synonymes de Scenario:"
    (is (ok? "Feature: f\nExample: s\nGiven a"))
    (is (ok? "Feature: f\nScenario Outline: s\nGiven <x>\nExamples:\n| x |\n| 1 |"))
    (is (ok? "Feature: f\nScenario Template: s\nGiven <x>\nScenarios:\n| x |\n| 1 |")))

  (testing "data table attachée à un step"
    (is (= [:tab_params [:header " nom " " prix "] [:row " iPhone " " 500 "]]
           (get-in (gherkin "Feature: f\nScenario: s\nGiven a\n| nom | prix |\n| iPhone | 500 |")
                   [2 1 2 1 3]))))

  (testing "case vide : la ligne garde ses colonnes, à toute position"
    (let [tab (fn [body] (get-in (gherkin (str "Feature: f\nScenario: s\nGiven a\n" body))
                                 [2 1 2 1 3]))]
      (is (= [:tab_params [:header " a " " b " " c "] [:row " 1 " "   " " 3 "]]
             (tab "| a | b | c |\n| 1 |   | 3 |"))
          "une case vide au milieu ne doit pas scinder la ligne en deux")
      (is (= [:tab_params [:header " a " " b " " c "] [:row " 1 " " 2 " "   "]]
             (tab "| a | b | c |\n| 1 | 2 |   |")))
      (is (= [:tab_params [:header " a " " b " " c "] [:row "   " " 2 " " 3 "]]
             (tab "| a | b | c |\n|   | 2 | 3 |")))
      (is (= [:tab_params [:header " a " " b "] [:row " 1 " "  "] [:row " 3 " " 4 "]]
             (tab "| a | b |\n| 1 |  |\n| 3 | 4 |")))))

  (testing "pipe échappé dans une cellule"
    (is (= [:header " a \\| b " " c "]
           (get-in (gherkin "Feature: f\nScenario: s\nGiven a\n| a \\| b | c |\n| 1 | 2 |")
                   [2 1 2 1 3 1]))))

  (testing "Background, avec ou sans nom"
    (is (ok? "Feature: f\nBackground:\nGiven a\nScenario: s\nGiven b"))
    (is (ok? "Feature: f\nBackground: un nom\nGiven a\nScenario: s\nGiven b"))
    (is (ok? "Feature: f\nContexte :\nEtant donné que a\nScénario : s\nQuand b")))

  (testing "Rule, avec description, Background et tags"
    (is (ok? "Feature: f\nRule: r\nExample: e\nWhen x"))
    (is (ok? (str "Feature: f\nBackground:\nGiven setup\n"
                  "Rule: r1\n  une description\n  Background:\n  Given rule setup\n  Example: e1\n  When x\n"
                  "@tagged\nRule: r2\n  Example: e2\n  When y\n")))
    (is (ok? "Feature: f\nRègle : r\n  Scénario : s\n  Quand x"))
    (is (ok? "Feature: f\nScenario: avant les rules\nGiven z\nRule: r\nExample: e\nWhen x")))

  (testing "section Examples"
    (is (ok? "Feature: f\nScenario: s\nGiven <x>\nExamples:\n| x |\n| 1 |")))

  (testing "commentaire après un bloc Examples : `steps` est le seul endroit du
  corps d'un scénario qui accepte un #, et `examples` le referme"
    (is (ok? "Feature: f\nScenario: s\nGiven <x>\nExamples:\n| x |\n| 1 |\n# un commentaire"))
    (is (ok? (str "Feature: f\nRule: r\nScenario: s\nGiven <x>\n"
                  "Examples:\n| x |\n| 1 |\n# un commentaire")))
    (is (ok? (str "Feature: f\nScenario: s1\nGiven <x>\nExamples:\n| x |\n| 1 |\n"
                  "# un commentaire\nScenario: s2\nGiven a"))))

  (testing "doc string triple-quote"
    (is (= [:doc_string [:doc_content "  ligne 1\n    ligne 2\n  "]]
           (get-in (gherkin "Feature: f\nScenario: s\nGiven a\n  \"\"\"\n  ligne 1\n    ligne 2\n  \"\"\"")
                   [2 1 2 1 3]))))

  (testing "doc string délimitée par des backticks"
    (is (= [:doc_string [:doc_content "  du `code` inline\n  "]]
           (get-in (gherkin "Feature: f\nScenario: s\nGiven a\n  ```\n  du `code` inline\n  ```")
                   [2 1 2 1 3]))))

  (testing "fins de ligne Windows"
    (is (ok? "Feature: f\r\nScenario: s\r\nGiven a\r\n")))

  (testing "feature sans scénario"
    (is (ok? "Feature: f\n")))

  (testing "scénario sans feature"
    (is (ok? "Scenario: s\nGiven a"))))

(deftest non-supporte-test
  (testing "le mot-clé Feature n'a pas de traduction : la ligne tombe dans la
  description et le nom de la feature est perdu, sans erreur"
    (is (= [:SPEC
            [:description "Fonctionnalité: f"]
            [:scenarios [:scenario [:scenario_sentence " s"]
                         [:steps [:step_sentence [:given] [:sentence "a"]]]]]]
           (gherkin "Fonctionnalité: f\nScenario: s\nGiven a"))))

  (testing "la narrative n'est reconnue qu'en anglais, alors que
  kw-translations-data en donne une traduction française"
    (is (= [:SPEC
            [:narrative "f"]
            [:description "En tant que x" "Je veux y"]
            [:scenarios [:scenario [:scenario_sentence " s"]
                         [:steps [:step_sentence [:when] [:sentence "a"]]]]]]
           (gherkin "Feature: f\nEn tant que x\nJe veux y\nScénario : s\nQuand a"))))

  (testing "l'espace après Feature: est obligatoire"
    (is (ko? "Feature:f\nScenario: s\nGiven a")))

  (testing "commentaire à l'intérieur du tableau d'Examples : ni `header` ni
  `row` n'ont d'alternative comment. Seule la fin du bloc est rattrapée."
    (is (ko? "Feature: f\nScenario: s\nGiven <x>\nExamples:\n# cmt\n| x |\n| 1 |"))
    (is (ko? "Feature: f\nScenario: s\nGiven <x>\nExamples:\n| x |\n# cmt\n| 1 |"))))
