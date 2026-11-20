# OpenAlex schema notes

Endpoint: `https://api.openalex.org/works/doi:{doi}` (DOI percent-encoded including `/`).
Envelope format is the same as Crossref (see crossref.md).

## Body fields observed
`id` (https://openalex.org/W...), `doi`, `title`, `display_name`, `publication_year`,
`publication_date`, `type`, `authorships` (list; each has `author` with `id`, `display_name`,
`orcid`, plus `institutions`, `raw_affiliation_strings`, position), `cited_by_count`,
`referenced_works` (list of work ids), `referenced_works_count`, `primary_location`,
`locations`, `open_access`, `topics`, `keywords`, `abstract_inverted_index`, `ids`,
`updated_date`, `created_date`.

## Committed sample (`datasets/samples/openalex/openalex_works_sample.jsonl`)
doi, retrieved_at, request_url, http_status, missing, and when found: openalex_id, title, year,
type, author_count, authors_with_orcid_count, authors_with_institution_count,
referenced_works_count, cited_by_count. No names, affiliations or iDs.

## Quirks observed
- 22 of 100 records have a `publication_year` different from DBLP's year (source: `datasets/samples/doi_spot_check.json`, `openalex_vs_dblp`; DBLP minus OpenAlex: 0 for 78, +1 for 16, and -1, -2, -5, -11, -21, -29 for one each).
- Author ORCID attachment is model-derived; the source docs say it can be wrong.
