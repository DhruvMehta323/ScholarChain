# Phase 11 — Repair 01

## Outcome (after the human's decisions of 2026-11-24)

| Item | Owner | Outcome | Rerun evidence |
|---|---|---|---|
| H1 | infra-engineer (MERGE-1, Terraform) | Accepted by the human as routed to the lane2→main merge; not open in Phase 11. Env names verified from source: `SCHOLARCHAIN_RAG_MODEL_PROVIDER`, `SCHOLARCHAIN_RAG_MODEL_BEDROCK_MODEL_ID`. | n/a |
| H2 | infra-engineer (MERGE-2, Terraform) + service-engineer (docs) | Routed to the merge; F-SVC-1 documented Voyage selection in `modules/services/API.md:166-181`. | Reviewer reread: PASS with LOW 2 |
| F-SPARK-1 KCore dedupe and self arcs | spark-engineer | Fixed in `KCore.scala:35`; tests unchanged. | Agent targeted: AdversarialGraphSuite 19/19 passed; reviewer reread PASS. **`KCore.scala` fails scalafmtCheck in CHECK 2.** |
| F-TEST-1 missing-metadata tests | test-engineer | 23 new tests in 6 suites (agent targeted: all pass). | **Four suites fail scalafmtCheck in CHECK 2**; reviewer MEDIUM 1 plus LOW 3-6. |
| M1, all LOWs | various | Deferred with disclosure (human decision). | n/a |

CHECK 2: `exit=1`, scalafmtCheck failed in hadoop, storage, rag and entityResolution (Test) and sparkAnalytics (Compile); no test ran. **STOPPED (§6: CHECK failed after the repair for a non-resource reason).** The fix is formatting only (owners spark-engineer and test-engineer), but the one repair round is used and the rerun allowance covers only a timeout or OOM, so it needs the human's decision.

### Human-approved formatting pass (after CHECK 2)

| Item | Owner | Outcome | Rerun evidence |
|---|---|---|---|
| FMT-SPARK | spark-engineer | `KCore.scala` scalafmt only | Agent: `sparkAnalytics` scalafmtCheck `[success]` |
| LOW-2 | service-engineer | `API.md:181` reworded (defined in core `ModelConfig`, not consumed by rag or services) | docs only |
| FMT-TEST + LOW 3-6 | test-engineer | 5 suites formatted; assertions strengthened; +2 tests (ER control split, hadoop missing country) | Agent: Test/scalafmtCheck `[success]` x5; targeted 3/3, 3/3, 6/6, 7/7, 3/3 |

**CHECK 3: `exit=0`, 2,662 passed, 0 failed (+107 over Phase 10's 2,555).** The reviewer did not reread this pass (the human ordered CHECK 3, then STOP).

---

## Original draft (as written before the decisions)

Inputs: T's failing tests (CHECK 1: 2,637 total, 2 failed, exit=1) and R's findings (`review-report.md`, REQUEST_CHANGES). Policy (§0.8): fix CRITICAL, HIGH and MEDIUM on correctness, data loss, security, grounding or Phase 14 breakage; defer LOW with disclosure. One round.

## Needs a human decision before routing (§6 STOP)

| ID | Sev | Owner(s) | Why it needs you |
|---|---|---|---|
| H1 generation model not connected | HIGH | infra-engineer (Terraform: set `SCHOLARCHAIN_RAG_MODEL_PROVIDER`, `SCHOLARCHAIN_RAG_MODEL_BEDROCK_MODEL_ID` or the Anthropic model ID; fill `bedrock_model_arns`), OR domain-engineer (map `models.generation-model-id` into the rag runtime) | Terraform is out of Phase 11 scope; the domain alternative changes the frozen core configuration contract. Also choose the model: the gate says Haiku 4.5, INFRA-CONTRACTS notes Haiku 5.5 pricing. |
| H2 embedder chosen by Voyage secret presence | HIGH | service-engineer (explicit provider selection) + infra-engineer (inject the Voyage secret only for `embedding_provider = "voyage"`; pass the provider) ; domain-engineer if a new `embedding.provider` key is added | Terraform out of scope; a new key changes the embedding configuration contract. |

## Routable within Phase 11 once you decide (proposed)

| ID | Sev | Owner | Proposed action |
|---|---|---|---|
| H2 (service part) | HIGH | service-engineer | Select the embedder from explicit configuration, not from the presence of the secret (depends on the H2 key decision). |
| M1 | MEDIUM | service-engineer (document `config` probe as a backstop); deploy-engineer / infra-engineer for Phase 14 | Phase 14 smoke tests must GET `/readyz`; optional ECS container health check (Terraform, yours). |
| Test gap (R) | — | test-engineer | Dry-start test with the Phase 12 per-service env map asserting the chosen model provider and embedder (would expose H1/H2). |
| Test gap (T) | — | test-engineer | "Missing topic, institution, abstract or profile metadata" category: not added, existing coverage unverified. |

## Proposed deferrals (LOW, disclosed)

- T: `AdversarialGraphSuite` KCore duplicate-arc and self-arc tests (spark-engineer; latent because `Graph.arcs` and `Graph.canonicalPairs` never emit them). Choice needed: spark-engineer adds `distinct()` / drops self arcs in `KCore` (small, makes CHECK green), or documents the precondition and test-engineer re-scopes the tests. Either is required for "all tests pass".
- R: L1 Neptune URI filter no-op; L2 tag not checked against HEAD (Phase 13/14 scripts must refuse `-dirty`); L3 timeout ordering and retry multiplication; L4 orphaned async jobs; L5 unused secret on retrieval; unused `rag/evidence/*` grant; known items (b) Neptune-only endpoint override and (c) local mode not self-contained in the image.
