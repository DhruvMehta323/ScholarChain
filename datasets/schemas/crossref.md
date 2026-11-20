# Crossref schema notes

Endpoint: `https://api.crossref.org/works/{doi}` (DOI percent-encoded including `/`).

## Raw envelope (gitignored, `datasets/raw/crossref/works/<sha256(doi)>.json`)
`identifier` (the DOI), `request_url` (redacted), `retrieved_at` (UTC), `http_status`,
`missing` (true for 404), `response_content_type`, `body_is_json`, `body`,
`fetcher_code_version`. A 404 is stored as an envelope with `missing: true`. Transport failures
are never stored as envelopes; they go to `errors.jsonl` and are retried.

## Body fields observed in the retrieved records
`status`, `message-type`, `message-version`, `message` with, among others: `DOI`, `title` (list),
`author` (list of objects; may hold `given`, `family`, `ORCID`, `affiliation`), `issued`
(`date-parts`), `published`, `container-title`, `publisher`, `type`, `reference`,
`reference-count`, `is-referenced-by-count`, `license`, `abstract` (some records), `funder`.
`ORCID` appears as a URL such as `http://orcid.org/0000-...` and is normalised to the bare iD.

## Committed sample (`datasets/samples/crossref/crossref_works_sample.jsonl`)
One JSON object per DOI. Always: doi, retrieved_at, request_url, http_status, missing.
When found: title, year (from `issued`, falling back through published fields), year_field,
type, container_title, publisher, author_count, authors_with_orcid_count, reference_count,
is_referenced_by_count. No names, abstracts, affiliations or iDs are committed.

## Quirks observed
- Titles can contain markup; compare after stripping tags and unescaping.
- One of 100 DOIs (an arXiv DOI) returned 404.
- DBLP and Crossref years differ for 9 of 99 compared records (source: `datasets/samples/doi_spot_check.json`).
