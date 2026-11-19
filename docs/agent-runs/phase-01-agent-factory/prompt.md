Use the factory-engineer agent to create the complete ScholarChain agent system from the authority matrix below, which is transcribed from the CS441 ScholarChain course specification. This matrix is human-ratified for this phase.

AUTHORITY MATRIX

| Agent | Creates and owns | Must not do | Primary gate |
|---|---|---|---|
| factory-engineer | CLAUDE.md, docs/agents.md, .claude/**, .mcp.json | Application, data, build, infrastructure, or deployment work | Human constitutional ratification and code review |
| build-engineer | build.sbt, project/*, .scalafmt.conf, local compose files, packaging, .gitignore | Application source, version-only maintenance, Terraform, deployment | sbt check and code review |
| domain-engineer | modules/core/**, shared domain records, stable identifiers, serialization contracts, configuration contracts | Dataset retrieval, distributed jobs, storage adapters, service routes, infrastructure | Contract tests, sbt check, code review |
| dataset-engineer | datasets/**, dataset manifest, download and sampling scripts, schema notes, license records | Hadoop analytics, Spark analytics, entity-match policy | Dataset validation tests and manifest review |
| hadoop-engineer | modules/hadoop/**, MapReduce jobs, Hadoop integration tests, counters, rejected-record formats | Spark jobs, storage clients, service APIs | Local or EMR sample run and deterministic output checks |
| entity-resolution-engineer | modules/entity-resolution/**, blocking keys, match features, thresholds, calibration tools, match decision schemas | Graph analytics, API synthesis, infrastructure | Gold-set evaluation and match-policy review |
| spark-engineer | modules/spark/**, graph construction, graph analytics, evidence-path generation | Hadoop parser ownership, runtime API, Terraform | Spark tests, sample run, output invariants |
| storage-engineer | modules/storage/**, loaders and adapters for S3, Neptune, DynamoDB, S3 Vectors, optional OpenSearch | Terraform, answer synthesis, runtime HTTP routes | Contract tests and bounded load test |
| rag-engineer | modules/rag/**, prompts/**, query plans, retrieval fusion, evidence bundles, claim verification | HTTP transport, cloud deployment, dataset acquisition | Retrieval and groundedness evaluation |
| service-engineer | modules/services/**, REST models, routes, health checks, runtime composition | Hadoop and Spark algorithms, Terraform, agent constitution | API tests and sbt check |
| test-engineer | tests/** and designated test source trees (modules/*/src/test/**) | Production implementation | Exact pass and fail report; no production edits |
| code-reviewer | No implementation artifacts; review reports only | Any write or edit | Read-only verified verdict |
| infra-engineer | infra/terraform/**, scripts/** (plan, deploy, rollback, smoke-test, run-bounded-pipeline, cleanup, check, package, archive-evidence), optional .github/workflows/** | terraform apply, live deployment, application source | fmt, validate, plan, script syntax checks, security scan, human approval |
| deploy-engineer | Deployment execution reports and release records under reports/deployments/** | Authoring Terraform or deployment scripts | Approved plan, clean tree, smoke tests, rollback gate |
| experiment-engineer | experiments/**, load-tests/**, experiment manifests, raw result collectors | Altering production algorithms to improve reported results | Reproducible run and immutable raw results |
| documentation-engineer | README.md, docs/** except docs/agents.md, docs/agent-runs/** and docs/incidents/**, plus reports/final-report.md, diagrams, demo script | Inventing results or changing implementation | Link check, command replay, evidence review |
| incident-responder | docs/incidents/**, diagnosis reports, approved rollback and bounded recovery actions | Schema changes, destructive data repair, infrastructure authorship | Human escalation for data, security, or destructive actions |
| dependency-updater | Version values in the approved dependency ledger and dependency update reports | Build structure, application features, major upgrades without approval | sbt check, vulnerability review, code review |

docs/agent-runs/** is human-owned evidence. The orchestrator may assemble agent reports there; no implementation agent may rewrite a human decision.

CONTROL RULES (from the course specification)
1. One artifact class has exactly one creating and owning agent.
2. An agent asked to edit another agent's artifact must decline and name the correct owner.
3. Reviewer-class agents have no write tools.
4. Infrastructure authors may produce plans but may not apply or deploy them.
5. Deployment operators may execute approved scripts but may not author those scripts.
6. Tests must be run before an agent may report implementation work complete.
7. Dangerous commands are blocked by hooks and permission rules.
8. Constitutional files require human ratification and a fresh session before they take effect.
9. One project phase uses one fresh orchestrator session and normally one commit.
10. Agent reports, committed contracts, and repository artifacts are the only memory that crosses sessions.
11. A blocked agent returns BLOCKED-ON with evidence and the required owner. The orchestrator routes the dependency, validates the repair, and reruns the blocked agent in a fresh context.
12. No agent approves its own work.

Every agent report uses these fields: STATUS: COMPLETE | BLOCKED-ON | FAILED, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE. An agent must never report a command as successful unless it observed the successful output.

Create:

1. CLAUDE.md
- Identify ScholarChain as a Scala 3, Hadoop, Spark, AWS, Graph-RAG project.
- Define module dependency boundaries (core is depended on by all modules; hadoop, entity-resolution, spark, storage, rag, services must not form cycles; services composes rag and storage; no module depends on services).
- Define sbt check as the repository definition of done.
- State the one-owner-per-artifact rule.
- Require feature tests for every behavior change.
- Require provenance for every data transformation and runtime claim.
- Require secrets only through Secrets Manager or approved local placeholders.
- Require all AWS infrastructure through Terraform.
- Forbid direct cloud-console mutation as an implementation path.
- State that hidden model reasoning is never stored.
- Import docs/agents.md.

2. docs/agents.md
- Transcribe the complete artifact ownership table above for all 18 agents, refined to exact repository paths.
- Add a lifecycle-to-agent routing table covering phases 0 to 17 of the course project.
- Add escalation rules for constitutional changes, data destruction, security changes, Terraform apply, deployment, expensive scale changes, and ambiguous ownership.
- State that subagents do not invoke one another and that all routing passes through the orchestrator.
- State that phase evidence lives in docs/agent-runs/phase-NN-<short-name>/ containing prompt.md, agent-report.md, review-report.md and test-report.md when applicable, human-gate.md, repair-NN.md when applicable, and commit.txt.

3. All remaining .claude/agents/*.md files (17 files; do not modify factory-engineer.md)
- Each has frontmatter with name, a routing-grade description in the vocabulary of this project, an explicit tools field, and model: sonnet, except code-reviewer which uses model: opus.
- Body sections: role, owned artifacts, non-negotiable laws, ordered procedure, refusal boundaries (decline and name the correct owner), and the report contract above.
- Use the narrowest practical tools field.
- code-reviewer gets Read, Grep, Glob only. No Edit, Write, or Bash.
- infra-engineer gets authoring tools plus Bash for fmt, validate, plan and syntax checks, but no apply or live deployment authority.
- deploy-engineer gets execution authority only through approved scripts under scripts/ and approved environments; it does not get Write or Edit on infra/ or scripts/.
- test-engineer gets write access only to test territories and must leave production defects exposed and report them to the owner.

4. .claude/settings.json and hooks
- PreToolUse hook on Bash running .claude/hooks/guard-dangerous.sh.
- PostToolUse hook on Write|Edit running .claude/hooks/format-scala.sh.
- Stop and SubagentStop hooks running .claude/hooks/verify-tests-ran.sh.
- .claude/hooks/validate-agent-files.sh as a runnable validation script.
- Explicit allow and deny permission lists.
- Deny reads of .env, .env.*, *.pem, *.key, ~/.aws/credentials, and secret directories.
- Deny terraform apply and terraform destroy.
- Deny git push --force, -f, --force-with-lease and +refspec pushes.
- Deny destructive S3 (s3 rm --recursive, s3 rb, delete-bucket), DynamoDB (delete-table), Neptune (delete-db-cluster, delete-db-instance), S3 Vectors (delete-vector-bucket, delete-index, delete-vectors), ECR (delete-repository, batch-delete-image), KMS (schedule-key-deletion, disable-key), and EMR (terminate-clusters, large modify-instance-groups scale-out) commands.
- Block direct modification of ratified constitutional files by agents other than factory-engineer.

Hook implementation constraints for this machine (macOS, Apple Silicon):
- Use #!/usr/bin/env bash and stay compatible with bash 3.2: no associative arrays, no mapfile, no ${var,,}.
- jq is not installed. Parse hook JSON input from stdin with python3.
- Block by exiting with code 2 and an actionable message on stderr naming what was blocked and which approved path to use instead.
- The guard must inspect the command text only and must never execute it.
- format-scala.sh must exit 0 harmlessly when the changed file is not Scala or when scalafmt or sbt is unavailable.
- verify-tests-ran.sh compares .claude/.last-test-run with the newest source change under modules/, tests/, project/ and build.sbt. When no such source files exist yet, it must allow stopping so this phase and Phase 0-style constitutional work are not blocked. When source exists and the marker is missing or older, it must block with a message telling the agent to run sbt check.
- All hooks must be executable (chmod +x).

5. .mcp.json
- GitHub access (read-oriented).
- AWS API inspection with no unrestricted write authority.
- Terraform documentation or provider inspection.
- AWS documentation access where available.
- Use environment-variable names only, never secret values. Document every server's trust boundary and environment variables in docs/agents.md.

6. .claude/commands
- deploy.md, rollback.md, incident.md, run-pipeline.md, and experiment.md.
- Every command must name the responsible agent and its gates, and take run-specific details as arguments.

7. docs/agent-runs/README.md
- Define the phase evidence format above.
- State that hidden model reasoning is not stored.

Do not create build files or application source. Do not modify factory-engineer.md, datasets/, or any existing docs/agent-runs/phase-* folder.

Validate every JSON file with python3 -m json.tool, every shell script with bash -n, all hook executable bits, all agent frontmatter, unique agent names, ownership completeness, routing collisions, routing orphans, and reviewer tool fences. Run validate-agent-files.sh and show its output.

Also run these safe guard probes by piping test JSON into guard-dangerous.sh (never run the commands themselves) and show exit codes: a Bash command "terraform destroy", a Bash command "git push --force origin main", and a Bash command "aws s3 rb s3://scholarchain-raw --force". Each must exit 2. Also show that a harmless command such as "ls" exits 0.

Present the complete diff, per-file authority justification, audit results, validation output, probe output, and post-ratification probe instructions. Stop for human ratification.