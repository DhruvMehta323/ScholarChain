---
description: Route a bounded ScholarChain cloud pipeline run (Hadoop, entity resolution, Spark, load) to deploy-engineer through scripts/run-bounded-pipeline.sh.
argument-hint: <environment> <dataset-sample-id> <size-bound> <approved-plan-reference>
disable-model-invocation: true
---

Responsible agent: deploy-engineer

Gates: approved plan, clean tree, an explicit size bound within the approval, evidence archived with `scripts/archive-evidence.sh`. EMR scale above the bounded limit requires human approval.

Run-specific inputs:
- Environment: $1
- Dataset sample id (from datasets/manifest): $2
- Size bound (records or input bytes, plus maximum instances): $3
- Approved plan reference: $4

Orchestrator instructions:

1. If any argument is missing, stop and ask the human. Never default the size bound.
2. Confirm that the approval covers this environment, sample and bound.
3. Dispatch deploy-engineer in a fresh context. Ask it to:
   - verify the gates;
   - execute `scripts/run-bounded-pipeline.sh` with exactly these inputs;
   - run `scripts/archive-evidence.sh`;
   - record the counters, output checksums and cost-relevant facts under `reports/deployments/`;
   - return the standard report.
4. Route pipeline defects to their owners (hadoop-engineer, entity-resolution-engineer, spark-engineer or storage-engineer). Route script defects to infra-engineer. Cluster teardown that needs a destructive call is human-only.
5. Store the report in the current phase's evidence folder for human review.
