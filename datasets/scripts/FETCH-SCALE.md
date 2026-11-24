# FETCH-SCALE: full enrichment fetch for dblp-10k and dblp-50k (human command)

Phase 15a, work order H. The agent does not run this. It is bounded, cache-first and resumable.
`dblp-10k` is a subset of `dblp-50k` (verified), so one run over the 50K selection covers both scales.
Outputs go to the gitignored `datasets/raw/{crossref,openalex,orcid}/works/` (one `<sha256(id)>.json`
envelope per DOI or ORCID iD). Nothing already cached is requested again, including the 1K-slice cache.

## Before you start

1. Run from the lane 2 repository root: (the worktree you are in; `git rev-parse --show-toplevel` prints it).
2. Export the polite-pool contact in your terminal (never paste it into a file or a log):
   `export SCHOLARCHAIN_CONTACT_EMAIL='<your address>'`. It is sent only in the User-Agent header.
   Optional: `export OPENALEX_API_KEY=...` (free key, $1/day instead of $0.10/day). Not needed:
   the plan costs about $0.05 of OpenAlex list calls, within the keyless $0.10/day budget.
3. Dry run, no network (prints cached / to-fetch counts, requests after batching, expected duration, disk):

   ```
   bash datasets/scripts/fetch_scale.sh plan
   ```

## The command (resumable)

```
caffeinate -i bash datasets/scripts/fetch_scale.sh 2>&1 | tee -a datasets/raw/logs/fetch_scale_50k.log
```

It runs, in order and stopping at the first source that aborts: Crossref (50 DOIs per list request),
OpenAlex (100 DOIs per list request), then ORCID (one request per iD, capped at 5,000 iDs).
Press Ctrl-C at any time; run the identical command again to resume. Each response is written as soon
as it arrives, so an interruption loses at most one request. Exit status 3 means a source stopped
early (hard cap, time limit or repeated transport failures); rerun the same command.

Bounds (all overridable by environment variables, see the header of `fetch_scale.sh`):

| Source | Per request | Hard request cap | Pause | Notes |
|---|---|---|---|---|
| Crossref | 50 DOIs (`filter=doi:a,doi:b`) | 2,000 | 0.5 s | polite pool, list queries limit 3 req/s |
| OpenAlex | 100 DOIs (`filter=doi:u1\|u2\|...`, the documented OR limit) | 2,000 | 0.5 s | list+filter $0.0001 per call; singleton re-checks free |
| ORCID | 1 iD | 5,500 | 0.5 s | iD cap `ORCID_LIMIT=5000`, see the STOP note below |

Retries on timeouts, 429 and 5xx use exponential backoff. `Retry-After` is honoured but capped at
`MAX_RETRY_AFTER_SECONDS` = 60 s per sleep (fetch_common 2.1.0), so a hostile or buggy header cannot park
the run; every attempt, retries included, counts against the hard cap. A DOI that a list response omits
is looked up again as a singleton (OpenAlex resolves some alias DOIs to a work with another primary DOI).
The `--plan` allowance for these re-checks is `SINGLETON_ALLOWANCE_PCT` = 3 percent of the DOIs still to
fetch. That figure is an ASSUMPTION sized from one run, not a guarantee. Observed in the 50K run
(about 49.7K DOIs): Crossref 1,225 singleton re-checks (about 2.5 percent), OpenAlex 949 (about 1.9 percent).

## One-line progress check (run in a second terminal)

```
for s in crossref openalex orcid; do printf '%s=%s ' $s "$(find datasets/raw/$s/works -name '*.json' | wc -l | tr -d ' ')"; done; echo; tail -n 2 datasets/raw/logs/fetch_scale_50k.log
```

The first line counts stored envelopes per source. Targets after a complete run:
crossref about 50,000, openalex about 50,000, orcid about 5,100 (5,000 cap plus the 100 of the 1K slice
and the Phase 15a smoke iDs, which may or may not fall inside the capped set). The log line shows
`progress requests=<used>/<cap> ok=<n> ...`, printed every 25 requests (ORCID: every 100).

## Expected effort (estimates; the smoke run observed about 2.5 s per Crossref list request of 50, 4 s per OpenAlex list request of 100, 0.9 s per ORCID request)

- Crossref: about 995 list requests plus about 1,225 singleton re-checks (observed; the plan assumes up to 3 percent); roughly 40 to 60 minutes; about 650 MB.
- OpenAlex: about 498 list requests plus about 949 singleton re-checks (observed; the plan assumes up to 3 percent); roughly 35 to 45 minutes; about 1.1 GB.
- ORCID: about 4,840 requests at the 5,000-iD cap; roughly 1.2 to 2 hours; about 1.2 GB (about 250 KB per iD).
- Total about 3 to 4 hours, well under 12 hours. 83 GB of disk was free when this was written.

## STOP note: the ORCID cap is a decision, not a default

ORCID iDs are only known after the DOI fetches (`orcid` is derived from the author fields of the stored
Crossref and OpenAlex records of the selected works). From the 282 DOIs stored so far, the observed
ratio is about 2.8 distinct iDs per DOI. Extrapolated to 50,000 works that is on the order of 138,000
iDs (an estimate, not observed): fetching them all would take about 58 hours at the polite rate and
about 35 GB. That exceeds the 12-hour STOP threshold, so the run caps ORCID at 5,000 iDs (a fixed
hash-ordered prefix, so raising the cap later only adds iDs). To change the cap:
`ORCID_LIMIT=2000 bash datasets/scripts/fetch_scale.sh` (cap and `ORCID_MAX_REQUESTS` should move together).

## When it finishes

Paste the closing lines (each source prints one JSON summary with `requests_made`, `already_present`
(cache hits), `retrieved_ok`, `missing_404`, `errors`, `other_status`, `aborted`; the script prints the
total `duration_seconds`) into `docs/agent-runs/phase-15a-dataset-scale/agent-report.md` under "H (human)".
The `--plan` report can be rerun at any time to see what is still left.

## Files this procedure uses

- `datasets/scripts/fetch_scale.sh`, `fetch_crossref.py` (2.0.0), `fetch_openalex.py` (2.0.0), `fetch_orcid.py` (2.0.0), `fetch_common.py` (2.1.0)
- Selections and extractions (gitignored): `datasets/raw/dblp/derived/dblp_selection_{10000,50000}.{tsv,xml}`
- An earlier ORCID iD list file is never overwritten: iDs of this run go to `datasets/raw/orcid/orcid_ids_50k.json`
  (a timestamped sibling is written if it already exists).
