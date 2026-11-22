# Phase 06 review report

## Review pass 1 (code-reviewer, read-only; condensed by the orchestrator)

Verdict: **APPROVE WITH NOTES**. There is no HIGH finding, and the reviewer found no over-merge under §4.1 as written. D ran no commands (Read, Grep and Glob only), so it could not read the Parquet or gzipped tables. A static count of `test(` declarations matches the claimed +210 (spark 160, core 50). Whether the tests pass was not verifiable by D; the orchestrator's canonical run is the evidence.

HIGH: none.

MEDIUM
- **M1 (NEEDS HUMAN DECISION; changes §4.1.2/§4.1.3).** Two different DBLP person keys can merge transitively through an external mention (`Identity.scala:138-144, 180-182`; `SPARK-CONTRACTS.md:59-67`).
  - Input: DBLP "Zhen Wang 0008" ~ crossref "Zhen Wang" ~ DBLP "Zhen Wang 0009" (both ACCEPTED), with no direct decision between the two DBLP keys. Result: one merged cluster, although DBLP's suffixes say they are different people.
  - Real data: 0 such merges (`mergedClustersWithTwoOrMoreDblpPersons = 0`).
  - Suggested rule: distinct DBLP keys with the same base name and different homonym suffixes are an implicit cannot-link.
  - Owner: spark-engineer, after the human decides.
- **M2 (fix needs no human decision; human to confirm the policy).** The identity rule drops decisions between two mentions of the same base identity (§4.1.1; `Identity.scala:134-136`, pinned by `IdentitySuite.scala:169-175`). The M5 invariant, however, fails the run on exactly that input (`Invariants.scala:85-99, 220-224`, pinned by `InvariantsSuite.scala:89-106`).
  - Input: two DBLP "Wei Wang" mentions with a REJECTED decision between them. Run exits 1.
  - Real rootI: 0 such decisions.
  - Fix: exclude same-base pairs from the invariant and count them as an observation.
  - Owners: spark-engineer, test-engineer.
- **M3 (NEEDS HUMAN DECISION; frozen wire format).** The Spark edge keeps 5 representative work IDs plus a digest (`Graph.scala:114-116`). The frozen Phase 03 `CoauthorEdge` requires `workCount == supportingWorkIds.size` (`Records.scala:397-398`), so an edge with more than 5 joint works cannot be expressed as the frozen record, and no table stores the full list.
  - Real all-time: no edge exceeds 5 joint works. The fixture test passes only because its maximum weight is 3.
  - Options: a full edge-to-work table, or a `CoauthorEdge` v2.
  - Owners: domain-engineer, spark-engineer, test-engineer.
- **M4 (NEEDS HUMAN DECISION; frozen enum).** The frozen `ResearcherMetrics` takes `CollaborationMetric` values only (`Enums.scala:34-41`). P_r, T_r, A_r, M_r and `maxTeamWorkIds` have no wire name, so the Spark `researcher-metrics` table is not expressible as the core record. Phases 07 and 09 will hit this. Owner: domain-engineer.

LOW
- **L1.** Oversize teams: Hadoop emits no pairs above `pair-expansion-hard-cap` but still writes authorship rows, so C_r and W_r would be understated. This is not documented in modules/spark. Owner: spark-engineer.
- **L2.** The invariant `identity_orcid_collisions_resolved` is hard-coded `true` (`Invariants.scala:225-229`), so "28 OK" includes one check that cannot fail. Owner: spark-engineer.
- **L3.** `DriverCollect.rows` silently applies `limit` while its doc says it refuses. `Report.scala` caps windows, paths and coreness rows in `run-summary.json` without a marker. The data tables are unaffected. Owner: spark-engineer.
- **L4.** `Pipeline.scala:44-46` records `min(policyVersion)`, so a mixed-policy decisions file is mislabelled. Owner: spark-engineer.
- **L5.** External singletons with an ORCID keep the unresolved ID (`Identity.scala:225-229`). This is documented, harmless today, and part of open assumption (b).
- **L6.** The alias map is broadcast (`Identity.scala:78`), which is risky on full DBLP. Owner: spark-engineer.
- **L7.** Defaults that matter on EMR: a local relative `checkpoint-dir`, and `code-version = "dev"`, which could reuse run IDs across code changes. Owners: domain-engineer, spark-engineer.
- **L8.** The path-pair sample and the average-path sources share seed 441 and the same hash, so the pair sample is a subset of the source set. Not documented. Owner: spark-engineer.
- **L9.** Test gaps:
  - no Spark-side test that an unmerged DBLP person with one ORCID gets `researcher:orcid:`;
  - no test of the single-base, two-ORCID fallback;
  - the "no invented numbers" check uses one pooled number set;
  - determinism is tested only with the same partitioning.

  Owner: test-engineer.
- **L10.** Two claims in the orchestrator's condensed report were not backed by output:
  - S1 = S2 for summed stage time (S1 46,471 ms, S2 46,422 ms);
  - the stated reason for 0 singletons.

  The orchestrator verified both against the files and corrected `agent-report.md`.
- **L11.** Component and community IDs embed the window (`component:<window>:<min id>`), while the core doc recommends `component:<min id>`. Both are valid; the docs should agree. Owner: domain-engineer.

Checked and correct (D):
- M5 as implemented: endpoints are mapped to base identities first; rejected or conflict pairs, or two validated ORCIDs, block the whole component, which is listed with its reason; UNRESOLVED and CONFLICT never link; `ResolvedMention` is never read; a non-converged identity loop fails the run.
- "A rejection against any mention of a person counts against the person" is correct under §4.1.1. The cost is recall only.
- §4.1 points 1-5.
- Driver collection: the only `.collect()` is in `DriverCollect.scala:35`, and every caller is aggregate or bounded.
- Every loop is capped and checkpointed, and SPARK-CONTRACTS.md §7 is documented.
- D recomputed all fixture hand arithmetic: P, C, T, A, M and W per window; edge counts; components; coreness; path estimates; Ada→Ivy; 23 pairs. All values match the tests.
- Determinism mechanisms, config sourcing, the bridge and community definitions with limitations, explicit windows on every windowed table.
- The real-run claims match `run-summary.json` and the decisions summary.
- Ownership, module boundaries, secrets.

Opinions for the human gate (not decisions):
- **(a) Metric definitions.** They are standard and documented. T_r counts r itself. With complete pair candidates, **W_r = T_r − P_r exactly** (fixture Ada 7−2=5; real Pieciak 44−2=42), so W_r as defined adds no information, and the `weighted_degree_sum` invariant holds by construction. If a tie-strength weight is wanted, consider a Newman-style Σ 1/(n_w−1). Otherwise keep W_r and document the identity.
- **(b) An unmerged DBLP person with one ORCID gets `researcher:orcid:`.** This matches the literal §4.1.4. Risks: IDs change between snapshots (sc1 ↔ ORCID), which changes Neptune and DynamoDB keys; the rule is non-local (an unrelated collision changes the ID); external singletons are treated differently (L5). The alternative is the base ID as the primary key with the ORCID as an attribute, which changes §4.1.4.

Not verified by D:
- sbt results and the marker; the orchestrator's Compare output;
- the seed-7 negative-control run (spark-engineer reports deleting that scratch root);
- Parquet contents; whether pipeline sources changed after S1/S2;
- that `Identifiers.scala` is additive only (no diff tool);
- several suites beyond names and samples.

Orchestrator note on M3 and M4: both concern how Phase 06 output maps onto frozen Phase 03 records. Per §8, a finding that would change a frozen wire format is a human decision, and none was routed. The verdict is APPROVE WITH NOTES, so per §8 no repair round was opened. The M2, L-item and test-gap fixes wait for the human's routing decision.

## Review pass 2: repair 01 only (code-reviewer, read-only; condensed by the orchestrator)

Verdict: **REQUEST_CHANGES**, narrowly on R-M1. The reviewer rates everything else APPROVE WITH NOTES. D ran no commands. Static `test(` counts match the claims: V2 contract 58, cluster-ID 12, V2Repair 23, IdentitySuite 40.

MEDIUM
- **R-M1. A homonym pair can still merge through an HJ1 alias group.** The homonym check stems only `dblpKey = min(baseName)` (`Identity.scala:153`, used at :227). The invariant uses the same key (`Invariants.scala:144-151`).
  - Input: an alias record `["Zhen Wang 0008", "Z. Wang"]`, the DBLP mention "Zhen Wang 0009", and an ACCEPTED link between them. The stems are "Z. Wang" and "Zhen Wang", so nothing is blocked and 0008 and 0009 end up in one researcher.
  - `IdentitySuite.scala:516-525` asserts that exactly this case is NOT blocked. The orchestrator confirmed this by reading the code and the test.
  - Real data: no impact, because the 1K selection has no alias records.
  - Fix: compute stems over every DBLP string of a base identity (mention raw names plus alias names), and block when one stem belongs to two or more base identities. Owners: spark-engineer, then test-engineer to invert the test and add an invariant negative control.
  - Alternative: the human ratifies the documented reading in `SPARK-CONTRACTS.md` §3.1, that the key is the smallest alias string. **Human decision.**
- **R-V1. RESOLVES_TO targets `researcher:orcid:*` have no vertex set** in `neptune.json` (`LoadManifests.scala:77-103`). A Neptune bulk load may reject these edges; D did not verify the loader behaviour. **Human decision**: an ORCID vertex table now (spark-engineer), or in Phase 07 (storage-engineer).

Deviation 1: row `provenance` is a JSON string column, not a struct. D finds this technically acceptable, with the reason confirmed (a map inside a struct breaks `exceptAll`). **It needs human acknowledgement**, because the decision said "exactly these schemas". The alternative is a struct with parameters as `array<struct<key,value>>`.

Deviation 2: `resolves-to` is not a core `GraphEdge`. **Acceptable**, and documented.

LOW
- **L-M1a (human confirmation).** An unsuffixed key plus a suffixed key ("X" with "X 0008") counts as a homonym pair. This is conservative and costs recall only.
- **L-P1.** Compare cannot strip `runId`/`codeVersion` from inside the provenance string, so runs with different labels would report false differences in `coauthor-edges`, `researcher-metrics` and `resolves-to`. Owner: spark-engineer.
- **L-P2.** Two doc comments contradict the provenance implementation. `AnalyticsRecordsV2.scala:17-18` belongs to domain-engineer; `V2Export.scala:25-27` belongs to spark-engineer.
- **L-D1.** A 1-2 KB provenance literal is added to every row before checkpointing, and it is re-parsed for every row during validation. That is costly on full DBLP. Owner: spark-engineer.
- **L-T1.** Several new identity, resolves-to and edge-works invariants have no negative control, so a check hard-coded to `true` would pass (the same class as L2). Owner: test-engineer.

Checked and correct (D):
- M2: same-base pairs are ignored, listed and counted, and the M5 invariant still fails on a real violation (InvariantsSuite:144-189).
- The ID rule: core computes the code-point minimum; ORCID is never the ID; property plus RESOLVES_TO; the edge cases are documented and tested.
- The v2 validation rules agree with the Spark output.
- The digest order is consistent between Spark and core.
- `coauthor-edge-works` is consistent with the edges.
- W_r is still the sum of `jointPublicationCount`.
- Three negative controls for the v2 export.
- Driver reads go only through `DriverCollect`, and are bounded.
- Ownership and module boundaries hold.
- `Records.scala` and `Enums.scala` are unchanged.

Not verified by D: sbt and Compare results; Parquet contents; whether the frozen examples validate (left to the tests); Neptune loader behaviour; that `Identifiers.scala` is additive only (no diff tool). The orchestrator separately observed `git diff --numstat` +89/−0 for `Identifiers.scala`.

Earlier LOW notes L1–L11 are deferred by the human and were not re-raised.

## Review pass 3: repair 02 only (code-reviewer, read-only; condensed by the orchestrator)

Verdict: **APPROVE WITH NOTES**. No HIGH or MEDIUM findings. D ran no commands. Per-suite `test(` counts match `repair-02.md` (27, 5, 3 and 20).

Checked and correct (D):
- **R-M1.**
  - `dblpNames` unions all three name sources: mention raw names, base names and every alias string (`Identity.scala:83-105`).
  - `homonymGroups` (`bases >= 2 && names >= 2` per component and stem) is exactly the rule "two different names of different bases". D gave a proof.
  - Alias groups do not block themselves (IdentitySuite:572).
  - There is no other path that puts a homonym pair in one researcher.
  - The invariant groups by the OUTPUT researcher, so it is not tautological for the merge path. It does share the two helpers, which literal tests pin.
  - The reversed test (IdentitySuite:516-550) and the negative control (:552-570) are correct.
- **Unsuffixed vs suffixed.**
  - Documented as confirmed in §3.1. The counter is defined as "involves" and overlaps other blocks.
  - `cannotLinkBlockedUnsuffixedVsSuffixed` is 0 in `sparkS1r2/run-summary.json`, read from the file.
- **R-V1.**
  - The external-identity vertices cover every RESOLVES_TO target by construction, and an invariant checks it.
  - The Neptune manifest agrees with `EdgeLinks` (pinned by a test).
  - Windowed vertices are matched on (window, id). The anti-joins read counts only.
  - Each of the 16 per-table, per-end negative controls fails exactly its own invariant.
  - Real run: every `edge_endpoints_exist_*` check is 0/0, there are 1423 vertices for 1423 edges, and all 51 invariants are ok (read from the files).
- **Provenance.**
  - The JSON string column is documented (`SPARK-CONTRACTS.md:154-164`).
  - The contract test parses and validates EVERY provenance row and is not vacuous (more than 50 rows, no nulls).
- **Compare.**
  - The ignore set is explicit and minimal. `runId` hides only `codeVersion`, because its hash inputs are still compared.
  - The r1 and r2 parameters and inputs are identical (read from the files).
  - Real differences are still reported (CompareRunIdentitySuite:166-243).
  - The NonFatal catch can only cause a false DIFFERENCE, never a false EQUAL.
  - The 58 EQUAL lines and every difference reconcile with the round-2 additions.
- **Driver collection.** The only `.collect(` is `DriverCollect.scala:35`.

LOW (none blocking):
- **L1 (human: accept as a documented limit, or defer).** Non-transitive alias mapping can block a merge between two names that share one alias record.
  - Input: R1 = ["Li Chen 0001","Li Chen 0002"], R2 = ["Li Chen 0002","Q"], mentions "Li Chen 0001" and "Q", with an ACCEPTED decision between them. The result is blocked, which contradicts the §3.1 wording.
  - Costs recall only, never a wrong merge. 0 cases on the 1K sample. Owner: spark-engineer.
- **L2.** There is no `Identity.build` test where the conflicting name exists ONLY as an alias string. A mutation that drops alias names from the join in `build` (:290) would still pass. Owner: test-engineer.
- **L3.** Compare strips `runId`/`codeVersion` at any depth and in any table, so a future data field with those names would be masked. None exists today. Owner: spark-engineer.

Not verified by D:
- the sbt totals (only the marker `exit=0` was readable);
- the 195 vs 205 spark baseline (test-engineer's unreconciled count);
- the Compare console output;
- per-round file attribution, because `modules/spark/` is untracked and D has no diff tool;
- the note that the superseded S1r2 run was moved to the scratchpad.
