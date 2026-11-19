---
description: Route a ScholarChain deployment to deploy-engineer, gated on an approved plan, a clean tree, smoke tests and a rollback path.
argument-hint: <environment> <release-id> <approved-plan-reference>
disable-model-invocation: true
---

Responsible agent: deploy-engineer

Gates: approved plan, clean tree, smoke tests, rollback gate.

Run-specific inputs:
- Environment: $1
- Release id: $2
- Approved plan reference (a path to the human approval record): $3

Orchestrator instructions:

1. If any argument is missing, stop and ask the human. Do not guess an environment or release.
2. Confirm that the approved plan reference exists and names this environment and release. If it does not, stop and report that human approval is missing.
3. Dispatch deploy-engineer in a fresh context with exactly these inputs. Ask it to:
   - verify the gates, quoting the evidence: the approval record, `git status --porcelain` empty, `git rev-parse HEAD`, `scripts/rollback.sh` present, and the rollback target identified;
   - execute `scripts/deploy.sh` with the approved arguments;
   - run `scripts/smoke-test.sh`;
   - write the execution record under `reports/deployments/`;
   - return the standard report.
4. If deploy-engineer returns BLOCKED-ON, route the dependency to the named owner (usually infra-engineer). Do not perform the work yourself.
5. Store the report in the current phase's evidence folder. A human reviews it. No agent approves the deployment.
