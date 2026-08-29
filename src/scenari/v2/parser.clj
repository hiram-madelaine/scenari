(ns scenari.v2.parser
  (:require [instaparse.core :as insta]))

(def kw-translations-data
  "Step keywords only: the feature file itself is parsed by the official gherkin
  parser, which carries the ~70 languages. These two grammars parse a *step
  sentence* - the glue-facing half of scenari, which gherkin does not cover."
  {:fr {:given "Etant donn\u00e9 que " :when "Quand " :and ["Et " "Mais " "* "] :then "Alors "}
   :en {:given "Given " :when "When " :and ["And " "But " "* "] :then "Then "}})

(defn- kw-translations
  "return a string consisting of appending the keyword separated by | for inclusion in gherkin grammar.
  A translation is either a string or a vector of synonyms."
  ([kw data]
   (->> (vals data)
        (mapcat (fn [lang] (let [t (get lang kw)] (if (vector? t) t [t]))))
        (remove nil?)
        (distinct)
        (map #(str "'" % "'"))
        (interpose "|")
        (apply str)))
  ([kw]
   (kw-translations kw kw-translations-data)))

(def ^:private words-regex
  "Un mot est tout ce qui n'ouvre pas un token : chiffres, guillemets, chevrons,
  crochets et accolades restent lisibles comme number, string, parameter, vector
  et map. Le reste - ponctuation, accents - appartient aux mots."
  "#'[^0-9\"<>{}$\\[\\]\\r\\n]+'")

(def sentence (insta/parser
               (str "SENTENCE         = <whitespace>? (words | data_group | parameter)* <eol>?
                             words            = " words-regex "
                             <parameter_name> = #'[a-zA-Z\"./\\_\\- ]+'
                             parameter        = <'<'> parameter_name <'>'> | <'${'> parameter_name <'}'>
                             string           = <'\"'> #'[^\"]*' <'\"'>
                             number           = #'\\d+'
                             <data_group>     = string | number | map | vector
                             map              = #'\\{[a-zA-Z0-9\\-:,./\\\" ]+\\}'
                             elements         = (#'\".+\"|[0-9]+' <whitespace>?)*
                             vector           = <'['> elements <']'>
                             <whitespace>     = #'\\s+'
                             <value>          = #'[a-zA-Z0-9+ ]*'
                             whitespace       = #'\\s+'
                             eol              = #'\r?\n'")))

(def step (insta/parser
           (str "STEP             = <whitespace?> step_keyword (words | data_group | parameter)* <eol>?
                             given            = <" (kw-translations :given) ">
                             when             = <" (kw-translations :when) ">
                             then             = <" (kw-translations :then) ">
                             and              = <" (kw-translations :and) ">
                             words            = " words-regex "
                             <parameter_name> = #'[a-zA-Z\"./\\_\\- ]+'
                             parameter        = <'<'> parameter_name <'>'> | <'${'> parameter_name <'}'>
                             string           = <'\"'> #'[^\"]*' <'\"'>
                             number           = #'\\d+'
                             <data_group>     = string | number | map | vector
                             map              = #'\\{[a-zA-Z0-9\\-:,./\\\" ]+\\}'
                             elements         = (#'\".+\"|[0-9]+' <whitespace>?)*
                             vector           = <'['> elements <']'>
                             <step_keyword>   = given | when | then | and
                             <whitespace>     = #'\\s+'
                             <value>          = #'[a-zA-Z0-9+ ]*'
                             whitespace       = #'\\s+'
                             eol              = #'\r?\n'")))
