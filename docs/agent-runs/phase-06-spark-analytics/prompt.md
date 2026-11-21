# Phase 06 — Temporal coauthorship graph and Spark analytics (work order)

Phase folder: `docs/agent-runs/phase-06-spark-analytics/`
Spec sections: "Phase 6: build the temporal coauthorship graph and run Spark analytics", "Detailed Spark requirements", "What must be measured", "Research graph model".
Input: the committed Phase 05 real local outputs (rootI Hadoop tables plus `match-decisions-final`). No AWS. The bounded EMR Spark run is deferred to Phase 15 and must be disclosed as NOT RUN.

Ownership follows `docs/agents.md` §1 and §1.1.

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A0 | build-engineer (+ dependency-updater for version values only) | `build.sbt`, `project/**` (Versions.scala: dependency-updater) | only if a precondition fails |
| A1 | domain-engineer | `modules/core/**` except `src/test/**` | only if a contract listed in §3 is missing |
| B | spark-engineer | `modules/spark/**` except `src/test/**` | always |
| C | test-engineer | `modules/spark/src/test/**`, `modules/core/src/test/**` | always |
| D | code-reviewer | nothing | always |

---

## 0. Rules for the orchestrator

1. You route; you do not write code, tests, contracts or docs inline. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. Write "NOT RUN" with the reason.
3. Canonical test command, exactly: `sbt check; echo "exit=$?" > .claude/.last-test-run`. Subagents cannot run it (cwd resets); they say so in their report. You run it from the repo root, with `cd` as its own call first.
4. Speed rule: agents iterate with targeted commands (`sbt "sparkAnalytics/testOnly <Suite>"`, `sbt "sparkAnalytics/Test/compile"`). Use `testOnly *` for real counts, never `test` (testQuick can print Total 0). The full canonical command runs once per work order. Run `scalafmt` on new files before CHECK.
5. One sbt user at a time. Never run two source-changing agents in parallel.
6. After each work order, append a `## Progress checkpoint` to `agent-report.md`. Keep reports concise; never paste whole files.
7. No AWS calls, no downloads, no LLM calls.
8. Do not stage or commit. The human commits the phase once, at the end.
9. Unattended mode: continue through all steps without asking, except at the STOP points in §8.

## 1. Preconditions (orchestrator, read-only)

Record under `## Preconditions` in `agent-report.md`:

- `git status --short` is clean, and HEAD descends from `docs/agent-runs/phase-05-entity-resolution/commit.txt`. If not, STOP: Phase 05 is not committed.
- The canonical command exits 0. Record the baseline (Phase 05 ended at 1,125).
- From Phase 05 `human-gate.md` and `review-report.md`, list what Phase 06 must honor: **M5** (§4 below), the deferred HJ7 ACCEPTED counter (use the Decisions counter group only), the work-kind limitation, and N1/N2.
- Locate the real inputs and record their paths and row counts: rootI `authorship`, `teams`, `pair-candidates`, `person-aliases`, `works`, `topics`, `institutions`, `exact-identifier-evidence`, `hj6a/entity-views`, and `match-decisions-final`.
- Check `build.sbt`: does `sparkAnalytics` depend on `core`, with Spark SQL as Provided via the Scala 2.13 interop, and is Spark on the Test classpath with a forked test JVM that has the Java 17 `--add-opens` flags Spark needs? If anything is missing, run A0.
- Check `CLAUDE.md` module boundaries allow `sparkAnalytics → core`. `sparkAnalytics` must NOT depend on `hadoop` or `entityResolution`; it reads their outputs as files only. If a module edge seems necessary, STOP and ask.

## 2. A0 — build-engineer (conditional)

Only the minimal changes found missing in §1: the `core` edge, Spark SQL Provided plus Test visibility, the forked test JVM and its `--add-opens` flags, `Test / parallelExecution := false` for Spark suites. No GraphFrames, no GraphX unless already pinned, no new libraries without a dependency-updater version entry. Run the canonical command and report.

## 3. A1 — domain-engineer (conditional contracts)

Inspect first. Phase 03 froze `CoauthorEdge`, `ResearcherMetrics`, `GraphNode`, `GraphEdge`, `EvidenceItem`. Add only what is missing, as new versioned immutable records with validation and a verbatim JSON example in the report:

- `ComponentRecord` (componentId, size, window), `CommunityRecord` (communityId, size, method, parameters, window), `BridgeRecord` (researcherId, measure name, value, parameters, window), `PathEvidence` (start, end, hops as researcher IDs, per-hop edge IDs and supporting work IDs, length, window), `SemanticSummary` (entity ID, document type, rendered text hash, text, window, run ID), `IdentityCluster` (researcher ID, member mention IDs, rule fired, cannot-link status, policy version).
- A researcher-ID path for **DBLP person keys** if core lacks one (see §4.1). Never change a frozen wire format in place; bump the version.

## 4. Human decisions built into this phase

### 4.1 Researcher identity (the input to every metric)

1. **DBLP person key.** A DBLP author string, including its homonym suffix (e.g. `Zhen Wang 0008`), is DBLP's own disambiguated person identity. All DBLP mentions with the same exact string, plus HJ1 person-alias records, form one base identity.
2. **Cross-source merge.** Two base identities merge only through ACCEPTED researcher decisions in `match-decisions-final` (both linked to the same external record, or a direct DBLP–DBLP accept).
3. **M5 cannot-link rule (mandatory).** Merges are computed as connected components over ACCEPTED links. A component is **not merged** if it contains any pair of members with a REJECTED or CONFLICT decision between them, or members carrying two different validated ORCIDs. Such a component falls back to its base identities (no merge), and is counted and listed as `cannot_link_blocked`.
4. **Canonical researcher ID.** A validated ORCID when exactly one exists in the cluster; otherwise a versioned ScholarChain identity from core's stable ID functions. UNRESOLVED and CONFLICT decisions never merge anything.
5. **Work identity.** Use the DBLP work ID as the canonical work; ACCEPTED work decisions attach external IDs and topics or institutions as enrichment only.

### 4.2 Algorithms (all DataFrame-based; documented in `modules/spark/SPARK-CONTRACTS.md`)

- **Connected components:** iterative min-label propagation with a convergence check and a max-iteration cap.
- **Shortest paths:** bounded BFS by iterative joins (max depth from config, default 6) from selected sources; never all-pairs.
- **Average path length / effective diameter:** BFS from a fixed-seed sample of k sources in the largest component (k from config). Report it as an estimate with k and the seed.
- **Communities:** label propagation with deterministic tie-breaking (smallest label wins) and a fixed iteration cap. State its known weaknesses.
- **Bridge measures (required: at least these two):** cross-community degree and participation coefficient. Approximate betweenness from sampled sources is optional; if not done, say so.
- **K-core:** iterative degree pruning, giving coreness per researcher.

## 5. B — spark-engineer

Paste the Preconditions, any A0/A1 reports, and §4. Everything goes in `modules/spark/**` (not `src/test/**`), plus `modules/spark/SPARK-CONTRACTS.md`.

**Technical rules:**
- Scala 3 cannot derive Spark encoders for case classes. Use the DataFrame API with explicit `StructType` schemas and `Row`; convert to and from core records only at the edges.
- Never collect the full graph, edge list or node list to the driver. Allowed driver collections: counts, small aggregates, at most k sampled source IDs, and convergence flags.
- Checkpoint iterative loops (local checkpoint directory under `target/`).
- Deterministic: the same inputs and config give identical outputs. No wall-clock values in data; no random without a fixed seed.
- All parameters (windows, depth, k, seed, iteration caps, partitions, paths) come from config (`scholarchain.spark.*` through core's `ModuleConfig`, defaults in a versioned resource). Nothing hard-coded.

**Jobs (one runnable driver with stages, or separate mains, documented):**
1. **Identity and authorship builder:** §4.1. Output researchers, identity clusters (with cannot-link status), works, AUTHORED, Topic, Institution, Venue. Validate that every authorship edge points to existing nodes. Count unresolved, merged and cannot-link-blocked identities.
2. **Temporal weighted coauthorship graph:** map Hadoop pair candidates onto canonical researcher IDs, drop self-pairs, canonicalize pair order, aggregate to `jointPublicationCount`, `firstCollaborationYear`, `lastCollaborationYear`, and representative work IDs (bounded list plus a stable pointer). One graph per configured window plus all-time.
3. **Breadth and team size per researcher and window:** P_r, C_r, T_r, A_r, M_r, W_r, and the work IDs responsible for M_r. Keep every measure separate; no composite score.
4. **Connected components:** component ID and size; fraction in the largest component; singleton count.
5. **Paths:** shortest-path evidence for a config list of researcher pairs (default: a fixed-seed sample of pairs inside the largest component), with hops, per-hop edge IDs and supporting works. Sampled average path length and effective diameter.
6. **Communities:** membership, sizes, cross-community edges.
7. **Bridges:** cross-community degree, participation coefficient, and the number of distinct communities each researcher touches.
8. **K-core:** coreness.
9. **Temporal:** per-window changes in distinct coauthors, team sizes, component and community membership, and bridge behavior. State inclusion-rule limits when windows are compared.
10. **Semantic summaries:** deterministic templates rendering researcher profiles, community profiles, path summaries and topic summaries from computed data and source records only. No invented numbers, no LLM.
11. **Export:** partitioned Parquet in the spec's `scholarchain-graph/` layout (local root path from config), plus load manifests (JSON) for Neptune, DynamoDB, S3 Vectors and ordinary S3, and a run manifest (inputs with checksums, config, code version, run ID).
12. **Run metrics:** a SparkListener that writes stage count, task count, shuffle read/write bytes and stage durations to `run-metrics.json`.
13. **Compare tool:** a runnable main that compares two output roots table by table (ignoring run-identity fields) and prints ALL TABLES EQUAL or the differences.

**Real local run (`local[*]`):** run the pipeline on the Phase 05 rootI inputs into two new roots, `sparkS1` and `sparkS2`, under `modules/spark/target/runs/`, with code-version label `<HEAD short>+phase06`. Compare them (ALL TABLES EQUAL required) and report: node and edge counts per window, identity counts (base, merged, cannot-link-blocked, unresolved), component count and largest-component fraction, community count and size distribution, top 10 researchers by C_r with P_r, A_r, M_r and W_r, the maximum team size and its work ID, the path-sample results, the estimated average path length, coreness distribution, bridge-measure distribution, partition counts, and the shuffle totals from `run-metrics.json`.

Report the public API that C tests against.

## 6. C — test-engineer (at least 35 new tests)

Paste the B report. Tests in `modules/spark/src/test/**`, with one shared local SparkSession per JVM (`local[2]`, UI off, 4 shuffle partitions).

- **Hand-checkable fixture graph** (about 12 researchers, 3 components, 2 dense groups joined by one bridge person, one isolated researcher, one 5-author paper, repeated collaborations, papers in two windows). Write the expected values by hand in the test, and assert exact C_r, T_r, A_r, M_r, W_r, P_r, edge weights, first and last years, components, shortest-path lengths, communities (or invariants if tie-breaking makes labels arbitrary), cross-community degree, participation coefficient, coreness and per-window values.
- **Identity:** same DBLP string merges; homonym suffixes stay separate; an ACCEPTED chain A~B~C with a REJECTED A–C stays unmerged (M5); two ORCIDs in a component block the merge; CONFLICT and UNRESOLVED never merge; a self-pair created by a merge is dropped.
- **Pair semantics:** canonical order; duplicates aggregate; a 1-author paper gives no edge; a large team gives n(n−1)/2 before consolidation.
- **Config:** a missing or invalid window, or depth or k ≤ 0, is rejected.
- **Determinism:** two runs of the fixture are equal.
- **No driver collection:** at least one test that fails if a job calls `collect` on a non-aggregate (for example a source-level check or an injected guard), or explain why it isn't testable.
- **Export:** Parquet schemas match the documented contracts; load manifests list every table; summaries contain only numbers present in the metric tables.

Leave failing tests in place and route them to the owner. Run `scalafmt`; the orchestrator runs CHECK.

## 7. D — code-reviewer (read-only)

Review in this order: M5 and over-merging; identity correctness; driver collection and distribution mistakes (shuffles, skew, checkpointing); metric correctness against the fixture; determinism; config and hard-coded values; contract drift; tests that would pass if the behavior were broken; claims not backed by output. Give severity, file:line, a failing input and the owner for each finding. Verdict: APPROVE, APPROVE WITH NOTES, or REQUEST_CHANGES.

## 8. Repair loop and STOP points

- On REQUEST_CHANGES, write `repair-NN.md` grouped by owner and route the findings that need no human decision. Each owner fixes; the orchestrator runs CHECK; if production source changed, spark-engineer reruns both real roots with a new label (`+repair01`, ...); D rereads only the repair. At most **2 rounds**.
- **STOP and ask the human** if: a precondition fails; a module edge outside §1 seems needed; a finding would change §4 decisions, a frozen wire format, ownership, or anything constitutional; CHECK fails after a repair; sparkS1 differs from sparkS2; or a third repair round would be needed.
- Otherwise, after D returns APPROVE or APPROVE WITH NOTES: write the final checkpoint "Phase 06 complete; waiting for human gate", and STOP. Do not commit or stage.

## Gate (what the human checks)

- Spark performs real distributed transformations; no full graph on the driver.
- M5 holds: cannot-link components are not merged, and the count is reported.
- Fixture tests pass for distinct and weighted degree, team sizes, components, paths, communities, bridges and k-core.
- Every bridge measure and the community method are defined, with limitations stated.
- Time windows are explicit in every output.
- Partitioning, shuffle boundaries and checkpointing are documented, with observed shuffle totals.
- sparkS1 equals sparkS2.
- The `sbt check` total rises by at least 35.
- Reviewer verdict: APPROVE or APPROVE WITH NOTES.

## Out of scope

AWS and EMR (the bounded EMR run happens in Phase 15); storage loaders; embeddings; RAG; services; Terraform; the 10K and 50K corpora; any LLM call.