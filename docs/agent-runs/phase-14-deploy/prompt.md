# Phase 14 — Apply, deploy and first real run (work order)

Phase folder: `docs/agent-runs/phase-14-deploy/`
Lane: lane 1 (`~/Desktop/ScholarChain`, branch `main`), after the lane2 → main merge. The project rule files (`CLAUDE.md`, `docs/agents.md`, `.claude/**`) are unchanged and must stay unchanged.
Spec sections: "Phase 14" (apply and deploy), "Cost controls", "Security", "Observability requirements".
**Spend starts in this phase.** Runtime model: Claude Haiku 4.5 on Bedrock through `us.anthropic.claude-haiku-4-5-20251001-v1:0`; embeddings Titan Embed Text v2. No Anthropic API, no Voyage.

| Part | Who | Writes only to | AWS |
|---|---|---|---|
| A | infra-engineer, build-engineer, owners per `docs/agents.md` | per ownership | no |
| B | orchestrator | — (CHECK) | no |
| H1 | **human** | tfvars, plan review, `terraform apply`, secret values | yes |
| C | deploy-engineer (approved scripts only) | `reports/**` per ownership | yes |
| D | code-reviewer | nothing | no |

---

## 0. Rules

1. You route; owners write. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. "NOT RUN" with the reason.
3. **Apply and destroy are human-only.** Agents never run `terraform apply`, `destroy`, state commands, `cleanup.sh`, or `aws secretsmanager put-secret-value`. Agents make AWS calls **only in Part C**, only through the Phase 13 scripts, only after the human writes "H1 done" in the session.
4. **Cost rules:** Neptune and the ALB exist only during the window that starts at H1 and ends with `scripts/env-window.sh down`. EMR at most 2 core/task plus primary, 15-minute idle auto-termination, steps TERMINATE_CLUSTER with a 1-hour timeout. Phase 14 runs the **10K scale only**. Before and after each Part C step, record the cost so far (Cost Explorer, before credits). **STOP immediately if month-to-date cost before credits exceeds $12.**
5. Do not stage or commit. `## Progress checkpoint` after each part.
6. Repair policy: fix HIGH and MEDIUM; defer LOW. At most one repair round. CHECK at most twice (one spare rerun only for a timeout or OOM in an untouched module).
7. **No agent edits `CLAUDE.md`, `docs/agents.md` or `.claude/**`.** The `reports/**` output folders have no owner in `docs/agents.md`: they are written only by the Phase 13 scripts the agents run, never hand-edited. If a guard blocks a script output or an agent write, STOP and ask; do not work around it.
8. Never run sbt while lane 2 runs sbt: check `pgrep -fl sbt` and the lane 2 `.claude/.last-test-run` timestamp first.

## 1. Preconditions (read-only)

- HEAD on `main` contains the lane2 merge (Phases 12, 13, 15a); `git status --short` clean except this folder.
- **Contract check:** compute the SHA-256 of `infra/terraform/INFRA-CONTRACTS.md` §Services and compare it with the provisional hash recorded in the Phase 10 evidence (`6b17edab…e2114`). If different, list every difference and STOP.
- Read the gates for Phases 10–13 (MERGE-1, MERGE-2, M13-1, M13-2, M13-3, G12-4/5/6, the smoke key file rule, the plain-HTTP rule, deploy `--gate-file`), `INFRA-CONTRACTS.md` §14 (Phase 14 apply checklist), and `docs/agents.md`.

## 2. A — merge reconciliation (no AWS)

1. **MERGE-1** (infra-engineer, Terraform): set the runtime's own `rag.model.*` environment overrides on the services that call the model (provider = `bedrock`, model id = `us.anthropic.claude-haiku-4-5-20251001-v1:0`; use the exact variable names recorded in the Phase 11 agent-report); remove `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID`; set `bedrock_model_arns` to: the Titan Embed Text v2 foundation-model ARN in us-east-2, the Haiku 4.5 inference-profile ARN in us-east-2, and the Haiku 4.5 foundation-model ARNs in the regions that profile routes to (read them from AWS documentation already cited in the repo or list them as a human check item; do not call AWS).
2. **MERGE-2** (infra-engineer): stop injecting `SCHOLARCHAIN_VOYAGE_API_KEY` and `SCHOLARCHAIN_ANTHROPIC_API_KEY` into any task definition. The secret resources may stay (empty, unused); document that.
3. **M13-1** (infra-engineer, `scripts/**`): `package.sh` builds and tags all three service images exactly as main's build defines them (Phase 11 P2), and `deploy.sh` pushes and deploys all three.
4. **M13-2** (owner per `docs/agents.md`): the storage load (Neptune bulk load from the curated prefix, DynamoDB items for the tables the runtime reads) and the embedding enqueue. **Preferred:** run the existing loader and enqueue entry points from the operator machine as steps of `run-bounded-pipeline.sh` (live profile, bounded to 10K), not a new EMR main. Add an EMR entry point only if one already exists or is a trivial wrapper. Record which path was taken.
5. **M13-3** (build-engineer): `.gitignore` entries for the `reports/**` raw output folders that must not be committed (keep small summary JSON committable as decided in the report).
6. **Image rebuild plan:** the Phase 11 `932fd2f-dirty` images are never deployed; `package.sh` rebuilds from the clean commit in Part C.
7. test-engineer: tests for the new Terraform/script behavior where the repo's harness allows (offline stub harness from Phase 13; Terraform `validate` with `-backend=false`).

## 3. B — CHECK

Run the canonical CHECK once: `sbt check; echo "exit=$?" > .claude/.last-test-run`. Report exact totals. If it fails, one repair round, one more CHECK. Then write the checkpoint "Part A complete; waiting for H1" and STOP.

## 4. H1 — human (the session waits)

The human, following `INFRA-CONTRACTS.md` §14:
1. Commits nothing yet; runs `scripts/plan.sh` with `create_neptune=true` and `create_alb=true`, reviews the summary with the chat assistant, then applies the saved plan himself.
2. Confirms the SNS email subscription (G12-6).
3. Sets the secret values himself: `SCHOLARCHAIN_SERVICES_API_KEY` and `SCHOLARCHAIN_INTERNAL_API_KEY` (random), and writes the services key to `~/.scholarchain/services-api-key` (mode 600) for the smoke test.
4. Records the applied plan SHA-256 in a committed gate file for `deploy.sh --gate-file` (the Phase 14 pre-deploy note in `human-gate-predeploy.md`, committed by the human).
5. Writes "H1 done" in the session.

## 5. C — deploy-engineer (approved scripts only, in this order)

1. `scripts/package.sh` from a clean tree (all three images tagged with the commit SHA).
2. `scripts/deploy.sh --gate-file <committed predeploy gate>` with query-api 1, retrieval 1, worker 0. Verify task definitions and image digests.
3. `scripts/run-bounded-pipeline.sh --scale 10k --smoke`, then the full 10K run: HJ1–HJ7 on EMR, the Spark pipeline on EMR, the storage load, the embedding enqueue (≤10K vectors). Scale the worker up for the drain, then back to 0. Record run IDs, step timings and output locations.
4. `scripts/smoke-test.sh --allow-insecure-http` with `SC_SMOKE_API_KEY_FILE=~/.scholarchain/services-api-key`: `/healthz`, `/readyz`, one bounded collaboration query through `POST /v1/query` that returns an evidence-backed answer, evidence and explain fetches, an idempotent repeat, a 401 without a key.
5. `scripts/archive-evidence.sh`. Record the cost so far.
6. **Do not run `env-window.sh down` yet** if the human says Phase 15c follows immediately; otherwise run it (services to 0) and print the human commands to remove Neptune and the ALB.

If any step fails: STOP, record the failure and the cost so far, and leave the EMR cluster auto-terminating. Do not retry a failed AWS step more than once.

## 6. D — code-reviewer

Review Part A changes (IAM scope of the Bedrock ARNs, no secret injection of unused keys, three-image packaging, the loader path, gitignore) and the Part C evidence for honesty (every claimed result has a raw file). Verdict.

## 7. STOP points

STOP if: a precondition or the contract check fails; a boundary would be crossed; an apply, destroy or secret write is needed; cost before credits exceeds $12; a Part C step fails; or a second repair round would be needed. Otherwise write "Phase 14 complete; waiting for human gate" and STOP. Do not commit or stage.

## Gate (what the human checks)

- The applied plan matches the reviewed plan SHA-256; no NAT gateway; Neptune and ALB only in the window.
- Three services running the clean-commit images; smoke test passed with an evidence-backed real-data answer.
- The 10K pipeline ran on EMR (Hadoop and Spark) with recorded timings; the storage load and ≤10K vectors are done.
- Cost so far recorded (before credits) and within plan.
- Reviewer verdict: APPROVE or APPROVE WITH NOTES.

## Out of scope

The 50K run and elasticity experiments (Phase 15c); evaluation (Phase 16); destroy (Phase 17 cleanup).