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
                 ::post-run              post-run
                 ::parallel?             (::parallel? testable)})]
    (assoc testable :kaocha.test-plan/tests tests)))

(defn- run-testables-parallel
  "Fan-out feature-level, une feature par thread. `pmap` conserve l'ordre des
  resultats, comme `testable/run-testables` -- rien a changer cote agregation.

  ponytail: `pmap` chunke par paquets de ncpus+2 ; si des features tres
  inegales en duree desequilibrent la fin de suite, passer a un pool dedie."
  [tests test-plan]
  (vec (pmap #(testable/run-testable % test-plan) tests)))

(defmethod testable/-run :kaocha.type/scenari [testable test-plan]
  (let [tests    (:kaocha.test-plan/tests testable)
        results  (if (::parallel? testable)
                   (run-testables-parallel tests test-plan)
                   (testable/run-testables tests test-plan))
        testable (-> testable
                     (dissoc :kaocha.test-plan/tests)
                     (assoc :kaocha.result/tests results))]
    testable))

(defn- run-feature*
  "Corps de :kaocha.type/scenari-feature, sans souci de sortie -- appele
  directement en sequentiel, ou sous binding de *out* en parallele."
  [testable test-plan]
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
       testable))))

(defmethod testable/-run :kaocha.type/scenari-feature [testable test-plan]
  (if (::parallel? testable)
    ;; en parallele, plusieurs features impriment en meme temps sur *out* :
    ;; on capture la sortie de celle-ci et on l'ecrit d'un bloc a la fin,
    ;; pour ne pas entrelacer ses lignes avec celles des autres.
    (let [sw     (java.io.StringWriter.)
          result (binding [*out* sw] (run-feature* testable test-plan))]
      (print (str sw))
      (flush)
      result)
    (run-feature* testable test-plan)))

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
