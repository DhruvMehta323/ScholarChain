# Entity resolution contracts (Phase 05, policy `er-v1`, feature set `er-features-v2`)

Owner: entity-resolution-engineer. Depends only on `core`. Package `scholarchain.er`. No library was added: the JDK
`HttpClient` serves the LLM client and the string metrics are in-module.

Layout: `src/main/scala/scholarchain/er` (pure functions), `.../er/tools` (runnable mains),
`src/main/resources/scholarchain-er.v1.conf` (versioned defaults), `src/main/resources/er-prompts/` (versioned prompt, NOT under
`prompts/**`), `gold/` (queue, human labels, split), `evaluation/` (results, frozen policy).

## 1. Normalization (pure)

- `Strings.nfkc`, `Strings.canon`, `Strings.foldAccents`, `Strings.tokens`, `Strings.titleNorm`, `Strings.textNorm`.
- `Strings.tokens` works on Unicode code points (supplementary-plane letters such as U+20BB7 are kept; a first initial is a whole code point, `ParsedName.firstInitial` is `Option[String]`). `Strings.nameTokens` is the same but keeps a hyphen between two letters inside the token. `Names.parse` uses it: a hyphenated family name is one family (`Anna Smith-Jones` and `Smith-Jones, Anna` both give family `smith jones`, key `r1|smithjones|a`); hyphens in given names still split (`Jean-Pierre` gives initials `jp`). This changes the block key of any "First Last" name whose last token is hyphenated (it was the part after the hyphen only).
- **Dashes (repair-04 F1).** `Strings.canon` = NFKC, then Unicode dash punctuation (U+2010 to U+2015, U+2212, U+FE58, U+FE63, U+FF0D) becomes ASCII `-` and U+00AD
  (soft hyphen) is dropped (joined, no separator). `Strings.tokens`, `Strings.nameTokens` and `Names.parse` all start from `canon`, so every name use (block keys and
  their fallbacks, `name_sim`, `family_exact`, `family_jw`, initials, `coauthor_overlap`, `author_overlap` via `Names.personKey`, `first_author_agree`) sees one form:
  `Ángela Hernández‐Solana` (U+2010), the ASCII form and `Hernández‐Solana, Ángela` all give family `hernandez solana` and block key `r1|hernandezsolana|a`.
  A hyphen next to a period is still a separator (`J.-P.`, also with U+2013). A soft hyphen inside a family joins the parts (`hernandezsolana`, same block key as the
  hyphenated form, but `family_exact` against the hyphenated form is 0 because the family strings differ by the space). Titles do not use `tokenize`: `titleNorm`
  goes through core's `Titles.titleKey`, unchanged. `Strings.tokens` is also used by `textNorm` (venue, affiliation): a dash was already a separator there, only a soft
  hyphen now joins instead of splitting; checked and judged harmless.
- Names: `Names.parse(raw): Option[ParsedName]` handles "First Last", "Last, First", initials ("J. R.", "J.-P."), particles
  (van, von, de, ...), and the DBLP homonym suffix (`Wei Wang 0001`). The suffix is IDENTITY evidence: it is kept in
  `ParsedName.suffix`, left out of every comparison string, and reported by the features `homonym_suffix_present` and
  `homonym_suffix_conflict` (both sides carry different suffixes).
- Titles: markup tags and a few HTML entities removed, then core's `Titles.titleKey`, then punctuation removed.
- Identifiers: `ErIds.doi | arxiv | orcid | openAlex` only delegate to core's `Doi`, `ArxivId`, `Orcid`, `OpenAlexId`. There is no second rule.
- **NFC vs NFD (Phase 04 gap 1).** NFKC is applied first, so the composed and decomposed spellings of a name are the same string
  for every feature and block key. Comparison strings also fold accents (NFD, combining marks removed; `ß ø đ ł æ œ ı ð þ` by table).
- **Confusables (Phase 04 gap 2).** Confusable characters are NOT folded (a Cyrillic U+0430 stays different from Latin `a`): folding would
  over-merge. A token that mixes Latin, Cyrillic or Greek letters sets `name_mixed_script` / `author_mixed_script`, which blocks every
  deterministic accept rule. Such a pair can still be scored and sent to a human or the LLM band.

## 2. Blocking (pure; `Blocking`)

| Kind | Primary key | Fallback keys |
|---|---|---|
| researcher (mention) | `r1\|<family, no spaces>\|<first initial or _>` | `<primary>\|y<year/5>`, then `<primary>\|c<coauthor family>` (<= 8) |
| work | `w1\|<up to 4 longest title tokens (len>=4, no stop words, cut to 12), sorted>\|y<year/3>` | `<primary>\|v<venue key or none>` |

Candidates are cross-source only: exactly one side is DBLP (`Blocking.eligible`). DBLP-DBLP researcher pairs only with person-alias
evidence (`aliasLinked = true`). The source pair is recorded in the candidate provenance parameters `leftSource` and `rightSource`
(`MatchCandidate` has no source field). A year bucket misses a pair whose years straddle a bucket edge (a recall cost).
Phase 04 persists no venue and no affiliation, so `venue_compat` and `affiliation_compat` are absent in real runs.
A pair found under several keys keeps the candidate with the smallest (type, key): `Blocking.preferred`.
`Blocking.generate` yields only the keys in the table above. The exact-identifier keys `x1|<type>|<id>` (one per ORCID, DOI, arXiv id or OpenAlex id) are added by HJ6 and are not part of `generate` (repair-03 L1c fixed the comment; no key was added, the smaller and safer change).

## 3. Features (pure; `Features`, version `er-features-v2`)

A feature that cannot be computed is ABSENT from the map; scoring treats it as neutral (0.5). Citation-neighbourhood evidence does not
exist in this corpus (no citation edges), so there is no such feature.

- Researcher: `name_sim` (0.6 Jaro-Winkler(family) + 0.4 given-name part: Jaro-Winkler of full given names, or an initial match 1/0 when a side
  has only an initial), `family_exact`, `family_jw` (Jaro-Winkler of the two family strings; repair-04 F2; NOT a score weight, read only by the ORCID name rule), `initials_compat` (1 / 0.25 / 0), `homonym_suffix_present`, `homonym_suffix_conflict`, `name_mixed_script`,
  `position_agreement` (only when both works have the same DOI), `coauthor_overlap` (overlap coefficient of "family|initial" keys),
  `affiliation_compat` (token Jaccard), `work_title_sim`, `year_distance`, `venue_compat`, `orcid_agreement`, `source_reliability`, `exact_id_conflict`.
- Work: `title_sim` (trigram Dice), `title_exact`, `year_distance`, `author_overlap`, `first_author_agree`, `author_mixed_script`, `venue_compat`,
  `doi_agreement`, `arxiv_agreement`, `openalex_agreement`, `source_reliability`, `exact_id_conflict`, and `doi_preprint_version_pair`.
- `doi_preprint_version_pair` (work; present, value 1.0, only when true; added in repair-03, no version label change because it is an extra key in the
  free-form `features` map and it is not part of the score): both DOIs are present and differ after normalization, exactly one DOI starts with a
  preprint-server prefix (`10.48550/` arXiv, `10.31219/` OSF, `10.20944/` Preprints.org, `10.2139/` SSRN, `10.21203/`
  Research Square, `10.22541/` Authorea, `10.36227/` TechRxiv; `10.1101/` is NOT listed, see below), and `title_sim >= 0.9` (`Features.VersionTitleSim`, hand-set). Anything else is absent.
  `10.1101/` (Cold Spring Harbor Laboratory) was removed in repair-04 F4: it is shared by bioRxiv/medRxiv and by journals (Genome Research `10.1101/gr.123456.120`,
  Genes and Development `10.1101/gad.*`), so a journal-to-journal pair could get `doi_disagree_possible_version` wrongly. A date-shaped rule
  (`10.1101/` + `YYYY.MM.DD.`, which matches `10.1101/2020.01.01.123456` and not `10.1101/gr.123456.120`) was the alternative; it would still miss the old
  numeric bioRxiv DOIs, so the smaller and safer change was chosen: remove the prefix. The cost is a missed version hint for bioRxiv/medRxiv, never a wrong one.
  The titles are the only evidence beyond the DOI shape, so this is a "possible version" signal, never an identity claim.
- Agreement features: 1.0 equal after core normalization, 0.0 both present and different, absent otherwise.
- `source_reliability` = min of the two sources (orcid .95, dblp .90, crossref .85, openalex .75, scholarchain .50).

## 4. Policy `er-v1` (pure; `Policy`)

Score = sum of weight x transformed feature (`year_distance` -> `max(0, 1 - d/3)`; absent -> 0.5). Weights (part of the policy version,
not config): researcher name_sim .28, initials_compat .10, position_agreement .14, coauthor_overlap .20, affiliation_compat .04, work_title_sim .10,
year_distance .05, venue_compat .04, source_reliability .05; work title_sim .35, title_exact .10, year_distance .10, author_overlap .20,
first_author_agree .10, venue_compat .10, source_reliability .05.

Decision order (first rule wins; the match path is recorded on every decision):

0. **REJECTED** (path `exact_id`, rule `orcid_disagreement`, reason `orcid_disagree`, confidence 1.0; repair-03 M1): the ORCIDs disagree, nothing else
   does (no `exact_id_conflict`, no other agreement feature is 0) and `position_agreement` is not 1. Two different ORCIDs are strong evidence of two
   people. `MatchDecisionV2` validation does not constrain outcome by path, and HJ7's only exact-id invariant (a candidate with
   `exact_id_conflict` must be CONFLICT) still holds, because HJ3 evidence is never routed to rule 0.
1. **CONFLICT** (path `exact_id`): `exact_id_conflict` = 1 (Phase 04 HJ3), or any of `orcid_agreement`, `doi_agreement`, `arxiv_agreement`,
   `openalex_agreement` is 0 and rule 0 did not fire (so an ORCID disagreement stays CONFLICT only beside the same DOI at the same author position
   (`position_agreement` = 1), beside HJ3 evidence, or beside another id disagreement). Never merged. Never overridden by the LLM. Reason
   `doi_disagree_possible_version` replaces `doi_disagree` when the DOI is the only disagreement and `doi_preprint_version_pair` = 1 (repair-03 L2;
   outcome unchanged).
1b. **CONFLICT** (path `exact_id`, rule and reason `orcid_name_mismatch`; repair-03 H1, rule changed in repair-04 F2): the ORCID is the only agreeing identifier
   and the family names do not fit: an ORCID-only accept needs `family_exact` = 1 (after the F1 dash normalization) OR `family_jw` >= `Policy.OrcidFamilyJwFloor`
   = **0.92**. A missing `family_exact` and `family_jw` (no parsable names) cannot be shown to fit and is CONFLICT. The repair-03 floor (`name_sim` >= 0.85) is
   retired: it admitted different common surnames when the given names agreed (`Wei Wang` / `Wei Yang`, `Li Zhang` / `Li Zhao`) and any initials-only side.
   The 0.92 value is hand-set, NOT tuned on any labels, and is a typo-level floor on the family name alone: `Wang` / `Yang` scores about 0.83, `Zhang` /
   `Zhao` about 0.85 and `Zhang` / `Wang` (from `W. Zhang` / `Wei Wang`) about 0.78, all below it, so none is accepted on an ORCID match alone; a one-letter slip
   in a longer family name passes. `Policy.OrcidNameFloor` is RETIRED (no rule reads it; the constant remains for one round only so existing test sources compile,
   and is to be deleted with `PolicySuite.scala:279`). Needs a match-policy review.
2. **ACCEPTED** (path `exact_id`): an agreement feature is 1, none is 0, and 1b did not fire.
3. **Deterministic** (path `deterministic`): `Policy.DeterministicRules`: researcher same work + same position + `name_sim >= .92` + same family +
   `work_title_sim >= .9` + no mixed script + no suffix conflict (accept); same family with `initials_compat = 0` (reject); different family with
   `name_sim < .6` (reject); work `title_exact` + `year_distance = 0` + `author_overlap >= .8` + no mixed script (accept); `title_sim < .4` (reject).
4. **Probabilistic**: `score >= accept` accepted; `score < reject` rejected; else UNRESOLVED, LLM-eligible when `llm-band-low <= score <= llm-band-high`.

Separate thresholds per entity kind: accept, reject, llm-band-low, llm-band-high, with `0 <= reject <= band-low <= band-high <= accept <= 1`.
`Decisions.build` writes a `MatchDecisionV2` whose `features` hold the candidate features plus `policy.score` and `policy.threshold.*`;
`policyVersion` is `er-v1`; `decisionTimestamp` is the candidate's `provenance.retrievedAt`, else `er.decision-epoch` (never the wall clock).

## 5. LLM adjudication (code only in Phase 05; the human runs P2)

- Output schema (exactly these keys): `leftId`, `rightId`, `outcome` (accept|reject|abstain), `confidence` [0,1], `citedFields`
  (`left.<field>` / `right.<field>` from the fields actually shown; non-empty for accept and reject), `reasonCodes` (non-empty, `[a-z0-9_]`).
- `AdjudicationValidator.parse` returns a fixed code on failure: `malformed_json`, `schema_violation`, `fabricated_id`, `invalid_outcome`,
  `confidence_out_of_range`, `invalid_cited_field`, `invalid_reason_codes`, `contract_violation` (core `validateAgainst`). A failure is retried up to
  `er.llm.max-attempts` live calls in total, then becomes an ABSTAIN record with reason codes `invalid_output` and the code (not cached).
- Prompt `er-prompts/er-adjudication-v1.txt`. Record text is rendered as JSON with `<`, `>`, `&` escaped, inside `<record>` tags, and the system
  text says the records are data and never instructions.
- `LlmClient`: `FakeLlmClient` (tests, counts calls), `AnthropicClient` (JDK `HttpClient`, key from `SCHOLARCHAIN_ANTHROPIC_API_KEY` only,
  never `ANTHROPIC_API_KEY`; errors carry a fixed code and an HTTP status, never a body or a header). `ApiKey.fromEnv` rejects a value with any
  character outside visible ASCII (`!`..`~`) and the message names the variable only; the request is also built inside the `try`, so a rejected header
  becomes the fixed code `bad_request`, never a stack trace (repair-03 M3). Provider `bedrock` fails with
  "LLM provider 'bedrock' is not implemented in Phase 05".
- Cache: JSONL, key = H(inputHash, promptVersion, modelId, policyVersion); only validated answers are cached; a hit makes no call. On EVERY hit
  (`Adjudicator.adjudicate`, `Resolver.cached`) the record's own `inputHash`, `modelId`, `promptVersion` and `policyVersion` must equal the values that
  made up the lookup (`AdjudicationCache.recordMatches`); a mismatch is a miss. At load (`AdjudicationCache.open`) a line whose stored lookup value differs
  from `AdjudicationCache.keyOf(record)` is dropped and counted with the skipped lines (repair-03 M2). Default
  `modules/entity-resolution/target/llm-cache/er-llm-cache.jsonl`, already ignored by the `**/target/` rule in `.gitignore`.
- Validation: an adjudication is checked against its candidate with `AdjudicationRecord.validateAgainst` on a cache hit (`Adjudicator.adjudicate`; a failing hit is treated as a miss), in `Resolver.cached`, and in `Resolver.decide` through the candidate overload `Policy.applyAdjudication(r, adj, min, candidate)`. When the cache file is loaded, a line whose record fails the contract is skipped and counted. The 3-argument `applyAdjudication` stays for an adjudication already known to belong to the candidate. `PromptTemplate.render` fills all placeholders in a single pass, so placeholder text inside a record is shown literally.
- Bounds: `er.llm.max-calls` live calls (retries included), `er.llm.max-cost-usd` (observed usage). Only UNRESOLVED candidates in the LLM band
  are eligible. `Policy.applyAdjudication`: abstain stays UNRESOLVED; accept/reject below `er.llm.min-confidence` stays UNRESOLVED; CONFLICT and
  already decided results are returned unchanged; otherwise path `llm`, `modelVersion` = `<model>|<prompt version>`.

## 6. Configuration (`scholarchain.er.*`, through core `ModuleConfig`)

Defaults live in `src/main/resources/scholarchain-er.v1.conf`. Precedence: env `SCHOLARCHAIN_ER_<KEY>` > `--override scholarchain.er.<key>=v` > file.
Keys: `er.schema-version`, `er.policy-version`, `er.feature-set-version`, `er.decision-epoch`, `er.max-block-size`,
`er.{researcher,work}.threshold.{accept,reject,llm-band-low,llm-band-high}`, `er.llm.{provider,model,price-input-usd-per-mtok,price-output-usd-per-mtok,
max-calls,max-attempts,max-output-tokens,max-cost-usd,timeout-seconds,min-confidence,cache-path}`.
All thresholds are PLACEHOLDERS until `Calibrate` writes the frozen policy; the model id and prices are placeholders the human confirms before P2.

## 7. Tools (run from the repository root; paths relative to it)

Every tool refuses to overwrite an existing output file. Add `--code-version "$(git rev-parse --short HEAD)"` to record the code version.

```
sbt "entityResolution/runMain scholarchain.er.tools.BuildViews --works <curated/works dir> --mentions <curated/author-mentions dir> --out <views.jsonl>"

sbt "entityResolution/runMain scholarchain.er.tools.BuildReviewQueue --candidates <match-candidates dir|file> --views <views.jsonl> --out modules/entity-resolution/gold/review-queue-v1.csv --seed 20260101 --researcher 150 --work 50"

sbt "entityResolution/runMain scholarchain.er.tools.SplitGold --labels modules/entity-resolution/gold/gold-labels-v1.csv --out modules/entity-resolution/gold/gold-split-v1.csv --seed 20260101"

sbt "entityResolution/runMain scholarchain.er.tools.Calibrate --split modules/entity-resolution/gold/gold-split-v1.csv --candidates <match-candidates> --out-policy modules/entity-resolution/evaluation/er-v1-frozen-policy.json --out-sensitivity modules/entity-resolution/evaluation/er-v1-threshold-sensitivity.csv"

sbt "entityResolution/runMain scholarchain.er.tools.AdjudicateBand --candidates <match-candidates> --views <views.jsonl> --policy modules/entity-resolution/evaluation/er-v1-frozen-policy.json"            # plan only
SCHOLARCHAIN_ANTHROPIC_API_KEY=... sbt "entityResolution/runMain scholarchain.er.tools.AdjudicateBand ... --confirm"                                                                              # P2, human only

sbt "entityResolution/runMain scholarchain.er.tools.Evaluate --split modules/entity-resolution/gold/gold-split-v1.csv --candidates <match-candidates> --policy modules/entity-resolution/evaluation/er-v1-frozen-policy.json --out-dir modules/entity-resolution/evaluation [--views <views.jsonl> --cache <cache.jsonl>]"

sbt "entityResolution/runMain scholarchain.er.tools.FinalDecisions --candidates <match-candidates> --policy <frozen> --views <views.jsonl> --cache <cache.jsonl> --out <match-decisions-final.jsonl> --summary <summary.json>"
```

- `BuildReviewQueue` columns: `pair_id, entity_kind`, then for left and right `source, id, name_or_title, coauthors_or_authors (<= 8), work_title, year,
  venue, ids, affiliation`, then `label, notes` (empty). BLIND: no score, feature or predicted outcome; rows are shuffled with the seed; the
  sidecar `*.sampling.json` has seed, parameters, input checksums and per-stratum counts only. Strata per kind: `exact` (<= n/10), `conflict`
  (<= n/20), score bins `bin0..bin4` with triple weight near the accept and reject thresholds; half of each quota is taken from hard negatives first
  (same-surname researcher pairs that are not a clean name match; work pairs with similar titles that differ in year or authors).
- `SplitGold`: deterministic (seed), stratified by kind x label, calibration size `round(0.6 N)` spread by largest remainder (102 / 68 for 170).
  UNSURE rows are excluded and counted in `<out>.summary.json`. An empty or unknown label is an error.
- `Calibrate` keeps only rows with `split = calibration` before any lookup; held-out rows are never scored. Objective: maximize F1 of the accepted class
  vs MATCH on calibration, ties to higher precision then higher threshold; `accept` grid 0.50..0.99 step 0.01; `reject` = largest 0.01 step not above the
  lowest score of a calibration MATCH that reached the probabilistic path (capped at accept); LLM band = [reject, accept].
- `Evaluate` reads held-out rows once (repair-03 L5; repair-04 F3). Every evaluation is appended to a ledger (`--ledger <file>`, default
  `heldout-evaluations.jsonl` beside the split file; one JSON line with `splitSha256`, `name`, `disclosedReEvaluation`) keyed by the checksum of the split
  file. A second evaluation of the same split is refused under ANY `--out-dir` or `--name` unless `--new-evaluation-reason "<why>"` is given; that
  evaluation is disclosed in the result (`disclosedReEvaluation`) and the ledger. The frozen policy's `er-gold-split` input checksum must equal the checksum
  of the split being evaluated, else the tool refuses (a policy with no such checksum is refused too). Deleting the ledger defeats the check; it is an
  honesty guard, not a security control. The result header (json and md) records `llmMinConfidence`, `erEnv` (the `SCHOLARCHAIN_ER_*` variables), the ledger path, the split checksum (`splitSha256`) and every
  `--override` used (`overrides`, key and value). Every tool that takes `--policy` (Evaluate, FinalDecisions, AdjudicateBand) refuses an
  `--override scholarchain.er.llm.model=...` that differs from the frozen policy's `llmModel`; the message names the key only. Remaining bypasses, documented limits
  (an honesty guard, not a security control): a fresh `--ledger` path, a copied split file (same checksum, empty ledger beside it), deleting the ledger, and a
  model changed through the config file default (no override, no `SCHOLARCHAIN_ER_*` variable) is not compared. The scope is not widened.
- `FrozenPolicy` now also freezes `llmMinConfidence` (the `er.llm.min-confidence` in force at `Calibrate`, applied by every tool that takes `--policy`) and
  `erEnv` (the `SCHOLARCHAIN_ER_*` environment at `Calibrate`; `Calibrate` `--override` values go to `parameters` as `override.<key>`). A tool run with
  `--policy` fails when the current `SCHOLARCHAIN_ER_*` environment differs from `erEnv`; the error names the variables, never the values. `Calibrate` now also freezes `llmModel` (the model in force). All three fields are
  optional on read, so an older frozen file (no fields) is still read and skips the env check. Positive class = ACCEPTED vs MATCH; rejected, unresolved and conflict count as predicted non-match. Output per
  entity kind: confusion matrix, precision, recall, F1, coverage (accepted + rejected), unresolved rate, conflict rate, metrics by match path, and
  every FP and FN with its features and reason codes; plus all stored predictions so the metrics can be recomputed.
- `AdjudicateBand` prints the plan (eligible, cached, calls planned, model, estimated tokens and cost) and calls nothing without `--confirm`.
  `--priority-pairs <csv with pair_id>` moves those pairs to the front; `--max-calls` overrides the config cap downward or upward (cost guard still applies).
- `FinalDecisions` writes `MatchDecisionV2` JSON Lines (sorted by pair) and a summary of counts by outcome and path.

## 8. Data layout and privacy

`gold/`: `review-queue-v1.csv`, `gold-labels-v1.csv` (human-authored; agents never write it), `gold-split-v1.csv`, `LABELING-GUIDE.md`.
`evaluation/`: frozen policy, sensitivity table, held-out results, final-decisions summary. The queue shows names, coauthors, titles, year, venue,
public IDs and affiliation strings only.

## 9. Pure-function API (for HJ6/HJ7 and tests)

See the B1 report for signatures. Main entry points: `Names.parse`, `Names.similarity`, `Names.initialsCompat`, `Blocking.keys | primary | fallbacks |
eligible | candidate | generate`, `Features.compute | researcher | work | preprintVersionPair`, `Policy.score | decide | applyAdjudication`, `Decisions.build`,
`ErConfigLoader.load | resolve`, `AdjudicationValidator.parse`, `AdjudicationInput.recordJson | inputHash`, `AdjudicationCache.key | keyOf | recordMatches`, `FrozenPolicy.erEnv | envDrift | splitChecksum`,
`GoldSplitter.split`, `Calibration.sweep | choose | calibrate`, `Evaluate.evaluate`.

## 10. Known limitations

No citation features; venue and affiliation are not persisted by Phase 04; year buckets lose boundary pairs; names are compared per mention, so an
author with several mentions yields several candidate pairs; mapping accepted pairs to researcher clusters (`ResolvedMention`) is not done here.

## 11. Repair-03 policy note (version label)

Repair-03 changed policy SEMANTICS (rules 0, 1b and the reason `doi_disagree_possible_version`) and added one feature key (`doi_preprint_version_pair`). No wire
format changed (`MatchDecisionV2`, `AdjudicationRecord` and `MatchCandidate` are untouched; `FrozenPolicy` gained two optional fields). `er-v1` stays the
policy version and `er-features-v1` the feature set label: `er-v1` has not been frozen or released, and Part B freezes it. HJ7 decisions made before
repair-03 are therefore not comparable with later ones under the same label; they must be rerun on a new run root before any labeling.

## 12. Repair-04 version note (why `er-features-v2`)

The feature set label moves to `er-features-v2` (config `er.feature-set-version` in `scholarchain-er.v1.conf`, `Features.Version`, `FrozenPolicy.featureSetVersion` read
from the config at `Calibrate`). WHY: the feature map and key derivation changed since `er-features-v1` was used for any run: (1) dash normalization changes the family and
block key of names written with Unicode dashes and the values of `name_sim`, `family_exact`, `coauthor_overlap`, `author_overlap`, `first_author_agree`; (2) the new
researcher feature `family_jw`; (3) `doi_preprint_version_pair` from repair-03; (4) the preprint prefix list lost `10.1101/`. A candidate must not claim v1 when its
features differ. The policy stays `er-v1`: it has never been frozen or released. The wire format is unchanged (`featureSetVersion` is a free token). The
label is a hand-chosen version string, not a content address. Hadoop reads the label from config (`RunContext` `er.feature-set-version`), not from a literal.
