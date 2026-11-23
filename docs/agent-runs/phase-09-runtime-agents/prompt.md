# Phase 09 — Runtime collaboration-analysis agents (work order)

Phase folder: `docs/agent-runs/phase-09-runtime-agents/`
Spec sections: "Phase 9: implement the runtime collaboration-analysis agents", "Runtime microservice graph", "Example evidence-backed workflow", "Security requirements".
Input: the core ports and records (Phases 03–08), the Phase 07 loader output (L4) and the Phase 08 local vectors, all local. **No AWS, Bedrock, Anthropic or Voyage call in this phase.** Every LLM-backed step runs against a fake model client in tests and local runs. Live model calls happen in Phase 14–15.

Ownership follows `docs/agents.md` §1 and §1.1.

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A0 | build-engineer (+ dependency-updater for version values) | `build.sbt`, `project/**` | only if a precondition fails |
| A1 | domain-engineer | `modules/core/**` except `src/test/**` | only if a contract in §3 is missing |
| B | rag-engineer | `modules/rag/**` except `src/test/**`, and `prompts/**` | always |
| C | test-engineer | `modules/rag/src/test/**`, `modules/core/src/test/**`, `modules/services/src/test/**` | always |
| D | code-reviewer | nothing | always |

---

## 0. Rules for the orchestrator

1. You route; you do not write code, prompts, tests or docs inline. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. Write "NOT RUN" with the reason.
3. Canonical test command, exactly: `sbt check; echo "exit=$?" > .claude/.last-test-run`. Subagents cannot run it; you run it from the repo root, with `cd` as its own call first. A full CHECK takes 15–38 minutes; run it once per work order.
4. Speed rule: agents use targeted commands (`sbt "rag/testOnly *"`, `sbt "rag/Test/compile"`). Use `testOnly *` for counts. Run `scalafmt` on new files before CHECK.
5. One sbt user at a time. **Do not run CHECK while the parallel worktree (`ScholarChain-lane2`) is running sbt**; if unsure, ask the human.
6. Append a `## Progress checkpoint` to `agent-report.md` after each work order.
7. **No AWS, LLM or embedding API calls; no credentials read.** Never read `.env`, `~/.aws` or any key. Never set or read `ANTHROPIC_API_KEY`; the runtime key variable is `SCHOLARCHAIN_ANTHROPIC_API_KEY`.
8. Do not stage or commit. Unattended mode: continue without asking, except at the STOP points in §7.
9. Repair policy: fix HIGH and MEDIUM findings that concern correctness, grounding, security or injection. Defer LOW findings with disclosure. A CHECK failure that is only a timeout or out-of-memory error in a module untouched by this phase may be rerun once, unchanged, and both runs recorded.

## 1. Preconditions (orchestrator, read-only)

- `git status --short` is clean, and HEAD descends from `docs/agent-runs/phase-08-embeddings-vectors/commit.txt`. If not, STOP.
- The canonical command exits 0. Record the baseline (Phase 08 ended at 2,118).
- From `CLAUDE.md` and `build.sbt`, record what `rag` depends on. `rag` uses core ports only (graph, key-value, vector, object store, embedder). It must not depend on `storage`, `spark`, `hadoop` or `entityResolution` code. A composed local run lives in `modules/services/src/test/**` (test-engineer), as in Phase 08. If another edge seems needed, STOP and ask.
- List the existing core records this phase uses: `QueryPlan`, `EvidenceItem`, `EvidenceBundle`, `AnswerClaim`, `AnswerResponse`, `SemanticContextItem`, `QuerySessionRecord`, the query-operation and verification-status enums, and the six graph templates. Note which are missing or incomplete.
- Locate the Phase 07 loader output (L4: Neptune CSV and DynamoDB JSONL) and the Phase 08 local vector output, for the composed benchmark run.

## 2. A0 — build-engineer (conditional)

Only if needed: `services` test classpath access to `rag` and `storage` (already present from Phase 08 if so). No new library; the Bedrock runtime and JDK `HttpClient` already cover the model clients. Run the canonical command and report.

## 3. A1 — domain-engineer (conditional)

Inspect first; add only what is missing, as versioned immutable records with validation and frozen JSON examples. Never change a frozen wire format in place.
- `QueryPlan` v2 if v1 lacks: operations from an **allowlist** (researcher ranking by a named metric, maximum team size, direct coauthors, shortest path, component lookup, community comparison, bridge ranking, temporal comparison, topic/venue/institution filter, semantic explanation), named researchers, time window, filters, ranking metric, limits.
- `ResolvedResearcherCandidate` (researcher ID, display name, confidence, evidence used, source IDs).
- `ClaimVerification` (claim ID, status VERIFIED | PARTIALLY_SUPPORTED | UNSUPPORTED | CONFLICTING, checked evidence IDs, reason codes).
- `ModelCall` audit record (stage, prompt ID and version, model ID, schema version, input hash, output hash, token counts when known, latency, retries, outcome).
- A `ModelClient` port (structured request with a JSON schema, structured response), if absent.

Run the canonical command and report.

## 4. B — rag-engineer

Everything goes in `modules/rag/**` and `prompts/**`, plus `modules/rag/RUNTIME-AGENTS.md`.

**Model clients behind the `ModelClient` port:**
- Bedrock Converse (default; model ID from config) and the Anthropic Messages API (the approved fallback, JDK `HttpClient`, key only from env `SCHOLARCHAIN_ANTHROPIC_API_KEY`, never logged). Provider chosen by config.
- A deterministic **fake model client** for tests and local runs, which returns scripted structured outputs keyed by prompt ID and input hash.
- Every model output is validated against its JSON schema before use. Malformed output gets a bounded retry (at most 2), then a typed failure. Context and token budgets come from config.

**Versioned prompts and schemas** in `prompts/runtime/` and `prompts/schemas/` (for example `query-planner-v1.txt`, `answer-synthesis-v1.txt`, `claim-verification-v1.txt`, with matching JSON schemas). Retrieved text is wrapped and labeled as data, never as instructions. No hidden chain-of-thought is requested or stored.

**Stages (one class per stage, composed by a `QueryWorkflow` over core ports):**
1. **QueryPlannerAgent:** turns the question plus optional structured constraints into a typed `QueryPlan` using only allowlisted operations. The LLM proposes; code validates. A deterministic rule-based fallback handles the structured request fields when the model output is invalid. **User text never becomes Cypher or an AWS call.**
2. **ResearcherResolverAgent:** resolves names to canonical researcher IDs using the identity, mention-to-researcher and metric tables through the key-value and graph ports. Returns multiple candidates when ambiguous, with confidence and the evidence used. **Abstains rather than guessing.**
3. **CollaborationMetricsAgent:** returns exact stored P_r, C_r, T_r, A_r, M_r, W_r, the maximum-team works, component, community, bridge measures, coreness and window, with run ID and metric version. No computation from text.
4. **PathFinderAgent:** bounded parameterized graph templates only (direct collaborators, shortest path). Returns every hop with edge weight, supporting work IDs, years and provenance. Enforces depth, result-count and timeout limits.
5. **CommunityAnalystAgent:** community membership and size, cross-community edges, bridge measures and neighboring communities. Explicitly distinguishes high direct degree from high bridge importance.
6. **SemanticContextAgent:** queries the appropriate vector indexes through the embedder and vector ports; returns `SemanticContextItem`s with scores and S3 pointers. **Vector similarity is never evidence for a coauthorship edge or a number.**
7. **EvidenceBuilderAgent:** a bounded `EvidenceBundle` that keeps deterministic graph and metric evidence separate from semantic context, including identity-resolution confidence and provenance.
8. **AnswerSynthesisAgent:** a concise analysis as typed `AnswerClaim`s, each with evidence IDs, explaining breadth vs. intensity vs. team size vs. connectivity vs. bridge importance when relevant, and stating uncertainty and missing evidence.
9. **ClaimVerificationAgent:** **deterministic first.** Code checks that every cited evidence ID exists and that every researcher ID, publication, count, team size, path hop, community, bridge value, window and number in a claim matches the bundle. An LLM check is optional and only for qualitative wording; it can downgrade a claim, never upgrade one. Unsupported claims are removed or labeled; the answer abstains when identity or evidence is insufficient.
10. **Audit:** persists the `QueryPlan`, resolved IDs, evidence IDs, metric versions, graph run ID, prompt and model versions, `ModelCall` records, latency, verification results and an answer hash through the key-value port (QuerySession and EvidenceBundle repositories' contracts).

**Benchmark suite (data-derived, not model-written):** `modules/rag/src/main/resources/benchmarks/benchmark-v1.json` with **at least 30 questions** generated from templates over the Phase 06/07 data: coauthor breadth, maximum team size, direct collaboration, shortest paths, components, communities, bridge researchers, temporal change, topics, institutions, ambiguous names (must abstain or list candidates) and evidence lookup. Each question's **expected values are computed deterministically from the metric and graph tables** (not by any model), stored with the question, and versioned. Include at least 3 prompt-injection questions and 3 ambiguous-name questions.

## 5. C — test-engineer (at least 45 new tests)

Paste the B report.
- **Planner:** allowlisted operations only; unknown or dangerous operations rejected; injection text in the question never reaches a template parameter unescaped or becomes an operation; the fallback path works.
- **Resolver:** ambiguous names return candidates or abstain; never silently pick one; homonym suffixes respected.
- **Metrics, paths, communities:** exact values against fixture tables; path hops carry supporting works; bounds enforced.
- **Semantic:** vector results never produce numerical claims; S3 pointers resolve.
- **Synthesis and verification:** a claim with a fabricated evidence ID is UNSUPPORTED; a wrong number is UNSUPPORTED or CONFLICTING; a wrong path hop is caught; an LLM verifier cannot upgrade a deterministic failure; malformed model JSON retries at most twice then fails typed; prompt injection inside a title, abstract or summary does not change the plan or the claims.
- **Audit:** every stage writes its audit fields; the key never appears in logs or exceptions.
- **Composed benchmark run** (in `modules/services/src/test/**`, composing rag with the storage fakes loaded from the Phase 07 L4 output and the Phase 08 vectors, with the fake model client): all benchmark questions run end to end; report per-category pass counts (numbers and IDs match expected; abstentions where required; injection questions handled). Run it twice; outputs must be identical.

Leave failing tests in place and route them. Run `scalafmt`; the orchestrator runs CHECK.

## 6. D — code-reviewer (read-only)

Review in this order: user text reaching queries or AWS (injection); grounding (every numerical, path and identity claim tied to evidence IDs); verifier strength (could it pass a wrong claim?); abstention on ambiguity; separation of graph facts from semantic context; secrets and key handling; prompt and schema versioning; benchmark independence from any model; tests that would pass if broken; claims not backed by output. Severity, file:line, failing input and owner for each finding. Verdict: APPROVE, APPROVE WITH NOTES, or REQUEST_CHANGES.

## 7. Repair loop and STOP points

- On REQUEST_CHANGES: write `repair-NN.md` grouped by owner, route within the repair policy, run CHECK, rerun the composed benchmark twice if production source changed, and have D reread only the repair. At most **2 rounds**.
- **STOP and ask the human** if: a precondition fails; a forbidden module edge seems needed; a finding would change an earlier frozen contract, ownership or anything constitutional; any step needs a live model or AWS call; CHECK fails after a repair for a reason other than the §0.9 resource case; the two benchmark runs differ; or a third round would be needed.
- Otherwise, after APPROVE or APPROVE WITH NOTES: write the final checkpoint "Phase 09 complete; waiting for human gate", and STOP. Do not commit or stage.

## Gate (what the human checks)

- User text cannot become database code or an AWS call.
- Ambiguous researcher names never silently resolve to one identity.
- Every numerical, path and identity claim has evidence IDs; unsupported claims are removed or labeled.
- Prompt-injection tests pass.
- Deterministic graph retrieval and semantic retrieval are separate and evaluated separately.
- The benchmark has at least 30 data-derived questions with expected values computed from the tables; the composed run is identical twice.
- The `sbt check` total rises by at least 45. Reviewer verdict: APPROVE or APPROVE WITH NOTES.

## Out of scope

HTTP routes and services (Phase 10); live model, embedding and AWS calls (Phase 14–15); Terraform; the full retrieval and answer evaluation with human ratings (Phase 16).