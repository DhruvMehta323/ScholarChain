# Phase 10 — Human gate

Phase: 10, RESTful microservices
Date: 2026-11-23
Reviewer and ratifier: Dhruv Mehta (human architect)
Decision: **ACCEPTED**, with three pre-deployment fixes routed into Phase 11 (section 4).

## 1. What I reviewed

- `prompt.md` (written by me; saved verbatim), my amendments during the phase (provisional contract, order, speed amendment, contract decisions 1–6, L2 split ownership), `spec-excerpt.md` (course spec sections I supplied verbatim), `repair-01.md`.
- `agent-report.md` (preconditions, A0, A1, R, B, C, the local sessions, the final checkpoint) and `review-report.md` (review 1 with 9 MEDIUM findings; the reread, APPROVE WITH NOTES).
- `modules/services/API.md` and the Phase 12 contract reconciliation list.

## 2. What was accepted

- **Services:** query-api (all public `/v1` routes), retrieval (versioned internal graph and vector routes) and embedding-worker, as named in the provisional Phase 12 contract (§Services SHA-256 `6b17edab…e2114`).
- **Routes:** `POST /v1/query` (synchronous, or 202 with a job), jobs, evidence, graph node and bounded neighbors, explain, `/healthz`, `/readyz` (with `/health` as an alias).
- **Behavior:** the spec's error model with trace IDs; `Idempotency-Key` (same body returns the stored result, different body returns 409); jobs and idempotency records in QuerySession under distinct prefixes; timeouts, bounded retries and circuit breakers; constant-time API-key authentication (`SCHOLARCHAIN_SERVICES_API_KEY` public, `SCHOLARCHAIN_INTERNAL_API_KEY` internal); structured logs without secrets; thin routes over the Phase 09 workflow; any final downstream failure fails the query with 502/503/504 rather than returning a partial answer (stricter; accepted).
- **Phase 09 carry-overs fixed and tested:** N5 (a joint count stated as a total is rejected, and the reverse), N6, N7, and L2 (`ANTHROPIC_API_KEY` rejected, in core RuntimeConfig and in the model client).
- **Local sessions:** scripted session run twice; all response pairs identical (orchestrator diff). An end-to-end API test reaches an evidence-backed answer whose cited evidence IDs all resolve.
- **Tests:** 2,423 → **2,555 passed, 0 failed** (+132).

## 3. Decisions taken during the phase

1. The service contract was read **provisionally** from the uncommitted Phase 12 worktree (read-only, hash recorded). Reconciliation happens at the Phase 12 merge.
2. Contract conflicts resolved by me: the Anthropic key name, health paths, a public API key, a separate internal key, job storage in QuerySession, a required Neptune endpoint (AWS mode fails at startup with a clear error when it is missing).
3. Speed amendment: CHECK only at the end and after the repair; one review pass; one repair round; no image rebuilds in this phase.
4. Three extra storage prefixes and a local-only planner stand-in were added (disclosed in agent-report.md).

## 4. Fixes required before Phase 14 (routed into Phase 11)

1. **Audit path bug** (found by the Phase 12 review): `Composition.scala` treats the relative rag prefix as a full S3 path, so audits would target a bucket named `rag`. The audit location must be the curated bucket (`SCHOLARCHAIN_STORAGE_CURATED_BUCKET`) with prefix `rag/audit/`.
2. **Images:** the three images with the real service mains have never been built, and they lack the `prompts/` folder that AWS-mode queries need.
3. **L3:** `/readyz` does not detect the missing `prompts/` folder, so an image could report ready while every query fails.

Also: the AWS composition code compiles but has never been executed.

## 5. Deferred notes

Review 1 L1–L12 (except L3, above) and reread N1–N7 are deferred and listed in agent-report.md. F4: no test exercises the idempotency concurrency race directly.

## 6. Run history and disclosures

- The baseline CHECK passed first time (2,423). One long CHECK earlier in the phase was slowed by the Mac sleeping; I then kept it awake with `caffeinate`.
- A0 side effects: the shared sbt server was replaced after a hung build edit; the public Temurin base image was pulled; three local placeholder images were built and never pushed.
- The repair's own tests uncovered and fixed a defect within the round (abstentions and downstream failures returned 500).
- Claude Code 2.1.323 in accept-edits mode on a second Claude Pro account, orchestrator on Opus; Phase 12 ran in parallel in a separate worktree.
- No AWS, model or embedding call was made.

## 7. My own checks

- [ ] `.claude/.last-test-run` reads `exit=0`.
- [ ] I read `API.md` and one error response example with a trace ID.
- [ ] `git status` shows no `target/` or `datasets/raw/` files tracked.

## 8. Manual changes

None to repository source. I supplied the spec excerpt and wrote the decisions.

Accepted commit: see `commit.txt`.