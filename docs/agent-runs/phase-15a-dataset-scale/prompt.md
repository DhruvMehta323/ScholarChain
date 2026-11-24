# Phase 15a — Dataset scale: ~10K and ~50K DBLP subsets with enrichment (work order)

Phase folder: `docs/agent-runs/phase-15a-dataset-scale/`
Lane: **lane 2** (git worktree `ScholarChain-lane2`, branch `lane2`), run in parallel with lane 1 (Phases 06–11).
Why: the instructor approval (2026-11-20, item 1) requires two real DBLP scales, about 10K and about 50K works, for the Phase 15 experiments. Only the 1K slice exists. This is dataset-engineer work under the Phase 03 manifest rules, run early because enrichment fetches are slow.

| Work order | Agent | Writes only to | Runs |
|---|---|---|---|
| A | dataset-engineer | `datasets/**` | always |
| H | **HUMAN** (no agent) | gitignored `datasets/raw/**` via the documented command | after A |
| B | dataset-engineer | `datasets/**` | after H |
| C | test-engineer | `tests/datasets/**` | always |
| D | code-reviewer | nothing | always |

---

## 0. Rules for the orchestrator

1. You route; you do not write code, tests or docs inline. Subagents do not call each other.
2. Honesty floor: never report a command, count or output that was not observed. Write "NOT RUN" with the reason.
3. **Lane 2 boundary:** no agent edits `build.sbt`, `project/**`, `modules/**`, `infra/**`, `scripts/**`, `CLAUDE.md`, `docs/agents.md` or `.claude/**`. If one of these seems necessary, STOP and ask the human.
4. No sbt run is needed in this phase. If the Stop hook asks for the canonical command because a file under a source path changed, run it once from the repo root: `sbt check; echo "exit=$?" > .claude/.last-test-run`.
5. Network: bounded, read-only requests to the public APIs already in the manifest (Crossref, OpenAlex, ORCID) are allowed. No AWS. No LLM calls. **The DBLP XML is never re-downloaded**; use the local copy.
6. Never print, log or commit an API key, token or email secret. A contact email for polite-pool headers comes only from an environment variable, and is never echoed.
7. Do not stage or commit. The human commits the phase at the end.
8. Append a `## Progress checkpoint` to `agent-report.md` after each work order.

## 1. Preconditions (orchestrator, read-only)

Record each item under `## Preconditions` in `agent-report.md`:

- The current directory is the lane 2 worktree on branch `lane2`, and `git status --short` is clean.
- `datasets/raw` resolves to the main repository's raw folder (a symlink the human created). The full `dblp.xml`, `dblp.dtd` and the existing Crossref/OpenAlex/ORCID caches are visible through it. If not, STOP.
- List the existing assets with sizes and versions: `datasets/dataset-manifest.yaml`, the selection files (1K, 10K and 100K selections from Phase 03, if present), `dblp_extract_subset.py` (v1.1.0), the fetch and cache scripts, `validate_manifest.py` and `check_no_secrets.py`.
- Record whether OpenAlex, Crossref and ORCID access currently requires a key or has a usage budget, as stated in the manifest and the scripts. If a paid key would be required, STOP and ask.

## 2. A — dataset-engineer (selections, extraction, fetch plan)

1. **Nested, deterministic selections.**
   - `dblp-10k`: reuse the Phase 03 10K selection if it exists and is valid.
   - `dblp-50k`: must contain all of `dblp-10k` (nested, so the scale comparison is clean). Fill the rest from the Phase 03 100K selection with a fixed, documented seed. If the 100K selection is missing or does not contain the 10K one, define a new nested rule over the full DBLP index, documented and seeded.
   - Report the exact counts, the rule, the seed, and the record-type mix of each selection.
2. **XML extraction.** Run `dblp_extract_subset.py` (bump its version only if it needs a change) as a single read-only streaming pass over the local `dblp.xml`. Write `dblp_selection_10000.xml` and `dblp_selection_50000.xml` under `datasets/raw/dblp/derived/` (gitignored). Validate well-formedness, record counts against the selection, and the DTD reference. Record sha256 and size for each.
3. **Enrichment fetch plan.** Using the existing fetch scripts (extend them if needed; keep them idempotent and bounded):
   - **DOI-driven:** Crossref and OpenAlex for every selected work with a DOI. Use batched lookups wherever the official API supports them (for example OpenAlex `filter=doi:a|b|…`), and say how many DOIs fit in one request.
   - **ORCID:** only ORCIDs that appear in the fetched Crossref or OpenAlex records for these works. Cap the count by config.
   - **Required behavior:** cache-first (skip anything already cached, including the 1K cache), resumable after interruption, a polite rate limit with backoff on 429 and 5xx, a hard request cap per source, the polite-pool contact from env only, and a progress log with counts every N requests. Defaults stay bounded: nothing large runs without explicit flags.
   - **Dry-run first:** a `--plan` mode that prints DOI counts, cached vs. to-fetch per source, the number of requests after batching, the expected duration at the configured rate, and the disk estimate.
   - **Smoke test:** a real fetch for at most 200 uncached DOIs, to prove the path works end to end.
4. Write the exact human command for the full fetch into `datasets/scripts/FETCH-SCALE.md`. It must be resumable, run under `caffeinate -i` on macOS, write its log to a file, and come with a one-line check command for progress.

Report: the selection counts, the extraction checksums, the `--plan` output for both scales, the smoke-test result, and the exact command. Then **STOP for the human (H)**, and say so in the final checkpoint.

## 3. H — HUMAN (full fetch; no agent)

The human runs the command from `FETCH-SCALE.md` in their own terminal and lets it finish (it can be stopped and resumed). The human pastes the tool's final summary (requests made per source, cache hits, failures, duration) into `agent-report.md` under "H (human)", then tells the orchestrator to continue with B.

## 4. B — dataset-engineer (validate and record)

1. Validate the caches for both scales: coverage per source (works with a DOI; Crossref hit rate, OpenAlex hit rate, ORCID records), failure counts and reasons, and schema validity of the cached records.
2. Update `datasets/dataset-manifest.yaml` with entries `dblp-10k` and `dblp-50k`: purpose (Phase 15 scales, instructor approval item 1), selection rule and seed, nesting, record counts, file names, sha256, sizes, enrichment coverage per source, retrieval dates, staleness, limitations (bounded DOI-driven enrichment is a design choice under the spec's bounded-retrieval rule, **not** an approved deviation), privacy notes for ORCID, and validation commands.
3. Commit-safe outputs only: small stats files (counts, checksums, coverage) may go under `datasets/`; nothing under `datasets/raw/` is committed.
4. Run `validate_manifest.py` and `check_no_secrets.py` and quote the results.

## 5. C — test-engineer (at least 10 new tests, in `tests/datasets/**`)

- The selections are deterministic for a seed, and 10K ⊂ 50K.
- The extractor gives the same record count as its selection on a small fixture.
- Batch splitting respects the documented batch size.
- Cache-first: a cached DOI makes no request (use a fake transport).
- Resume: an interrupted run continues without re-fetching.
- Backoff on 429 and 5xx; the hard request cap is enforced.
- `--plan` makes no network call.
- No secret or contact email appears in any log line.
- The manifest validator passes for the new entries and rejects a missing checksum.

Run the Python test suite and report exact counts.

## 6. D — code-reviewer (read-only)

Review: unbounded downloads or missing caps; secrets handling; determinism and nesting of the selections; manifest accuracy (every number traceable to an observed output); the license and privacy notes; tests that would pass if the behavior were broken; claims not backed by output. Verdict: APPROVE, APPROVE WITH NOTES, or REQUEST_CHANGES.

## 7. Repair and STOP points

- On REQUEST_CHANGES: one repair round, by the owners, then D rereads.
- STOP and ask the human if: a precondition fails; a paid key would be needed; the plan shows more than 12 hours of fetching at the polite rate; a lane 2 boundary would be crossed; or a second repair round would be needed.
- Otherwise write the final checkpoint "Phase 15a complete; waiting for human gate", and STOP. Do not commit or stage.

## Gate (what the human checks)

- Two real, nested subsets (~10K ⊂ ~50K) with checksums, counts and a documented seed.
- Enrichment coverage per source reported from observed counts, failures included.
- The manifest entries validate; no secrets anywhere.
- Fetching was bounded, cache-first and resumable; the full fetch was run by the human.
- Reviewer verdict: APPROVE or APPROVE WITH NOTES.

## Out of scope

Running Hadoop, ER or Spark on the new subsets (that is Phase 15, on EMR); any code under `modules/**`; AWS; Terraform.
