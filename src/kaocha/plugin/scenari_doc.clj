(ns kaocha.plugin.scenari-doc
  "`--doc-html FICHIER` : la documentation HTML des scénarios sélectionnés.

  Un seul document : sommaire cliquable, une section par feature, une ancre par
  scénario. Il est écrit depuis le test-plan, donc *après* les filtres - ce qui
  aurait tourné est exactement ce qui est documenté. Le plugin doit pour cela
  être listé après `:kaocha.plugin/scenari-tags` dans `:kaocha/plugins`, les
  filtres de kaocha (`--focus`, `--skip-meta`) étant eux toujours devant.

  Rien n'est exécuté : c'est de la doc statique, pas un rapport de run. Les
  suites sont marquées `::testable/skip` une fois le fichier écrit."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [kaocha.output :as output]
            [kaocha.plugin :refer [defplugin]]
            [kaocha.testable :as testable]))

;; les clés que kaocha.type.scenari pose sur la feature - écrites en toutes
;; lettres pour ne pas charger ce namespace juste pour deux mots-clés
(def ^:private feature-annotations :kaocha.type.scenari/annotations)
(def ^:private feature-description :kaocha.type.scenari/description)

(defn- esc [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(defn- anchor [id]
  (str/replace (subs (str id) 1) #"[^A-Za-z0-9]+" "-"))

(defn- kept
  "Les enfants non skippés, que le nœud vienne du test-plan (`--doc-html`) ou du
  résultat du run (`--doc-report`) - les deux arbres ont la même forme, sous
  deux clés. kaocha.plugin.filter ne marque que le nœud qui ne passe pas, pas
  ses enfants : il faut donc s'arrêter dessus, pas les tester un par un."
  [testable]
  (remove ::testable/skip (or (:kaocha.test-plan/tests testable)
                              (:kaocha.result/tests testable))))

(defn selected-features
  "Les features scenari retenues par les filtres, chacune avec ses scénarios
  retenus sous `::scenarios`."
  [tree]
  (for [suite   (kept tree)
        feature (kept suite)
        :when   (= :kaocha.type/scenari-feature (::testable/type feature))]
    (assoc feature ::scenarios (kept feature))))

;; ------------------------
;;         RENDU
;; ------------------------

(defn- tags-html [annotations]
  (when (seq annotations)
    (str "<p class=\"tags\">" (esc (str/join " " (map #(str "@" %) (sort annotations)))) "</p>")))

(defn- desc-html [description]
  (when-not (str/blank? description)
    (str "<pre class=\"desc\">" (esc description) "</pre>")))

(defn- table-html [rows]
  ;; l'ordre des colonnes suit celui des clés de la ligne, comme le rendu terminal
  (let [headers (keys (first rows))]
    (str "<table><thead><tr>"
         (apply str (for [h headers] (str "<th>" (esc (name h)) "</th>")))
         "</tr></thead><tbody>"
         (apply str (for [row rows]
                      (str "<tr>" (apply str (for [h headers] (str "<td>" (esc (get row h "")) "</td>"))) "</tr>")))
         "</tbody></table>")))

(defn- params-html
  "Les params blocs - docstring et datatable. Les params valeurs sont déjà dans
  la phrase du step."
  [params]
  (apply str (for [{:keys [type val]} params]
               (case type
                 :doc-string (str "<pre class=\"docstring\">" (esc val) "</pre>")
                 :table      (table-html val)
                 nil))))

;; `:status` n'existe qu'après un run (`--doc-report`) : sans lui le document
;; est la même doc statique, sans classe ni pastille
(defn- status-class [status] (if status (str " " (name status)) ""))

(defn- badge [status]
  (when status
    (str "<span class=\"badge " (name status) "\">" (esc (name status)) "</span>")))

(defn- error-html [^Throwable e]
  (when e
    (str "<pre class=\"error\">" (esc (or (ex-message e) (str e))) "</pre>")))

(defn- step-html [{:keys [sentence-keyword sentence params status exception]}]
  (str "<li class=\"step" (status-class status) "\">"
       "<span class=\"kw\">" (esc (str/capitalize (name sentence-keyword))) "</span> "
       (esc sentence)
       (params-html params)
       (error-html exception)
       "</li>"))

(defn- scenario-html [scenario]
  (str "<section class=\"scenario" (status-class (:status scenario))
       "\" id=\"" (anchor (::testable/id scenario)) "\">"
       "<h3>" (esc (::testable/desc scenario)) " " (badge (:status scenario)) "</h3>"
       (tags-html (:annotations scenario))
       (desc-html (:description scenario))
       "<ol class=\"steps\">" (apply str (map step-html (:steps scenario))) "</ol>"
       "</section>"))

(defn- feature-status [feature]
  (let [statuses (set (map :status (::scenarios feature)))]
    (cond (statuses :fail)    :fail
          (statuses :success) :success)))

(defn- feature-html [feature]
  (str "<section class=\"feature" (status-class (feature-status feature))
       "\" id=\"" (anchor (::testable/id feature)) "\">"
       "<h2>" (esc (::testable/desc feature)) " " (badge (feature-status feature)) "</h2>"
       (tags-html (feature-annotations feature))
       (desc-html (feature-description feature))
       (apply str (map scenario-html (::scenarios feature)))
       "</section>"))

(defn- toc-html [features]
  (str "<nav><h2>Sommaire</h2><ul>"
       (apply str
              (for [feature features]
                (str "<li class=\"" (str/trim (status-class (feature-status feature))) "\">"
                     "<a href=\"#" (anchor (::testable/id feature)) "\">"
                     (esc (::testable/desc feature)) "</a><ul>"
                     (apply str
                            (for [scenario (::scenarios feature)]
                              (str "<li class=\"" (str/trim (status-class (:status scenario))) "\">"
                                   "<a href=\"#" (anchor (::testable/id scenario)) "\">"
                                   (esc (::testable/desc scenario)) "</a></li>")))
                     "</ul></li>")))
       "</ul></nav>"))

(def ^:private css "
body{font:16px/1.5 system-ui,sans-serif;max-width:52rem;margin:2rem auto;padding:0 1rem;color:#222}
h1{font-size:1.6rem} h2{font-size:1.3rem;margin-top:2rem} h3{font-size:1.05rem;margin-bottom:.2rem}
nav{background:#f6f6f6;padding:.5rem 1rem;border-radius:4px}
nav ul{list-style:none;padding-left:1rem} nav>ul{padding-left:0}
a{color:#0a58ca;text-decoration:none} a:hover{text-decoration:underline}
.feature{border-top:1px solid #ddd}
.scenario{margin:1rem 0 1rem 1rem}
.tags{color:#0a7d7d;font-family:monospace;margin:.2rem 0}
.desc,.docstring{color:#555;background:#f6f6f6;padding:.4rem .6rem;white-space:pre-wrap;font-size:.9rem}
.steps{list-style:none;padding-left:0} .steps li{margin:.15rem 0}
.kw{color:#0a58ca;font-weight:600}
table{border-collapse:collapse;margin:.4rem 0;font-size:.9rem}
th,td{border:1px solid #ccc;padding:.15rem .5rem;text-align:left}
.badge{font-size:.7rem;text-transform:uppercase;padding:.1rem .4rem;border-radius:3px;vertical-align:middle;color:#fff}
.badge.success{background:#1a7f37} .badge.fail{background:#b3261e} .badge.pending{background:#888}
.scenario.fail{border-left:3px solid #b3261e;padding-left:.7rem}
.scenario.success{border-left:3px solid #1a7f37;padding-left:.7rem}
.step.fail{color:#b3261e} .step.pending{color:#999}
.error{color:#b3261e;background:#fdf0ef;padding:.4rem .6rem;white-space:pre-wrap;font-size:.85rem}
nav li.fail>a{color:#b3261e}
")

(defn- counts [features]
  (let [scenarios (mapcat ::scenarios features)]
    (str (count features) " feature(s), " (count scenarios) " scénario(s)"
         (when-let [failed (seq (filter #(= :fail (:status %)) scenarios))]
           (str ", " (count failed) " en échec"))
         ".")))

(defn document [features]
  (str "<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"utf-8\">"
       "<title>Features</title><style>" css "</style></head><body>"
       "<h1>Features</h1>"
       "<p>" (counts features) "</p>"
       (toc-html features)
       (apply str (map feature-html features))
       "</body></html>"))

(defn- write! [target features]
  (io/make-parents target)
  (spit target (document features))
  (if (seq features)
    (println "Wrote" target "-" (counts features))
    (output/warn "No scenario selected, " target " is empty.")))

(defplugin kaocha.plugin/scenari-doc
  "Génère la documentation HTML des scénarios sélectionnés, sans les exécuter."

  (cli-options [opts]
               (-> opts
                   (conj [nil "--doc-html FILE" (str "Write the selected scenarios to FILE as an HTML "
                                                     "document (table of contents + one anchor per "
                                                     "scenario) instead of running them.")])
                   (conj [nil "--doc-report FILE" (str "Same document, but run the scenarios first and "
                                                       "annotate it with their result.")])))

  (config [config]
          (let [{:keys [doc-html doc-report]} (:kaocha/cli-options config)]
            (cond-> config
              doc-html   (assoc ::target-file doc-html)
              doc-report (assoc ::report-file doc-report))))

  (post-load [test-plan]
             ;; ::target-file est une clé de config ordinaire :
             ;; `:kaocha.plugin.scenari-doc/target-file` dans tests.edn marche aussi
             (if-let [target (::target-file test-plan)]
               (do (write! target (selected-features test-plan))
                   ;; doc statique : on documente, on n'exécute pas
                   (update test-plan :kaocha.test-plan/tests
                           (partial mapv #(assoc % ::testable/skip true))))
               test-plan))

  (post-run [result]
            (when-let [target (::report-file result)]
              (write! target (selected-features result)))
            result))
