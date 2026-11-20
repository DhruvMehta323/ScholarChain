# Phase 0 human gate — seed agent

Reviewed: .claude/agents/factory-engineer.md, read line by line against the Phase 0 work order and gate in the course spec.

Decision: ACCEPTED after one repair (see repair-01.md).

Checks:
- Only one file was created (.claude/agents/factory-engineer.md).
- The tools field is explicit: Read, Grep, Glob, Write, Edit, Bash.
- Ownership is limited to the seven control-plane artifacts.
- The agent cannot self-ratify, commit or push.
- All seven floor invariants are present.

Repair: the original report contract did not use the course-required report fields. repair-01 replaced it with the STATUS ... NEXT GATE fields and added a BLOCKED-ON routing rule and an observed-output evidence rule.

Manual changes: none. The file is entirely agent-generated.

Disclosure: Claude Code ran in accept-edits mode: file writes were applied automatically and every shell command required my approval. 

Remaining risk: the guard and hook behaviour described here is only a specification until Phase 1 creates the actual hooks and the probes test them.

Tools: Claude Code 2.1.311, model claude-opus-5-5, Claude Pro login, Java 17.0.21.

Accepted commit: see commit.txt