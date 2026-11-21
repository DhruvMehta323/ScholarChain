# Phase 06 — Temporal coauthorship graph and Spark analytics: agent report

Assembled by the orchestrator. Agent reports are pasted in condensed form. Nothing is reported as run unless its output was observed.

## Preconditions

- `git status --short`: the only entry is `?? docs/agent-runs/phase-06-spark-analytics/`, which is this phase's evidence folder holding the work order. The tree is otherwise clean.
- HEAD `baa31d5` descends from Phase 05 `commit.txt` `5dd0ed6607b43180024ed79b15a9618075b59323`. Checked with `git merge-base --is-ancestor`: DESCENDS.
- Baseline canonical command `sbt check; echo "exit=$?" > .claude/.last-test-run`:
  - `Passed: Total 640` (core), `Passed: Total 293` (entityResolution), `Passed: Total 192` (hadoop), the other modules Total 0. That is **1,125 passed and 0 failed**, matching the Phase 05 end state.
  - `[success] elapsed time: 234 s`; the marker is `exit=0`.
- What Phase 06 must honor, from Phase 05 `human-gate.md` and `review-report.md`:
  - **M5 (mandatory):** accepted pairs are decided pairwise, so A~B and B~C with a REJECTED or CONFLICT A–C would over-merge under connected components. Implemented as the §4.1.3 cannot-link rule. The review also notes that `ResolvedMention` lets an `accepted` record carry another mention's singleton ID, so Phase 06 must not trust that field blindly.
  - **Deferred HJ7 ACCEPTED counter:** HJ7's `ACCEPTED` counter equals the total decisions written (2192 in rootH), not accepted outcomes. Phase 06 quotes only the Decisions counter group / `match-decisions-final.summary.json` `byOutcome`.
  - **Work-kind limitation:** work thresholds are uncalibrated (13 queue rows, 1 NON_MATCH; precision not measurable). Under §4.1.5, ACCEPTED work decisions only add enrichment and never change the canonical DBLP work.
  - **N1:** the 0.92 family-JW route admits short surnames one letter apart (Chen/Cheng) when they share an ORCID. It fires 0 times in rootI (agent-reported). Phase 06 inherits that risk through ACCEPTED researcher decisions. M5's two-ORCID block does not catch it, because both sides share one ORCID.
  - **N2:** additive block key for double surnames ("Ffrench Constant") is deferred. It is a recall loss only (missed candidates stay as separate base identities).
- Real inputs (Phase 05 rootI, code version `74eee4e+repair04`, run `run:89884d89…6610`). Row counts are the observed line counts of the gzipped part files:

  | Table | Path | Rows |
  |---|---|---|
  | authorship | `modules/hadoop/target/runs/rootI/curated/authorship/snapshot=dblp-1k/` | 3,928 |
  | teams | `…/rootI/curated/teams/snapshot=dblp-1k/` | 1,000 |
  | pair-candidates | `…/rootI/curated/pair-candidates/snapshot=dblp-1k/` | 8,420 |
  | works | `…/rootI/curated/works/snapshot=dblp-1k/` | 8,295 |
  | topics | `…/rootI/curated/topics/snapshot=dblp-1k/` | 206 |
  | institutions | `…/rootI/curated/institutions/snapshot=dblp-1k/` | 250 |
  | exact-identifier-evidence | `…/rootI/curated/exact-identifier-evidence/snapshot=dblp-1k/` | 296 |
  | author-mentions (extra) | `…/rootI/curated/author-mentions/snapshot=dblp-1k/` | 4,711 |
  | hj6a/entity-views | `modules/hadoop/target/runs/rootI/work/hj6a/entity-views/` | 13,006 |
  | match-decisions-final | `modules/entity-resolution/target/p3/match-decisions-final.jsonl` | 2,191 (summary: accepted 999, rejected 712, unresolved 470, conflict 10) |
  | person-aliases | **not present** in rootI (no `hj1/person-aliases` or curated table; HJ1 counters show no `PERSON_ALIASES`, so the 1K selection has no `www` person records) | 0 |

- Build (`build.sbt:110-116`, `project/Dependencies.scala`):
  - `sparkAnalytics.dependsOn(core)`: YES. It does not depend on `hadoop` or `entityResolution`.
  - Spark SQL `Provided` via `CrossVersion.for3Use2_13` (Spark 4.1.1; Scala 2.13.17 pin): YES. Provided dependencies are on the sbt Test classpath.
  - Forked test JVM with the JDK 17 `--add-opens` flags: YES (`commonSettings`, `build.sbt:33-58`).
  - `Test / parallelExecution := false` for the Spark suites: **MISSING**, so A0 runs for that one setting.
  - `spark-graphx` is already pinned as Provided. Per §4.2, the algorithms are DataFrame-based and GraphX is not used.
  - The module directory `modules/spark/` does not exist yet (no sources).
- Module boundaries: CLAUDE.md forbids cycles and any dependency on `services`. `sparkAnalytics → core` is allowed. Hadoop and ER outputs are read as files only. No new module edge is needed.
- Core contracts (§3 inspection): `CoauthorEdge`, `ResearcherMetrics`, `GraphNode`, `GraphEdge` and `EvidenceItem` exist (`Records.scala`). `ComponentRecord`, `CommunityRecord`, `BridgeRecord`, `PathEvidence`, `SemanticSummary` and `IdentityCluster` are **missing**. There is no DBLP-person-key researcher ID function (only the generic `Ids.researcherIdScholarChain(identityKey)`), and there is no Spark module config contract (core has `HadoopConfig` / `scholarchain-hadoop.v1.conf` only). A1 therefore runs.

## A0 — build-engineer (condensed)

- STATUS: COMPLETE. `build.sbt` gained 2 lines in `sparkAnalytics`: a comment and `Test / parallelExecution := false`. The orchestrator confirmed with `git diff --stat` (`build.sbt | 2 ++`) that nothing else changed.
- Agent commands: `sbt sparkAnalytics/Test/compile` exit 0; `sbt scalafmtSbtCheck` exit 0. The agent also ran the canonical command itself and reports `[success] elapsed time: 234 s` and marker `exit=0` (marker 23:49:41, build.sbt 23:41:47). An earlier attempt with a `cd` prefix was blocked by the guard.
- Disclosure: the orchestrator also started the canonical command while the agent's run was in progress, so two `sbt check` runs overlapped through the one sbt server (this departs from rule 0.5). The orchestrator's run observed: core `Passed: Total 640`, entityResolution 293, hadoop 192, Failed 0; `[success] elapsed time: 462 s`; marker `exit=0`. That is **1,125 passed, 0 failed**, unchanged. No Spark suite exists yet, so the setting is loaded but not exercised.

## Progress checkpoint

A0 done (1,125 passed, exit=0). Next: A1, domain-engineer.

## A1 — domain-engineer (condensed)

- STATUS: COMPLETE. Files:
  - created `modules/core/src/main/scala/scholarchain/core/AnalyticsRecords.scala`, `SparkConfig.scala` and `modules/core/src/main/resources/scholarchain-spark.v1.conf`;
  - modified `Identifiers.scala` (three additive `Ids` functions; no existing function or wire format changed).
- New v1 records, each with `provenance`, `derives ReadWriter` and `given Contract`: `ComponentRecord`, `CommunityRecord`, `BridgeRecord`, `PathEvidence`, `SemanticSummary`, `IdentityCluster`. New enums (lowercase wire): `BridgeMeasure` (cross_community_degree, participation_coefficient, distinct_communities, approx_betweenness) and `CannotLinkStatus` (clear, blocked).
- Window convention: no optional windows. All-time is the explicit `GraphWindows.AllTime = GraphWindow(1000, 9999)`, label `1000-9999`.
- Identity functions:
  - `Ids.researcherIdDblpPerson(name)` = `researcher:sc1:H("scholarchain.researcher.v1", "dblp-person.v1:" + exact string)`. The string is exact (no trim or fold), so homonym suffixes stay distinct, and it cannot collide with the `unresolved.v1:` scheme.
  - `Ids.researcherIdMergedCluster(baseIds)` (≥ 2 base IDs, order-independent) uses the `merged-cluster.v1:` key.
  - `Ids.researcherIdForCluster(baseIds, orcids)`: exactly one ORCID gives `researcher:orcid:…`; two or more distinct ORCIDs give Left (M5); no ORCID gives the single base ID or the merged-cluster ID.
- `SparkConfig` mirrors `HadoopConfig`: 21 `spark.*` keys, env `SCHOLARCHAIN_SPARK_<KEY>`, override `scholarchain.spark.<key>`, defaults in `scholarchain-spark.v1.conf`. Defaults: windows `2000-2004,…,2020-2024` plus all-time, bfs depth 6, path pairs 20 / seed 441, k 50 / seed 441, iteration caps LP 20, CC 50, k-core 50, representative work IDs 5, shuffle partitions 8, output partitions 4, plus the checkpoint, output and three input paths, `policy-version` `coauthor-graph.v1` and `code-version` `dev`. It rejects empty, overlapping, duplicate or invalid windows, counts < 1 (path-sample-pairs ≥ 0), and blank paths and tokens. Errors are collected and never carry values.
- Verbatim JSON examples came from a scratch main the agent ran and then deleted. They are in the agent's report and not reproduced here.
- Agent commands: `sbt "core/compile; core/Test/compile"` success; `sbt "core/scalafmt; core/compile; core/Test/compile"` success (3 files formatted). The agent did not run `sbt check` (per the work order).
- Orchestrator canonical run: core 640, entityResolution 293, hadoop 192 passed, Failed 0; `[success] elapsed time: 234 s`; marker `exit=0`. The new contracts have no tests yet; they are routed to C.
- Orchestrator observation: real DBLP-1k work years run from 1938 to 2027 (at most 71 per year; most from 2005 on). The default windows cover 2000–2024, so works from before 2000 and from 2025–2027 appear only in the all-time graph. This is disclosed and not changed.

## Progress checkpoint

A1 done (1,125 passed, exit=0). Next: B, spark-engineer.

## B — spark-engineer (condensed)

- STATUS: COMPLETE. Created `modules/spark/SPARK-CONTRACTS.md` and 23 sources under `modules/spark/src/main/scala/scholarchain/spark/`: Run, Compare, Pipeline, Inputs, Identity, Graph (Graph, Metrics), Components, Communities, Bridges, KCore, ShortestPaths (Sampling, ShortestPaths), Temporal, Enrichment, Summaries, Export, LoadManifests, Invariants, Report, RunMetrics (SparkListener), Udfs, Checkpointer (Checkpointer, Convergence), DriverCollect, and Schemas (Cols, InputSchemas, the Tables registry). Nothing outside `modules/spark/` was touched.
- Run path: `sbt "sparkAnalytics/Test/runMain scholarchain.spark.Run --master local[*] --output-root <abs> --code-version baa31d5+phase06 --checkpoint-dir <abs> --set input.…=<abs>"`. Spark is Provided, so only the Test classpath has it. Paths must be absolute because the forked JVM's cwd is `modules/spark`. No build change was needed.
- Agent-reported process: two debug runs failed and were fixed before the real runs (an ambiguous self-join in Components; Long values written as JSON strings). The first Compare crashed on MAP columns in `exceptAll`; it was fixed in `Compare.scala` only, after S1/S2 were written. Run/pipeline sources did not change between or after S1 and S2. A negative control (S1 vs a `path-sample-seed=7` run) printed DIFFERENCES, so the tool is not vacuous. A temporary identity smoke main was run on a synthetic fixture and then deleted. The agent killed a stuck sbt server (pid 82691) once.
- Real runs: sparkS1 and sparkS2 each exit 0 (42 s / 41 s), `OK 26 tables written`, the same run ID `run:fa55dff8…0a0a`. Invariants: 28 checked, 28 OK on both.
- **Orchestrator re-ran Compare** (`sbt "sparkAnalytics/Test/runMain scholarchain.spark.Compare <S1> <S2>"`): every table, `run-summary.json`, `run-manifest.json`, the four load manifests and the 26 `_provenance.json` files printed EQUAL, and the last line was **`ALL TABLES EQUAL`**.
- Inputs read: mentions 4,711, authorship 3,928, pair-candidates 8,420, works 8,295, teams 1,000, topics 206, institutions 250, entity-views 13,006, decisions 2,191. `person-aliases` is absent and treated as empty (listed in `absentOrEmptyTables`). `exact-identifier-evidence` is not read. Outcomes come from the decisions file only: researcher accepted 775, rejected 712, unresolved 470, conflict 6; work accepted 224, conflict 4. The total of 999 accepted matches the summary `byOutcome`.
- Identity: 4,711 mentions (3,928 DBLP, 783 external); 3,893 base DBLP identities; 3,907 clusters; **0 merges of 2+ DBLP persons**; 388 clusters absorbing 769 external mentions; **cannot_link_blocked 0**; 14 unresolved external singletons; 1,423 ORCID-ID clusters; 0 ORCID collisions; 0 unmapped decisions; 0 self-pairs dropped after a merge. M5 is implemented, but the real 1K data never triggers it, so it is exercised only by fixtures (routed to C).

| Window | Nodes | Edges | Works | Components | Largest (fraction) | Communities | Max team |
|---|---|---|---|---|---|---|---|
| 2000-2004 | 149 | 182 | 49 | 48 | 6 (0.0403) | 49 | 6 |
| 2005-2009 | 295 | 405 | 94 | 94 | 9 (0.0305) | 94 | 9 |
| 2010-2014 | 530 | 1,103 | 140 | 138 | 20 (0.0377) | 139 | 20 |
| 2015-2019 | 771 | 1,409 | 196 | 196 | 12 (0.0156) | 196 | 12 |
| 2020-2024 | 1,299 | 3,592 | 292 | 290 | 42 (0.0323) | 291 | 42 |
| all-time (1000-9999) | 3,893 | 8,416 | 1,000 | 969 | 42 (0.0108) | 983 | 42 |

- Singletons are 0 in every window. Per review note L10, the reason is that the DBLP selection admits only works with at least two authors (Phase 03 work order), not merely that every researcher has a work. Of the works, 229 fall only in all-time: 102 before 2000 and 127 after 2024.
- All-time community sizes: 2: 243, 3-5: 577, 6-10: 151, 11-25: 11, 26+: 1; the top sizes are 42, 23, 20, 14 and 13.
- Top 10 by C_r (all-time): nine researchers with C_r 41, P_r 1, A_r 42.0, M_r 42, W_r 41, all from `work:dblp:journals/neuroimage/LucaILPSZANFBZF21`, plus Tomasz Pieciak (C_r 41, P_r 2, A_r 22.0, M_r 42, W_r 42). Ties are ordered by researcherId. The maximum team size is 42 on that same work.
- Paths (seed 441, depth 6): all-time 20 of 20 sampled pairs found, all of length 1. Per window 3/3, 4/4, 10/10, 6/6, 20/20. The estimated average path length is 1.0 (k = 50 requested, 42 sources used, seed 441, 1,722 reached pairs, effective diameter 1). The sample's largest components are single-paper cliques, so these values reflect the sparse 1K sample.
- All-time coreness: 1: 487, 2: 751, 3: 826, 4: 656, 5: 486, 6: 224, 7: 120, 8: 81, 9: 70, 10: 44, 11: 36, 12: 13, 13: 14, 19: 20, 22: 23, 41: 42.
- All-time bridges: cross-community degree 0: 3,838, 1: 41, 2: 5, 3-5: 9. Participation coefficient 0: 3,838, (0.25, 0.5]: 55, max 0.5. Distinct communities 1: 3,838, 2: 55. **Approximate betweenness: NOT computed.**
- Algorithms (iterations / converged / cap): identity CC 3/true/50; graph CC 3/true/50; LP 6/true/20 (own label counts as one vote); k-core 20/true/50; BFS 2/true/6.
- Partitions: shuffle 8 (AQE off), output 4 per window. Shuffle totals (`run-metrics.json`; identical in S1 and S2): 390 jobs, 1,078 stages, 10,190 tasks, shuffle read 212,151,556 B, shuffle write 197,170,720 B. Summed stage time is wall-clock and differs: S1 46,471 ms, S2 46,422 ms. It is not part of the compared output. (Corrected after review note L10, which the orchestrator verified against both files.)
- Agent's assumptions that need human confirmation at the gate (outside §4; not changed):
  1. The metric definitions are not given in any repository file: P_r = distinct works; C_r = distinct coauthors; T_r = sum of team sizes; A_r = T_r / P_r; M_r = maximum team size (with `maxTeamWorkIds`); **W_r = weighted degree** (sum of `jointPublicationCount`).
  2. An unmerged DBLP person with exactly one validated ORCID gets the `researcher:orcid:` ID. If two clusters share an ORCID, both fall back to non-ORCID IDs.
- Orchestrator canonical run after B: core 640, entityResolution 293, hadoop 192 passed, Failed 0 (no Spark tests yet); `[success] elapsed time: 234 s`; marker `exit=0`.
- EMR Spark run: **NOT RUN** (deferred to Phase 15).

## Progress checkpoint

B done (1,125 passed, exit=0; sparkS1 = sparkS2). Next: C, test-engineer.

## C — test-engineer (condensed)

- STATUS: COMPLETE. Created 16 test files: three core suites in `modules/core/src/test/scala/scholarchain/core/`, and 12 suites plus `TestSupport.scala` in `modules/spark/src/test/scala/scholarchain/spark/`. No production edits.

| Suite | Tests |
|---|---|
| core AnalyticsRecordsContractSuite | 21 |
| core SparkIdentityIdsContractSuite | 12 |
| core SparkConfigContractSuite | 17 |
| spark IdentitySuite | 19 |
| spark PairSemanticsSuite | 14 |
| spark FixtureMetricsSuite | 15 |
| spark FixtureGraphSuite | 15 |
| spark GraphAlgorithmsSuite | 25 |
| spark SummariesSuite | 9 |
| spark ExportSuite | 17 |
| spark DeterminismSuite | 6 |
| spark InvariantsSuite | 13 |
| spark NoDriverCollectSuite | 8 |
| spark SchemasContractSuite | 6 |
| spark InputsRunSuite | 11 |
| spark RunMetricsSuite | 2 |
| **New total** | **210** (core 50, spark 160) |

- Fixture: 13 researchers and 11 works. It has a 5-author paper, a 4-clique, a bridge person, a repeated pair (3 works), an isolated researcher, and a 2012 work outside both explicit windows. Windows are 2000-2004, 2005-2009 and all-time. Expected values are written by hand with the arithmetic in comments.
- Identity tests cover M5 with REJECTED and with CONFLICT, two ORCIDs, CONFLICT/UNRESOLVED never merging, homonyms, aliases, and a self-pair after a merge.
- No-driver-collection check: a source scan outside `DriverCollect.scala`, with negative controls showing it flags an injected `df.collect()`.
- Negative controls also exist for invariants, Compare, determinism and the summary number check.
- The agent reports no production defect and no failing test. Its own test mistakes were fixed (one hand-computed year, among others).
- Not tested: `Report.build`/`run-summary.json` directly, the `Run.run` happy path (`spark.stop()` would kill the shared session), and `Compare.main`. Label-propagation community labels are asserted as invariants plus one pinned observation.
- Agent targeted runs: `sparkAnalytics/testOnly *` Total 160 passed; `core/testOnly *` Total 690 passed; scalafmtCheck success for both test trees.
- **Orchestrator canonical run:** core `Passed: Total 690`, entityResolution 293, hadoop 192, sparkAnalytics 160 (contractTest spark 6), Failed 0. That is **1,335 passed and 0 failed, +210 over the 1,125 baseline**. `[success] elapsed time: 488 s`; marker `exit=0`.

## Progress checkpoint

C done (1,335 passed, exit=0). Next: D, code-reviewer.

## D — code-reviewer

Verdict: **APPROVE WITH NOTES** (full condensed review in `review-report.md`). There is no HIGH finding. The MEDIUM findings are:
- M1, a transitive merge of distinct DBLP keys through an external mention (human decision);
- M2, the within-base-rejection invariant contradiction (fixable without a §4 change; human to confirm);
- M3, the bounded edge work list against the frozen `CoauthorEdge` (human decision);
- M4, the missing `CollaborationMetric` wire names for P_r, T_r, A_r, M_r (human decision).

There are 11 LOW notes. All have 0 occurrences or no effect on the real rootI run, except L10 (two report claims), which the orchestrator corrected in this file after checking `sparkS1` and `sparkS2` `run-metrics.json`.

Orchestrator checks after the review:
- `git diff --numstat`: `build.sbt` 2 added / 0 removed; `Identifiers.scala` 58 added / 0 removed, so the core change is additive only.
- `git status --short -uall` shows 49 untracked or modified paths, none under `target/` (`**/target/` is gitignored, `.gitignore:14`).
- No source under `modules/` is newer than the marker (01:17:31).
- Nothing is staged.

## Gate summary (observed)

- Spark performs distributed DataFrame transformations. The only driver collection is through `DriverCollect`, with aggregates and bounded samples only (D confirmed; a source-scan test pins it).
- M5: implemented; `cannot_link_blocked` = 0 on rootI (reported). Covered by fixture tests for REJECTED, CONFLICT and two ORCIDs. Open: M1.
- Fixture tests cover distinct and weighted degree, team sizes, components, paths, communities (invariants), bridges and k-core. D recomputed the hand values.
- Bridge measures (cross-community degree, participation coefficient, distinct communities) and label propagation are defined with their limitations. Approximate betweenness was NOT computed.
- Every windowed table carries an explicit window; all-time is `1000-9999`. 229 of 1,000 works fall only in all-time under the default windows.
- Partitioning, shuffle boundaries and checkpointing are documented in `SPARK-CONTRACTS.md` §7. Shuffle totals are in B.
- sparkS1 = sparkS2: `ALL TABLES EQUAL` (re-run by the orchestrator).
- `sbt check`: 1,125 → **1,335** passed, 0 failed (+210, gate ≥ 35); marker `exit=0`.
- Reviewer verdict: APPROVE WITH NOTES.
- NOT RUN: the bounded EMR Spark run (Phase 15). No AWS calls, downloads or LLM calls were made.

Open items for the human gate:
1. M1: an implicit cannot-link for distinct DBLP homonym-suffix keys?
2. M3 and M4: how Phase 06 edges and metrics map onto the frozen `CoauthorEdge` and `ResearcherMetrics` (v2 records, a full edge-to-work table, or enum additions).
3. M2: confirm the policy "within-base rejections are ignored and counted" and route the fix.
4. Assumption (a), the metric definitions: note that W_r = T_r − P_r exactly as defined.
5. Assumption (b), ORCID IDs for unmerged DBLP persons.
6. Whether to route the LOW notes (L1-L4, L6-L9, L11) now or defer them.

Process disclosures:
- After A0, two canonical `sbt check` runs overlapped (the agent's and the orchestrator's).
- spark-engineer killed a stuck sbt server once.
- The Compare tool was fixed after S1/S2 were written; the Run sources were not changed.
- The seed-7 negative-control root was deleted by spark-engineer and cannot be re-inspected.

## Progress checkpoint

Phase 06 complete; waiting for human gate. Nothing staged or committed.

## Repair 01 (human decisions in `repair-01.md`)

## Progress checkpoint

R1 (domain-engineer) done: v2 records, examples, ID helper. CHECK 1,335 passed, 0 failed, `exit=0`. Next: R2 spark-engineer.

## Progress checkpoint

R2 (spark-engineer) and R3 (test-engineer) are done. sparkS1r1 = sparkS2r1 (`ALL TABLES EQUAL`, re-run by the orchestrator). CHECK: 1,450 passed, 0 failed, `exit=0`. Next: D re-reviews only the repair.

Deferred LOW notes (human decision, repair 01): L1–L11 from review pass 1, listed in review-report.md.

## Progress checkpoint

Repair 01 STOPPED: D returned REQUEST_CHANGES on R-M1 (a homonym pair can merge through an HJ1 alias group). CHECK: 1,450 passed, 0 failed, `exit=0`. sparkS1r1 = sparkS2r1.

Waiting on human decisions:
- R-M1: fix it, or ratify the "smallest alias string" reading.
- R-V1: an ORCID vertex now, or in Phase 07.
- Deviation 1: acknowledge provenance as a string column.
- L-M1a: confirm.

Nothing staged or committed.

## Repair 02 (human decisions in `repair-02.md`)

Deferred LOW notes from review pass 2 (human decision): L-P2, L-D1, L-T1. L-P1 (Compare and run-identity fields) is being fixed in round 2.

## Progress checkpoint

Round 2: R2a, R2b and R2a-fix are done.
- sparkS1r2 = sparkS2r2: `ALL TABLES EQUAL` (orchestrator re-ran Compare).
- r1 vs r2: the differences are only r2's additions (orchestrator re-ran Compare).
- CHECK: 1,513 passed, 0 failed, `exit=0`.

Next: D rereads round 2.

## Final state after repair 02

- Verdict (D, pass 3): APPROVE WITH NOTES.
- `sbt check`: 1,513 passed, 0 failed, `exit=0`.
- sparkS1r2 = sparkS2r2: `ALL TABLES EQUAL`.
- Dangling edge endpoints: 0 in all 8 edge tables.
- Identity is unchanged on the real data: 0 merges of 2+ DBLP persons, cannot_link_blocked 0, unsuffixed-vs-suffixed 0. 1423 ORCID properties, 1423 RESOLVES_TO edges and 1423 external-identity vertices.

Deferred LOW notes (human decisions):
- Pass 1: L1-L11.
- Pass 2: L-P2, L-D1, L-T1. L-P1 was fixed in round 2.

Open for the human gate:
- Pass 3, L1: an alias-record edge case blocks a merge. Recall only.
- Pass 3, L2: missing test for an alias-only conflicting name.
- Pass 3, L3: Compare strips run fields at any depth.

Process disclosures, round 2:
- R2b found a defect in the round-2 Compare work. It was fixed in-round (R2a-fix, a one-line catch) and was not counted as a third round.
- After the session restart, the orchestrator killed a hung sbt client and two idle local sbt servers.

## Progress checkpoint

Phase 06 complete; waiting for human gate. Nothing staged or committed.
