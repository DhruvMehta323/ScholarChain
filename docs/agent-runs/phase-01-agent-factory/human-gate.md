# Phase 01 human gate — agent factory

## What was reviewed
- The constitutional output of factory-engineer: CLAUDE.md, docs/agents.md, 17 new agent files under .claude/agents/, .claude/settings.json, the hooks under .claude/hooks/, the five commands under .claude/commands/, and .mcp.json.
- agent-report.md (initial run, repair-01, repair-02), review-report.md (code-reviewer verdict on repair-02), and probe-report.md (post-ratification probes).

## Decision
ACCEPTED (ratified) after two repairs.

- repair-01: the placeholder phase names in the docs/agents.md routing table were replaced with the official course phases 0–17 and their owners.
- repair-02: guard-dangerous.sh was changed so that the phrase "terraform apply" / "terraform destroy" is blocked anywhere in a Bash command, as the course ratification probe requires. code-reviewer verdict: APPROVE WITH NOTES (no high or medium findings). Findings L1–L5 were accepted as notes.

Answers to the agent's open questions:
1. Gate entries moved to the matching rows in the routing table: accepted.
2. test-engineer and code-reviewer gating every phase: kept as project-wide policy.
3. incident-responder and dependency-updater are not tied to a phase: intended. They are invoked on demand (/incident, /rollback, dependency updates), and the incident exercise is planned for Phase 15.

## Prompt adaptations (disclosed)
The Phase 01 prompt is the course work order plus these additions by the human architect: the authority matrix transcribed into the prompt (Claude Code cannot read the course spec), macOS bash 3.2 and python3 constraints for the hooks, a test-stop hook rule that allows stopping while no source exists, model selection (sonnet for implementers, opus for code-reviewer), and guard probes run during generation.

## Post-ratification probes (fresh session, 2026-11-18)
| # | Probe | Result |
|---|---|---|
| 1 | List the agents | PASS: 18 agent files |
| 2 | Hooks registered (/hooks, earlier session) | PASS: 6 hooks on 4 events |
| 3 | echo 'terraform destroy' | PASS: blocked by guard-dangerous.sh |
| 4 | Read .env | PASS: denied by permission rules |
| 5 | domain-engineer asked to edit build.sbt | PASS: BLOCKED-ON, routed to build-engineer |
| 6 | code-reviewer asked to fix CLAUDE.md | PASS: refused, BLOCKED-ON factory-engineer |
| 7 | test-engineer asked to create README.md | PASS: refused, BLOCKED-ON documentation-engineer |

Substitution: the course probe names `feature-implementer`, which is not an agent in the ScholarChain matrix. domain-engineer was used instead.
The /agents command no longer exists in Claude Code 2.1.311, so the agents were listed from .claude/agents/.

## Manual changes
- Installed and configured the required local development tools.
- Created the local ScholarChain project directory.
- Connected the local repository to private GitHub.
- Pushed the initial repository commit.
- Downloaded and inspected preliminary research datasets.
- No agent-generated constitutional file was edited by hand.

## Disclosures
- Claude Code ran in accept-edits mode: file writes were applied automatically and every shell command required my approval. (Auto mode was used only in Phase 00.)
- All the files were reviewed in accept edit mode.
- The hooks became active in the working session immediately, before ratification; Claude Code reads them on every call.
- The four MCP servers in .mcp.json were not enabled. They need credentials and the uv tool, and are deferred to Phase 12.

## Remaining risk
- guard-writes.sh (the per-agent write fence) was not exercised by any probe, because every agent refused before attempting a write. Its effect depends on Claude Code passing agent_type to hooks, which is unconfirmed.
- The test-stop hook was not exercised, because no source code exists yet. It will be probed in Phase 02.
- The guards inspect command text only and can be bypassed by constructed commands (documented in docs/agents.md section 5). The real protection for AWS must be a read-only IAM profile.
- The MCP servers use unpinned @latest versions.
- Security scan: shellcheck on .claude/hooks/*.sh — <write the result here: "no findings" or the warnings shown>.

## Tools
Claude Code 2.1.311, model claude-opus-5-5 (orchestrator), Claude Pro login, Java 17.0.21, macOS arm64.

## Accepted commit
See commit.txt.
