(ns scenari.v2.corpus-test
  "Rejoue un corpus externe de .feature réels contre la grammaire, pour attraper
  les régressions que les fixtures du repo ne couvrent pas.

      SCENARI_CORPUS=/chemin/vers/scenarios ./test.sh --focus :unit

  Sans la variable d'environnement, le test ne fait rien : le corpus vit hors du
  repo et n'est pas versionné ici."
  (:require [clojure.string :as string]
            [clojure.test :as t :refer [deftest is]]
            [scenari.v2.core :as core]))

(defn corpus-failures
  "Construit l'AST de chaque feature du répertoire et renvoie [[chemin motif] ...]
  pour celles qui lèvent. La glue du projet n'étant pas chargée ici, chaque step
  ressortirait en :missing-step — un événement que le reporter kaocha ne sait pas
  traiter — d'où la neutralisation de do-report : seule la grammaire est en test."
  [dir]
  (let [files (core/get-feature-files dir)]
    [(count files)
     (with-redefs [t/do-report (constantly nil)]
       (->> files
            (keep (fn [f]
                    (try (core/->feature-ast (slurp f) {} *ns*) nil
                         (catch Throwable e
                           (let [{:keys [line column text]} (:failure (ex-data e))]
                             [(.getPath f)
                              (if line
                                (str "ligne " line ", colonne " column " : " text)
                                (first (string/split-lines (str (.getMessage e)))))])))))
            (sort-by first)
            vec))]))

;; Défini uniquement quand le corpus est disponible : sans la variable, il n'y a
;; rien à vérifier, et un deftest vide compterait comme un échec côté kaocha.
(when-let [dir (System/getenv "SCENARI_CORPUS")]
  (deftest external-corpus-test
    (let [[checked failures] (corpus-failures dir)]
      (is (pos? checked) (str "aucun .feature trouvé sous " dir))
      (is (empty? failures)
          (str (count failures) " feature(s) sur " checked " ne se construisent plus :\n"
               (string/join "\n" (map (fn [[path msg]] (str "  " path "\n    " msg)) failures)))))))
