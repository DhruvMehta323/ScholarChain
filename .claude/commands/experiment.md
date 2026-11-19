---
description: Route a reproducible ScholarChain experiment or load test to experiment-engineer, gated on a manifest and immutable raw results.
argument-hint: <experiment-id> <manifest-path>
disable-model-invocation: true
---

Responsible agent: experiment-engineer

Gates: reproducible run (the manifest pins the commit, dataset entry, parameters, seed, environment and bounds), immutable raw results, and no production algorithm changes.

Run-specific inputs:
- Experiment id: $1
- Manifest path under experiments/ or load-tests/: $2

Orchestrator instructions:

1. If either argument is missing, stop and ask the human.
2. Dispatch experiment-engineer in a fresh context with these inputs. Ask it to:
   - verify or complete the manifest;
   - run within the stated bounds;
   - store the raw results without editing them;
   - return the standard report with the reproduction command.
3. Runs in cloud environments, or above the bounded scale, need recorded human approval before dispatch.
4. If the experiment suggests a production change, route it to the owning agent as a separate work order. experiment-engineer must not make it.
5. Store the report in the current phase's evidence folder for human review.
