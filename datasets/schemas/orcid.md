# ORCID schema notes

Endpoint: `https://pub.orcid.org/v3.0/{orcid-id}/works` with `Accept: application/json`.
Only the works section is requested, to keep personal data to the minimum needed for linking.

## Derivation of iDs
Taken from stored Crossref `message.author[].ORCID` and OpenAlex
`authorships[].author.orcid` for the selected DOIs, normalised to the bare iD, validated against
`^[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9X]$`, deduplicated, ranked by sha256(441|orcid|id).
`datasets/raw/orcid/orcid_ids.json` (gitignored) lists every found iD with the DOIs it came from.

## Body fields observed
`last-modified-date`, `group` (list; each group has `external-ids` and `work-summary`), `path`
(contains the iD). Records with no public works have an empty `group`.

## Committed output
`datasets/samples/orcid/orcid_coverage_summary.json`: aggregate counts only. No iD, name or
record-level data is committed (see manifest `privacy`).

## Quirks observed
- 24 of 100 retrieved records have no public works.
- Unauthenticated requests succeeded although the documentation requires a token.
