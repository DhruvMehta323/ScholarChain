# Phase 12 — review report (code-reviewer)

Stored by the orchestrator from the code-reviewer's returned report, verbatim in substance.

## D pass 1 — work order A after repair round 1

```
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 12 D (pass 1), read-only review of infra/terraform/**, scripts/plan.sh, scripts/cleanup.sh, scripts/lib/common.sh (untracked) against prompt.md §2 A, §3, §6, Gate and the recorded human decisions.
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: none run; validation evidence in agent-report.md UNVERIFIED by the reviewer (not checkable by reading).
VERDICT: REQUEST_CHANGES (1 HIGH, 7 MEDIUM, 13 LOW)
```

Human decisions checked as requirements: all met (variables.tf:146-150, 262-266, 206-210, 81-89, 56-65; iam/main.tf:44, 342, 379, 414, 432; plan.sh:29-40).

PASS: ownership; secrets (secrets created empty, ARNs-only outputs); no NAT gateway, no interface endpoint, gateway endpoints S3 + DynamoDB only; output names vs scholarchain-storage.v1.conf, ConfigKeys and STORAGE-CONTRACTS §4 (15 tables with correct pk/sk, expiresAt TTL only on QuerySession and EntityLinkCache, 5 index names, 36 outputs); bounded defaults (ECS desired 0 / max 4, EMR 1..3 with 600 s idle, 7-day logs, $25 budget with $5/$12/$20 alerts); public exposure (full public access blocks + TLS-only policies, Neptune private, SG chain ALB -> query-api -> retrieval -> Neptune and EMR -> Neptune); NAT-free reachability; PassRole scoping; runtime/deployment role separation. apply/destroy appear only as printed heredoc text in cleanup.sh and in comments in plan.sh. Cost citations present (URLs not fetched by the reviewer: UNVERIFIED).

### HIGH
- **H1** iam/main.tf:237-247, pipeline/main.tf:14-15: the EMR service role has only AmazonEMRServicePolicy_v2, whose iam:PassRole covers role/EMR_EC2_DefaultRole only; the custom instance-profile role scholarchain-emr-ec2 cannot be passed, so CreateCluster fails. Fix: inline iam:PassRole on the emr_ec2 role ARN with iam:PassedToService = ec2.amazonaws.com. (Confirm at https://docs.aws.amazon.com/aws-managed-policy/latest/reference/AmazonEMRServicePolicy_v2.html.)

### MEDIUM
- **M1** observability/main.tf:123-135: with create_alb=false the "API latency" metric widget has metrics = []; PutDashboard is expected to reject it at apply. Fix: include it only when has_alb, or use a text widget.
- **M2** iam/main.tf:270-279: EMR instance role lacks s3:DeleteObject; FileOutputCommitter/EMRFS rename (copy + delete of _temporary/) and Spark overwrite fail. Fix: s3:DeleteObject scoped to the curated bucket prefixes used.
- **M3** data/s3.tf:102-109: the 7-day noncurrent-version expiry also applies to raw and reports (evidence) buckets, making accidental deletes permanent. Fix: exclude evidence buckets or use a long window.
- **M4** cleanup.sh:59-66 with data/s3.tf:27-124: destroy removes the evidence buckets' sub-resources (public access block, TLS policy, versioning, SSE, lifecycle, ownership) before failing on the non-empty buckets. Fix: printed human commands remove the preserved buckets and their sub-resources from state first (human-only), or document a targeted procedure and the residual posture.
- **M5** .gitignore:40-42, plan.sh:13, 67-68, 103, INFRA-CONTRACTS.md:230: tfplan-*.sha256 / .summary.txt / .untracked.txt are NOT gitignored (no `tfplan*` pattern; the checkpoint wording was wrong); the summary carries the account ID, and later runs see earlier outputs as untracked. Fix: infra/terraform/plans/.gitignore (`*`, `!.gitignore`) and correct the docs.
- **M6** iam/main.tf:338-353, pipeline/main.tf:18-43 (UNVERIFIED): the Step Functions role may need elasticmapreduce:PutAutoTerminationPolicy, PutManagedScalingPolicy and AddTags for the RunJobFlow parameters used. Fix: verify against EMR IAM docs and add scoped to cluster/*.
- **M7** environments/course/main.tf:69, 76, INFRA-CONTRACTS.md:117, 177: core treats a blank env var as unset (Config.scala:14, 123; ModuleConfig.scala:41). With Neptune off, SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT="" makes the required key missing (every service resolving core config fails to start), and SCHOLARCHAIN_STORAGE_NEPTUNE_BULK_LOAD_ROLE_ARN="" silently falls back to the placeholder ARN. Fix: correct §5/§9a, omit the role-ARN variable when Neptune is off or document the fallback, raise "optional Neptune endpoint" as an open point for domain-engineer / Phase 10; no change to modules/.

### LOW (defer with disclosure)
- L1 ALB CIDR validation accepts /1–/7 and IPv6 (variables.tf:60-62; network/main.tf:141-159).
- L2 plan.sh hashes the untracked file list, not contents (plan.sh:33-34).
- L3 plan.sh summary: iam_changes counts aws_iam_* only; stateful list omits log groups and S3 lifecycle/versioning (plan.sh:93-100).
- L4 cleanup.sh:58, 63 print apply/destroy commands in a heredoc (never executed); human to confirm against §0.4 wording.
- L5 dashboard DynamoDB SEARCH schema misses the Operation dimension (observability/main.tf:144-145).
- L6 one SG used as both EMR managed master and slave (pipeline/main.tf:27-28); confirm in Phase 14 smoke.
- L7 Neptune loader trust policy has no aws:SourceAccount condition (neptune/main.tf:53-62).
- L8 ECS tasks fail while secrets are empty; query-api always requires ANTHROPIC_API_KEY (compute/main.tf:16, 25, 34, 101); document in runbook.
- L9 budget is MONTHLY; a two-month project can exceed $25 total (observability/main.tf:184-201).
- L10 no per-step TimeoutSeconds; only the 4-hour execution timeout (pipeline/main.tf:50, 72-86).
- L11 cleanup.sh:29 `terraform state list` needs an initialized directory.
- L12 RunJobFlow "*" could add an aws:RequestTag condition; re-check DeregisterTaskDefinition / DescribeLogGroups resource-level support.
- L13 cost table omits EMR public IPv4 and may undercount Container Insights metrics.

MANUAL ACTION REQUIRED (reviewer): human approves the IAM set as a security change (docs/agents.md §4); human confirms L4; scanner before B.

## D re-review — after the single repair round of A

```
STATUS: COMPLETE
OWNER: code-reviewer
COMMANDS RUN: none (Read, Grep, Glob only)
VERDICT: APPROVE WITH NOTES. All HIGH and MEDIUM findings resolved (M6 as a precaution whose necessity is unconfirmed); 3 new LOW; no new HIGH or MEDIUM.
```

- H1 RESOLVED (iam/main.tf:249-269). M1 RESOLVED (observability/main.tf:138-152). M2 RESOLVED (iam/main.tf:297-308). M3 RESOLVED (data/s3.tf:102-114).
- M4 RESOLVED (cleanup.sh:59-73; the 14 state addresses match data/s3.tf and module "data"; print-only). M5 RESOLVED (plans/.gitignore; INFRA-CONTRACTS.md:232).
- M6 RESOLVED as a precaution (iam/main.tf:383-390). Residual: tag-on-create may be evaluated at RunJobFlow on "*"; Phase 14 smoke job will show it.
- M7 RESOLVED (environments/course/main.tf:60-81; types still compatible; O1 documented).
- LOW fixed: L1, L5, L8, L9, L10, L11, L13. Deferred with disclosure: L2, L3, L4, L6, L7, L12.
- New LOW:
  - N1 bash -n result for the changed cleanup.sh not reported. CLOSED by the orchestrator: `bash -n scripts/cleanup.sh` exit 0 (observed 2026-11-22).
  - N2 cleanup.sh:42 lists the raw/reports sub-resources under "all-other-resources-to-remove" although step D preserves them (misleading listing). Deferred.
  - N3 variables.tf:61 L1 validation with `&&` may raise an evaluation error instead of the validation message for a value without "/"; still rejected. Deferred.
  - Note: output live_suite_environment still emits SCHOLARCHAIN_IT_NEPTUNE_ENDPOINT = "" with Neptune off (output only). Deferred.

## D review — Phase 08 alignment (human-ordered change)

```
STATUS: COMPLETE
OWNER: code-reviewer
COMMANDS RUN: none (Read, Grep, Glob only)
VERDICT: APPROVE WITH NOTES (no HIGH, no MEDIUM, 5 LOW)
```

Verified: test claims table (data/dynamodb.tf:15, 30-41; protection off for it only); live_suite_environment uses the test claims table (outputs.tf:158-159),
ECS keeps production (main.tf:71); maxReceiveCount = embedding_max_attempts + 1 = 4 is correct against §12.3 (main.tf:128-132); embedding-worker IAM
(iam/main.tf:161-211) all resource-scoped; Bedrock foundation-model ARN format correct, only when embedding_provider = bedrock; bedrock_model_arns now
query-api generation only; retrieval single embedding-model grant; one Voyage secret reused; lifecycle on logs/it-embedding/. (a) retrieval keeps
QueryVectors/GetVectors/ListVectors/GetIndex; (b) index ARNs provider-computed; (e) redrive/visibility OK; (f) env names consistent; (g) no new "*", no secrets.

LOW:
- L-A live suite and a deployed worker share the production queue; a worker scaled above 0 could take the suite's indexOverride jobs and claim them into
  the production claims table. Fix: runbook precondition (embedding-worker at 0 tasks, empty queue) or separate test queues. Deferred.
- L-B worker call set unverified (GetQueueUrl, GetIndex). RESOLVED by the orchestrator after the merge (read-only, lane2 23f2914):
  WorkQueues.scala calls only sendMessage/receiveMessage/deleteMessage; VectorStores.scala only getVectors/putVectors/queryVectors; ObjectStores.scala
  reads with getObjectAsBytes (s3:GetObject). No GetQueueUrl, no GetIndex. Open, NOT VERIFIED: KeyValueStores.scala:212, 218 put with
  ReturnValuesOnConditionCheckFailure.ALL_OLD; whether that needs any permission beyond dynamodb:PutItem is to be checked in B.
- L-C worker text read scoped to the curated bucket; producers must write texts there. Document in §5. Deferred.
- L-D visibility timeout 300 s not coupled to batch time. Deferred.
- L-E embedding_provider and embedding_model_id not cross-validated. Deferred.

## D reread — human-approved change set (2026-11-23)

Stored by the orchestrator, condensed from the returned report.

```
STATUS: COMPLETE
OWNER: code-reviewer
COMMANDS RUN: none (Read, Grep, Glob only)
VERDICT: REQUEST_CHANGES (0 HIGH, 6 MEDIUM, 10 LOW)
```

Pass: us-east-2 default; "not reconfirmed" marking; totals arithmetic; guard before planning in the normal case; regular FARGATE; secret and env names; retrieval reach;
/healthz target check (not verified in code); three execution roles scoped; Phase 08 needs; 8 trivy ignores within the approved list; no regressions.

MEDIUM:
- M-1 observability/main.tf:107: recursive `flatten` collapses metric arrays; PutDashboard expected to reject the body at first provisioning. Missed in three earlier passes.
- M-2 INFRA-CONTRACTS.md:238-250: (c) $14.19 does not show being under $15: own tolerance gives 15.69; omitted Fargate deploy hours (+0.21), a Phase 14 EMR smoke run (+0.3 to +1.61), Neptune above 1 NCU (up to +2.89), Neptune storage and IO, and fixed lines beyond 30 days (60 days = 16.09).
- M-3 compute/main.tf:148-150, 224-249: ecs_min_tasks = 0 may leave query-api and retrieval at 1 task (about $0.42 per task-day). Behavior UNVERIFIED. Missed in pass 1.
- M-4 common.sh:56,62-76,89-131; plan.sh:105-110: the pre-plan value can differ from Terraform's (auto.tfvars order, symlinks, block comments or heredocs, override files, workspace, TF_DATA_DIR); the post-plan check leaves the plan file. Wrong account is still refused by allowed_account_ids unless an override file removes it.
- M-5 observability/main.tf:8-17: alarm emails expected not to be delivered with alias/aws/sns (reviewer's knowledge, not fetched). CloudWatch is the only publisher; Budgets emails directly.
- M-6 INFRA-CONTRACTS.md:33 contradicts line 183 and the code (SNS encryption).

LOW: L-1 stale text (217, 197, 266, 93, 282, 250; variables.tf:231, 171); L-2 plan summary carries account_id into tracked report; L-3 *.tfvars.json not gitignored;
L-4 dynamodb.tf:35 reason ("rebuilt from S3") wrong for RunLedger, EvidenceBundle, claims; L-5 iam/main.tf:489 RegisterTaskDefinition may support resource scoping (UNVERIFIED);
L-6 worker role lacks s3:ListBucket (403 vs 404); L-7 no runtime role can enqueue the first embedding job; L-8 live suite shares the production queue; L-9 cleanup.sh:42; L-10 variables.tf:70.

Reviewer corrections: M-1, M-2, M-3 and L-5 were missed or wrongly passed earlier. Earlier L-B closed for the lane 1 working tree as read.

## D reread — repair round 2 plus the cost-only round (2026-11-24)

Stored by the orchestrator, condensed from the returned report.

```
STATUS: COMPLETE
OWNER: code-reviewer
COMMANDS RUN: none (Read, Grep, Glob only; read-only reads of the lane 1 tree)
VERDICT: APPROVE WITH NOTES (0 HIGH, 1 MEDIUM, 6 LOW); REQUEST_CHANGES for N-M1 only if the human does not defer it to Phase 10
```

Resolved: M-1 (concat; 25 explicit metrics), M-2 (as a model), M-3 (config and docs; MaxCapacity=0 NOT VERIFIED), M-4 (no fail-open found by reading), M-5, L-3, L-4. M-6 mostly. L-2 partial.
Cost round PASS: EMR at most 3 nodes, on-demand, idle 900 s, label validation and fallback; Container Insights dependants; budget cost_types; §10 arithmetic spot-checked;
§6.1 hung-run statement correct; DynamoDB load list consistent with the lane 1 tree; three execution roles PASS; AWS-0090 scoping; no regressions.
UNVERIFIED by the reviewer: MapReduce on emr-spark-8.1.0 with Spark only (Phase 14 smoke job); trivy attribution of AWS-0090; tool outputs.

MEDIUM:
- N-M1 iam/main.tf:100-104: query-api has s3:PutObject only on curated rag/evidence/*; the Phase 09 AuditAgent writes to <auditBucket>/rag/audit/ (lane 1 AuditAgent.scala:199-200, 258;
  RuntimeSupport.scala:73); no audit bucket in Terraform. Audited answers would fail with AccessDenied. Missed in earlier passes. Proposed: defer to Phase 10 reconciliation, record as open point.

LOW: L-1 INFRA-CONTRACTS.md:253 alerts $5/$12/$20 stale; L-2 terraform plan console output shows bucket names with the account ID (paste only the summary block);
L-11 fallback needs a flag flip and apply between MapReduce and Spark executions; a fallback smoke rerun raises HIGH to about $17.99; L-12 neptune_max_ncu has no validation; L-9, L-10 open.

## D reread — N-M1 audit-write change only (2026-11-24)

```
STATUS: COMPLETE
OWNER: code-reviewer
COMMANDS RUN: none (Read, Grep only; read-only reads of the lane 1 working tree)
VERDICT: APPROVE WITH NOTES (Terraform change correct as ordered)
```

PASS: iam/main.tf:105-111 WriteAuditDocuments, s3:PutObject on <curated>/rag/audit/* only, query-api task role only; outputs.tf:20-23 rag_audit_bucket;
AuditAgent needs PutObject only (putIfAbsent = single putObject with ifNoneMatch, 412 = AlreadyExists; ObjectStores.scala:398-443); reads covered by GetObject on rag/*.
No dedicated audit-bucket config key exists in lane 1 resources. INFRA-CONTRACTS :226 and :303 accurate against code.

MEDIUM (cross-lane, not an infrastructure defect): lane 1 uncommitted Phase 10 Composition.scala:222-232 (tablesOf) derives the audit bucket from storage.s3.rag-prefix
(default "rag/"): bucket "rag", prefix "audit". On AWS every audit write and bundle spill would get AccessDenied. Owner: service-engineer (modules/services/**).
Suggested fix: bucket from the curated bucket (SCHOLARCHAIN_STORAGE_CURATED_BUCKET), prefix rag-prefix + "audit". NOT VERIFIED against main (uncommitted).
LOW (doc, deferred): INFRA-CONTRACTS.md:520 O3 omits this derivation and names the wrong consequence.
