# Phase 06 repair 01

Source: the human's decisions on review pass 1, received in session. The orchestrator records them here verbatim in substance. The human writes `human-gate.md`.

## Human decisions

- **M1.** Different DBLP homonym suffixes in one cluster are an implicit cannot-link under the M5 rule. That cluster falls back to base identities and is counted as `cannot_link_blocked`.
- **M2.** Confirmed. A REJECTED decision inside one DBLP base identity is ignored, counted and listed. Rule 4.1.1 wins.
- **M3/M4.** domain-engineer adds `CoauthorEdgeV2` and `ResearcherMetricsV2`; the frozen v1 records stay untouched. The v2 records carry:
  - window start and end;
  - `edgeId`;
  - an evidence pointer;
  - named bridge measures (`crossCommunityDegree`, `participationCoefficient`, `communitiesTouched`) in place of the single `bridgeScore`.

  They include validation and frozen JSON examples. spark-engineer exports exactly these schemas. test-engineer adds a contract test that every exported edge and metric row validates as the v2 record, plus core round-trip tests.
- **Assumption (a).** W_r stays the spec's sum of `jointPublicationCount`. A tie-strength weight may be added as an extra column only.
- **Assumption (b).** The canonical researcher ID is the DBLP base ID (its stable core ID).
  - A merged cluster uses its lexicographically smallest member key.
  - ORCID is a property plus a `RESOLVES_TO` edge.
  - Update `SPARK-CONTRACTS.md`.
- **LOW notes.** All 11 (L1-L11) are deferred and listed in `agent-report.md`.

## Plan (one round)

| Order | Owner | Scope |
|---|---|---|
| R1 | domain-engineer | `CoauthorEdgeV2`, `ResearcherMetricsV2` (validation, frozen JSON examples); ID rule support in `Ids` (additive only) |
| R2 | spark-engineer | M1, M2, ID rule (b), ORCID property and `RESOLVES_TO` edge, v2 export, `SPARK-CONTRACTS.md`; reruns sparkS1/sparkS2 with label `baa31d5+phase06r1` and compares; identity counts before and after |
| R3 | test-engineer | Tests for M1, M2, the v2 contracts (core round-trip, every exported row validates) and the ID rule |
| CHECK | orchestrator | `sbt check; echo "exit=$?" > .claude/.last-test-run` from the repository root |
| D | code-reviewer | Rereads only the repair |

STOP if CHECK fails, if the roots differ, or if D returns REQUEST_CHANGES. Otherwise write the final checkpoint and STOP for the human gate. Nothing is staged or committed.

## Outcome

(appended below as each step completes)

### R1 domain-engineer (STATUS: COMPLETE)

- Created `modules/core/src/main/scala/scholarchain/core/AnalyticsRecordsV2.scala`, with `CoauthorEdgeV2`, `ResearcherMetricsV2` and `GraphEdgeTypes.ResolvesTo = "RESOLVES_TO"`.
- Created the frozen examples `modules/core/src/main/resources/contracts/coauthor-edge-v2.example.json` and `researcher-metrics-v2.example.json`.
- Added `Ids.researcherIdForClusterV2(dblpPersonKeys)`. It returns `researcherIdDblpPerson(min key)` (code-point order), and `Left` for an empty or blank key.
- `Identifiers.scala` is additive (+89/−0). `AnalyticsRecords.scala` changed in doc comments only. The orchestrator observed an empty `git diff` for `Records.scala` and `Enums.scala`.
- `CoauthorEdgeV2` holds:
  - flat `researcherA/B` and `windowStart/End`;
  - `jointPublicationCount` and collaboration years;
  - `representativeWorkIds`, `supportingWorksDigest` and `supportingWorksTruncated`;
  - an evidence pointer (`evidenceTable = "coauthor-edge-works"`, `evidenceKey = edgeId`, `evidencePartition = window label`).
- `ResearcherMetricsV2` holds named fields:
  - P_r, C_r, T_r, A_r, M_r, W_r and `maxTeamWork*`;
  - `communityId`, `componentId` and `coreness?`;
  - `crossCommunityDegree`, `participationCoefficient` and `communitiesTouched`;
  - an optional `tieStrengthWeightedDegree`.
- schemaVersion stays 1.
- Agent-observed results:
  - `core/compile` and `core/Test/compile` succeeded;
  - `core/testOnly *ContractSuite` passed 690, failed 0;
  - the examples were read and validated in a scratch run.
- The agent wrote one scratch file to `/tmp/x` and has deleted it.
- Orchestrator CHECK after R1, canonical command: the testFull totals were core 690, spark 160, ER 293 and hadoop 192, so 1,335 passed and 0 failed. The marker reads `exit=0`. No new tests yet (R3).

### R2 spark-engineer (STATUS: COMPLETE; 14 old-behavior tests fail until R3)

**Changed files**
- New: `V2Export.scala`.
- Modified: Identity, Udfs, Schemas, Pipeline, Enrichment, Invariants, Report and LoadManifests (all `.scala`), and `SPARK-CONTRACTS.md` §1, §3, §4, §8 and §10.
- The agent reports touching no test files.

**M1 (homonyms)**
- A key's stem is the key with one trailing ` NNNN` removed (regex `^.+ [0-9]{4}$`). A key without that suffix is its own stem.
- A component is blocked when two or more different DBLP base identities in it share a stem. The new reason is `distinct_dblp_homonyms`, combinable with the others. It has its own counter.

**M2 (within-base rejections)**
- A rejected or conflict decision between mentions of the same base identity is ignored and counted in `withinBaseRejectionsIgnored`.
- These pairs are listed in the new table `within-base-rejections-ignored`, with a 20-row sample in the summary.
- The M5 invariant now excludes same-base pairs.

**Researcher IDs (decision b)**
- The canonical ID is `researcherIdForClusterV2(dblpKeys)`. ORCID is never the canonical ID.
- `researchers` gains `orcid` (one value or null) and `orcids` (all values).
- New table `resolves-to`, with one row per (researcher, ORCID) of type `RESOLVES_TO`.
- External singletons keep `researcherIdUnresolved`.

**v2 export**
- `coauthor-edges` and `researcher-metrics` now use the `CoauthorEdgeV2` and `ResearcherMetricsV2` field names.
- New table `coauthor-edge-works`, with the full edge-to-work evidence.
- 29 tables in total (was 26). 40 invariants, including executor-side `Wire.read` validation of every exported edge and metric row.
- `tieStrengthWeightedDegree` is always null; W_r is unchanged.

**Deviations flagged by the agent**
- The row `provenance` is a compact JSON STRING column, not a struct. A struct holding a map breaks `exceptAll` in Compare and the determinism tests. Validation parses the string before `Wire.read`.
- `resolves-to` is not the core `GraphEdge` record, because Phase 06 produces no `EvidenceItem`s.

**Real runs (`baa31d5+phase06r1`)**
- sparkS1r1 and sparkS2r1 each exited 0 with 40 invariants OK and `OK 29 tables written` (agent-observed).
- The orchestrator re-ran `Compare sparkS1r1 sparkS2r1`: 65 EQUAL lines, and the final line was **`ALL TABLES EQUAL`**.

**Identity counts (agent-read from `run-summary.json`)**

| metric | before (sparkS1) | after (sparkS1r1) |
|---|---|---|
| mentions / DBLP mentions | 4711 / 3928 | 4711 / 3928 |
| DBLP base identities / clusters | 3893 / 3907 | 3893 / 3907 |
| merges of 2+ DBLP persons | 0 | 0 |
| cannot_link_blocked (rejected or conflict / two ORCIDs / homonyms) | 0 (no breakdown) | 0 (0 / 0 / 0) |
| within-base rejections ignored | not reported | 0 |
| unresolved external singletons | 14 | 14 |
| canonical ORCID IDs | 1423 | 0 |
| ORCID property / RESOLVES_TO edges | n/a | 1423 / 1423 |
| ORCIDs shared by 2+ clusters | collisions 0 | 2 ORCIDs, 4 clusters (involving external singletons) |
| nodes / all-time edges / components | 3893 / 8416 / 969 | 3893 / 8416 / 969 |
| all-time communities | 983 | 986 (label propagation depends on the changed IDs) |
| coauthor-edge-works rows | n/a | 15111 (= sum of jointPublicationCount) |

**Tests**
- Agent-observed: 14 existing tests now fail because they pin old behavior: table counts 26→29, ORCID IDs, merged-ID expectations, the old M2 invariant expectation, new observation keys, and row provenance differing between the fixtures. They are routed to R3.
- No intermediate CHECK was run, because it is known to fail until R3.

### R3 test-engineer (STATUS: COMPLETE)

**New suites**
- `core/.../AnalyticsRecordsV2ContractSuite.scala` (58 tests): v2 records, frozen examples, `GraphEdgeTypes`, v1 pins.
- `core/.../ResearcherIdClusterV2ContractSuite.scala` (12 tests).
- `spark/.../V2RepairSuite.scala` (23 tests). It runs an extended fixture covering:
  - the ID rule, ORCID and `RESOLVES_TO`, M1 and M2;
  - the v2 export, where every row validates through `Wire.read`, including a truncated 7-work edge with its full evidence rows and digest;
  - three negative controls;
  - the metrics join, manifests and determinism.

**Modified suites**
- ExportSuite, SchemasContractSuite, FixtureMetricsSuite, IdentitySuite (+21 M1, M2, ID and ORCID tests), InputsRunSuite and InvariantsSuite (the M2 test was split into "ignored" plus a negative control for a real violation).

**The 14 superseded tests now expect:**
- 29 tables and 15 windowed tables;
- `RESOLVES_TO` as an edge label;
- the v2 metric columns, with no `bridgeScore`;
- DBLP-based IDs;
- ORCID as a property plus `RESOLVES_TO`;
- table comparison without the provenance column, with provenance asserted separately;
- the new observation keys;
- M2 pairs ignored.

**Agent-observed totals**
- `sparkAnalytics/testOnly *`: 205 passed, 0 failed. It was 160 total with 14 failed before R3.
- `core/testOnly *`: 760 passed, 0 failed.

**Defects and notes**
- No production defects reported.
- External members that fall back after a homonym block carry `cannot_link_fallback`, so they are not counted in `unresolvedSingletons`.
- The metrics example writes `4`, not `4.0`. It is compared as a JSON value rather than byte for byte.

### Orchestrator CHECK after R3

- I ran the canonical command from the repository root. It printed `[success] elapsed time: 717 s`.
- Totals per module: core 760, entity resolution 293, spark 205 and hadoop 192. That is 1,450 passed and 0 failed, +115 since before the repair (1,335).
- The marker reads `exit=0`.

### D re-review: REQUEST_CHANGES (R-M1). See review-report.md, pass 2.

STOP, as the human's instruction requires on REQUEST_CHANGES. This was the one authorised repair round, so no further round was opened.
