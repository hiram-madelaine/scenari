(ns scenari.v2.parser
  (:require [instaparse.core :as insta]))

(def kw-translations-data {:fr {:given    "Etant donné que " :when "Quand " :and ["Et " "Mais " "* "]
                                :then     "Alors " :scenario ["Scénario :" "Plan du scénario :" "Exemple :"]
                                :background "Contexte :"
                                :examples ["Exemples :" "Scénarios :"]
                                :narrative "Narrative: "
                                :as_a "En tant que "
                                :in_order_to " afin de "
                                :I_want_to " Je veux "
                                :so_that " afin de "}
                           :en {:given    "Given " :when "When " :and ["And " "But " "* "]
                                :then     "Then " :scenario ["Scenario:" "Scenario Outline:" "Scenario Template:" "Example:"]
                                :background "Background:"
                                :examples ["Examples:" "Scenarios:"]
                                :narrative "Narrative: "
                                :as_a "As a "
                                :in_order_to " in order to "
                                :I_want_to " I want to "
                                :so_that " so that "}})

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

(def gherkin (insta/parser
                      (str "
           SPEC = <whitespace?> <comment?> annotations? narrative? description? <whitespace?> <comment?> background? scenarios
           narrative          = <'Narrative: '|'Feature: '> <whitespace?> #'.*' <eol>? (as_a I_want_to in_order_to |
                                                                                       as_a I_want_to so_that | in_order_to as_a I_want_to |
                                                                                       as_a in_order_to I_want_to)?
           annotations        = (<whitespace?> annotation <whitespace?>)*
           annotation         = <'@'> #'\\w+'
           in_order_to        = <whitespace>? <'In order to '> #'.*' <eol>
           as_a               = <whitespace>? <'As a '> #'.*' <eol>
           I_want_to          = <whitespace>? <'I want to '> #'.*' <eol>
           so_that            = <whitespace>? <'So that '> #'.*' <eol>
           description        = description_line+
           <description_line> = <whitespace?> !keyword_prefix #'[^\\r\\n]+'
           <narrative_keyword>= 'As a ' | 'I want to ' | 'In order to ' | 'So that '
           <keyword_prefix>   = scenario_keyword | step_keywords | examples-keywords | narrative_keyword
                              | 'Feature:' | 'Narrative:' | 'Rule:' | background_keyword
                              | '@' | '#' | '|' | '\"\"\"' | '*'
           background         = <whitespace?> <background_keyword> <#'[^\\r\\n]*'> <eol> steps
           <background_keyword>= " (kw-translations :background) "
           scenarios          = (scenario <eol?> <eol?>)*
           <scenario_keyword> = " (kw-translations :scenario) "
           scenario           = <scenario_keyword> scenario_sentence <eol> steps examples?
           <comment>          = (comment_line whitespace?)*
           <comment_line>     = <whitespace*> <'#'> <sentence>
           steps              = (comment | <whitespace*> | step_sentence | <eol>)*
           given              = <" (kw-translations :given) ">
           when               = <" (kw-translations :when) ">
           then               = <" (kw-translations :then) ">
           and                = <" (kw-translations :and) ">
           <step_keywords>    = given | when | then | and
           <whitespace>       = #'\\s+'
           <space>            = ' '  | '\t'
           <eol>              = #'\r?\n'
           scenario_sentence  = #'.*'
           step_sentence      = step_keywords sentence (<eol> (tab_params | doc_string))?
           sentence           = #'.*'
           doc_string         = <whitespace?> <doc_delim> <eol> doc_content <whitespace?> <doc_delim>
           <doc_delim>        = '\"\"\"' | '```'
           doc_content        = #'(?:[^\"`]+|\"(?!\"\")|`(?!``))*'
           examples           = <whitespace?> examples-keywords <eol> header row* <eol?>
           <examples-keywords>= <" (kw-translations :examples) ">
           tab_params         = header row* <eol?>
           header             = <indent> <'|'> (column_name <'|'>)+
           <column_name>      = #'(?:[^|\\\\\r\n]|\\\\.)*'
           row                = <eol> <indent> <'|'> (value <'|'>)+
           <value>            = #'(?:[^|\\\\\r\n]|\\\\.)*'
           <indent>           = <#'[ \t]*'>
           word               = #'[\\p{L}$€]+'
           number             = #'[0-9]+'
           ")))

(def sentence (insta/parser
                (str "SENTENCE         = <whitespace>? (words | data_group | parameter)* <eol>?
                             words            = #'[a-zA-Z./\\_\\-\\'èéêàûù ]+'
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
                             words            = #'[a-zA-Z./\\_\\-\\'èéêàûù ]+'
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
