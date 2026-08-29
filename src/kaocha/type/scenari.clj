(ns kaocha.type.scenari
  (:require [clojure.string :as string]
            [clojure.test :as t]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.tools.namespace.find :as ns-find]
            [kaocha.testable :as testable]
            [kaocha.hierarchy :as hierarchy]
            [kaocha.repl :as krepl]
            [scenari.v2.core :as v2]
            [scenari.v2.core :as sc]
            [scenari.v2.test]))

(s/def :kaocha.type/scenari (s/keys :req [:kaocha/source-paths
                                          :kaocha/test-paths]))

(defn path->file "Looking path from resource or a file in file system" [path]
  (or (io/file (io/resource path))
      (io/file path)))

(defn find-features-meta-in-dir [path]
  (->> path
       path->file
       ns-find/find-namespaces-in-dir
       (map #(ns-publics (symbol %)))
       (mapcat #(map meta (vals %)))
       (filter #(:scenari/raw-feature %))))

(defn path->id [path]
  (-> path
      (str/replace #"/" ".")
      (str/replace #"_" "-")
      (str/replace #" " "_")
      (str/replace #"\.feature$" "")))

(defn ->id [s]
  (-> s
      str/trim
      (str/replace #"/" ".")
      (str/replace #"_" "-")
      (str/replace #" " "-")))

(defn scenario->id [scenario]
  (-> (:scenario-name scenario)
      str/trim
      (str/replace #" " "-")))

(defn scenario->testable [document scenario]
  (merge scenario
         {::testable/type :kaocha.type/scenari-scenario
          ::testable/id   (keyword (scenario->id scenario))
          ;; gherkin @annotations of the scenario, so `--focus-meta`/`--skip-meta`
          ;; also work one level below the feature
          ::testable/meta (zipmap (map keyword (:annotations scenario)) (repeat true))
          ::testable/desc (or (:scenario-name scenario) "")
          ::feature       (keyword (path->id (str (:project-directory document) (:file document))))
          ::file          (str (:project-directory document) (:file document))}))

(defn- with-unique-ids
  "Kaocha addresses a leaf by its id, and every row of an Examples table gives a
  scenario with the same name as soon as that name carries no <placeholder> -
  the common case. Number the repeats, so --focus can reach a single row and the
  report does not show N indistinguishable leaves."
  [testables]
  (second (reduce (fn [[seen acc] testable]
                    (let [id (::testable/id testable)
                          n  (inc (get seen id 0))]
                      [(assoc seen id n)
                       (conj acc (cond-> testable
                                   (> n 1) (assoc ::testable/id
                                                  (keyword (str (name id) "-" n)))))]))
                  [{} []]
                  testables)))

(defn- require-all-ns [paths]
  (->> paths
       (map path->file)
       (mapcat ns-find/find-namespaces-in-dir)
       (apply require)))

(defmethod testable/-load :kaocha.type/scenari [testable]
  (require-all-ns (::glue-paths testable))
  (let [tests (for [test-path (:kaocha/test-paths testable)
                    {{:keys [feature scenarios pre-run post-run annotations description]} :scenari/feature-ast
                     feature-content                                 :scenari/raw-feature
                     :as                                             feature-meta} (find-features-meta-in-dir test-path)]
                {::testable/type         :kaocha.type/scenari-feature
                 ::testable/id           (keyword (str (:ns feature-meta)) (str (:name feature-meta)))
                 ;; allows `--focus <ns>` to select every feature of a namespace
                 ::testable/aliases     [(keyword (str (:ns feature-meta)))]
                 ;; var metadata (deffeature ^:tag ...) + gherkin @annotations,
                 ;; so `--focus-meta`/`--skip-meta` work on features
                 ::testable/meta         (merge (dissoc feature-meta :scenari/raw-feature :scenari/feature-ast :test)
                                                (zipmap (map keyword annotations) (repeat true)))
                 ::testable/desc         feature
                 :kaocha.test-plan/tests (with-unique-ids (map #(scenario->testable feature-content %) scenarios))
                 ::annotations           annotations
                 ::description           description
                 ::pre-run               pre-run
                 ::post-run              post-run})]
    (assoc testable :kaocha.test-plan/tests tests)))

(defn- parallel-tagged? [testable]
  (boolean (get (::testable/meta testable) :parallel)))

(defn- run-testables-tag-parallel
  "Une feature @parallel tourne en arriere-plan (future) sans bloquer les
  suivantes ; toute feature non taguee reste synchrone a sa place dans la
  liste, comme aujourd'hui -- l'ordre des resultats est preserve.

  ponytail: pas de limite au nombre de futures simultanes ; ajouter un pool
  borne seulement si un jour beaucoup de features sont taguees a la fois."
  [tests test-plan]
  (->> tests
       (mapv (fn [t] (if (parallel-tagged? t)
                       (future (testable/run-testable t test-plan))
                       (testable/run-testable t test-plan))))
       (mapv #(cond-> % (future? %) deref))))

(defmethod testable/-run :kaocha.type/scenari [testable test-plan]
  (let [results  (run-testables-tag-parallel (:kaocha.test-plan/tests testable) test-plan)
        testable (-> testable
                     (dissoc :kaocha.test-plan/tests)
                     (assoc :kaocha.result/tests results))]
    testable))

(defmethod testable/-run :kaocha.type/scenari-feature [testable test-plan]
  ;; une feature @parallel tourne pendant qu'une autre est encore en train
  ;; d'imprimer sa propre sortie -- bufferiser systematiquement evite tout
  ;; entrelacement, meme quand rien n'est tagge (le cout est negligeable).
  (let [sw     (java.io.StringWriter.)
        result (binding [*out* sw]
                 (t/do-report {:type        :begin-feature
                               :feature     (:kaocha.testable/desc testable)
                               :annotations (::annotations testable)
                               :description (::description testable)})
                 (sc/run-hooks
                  {:pre-run (::pre-run testable) :post-run (::post-run testable)}
                  (fn []
                    (let [results (testable/run-testables (:kaocha.test-plan/tests testable) test-plan)
                          testable (-> testable
                                       (dissoc :kaocha.test-plan/tests)
                                       (assoc :kaocha.result/tests results))]
                      (t/do-report {:type :end-feature})
                      testable))))]
    (print (str sw))
    (flush)
    result))

(defmethod testable/-run :kaocha.type/scenari-scenario [testable test-plan]
  (t/do-report {:type :begin-scenario :scenario testable})
  (let [testable (sc/run-scenario testable)]
    (doseq [step (:steps testable)]
      ;; every step is reported, :pending ones included, so the steps skipped
      ;; after a failure still show up
      (t/do-report {:type :begin-step :step step})
      (when (= :fail (:status step))
        (t/do-report {:type :step-failed :exception (:exception step)})))
    (-> testable
        (merge {:kaocha.result/count 1
                :kaocha.result/pass  (if (= (:status testable) :success) 1 0)
                :kaocha.result/fail  (if (= (:status testable) :fail) 1 0)}))))

(defmethod testable/-run :kaocha.type/scenari-step [testable test-plan]
  (let [results [(v2/run-step {} testable)]
        testable (-> testable
                     (dissoc :kaocha.test-plan/tests)
                     (assoc :kaocha.result/pass results))]
    testable))

(s/def ::glue-paths (s/coll-of string?))

(s/def :kaocha.type/scenari (s/keys :req [:kaocha/source-paths
                                          :kaocha/test-paths
                                          ::glue-paths]))

(s/def :kaocha.type/scenari-feature any?)
(s/def :kaocha.type/scenari-scenario any?)
(s/def :kaocha.type/scenari-step any?)

(hierarchy/derive! ::begin-feature :kaocha/begin-group)
(hierarchy/derive! ::end-feature :kaocha/end-group)

(hierarchy/derive! ::begin-scenario :kaocha/begin-test)
(hierarchy/derive! ::end-scenario :kaocha/end-test)

(hierarchy/derive! :kaocha.type/scenari :kaocha.testable.type/suite)
(hierarchy/derive! :kaocha.type/scenari-feature :kaocha.testable.type/group)
(hierarchy/derive! :kaocha.type/scenari-scenario :kaocha.testable.type/leaf)

(comment
  (in-ns 'kaocha.type.scenari)
  (krepl/run :scenario)
  (krepl/run :unit)

  (krepl/run {:config-file "tests.edn"})

  (krepl/test-plan)

  (krepl/test-plan {:tests [{:id                           :scenario
                             :type                         :kaocha.type/scenari
                             :kaocha/source-paths          ["src"]
                             :kaocha/test-paths            ["test/scenari/v2"]
                             :scenari.v2.kaocha/glue-paths ["test/scenari/v2"]}]}))
