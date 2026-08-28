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

  (testing "Scenario, Scenario Outline et Example suivis d'une description libre"
    (is (= (gherkin "Feature: f
Scenario: s
  Une narration de scenario,
  sur deux lignes.
Given a")
           [:SPEC
            [:narrative "f"]
            [:scenarios
             [:scenario [:scenario_sentence " s"]
              [:description "Une narration de scenario," "sur deux lignes."]
              [:steps [:step_sentence [:given] [:sentence "a"]]]]]]))
    (is (ok? "Feature: f\nScenario Outline: s\n  une narration\nGiven <x>\nExamples:\n| x |\n| 1 |"))
    (is (ok? "Feature: f\nRule: r\nExample: e\n  une narration\nWhen x")))

  (testing "Background suivi d'une description libre. La spec l'autorise, mais
  scenari n'a pas de niveau Background dans la feature map \u2014 les steps sont
  epissees dans chaque scenario \u2014 donc la description est acceptee et masquee."
    (is (= (gherkin "Feature: f\nBackground:\n  une narration\n  Given setup\nScenario: s\nGiven a")
           [:SPEC
            [:narrative "f"]
            [:background [:steps [:step_sentence [:given] [:sentence "setup"]]]]
            [:scenarios
             [:scenario [:scenario_sentence " s"]
              [:steps [:step_sentence [:given] [:sentence "a"]]]]]]))
    (is (ok? "Feature: f\nRule: r\n  Background: un nom\n  une narration\n  Given setup\n  Example: e\n  When x")))

  (testing "une description indentee n'a qu'un seul parse : `description_line`
  interdit a son contenu de commencer par un blanc, sinon `<whitespace?>` et le
  contenu se disputent l'indentation"
    (is (= 1 (count (insta/parses gherkin "Feature: f\nScenario: s\n  une narration\nGiven a")))))

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

  (testing "blancs en fin de ligne d'un tableau : très courants dans un .feature
  édité à la main, et `->feature-ast` lève désormais sur un échec de parsing"
    (is (= [:tab_params [:header " a " " b "] [:row " 1 " " 2 "]]
           (get-in (gherkin "Feature: f\nScenario: s\nGiven a\n| a | b |   \n| 1 | 2 |")
                   [2 1 2 1 3])))
    (is (ok? "Feature: f\nScenario: s\nGiven a\n| a | b |\t\n| 1 | 2 |")))

  (testing "ligne vide dans un tableau, entre un step et son tableau, et après Examples:"
    (is (ok? "Feature: f\nScenario: s\nGiven a\n| a | b |\n\n| 1 | 2 |"))
    (is (ok? "Feature: f\nScenario: s\nGiven a\n\n| a | b |\n| 1 | 2 |"))
    (is (ok? "Feature: f\nScenario: s\nGiven a\n\n  \"\"\"\n  txt\n  \"\"\""))
    (is (ok? "Feature: f\nScenario Outline: s\nGiven <x>\nExamples:\n\n| x |\n| 1 |")))

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

  (testing "un bloc Examples peut porter un nom, comme Scenario, Background et
  Rule. Le nom est masqué : la feature map n'a pas de niveau Examples."
    (is (ok? "Feature: f\nScenario Outline: s\nGiven <x>\nExamples: les cas nominaux\n| x |\n| 1 |"))
    (is (ok? "Feature: f\nPlan du scénario : s\nQuand <x>\nExemples : les cas\n| x |\n| 1 |")))

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

  (testing "un bloc fencé dans une doc string \"\"\", et l'inverse : le contenu
  n'exclut que le délimiteur qui a ouvert la doc string"
    (is (= [:doc_string [:doc_content "  ```clojure\n  (+ 1 2)\n  ```\n  "]]
           (get-in (gherkin "Feature: f\nScenario: s\nGiven a\n  \"\"\"\n  ```clojure\n  (+ 1 2)\n  ```\n  \"\"\"")
                   [2 1 2 1 3])))
    (is (ok? "Feature: f\nScenario: s\nGiven a\n  ```\n  du \"\"\" au milieu\n  ```")))

  (testing "les deux délimiteurs d'une doc string doivent correspondre"
    (is (ko? "Feature: f\nScenario: s\nGiven a\n  \"\"\"\n  txt\n  ```")))

  (testing "fins de ligne Windows"
    (is (ok? "Feature: f\r\nScenario: s\r\nGiven a\r\n")))

  (testing "espaces en fin de fichier, sans saut de ligne final : `steps` ne mange
  que des lignes entières, c'est la fin de SPEC qui absorbe le reste"
    (is (ok? "Feature: f\nScenario: s\nGiven a\n| x |\n| 1 |\n\n\n  "))
    (is (ok? "Feature: f\nScenario: s\nGiven a\t")))

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

(deftest cout-de-parsing-test
  (testing "le coût de parsing reste linéaire en nombre de scénarios.

  Deux règles tiennent cette linéarité, et toute évolution de la grammaire doit
  les respecter :
  - aucune production ne matche le vide à l'intérieur d'une répétition ;
  - un saut de ligne n'a qu'un seul consommateur possible — les blocs démarrent
    sur `<indent>`, jamais sur `<whitespace?>`, et `steps` est le seul à manger
    les lignes vides qui suivent un scénario.

  Avec deux consommateurs concurrents, le parseur GLL explore toutes les
  répartitions : 20 scénarios décrits partaient en OutOfMemoryError."
    (let [source (str "Feature: f\n"
                      (apply str (repeat 60 "Scenario: s\n  une narration\nGiven a\n\n")))
          debut  (System/nanoTime)
          ast    (gherkin source)
          ms     (long (/ (- (System/nanoTime) debut) 1e6))]
      (is (not (insta/failure? ast)))
      ;; ~50 ms en pratique ; le seuil ne détecte que le retour de l'explosion
      (is (< ms 2000) (str "60 scénarios parsés en " ms " ms")))))
