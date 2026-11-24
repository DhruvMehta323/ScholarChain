# Phase 09 — Human gate

Phase: 09, runtime collaboration-analysis agents
Date: 2026-11-23
Reviewer and ratifier: Dhruv Mehta (human architect)
Decision: **ACCEPTED**, with the decisions, disclosures and remaining risks below.

## 1. What I reviewed

- `prompt.md` (written by me; saved verbatim), `repair-01.md`, `repair-02.md`.
- `agent-report.md` (preconditions, A0/A1 where run, B, C, the composed benchmark runs, both repair rounds, the final checkpoint).
- `review-report.md`: the first review, the reread after repair 01, and the final reread after repair 02 (APPROVE WITH NOTES).
- `modules/rag/RUNTIME-AGENTS.md`, the prompts and schemas under `prompts/`, and `benchmark-v1.json`.
- My own checks (section 6).

## 2. What was accepted

- **Runtime stages (rag-engineer):** QueryPlanner (allowlisted operations; the model proposes, code validates; deterministic fallback), ResearcherResolver (candidates or abstention), CollaborationMetrics (exact stored values with run and metric version), PathFinder (bounded parameterized templates; every hop with supporting works), CommunityAnalyst, SemanticContext (vector results never used as evidence for numbers or edges), EvidenceBuilder (graph facts separate from semantic context), AnswerSynthesis (typed claims with evidence IDs), ClaimVerification (deterministic checks first; an optional model check can only downgrade), and Audit.
- **Model clients:** Bedrock Converse (default) and the Anthropic Messages API (approved fallback), plus a deterministic fake client; every output validated against a versioned JSON schema, with at most 2 retries for malformed output. Versioned prompts and schemas under `prompts/runtime/` and `prompts/schemas/`.
- **Benchmark:** 44 questions with expected values computed from the Phase 07 tables by a deterministic generator, not by a model. All 44 pass in both composed runs (rag with the storage fakes loaded from the Phase 07 and 08 outputs, fake model client), and the two runs are byte-identical (`diff -r` empty). All 4 ambiguous-name questions abstain with at least 2 candidates; all 4 prompt-injection questions pass.
- **Tests:** 2,118 → **2,423 passed, 0 failed** (+305), marker `exit=0`.

## 3. Decisions

1. **N5 (MEDIUM): the verifier can pass a claim that states a joint publication count as one researcher's total.** Accepted for this gate only because no real model writes answers yet. **It must be fixed before any live model call**: it is carried into the Phase 10 work order for rag-engineer, together with N6 and N7 (similar wording gaps) and their documentation in RUNTIME-AGENTS.md.
2. **L2: the config accepts `ANTHROPIC_API_KEY` as the key variable name.** Not acceptable (that variable switches Claude Code itself to API billing, and the project's rule is `SCHOLARCHAIN_ANTHROPIC_API_KEY` only). Carried into Phase 10 for rag-engineer: reject that name with a clear error.
3. **Narrowed homonym rule:** accepted with disclosure. A same-name researcher who ranks below the top 100 vector hits can go undetected; the strict version made every name-only question abstain. Measured in the Phase 16 evaluation.
4. **Deferred:** L3, L5, L6, L7. L1 was fixed in repair 01 because its test was failing.

## 4. Run history (disclosed)

- The first baseline CHECK failed with a 30 s Spark timeout while the parallel worktree was running its own Spark tests. The single allowed unchanged rerun passed at 2,118.
- rag-engineer hit the weekly usage limit partway through work order B and resumed after the reset.
- The orchestrator did not act on a message meant for the parallel worktree (Phase 12) that I pasted into this session by mistake.

## 5. Not run in this phase

No AWS, Bedrock, Anthropic or Voyage call. The model-ID defaults and the live Bedrock and Anthropic paths are unverified until Phase 14–15.

## 6. My own checks

- [ ] `.claude/.last-test-run` reads `exit=0`.
- [ ] I read three benchmark questions and their expected values, and traced one value back to a Phase 07 table.
- [ ] I read one prompt file and confirmed retrieved text is labeled as data.
- [ ] `git status` shows no `target/` or `datasets/raw/` files tracked.

## 7. Disclosures

Claude Code 2.1.323 in accept-edits mode on a second Claude Pro account, monitored through Remote Control; orchestrator on Opus. Phase 12 ran in parallel in a separate worktree on the same machine.

## 8. Manual changes

None to repository source. I wrote the decisions in this file.

## 9. Remaining risk

N5–N7 open until Phase 10; live model behavior untested; the narrowed homonym rule; the CHECK flakiness under concurrent load.

Accepted commit: see `commit.txt`.