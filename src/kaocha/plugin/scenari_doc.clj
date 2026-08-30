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
  "Les enfants non skippés. kaocha.plugin.filter ne marque que le nœud qui ne
  passe pas, pas ses enfants : il faut donc s'arrêter dessus, pas les tester
  un par un."
  [testable]
  (remove ::testable/skip (:kaocha.test-plan/tests testable)))

(defn selected-features
  "Les features scenari retenues par les filtres, chacune avec ses scénarios
  retenus dans `:kaocha.test-plan/tests`."
  [test-plan]
  (for [suite   (kept test-plan)
        feature (kept suite)
        :when   (= :kaocha.type/scenari-feature (::testable/type feature))]
    (assoc feature :kaocha.test-plan/tests (kept feature))))

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

(defn- step-html [{:keys [sentence-keyword sentence params]}]
  (str "<li><span class=\"kw\">" (esc (str/capitalize (name sentence-keyword))) "</span> "
       (esc sentence)
       (params-html params)
       "</li>"))

(defn- scenario-html [scenario]
  (str "<section class=\"scenario\" id=\"" (anchor (::testable/id scenario)) "\">"
       "<h3>" (esc (::testable/desc scenario)) "</h3>"
       (tags-html (:annotations scenario))
       (desc-html (:description scenario))
       "<ol class=\"steps\">" (apply str (map step-html (:steps scenario))) "</ol>"
       "</section>"))

(defn- feature-html [feature]
  (str "<section class=\"feature\" id=\"" (anchor (::testable/id feature)) "\">"
       "<h2>" (esc (::testable/desc feature)) "</h2>"
       (tags-html (feature-annotations feature))
       (desc-html (feature-description feature))
       (apply str (map scenario-html (:kaocha.test-plan/tests feature)))
       "</section>"))

(defn- toc-html [features]
  (str "<nav><h2>Sommaire</h2><ul>"
       (apply str
              (for [feature features]
                (str "<li><a href=\"#" (anchor (::testable/id feature)) "\">"
                     (esc (::testable/desc feature)) "</a><ul>"
                     (apply str
                            (for [scenario (:kaocha.test-plan/tests feature)]
                              (str "<li><a href=\"#" (anchor (::testable/id scenario)) "\">"
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
")

(defn document [features]
  (str "<!DOCTYPE html><html lang=\"fr\"><head><meta charset=\"utf-8\">"
       "<title>Features</title><style>" css "</style></head><body>"
       "<h1>Features</h1>"
       "<p>" (count features) " feature(s), "
       (reduce + (map (comp count :kaocha.test-plan/tests) features)) " scénario(s).</p>"
       (toc-html features)
       (apply str (map feature-html features))
       "</body></html>"))

(defplugin kaocha.plugin/scenari-doc
  "Génère la documentation HTML des scénarios sélectionnés, sans les exécuter."

  (cli-options [opts]
               (conj opts
                     [nil "--doc-html FILE" (str "Write the selected scenarios to FILE as an HTML "
                                                 "document (table of contents + one anchor per "
                                                 "scenario) instead of running them.")]))

  (config [config]
          (cond-> config
            (:doc-html (:kaocha/cli-options config))
            (assoc ::target-file (:doc-html (:kaocha/cli-options config)))))

  (post-load [test-plan]
             ;; ::target-file est une clé de config ordinaire :
             ;; `:kaocha.plugin.scenari-doc/target-file` dans tests.edn marche aussi
             (if-let [target (::target-file test-plan)]
               (let [features (selected-features test-plan)]
                 (io/make-parents target)
                 (spit target (document features))
                 (if (seq features)
                   (println "Wrote" target "-" (count features) "feature(s),"
                            (reduce + (map (comp count :kaocha.test-plan/tests) features)) "scenario(s).")
                   (output/warn "No scenario selected, " target " is empty."))
                 (update test-plan :kaocha.test-plan/tests
                         (partial mapv #(assoc % ::testable/skip true))))
               test-plan)))
