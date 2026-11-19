# Phase 01 — Agent report (factory-engineer)

Source: final output of the Claude Code orchestrator session for Phase 01, copied from the terminal.
Note: the terminal cut off some lines when they were copied. In this file the box-drawing tables were converted to plain rows, and a few cut-off words were filled in from context. The content is otherwise unchanged. The complete original wording is in the Claude Code session transcript (`/export`).

---

## Part 1 — Initial Phase 01 run

```text
I'll hand this to the factory-engineer agent with the full authority matrix and work order.
⏺ factory-engineer(Build ScholarChain agent system)
Agent "Build ScholarChain agent system" finished · 47m

Independent check found a discrepancy: all four probes returned exit 0, not 2. Investigating.
The guard does exit 2. My loop was wrong: the $(basename …) call ran before $? was read and reset it. Re-running with the exit code captured correctly.

Phase 01 is built and ready for your ratification, except docs/agent-runs/README.md. The agent declined that file because your matrix makes docs/agent-runs/** human-owned. One thing first: the new hooks are already active in this session, before you've ratified anything.

The hooks are already running. Claude Code picked up the configuration straight away. The guard blocked three of the agent's own commands and one of mine. If you want the old behaviour until you ratify, move that file aside.

What was created
32 new files; no tracked file changed (git diff --stat is empty). factory-engineer.md, datasets/ and the phase-00 and phase-01 evidence folders were not touched.
The full diff is 3,553 lines (saved in the session scratchpad as phase01-complete.patch).

File(s) | Why the factory-engineer may create it
- CLAUDE.md (imports @docs/agents.md) | matrix row, item 1
- docs/agents.md | matrix row, item 2: ownership table for 18 agents, routing for phases 0–17, escalation rules, trust boundaries for the MCP servers, evidence format
- .claude/agents/*.md (17 new) | .claude/**, item 3. code-reviewer uses opus with Read, Grep, Glob only; deploy-engineer has no Edit; the others use sonnet
- .claude/settings.json | .claude/**, item 4: allow, ask and deny lists (26 deny rules), hook wiring
- .claude/hooks/guard-dangerous.sh | Item 4: checks Bash and MCP calls by reading the command text only, never running it
- .claude/hooks/guard-writes.sh | Item 4's block on constitutional files, plus a write fence for each agent (an interpretation, see below)
- .claude/hooks/format-scala.sh, verify-tests-ran.sh | Item 4
- .claude/hooks/validate-agent-files.sh and .py | Item 4 (runnable validator, not wired as a hook)
- .mcp.json | Item 5: four servers (github with GITHUB_READ_ONLY=1, aws-api with READ_OPERATIONS_ONLY=true, terraform, aws-docs), using environment-variable names only
- .claude/commands/{deploy,rollback,incident,run-pipeline,experiment}.md | Item 6: each names its agent and gates and takes run-specific details as arguments

What I checked myself
- Validator: validate-agent-files.sh exits 0 with FAILURES: 0. It checks:
  - frontmatter for all 18 agents, with unique names
  - the reviewer tool limits
  - all five hook wirings
  - the 26 deny rules, with no rule both allowed and denied
  - the four MCP servers
- Syntax and permissions: both JSON files pass json.tool. All five .sh hooks pass bash -n and are executable.
- Guard probes: I piped JSON files in; none of the commands were executed.
  terraform destroy                        ->  exit=2
  git push --force origin main             ->  exit=2
  aws s3 rb s3://scholarchain-raw --force  ->  exit=2
  ls                                       ->  exit=0
  My first attempt showed all four as exit 0. That was a bug in my loop, not the guard; captured properly, the results are above.
- The agent's own audit:
  - Routing: no duplicate names, and the closest pair of descriptions overlaps 0.24 against a 0.50 limit. Every matrix agent has a definition and every hook and command points to something that exists.
  - Ownership: no path has two owners.
  - Probes and hooks: 100 of 100 guard probes matched. grep -rn "terraform apply" docs/ and terraform plan pass. The test-gate hook allows stopping while no source exists and blocks once source exists without a test run.
  - Not run: sbt check, because there is no build yet.

Risks you should know about
1. The guards can be bypassed. They only read command text, so a command hidden inside Python, encoded payloads or SDK calls gets past them. The agent itself wrote into .claude/hooks/ through a Python script without being stopped. For AWS, the real safeguard has to be a read-only IAM role, not the guard.
2. The per-agent write fences may not work. They rely on Claude Code sending an agent_type field to hooks, which nobody has confirmed yet. If the field is missing, the fences do nothing. Setting SCHOLARCHAIN_FACTORY_SESSION=1 would unlock constitutional edits for every agent in the session. Probe (f) below tests this.
3. Things you may want to correct:
   - The names for phases 0–17 are placeholders; the agent didn't have the course phase list. (Fixed in repair-01, Part 2.)
   - The agent made several ownership calls, including the dependency ledger and Hadoop integration tests under src/it/**. They are listed in docs/agents.md §1.1.
   - It added extra blocks beyond what you asked for, such as s3 sync --delete and SQS purge-queue.
4. Smaller points:
   - The MCP servers use unpinned @latest versions.
   - The ownership map exists in both guard-writes.sh and docs/agents.md, and nothing checks that they match.
   - The brief said jq isn't installed, but it is; the hooks use python3 anyway.

Decisions for you
1. Ratify the work (as two parts, A: settings and hooks together), then restart the session.
2. docs/agent-runs/README.md: write it yourself, or tell the orchestrator to write it.
3. .gitignore: an entry for .claude/.last-test-run is needed; this belongs to build-engineer.
4. Human-only steps: write human-gate.md and commit.txt. For the MCP servers, install uv, create a read-only AWS profile scholarchain-readonly, and use a fine-grained read-only GitHub token.

Probes to run after ratification, in a fresh session
- a. /hooks should show six registrations.
- b. .claude/hooks/validate-agent-files.sh should end with FAILURES: 0.
- c. Re-run the four piped probes above. Writing the dangerous text into a command gets blocked.
- d. Ask me to run these harmless targets; each should show a "BLOCKED by .claude/hooks/guard-dangerous.sh" message:
  - cd "$(mktemp -d)" && terraform destroy
  - git push --force origin probe-nonexistent-ref
  - aws s3 rb s3://scholarchain-probe-nonexistent-7f3k
- e. Ask me to write to CLAUDE.md; it should be blocked.
- f. Send the test-engineer to create README.md. If it refuses and names documentation-engineer as owner, the fences work. If the write succeeds, delete the file and treat the fences as inactive.
- g. Turns should end normally while there is no source.

I've stopped here for your ratification.
```

---

## Part 2 — Repair-01 (official phase names)

```text
Agent "Repair phase names in routing table" finished · 4m 41s

Repair-01 is done: all 18 rows of the routing table in docs/agents.md now use your official phase names and owners. I checked the result independently, and the agent's report is below in the required format.

What I checked: diff against the pre-edit copy shows changes only to line 48 (the sentence introducing the table) and the 18 table rows on lines 52–69. No other file changed. validate-agent-files.sh exits 0 with FAILURES: 0.

Decisions for you:
1. Gate column (ASSUMPTIONS 4): the agent moved each existing Gate entry onto the row for the same work, so the Terraform row doesn't end up reading "smoke tests, rollback gate". Please check that this is acceptable under "change nothing else".
2. Test and review gates (RISKS 1): lines 48 and 76 still say test-engineer and code-reviewer gate every phase. Your list names them only for some phases. Keep both as project-wide policy, or remove them?
3. Unused agents (RISKS 4): incident-responder and dependency-updater are in no phase. Is that intended?

STATUS: COMPLETE (proposed change only; not ratified, not in force)

OWNER: factory-engineer

WORK ORDER: Phase 01 repair-01: replace the placeholder phase names in the routing table, and in any other owned file that repeats them, with the official phases 0–17 and their owners. Run validate-agent-files.sh.

FILES CREATED: none in the repo. A pre-edit copy of docs/agents.md was saved in the session scratchpad (repair01-before/docs/agents.md).

FILES MODIFIED: docs/agents.md only.
- Line 48: the sentence calling the names "provisional" now refers to the official course phases.
- Lines 52–69: the 18 rows of the table.
- A search of CLAUDE.md, .mcp.json and .claude/** found no placeholder names, only general wording like "Phase 0/1" and "current phase".

Table rows after the change (Phase | Work | Primary | Supporting | Gate):
00 | Plant the seed agent | human orchestrator | none | Human constitutional ratification
01 | Factory builds the agent system | factory-engineer | none | Ratification, fresh session, probes
02 | Build and repository skeleton | build-engineer | code-reviewer | sbt check, code review
03 | Contracts, configuration, dataset manifests | dataset-engineer, domain-engineer | code-reviewer | Contract tests, sbt check, code review; dataset validation, manifest review
04 | Raw ingestion and Hadoop MapReduce | hadoop-engineer | test-engineer, code-reviewer | Local sample run, deterministic output
05 | Entity resolution | entity-resolution-engineer | code-reviewer | Gold-set evaluation, match-policy review
06 | Temporal coauthorship graph and Spark analytics | spark-engineer | code-reviewer | Spark tests, sample run, invariants
07 | Storage adapters and loading | storage-engineer | code-reviewer | Contract tests, bounded load test
08 | Embeddings and S3 Vectors | storage-engineer, rag-engineer | none | Contract/load tests; retrieval and groundedness evaluation
09 | Runtime collaboration-analysis agents | rag-engineer | code-reviewer | Retrieval and groundedness evaluation
10 | REST microservices | service-engineer | none | API tests, sbt check
11 | Adversarial testing and full review | test-engineer, code-reviewer | none | Exact pass/fail report; read-only verdict
12 | AWS infrastructure in Terraform | infra-engineer | code-reviewer | fmt, validate, plan, syntax, security scan, human approval
13 | Build, deployment, experiment automation | infra-engineer | none | Same as 12
14 | Apply and deploy through human approval | deploy-engineer | none | Approved plan, clean tree, smoke tests, rollback gate
15 | Data pipeline and elasticity experiments | experiment-engineer | none | Reproducible run, immutable raw results
16 | Evaluation | experiment-engineer, documentation-engineer | none | Reproducible run; link check, command replay, evidence review
17 | Documentation, final review, release | documentation-engineer | code-reviewer, test-engineer | Link check, command replay, evidence review; read-only verdict; pass/fail report

COMMANDS RUN:
- grep over the owned files for the placeholder names.
- cp of docs/agents.md before editing; the two shasums matched.
- One Edit of docs/agents.md.
- validate-agent-files.sh: exit 0.
- diff -u: exit 1, meaning the files differ, as expected.
- git status and git diff --stat: no tracked changes.

TEST RESULTS: none run. This is a docs-only change and no source code exists yet.

VALIDATION RESULTS: validate-agent-files.sh exits 0 with FAILURES: 0. The agent got this result and the orchestrator got it again independently.
- All 18 agents pass, names are unique, and the reviewer tool fences hold.
- Description overlap is at most 0.24.
- Commands, hook wiring, the 26 deny rules and the 4 MCP servers pass.
- No hook, settings or permission file was touched, and no agent's privileges changed.

ASSUMPTIONS:
1. Agents before the semicolon go in "Primary"; agents after it go in "Supporting".
2. "human orchestrator" goes in Primary for phase 00, even though it is not an agent.
3. Phases are numbered two-digit (00–17), matching the evidence folder naming.
4. The Gate column holds acceptance criteria, not agent names, so the list doesn't set it. The agent invented no new criteria; it reused existing wording in two ways:
   - Where an old row described the same work, its Gate entry moved with that work.
   - Where no old row matched (08, 11, 13, 16, and others), it copied each agent's primary gate from the ownership table in section 1.
   - The Gate entries of the old rows for pipeline runs and maintenance were dropped.
5. Notes such as (/run-pipeline) were removed from the owner columns. The command-to-agent mapping is still in section 6.
6. Line 48 was changed because "provisional" is no longer accurate.

RISKS:
1. Lines 48 and 76 still say test-engineer and code-reviewer gate every phase. The list names test-engineer only for phase 04 and gives no gate agent for 08, 10, 13, 14, 15 and 16. Both sentences were left in place for the human to decide.
2. Phase 14, "Apply and deploy": Terraform apply stays human-only and deploy-engineer only runs deployment. No permission was widened.
3. Phase 13, "experiment automation": experiments/** stays owned by experiment-engineer, not infra-engineer.
4. incident-responder and dependency-updater appear in no phase. They can still be reached through /incident and /rollback and through one-off work orders.
5. The phase list exists only as a work order, and the whole Phase 01 system is still unratified.

MANUAL ACTION REQUIRED:
- Review and ratify the diff, especially the Gate column.
- Decide on lines 48 and 76, the roles of the two unused agents, and the phase 13 boundary.
- Ratify the Phase 01 system as a whole, then restart the session.
- Nothing has been committed.

BLOCKED-ON OWNER: n/a

BLOCKED-ON EVIDENCE: n/a

NEXT GATE: ratification of this diff together with the Phase 01 system, then a fresh session and the post-ratification probes.
```
