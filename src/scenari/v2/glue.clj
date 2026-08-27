(ns scenari.v2.glue
  (:require [clojure.test :as t]
            [clojure.string :as string]))

(def ^:private glues-cache (atom nil))

(defn invalidate-glues-cache!
  "Invalidate the `all-glues` cache. Called by the step definition macros: a glue
   can appear in an already loaded namespace, which the namespace count alone
   cannot detect."
  []
  (reset! glues-cache nil))

(defn all-glues
  "Find all glue functions (step definitions) in all loaded namespaces.

   Resolving the glue of every step of every feature means scanning all the public
   vars of all the loaded namespaces once per step, which dominates the loading
   time of a large feature suite. The result is therefore memoized, and recomputed
   as soon as a namespace is loaded or a step is (re)defined."
  []
  (let [nss (all-ns)
        k   (count nss)
        {:keys [ns-count glues]} @glues-cache]
    (if (= ns-count k)
      glues
      (let [computed (into []
                           (comp (mapcat #(vals (ns-publics %)))
                                 (map #(assoc (meta %) :ref %))
                                 (filter #(contains? % :step)))
                           nss)]
        (reset! glues-cache {:ns-count k :glues computed})
        computed))))

(defn ns-proximity-score
  "Calculate proximity score between two namespaces based on common segments"
  [ns-glue ns-feature]
  (loop [[ns-glue & child-ns-glue] (string/split ns-glue #"\.")
         [ns-feature & child-ns-feature] (string/split ns-feature #"\.")
         score 0]
    (if (or (not ns-feature) (not ns-glue) (not= ns-glue ns-feature))
      score
      (recur child-ns-glue child-ns-feature (inc score)))))

(defn find-closest-glues-by-ns
  "Find glues with closest namespace proximity to the feature namespace"
  [matched-glues ns-feature]
  (let [[_score closest-glues-by-ns] (->> matched-glues
                                          (map #(hash-map (ns-proximity-score (str ns-feature) (str (:ns %))) [%]))
                                          (apply merge-with into)
                                          (apply max-key key))]
    closest-glues-by-ns))

(defn- sentence-with-tokens->regex
  "Replace all value token like {string} and {number} in sentence. Returns a regex"
  [s]
  (-> s
      (string/replace #"\{string\}" "\"([^\"]*)\"")
      (string/replace #"\{number\}" "(\\\\d+)")
      re-pattern))

(defn find-glue-by-step-regex
  "Return the tuple of fn/regex as a vector that match the step-sentence"
  ([step ns-feature] (find-glue-by-step-regex step ns-feature (all-glues)))
  ([step ns-feature glues]
   (let [{:keys [sentence]} step
         matched-glues (filter #(seq (re-matches (sentence-with-tokens->regex (:step %)) sentence)) glues)]
     (cond
       (empty? matched-glues)
       (do (t/do-report {:type :missing-step, :step-sentence step})
           nil)

       (> (count matched-glues) 1)
       (let [[matched-glue & conflicts] (find-closest-glues-by-ns matched-glues ns-feature)]
         (if conflicts
           (throw (RuntimeException.
                    (str (+ (count conflicts) 1)
                         " matching functions were found for the following step sentence:\n "
                         sentence
                         ", please refine your regexes that match: \n"
                         matched-glue "\n"
                         (string/join "\n" conflicts))))
           (assoc matched-glue
             :warning (str (count matched-glues) " matching functions were found for this step sentence"))))

       :else (first matched-glues)))))
