---
description: Route a ScholarChain incident to incident-responder for read-only diagnosis, an incident record, and only human-approved bounded recovery.
argument-hint: <incident-id> <environment> <symptom summary...>
disable-model-invocation: true
---

Responsible agent: incident-responder

Gates: human escalation for data, security, or destructive actions. Recovery actions require a recorded human approval naming the action, the environment and the bound.

Run-specific inputs:
- Incident id: $1
- Environment: $2
- Full arguments (the symptom summary follows the first two words): $ARGUMENTS

Orchestrator instructions:

1. If the incident id or environment is missing, stop and ask the human.
2. Dispatch incident-responder in a fresh context with these inputs. Ask it to:
   - gather read-only evidence;
   - write `docs/incidents/<incident-id>.md` with the timeline, evidence, suspected cause and fix owner;
   - list the candidate recovery actions, each with its bound and risk;
   - return the standard report.
3. Present the candidate actions to the human. Only after a human records an approval may you re-dispatch incident-responder to execute the approved action, which is usually `scripts/rollback.sh`.
4. Route the permanent fix to the owner that incident-responder names. Data, security and destructive actions stay human-only.
5. Store the report in the current phase's evidence folder.
