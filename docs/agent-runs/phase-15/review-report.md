

<!-- ===== phase-15a-dataset-scale/review-report.md ===== -->

# Phase 15a — code-reviewer report (round 1)

Stored by the orchestrator, verbatim except that ORCID iDs are not reproduced.

```
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Phase 15a, work order D. Read-only review of the A, H, B and C dataset-scale changes in the lane 2 worktree, covering the section 6 checks and the four items the orchestrator raised. Verdict: APPROVE, APPROVE WITH NOTES, or REQUEST_CHANGES.

VERDICT: REQUEST_CHANGES (one repair round). Core work is sound: fetching is capped and cache-first, secrets are handled correctly, the selections are deterministic and nested, and every count spot-checked traces to raw output. Three findings block the commit (F1, F2, F3). The rest are notes.

FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no shell). Read, Grep and Glob only, through /Users/dhruv_mehta/Desktop/ScholarChain/datasets/raw (Glob does not follow the lane 2 symlink).
TEST RESULTS: none run. C reports "133 passed, 4 subtests passed" and the orchestrator reports a re-run. Not confirmable from files: UNVERIFIED. Test counts match the files: 9 + 28 + 13 = 50 new.

VALIDATION RESULTS:
- Caps and bounds: PASS.
  - Every attempt, retries included, consumes the budget (fetch_common.py:165-179, 221-222).
  - Defaults are 100 works / 200 requests; --allow-more is required above 100 (fetch_common.py:44-45, 118-121). fetch_scale.sh:14-17 sets explicit caps.
  - Log shows the cap firing: fetch_scale_50k.log:84 "ABORT: hard request cap of 2000 reached"; run 2 resumed with cached=49780 (line 91).
  - --plan returns before any network call (fetch_crossref.py:83-89, fetch_openalex.py:95-107, fetch_orcid.py:144-174).
- Secrets: PASS.
  - Email read from the environment and placed only in the User-Agent (fetch_common.py:71-74). Only Content-Type is stored from response headers (line 295). URLs are redacted.
  - Grep for @|mailto|Bearer across raw/logs/: 0 matches.
  - Log prints presence only: "SCHOLARCHAIN_CONTACT_EMAIL=set" (log line 90).
- Determinism and nesting: PASS.
  - Ranking is sha256(seed|nested-50k|key), independent of input order; the 10K rows are checked byte for byte against the 100K (dblp_nested_select.py:87-121).
  - Spot checks: 50K TSV article 23,649, inproceedings 26,351; 10K TSV article 5,132; 50K XML 50,000 records, 10K XML 10,000 records; DOCTYPE dblp.dtd present.
  - Provenance JSON matches the stats file.
  - Key-set comparison not performed by the reviewer; relies on the selector's own check and the orchestrator's plain-split check.
- Manifest numbers: PASS.
  - Every coverage number in dataset-manifest.yaml:454-538 equals scale_enrichment_stats.json.
  - 50K Crossref 404 prefixes sum to 1,158 across 59 prefixes; the human-named four give 894 (77.2%).
  - OpenAlex 404s by prefix sum to 89.
  - The H summary matches log lines 86, 104, 165 and 222.
  - Crossref differences from H: 48,561 + 281 = 48,842 and 1,157 + 1 = 1,158, consistent with OpenAlex 282 + 49,629 = 49,911.
  - errors.jsonl:1-3 shows the three 409s.
  - sha256 values and byte sizes: UNVERIFIED (no shell); the orchestrator reports shasum checks.

FINDINGS:
- F1 MEDIUM, required, dataset-engineer.
  - Absolute local paths with the OS username in committed files: datasets/samples/dblp/scale_selection_stats.json:12,17,62,69 and datasets/scripts/FETCH-SCALE.md:10.
  - Root cause: rel() at dblp_nested_select.py:68-72 uses Path.resolve(), which follows the datasets/raw symlink, so relative_to(REPO) fails and the absolute path is used. fetch_common.rel_to_repo (fetch_common.py:606-610) has the same flaw.
  - Fix: repo-relative paths without resolving symlinks; regenerate the stats file; update selection_stats_sha256 at dataset-manifest.yaml:453; remove the absolute path from FETCH-SCALE.md.
- F2 MEDIUM, required before commit, human/orchestrator.
  - Three real ORCID iDs in phase evidence (agent-report.md:149 and :194). The manifest (:509, :532, :543) says no ORCID iDs are committed.
  - The stats file stores unsalted sha256(iD) (scale_enrichment_stats.json:350-354), which can be reversed by dictionary.
  - Fix: redact the iDs, or have the human decide otherwise and correct the manifest wording.
- F3 MEDIUM, required, dataset-engineer.
  - The manifest marks the eligible index INVALID under policy 2 and defers the 10K/50K selections (dataset-manifest.yaml:150-158); select_50000 is "not run" (:147).
  - Phase 15a used the policy-1 10K and built the 50K from the policy-1 100K, but scale_datasets (:457, :494) does not say so. datasets.dblp.current_state and selections (:196-215) are stale.
  - Fix: add the limitation to both scale entries; update current_state and select_50000; cross-reference scale_datasets from datasets.dblp.selections.
- F4 LOW, recommended, dataset-engineer (validator) + test-engineer (tests).
  - validate_manifest.py:229 fe["sha256"] and :260 sd["dblp-50k"]["nesting"] raise KeyError instead of a clean FAIL.
  - assert_rejected (test_scale_manifest.py:63-64) accepts any exception, so test_missing_dblp_50k_entry_is_rejected (:83-86) and test_removed_file_checksum_key_is_rejected (:97-100) pass only because the validator crashes.
  - Fix: .get with check(...); tests require ("exit", 1) and the expected FAIL text.
- F5 LOW, dataset-engineer.
  - Plan assumes 1% singleton re-checks (fetch_common.py:560, 572-573; FETCH-SCALE.md:44, 59).
  - Observed: Crossref 1,225 (log line 86), which is why run 1 hit the cap; OpenAlex 949.
  - Fix: label it an assumption and size the allowance from the observed rates.
- F6 LOW, dataset-engineer.
  - The cmp determinism commands (dataset-manifest.yaml:489, 536) break because scale_enrichment_stats.json:4 embeds code_version "baa31d5+uncommitted-scripts".
  - Fix: "identical apart from code_version", or exclude the field.
- F7 LOW, dataset-engineer.
  - Retry-After sleep has no ceiling (fetch_common.py:235, 238); the 150 s budget is checked only before the next attempt.
  - Fix: cap the delay (for example 60 s).
- F8 LOW, test-engineer.
  - Selection determinism tests (test_scale_selection.py:54-87) compare two runs of the same code; no golden value pins the rank formula.
  - No repo-relative-path test for --stats-out (how F1 slipped through).
  - The 429 test (test_scale_fetch.py:171-184) does not cover a large Retry-After.
  - The other fetch tests are meaningful (the email is actually sent and checked absent; the cap counts retries).
- F9 LOW note, build-engineer.
  - .gitignore:2 "datasets/raw/" (trailing slash) does not match a symlink. The symlink is excluded only by the main repo's local .git/info/exclude:7.
  - Fix: check git status before staging, or add "datasets/raw" without the slash.

KNOWN ITEMS:
1. Absolute paths: confirmed and widened to FETCH-SCALE.md (F1).
2. KeyError: confirmed, plus a second path at line 260 (F4).
3. Inference vs. evidence: B handled both correctly.
   - Agency "Not verified ... inference only", with Crossref-member prefixes noted (dataset-manifest.yaml:521).
   - 409: documented meaning quoted, with an inference caveat; body not stored (:522).
   - H's "mostly DataCite" remark stays unconfirmed.
   - The ORCID doc and its sha256: UNVERIFIED.
   - Design note: the fetcher discards non-404 error bodies (fetch_common.py:464-467).
4. scale_datasets placement: acceptable (validate_manifest.py:268 requires exactly four sources), but it leaves datasets.dblp stale (F3).

Ownership: every changed path belongs to its reporting agent (dataset-engineer: datasets/**; test-engineer: tests/datasets/**). No lane 2 boundary path touched. No hidden reasoning in artifacts. No Terraform or AWS.

ASSUMPTIONS:
- The raw files under the main repo are what the lane 2 symlink points to.
- The phase folder will be committed with the phase.

RISKS:
- Hashes, sizes and test results UNVERIFIED (no shell).
- Joins must use the envelope identifier (574 OpenAlex alias DOIs).
- ORCID coverage of 4.5% is not representative of authors.

MANUAL ACTION REQUIRED:
- Human: F2 redaction decision.
- Human: whether to rerun to retry the three 409 iDs.
- Human: accept that the scales rest on the policy-1 index until the deferred reindex (F3).
- Human: final gate decision after the repair and re-read.

BLOCKED-ON OWNER: n/a
BLOCKED-ON EVIDENCE: n/a
NEXT GATE: one repair round (dataset-engineer F1, F3, F4 validator; human/orchestrator F2; test-engineer F4 tests), then code-reviewer re-read.
```

# Round 2 — re-read after repair 01: APPROVE WITH NOTES

Summary stored by the orchestrator (ORCID iDs not reproduced).

**Per finding:**
- F1 FIXED: the only `/Users/` hit is `validate_manifest.py:311`, the detection check itself; `rel()` and `rel_to_repo` use `os.path.abspath`.
- F2 FIXED in agent-owned files: no per-iD hashes; manifest privacy wording corrected at :497, :521, :534, :545. Residue: `agent-report.md:149` (the human-written H section) still holds three real iDs. This does not block APPROVE, but the **human must redact it before commit**.
- F3 FIXED: limitation "PENDING the human gate" (dataset-manifest.yaml:490, :536); current_state, select_50000 and the cross-reference updated; the validator enforces it.
- F4 FIXED: clean FAILs plus the backstop; `assert_rejected` is strict.
- F5 FIXED: the plan now honestly prints "TOO LOW" for a fresh 50K Crossref run at the 2,000 cap (resumable).
- F6, F7 FIXED.
- F8 FIXED, with one weak spot: `test_scale_enrichment_stats.py:85-88` and `validate_manifest.py:215-221` detect per-iD hashes by key name only. Recommended later: assert that the fixture iD hashes are absent from the output. Minor: `RepoRelativePathTests` sits after `unittest.main()` in `test_scale_selection.py`, so it runs under pytest but not as a script.
- F9 OPEN (human / build-engineer): `.gitignore` `datasets/raw/` does not match the symlink.

**New notes (LOW):**
- N1: `dblp_nested_select.py` SCRIPT_VERSION is still 1.0.0 although `rel()` changed; the regeneration command for `scale_selection_stats.json` is not recorded.
- N2: the validator's absolute-path check detects only `/Users/`.

**sbt check:** the earlier failure (AdversarialSuite timeout) does not bear on the Phase 15a gate. `sbt check` runs no Python dataset checks (build.sbt:187-190), and lane 2 changed nothing under modules/. It does bear on the repo definition of done.

**Manual actions (human):**
1. Redact the iDs at agent-report.md:149.
2. Decide on the policy-1 limitation.
3. `sbt check` rerun.
4. `git status` before staging (F9).
5. Optional: retry the 409 iDs.
6. Gate decision.


<!-- ===== phase-15b-experiment-harness/review-report.md ===== -->

# Phase 15b — code-reviewer report

Stored by the orchestrator from the code-reviewer's returned message. The reviewer is read-only (Read, Grep, Glob; no shell).

## Review 1 (Work Order D, before repair)

**Verdict: REQUEST CHANGES.** HIGH 0, MEDIUM 6, LOW 12.

Scope: untracked `experiments/**`, `load-tests/**`, `tests/experiments/**`. Not re-run by the reviewer (no shell): the orchestrator's "59 passed in 13.39s" and the validator's "RESULT PASSED" (UNVERIFIED by the reviewer; by reading, `tests/experiments/` defines 59 test items, so the count matches). The test-engineer's mutation check ("14 failed, 45 passed") is UNVERIFIED. `sbt check` NOT RUN (rule 4).

### Checks that pass (reviewer read the code)

- Ownership and boundary: every new path is under `experiments/**`, `load-tests/**` (experiment-engineer) or `tests/experiments/**` (test-engineer). No `__pycache__` under these paths.
- Secrets (`load-tests/loadgen.py:95-117`): key from an env-named file, outside the repo (realpath), mode 600/400; errors never include the path or key; key only in the auth header (246, 294); meta.json records only the variable name (480); exceptions logged by class name only (254-255); HTTPS default verifying context (231); non-loopback host must equal `--expect-host` (412); plain HTTP needs the flag (410); tests scan stdout, stderr, outputs and ledger for the key.
- Caps: `lower()` (385-390) refuses CLI values above the manifest for requests, duration, concurrency; model calls and cost reserved before each request (206-225); refusal up front if requests × cost exceeds the cap (401); global ledger limit 1.80 (443-445).
- Budget arithmetic: 3 × (4000 × $1/M + 600 × $5/M) + 500 × $0.00002/1K = $0.02101 per request; model caps sum 1.85 < 2; cost caps sum 11.80 < 25.
- Immutability: O_CREAT|O_EXCL in `sc_common.open_new`, `loadgen.open_new`, collectors (`collect_common.py:52-71`), fixtures (`make_synthetic_fixtures.py:42-51`); `--force` only on derived outputs; failed requests become rows; partial runs get meta status=failed.
- Collectors: read-only allowlist (`awscli.py:20-37`); MUTATING only in `failure_drill.py:58`; dry-run executes only the stub; tested; no delete/terminate/purge; drill needs `--execute --yes` and an approved manifest.
- Metrics hand-verified against `metrics.py`: nearest-rank fixture 10..100 → p50 50, p95 100, p99 100; retrieval fixture strict MRR 0.5, R@3 0.6667, nDCG@3 0.5169.
- Manifests: six (A–F), each with `cost_cap_usd` and `duration_cap_seconds`; A 10,000 and B 50,000 works (instructor item 1); C ceiling 10,000 vectors (item 2); B requires the A record and `--approve-50k`; all approved=false; `<id>.json` as `run-experiment.sh:49-54` requires.
- Evaluation scaffolding: templates contain no numeric values; `er_report.py` copies committed Phase 05 values with SHA-256; factory metrics count from `docs/agent-runs`.

### MEDIUM

- **M1** Ledger and caps can be bypassed without numeric flags (`loadgen.py:349-350, 374, 419-422, 431-432`; `cost_report.py:48-51`): `--ledger` to a fresh file resets the $1.80 ledger; `--budget-file` can change cost assumptions or limit; `--manifest` accepts any JSON with no schema or `approved` check; a `"synthetic": true` benchmark aimed at a real host is labeled SYNTHETIC, uses the synthetic ledger, skips the benchmark SHA check, and is excluded from real totals.
- **M2** `failure_drill.py:79-101` performs AWS mutations before checking that its write-once record can be written; a rerun from shell history mutates again and then fails to record. `subprocess.TimeoutExpired` (`awscli.py:79`) not caught.
- **M3** The drill does two failures (poison message and stop task) unless `--skip-stop-task`; the work order says one.
- **M4** D and E manifest commands cannot run through `scripts/run-experiment.sh` (runs `.command` verbatim, 59/73): missing required `--benchmark`, hardcoded `--run-id`, no way to add `--allow-insecure-http`.
- **M5** Raw outputs under `experiments/results/` dirty the tree; `scripts/lib/common.sh:312-325` excludes only `reports/*`, so `run-experiment.sh` refuses the next experiment. README does not mention committing between experiments.
- **M6** Analysis outputs carry no provenance (`an_common.py:42-51`), contrary to the CLAUDE.md provenance rule.

### LOW (deferred under rule 6 unless noted)

- L1 percentile rank computed with `ceil(p/100.0*n)` can be off by one for some p (not p50/p95/p99).
- L2 ledger key overwrite on rerun with a different `--out-dir`; concurrent-process race.
- L3 per-experiment model caps apply per invocation, not across reruns.
- L4 unexpected exception in a worker thread drops the row; meta still says completed.
- L5 duration cap can be overshot by in-flight requests (up to the 60 s request timeout).
- L6 async (202) replies count as answered with an empty list.
- L7 `scale_comparison.py:53` labeling ignores synthetic samples/cost inputs.
- L8 committed synthetic artifacts have weak provenance; the synthetic ledger is nearly full ($1.765 of $1.80).
- L9 `experiments/analysis/output/SYNTHETIC-examples/cost-per-experiment.csv` has no SYNTHETIC- prefix and synthetic=false rows; the guarding test checks only prefixed files. (Routed in repair 01.)
- L10 test gaps: failure_drill, run_suite, `--fetch-explain`, cost_report, scale_comparison, the real (non-loopback) path; two cap tests assert only rc==3.
- L11 no Bedrock metrics collector; the $2 cap rests on assumed tokens.
- L12 known documented gaps: E graph-only/vector-only return 422 on the deployed service (owners service-engineer / rag-engineer); C blocked on M13-2; drill does not check queue URL region.

### Reviewer's manual actions

1. Decide on a repair round for M1–M6. 2. Decide whether `experiments/results/SYNTHETIC-*` and the synthetic ledger are committed. 3. For M5, decide: commit between experiments, route `reports/experiments/**` ownership to factory-engineer, or an infra-engineer change to `SC_GENERATED_EXCLUDES`. 4. Resolve the no-sbt vs Stop-hook conflict. 5. Verify the Haiku 4.5 Bedrock price.

## Review 2 (re-review of Repair 01)

**Verdict: APPROVE WITH NOTES.** HIGH 0, MEDIUM 0, LOW 5 new (N1–N5). Reviewer ran nothing (no shell); the orchestrator's `131 passed in 38.32s` is UNVERIFIED by the reviewer.

### Status of review-1 findings (reviewer read the code)

- **M1 FIXED** — `enforce_target_rules` (`load-tests/loadgen.py:396-410`), called at :463 and :470, before the plan print/dry-run (:491) and `load_key` (:500). `--ledger`/`--budget-file` refused for non-loopback (:400-401); real ledger is the fixed `REAL_LEDGER` (:72, :483); manifest must pass `check_manifest` and be approved with an approver (:402-406); synthetic benchmark refused for non-loopback (:407-408); benchmark SHA required (:409-410). Loopback is an exact match on `127.0.0.1`/`localhost`/`::1` (:75, :388-393); other aliases get the strict rules (fails safe). `run_suite.py:46-57` applies the same rules; `cost_report.py:54-64` counts non-loopback "synthetic" metas as real and flags them.
- **M2 FIXED** — `create_start_record` (`experiments/collect/failure_drill.py:52-57`) at :137-147 before every AWS call; `run_action` (:60-74) logs `action_begin` before the mutation and records `TimeoutExpired` as `outcome_unknown`, exit 5 (:163), no retry.
- **M3 FIXED** — required `--action` with choices poison-message / stop-task (:87); one mutation per run; manifest and `experiments/README.md:47` match.
- **M4 FIXED** — no hardcoded run ids; `SC_RUN_ID`/`SC_LOADTEST_BENCHMARK` fallbacks (`run_suite.py:28-29`, `loadgen.py:355,360`, `failure_drill.py:83-85`); compatible with `scripts/run-experiment.sh:59,73`.
- **M5 FIXED within boundary** — `sc_common.results_root()`/`derived_root()` (:34-47); README "Between experiments" (:64-81). `SC_GENERATED_EXCLUDES` change routed to infra-engineer.
- **M6 FIXED** — `sc_common.write_provenance` (:167-181), `check_free` (:146-151), `an_common._provenance` (:33-36); all analysis scripts call `begin`; evaluation scripts write sidecars.
- **L9 FIXED** — everything under `SYNTHETIC-examples/` is prefixed and has a sidecar.

### Regression checks
Secrets pass; caps pass; write-once pass; synthetic labeling pass (stricter); raw files under `experiments/results/` unchanged by reading (names, ledger contents $1.76484, timestamps 08:39–08:47Z vs repair outputs 09:09Z; not hash-verified); ownership pass.

### New findings (LOW)
- **N1** Real ledger can undercount through a ledger-key collision (`loadgen.py:191-197`, key :485 excludes out-dir): retry with the same `SC_RUN_ID` and a different `SC_RESULTS_ROOT`/`--out-dir` drops earlier spend. Each rerun is still capped per run. Suggested: refuse a reused key for non-loopback targets. (Old L2, now also reachable via `SC_RESULTS_ROOT`.)
- **N2** README does not tell the human to commit the manifest approval edit; `run-experiment.sh:55` then refuses on the dirty tree (fails safe).
- **N3** `SYNTHETIC-cost-per-experiment.csv.provenance.json:98,101` stores an absolute home-directory path (`cost_report.py:74`, `extra=vars(a)` not made relative). Privacy and portability.
- **N4** Test gaps: no positive non-loopback control (approved manifest, matching SHA, reaching the key step); no provenance tests for the evaluation scripts.
- **N5** `failure_drill.py` with `--execute --yes` but `--dry-run` left in, with a real aws: the record shows `action_begin` and `action_end outcome=failed`, exit 4, for a call that never ran; run id used up. Fails safe, misleading record.

### Deferred LOWs changed by the repair
L10 partly closed; L8 examples now have sidecars (synthetic ledger unchanged at $1.765 of $1.80); L2 see N1. L1, L3–L7, L11, L12 unchanged.

### Residual risk (by design)
A loopback target is trusted by its label; port-forwarding `localhost` to the real ALB would book real spend as SYNTHETIC. Requires a deliberate act.
