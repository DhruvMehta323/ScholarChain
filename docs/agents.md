# ScholarChain agents: roster, ownership, routing, escalation

Constitutional file. Owner: factory-engineer. It is derived from the human-ratified Phase 01 authority matrix, transcribed from the CS441 ScholarChain course specification. Changes take effect only after human ratification and a fresh session.

## 1. Artifact ownership

Every path below has exactly one owning agent. A path that is not listed is unowned. Report it to the orchestrator, which routes the gap to factory-engineer. Do not create unowned paths. In the globs, `**` matches any depth and `*` matches one path segment. Test trees inside modules belong to test-engineer and are excluded from each module owner.

| Agent | Owns (exact repository paths) | Must not do | Primary gate | Tools | Model |
|---|---|---|---|---|---|
| factory-engineer | `CLAUDE.md`, `docs/agents.md`, `.claude/**` (agents, hooks, commands, `settings.json`), `.mcp.json` | Application, data, build, infrastructure, or deployment work | Human constitutional ratification and code review | Read, Grep, Glob, Write, Edit, Bash | inherit |
| build-engineer | `build.sbt`, `project/**` except `project/Versions.scala`, `.scalafmt.conf`, `.gitignore`, `.sbtopts`, `.jvmopts`, local compose files (`docker-compose*.y*ml`, `compose*.y*ml`), `packaging/**` | Application source, version-only maintenance, Terraform, deployment | `sbt check` and code review | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| domain-engineer | `modules/core/**` except `modules/core/src/test/**`: shared domain records, stable identifiers, serialization contracts, configuration contracts | Dataset retrieval, distributed jobs, storage adapters, service routes, infrastructure | Contract tests, `sbt check`, code review | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| dataset-engineer | `datasets/**`: dataset manifest (`datasets/manifest.*`), download and sampling scripts, schema notes, license records | Hadoop analytics, Spark analytics, entity-match policy | Dataset validation tests and manifest review | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| hadoop-engineer | `modules/hadoop/**` except `modules/hadoop/src/test/**`: MapReduce jobs, Hadoop integration tests in `modules/hadoop/src/it/**`, counters, rejected-record formats | Spark jobs, storage clients, service APIs | Local or EMR sample run and deterministic output checks | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| entity-resolution-engineer | `modules/entity-resolution/**` except `modules/entity-resolution/src/test/**`: blocking keys, match features, thresholds, calibration tools, match decision schemas, gold-set labels | Graph analytics, API synthesis, infrastructure | Gold-set evaluation and match-policy review | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| spark-engineer | `modules/spark/**` except `modules/spark/src/test/**`: graph construction, graph analytics, evidence-path generation | Hadoop parser ownership, runtime API, Terraform | Spark tests, sample run, output invariants | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| storage-engineer | `modules/storage/**` except `modules/storage/src/test/**`: loaders and adapters for S3, Neptune, DynamoDB, S3 Vectors, optional OpenSearch | Terraform, answer synthesis, runtime HTTP routes | Contract tests and bounded load test | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| rag-engineer | `modules/rag/**` except `modules/rag/src/test/**`, `prompts/**`: query plans, retrieval fusion, evidence bundles, claim verification | HTTP transport, cloud deployment, dataset acquisition | Retrieval and groundedness evaluation | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| service-engineer | `modules/services/**` except `modules/services/src/test/**`: REST models, routes, health checks, runtime composition | Hadoop and Spark algorithms, Terraform, agent constitution | API tests and `sbt check` | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| test-engineer | `tests/**`, `modules/*/src/test/**` | Production implementation | Exact pass and fail report; no production edits | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| code-reviewer | No implementation artifacts. It returns review reports as messages, and the orchestrator stores them as `review-report.md` | Any write or edit | Read-only verified verdict | Read, Grep, Glob | opus |
| infra-engineer | `infra/terraform/**`, `scripts/**` (`plan`, `deploy`, `rollback`, `smoke-test`, `run-bounded-pipeline`, `cleanup`, `check`, `package`, `archive-evidence`), optional `.github/workflows/**` | `terraform apply`, live deployment, application source | fmt, validate, plan, script syntax checks, security scan, human approval | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| deploy-engineer | `reports/deployments/**` (deployment execution reports and release records) | Authoring Terraform or deployment scripts | Approved plan, clean tree, smoke tests, rollback gate | Read, Grep, Glob, Write, Bash | sonnet |
| experiment-engineer | `experiments/**` (experiment manifests, raw result collectors, raw results), `load-tests/**` | Altering production algorithms to improve reported results | Reproducible run and immutable raw results | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| documentation-engineer | `README.md`, `docs/**` except `docs/agents.md`, `docs/agent-runs/**` and `docs/incidents/**`, plus `reports/final-report.md`, diagrams (`docs/diagrams/**`), demo script (`docs/demo-script.md`) | Inventing results or changing implementation | Link check, command replay, evidence review | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| incident-responder | `docs/incidents/**` (diagnosis reports and incident records). It executes approved rollback and bounded recovery actions | Schema changes, destructive data repair, infrastructure authorship | Human escalation for data, security, or destructive actions | Read, Grep, Glob, Write, Edit, Bash | sonnet |
| dependency-updater | Version values in the approved dependency ledger `project/Versions.scala`, and `reports/dependency-updates/**` | Build structure, application features, major upgrades without approval | `sbt check`, vulnerability review, code review | Read, Grep, Glob, Write, Edit, Bash | sonnet |

**Human-owned evidence:** `docs/agent-runs/**`. The orchestrator may assemble agent reports there. No implementation agent may write or rewrite a human decision, and `docs/agent-runs/*/human-gate.md` is never written by any agent tool.

**Runtime state (not an artifact):** `.claude/.last-test-run` is written only by the canonical test command (section 7). It should be gitignored. That is a build-engineer change to `.gitignore`.

### 1.1 Ownership resolutions

These read the matrix to exact paths where its classes could overlap. Each one is an interpretation and must be ratified with this file.

1. **Dependency ledger.** The matrix gives `project/*` to build-engineer and "version values in the approved dependency ledger" to dependency-updater. The ledger is the single file `project/Versions.scala`, which holds only version constants and is owned by dependency-updater. build-engineer owns every other file under `project/`, including `project/Dependencies.scala`, which references `Versions.*`. A new dependency needs both owners: build-engineer adds the reference, and dependency-updater adds the version value.
2. **Hadoop integration tests.** The matrix gives "Hadoop integration tests" to hadoop-engineer and `modules/*/src/test/**` to test-engineer. Hadoop integration tests therefore live in `modules/hadoop/src/it/**`, owned by hadoop-engineer. Unit and feature tests live in `modules/hadoop/src/test/**`, owned by test-engineer.
3. **Contract tests.** Contract tests for `core` and `storage` are test sources in `modules/<m>/src/test/**`, owned by test-engineer. domain-engineer and storage-engineer specify the contracts. test-engineer encodes them.
4. **Dataset scripts and infrastructure scripts.** Download and sampling scripts live under `datasets/**` (dataset-engineer). `scripts/**` at the repository root is infra-engineer's operational script set.
5. **Rollback execution.** infra-engineer authors `scripts/rollback.sh`. deploy-engineer executes it as part of a release (the rollback gate). incident-responder executes it during an incident, only with a recorded human approval. Neither of them edits it.
6. **Diagnosis reports.** Incident diagnosis reports live in `docs/incidents/**` (incident-responder). Deployment execution records live in `reports/deployments/**` (deploy-engineer).
7. **Gold sets.** Entity-resolution gold-set labels and calibration data live inside `modules/entity-resolution/**`. They are match policy, which the matrix forbids to dataset-engineer.

## 2. Lifecycle routing (phases 0 to 17)

The phase names and owners below are the official course phases supplied by the human (Phase 01 repair-01 work order). The owning agent is fixed by section 1 whatever the phase is called. test-engineer and code-reviewer are cross-cutting gates in every implementation phase.

| Phase | Work | Primary agent | Supporting agents (separate routed runs) | Gate |
|---|---|---|---|---|
| 00 | Plant the seed agent | human orchestrator | none | Human constitutional ratification |
| 01 | Factory builds the agent system | factory-engineer | none | Human constitutional ratification, fresh session, post-ratification probes |
| 02 | Build and repository skeleton | build-engineer | code-reviewer | `sbt check`, code review |
| 03 | Contracts, configuration, dataset manifests | dataset-engineer, domain-engineer | code-reviewer | Contract tests, `sbt check`, code review; dataset validation tests, manifest review |
| 04 | Raw ingestion and Hadoop MapReduce | hadoop-engineer | test-engineer, code-reviewer | Local sample run, deterministic output checks |
| 05 | Entity resolution | entity-resolution-engineer | code-reviewer | Gold-set evaluation, match-policy review |
| 06 | Temporal coauthorship graph and Spark analytics | spark-engineer | code-reviewer | Spark tests, sample run, output invariants |
| 07 | Storage adapters and loading | storage-engineer | code-reviewer | Contract tests, bounded load test |
| 08 | Embeddings and S3 Vectors | storage-engineer, rag-engineer | none | Contract tests and bounded load test; retrieval and groundedness evaluation |
| 09 | Runtime collaboration-analysis agents | rag-engineer | code-reviewer | Retrieval and groundedness evaluation |
| 10 | REST microservices | service-engineer | none | API tests, `sbt check` |
| 11 | Adversarial testing and full review | test-engineer, code-reviewer | none | Exact pass and fail report; no production edits; read-only verified verdict |
| 12 | AWS infrastructure in Terraform | infra-engineer | code-reviewer | fmt, validate, plan, script syntax, security scan, human approval |
| 13 | Build, deployment, experiment automation | infra-engineer | none | fmt, validate, plan, script syntax checks, security scan, human approval |
| 14 | Apply and deploy through human approval | deploy-engineer | none | Approved plan, clean tree, smoke tests, rollback gate |
| 15 | Data pipeline and elasticity experiments | experiment-engineer | none | Reproducible run, immutable raw results |
| 16 | Evaluation | experiment-engineer, documentation-engineer | none | Reproducible run and immutable raw results; link check, command replay, evidence review |
| 17 | Documentation, final review, release | documentation-engineer, code-reviewer, test-engineer | none | Link check, command replay, evidence review; read-only verified verdict; exact pass and fail report |

## 3. Routing rules

- Subagents do not invoke one another. No agent definition grants the Task or Agent tool. All routing passes through the orchestrator, which is the main Claude Code session run by the human.
- The orchestrator dispatches one agent per work order. It never asks an agent to work outside the agent's ownership.
- A blocked agent stops and returns `STATUS: BLOCKED-ON` with `BLOCKED-ON OWNER` (an agent from section 1) and `BLOCKED-ON EVIDENCE` (the artifact path, the ownership row, and why the work needs it). The orchestrator routes the dependency to that owner, validates the repair, and reruns the blocked agent in a fresh context.
- Before a phase can be accepted, code-reviewer reviews implementation work and test-engineer reports exact pass and fail results. No agent approves its own work.
- Agent reports, committed contracts, and repository artifacts are the only memory that crosses sessions.

## 4. Escalation rules

Each of these requires an explicit human decision, recorded by the human in the phase's `human-gate.md` before the action. Agents stop and report `MANUAL ACTION REQUIRED` rather than proceed.

| Trigger | Rule |
|---|---|
| Constitutional change (`CLAUDE.md`, `docs/agents.md`, `.claude/**`, `.mcp.json`) | factory-engineer proposes a diff. A human ratifies it, then the session restarts. Nothing takes effect before both. |
| Data destruction (deleting or overwriting raw datasets, S3 objects, graph or table contents, raw experiment results) | Human-only. Agents may propose a written plan. Destructive repair is never done by an agent, including incident-responder. |
| Security change (IAM, KMS, network exposure, secrets, authentication, MCP trust boundaries, permission rules) | Human approval. A security scan and a code-reviewer verdict are required before approval. |
| Terraform apply or destroy | Human-only. infra-engineer delivers a saved plan and its review. The human runs apply. |
| Deployment | deploy-engineer only, using approved scripts, an approved environment, and a recorded human approval of the plan. Each run of a deploy, rollback, or pipeline script prompts for human permission. |
| Expensive scale change (EMR above 3 instances per group, larger instance classes, Neptune or OpenSearch sizing, provisioned capacity) | Human approval with a cost estimate. The guard blocks EMR resize targets above the limit. |
| Ambiguous ownership (a path matches no owner or more than one) | Stop and return `BLOCKED-ON`, naming the orchestrator. The orchestrator routes the question to factory-engineer, which proposes an ownership diff for ratification. |

## 5. Enforcement layer and its limits

| Mechanism | File | What it does |
|---|---|---|
| PreToolUse `Bash` and `mcp__.*` | `.claude/hooks/guard-dangerous.sh` | Inspects command text only and never executes it. Blocks recursive deletes of root, top-level directories, home, the repository, `.git`, or their ancestors. Also blocks bare globs and unguarded `$VAR` targets, `mkfs`, `dd` to devices, fork bombs, Terraform apply, destroy, and state mutation (including `-chdir`, tofu, terragrunt, and variable or alias forms), destructive AWS operations (`delete-*`, `terminate-*`, `batch-delete-*`, `detach-*`, `s3 rb`, `s3 rm --recursive`, `sync --delete`, KMS key deletion or disable, EMR resize above `SCHOLARCHAIN_EMR_MAX_INSTANCE_COUNT` (default 3)), secret-store reads, references to secret files, force and ref-deleting pushes, `git clean -d/-x`, and shell writes to constitutional files, human gates, or the test marker. It reads the text of a script being executed (never runs it) so wrapped forms are caught. For deploy-engineer, it allows only approved scripts and read-only commands. For the aws-api MCP server, it allows only read operations. For the GitHub MCP server, it blocks write-named tools. |
| PreToolUse `Write\|Edit\|MultiEdit\|NotebookEdit` | `.claude/hooks/guard-writes.sh` | Blocks constitutional writes without a factory signal, human-gate writes, test-marker writes, and global config or credential writes. Blocks every write from code-reviewer. When the subagent is identified, enforces section 1 ownership. |
| PostToolUse `Write\|Edit` | `.claude/hooks/format-scala.sh` | Runs scalafmt on the single changed `*.scala`, `*.sc`, or `*.sbt` file when `.scalafmt.conf` exists. Always exits 0. Never deletes or reverts. |
| Stop and SubagentStop | `.claude/hooks/verify-tests-ran.sh` | Blocks ending a turn when source is newer than `.claude/.last-test-run`. Allows it when no source exists yet. code-reviewer is exempt. |
| Manual validator | `.claude/hooks/validate-agent-files.sh` (logic in `validate-agent-files.py`) | Not wired as a hook. A human or factory-engineer runs it to validate agent frontmatter, sections, roster, commands, settings, hooks, and MCP configuration. The logic lives in Python so that the guard's script-text scan does not read the validator's lists of forbidden commands as commands. |
| Permissions | `.claude/settings.json` | Explicit allow, ask, and deny lists. Denies secret reads, Terraform apply and destroy, force pushes, and destructive AWS operations. Asks before commits, pushes, `terraform init`/`plan`, deploy, rollback, and pipeline scripts, and EMR resizes. |

**Factory signal.** Constitutional files may be written through file tools or the shell only when the hook input carries `agent_type == "factory-engineer"`, or when the human started Claude Code with `SCHOLARCHAIN_FACTORY_SESSION=1` in its environment. An agent's `export` inside Bash does not reach hook processes. Limits, stated honestly:

- Whether `agent_type` is present in hook input depends on the Claude Code version. If it is absent, factory-engineer cannot write constitutional files until the human sets the environment variable, and per-agent ownership fences in guard-writes.sh do not apply. Only the global rules (constitutional, human-gate, marker, global config) remain in force.
- While `SCHOLARCHAIN_FACTORY_SESSION=1` is set, every agent in that session passes the constitutional rule. Set it only for factory-engineer phases.
- A human editing files outside Claude Code is not affected by hooks.

**Text-inspection limits.** The guards inspect text. They do not interpret programs. Some forms evade them: code in Python, Ruby, or other interpreters that builds commands dynamically, SDK calls (boto3, the AWS SDK for Java), base64-decoded payloads, commands assembled across separate tool calls, and the internals of compiled binaries. They are conservative in other places, and so they block some harmless text:

- any command that mentions `.env`, `*.pem`, `*.key`, or `secrets/`, even inside a grep pattern
- `rm -rf .` or `rm -rf ./*` in any directory
- a non-benign command with `terraform` and `apply` as separate words
- any Bash command whose text contains the phrase `terraform apply` or `terraform destroy` (any case, with any whitespace or a backslash line continuation between the words), even inside quotes, `echo`/`printf` arguments, `grep`/`rg` patterns, git commit or log messages, and comments. This is an accepted false positive for read-only commands such as shell `grep`. Workaround: search for the phrase with the Grep tool instead of shell grep.
- heredoc bodies that contain blocked commands
- running a script whose text contains a blocked command, such as `scripts/cleanup.sh`, which therefore stays human-executed

Script-text scanning follows only shell scripts (a `.sh`, `.bash`, or `.zsh` extension or a shell shebang). It skips the ratified hook scripts in `.claude/hooks/`, which contain the very patterns they block. Those scripts are protected from agent writes by the constitutional rule. Quoted text passed to `grep`, `rg`, `echo`, `printf`, and `git commit/log/tag/show` is not treated as a command, unless the same command line pipes into a shell, `eval`, `source`, or `xargs`. The exception is the phrase `terraform apply` or `terraform destroy`: the guard blocks it anywhere in the Bash command text, quoted text included. Scripts must guard `rm` targets as `"${VAR:?}"`. Infrastructure scripts must never call `terraform apply` or `destroy`, and pipeline scripts should use EMR auto-termination rather than `terminate-clusters`.

## 6. Commands

| Command | Responsible agent | Arguments | Gates |
|---|---|---|---|
| `/deploy` | deploy-engineer | environment, release id, approved plan reference | Approved plan, clean tree, smoke tests, rollback gate |
| `/rollback` | deploy-engineer | environment, target release id, reason | Recorded human approval, clean tree, post-rollback smoke tests |
| `/incident` | incident-responder | incident id, environment, symptom summary | Human escalation for data, security, or destructive actions |
| `/run-pipeline` | deploy-engineer | environment, dataset sample id, size bound | Approved plan, bounded size, clean tree, evidence archived |
| `/experiment` | experiment-engineer | experiment id, manifest path | Reproducible run, immutable raw results |

Commands set `disable-model-invocation: true`, so only a human can start them. They do not grant tools.

## 7. Test-run marker protocol

From the repository root, run:

```
sbt check; echo "exit=$?" > .claude/.last-test-run
```

This is the only accepted way to write the marker. Both guards block any other write to it, including file tools and `touch`. The marker proves that tests ran after the last source change and records their exit status. It does not prove that they passed. The agent's report must quote the observed result.

## 8. MCP servers and trust boundaries

MCP tools are available to the orchestrator session. No subagent `tools:` field lists MCP tools, so subagents do not receive them. `.mcp.json` contains environment-variable names only. It never contains secret values. Project MCP servers require per-server human approval in Claude Code before they start.

| Server | Launch | Environment variables | Trust boundary |
|---|---|---|---|
| `github` | `docker run ghcr.io/github/github-mcp-server` | `GITHUB_PERSONAL_ACCESS_TOKEN` (from the user's environment; empty default), `GITHUB_READ_ONLY=1`, `GITHUB_TOOLSETS=repos,issues,pull_requests,actions` | Read-only by server flag. guard-dangerous.sh also blocks write-named tools. Use a fine-grained token with read-only repository scopes. The token is the real boundary. |
| `aws-api` | `uvx awslabs.aws-api-mcp-server@latest` | `AWS_REGION` (default `us-east-1`), `SCHOLARCHAIN_AWS_READONLY_PROFILE` mapped to `AWS_API_MCP_PROFILE_NAME` (default `scholarchain-readonly`), `READ_OPERATIONS_ONLY=true`, `FASTMCP_LOG_LEVEL` | Inspection only. The server is set to read-only operations, and guard-dangerous.sh allows only `describe-`, `list-`, `get-`, and similar read operations. The named profile must map to a read-only IAM role (for example ViewOnlyAccess). This server runs AWS calls outside the Bash guard, so the IAM role is the real boundary. |
| `terraform` | `docker run hashicorp/terraform-mcp-server` | none passed (no `TFE_TOKEN`) | Registry and provider documentation only. Without an HCP Terraform token it cannot reach workspaces or runs. |
| `aws-docs` | `uvx awslabs.aws-documentation-mcp-server@latest` | `AWS_DOCUMENTATION_PARTITION=aws`, `FASTMCP_LOG_LEVEL` | Public AWS documentation, read-only, no credentials. |

The `@latest` and untagged images are a supply-chain risk. Pinning versions is a security change that requires human approval. `uvx` (from uv) must be installed for the two awslabs servers.

## 9. Phase evidence

Phase evidence lives in `docs/agent-runs/phase-NN-<short-name>/` and contains:

- `prompt.md`: the human work order, verbatim
- `agent-report.md`: the agent's report in the required field format
- `review-report.md`: the code-reviewer verdict, when applicable
- `test-report.md`: test-engineer's exact pass and fail report, when applicable
- `human-gate.md`: the human decision, written by the human
- `repair-NN.md`: each repair request and its outcome, when applicable
- `commit.txt`: the accepted commit hash

Hidden model reasoning is never stored in evidence. `docs/agent-runs/**` is human-owned evidence, and the orchestrator assembles agent reports there.

## 10. Report contract (all agents)

```
STATUS: COMPLETE | BLOCKED-ON | FAILED
OWNER:
WORK ORDER:
FILES CREATED:
FILES MODIFIED:
COMMANDS RUN:
TEST RESULTS:
VALIDATION RESULTS:
ASSUMPTIONS:
RISKS:
MANUAL ACTION REQUIRED:
BLOCKED-ON OWNER:
BLOCKED-ON EVIDENCE:
NEXT GATE:
```

An agent never reports a command as successful unless it observed the successful output.
