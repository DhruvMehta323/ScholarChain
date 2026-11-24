# Phase 17a — Documentation and report draft (work order)

Phase folder: `docs/agent-runs/phase-17a-docs-draft/`
Lane: **lane 2** (worktree `ScholarChain-lane2`, branch `lane2`), after `main` is merged into lane2.
Purpose: write everything for Phase 17 that does not depend on AWS results now, so Phase 17 only fills in numbers. Phases 17a and 17 are combined into one Phase 17 at the end.

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A | the agent that owns `README.md` and `docs/**` per `docs/agents.md` (route by ownership; if no owner is listed for a path, STOP and ask) | `README.md` (see rule 4), `docs/report/**`, `docs/agent-runs/README.md` | always |
| D | code-reviewer | nothing | always |

## 0. Rules

1. You route; owners write. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. **No invented numbers.** Every number in a document comes from a committed file, cited by path; anything that depends on Phases 14–16 is the literal placeholder `[[PENDING-AWS: <what>]]` or `[[PENDING-EVAL: <what>]]`.
3. **No sbt, no AWS, no network.** Lane 1 is running Phase 14. Do not edit `CLAUDE.md`, `docs/agents.md`, `.claude/**`, `modules/**`, `scripts/**`, `infra/**`, `experiments/**`. The project rule files stay exactly as they are.
4. **The course spec is never modified.** First find where the course specification and project rules live (README.md or another file). If `README.md` is the course spec, do not edit it: write the project README draft to `docs/report/PROJECT-README-draft.md` instead and note it for my gate.
5. Do not stage or commit. `## Progress checkpoint` after each work order. One repair round at most. Target time: 2 hours.
6. The stop hook may demand an sbt run because files changed. Do not run sbt; report it, and I run the canonical CHECK myself.

## 1. Preconditions (read-only)

- HEAD on `lane2` contains `main` (Phases 00–13, 15a) and Phase 15b; `git status --short` clean except this folder.
- Read the course spec (deliverables, report sections, README requirements, video and cleanup requirements), every `docs/agent-runs/*/human-gate.md`, `agent-report.md` and `review-report.md`, `.claude/agents/*.md`, `docs/agents.md`, `infra/terraform/INFRA-CONTRACTS.md`, `scripts/`, `experiments/README.md`.

## 2. A — documents

1. **Project README** (location per rule 4), with every section the spec requires, including:
   - Overview and architecture (DBLP + enrichment → Hadoop MapReduce on EMR → Spark analytics → Neptune / DynamoDB / S3 / S3 Vectors → runtime RAG agents → REST services on ECS; Terraform), with a text or Mermaid diagram.
   - **Agent catalog:** each agent in `.claude/agents/` with its role, owned paths and tools, taken from the files, not paraphrased into new rules.
   - **"Reproducing the agentic workflow":** how a phase runs (work order, orchestrator, owners, test-engineer, code-reviewer, repair rounds, hooks, human gates, canonical CHECK), with the exact commands.
   - How to build and test locally, how to plan / apply / deploy / run the pipeline / run experiments / clean up with the Phase 13 scripts (apply and destroy marked human-only).
   - Configuration and secrets by environment-variable name only; the Bedrock-only runtime model (Haiku 4.5 through the `us.` inference profile, Titan Embed v2).
   - Phase table 00–17 with status, test counts and commit hashes from `commit.txt` files.
2. **Final report draft** `docs/report/final-report-draft.md`, following the spec's required sections. Keep **local tests, fixture demos and AWS-validated real-data runs strictly separate** (instructor requirement). Fill everything that is already known from committed evidence (entity-resolution gold-set results, test counts per phase, design decisions, repair history, reviewer findings by severity); placeholders for AWS and evaluation results.
3. **Disclosures section** in the report, compiled from the gates and the handoff list: second and third Claude Pro accounts; accept-edits mode (and auto mode in part of lane 2); the parallel worktree; human-authored gold labels via a local display tool; P2 live LLM adjudication not executed; superseded runs; placeholder model prices; work-kind ER limits; identity rules exercised by fixtures only at 1K; affiliations not persisted; title-only paper vectors at 1K; CHECK flakiness under concurrent load and the AdversarialSuite timeout change; the narrowed homonym rule; the Phase 10 gate file overwritten and restored; the Phase 11 reviewer not rereading the final formatting pass; the Phase 15b stop-hook override and my manual CHECK; the rule-file items I chose not to change (cancelled factory session); the smoke-test key file and plain-HTTP decisions; all deferred LOW notes per phase (summarized, with paths).
4. **Demo video script** `docs/report/demo-script.md`: a timed outline (≤10 minutes unless the spec says otherwise) covering the agent factory, one phase run, the AWS deployment, a real query with evidence, the elasticity results and cleanup; shots that need AWS marked `[[PENDING-AWS]]`.
5. **Cleanup checklist** `docs/report/cleanup-checklist.md` from `scripts/cleanup.sh`, INFRA-CONTRACTS and the spec's cleanup requirements: the order of human steps (env-window down, ECR emptying, the human destroy, the secret recovery-window note, the final inventory).
6. **`docs/agent-runs/README.md`:** one row per phase folder (phase, status, accepted commit, tests, reviewer verdict), from committed files only.

## 3. D — code-reviewer

Check: every number traced to a committed file; no invented results; local / fixture / AWS evidence kept separate; every spec deliverable section present or explicitly pending; the agent catalog matches `.claude/agents/`; no secrets, account ID or ORCID iDs; the course spec untouched. Verdict.

## 4. STOP

STOP if a precondition fails, a boundary or ownership gap is hit, or a second repair round would be needed. Otherwise write "Phase 17a complete; waiting for human gate" and STOP. Do not commit or stage.