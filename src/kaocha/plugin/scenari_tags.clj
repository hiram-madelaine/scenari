(ns kaocha.plugin.scenari-tags
  "`--tags \"@a and not @b\"` : le filtrage par expression de tags de Cucumber.

  kaocha ne sait faire que des OU (`--focus-meta`/`--skip-meta` passent leur
  liste à un `some`), et son focus est abandonné pour tout le sous-arbre dès
  qu'un nœud matche - une feature taguée fait donc tourner tous ses scénarios.
  Ici l'expression est évaluée scénario par scénario, comme Cucumber l'évalue
  pickle par pickle.

  Seules les suites scenari sont concernées : `--tags` laisse tourner une suite
  clojure.test. Pour n'exécuter que les features, la combiner avec `--focus`."
  (:require [kaocha.output :as output]
            [kaocha.plugin :refer [defplugin]]
            [kaocha.testable :as testable])
  (:import [io.cucumber.tagexpressions Expression TagExpressionParser]))

(defn- scenario? [t] (= :kaocha.type/scenari-scenario (::testable/type t)))

(defn- scenari? [t]
  (contains? #{:kaocha.type/scenari-feature :kaocha.type/scenari-scenario}
             (::testable/type t)))

(defn- matches? [^Expression expr testable]
  ;; les tags sont stockés sans le @ (scenari.v2.core/tag-names), l'expression
  ;; le veut - `evaluate ["smoke"]` sur `@smoke` rend false
  (.evaluate expr (mapv #(str "@" %) (:annotations testable))))

(defn filter-testable
  "Marque `::testable/skip` les scénarios dont les tags ne satisfont pas `expr`,
  puis tout nœud scenari dont il ne reste que des enfants skippés - sans quoi le
  reporter annonce une feature vide."
  [expr testable]
  (if-let [tests (:kaocha.test-plan/tests testable)]
    (let [tests (mapv #(filter-testable expr %) tests)]
      (cond-> (assoc testable :kaocha.test-plan/tests tests)
        (and (seq tests) (every? scenari? tests) (every? ::testable/skip tests))
        (assoc ::testable/skip true)))
    (cond-> testable
      (and (scenario? testable) (not (matches? expr testable)))
      (assoc ::testable/skip true))))

(defplugin kaocha.plugin/scenari-tags
  "Filtre les scénarios scenari sur une expression de tags cucumber."

  (cli-options [opts]
               (conj opts
                     [nil "--tags EXPR" (str "Only run scenari scenarios whose gherkin tags match this "
                                             "cucumber tag expression, e.g. \"@smoke and not @wip\".")]))

  (config [config]
          (cond-> config
            (:tags (:kaocha/cli-options config))
            (assoc ::expression (:tags (:kaocha/cli-options config)))))

  (post-load [test-plan]
    ;; ::expression est une clé de config ordinaire : `:kaocha.plugin.scenari-tags/expression`
    ;; dans tests.edn marche aussi, ce qui la rend utilisable depuis kaocha.repl
             (if-let [s (::expression test-plan)]
               (let [expr (try (TagExpressionParser/parse s)
                               (catch Exception e
                                 (output/error-and-throw {:kaocha/early-exit 248} nil (.getMessage e))))
                     plan (update test-plan :kaocha.test-plan/tests
                                  (partial mapv #(filter-testable expr %)))]
                 (when-not (some #(and (scenario? %) (not (::testable/skip %)))
                                 (testable/test-seq plan))
                   (output/warn "--tags " s " did not match any scenario."))
                 plan)
               test-plan)))
