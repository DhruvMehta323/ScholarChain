# Phase 06 — Human gate

Phase: 06, temporal coauthorship graph and Spark analytics
Date: 2026-11-21
Reviewer and ratifier: Dhruv Mehta (human architect)
Decision: **ACCEPTED**, with the decisions, disclosures and remaining risks recorded below.

## 1. What I reviewed

- The work order `prompt.md` (written by me; saved verbatim), `repair-01.md` and `repair-02.md`.
- `agent-report.md`: preconditions, A0, A1, B, C, the real-run reports, both repair rounds and the final checkpoint.
- `review-report.md`: review 1 (REQUEST_CHANGES), review 2 after repair 1, review 3 after repair 2 (APPROVE WITH NOTES, no HIGH or MEDIUM findings).
- `modules/spark/SPARK-CONTRACTS.md`.
- My own hands-on checks (section 6).

## 2. What was accepted

- **Build (build-engineer):** the Spark module settings found missing in the preconditions (A0). [Confirm the exact list from agent-report.md.]
- **Core contracts (domain-engineer):** the §3 records (components, communities, bridges, path evidence, semantic summaries, identity clusters), the DBLP person-key researcher ID, and `CoauthorEdgeV2` and `ResearcherMetricsV2` (frozen v1 records untouched).
- **Spark pipeline (spark-engineer):** identity and authorship builder with the M5 cannot-link rule; temporal weighted coauthorship graph; P_r, C_r, T_r, A_r, M_r and W_r with the works responsible for M_r; connected components; bounded BFS shortest paths and a sampled average path length; label-propagation communities; cross-community degree, participation coefficient and communities touched; k-core; per-window temporal metrics; deterministic semantic summaries; partitioned Parquet export with load manifests and a run manifest; run metrics from a SparkListener; a table-by-table compare tool.
- **Tests:** 1,125 → **1,513 passed, 0 failed** (marker `exit=0`).
- **Real local runs** on the Phase 05 rootI inputs: sparkS1r2 and sparkS2r2, ALL TABLES EQUAL (re-run by the orchestrator). Dangling edge endpoints: 0 across all 8 edge tables; 1,423 external-identity vertices cover the 1,423 RESOLVES_TO edges.

## 3. Results on the 1K sample

[Fill from agent-report.md, the real-run section: researcher and edge counts per window, component count and largest-component fraction, community count, top researchers by C_r, the maximum team size and its work, the path sample, the estimated average path length, coreness and bridge distributions, partition counts and shuffle totals.]

Identity: 0 merges of two or more DBLP persons and 0 cannot-link blocks on this sample (no alias records in the 1K slice). The M1, M5 and unsuffixed-versus-suffixed rules are exercised by test fixtures only.

## 4. Decisions taken during the phase

1. **DBLP person key:** an exact DBLP author string, including its homonym suffix, is one base identity.
2. **M5 cannot-link rule:** a component of ACCEPTED links is not merged if it contains a REJECTED or CONFLICT pair or two different validated ORCIDs.
3. **M1 (review 1):** different DBLP homonym suffixes in one cluster are an implicit cannot-link. Round 2 extended the check to every name in an alias group.
4. **Unsuffixed versus suffixed** ("X" vs "X 0008"): also blocked, because they are separate DBLP person profiles. This may cost recall; it blocked 0 merges on this sample.
5. **M2:** a REJECTED decision inside one DBLP base identity is ignored, counted and listed; the DBLP identity wins.
6. **M3 and M4:** Phase 06 exports `CoauthorEdgeV2` and `ResearcherMetricsV2`, with window fields, edge IDs, evidence pointers and named bridge measures. Contract tests check every exported row.
7. **W_r:** kept as the spec defines it (sum of joint publication counts).
8. **Canonical researcher ID:** the DBLP base ID (a merged cluster takes its lexicographically smallest member key). ORCID is a property plus a RESOLVES_TO edge to an external-identity vertex.
9. **Provenance as a JSON string column:** accepted. Documented, and a test checks that every value parses into core Provenance.
10. **Compare tool:** it ignores run-identity fields, so differently labelled runs compare cleanly. A crash on non-JSON text, found by test-engineer during round 2, was fixed inside round 2 (one line). I confirm this was not a third round; the real runs do not use the compare tool, so they did not need repeating.
11. **Deferred notes:** L1–L11 (review 1), L-P2, L-D1, L-T1 (review 2), and L1–L3 (review 3: an unusual alias overlap can block a same-record merge, costing recall only; no test for a conflicting name that appears only in an alias record; the compare tool hides any field named runId or codeVersion). Listed in agent-report.md.

## 5. Bounded EMR run

**NOT RUN in this phase.** The work order defers the bounded EMR Spark run to Phase 15 (no AWS before Phase 14). Every result above comes from local `local[*]` runs on real 1K data.

## 6. My own checks

- [x] `.claude/.last-test-run` reads `exit=0`.
- [x] The compare tool on sparkS1r2 and sparkS2r2 prints ALL TABLES EQUAL.
- [x] `SPARK-CONTRACTS.md` defines every bridge measure and the community method, with limitations.
- [x] I read the fixture test and its hand-written expected values.
- [x] `git status` shows no `target/` or `datasets/raw/` files tracked.

## 7. Disclosures

- Claude Code ran in accept-edits mode on a second Claude Pro account, monitored through Remote Control. Orchestrator model: Opus. Subagent models as pinned in `.claude/agents/`. [Claude Code version: fill in from `claude --version`.]
- Phase 15a (dataset scale) ran in parallel in a separate git worktree on branch `lane2`, on a third account. It touches only `datasets/**`, `tests/datasets/**` and its own phase folder.
- Subagents cannot run the canonical test command; the orchestrator ran it from the repository root.
- After an interruption, the orchestrator stopped a hung sbt client and two idle local sbt servers.
- spark-engineer moved an earlier sparkS1r2 run, made before a wording-only change, to the scratchpad; it was never compared.
- The 1K sample is sparse, so graph metrics on it demonstrate the pipeline, not research conclusions. The 10K and 50K runs come in Phase 15.
- `person-aliases` is absent from rootI (0 `www` records in the 1K slice).

## 8. Manual changes

None to repository source. I wrote the decisions in this file.

## 9. Remaining risk

The identity rules are proven on fixtures only at this scale; label-propagation weaknesses (unstable on symmetric structures, resolution limits); sampled path estimates; the EMR run is still pending; the deferred notes in section 4.

Accepted commit: see `commit.txt`.