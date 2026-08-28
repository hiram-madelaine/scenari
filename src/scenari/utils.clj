(ns scenari.utils
  (:require [clojure.string :as string]
            [kaocha.output :as output]))

(defn contextual-eval [ctx expr]
  (eval
   `(let [~@(mapcat (fn [[k v]] [k `'~v]) ctx)]
      ~expr)))

(defmacro local-context []
  (let [symbols (keys &env)]
    (zipmap (map (fn [sym] `(quote ~sym)) symbols) symbols)))

(defn readr [prompt exit-code]
  (let [input (clojure.main/repl-read prompt exit-code)]
    (if (= input ::tl)
      exit-code
      input)))

(defmacro break-with-repl []
  `(clojure.main/repl
    :prompt #(print "debug=> ")
    :read readr
    :eval (partial contextual-eval (local-context))))

(defn get-whole-in
  ""
  ([tree ks]
   (get-whole-in tree ks nil))
  ([tree ks default]
   (if (or (empty? ks) (nil? ks))
     (if (empty? tree)
       default
       tree)
     (if (keyword? (first tree))
       ;;mono node root tree
       (if (= (first tree) (first ks))
         (if (empty? (rest ks))
           tree
           (recur (rest tree) (rest ks) default))
         default)
       ;;multi nodes root tree
       (recur (mapcat vector
                      (filter (fn [node]
                                (if (keyword? (first node))
                                  (= (first node) (first ks))
                                  false)) tree))
              (rest ks)
              default)))))

(defn get-in-tree
  ""
  ([tree ks]
     (get-in-tree tree ks nil))
  ([tree ks default]
     (if (or (empty? ks) (nil? ks))
       (if (empty? tree)
         default
         tree)
       (if (keyword? (first tree))
         ;;mono node root tree
         (if (= (first tree) (first ks))
           (recur (rest tree) (rest ks) default)
           default)
         ;;multi nodes root tree
         (recur (mapcat (fn [node]
                          ;;remove first element and add to the
                          (rest node))
                        (filter (fn [node]
                                  (if (keyword? (first node))
                                    (= (first node) (first ks))
                                    false)) tree))
                (rest ks)
                default)))))

(def ^:private ansi-codes
  {:reset "0" :bold "1" :black "30" :red "31" :green "32" :yellow "33"
   :blue "34" :purple "35" :cyan "36" :white "37" :grey "90"})

(defn ansi-code
  "Escape sequence for a color key, or for a vector of them ([:bold :cyan]).
  Empty string when colored output is off, so it stays safe to interpolate.
  Honours kaocha's --color/--no-color through kaocha.output/*colored-output*."
  [color]
  (if output/*colored-output*
    (format "\u001b[%sm" (->> (if (sequential? color) color [color])
                              (map #(get ansi-codes % "0"))
                              (string/join ";")))
    ""))

(defn color-str [color & xs]
  (str (ansi-code color) (apply str xs) (ansi-code :reset)))

(def digits-only? (re-pattern #"^[0-9.]*$"))

(defn number-value-of
  "given a string, detect if it contains digits only, then convert to a long or Double, otherwise return the string unchanged"
  [s]
  ;;first a regex to detect if the string contains digits only
  (let [re-number? (re-find digits-only? s)]
    ;;then try to cast the string to a number going from the largest type to the shortest one
    (if re-number?
      (try
        (Long/valueOf s)
        (catch java.lang.NumberFormatException nfe
          (try
            (Double/valueOf s)
            (catch java.lang.NumberFormatException nfe
              s))))
      s)))
