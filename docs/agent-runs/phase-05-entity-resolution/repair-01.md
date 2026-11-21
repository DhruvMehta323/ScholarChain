# Repair 01 — core contract defects found by A2

Routed by the orchestrator to domain-engineer (owner of `modules/core/src/main/**`). test-engineer then reruns `ResolutionContractSuite`.

## R1. JSON null accepted for enum fields (moderate)
- Failing test: `ResolutionContractSuite.scala:377`, `MatchDecisionV2: a JSON null enum on the wire is rejected, not read as null`.
- Observed: `Wire.read` of `"outcome":null` returns Right with a null `outcome`.
- Cause: `Wire.enumRw` (`Wire.scala:26-36`) reads a null through `readwriter[String]` without a null check.
- Required: a JSON null for any enum must fail to read. The fix goes in `Wire.enumRw` so that it covers every enum in core. Do not change any wire format.

## R2. `ResolvedMention.unresolved` can build an accepted record (low)
- Failing test: `ResolutionContractSuite.scala:549`.
- Orchestrator decision: the helper documents the "singleton (never merged) record", so it must reject `ResolutionOutcome.Accepted`, with a clear validation error. Direct construction of an accepted record with a valid researcher ID stays allowed.

## Outcome
- R1: fixed in `Wire.enumRw` (`Wire.scala`). The reader now overrides `visitNull` and throws `Abort("null is not a valid <enum>")`. The writer is unchanged, so there is no wire change for valid values.
- R2: fixed in `ResolvedMention.unresolved` (`Resolution.scala`). A new first step rejects `Accepted` with `InvalidField("outcome", ...)`. Direct construction is unchanged.
- No test was edited. domain-engineer judged that neither test is wrong.
- domain-engineer could not write the marker. The guard rejected `cd <root> &&` prefixes, and the shell cwd had been reset to the phase folder, so its marker commands never executed.
- The orchestrator ran the canonical command from the repo root: `[success] elapsed time: 129 s`, core Passed 640, hadoop Passed 114, Failed 0, marker `exit=0`. `ResolutionContractSuite` finished 116 total, 0 failed.
- Open: domain-engineer notes that a null on an `Option[Enum]` field was not tested separately, and it is expected to still read as `None`. Tests that assert only `Left` remain to be tightened if the reviewer asks.
