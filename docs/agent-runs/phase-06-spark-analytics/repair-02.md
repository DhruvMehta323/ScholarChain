# Phase 06 repair 02 (final round)

Source: the human's decisions on review pass 2, received in session and recorded here by the orchestrator. The human writes `human-gate.md`.

## Human decisions

- **R-M1: FIX.** The cannot-link (homonym) check applies across EVERY name in an alias group (all pairs of member names), not only the representatives. test-engineer reverses the test that pinned the old behaviour and adds a negative control: an alias group with no conflicting suffix still merges.
- **RESOLVES_TO targets: FIX now.** spark-engineer exports an external-identity vertex table (for example `nodes/type=external_identity`) holding every `researcher:orcid:*` target, so that every edge points to an existing vertex. It also adds an export invariant that fails on any dangling edge endpoint. test-engineer adds a test for it.
- **Provenance as a JSON string column: ACCEPTED.** Document it in `SPARK-CONTRACTS.md`. test-engineer adds a contract test that every provenance string parses into core `Provenance`.
- **Unsuffixed vs suffixed ("X" vs "X 0008") blocked: CONFIRMED** (they are separate DBLP person profiles). Document it in §3.1 and report how many merges it blocks.
- **Compare tool: FIX** in the same spark-engineer pass. Ignore run-identity fields (run ID, code-version label, provenance run fields), so that differently labelled runs compare cleanly.
- **Other LOW notes (L-P1, L-P2, L-D1, L-T1): DEFER** and list them in `agent-report.md`.
  - Orchestrator note: the compare-tool fix is the substance of L-P1. L-P1 is therefore recorded as fixed by this round, and L-P2, L-D1 and L-T1 are deferred.

## Plan

| Order | Owner | Scope |
|---|---|---|
| R2a | spark-engineer | R-M1 all-names check; external-identity vertices plus a dangling-endpoint invariant; provenance doc; §3.1 doc plus the unsuffixed/suffixed block count; Compare fix. Reruns sparkS1r2/sparkS2r2 with label `baa31d5+phase06r2`, compares them, and compares r1 against r2 |
| R2b | test-engineer | Reverse the alias test plus a negative control; dangling-endpoint test; provenance-parse contract test; Compare test |
| CHECK | orchestrator | canonical command from the repository root |
| D | code-reviewer | Rereads only round 2 |

STOP if CHECK fails, if the roots differ, or if D returns REQUEST_CHANGES (no third round). Otherwise write the final checkpoint and STOP for the human gate. Nothing is staged or committed.

## Outcome

### R2a spark-engineer (STATUS: COMPLETE; 6 stale tests fail until R2b)

**Changed files.** Identity, Schemas, Enrichment, Pipeline, LoadManifests, Invariants, Report, Compare and V2Export (all `.scala`), plus `SPARK-CONTRACTS.md` §1, §3, §3.1, §4, §8 and §10.

**R-M1 (all names).**
- A base identity's DBLP names are its mention raw names, its base name, and every HJ1 alias string mapped to it.
- A component is blocked (`distinct_dblp_homonyms`) when, for one stem, it holds 2 or more distinct base identities AND 2 or more distinct names.
- Names in one alias group never block each other.
- Distributed: the names are exploded and grouped by (component, stem).
- The invariant is recomputed with the same helpers.

**Unsuffixed vs suffixed.**
- New counter `cannotLinkBlockedUnsuffixedVsSuffixed` and new column `unsuffixedVsSuffixedGroups` in `cannot-link-blocked`.
- The counter OVERLAPS the other reasons. It means "involves", not "blocked solely because".

**R-V1 (external-identity vertices).**
- New table `external-identities` (`externalId`, `kind` = `orcid`, `value`), with one row per distinct RESOLVES_TO target. It has a Neptune vertex set `ExternalIdentity`.
- 30 tables in total. There is no row-level provenance column; table-level `_provenance.json` is present, as for other node tables.

**New invariants (51 in total).**
- Three `external_identities_*` checks.
- `edge_endpoints_exist_<table>` for authored, coauthor-edges, resolves-to, has-topic, published-in, community-membership, component-membership and community-cross-edges. These are distributed anti-joins.

**Compare (L-P1).**
- Ignored: the columns `runId` and `codeVersion`; `runId`, `codeVersion` and the run-identity parameter keys inside provenance strings; `runId`, `codeVersion`, `outputRoot` and `configOrigins` in the JSON files.
- Not ignored: checksums and algorithm parameters.

**Provenance documented** as a JSON string column, and the stale `V2Export` comment is fixed.

**Real runs (`baa31d5+phase06r2`), agent-observed.**
- sparkS1r2 and sparkS2r2: exit 0, 51/51 invariants OK, `OK 30 tables written`.
- Dangling endpoints are 0 for every one of the 8 edge tables.
- A superseded first S1r2 run, made before a wording-only change, was moved to the session scratchpad and never compared.

**Orchestrator re-ran `Compare sparkS1r2 sparkS2r2`:** **`ALL TABLES EQUAL`**.

**Agent-reported `Compare sparkS1r1 sparkS1r2`:** exit 1. The 28 common tables print EQUAL despite the different labels. The differences are only r2's additions:
- the new `cannot-link-blocked` column (0 rows in both);
- the new `external-identities` table;
- new summary keys and invariants;
- the Neptune vertex and the s3 entry for `external-identities`.

**Identity counts r1 → r2: unchanged.**
- mentions 4711; DBLP bases 3893; clusters 3907;
- merges of 2+ DBLP persons 0; cannot_link_blocked 0 (0/0/0); unsuffixed vs suffixed 0;
- within-base rejections 0; unresolved singletons 14;
- ORCID property 1423; RESOLVES_TO 1423;
- external-identity vertices: absent in r1, 1423 in r2;
- all-time nodes 3893, all-time edges 8416.

The 1K sample has no alias records, so the new rule is exercised by fixtures only.

**Tests.** Agent-observed: `sparkAnalytics/test` 195 total, 6 failed. Five are stale counts (30 tables, 8 vertex sets, 60 EQUAL lines, s3 30). The sixth is the IdentitySuite alias test, which is the intended reversal.

**Orchestrator re-ran `Compare sparkS1r1 sparkS1r2`.** It exited 1 with 58 EQUAL lines. The only differences reported were:
- `cannot-link-blocked`: SCHEMA DIFFERS (the new column);
- `external-identities`: missing in A, and its `_provenance.json` present in only one root;
- `run-summary.json`, `run-manifest.json`, `load-manifests/neptune.json`, `load-manifests/s3.json` and `cannot-link-blocked/_provenance.json`: these "differ after removing run-identity keys".

This matches the agent's explanation: every difference is an r2 addition, and the unchanged tables compare EQUAL across the two labels.

Process disclosure: the first attempt at this compare was interrupted when the session restarted. It left a stale sbt server, and the next client hung at 0% CPU. The orchestrator killed the hung client and two idle local sbt server processes (PIDs 31335, 33721 and 58881), then re-ran the compare.

### R2b test-engineer (STATUS: FAILED, on one production defect)

**New suites** (all in `modules/spark/src/test/.../spark/`):
- ExternalIdentitiesSuite (27 tests);
- HomonymInvariantSuite (5 tests);
- ProvenanceContractSuite (3 tests);
- CompareRunIdentitySuite (20 tests).

**Modified suites:** ExportSuite, V2RepairSuite, SchemasContractSuite (+1 test) and IdentitySuite (+7 M1 tests, including the negative control the human required).

**The 6 stale tests updated:**
- table count 30;
- Neptune vertex sets 8;
- 60 EQUAL lines;
- s3 manifest 30;
- schema registry 30;
- the IdentitySuite alias test REVERSED: the 0008/0009 case is now blocked with `distinct_dblp_homonyms`.

**Agent-observed totals:**
- spark `testOnly *`: 268 total, 263 passed, 5 failed.
- core `testOnly *`: 760 passed.

**Defect** (owner: spark-engineer):
- The 5 failures are `CompareRunIdentitySuite` "stripProvenanceRun leaves the text unchanged" for non-JSON input.
- Cause: `Compare.scala:124`/`:134` catches only `ujson.ParsingFailedException`, but `ujson.read` throws `TraceVisitor.TraceException` instead.
- This breaks the documented contract at `Compare.scala:117-119`.

**Orchestrator decision.** The defect sits inside the round-2 Compare deliverable, so it is routed as an in-round fix (R2a-fix) to spark-engineer, not as a third round. CHECK runs after that fix. The tests were not weakened.

### R2a-fix spark-engineer (STATUS: COMPLETE)

- Change: `Compare.scala:134`. The catch clause changed from `ujson.ParsingFailedException` to `scala.util.control.NonFatal(_)`. Nothing else changed.
- Agent-observed: `CompareRunIdentitySuite` 20 passed, 0 failed.
- Orchestrator check: `grep` shows no main source other than `Compare.scala` references `Compare`, so the real roots need no rerun.

### Orchestrator CHECK after round 2

- Ran the canonical command from the repository root: `[success] elapsed time: 1333 s`.
- Totals: core 760, entity resolution 293, hadoop 192, spark 268. That is **1,513 passed, 0 failed** (+63 since round 1's 1,450).
- Marker: `exit=0`.

### D re-review: APPROVE WITH NOTES (see review-report.md, pass 3). New LOW notes L1-L3 are for the human gate.
