(ns scenari.v2.core
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [instaparse.core :as insta]
            [instaparse.transform :as insta-trans]
            [scenari.v2.parser :as parser]
            [scenari.v2.glue :as glue])
  (:import (java.io File)
           (org.apache.commons.io FileUtils)
           (java.util UUID)))


;; ------------------------
;;          LOAD
;; ------------------------

(defn cell
  "A table cell, trimmed and unescaped: gherkin escapes \\| \\\\ and \\n inside cells."
  [s]
  (string/replace (string/trim s) #"\\(.)"
                  (fn [[_ c]] (case c "n" "\n" c))))

(defn tab-params->params [[param-type [_ & headers] & rows]]
  (when (= :tab_params param-type)
    (let [param-names (map (comp keyword cell) headers)
          params-values (map (comp #(map cell %) rest) rows)]
      [{:type :table :val (mapv #(apply hash-map (interleave param-names %)) params-values)}])))

(defn doc-string->params [[param-type [_ content]]]
  (when (= :doc_string param-type)
    [{:type :doc-string :val (string/trim content)}]))

(defn sentence-params->params [[type val]] {:type :value :val (condp = type
                                                                    :number (read-string val)
                                                                    :string (str val))})

(defn file-from-fs-or-classpath [x]
  (let [r (io/resource x)
        f (when (and (instance? File x) (.exists x)) x)
        f-str (when (and (instance? String x) (.exists (io/as-file x))) x)]
    (io/as-file (or r f f-str))))

(defn get-feature-files [basedir]
  (letfn [(find-spec-files [basedir]
            (FileUtils/listFiles
              basedir
              (into-array ["story" "feature"])
              true                                          ;;recursive
              ))]
    (case (str (type basedir))
      "class java.lang.String" (if (.exists (File. ^String basedir))
                                 (find-spec-files (File. ^String basedir))
                                 (throw (RuntimeException. (str basedir " doesn't exists in path: " (System/getProperty "user.dir")))))
      "class java.io.File" (find-spec-files basedir))))

(defn find-sentence-params [sentence]
  (insta-trans/transform
    {:SENTENCE (fn [& s] (->> s
                              (filter (fn [[type _]] (#{:string :number} type)))
                              (mapv sentence-params->params)))}
    (parser/sentence sentence)))

(defmulti read-source
          (fn [path]
            (letfn [(file-or-dir [x]
                      (cond (.isFile x) :file
                            (.isDirectory x) :dir))]
              (if (instance? String path)
                (if-let [f (file-from-fs-or-classpath path)]
                  (file-or-dir f)
                  :feature-as-str)
                (if (instance? File path)
                  (file-or-dir path)
                  (throw (RuntimeException. (str "type " (type path) "for spec not accepted (only string or file)")))))))
          :default :file)

(defmethod read-source
  :dir
  [path]
  (doseq [spec-file (get-feature-files path)]
    (read-source spec-file)))

(defmethod read-source
  :file
  [path-or-source]
  (read-source (slurp (file-from-fs-or-classpath path-or-source))))

(defmethod read-source :feature-as-str [source] source)

(defn step->map [[_step-sentence [step-key] [_sentence sentence] data-param]]
  (merge {:sentence-keyword step-key
          :sentence         sentence
          :raw              (str (string/capitalize (name step-key)) " " sentence)}
         (when-let [params (into (find-sentence-params sentence)
                                 (or (tab-params->params data-param)
                                     (doc-string->params data-param)))]
           {:params params})))

(defn- node? [tag x] (and (vector? x) (= tag (first x))))

(defn- expand-scenario
  "Scenario outline: a scenario carrying an Examples table becomes one scenario
  per row, with the <placeholders> substituted everywhere inside it. Runs on the
  parse tree, before the transform resolves glues on the substituted sentences."
  [scenario]
  (if-let [[_ [_ & headers] & rows] (some #(when (node? :examples %) %) (rest scenario))]
    (let [base (into [:scenario] (remove #(node? :examples %)) (rest scenario))]
      (for [[_ & values] rows
            :let [params (zipmap (map #(str "<" (cell %) ">") headers)
                                 (map cell values))]]
        (walk/postwalk #(if (string? %) (reduce-kv string/replace % params) %) base)))
    [scenario]))

(defn- with-background
  "Background steps run before each scenario: splice them at the head of the
  scenario's own steps, so nothing downstream needs to know about backgrounds."
  [bg-steps scenario]
  (mapv (fn [node]
          (if (node? :steps node)
            (into [:steps] (concat bg-steps (rest node)))
            node))
        scenario))

(defn- normalize-scenarios
  "Pre-pass on the parse tree: splice in the Background and expand the scenario
  outlines, so the transform below only ever sees plain scenarios."
  [spec]
  (let [bg-steps (rest (second (some #(when (node? :background %) %) (rest spec))))]
    (into [] (comp (remove #(node? :background %))
                   (map (fn [node]
                          (if (node? :scenarios node)
                            (into [:scenarios]
                                  (comp (map #(with-background bg-steps %))
                                        (mapcat expand-scenario))
                                  (rest node))
                            node))))
          spec)))

(defn ->feature-ast [source {:keys [pre-run pre-scenario-run post-scenario-run default-scenario-state] :as _options} ns-feature]
  (let [ast (parser/gherkin source)
        _ (when (insta/failure? ast)
            (throw (ex-info (str "Cannot parse feature:\n" (print-str ast))
                            {:source source :failure ast})))
        ast (normalize-scenarios ast)
        feature (insta-trans/transform
                 {:SPEC              (fn [& s] (apply merge s))
                  :annotation        (fn [s] s)
                  :annotations       (fn [& s] {:annotations (set s)})
                  :narrative         (fn [& n] {:feature (string/join " " n)})
                  :description       (fn [& lines] {:description (string/join "\n" lines)})
                  :steps             (fn [& contents]
                                       {:steps (vec (map-indexed (fn [i content]
                                                                   (let [step (step->map content)]
                                                                     (-> step
                                                                         (assoc :order i)
                                                                         (assoc :glue (glue/find-glue-by-step-regex step ns-feature)))))
                                                                 contents))})
                  :scenario_sentence (fn [a] {:scenario-name a})
                  :scenario          (fn [& contents] (into {:id            (.toString (UUID/randomUUID))
                                                             :pre-run       (map #(assoc (meta %) :ref %) pre-scenario-run)
                                                             :post-run      (map #(assoc (meta %) :ref %) post-scenario-run)
                                                             :default-state (or default-scenario-state {})}
                                                            contents))
                  :scenarios         (fn [& contents] {:scenarios (into [] contents)
                                                       :pre-run   (map #(assoc (meta %) :ref %) pre-run)})}
                 ast)]
    (when (empty? (:scenarios feature))
      (throw (ex-info (str "Feature has no scenario. Lines whose keyword is not recognized "
                           "are parsed as free description:\n" (:description feature))
                      {:source source :feature feature})))
    feature))

;; ------------------------
;;          RUN
;; ------------------------

(defn run-step [step scenario-state]
  (binding [clojure.test/*report-counters* (ref clojure.test/*initial-report-counters*)]
    (let [f (get-in step [:glue :ref])
          params (cons scenario-state (mapv :val (get step :params)))]
      (try (let [result (apply f params)
                 state (last result)
                 any-fail? (> (:fail (deref clojure.test/*report-counters*)) 0)]
             (-> step
                 (assoc :input-state scenario-state)
                 (assoc :output-state state)
                 (assoc :status (if any-fail? :fail :success))))
           (catch Throwable e
             (-> step
                 (assoc :input-state scenario-state)
                 (assoc :exception e)
                 (assoc :status :fail)))))))

(defn run-steps [steps state [step & others]]
  (if-not step
    steps
    (let [{:keys [output-state status] :as step-result} (run-step step state)
          steps (map #(if (= (:order step-result) (:order %)) step-result %) steps)]
      (if (= status :fail)
        steps
        (recur steps output-state others)))))

(defn run-scenario [scenario]
  (let [default-state (:default-state scenario)
        pending-steps (map #(assoc % :status :pending) (:steps scenario))
        _ (doseq [{pre-run-fn :ref} (:pre-run scenario)]
            (pre-run-fn))
        result-steps (run-steps pending-steps default-state pending-steps)
        _ (doseq [{post-run-fn :ref} (:post-run scenario)]
            (post-run-fn))]
    (-> scenario
        (assoc :steps result-steps)
        (assoc :status (if (contains? (set (map :status result-steps)) :fail) :fail :success)))))

(defn run-scenarios [scenarios [scenario & others]]
  (if-not scenario
    scenarios
    (let [scenario-result (run-scenario scenario)
          scenarios (map #(if (= (:id %) (:id scenario)) scenario-result %) scenarios)]
      (recur scenarios others))))

(defn run-feature [feature]
  (let [{:keys [scenarios pre-run] :as feature-ast} (get (meta feature) :scenari/feature-ast)]
    (doseq [{pre-run-fn :ref} pre-run]
      (pre-run-fn))
    (let [scenarios (run-scenarios scenarios scenarios)]
      (-> feature-ast
          (assoc :scenarios scenarios)
          (assoc :status (if (contains? (set (map :status scenarios)) :fail) :fail :success))))))

(defn run-features
  ([] (apply run-features (filter #(some? (:scenari/feature-ast (meta %))) (vals (ns-interns *ns*)))))
  ([& features] (map run-feature features)))


;; ------------------------
;;          DEFINE
;; ------------------------
(defmacro deffeature [name feature & [options]]
  (let [feature# `~(eval feature)
        name# `~(if (symbol? name) name (eval name))
        source# (read-source feature#)
        feature-ast# `(->feature-ast ~source# ~options *ns*)]
    `(do
       (ns-unmap *ns* '~name#)
       (require '[scenari.v2.test])
       (t/deftest ~(vary-meta name# assoc
                              :scenari/raw-feature source#
                              :scenari/feature-ast feature-ast#
                              :scenari/feature-test true) [] (scenari.v2.test/run-features (var ~name#))))))


(defn re->symbol [re]
  (-> (str re)
      (string/replace #"\\\"\(\.\*\)\\\"" "param")
      (string/replace #" " "-")
      symbol))

;; TODO make a step evaluable as a standalone fun
;; TODO duplication, should be resolve with a macro
(defmacro defgiven [regex params & body]
  `(do (defn ~(-> (re->symbol regex)
                  (vary-meta assoc :step regex)) ~params (into [] [~@body]))
       (glue/invalidate-glues-cache!)))

(defmacro defand [regex params & body]
  `(do (defn ~(-> (re->symbol regex)
                  (vary-meta assoc :step regex)) ~params (into [] [~@body]))
       (glue/invalidate-glues-cache!)))

(defmacro defwhen [regex params & body]
  `(do (defn ~(-> (re->symbol regex)
                  (vary-meta assoc :step regex)) ~params (into [] [~@body]))
       (glue/invalidate-glues-cache!)))

(defmacro defthen [regex params & body]
  `(do (defn ~(-> (re->symbol regex)
                  (vary-meta assoc :step regex)) ~params (into [] [~@body]))
       (glue/invalidate-glues-cache!)))
