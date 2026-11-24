# Phase 11 — Adversarial testing, full review and pre-deployment fixes (work order)

Phase folder: `docs/agent-runs/phase-11-adversarial-review/`
Spec section: "Phase 11: adversarial testing and full repository review".
**Hard time limit: about 2.5 hours.** This phase runs one adversarial test pass, one full read-only review and one repair round, and it closes the three pre-deployment fixes from the Phase 10 gate. No AWS or model calls.

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| P1 | service-engineer | `modules/services/**` except `src/test/**` | always (pre-deploy fixes 1 and 3) |
| P2 | build-engineer | `build.sbt`, `project/**`, `packaging/**` | always (pre-deploy fix 2) |
| T | test-engineer | `tests/**`, `modules/*/src/test/**` | always |
| R | code-reviewer | nothing | always |
| F | owning agents per finding | their own paths | only for routed findings |

---

## 0. Rules for the orchestrator

1. You route; you do not write code or tests inline. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. Write "NOT RUN" with the reason.
3. Canonical CHECK, exactly `sbt check; echo "exit=$?" > .claude/.last-test-run`, run by you from the repo root, **at most three times**: once after P1+P2+T together, once after the repair round, and one allowed unchanged rerun if a failure is only a timeout or OOM in an untouched module. Agents use targeted commands only.
4. **Never run sbt while the parallel worktree runs sbt.** Lane 2 is not using sbt tonight; if unsure, ask.
5. Append a `## Progress checkpoint` to `agent-report.md` after each work order.
6. No AWS, model or embedding calls; no credentials read. Docker image builds are allowed locally (never pushed) if Docker is running; otherwise NOT RUN.
7. Do not stage or commit. Unattended mode: continue without asking, except at the STOP points in §6.
8. Repair policy: fix CRITICAL, HIGH and MEDIUM findings on correctness, data loss, security, grounding, or anything that would break the Phase 14 deployment; defer LOW with disclosure. **One repair round only.**
9. If any single step will exceed 40 minutes, STOP and say why.

## 1. Preconditions (orchestrator, read-only)

- `git status --short` is clean except this folder; HEAD descends from `docs/agent-runs/phase-10-services/commit.txt`.
- Read the Phase 10 gate §4 (the three pre-deployment fixes) and the deferred-notes sections of every phase gate (05–10) for context.
- Do **not** run a baseline CHECK (Phase 10 ended at 2,555, exit 0, on this tree).

## 2. P1 — service-engineer (pre-deployment fixes 1 and 3)

1. **Audit path:** in `modules/services/src/main/scala/scholarchain/services/Composition.scala` (around lines 221–231), the audit location must be the curated bucket from `SCHOLARCHAIN_STORAGE_CURATED_BUCKET` with the relative prefix `rag/audit/`. The relative rag prefix must never be parsed as a bucket name. The same rule applies to every other relative prefix the composition joins with a bucket.
2. **Readiness:** `/readyz` reports not-ready (with a reason code, no secret) when the prompts and schemas directory is missing or a required prompt version is absent, and when a required configuration value is missing in AWS mode.
3. **AWS composition dry start:** make each service main constructible in AWS mode with fake environment values and an injected credentials provider pointed at a loopback endpoint, so a test can execute the composition code up to the first network call without reaching AWS.

## 3. P2 — build-engineer (pre-deployment fix 2)

- The three service images must contain the real service mains and the `prompts/` tree (runtime prompts and schemas) at the path the services read.
- Build all three images locally (if Docker is running), tagged with the current commit SHA plus `-dirty`; never push. Run each container with `--help` or a local-mode start and an HTTP check of `/healthz` and `/readyz`; record the results. Confirm non-root, no Spark or Hadoop on the runtime classpath, and image sizes.
- If Docker is not running, record NOT RUN and give the human the exact commands.

## 4. T — test-engineer (adversarial pass; at least 40 new tests)

Add tests only where coverage is missing; do not duplicate existing ones. Required categories (the spec's list):
- malformed and truncated DBLP XML; Unicode normalization and confusables; duplicate and conflicting DOIs; common names and reordered author lists; very large blocking groups;
- coauthor edges with unresolved or conflicting endpoints; empty and disconnected graphs; distinct degree, component, shortest path, bridge and k-core known-graph cases; large-team pair expansion;
- missing topic, institution, abstract or profile metadata; duplicate vector keys and stale model versions;
- prompt injection in titles, abstracts and summaries; malformed model JSON and fabricated identifiers; unsupported answer claims;
- Neptune timeout; DynamoDB conditional-write conflict; S3 Vectors throttling or transient failure; replayed workflow and idempotency;
- API authentication and authorization, malformed bodies, timeouts; logging redaction; configuration omissions;
- **the P1 fixes:** the audit object key is `<curated bucket>/rag/audit/...`, never bucket `rag`; `/readyz` not-ready without prompts; the AWS-mode dry start executes the composition with a loopback endpoint and a credentials provider that fails if anything leaves the machine.

Leave failing tests that expose production defects in place, and route them to the owner. Report exact counts and a coverage-by-requirement table.

## 5. R — code-reviewer (full repository, read-only)

Review in this order: correctness and data loss; contract and schema drift across phases; artifact ownership violations; security, secrets, IAM assumptions, injection; Hadoop and Spark distribution mistakes; entity-resolution over-merging and unreported uncertainty; storage idempotency and provenance; RAG grounding and unsupported claims; API behavior and failure handling; tests that would pass if broken; documentation claims not backed by implementation; cost and cleanup risks; **anything that would break the Phase 14 deployment** (configuration names versus the Phase 12 contract, prompts in images, audit and other S3 paths, IAM actions the code needs). Verify every finding against the files. Severity, file:line, failing input, owner. Verdict: APPROVE, APPROVE WITH NOTES, or REQUEST_CHANGES.

## 6. Repair loop and STOP points

- Group T's failing tests and R's findings by owner into `repair-01.md`; route within the repair policy; each owner fixes and runs targeted tests; then CHECK; test-engineer reruns the affected tests; R rereads only the repair. **One round.**
- **STOP and ask the human** if: a precondition fails; a finding would change a frozen contract, the identity rules, ownership or anything constitutional; any step needs AWS or a live model; CHECK fails after the repair for a non-resource reason; or a CRITICAL or HIGH finding remains after the round.
- Otherwise write the final checkpoint "Phase 11 complete; waiting for human gate", and STOP. Do not commit or stage.

## Gate (what the human checks)

- No critical or major verified finding remains.
- The three pre-deployment fixes are done and tested; images contain the real mains and prompts and pass local health and readiness checks (or NOT RUN with commands).
- Every production behavior has a relevant test; all tests pass.
- No reviewer wrote production code; the repair record names owners and rerun evidence.
- The `sbt check` total rises by at least 40.

## Out of scope

AWS deployment (Phase 14); Terraform changes (route any to the human); new features.