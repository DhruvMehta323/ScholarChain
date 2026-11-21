# Repair 02: ER defects found by Work Order C (AdversarialSuite)

Routed by the orchestrator, on the human's instruction, to entity-resolution-engineer (owner of `modules/entity-resolution/**` except `src/test/**`). Source: test-engineer's C report. Observed canonical run before repair: marker `exit=1`; core 640 passed; hadoop 186 passed; entityResolution 207 total, 202 passed, 5 failed. All five failures are in `modules/entity-resolution/src/test/scala/scholarchain/er/AdversarialSuite.scala`.

## Rules
- Do not edit any test.
- No threshold, weight or wire-format change.
- Agents do not run the canonical marker command, because the shell cwd resets. Say so in the report; the human or the orchestrator runs it from the repo root.

## Root causes and required fixes
1. **Hyphenated family names** (test line 47). A hyphenated family name must give the same block key in "First Last" and "Last, First" form. `Strings.tokens` splits the hyphen today ("Anna Smith-Jones" gives `r1|jones|a`, "Smith-Jones, Anna" gives `r1|smithjones|a`). Fix it in `Text.scala` (`Names.parse`, around lines 48-55 and 204-224) without splitting the hyphen the way it does now.
2. **Supplementary-plane letters** (test line 52). `Strings.tokens` must work on Unicode code points, not UTF-16 halves, so that letters such as U+20BB7 are kept (`Text.scala`, around lines 48-55).
3. **Template injection** (test line 121). `PromptTemplate.render` must substitute all placeholders in a single pass, so that placeholder text inside a record is never expanded (`Adjudication.scala`, around lines 135-140).
4. **Foreign or tampered adjudications** (tests at lines 154 and 165). An adjudication must be validated against the candidate with `AdjudicationRecord.validateAgainst` in three places:
   - on a cache hit in `Adjudicator.adjudicate` (`Adjudication.scala`, around lines 462-465);
   - when the cache is loaded (`AdjudicationCache.open`), where a bad line is skipped and counted, never served;
   - in `Resolver.decide` and `Policy.applyAdjudication` (`Io.scala`, around lines 193-198, and `Policy.scala`, around lines 206-231).

   A foreign or tampered adjudication must never change an outcome. CONFLICT stays never overridden.

## Follow-up after the fix (orchestrator sequence)
1. test-engineer reruns the ER suites, and adds a few tests for `BuildViews` and `BuildReviewQueue`: the blind CSV has no score or feature columns, a fixed seed is deterministic, and both entity kinds are present.
2. The orchestrator runs the canonical command from the repo root and reports the observed totals.
3. Only then D (code-reviewer), with the B2 notes: ORCID disagreement as CONFLICT, and the `x1|type|id` exact-identifier block keys.

## Outcome
entity-resolution-engineer, reported by the agent (not yet confirmed by an orchestrator canonical run):
- 1 and 2: `Text.scala`. A new `Strings.nameTokens` keeps a letter-to-letter hyphen, and `Names.parse` uses it. "Anna Smith-Jones" and "Smith-Jones, Anna" now both give family "smith jones" and block key `r1|smithjones|a`. Tokenizing is by code point; `firstInitial` is now `Option[String]`. Plain `Strings.tokens` behaves as before for titles and other callers.
- 3: `PromptTemplate.render` substitutes all placeholders in one `replaceAllIn` pass, with `quoteReplacement`.
- 4: a cache hit is served only if `AdjudicationRecord.validateAgainst(record, candidate)` is Right, otherwise it is treated as a miss. `AdjudicationCache.open` skips and counts lines that fail the contract check. `Policy.applyAdjudication` has a new candidate overload that drops an invalid adjudication, and `Resolver.decide` and `Resolver.cached` use validation. The 3-argument signature is kept because `PolicySuite` calls it.
- Files: `Text.scala`, `Blocking.scala` (one `getOrElse` type fix), `Adjudication.scala`, `Policy.scala`, `Io.scala`, `ER-CONTRACTS.md`. No test edited; no threshold, weight or wire change.
- Agent-observed: `entityResolution/test` Total 207, Failed 0 (AdversarialSuite 20, 0 failed); `hadoop/testOnly ErJobsUnitSuite` Total 43, Failed 0. It did not run the canonical command, because the cwd resets.
- Risks the agent states:
  - The cache-load check is a contract check only. A valid line for the wrong pair is caught at hit time.
  - **The block key changes on real data.** A "First Last" name whose last token is hyphenated now gives `r1|smithjones|x`, not `r1|jones|x`, so HJ6 candidates and pair IDs can shift. Scores for hyphenated names can also move slightly.
