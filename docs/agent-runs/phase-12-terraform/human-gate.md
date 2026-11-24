# Phase 12 — Terraform: Human Gate

Reviewer: Dhruv Mehta (dmeht35)
Date: 2026-11-23 (CST)
Branch: lane2 (worktree ScholarChain-lane2)
Plan commit: 23f2914c56ff (dirty=true: untracked files only; the tracked diff hash is the empty-diff hash)

## 1. Plan evidence (H)

| Item | Value |
|---|---|
| Plan file SHA-256 | 757331672f9fd90dd312c1f292d4d2a66891557c8913d36ea4a3d010f64af09b |
| Account check | match (the account ID is not recorded in the repo) |
| Region / environment | us-east-2 / course |
| Plan exit code | 2 (changes present) |
| Add / change / destroy / replace | 160 / 0 / 0 / 0 |
| IAM changes | 20, all creates (ECS execution and task roles ×3, EMR service and EC2 role plus instance profile, Step Functions role, inline policies) |
| NAT gateways | 0 |
| Interface endpoints | 0 |
| Neptune | off (neptune_endpoint = "") |
| ALB | off (query_api_url = "") |
| ECS | cluster plus 3 services, desired count 0 |
| Stateful creates | 17 DynamoDB tables, 5 S3 buckets, 1 vector bucket plus 6 indexes, 3 ECR repos, 4 secrets, 2 SQS queues |
| Stateful destroy or replace | none |
| Plan files git-ignored | yes (infra/terraform/plans/.gitignore:2) |

Full SC-SUMMARY block: agent-report.md § H.

## 2. Checks

- [x] Plan produced by scripts/plan.sh; no apply or destroy was run by any agent.
- [x] Budget `scholarchain-budget` is $25 monthly with `include_credit = false` (counts usage before credits); email alerts at $5, $10 and $15.
- [x] All resources tagged course / environment / expiration-date 2026-11-30 / owner / project.
- [x] Secrets are created empty ("Value set by a human; Terraform holds no secret value").
- [x] ECR tags immutable, scan on push, keep the last 5 images.
- [x] SQS embedding queue has a DLQ; CloudWatch alarms on DLQ depth > 0 and oldest message > 600 s.
- [x] ALB ingress limited to one IPv4 /32 (in the git-ignored tfvars).
- [x] Neptune 1.5.0.1 supports db.serverless in us-east-2 (verified with describe-orderable-db-instance-options).
- [x] EMR release emr-spark-8.1.0 verified in us-east-2 (describe-release-label).

## 3. Findings and routing

| ID | Severity | Finding | Route |
|---|---|---|---|
| G12-1 | MEDIUM | Terraform `app_environment` sets `SCHOLARCHAIN_MODELS_GENERATION_MODEL_ID`, but no main code reads that name. The runtime reads `rag.model.provider` (bedrock / anthropic / fake) plus `rag.model.bedrock-model-id` and the Anthropic model ID. The provider and model ID wiring between Terraform and the services must agree. | Lane2 → main merge reconciliation (before Phase 14) |
| G12-2 | MEDIUM | If provider = bedrock, Claude Haiku 4.5 on Bedrock normally has to be invoked through a cross-region inference profile ID (`us.` prefix), not the bare model ID, and the account may need one-time Anthropic model access on Bedrock. If provider = anthropic, the ID must be the Anthropic API form (`claude-haiku-4-5-20251001`). | Decide at the merge; verify in the Phase 14 smoke test |
| G12-3 | LOW | The Voyage secret is named `scholarchain/voyage-api-key` (the other secrets are `SCHOLARCHAIN_*`). The runtime reads the env var `SCHOLARCHAIN_VOYAGE_API_KEY`; the ECS task definition must map the secret to that name. | Check at merge (Voyage is a fallback only) |
| G12-4 | LOW | Secrets have a 7-day recovery window. A destroy followed by a re-apply within 7 days fails on the secret names unless they are force-deleted. | Phase 13 cleanup script / runbook note |
| G12-5 | LOW | ECR repos have `force_delete = false`, so destroy fails while images remain. | Phase 13 cleanup empties the repos before destroy |
| G12-6 | INFO | The SNS email subscription must be confirmed from the uic.edu inbox after apply, or alarms are not delivered. | Phase 14 apply checklist |

None of the findings blocks this gate: there are no cost, security or destroy risks, and all of them are wiring items for the merge or Phase 14.

## 4. Cost acceptance

- High estimate $17.04 (agent cost table), accepted with these usage rules: Neptune and the ALB are toggled on only during the Phase 14/15c windows; ECS desired count is 0 outside test windows; EMR has at most 2 core/task nodes plus the primary, with 15-minute idle auto-termination; destroy at the end of each window.
- Hard stop: the AWS spend limit is $20 measured before credits; budget alerts at $5, $10 and $15.

## 5. Decision

- [x] **APPROVED** — Phase 12 accepted. Apply is deferred to Phase 14 and is human-only.
- Findings G12-1 to G12-3 go into the lane2 → main merge checklist; G12-4 and G12-5 go into the Phase 13 work order; G12-6 goes into the Phase 14 apply checklist.

Signed: Dhruv Mehta — 2026-11-23