# ScholarChain: shared project memory

Constitutional file. Owner: factory-engineer. Changes take effect only after human ratification and a fresh session.

## What ScholarChain is

ScholarChain is a Scala 3 scholarly-knowledge pipeline and Graph-RAG service on AWS. It does the following:

- Acquires bibliographic datasets such as DBLP and Crossref.
- Parses and normalizes them with Hadoop MapReduce.
- Resolves duplicate authors and works with entity resolution.
- Builds and analyzes a citation and collaboration graph with Spark.
- Loads results into S3, Neptune, DynamoDB, and S3 Vectors (with optional OpenSearch).
- Answers questions through a retrieval-augmented, evidence-grounded service.

## Module dependency boundaries

The sbt modules live under `modules/<name>`.

- `core` is depended on by every module. `core` depends on no other ScholarChain module.
- `hadoop`, `entity-resolution`, `spark`, `storage`, `rag`, and `services` must not form dependency cycles.
- `services` composes `rag` and `storage` at runtime.
- No module depends on `services`.
- A module that needs another module's behavior consumes a contract defined in `core`. It does not reach into that module's internals.
- A change to these boundaries is a build-structure change. It goes to build-engineer and requires code review.

## Definition of done

`sbt check` is the repository definition of done. build-engineer defines it as a command alias. Work is not complete until `sbt check` has been run and its observed result is reported.

Record every test run with exactly this command, run from the repository root:

```
sbt check; echo "exit=$?" > .claude/.last-test-run
```

The Stop and SubagentStop hooks refuse to end a turn when source under `modules/`, `tests/`, `project/`, or `build.sbt` is newer than that marker. A failing run must be reported as `STATUS: FAILED`.

## Project rules

- **One owner per artifact.** Every artifact class has exactly one creating and owning agent, listed in `docs/agents.md`. If you are asked to edit an artifact you do not own, decline, name the owner, and return `STATUS: BLOCKED-ON`.
- **Feature tests for every behavior change.** No behavior change merges without a test that exercises it. test-engineer writes the test. The implementing agent runs `sbt check`.
- **Provenance.** Every data transformation records its inputs, their versions or checksums, the code version, and its parameters. Every runtime claim (an answer, a metric, a reported result) cites the evidence it rests on. Unsupported claims are not reported.
- **Secrets.** Secrets come only from AWS Secrets Manager at runtime, or from approved local placeholder templates that hold dummy values. Never commit, print, or read real secret values. Reads of `.env*`, `*.pem`, `*.key`, `~/.aws/credentials`, and secret directories are denied.
- **Infrastructure.** All AWS infrastructure is defined in Terraform under `infra/terraform/`. Agents may plan. Only a human applies.
- **No console mutation.** Direct cloud-console or ad-hoc CLI mutation is never an implementation path. If a change is not in Terraform or in an approved script, it did not happen.
- **No hidden reasoning in artifacts.** Hidden model reasoning is never stored in the repository, reports, or evidence. Reports contain observed facts, commands, outputs, and decisions.
- **Evidence.** Never report a command as successful unless you observed its successful output.
- **No self-approval.** No agent approves, merges, commits, or pushes its own work.

## Sessions and memory

Each project phase uses one fresh orchestrator session and normally one commit. Only agent reports, committed contracts, and repository artifacts carry memory across sessions.

Agent roster, ownership, routing, escalation, enforcement, MCP trust boundaries, and the evidence format:

@docs/agents.md
