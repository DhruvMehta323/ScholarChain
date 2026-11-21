# Repair 03: fixes from review pass 1 (ONE round)

Routed on the human's decisions. Order: entity-resolution-engineer, hadoop-engineer, test-engineer. One sbt user at a time. Agents do not run the canonical marker command (their cwd resets); the orchestrator runs CHECK from the repo root.

## Human decisions
1. M1: an ORCID disagreement gives REJECTED (path exact_id, reason `orcid_disagree`) in the plain case. CONFLICT only when the disagreement sits alongside strong match evidence (same DOI and same author position, or HJ3 conflict evidence). Policy-only; HJ6 and features stay unchanged.
2. L2: reason code `doi_disagree_possible_version` for a preprint/published DOI disagreement. The outcome stays CONFLICT.
3. M4 (changed by the human after CHECK 3, final form): no WIP commit and no staged tree hash; the phase is committed once, at the end. The new real runs use `--code-version "74eee4e+repair03"`. That label differs from `74eee4e+uncommitted`, so the new run IDs do not clash with rootE, rootF, rootG and rootF2.
   - History: two earlier versions of this decision (a WIP commit hash, then a `git write-tree` hash) arrived with literal placeholders, `<HASH>` and `<CODEVERSION>`. The orchestrator checked and found the index empty, so `git write-tree` only returned the tree of `HEAD`. No run was started with either.
   - Limit, stated by the orchestrator: `74eee4e+repair03` is a hand-chosen label, not a content address. It separates these runs from the earlier ones. It does not identify the code, so if the code changes after these runs the same label would name different code, and the run ID would not change. M4 is therefore reduced, not closed. The code must stay unchanged from CHECK 3 while the runs and the review happen. The orchestrator checks this before the run (see "After repair-03").

## entity-resolution-engineer
- H1: accept on an ORCID match only if the names fit (`family_exact` = 1, or `name_sim` at or above a stated floor). Otherwise CONFLICT with reason `orcid_name_mismatch`. Document the floor in ER-CONTRACTS.md. Do not tune it on any labels.
- M1, L2: as decided above.
- M2: on every cache hit compare inputHash, modelId, promptVersion and policyVersion with the lookup key. At load drop any line whose key does not equal the key function's value for its own fields.
- M3: build the request inside `try`, or reject non-visible-ASCII keys in `ApiKey.fromEnv`, naming only the variable.
- L1c: fix the `Blocking.generate` comment, or add x1 keys.
- L5 a-c: freeze `min-confidence` and the env overrides in `FrozenPolicy` and in the Evaluate header; check the split checksum; tighten the held-out-once check.

## hadoop-engineer
- L1a-b: x1 keys are not typed as primary; document that x1 blocks have no fallback. No other changes.

## test-engineer
- Tests for H1 (Wei Wang / Li Zhang sharing an ORCID), M1, M2 (tampered outcome with the same IDs), M3 (key with CR/LF), the L1d cap test and L5. Also update the existing tests that pinned the old ORCID-disagree CONFLICT behavior (the ER agent lists them).

## Deferred (not fixed now)
L3, L4, L7, L8, L9, M5 (Phase 06 must not union accepted pairs until a cannot-link rule exists), and the CLAUDE.md boundary wording.
Added by the human after CHECK 3:
- L1a: x1 keys stay typed `primary`, as documented in JOB-CONTRACTS.md.
- The HJ7 `ACCEPTED` counter (`Hj7Decision.scala:74`): reports quote per-outcome counts from the Decisions counter group only, never `ACCEPTED` for HJ7.
- L1d: the `src/it` cap test stays as it is; `ErX1CapUnitSuite` covers the cap.

## After repair-03
CHECK from the repo root (done: CHECK 3, exit=0). Then hadoop-engineer runs two new roots (rootH and rootH2) with `--code-version "74eee4e+repair03"` and no source edits, compares them, and reports decision counts by outcome and path (from the Decisions counter group only) and how many of rootG's exact_id accepts changed outcome. Then D re-reviews only the repair-03 changes. If D returns APPROVE or APPROVE WITH NOTES, Q samples from the new root and the orchestrator stops at the labeling gate. If D returns REQUEST_CHANGES, the orchestrator stops and shows the human the findings.

## Outcome
Agent-reported unless marked as observed by the orchestrator.

- entity-resolution-engineer: H1 (ORCID accept needs `family_exact` = 1 or `name_sim` >= `Policy.OrcidNameFloor` = 0.85, hand-set and not tuned on labels, else CONFLICT `orcid_name_mismatch`), M1 (plain ORCID disagreement is REJECTED on exact_id, reason `orcid_disagree`; CONFLICT kept beside HJ3 evidence, another id disagreement or position_agreement = 1), L2 (new work feature `doi_preprint_version_pair`, preprint-server prefixes plus title_sim >= 0.9; reason `doi_disagree_possible_version`, outcome stays CONFLICT), M2 (hit and load validation against the lookup values), M3 (non-visible-ASCII keys rejected; request built inside `try`), L1c (`Blocking.generate` comment fixed, no x1 keys added), L5 a-c (held-out ledger, split-checksum check, frozen min-confidence and SCHOLARCHAIN_ER_* env with drift check). `er-v1` and `er-features-v1` labels unchanged, no wire change; `FrozenPolicy` gained two optional fields.
- hadoop-engineer: L1b done (JOB-CONTRACTS.md: x1 blocks have no fallback; an oversize x1 block is skipped and listed). **L1a NOT done**, blocked on domain-engineer: `BlockKeyType` has only `primary` and `fallback` (`Enums.scala:118-120`), so x1 keys stay typed `primary`. Awaiting the human's decision (recommended: defer). Observation: `Hj7Decision.scala:74` increments ACCEPTED for every decision; not changed.
- test-engineer: 10 old-behavior tests updated; new tests for H1, M1, L2, M2, M3, L5 and an x1 cap unit suite (`ErX1CapUnitSuite`, 5 tests). The IT cap test at `src/it/.../BlockingCandidateItSuite.scala:91` still counts x1 members with `Blocking.keys` (it counts 0): it belongs to hadoop-engineer and was not edited. Safety tests were reasoned about, not mutation-tested.
- CHECK 3 (orchestrator, canonical, from the repo root): **marker `exit=0`**, `[success] elapsed time: 235 s`. core Passed 640, hadoop Passed 192, entityResolution Passed 262; Failed 0, Errors 0 in each. Total **1,094 passed, 0 failed** (baseline 638, +456).
- Not done in this round (deferred, per the human): L3, L4, L7, L8, L9, M5 (Phase 06 must not union accepted pairs until a cannot-link rule exists), and the CLAUDE.md boundary wording for the hadoop to entityResolution edge.
- Consequence: the policy semantics changed, so rootE, rootF, rootG and rootF2 are superseded. The review queue must be sampled from a new root run with the WIP commit hash as `--code-version`.
