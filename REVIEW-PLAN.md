# Plan de traitement — revue `feat/gherkin-conformance`

Fichier d'état de travail. **Le statut ne vit qu'ici, dans le tableau.** Après chaque
correctif : mettre à jour la ligne, ajouter une entrée au journal, committer avec l'id
(`F03: run the kaocha :post-run hooks`). Supprimer ce fichier à la fusion de la branche.

Statuts : `TODO` · `WIP` · `OK` (corrigé + vérifié) · `SKIP` (écarté, motif obligatoire)

## État

| id | Statut | Lot | Fichier | Problème | Vérif. |
|----|--------|-----|---------|----------|--------|
| F01 | OK | A | `parser.clj:51` | Parsing exponentiel / OOM sur descriptions de scénario | perf |
| F02 | OK | A | `parser.clj:87` | `row` refuse espaces en fin de ligne et lignes vides | conformance |
| F07 | OK | A | `parser.clj:82` | `Examples: <nom>` ne parse pas | conformance |
| F08 | OK | A | `parser.clj:64` | Un seul bloc `Examples` par Outline | conformance |
| F13 | OK | A | `parser.clj:81` | ``` ``` ``` impossible dans un docstring `"""` | conformance |
| F03 | OK | B | `kaocha/type/scenari.clj:84` | `:post-run` feature jamais exécuté sous kaocha | feature_test |
| F04 | OK | B | `core.clj:267` | `run-features` renvoie un `map` paresseux | feature_test |
| F10 | OK | B | `core.clj:259`, `test.clj:183` | `:post-run` hors `finally` | feature_test |
| F05 | OK | C | `step.clj:16` | ClassCastException sur step sans glue ponctué | step_test |
| F06 | OK | C | `test.clj:56` | `%-0s` → `MissingFormatWidthException` | report_test |
| F09 | TODO | D | `core.clj:115` | Substitution Outline chaînée / non déterministe | core_test |
| F14 | TODO | D | `core.clj:112` | `Examples` sans ligne → scénario disparu, erreur trompeuse | core_test |
| F12 | TODO | D | `core.clj:147` | Tags d'une `Rule` perdus | conformance |
| F11 | TODO | D | `kaocha/type/scenari.clj:52` | Ids kaocha dupliqués sur les lignes d'Outline | manuel |
| F15 | TODO | E | `core.clj:295` | Les macros `def*` renvoient `nil` + duplication ×4 | core_test |

## Ordre et dépendances

Les lots sont indépendants ; à l'intérieur d'un lot, l'ordre est celui du tableau.
Une seule vraie dépendance : **F01 avant le reste du lot A** — c'est lui qui dicte la
règle de grammaire (« aucune production ne doit pouvoir matcher le vide dans une
répétition, aucune ne doit avaler un `\n` implicitement »), et les autres corrections
de grammaire doivent la respecter.

Faire **un commit par id**. Ne pas grouper : chaque correctif de grammaire peut
introduire une régression de perf, on veut pouvoir bissecter.

---

## Lot A — Grammaire (`src/scenari/v2/parser.clj`)

Test cible : `test/scenari/v2/gherkin_conformance_test.clj`.

### F01 — Parsing exponentiel des descriptions

Cause : `<description_line> = <whitespace?> !keyword_prefix ...` où `whitespace = #'\s+'`
avale les fins de ligne, combiné à `steps = (comment | <whitespace*> | step_sentence | <eol>)*`
dont l'alternative `<whitespace*>` matche le vide. Le parseur GLL peut découper les
blancs entre `description` et `steps` d'un nombre exponentiel de façons. La grammaire
reste non ambiguë (`insta/parses` renvoie 1 parse) : c'est le coût de recherche.
Mesuré : 5 scénarios → 91 ms, 10 → 2,9 s, 20 → OOM.

Correctif : rendre la consommation des blancs déterministe.
- `<description_line>` : remplacer `<whitespace?>` par `<indent>` (`#'[ \t]*'`, sans `\n`)
  et consommer explicitement le `<eol>` final.
- `steps` : supprimer l'alternative `<whitespace*>` qui matche le vide (`<#'[ \t]+'>`
  si un espace en début de ligne doit rester toléré).

Vérif :
- `insta/parses` renvoie exactement 1 parse sur un fichier tags + description + background + rule ;
- une feature générée à 60 scénarios avec ligne de description parse en < 1 s (test à ajouter, avec seuil large pour ne pas être instable en CI).

### F02 — `row` trop strict

`row = <eol> <indent> <'|'> ...` exige le `\n` immédiatement après le `|` fermant de la
ligne précédente. Cassé (et, depuis `af2dae4`, fatal au chargement du namespace) : espaces
ou tabulation en fin de ligne, ligne vide entre deux lignes de tableau, ligne vide entre
un step et son tableau. Les 4 cas passaient sur `master`.

Correctif : `row = <#'[ \t]*'> (<eol> <#'[ \t]*'>)+ <'|'> (value <'|'>)+` — chaque
répétition consomme au moins un `\n`, donc pas de match vide (règle F01). Même
traitement pour le `<eol>` qui précède `tab_params` dans `step_sentence`.

Vérif : les 4 cas ci-dessus dans le test de conformance.

### F07 — `Examples:` nommé

`examples = <whitespace?> examples-keywords <eol> ...` : pas de nom autorisé, alors que
`scenario`, `background` et `rule` acceptent tous un `<#'[^\r\n]*'>`. Ajouter le même.

### F08 — Plusieurs blocs `Examples`

`scenario = ... steps examples?` → `examples*`, et `expand-scenario` doit itérer sur
**tous** les nœuds `:examples` (`mapcat`) au lieu du `some` actuel qui garderait
silencieusement le premier. À faire après F07 (même production).

### F13 — Backticks dans un docstring `"""`

`doc_content` exclut les suites de backticks quelle que soit la délimitation ouvrante, et
`<doc_delim> = '"""' | '```'` est matché indépendamment à l'ouverture et à la fermeture
(`"""` ouvert / ``` fermé est accepté). Correctif : deux alternatives distinctes,
chacune avec son délimiteur et son propre regex de contenu. Corrige les deux défauts.

---

## Lot B — Hooks `:post-run`

La fonctionnalité (`f11665b`) n'est câblée que sur les deux runners Clojure.

### F03 — kaocha n'exécute pas le `:post-run` feature

`-load` ne déstructure pas `post-run` et ne stocke que `::pre-run` ; `-run
:kaocha.type/scenari-feature` n'appelle que les pre-run. C'est le runner de `./test.sh`,
donc le teardown est silencieusement sauté en usage normal.
Correctif : ajouter `post-run` à la déstructuration, `::post-run` au testable, et
l'appel après `run-testables` — directement dans le `finally` de F10.

### F04 — `run-features` paresseux

`([& features] (map run-feature features))` : `(v2/run-features #'ma-feature)` n'exécute
rien et ne signale rien. `mapv`. (Le test post-run existant contourne par un `doall` —
le retirer.)

### F10 — `:post-run` hors `finally`

Dans `core/run-feature` (l.259), `core/run-scenario` (l.241), `test/run-feature` (l.183)
et la boucle de scénario de `test.clj`, le `doseq` post-run est en flot normal. Toute
exception qui échappe au `catch` de `run-step` — `t/do-report`, ambiguïté de glue,
hook `:pre-scenario-run` — saute le teardown, exactement le cas pour lequel il existe.
`try`/`finally` aux 4 endroits + le `-run` kaocha de F03.

Vérif (lot B) : dans `feature_test.clj`, une feature dont un hook `:pre-scenario-run`
lève, assertion sur l'effet de bord du post-run ; le test doit passer sous `./test.sh`
(kaocha) **et** en appelant `v2/run-features` directement.

---

## Lot C — Robustesse d'exécution

### F05 — `generate-step-fn` casse le chargement

Deux défauts superposés :
1. `(ex-info (:reason (insta/get-failure ...)) ...)` passe un vecteur là où une `String`
   est attendue → `ClassCastException` au lieu du message « step manquant ».
2. Cause racine : `words = #'[a-zA-Z./\_\-\'èéêàûù ]+'` (dupliqué dans les grammaires
   `sentence` et `step`) rejette `,` `:` `(` `)` et la plupart des accents. N'importe
   quel step sans glue avec une ponctuation ordinaire fait échouer le chargement du
   namespace au lieu d'afficher le squelette `defwhen`.

Correctif : message d'erreur en `str`, et élargir `words` (`\p{L}` pour les accents +
ponctuation courante), en factorisant le regex dans un `def` partagé par les deux
grammaires. Ne pas y inclure chiffres, guillemets et crochets : les tokens `number`,
`string`, `vector` et `parameter` doivent continuer à être découpés.

Vérif : le stub `t/do-report` de `corpus_test.clj` peut être retiré ; un step
`Given a step, with a comma` sans glue produit le squelette.

### F06 — `%-0s` dans le reporter

Colonne de tableau dont l'en-tête et toutes les cellules sont vides → largeur 0 →
`(format "%-0s" "")` lève. Correctif : `(max 1 (widths %1))`.

---

## Lot D — Sémantique

### F09 — Substitution Outline chaînée

`(reduce-kv string/replace % params)` re-substitue dans le texte déjà substitué, dans
l'ordre de la hash-map : `| <b> | 42 |` sur `Given <a> then <b>` donne `42 then 42` au
lieu de `<b> then 42`, et le résultat dépend de l'ordre d'itération de `zipmap`.
Correctif : une seule passe — `(string/replace s #"<[^>]*>" #(get params % %))`.

### F14 — `Examples` avec en-tête mais sans ligne

`(for [[_ & values] rows] ...)` sur `rows` vide renvoie `()`, le scénario disparaît, et
le garde-fou « Feature has no scenario… » accuse la reconnaissance des mots-clés.
Correctif : lever explicitement depuis `expand-scenario` en nommant le scénario.
Même fonction que F09 → même commit possible, mais deux tests distincts.

### F12 — Tags d'une `Rule` perdus

`normalize-scenarios` supprime le sous-arbre `:rules` et `rule-scenarios` ne lit que
`(child :scenarios rule)`. Or la spec Gherkin fait hériter les tags d'une Rule à ses
scénarios : `./test.sh --focus-meta :slow` sur `@slow / Rule:` ne sélectionne rien.
Correctif : un `with-annotations` calqué sur `with-description` (qui, lui, fait déjà
correctement le portage). La perte du *nom* de la Rule est documentée comme voulue
dans `doc/feature-structure.md` — ne pas y toucher ici.

### F11 — Ids kaocha dupliqués

`scenario->id` dérive de `:scenario-name` seul ; un Outline dont le nom ne contient pas
de `<placeholder>` (cas courant) produit N scénarios de même id. `--focus
feature/mon-scenario` ne peut cibler une ligne, et le rapport montre N feuilles
identiques.
Correctif retenu (le plus local) : dédupliquer dans `-load` en suffixant un ordinal aux
ids répétés. Alternative si l'on veut des ids parlants : porter les valeurs de la ligne
dans le map de scénario depuis `expand-scenario` — plus intrusif (nœud AST + entrée de
transform), à ne faire que si le suffixe ordinal se révèle insuffisant à l'usage.

---

## Lot E — Nettoyage

### F15 — Macros `def*`

Les 4 macros renvoient `nil` (tout `def*` Clojure renvoie son var : casse
`(doto (defgiven ...) ...)`, l'écho REPL, l'outillage) et leur corps est copié-collé
à l'identique 4 fois — la duplication est déjà signalée par un TODO du fichier.
Correctif : une macro partagée, terminée par `(var ~sym)` ; les 4 noms deviennent des
alias. Le mot-clé (`given`/`when`/…) n'étant pas utilisé dans le corps, il n'y a rien à
paramétrer.

---

## Vérification globale (à chaque fin de lot)

```bash
./test.sh                                   # 42 tests / 145 assertions au départ
SCENARI_CORPUS=<corpus> ./test.sh           # filet de régression grammaire
```

## Journal

<!-- une ligne par correctif : id — date — commit — note -->
- **F01** — 2026-08-29 — `4ee4002` — grammaire : `description` ancrée à la ligne
  (`<indent>` au lieu de `<whitespace?>`, séparateur atomique `<blanks>`), `steps` sans
  alternative vide, `scenarios = scenario*`, blocs démarrant sur `<indent>`.
  60 scénarios décrits : OOM → **51 ms**, 1 seul parse. Test `cout-de-parsing-test`.
- **F03 + F04 + F10** — 2026-08-29 — `a839f9b` — cause commune : les hooks étaient
  rejoués à 6 endroits. Un seul `v2/run-hooks` (pre-run, corps, post-run dans un
  `finally`) utilisé par `core/run-scenario`, `core/run-feature`, `test/run-feature`
  (feature + scénario) et `kaocha.type.scenari/-run` ; `-load` porte désormais
  `::post-run` ; `run-features` passe en `mapv`. Tests `post-run-test` (3 runners) et
  `run-hooks-test`.
- **F02** — 2026-08-29 — `cc4413d` — `row` démarre sur `<indent>` et mange ses propres
  sauts de ligne ; `<blanks>` sépare un step de son tableau / sa doc string, et
  `Examples:` de sa table.
- **F07** — 2026-08-29 — `a934bd7` — nom masqué après `examples-keywords`, comme
  `background` et `rule`.
- **F08** — 2026-08-29 — `9308e2e` — `examples*` dans la grammaire, `expand-scenario`
  itère sur tous les blocs (`filter` au lieu de `some`).
- **F13** — 2026-08-29 — `1e36294` — une branche par délimiteur dans `doc_string`, même
  token à l'ouverture et à la fermeture ; corrige aussi les délimiteurs dépareillés.
- **F05** — 2026-08-29 — `a6f7fd4` — `ex-info` recevait le `:reason` d'instaparse (un vecteur) ;
  et surtout `words` était une liste blanche. Un mot est désormais tout ce qui n'ouvre pas
  un token, regex partagé par les grammaires `sentence` et `step`.
  Le stub `t/do-report` de `corpus_test.clj` est conservé : il sert aussi à taire le
  rapport sur des centaines de fichiers, et n'a pas pu être vérifié sans corpus.
- **F06** — 2026-08-29 — `ca249ef` — `(max 1 largeur)` dans `table-lines`.
