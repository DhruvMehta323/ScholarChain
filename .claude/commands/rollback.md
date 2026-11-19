---
description: Route a ScholarChain release rollback to deploy-engineer, gated on recorded human approval, a clean tree and post-rollback smoke tests.
argument-hint: <environment> <target-release-id> <reason-or-approval-reference>
disable-model-invocation: true
---

Responsible agent: deploy-engineer (during an active incident, incident-responder may execute the same script only with a recorded human approval; use /incident first)

Gates: recorded human approval of the rollback, clean tree, rollback script present, post-rollback smoke tests.

Run-specific inputs:
- Environment: $1
- Target release id to roll back to: $2
- Reason or approval reference: $3

Orchestrator instructions:

1. If any argument is missing, stop and ask the human.
2. Confirm that a human approval for this rollback is recorded (the reference in $3, or the phase's human-gate.md). If it is not, stop and request it.
3. Dispatch deploy-engineer in a fresh context. Ask it to:
   - verify the gates and quote the evidence;
   - execute only `scripts/rollback.sh` with the approved environment and target release;
   - run `scripts/smoke-test.sh`;
   - write the record under `reports/deployments/`;
   - return the standard report.
4. Rollbacks never include data deletion, schema changes or Terraform apply. Any of those is escalated to a human.
5. Store the report in the current phase's evidence folder for human review.
