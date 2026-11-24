# Phase 13 — Automation: Human Gate

Reviewer: Dhruv Mehta (dmeht35)
Date: 2026-11-24 (CST)
Branch: lane2 (worktree ScholarChain-lane2), base 5edd031 (Phase 12 accepted)
Decision: **ACCEPTED WITH NOTES**

## 1. What I reviewed

- `prompt.md` (my work order, with the Phase 12 gate items G12-4/5/6 built in), `agent-report.md`, `review-report.md` (first review REQUEST CHANGES, 0 HIGH / 8 MEDIUM; reread after one repair round: APPROVE WITH NOTES, 5 LOW).
- `infra/terraform/INFRA-CONTRACTS.md` §14 (script table, merge items, Phase 14 apply checklist).

## 2. What was accepted

- New scripts: `check.sh`, `package.sh`, `deploy.sh`, `rollback.sh`, `smoke-test.sh`, `run-bounded-pipeline.sh`, `run-experiment.sh`, `archive-evidence.sh`, `env-window.sh`; `cleanup.sh` extended; two helper libraries; an offline test harness with stub `aws`, `terraform`, `docker`, `sbt`.
- Phase 12 gate items: G12-4 (secret recovery window note and printed force-delete commands), G12-5 (ECR emptied behind confirmation before the printed destroy), G12-6 (SNS confirmation in the Phase 14 apply checklist).
- All 8 MEDIUM findings fixed in one repair round and confirmed by the reviewer (plan checksum bypass, dirty build under a clean tag, Hadoop entry point, non-UTC step timing, unbounded EMR instance class, missing failed-deploy record, overridable build commands, API key over plain HTTP).
- Verification observed by the orchestrator: shellcheck exit 0; harness RESULT passed=253 failed=0.
- No script contains apply or destroy; 50K refused without a 10K record and `--approve-50k`; EMR steps use TERMINATE_CLUSTER with a 1-hour timeout.

## 3. Decisions

1. **Smoke-test key source (deviation accepted):** the hook guard blocks a Secrets Manager read inside a script, so `smoke-test.sh` reads the services API key from a file given by `SC_SMOKE_API_KEY_FILE`. Conditions: the file is created by me, mode 600, **outside the repository** (`~/.scholarchain/`), never committed, and deleted after the Phase 14/15c windows. The key value is never printed.
2. **Plain-HTTP smoke test (accepted for the course window):** the ALB is HTTP only (no domain or certificate). `--allow-insecure-http` is allowed only with ALB ingress limited to my /32. I rotate `SCHOLARCHAIN_SERVICES_API_KEY` after the last smoke or experiment run.
3. **Deploy approval:** `deploy.sh` requires the approved plan SHA-256 to appear in a committed human gate passed with `--gate-file`. The Phase 12 plan will be superseded by a new plan after the merge items (MERGE-1/2); the new plan SHA-256 is recorded in the Phase 14 gate before deploy.
4. **LOW notes deferred with disclosure:**
   - N1: the gate check is a substring match (a line containing the SHA plus "REJECTED" would pass). Mitigation: only I write gate files; I pass the gate file explicitly.
   - N2: the release image is the newest-created local tag. Mitigation: in Phase 14, run `package.sh` from a clean tree immediately before `deploy.sh`, and check the printed image tag equals the commit SHA.
   - N3: the build-command test is weak.
   - Remaining LOWs as listed in review-report.md.

## 4. Routing

| ID | Item | Route |
|---|---|---|
| I1 | settings.json ask rules may not match the `VAR=… bash scripts/deploy.sh` form | Factory session |
| I2 | `reports/*` output folders have no owner | Factory session |
| M13-3 | `.gitignore` for the `reports/*` output folders | build-engineer, at the merge |
| M13-1 | lane2 packages one service image; Terraform has three services (main has the three Phase 11 images) | lane2 → main merge |
| M13-2 | No EMR entry point yet for the storage load and the embedding enqueue | lane2 → main merge / Phase 14 work order |
| MERGE-1/2 | Bedrock model wiring and secret injection (from Phase 11) | lane2 → main merge |

## 5. Not run (by design)

sbt check, any AWS call, Terraform init/plan/validate, docker, a real EMR cluster. `cleanup.sh` is blocked for agents by the guard and was exercised only through the harness; I run it myself.

## 6. My own checks

- [ ] `git status --short` shows no `reports/` output files and no tfvars.
- [ ] I read INFRA-CONTRACTS.md §14 and the Phase 14 apply checklist.
- [ ] `grep -rn "terraform apply\|terraform destroy" scripts/` prints only printed-command strings, no executed calls.

Accepted commit: see `commit.txt`.

Signed: Dhruv Mehta — 2026-11-24