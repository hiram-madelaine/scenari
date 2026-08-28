(ns scenari.v2.step
  (:require [clojure.string :as string]
            [instaparse.core :as insta]
            [scenari.v2.parser :as parser]))

(defn- extract-params-as-args [params]
  (str "[state " (string/join " " (map-indexed (fn [idx _] (str "arg" idx)) params)) "]"))

(defn generate-step-fn
  "return a string representing a spexec macro call corresponding to the sentence step"
  [step-sentence]
  (let [{:keys [raw params]} step-sentence
        sentence-ast (parser/step raw)
        ;; ex-info veut une String : passer le :reason d'instaparse, qui est un
        ;; vecteur, faisait remonter une ClassCastException a la place du step manquant
        _ (when (insta/failure? sentence-ast)
            (throw (ex-info (str "Cannot parse the step sentence: " raw)
                            {:parsed-text step-sentence
                             :failure     (insta/get-failure sentence-ast)})))
        [_ [step-type] & sentence-elements] sentence-ast]
    (str (case step-type
           :given "(defgiven \""
           :and   "(defand \""
           :when  "(defwhen \""
           :then  "(defthen \""
           "(defwhen \"")
         (apply str (map (fn [[what? data]]
                           (case what?
                             :words data
                             :string "{string}"
                             :number "{number}"
                             "test")) sentence-elements))
         "\"  "
         (extract-params-as-args params)
         (case step-type
           :given "  (do \"setup or assert correct tested component state\"))"
           :when  "  (do \"something\"))"
           :then  "  (do \"assert the result of when step\"))"
           "  (do \"something\"))"))))
