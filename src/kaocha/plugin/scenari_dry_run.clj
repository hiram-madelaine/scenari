(ns kaocha.plugin.scenari-dry-run
  "`--dry-run` : vérifie que chaque step des scénarios sélectionnés a bien un
  glue, sans rien exécuter.

  Le glue est résolu au parse (`scenari.v2.core/pickle-step->map` pose
  `:glue nil` quand rien ne matche), donc tout est déjà dans le test-plan : il
  suffit de le parcourir. Sans ça un step non défini n'explose qu'à
  l'exécution, sur un `(apply nil ...)`, *après* les steps précédents et leurs
  effets de bord.

  Sortie non nulle s'il manque un glue, avec le squelette à coller pour
  chacun. Comme `scenari-doc`, à lister après `:kaocha.plugin/scenari-tags`
  pour ne vérifier que ce qui aurait tourné - et le walk est le sien, les deux
  plugins lisent le même arbre filtré."
  (:require [clojure.string :as str]
            [kaocha.output :as output]
            [kaocha.plugin :refer [defplugin]]
            [kaocha.plugin.scenari-doc :as doc]
            [kaocha.testable :as testable]))

(defn undefined-steps
  "`[feature scenario step]` pour chaque step sans glue, dans l'ordre de l'arbre."
  [test-plan]
  (for [feature  (doc/selected-features test-plan)
        scenario (::doc/scenarios feature)
        step     (:steps scenario)
        :when    (nil? (:glue step))]
    [(::testable/desc feature) (::testable/desc scenario) step]))

(defn report
  "Les steps manquants groupés par scénario. Le squelette à coller, lui, est
  déjà imprimé par `find-glue-by-step-regex` au moment du parse (événement
  `:missing-step`) - ce qui manque là-haut, c'est *où* le step est utilisé."
  [missing]
  (str/join
   "\n"
   (for [group (partition-by (juxt first second) missing)
         :let  [[feature scenario _] (first group)]
         line  (cons (str "  " feature " > " scenario)
                     (map (fn [[_ _ step]] (str "    " (:raw step))) group))]
     line)))

(defplugin kaocha.plugin/scenari-dry-run
  "Vérifie que tous les steps ont un glue, sans exécuter les scénarios."

  (cli-options [opts]
               (conj opts
                     [nil "--dry-run" "Check that every selected step resolves a step definition, without running anything."]))

  (config [config]
          (cond-> config
            (:dry-run (:kaocha/cli-options config)) (assoc ::enabled? true)))

  (post-load [test-plan]
             (if (::enabled? test-plan)
               (let [features  (doc/selected-features test-plan)
                     scenarios (mapcat ::doc/scenarios features)
                     steps     (mapcat :steps scenarios)
                     missing   (undefined-steps test-plan)
                     plan      (update test-plan :kaocha.test-plan/tests
                                       (partial mapv #(assoc % ::testable/skip true)))]
                 (println (format "Dry run: %d feature(s), %d scenario(s), %d step(s), %d undefined."
                                  (count features) (count scenarios) (count steps) (count missing)))
                 (when (seq missing)
                   (println)
                   (println (report missing))
                   (println)
                   ;; le runner sort sur ce code : le dry run échoue en CI
                   (output/error-and-throw {:kaocha/early-exit 1} nil
                                           (count missing) " step(s) without a step definition."))
                 plan)
               test-plan)))
