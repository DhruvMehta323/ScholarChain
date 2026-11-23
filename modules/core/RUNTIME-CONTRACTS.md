# Core runtime contracts (Phase 09)

Owner: domain-engineer. Additive: no earlier record, enum, wire form or ID changes. `QueryOperation`
v1, `VerificationStatus` v1 and `QueryPlan` v1 are frozen and unchanged. Wire `schemaVersion` of all
records below is `1` (the `V2` in a Scala name marks a new record family, as in `AnalyticsRecordsV2`).
Enum wire names are lowercase snake_case.

| Scala name | Source file | Frozen example |
|---|---|---|
| `QueryOperationV2` (11 values), `QueryFilters`, `QueryLimits`, `QueryPlanV2` | `RuntimeEnums.scala`, `RuntimeRecords.scala` | `contracts/query-plan-v2.example.json` |
| `ResolvedResearcherCandidate` | `RuntimeRecords.scala` | `contracts/resolved-researcher-candidate.example.json` |
| `ClaimVerificationStatus`, `ClaimVerification` | `RuntimeEnums.scala`, `RuntimeRecords.scala` | `contracts/claim-verification.example.json` |
| `ModelStage`, `ModelCallOutcome`, `ModelCall` | `RuntimeEnums.scala`, `RuntimeRecords.scala` | `contracts/model-call.example.json` |
| `ModelClient` port, `ModelRequest`, `ModelSegment`, `ModelSegmentKind`, `ModelResponse`, `ModelTokenUsage`, `ModelError`, `ModelResult` | `ModelPorts.scala` | none (not a serialized record) |
| `ModelProvider`, `RuntimeConfigKeys`, `RuntimeSettings`, `RuntimeConfigLoader` | `RuntimeEnums.scala`, `RuntimeConfig.scala` | defaults in `scholarchain-rag.v1.conf` |

Validation rules are in the Scaladoc of each type. Environment variables: `SCHOLARCHAIN_RAG_<KEY>`
(for example `SCHOLARCHAIN_RAG_MODEL_PROVIDER`); override names `scholarchain.rag.<key>`. No secret
is a config value: only the NAME of the Anthropic key variable
(`SCHOLARCHAIN_ANTHROPIC_API_KEY`) is configured.

Retrieved text is never a system segment: `ModelRequest.validate` rejects `Data` segments in `system`.
`ModelCall` stores hashes and counts only, never prompt or output text.
