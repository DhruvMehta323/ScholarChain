# Phase 09 — repair 02 (final round)

Trigger: code-reviewer review 2 = REQUEST_CHANGES (`review-report.md`, "Review 2"). Round 2 of at most 2 (§7). If review 3 is not APPROVE or APPROVE WITH NOTES, the orchestrator STOPs for the human: a third round is not allowed.

Repair policy (§0.9): fix HIGH and MEDIUM findings that concern correctness, grounding, security or injection. Defer LOW with disclosure.

## R2a — rag-engineer (`modules/rag/**` except `src/test/**`, `prompts/**`)

- N1 (HIGH): bind string, enumerated and boolean assertion values to the claim text through closed wording. Negation ("not", "no", "never", "neither", "none", "without"), ordinal and superlative wording ("first", "second", "top", "highest", "most", "least", …) must be bound to a matching assertion. Otherwise the claim is UNSUPPORTED (or CONFLICTING when it contradicts a true assertion, e.g. "not a bridge" vs classification=bridge, "no path" vs found=true, "never coauthored" vs a true edge, "ranked first" vs an `order` where the researcher is not first).
- N2 (HIGH): a number binds only to an assertion whose subject is the owning researcher, contains it, or names it as researcherA/B, from/to or an `order` member. Non-researcher subjects get no wildcard. With more than one researcher mentioned, the `mentioned` fallback is dropped and an unowned number is UNSUPPORTED.
- N3 (MEDIUM): fields that share a noun must be told apart by position or qualifier ("from X to Y" ⇒ prev then current, "average" vs "maximum"/"largest" team size). A swapped pair or the wrong qualifier is CONFLICTING or UNSUPPORTED.
- N4 (MEDIUM): an edge ID or work ID stated for a specific hop ("first hop", "hop 2", or adjacent to a hop's endpoints) must match that hop's asserted edge or works, else CONFLICTING. Include the nested `hopWorkIds` in the checks.
- Deviation (b): `relation_without_assertion` / `reference_without_assertion` on a pairwise relation claim ⇒ UNSUPPORTED (removed). `display_name_mismatch` ⇒ CONFLICTING. Partial stays only for `unverified_proper_noun` and `qualitative_wording_unchecked`.
- Keep TemplateSynthesizer output VERIFIED (benchmark 44/44). Benchmark JSON and prompts unchanged unless unavoidable (disclose).
- Update RUNTIME-AGENTS.md: the rule set, the M2 residual for production embedders, and the remaining limits.

## R2b — test-engineer (`modules/rag/src/test/**`, `modules/services/src/test/**`, `modules/core/src/test/**`)

- Tests for every N1 input (a)–(d), N2 (a)–(b), N3 (both) and N4, and for both deviation-(b) changes, asserting exact statuses. Update existing tests that pinned the old partial status for those two reason codes; that pinned behaviour is exactly the change requested. Rerun rag, core Runtime* and services; run the composed benchmark twice (R1, R2).

## Deferred LOW (disclosed)

L2 (domain-engineer: config accepts `ANTHROPIC_API_KEY` as the env-var name), L3, L5, L6, L7 (semantic `sourceEntityId` in `refPool`; limited impact because an assertion is still required).

## Outcome

### R2a — rag-engineer (agent STATUS: FAILED by design; production done, 8 pinned tests await R2b)

- Files modified: `modules/rag/src/main/scala/scholarchain/rag/runtime/ClaimVerificationAgent.scala`, `modules/rag/RUNTIME-AGENTS.md`. TemplateSynthesizer, benchmark JSON and prompts unchanged.
- Rule changes:
  - N2: number ownership is bound to the assertion's researchers; non-researcher subjects get no wildcard; the `mentioned` fallback is removed; jointPublicationCount needs both researchers.
  - N3: "from X to Y" position for prev/current; team-size numbers need an average/maximum qualifier.
  - N1: closed negation, classification and ordinal/superlative wording bound to found, classification, order or coauthorIds (CONFLICTING on contradiction, UNSUPPORTED when unbound).
  - N4: per-hop edge and work check (`hop_evidence_mismatch`).
  - Deviation (b): pairwise relation without assertion → UNSUPPORTED; `display_name_mismatch` → CONFLICTING.
- Agent console run of every Review-2 failing input: N1a/b/c/d CONFLICTING; N2a/b UNSUPPORTED; N3a/b UNSUPPORTED; N4 variants CONFLICTING; (b) pair claims UNSUPPORTED and the name mismatch CONFLICTING. True controls VERIFIED; all 20 TemplateSynthesizer fixture claims VERIFIED.
- Agent-observed: `rag/testOnly *` 316 total, 7 failed (VerifierRepairSuite, old pinned statuses); `Phase09BenchmarkSuite` 59 total, 1 failed (pinned display-name status), benchmark R1 44/44 strict; `rag/scalafmtAll` reformatted 1 source.
- Risks (agent): the closed English wording lists miss phrasings outside them (and `llmVerification=false` by default); the stricter owner rule may drop more claims written by a real model; `membersOf` also includes hops and coauthorIds.

### R2b — test-engineer (STATUS: COMPLETE, agent report)

- Created `modules/rag/src/test/scala/scholarchain/rag/runtime/VerifierRound2Suite.scala` (26 tests: N1a–d, N2a–b, N3a–b, N4 ×7, deviation (b) ×4, partial controls ×2, fixture sanity). Updated the 8 pinned-status items in `VerifierRepairSuite.scala` and `Phase09BenchmarkSuite.scala` (~311). Added 1 services end-to-end test: N1 negation and N2 wrong owner on real L4 data are neither verified nor in the answer text.
- Agent-observed after scalafmt: rag 342/342, core `Runtime*` 43/43, services 88/88 (Phase09BenchmarkSuite 60). Benchmark 44/44 strict in R1 and R2.
- Orchestrator verification:
  - `diff -r …/R1 …/R2` printed nothing (IDENTICAL).
  - Newest rag production mtime 23:14:42 (R2a) predates the R2b test files (23:18:33).
  - Benchmark SHA-256 still `e511d175…21e6`.
  - `git status`: no tracked change.
- CHECK after repair 02 (canonical command): testFull hadoop 192, sparkAnalytics 268, entityResolution 293, storage 323, rag 342, services 88, core 917. **2,423 passed, 0 failed**. `[success] elapsed time: 1512 s`; marker `exit=0`. 0 occurrences of `AwsItSuite`.
- Review 3 (code-reviewer): **APPROVE WITH NOTES**. N1–N4 and deviation (b) RESOLVED. New: N5 MEDIUM (joint count presented as an individual total), deferred to before real-model use; N6, N7 LOW (disclosure). L2, L3, L5, L6, L7 stay deferred. Details in `review-report.md` (Review 3).
