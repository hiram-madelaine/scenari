# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

# [Unreleased] #

## Changed ##

Step sentences are matched with `io.cucumber/cucumber-expressions`, the
reference implementation, instead of the two hand-rolled token substitutions.

Gained: `{int}` `{float}` `{word}` and the other built-in types, optional text
`apple(s)`, alternation `hot/cold`, and an undefined token now raises an error
naming the guilty glue instead of a `PatternSyntaxException`. `{number}` is kept
as a custom parameter type — the glues already written still match, and it now
accepts a sign and decimals. A sentence wrapped in `^...$` or `/.../` is still
read as a plain regex.

Arguments passed to a step fn are unchanged for now: they still come from the
sentence's literals, not from the expression match.

Breaking:

- A literal `/`, `(` or `)` in a sentence matcher must be escaped (`\/`), or it
  reads as alternation or optional text. Replayed on a 221-file corpus: 2
  sentences out of 1123 were concerned.

Feature files are now parsed by `io.cucumber/gherkin`, the reference
implementation, instead of the hand-written instaparse grammar. `->feature-ast`
builds the feature map from gherkin *pickles*, which already resolve `Background`
splicing, `Rule` flattening, tag inheritance and `Scenario Outline` expansion.

Gained: the ~70 gherkin languages and the `# language:` header, tags on an
`Examples` block, comments inside an `Examples` table, doc string content types
(``` ```json ``` reaches the step as `:media-type`), and line/column in parse
errors.

Breaking:

- A `.feature` must start with a `Feature:` line (or a tag, comment or
  `# language:` header). A bare `Scenario:` is now a parse error.
- Non-English keywords require the `# language:` header; the language is no
  longer guessed from the keywords themselves.
- French features must write `Scénario:`, not `Scénario :` — the official
  dialect puts no space before the colon. In exchange the dialect is richer:
  `Soit`, `Sachant que`, `Lorsque`, `Donc`, `Et que` all work.
- `:scenario-name` no longer carries the leading space the old grammar left in.
- `:feature` is the feature name; an `As a / I want to / So that` narrative is
  free description text, per the spec, and lands in `:description`.
- `<placeholders>` are substituted in steps, their arguments and the scenario
  name, not in free description text.

# [1.4.4] - 2019-09-18

Add insta parse regex to handle unicode characters, numerics and punctuation
https://github.com/jgrodziski/scenari/pull/8

# [1.4.0] - 2019-05-17 #

## Added ##

Examples table as input step param
