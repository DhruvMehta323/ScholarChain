# Phase 09 — code-reviewer report (review 1)

Stored by the orchestrator from the code-reviewer's returned message. The reviewer had Read/Grep/Glob only.

**Verdict: REQUEST_CHANGES**

## Per-check results

| Check | Result |
|---|---|
| Injection (user text → queries/AWS) | PASS. The question reaches only a Data model segment (`QueryPlannerAgent.scala:105`) and embedder input (`QueryWorkflow.scala:219`, `ResearcherResolverAgent.scala:213`). Graph calls use allowlisted templates with typed IDs/window labels only (`GraphAgents.scala:42,137,203-208,287-291`). The keyword fallback selects operations only (`QueryPlannerAgent.scala:290-309`). Filters are validated tokens and never reach a query. |
| Grounding / verifier strength | FAIL (H1, H2, H3) |
| Abstention | PASS with residual risk M2 (`QueryWorkflow.scala:96`, `ResearcherResolverAgent.scala:207`) |
| Graph vs semantic separation | PASS in the builder (`EvidenceBuilderAgent.scala:40-53`). The verifier's reference pool includes semantic excerpts (part of H2). |
| Secrets | PASS with L2. Key read at call time via the configured name, sent only in the x-api-key header, toString redacted (`ModelClients.scala:78-98,134-135`); Bedrock catch blocks copy no exception text (`:277-285`); tests in `ModelRuntimeSuite.scala:223-310`, `AuditSuite.scala:254-260`. |
| Prompt/schema versioning | PASS. Versioned files with sha256 recorded (`ModelRuntime.scala:41-49`, `QueryWorkflow.scala:334-343`); data wrapped, close-tags neutralized (`ModelRuntime.scala:105-123`); prompts forbid chain-of-thought. |
| Benchmark independence | PASS with L3. Expected values from L4 files with input sha256, loadRunId, sparkRunId, `"model":"none"` (`benchmark-v1.json:23-95`, `BenchmarkGenerator.scala:738-773`). |
| Module boundaries | PASS. No storage/spark/hadoop/ER/services import under `modules/rag/src`. |
| Ownership | PASS |
| Frozen contracts | PASS. No tracked file changed; new types additive. |
| Hidden reasoning in artifacts | PASS |
| Tests that would pass if broken | FAIL (M4) |
| Claims not backed by output | No false claim found. The reviewer could not run commands; test counts, R1/R2 diff and the benchmark SHA are UNVERIFIED by the reviewer (the orchestrator observed the diff and SHA). From files: `R1/summary.json` passed 34/44, evidenceCheckPassed 44, modelCalls 84. |

## Findings

- **H1 HIGH (rag-engineer).** `ClaimVerificationAgent.scala:215-222` (`numbersIn` `:326-330`): every digit run inside the subject and stored string fields (64-hex IDs, community/component IDs, window labels) counts as covered. Failing inputs: "In window 1000-9999, researcher:sc1:… has 9999 distinct coauthors." (`Phase09BenchmarkSuite.scala:184`); "… has 2004 distinct coauthors" (`VerifierGroundingSuite.scala:29`); "has 7 coauthors" when the ID contains "…a7f…". Fix: no substring-of-ID exemption; whole-token equality with a stored numeric field at most.
- **H2 HIGH (rag-engineer; tests test-engineer).** `ClaimVerificationAgent.scala:234-236`: a claim with entity references but no assertion and no digits is VERIFIED (only `refs.isEmpty` downgrades); the reference pool (`:188-190`) includes semantic excerpts. Failing inputs: "researcher:A and researcher:B are direct coauthors." citing A's metrics and B's identity item → VERIFIED (fabricated edge); "researcher:A has twelve coauthors." → VERIFIED; "The path from researcher:A to researcher:B passes through Hedy Lamarr." → VERIFIED; "Grace Hopper (researcher:A) …" with a wrong name → VERIFIED. Fix: VERIFIED only when every reference and relation is covered by a matching assertion, otherwise at most partially_supported; reference pool from deterministic items only.
- **H3 HIGH (rag-engineer; tests test-engineer).** `ClaimVerificationAgent.scala:199-222`, `:202-204`: assertions not bound to the text's field, subject or window; window-less assertions match any window; the text window is checked only against a pool (`:193-196`). Failing inputs: wrong field (text says coauthors, assertion is maxTeamSize with the same value); wrong researcher (text names B, assertion is for A); wrong window ("In 2015-2019 … 12 coauthors" with assertion window 2020-2024; prevWindow string puts 2015-2019 in the pool); wrong hop order in the text with a true length assertion. `RUNTIME-AGENTS.md:54` is inaccurate as a result.
- **M1 MEDIUM (rag-engineer).** `AnswerSynthesisAgent.scala:325-333,362-366`: TemplateSynthesizer does not assert `neighborhood.jointPublicationCounts` or `path.edgeIds` → 10 strict benchmark failures.
- **M2 MEDIUM (rag-engineer).** `ResearcherResolverAgent.scala:212-230,296`: candidates only from the top-100 vector hits; a second exact-match homonym ranked past 100 lets a single match resolve. Mitigation: when the query saturates VectorTopK, a name-only single match is Ambiguous (`candidates_truncated`) unless an ID is supplied.
- **M3 MEDIUM (rag-engineer).** `QueryWorkflow.scala:51-64`, `AuditAgent.scala:149-171,235-249`: queryId/sessionId depend only on question and constraints, so asking the same question twice yields `session_conflict` and the session keeps pointing at the first run's bundle.
- **M4 MEDIUM (test-engineer).** No tests for H2/H3 inputs; `SynthesisVerificationSuite.scala:76-80` passes only by fixture luck (H1).
- **L1 LOW (rag-engineer).** `BenchmarkResponders.scala:79` `entity:unknown` for semantic items; printed via `TemplateSynthesizer` `:450`.
- **L2 LOW (domain-engineer).** `RuntimeConfig.scala:160-162` accepts `anthropic-api-key-env-var = ANTHROPIC_API_KEY`; reject it or require the `SCHOLARCHAIN_` prefix.
- **L3 LOW (rag-engineer).** `BenchmarkGenerator.scala:141-157,633-638` reuses the runtime NameKey for expectations.
- **L4 LOW docs (rag-engineer).** `AuditAgent.scala:28` / RUNTIME-AGENTS.md §5 say the question is stored only as a hash, but `QueryAudit.plan` holds it; §10 override key; `:54` (H3).
- **L5 LOW (rag-engineer).** `AnswerSynthesisAgent.scala:352-356,369`: `detail` computed, never rendered.
- **L6 LOW (rag-engineer).** `ResearcherResolverAgent.scala:97`: unsynchronized mutable identity cache.

---

# Review 2 (reread of repair 01 only)

**Verdict: REQUEST_CHANGES.** The reviewer could not run commands. From `R1/summary.json`: passed 44/44, evidenceCheckPassed 44, modelCalls 84.

| Finding | Resolution | Evidence |
|---|---|---|
| H1 | RESOLVED | Numbers read from compacted text (IDs, windows, display names → placeholders) `ClaimVerificationAgent.scala:421-449,231`; covered only by an assertion of that value `:591-609`; tests `VerifierRepairSuite.scala:533-593`, `SynthesisVerificationSuite.scala:77-86`, `Phase09BenchmarkSuite.scala:222-234` |
| H2 | PARTIALLY RESOLVED | Review inputs now partial/unsupported (`VerifierRepairSuite.scala:121-302,600-647`); one covered reference anchors any predicate wording (`:318`) → N1 |
| H3 | PARTIALLY RESOLVED | Review inputs caught (`VerifierRepairSuite.scala:353-529`); subject binding skipped for non-researcher subjects (`:603-606`) and leading-number fallback (`:605`) → N2 |
| M1 | RESOLVED | `AnswerSynthesisAgent.scala:334-336,373-376`; tamper tests `VerifierRepairSuite.scala:767-787` |
| M2 | PARTIALLY RESOLVED, deviation accepted with note | `ResearcherResolverAgent.scala:181,186-193,251-252`; tests `ResearcherResolverSuite.scala:167-211` |
| M3 | RESOLVED | `QueryWorkflow.scala:68-70`, `AuditAgent.scala:137,253`; tests `AuditSuite.scala:208,242,258`; same-millisecond residual disclosed |
| M4 | RESOLVED for review-1 inputs | N1–N4 untested |
| L1 | RESOLVED | `BenchmarkResponders.scala:82-83`; test `SemanticAndEvidenceSuite.scala:190` |

New findings. The reviewer traced these by reading the code; none was run.
- **N1 HIGH (rag-engineer; tests test-engineer).** `ClaimVerificationAgent.scala:289-319` (anchoring `:318`): string and boolean assertion values are never bound to the text, so negations, classifications and ordinals pass. Inputs → VERIFIED:
  - (a) "Grace is not a bridge." with assertion classification=bridge.
  - (b) "No collaboration path between Ada and Grace was found in window W." with true found=true, from and to assertions.
  - (c) "Ada and Alan have never coauthored." with the true edge assertions.
  - (d) "X is ranked first by coauthorCount in W" with a true `order` where X is not first.
  - Fix: bind enumerated or boolean values to closed wording; cap negation, ordinal or superlative wording not bound to an assertion.
- **N2 HIGH (rag-engineer).** Wrong-subject binding.
  - (a) Non-researcher subjects bind to anyone (`:603-606`): "In W, Ada has n publications." citing the ada-alan edge, with jointPublicationCount=n → VERIFIED (a joint count passed off as Ada's total).
  - (b) Leading-number fallback (`:605`): "In W, 2 distinct coauthors: Ada; 1 distinct coauthors: Alan." with true ada=1, alan=2 → VERIFIED (values swapped).
  - Fix: the owner must be the subject, embedded in it, or a researcherA/B, from/to or order value of the assertion; drop the `mentioned` fallback when more than one researcher is mentioned.
- **N3 MEDIUM (rag-engineer).** A noun shared by two fields lets values swap: "went from 12 to 8 coauthors" with true prev=8, cur=12 (`:524`); "average team … 6 members" with maxTeamSize=6 (`:537`).
- **N4 MEDIUM (rag-engineer).** Nothing ties an edge or work to a hop: "… Ada -> Alan -> Grace; its first hop is <eAlanGrace>." → VERIFIED (`:562-563,667-672`; `strArray` skips nested `hopWorkIds`).
- **L7 LOW (rag-engineer).** `refPool` includes semantic items' `sourceEntityId` (`:236`), contrary to rule 2.

Disclosed deviations:
- (a) Narrowed M2: **ACCEPTED WITH NOTE.** Residual: a homonym ranked below non-matching profiles is missed. Disclose for production embedders.
- (b) Fabricated relation kept as partially_supported at 0.5: **NOT ACCEPTED** for two reason codes. `relation_without_assertion` / `reference_without_assertion` on a pairwise relation claim should be Unsupported and removed (CLAUDE.md: "Unsupported claims are not reported"). `display_name_mismatch` should be Conflicting. Capping at partial stays acceptable for `unverified_proper_noun` and `qualitative_wording_unchecked`.

Other checks: injection PASS (stored names via `Regex.quote` `:424`); secrets PASS; boundaries PASS; ownership PASS; hidden reasoning PASS. "No production edits by test-engineer" rests on the orchestrator's mtime check.
Reviewer's risk note: with a real synthesis model and the default `llmVerification=false` (`QueryWorkflow.scala:454`), N1 and N2 cases would reach users labeled supported at confidence 1.0. The benchmark uses template wording and does not exercise them.

---

# Review 3 (reread of repair 02 only)

**Verdict: APPROVE WITH NOTES.** The reviewer could not run commands. From `R2/summary.json`: passed 44/44, evidenceCheckPassed 44, modelCalls 84, no failedIds.

| Item | Resolution | Evidence |
|---|---|---|
| N1 | RESOLVED for (a)–(d); residual: closed wording lists | `ClaimVerificationAgent.scala:842-844,921-935,849-852,943-950,964-1003`; tests `VerifierRound2Suite.scala:151-233` |
| N2 | RESOLVED for (a), (b); residual N5/N6 | `:717,661-665,719-722,723-730,710-712`; tests `VerifierRound2Suite.scala:237-290` |
| N3 | RESOLVED | `:631-634,677-684,685-691`; tests `VerifierRound2Suite.scala:294-345`; variants traced unsupported, never wrongly VERIFIED |
| N4 | RESOLVED for the stated, ordinal and arrow-adjacent forms; residual R2 (disclosed) | `:1021-1090,1030-1033,1052,1013-1016,256`; tests `VerifierRound2Suite.scala:348-400` |
| Deviation (b) | RESOLVED | `:357-362,837-838,331,333-355,367-375`; tests `VerifierRound2Suite.scala:403-475`, `VerifierRepairSuite.scala:121-138,175-177,273-285` |
| L7 | NOT RESOLVED, deferred LOW, disclosed | `:261`; `RUNTIME-AGENTS.md:157` |

New findings. The reviewer traced these by reading; none was run.
- **N5 MEDIUM (rag-engineer; tests test-engineer), deferred.** At `:723-730` a joint publication count can be presented as one researcher's total. The guard checks only that both pair members precede the number, not joint wording. "In window W, Ada, who coauthored with Alan, has N publications." with the true edge assertions → VERIFIED. Fix: bind jointPublicationCount only next to joint wording. Fix before any real synthesis model runs with `llmVerification=false`.
- **N6 LOW (rag-engineer).** At `:609-610,710` the owner is the nearest preceding mention: "In window W, Grace is among the 12 coauthors of Ada." binds 12 to Grace (VERIFIED when Grace's count is 12). `RUNTIME-AGENTS.md:76` ("never guessed") is inaccurate for this word order.
- **N7 LOW (rag-engineer, docs).** Comparatives ("more than", "fewer", "twice") and change-direction words ("increased", "fell") are unchecked and undisclosed: "Ada has 12 coauthors, more than Alan" (alan=15) → VERIFIED; "increased to 8" (prev 12) → VERIFIED.

Disclosed residuals the reviewer confirmed:
- R1, negation outside the list: "non-bridge", "unconnected/disconnected", "lack a path".
- R2, hops named in other words: "the hop from Alan to Grace is <edge>", "initial hop".
- R3, ordinals by position: caught in every form tried.

Gate assessment: kept claims cite existing evidence; every number equals an asserted stored value; path order and per-hop evidence are checked when written with arrows or ordinals; identity names are checked; unsupported and conflicting claims are removed; partial claims are labeled at 0.5. What remains is misattribution through English phrasings outside closed lists, always using real stored values. The reviewer judges this disclosable, not blocking, because the work order makes the LLM check optional.

Other checks: injection PASS (`Regex.quote` `:467`, static regexes); secrets PASS; boundaries PASS (imports `scholarchain.core.*` only); ownership PASS; hidden reasoning PASS; TemplateSynthesizer PASS (44/44); RUNTIME-AGENTS.md rules 1–12 (`:55-70`) match the code except as noted in N6/N7.
