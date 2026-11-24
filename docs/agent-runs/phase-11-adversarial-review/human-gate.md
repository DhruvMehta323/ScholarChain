# Phase 11 — Adversarial review and pre-deployment fixes: Human Gate

Reviewer and ratifier: Dhruv Mehta (dmeht35)
Date: 2026-11-24 (CST)
Branch: main (ScholarChain), base 932fd2f (Phase 10 gate restored)
Decision: **ACCEPTED WITH NOTES**

## 1. What I reviewed

- `prompt.md` (my work order), my decisions during the phase (H1/H2 routing, the Bedrock-only runtime, the repair scope, the formatting pass approval), `agent-report.md`, `review-report.md`, `repair-01.md`.

## 2. What was accepted

- **Pre-deployment fixes from the Phase 10 gate §4:**
  1. Audit path: audits go to `SCHOLARCHAIN_STORAGE_CURATED_BUCKET` under `rag/audit/`, never a bucket named `rag`; a test observed the actual write keys.
  2. Readiness (L3): `/readyz` returns 503 with a reason code when prompts, a required prompt version or required config is missing.
  3. AWS-mode dry start: all three service mains start in AWS mode with a tripwire credentials provider, called zero times.
  - 37 tests cover the three fixes.
- **Images (P2):** three images with the real mains and `prompts/`, non-root (uid 10001), no Spark or Hadoop jars, built locally and never pushed. `/healthz` and `/readyz` returned 200 locally for query-api and retrieval (with a mounted test-data folder); the embedding-worker has no local mode and exits 2, as expected.
- **Adversarial pass:** 105 new tests (82 in T, 23 in the repair for missing topic, institution, abstract or profile metadata).
- **Repair round:** k-core now drops duplicate edges and self-loops before the calculation (pipeline results unchanged, confirmed by the reviewer); API.md documents that Voyage is selected only when its key is present; LOW 2–6 fixed in the formatting pass.
- **Tests:** CHECK 3 `sbt check` exit 0, **2,662 passed, 0 failed** (Phase 10: 2,555; +107).

## 3. Decisions taken during the phase

1. **Runtime model is Bedrock only.** No Anthropic API and no Voyage costs. Answer model: Claude Haiku 4.5 through the US cross-region inference profile `us.anthropic.claude-haiku-4-5-20251001-v1:0`; embeddings: Titan Embed Text v2. I verified on 2026-11-24 that the account lists the profile and a 5-token Converse call through it succeeds in us-east-2.
2. **H1 (answer model not wired) and H2 (embedder selected by secret presence) are accepted as routed to the lane2 → main merge**, not open within Phase 11:
   - MERGE-1: Terraform sets the runtime's own `rag.model.*` environment overrides (provider = bedrock, model id = the inference profile ID; variable names recorded in agent-report.md), removes the unused `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID`, and sets `bedrock_model_arns` to Titan Embed v2, the Haiku 4.5 inference profile and its foundation-model ARNs in the routed regions.
   - MERGE-2: Terraform stops injecting `SCHOLARCHAIN_VOYAGE_API_KEY` and `SCHOLARCHAIN_ANTHROPIC_API_KEY` into the task definitions (an empty secret blocks task start; neither is used).
3. Item 3 (k-core): fixed in code rather than re-scoping the tests.
4. One formatting-only pass plus LOW 2–6 approved after CHECK 2 failed on formatting alone (no test ran); CHECK 3 was the last allowed run.

## 4. Disclosures

- All three allowed CHECK runs were used: CHECK 1 failed (2 k-core tests), CHECK 2 failed (formatting, no test ran), CHECK 3 passed.
- **The reviewer did not reread the final pass** (formatting and LOW 2–6), because I asked for CHECK 3 and then STOP. Its last verdict, REQUEST_CHANGES, asked only for that formatting and a passing CHECK; the logic of all three repairs had already passed its reread.
- The reviewer did not review the Hadoop, Spark or entity-resolution sources in depth this pass.
- No AWS, model or embedding call was made by any agent. Whether the runtime accepts the inference profile ID end to end is verified in Phase 14.

## 5. Deferred

- M1: the `/readyz` config check cannot fire in AWS mode, because startup refuses first.
- Local mode inside the images is not self-contained (needs a mounted folder with `build.sbt` and fixture data).
- All other LOW findings, as listed in review-report.md and repair-01.md.

## 6. Before Phase 14

- **Do not deploy the `932fd2f-dirty` images.** Rebuild all three from the clean, committed merge commit with `scripts/package.sh` immediately before `deploy.sh`.
- MERGE-1 and MERGE-2 land in the merge, followed by a new Terraform plan reviewed by me.

## 7. My own checks

- [x] `.claude/.last-test-run` reads `exit=0`.
- [x] `git status --short` shows no `target/`, `datasets/raw/` or image tarballs.
- [x] `docker images | grep scholarchain` shows the three local images and nothing was pushed.

## 8. Manual changes

None to repository source. I wrote the decisions and this gate.

Accepted commit: see `commit.txt`.

Signed: Dhruv Mehta — 2026-11-24