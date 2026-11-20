# DBLP schema notes

Source: `datasets/raw/dblp/dblp.xml` with `dblp.dtd` (manifest: `datasets.dblp`). Parse only with
`lxml.etree.iterparse(load_dtd=True, resolve_entities=True, no_network=True, huge_tree=True)`;
the file uses DTD entities and several records share a line.

## Raw record shape (observed)
Root `<dblp>` holds top-level records: article, book, data, incollection, inproceedings,
mastersthesis, phdthesis, proceedings, www (counts in the manifest). Attributes seen: `key`,
`mdate`, optionally `publtype`. Child elements used here: `author` (repeated), `title` (may
contain inline markup, so join text), `year`, `journal` or `booktitle`, `ee` (repeated URLs, one
may be `https://doi.org/...`), `pages`, `url`. Encoding is ISO-8859-1. The file has no embedded
generation date.

## Selection TSV (`datasets/samples/dblp/dblp_selection_1000.tsv`)
Tab separated, header row, UTF-8, LF line endings.

| column | type | rule |
|---|---|---|
| key | string | DBLP key, unique within the file |
| type | enum | article or inproceedings |
| year | 4 digits | DBLP `year` element |
| venue | string | first two key segments (for example conf/acl, journals/tods) |
| doi | string | canonical DOI (datasets/scripts/doi_canonical.py): URL prefix removed, percent-decoded exactly once because it came from a doi.org URL, ASCII A-Z lower-cased, must match `10.` + 4-9 digits + `/` + non-whitespace, non-control characters; a `%` in a stored DOI is therefore a literal character, and a URL-form DOI that decodes to whitespace (for example `https://doi.org/10.1109/SWAT%20.1970`) is rejected |
| n_authors | int >= 2 | count of `author` elements |
| title | string | tags removed, whitespace collapsed, trailing period kept as DBLP writes it |

`dblp_selection_1000.provenance.json` records the seed (441), the script version, the input
SHA-256 and the output SHA-256. Since script 1.1.0 new provenance files also record the DTD
SHA-256, the cache fingerprint and the index-format and eligibility-policy versions. The seed and
size are recorded only there; they do not affect the eligibility index.

## Eligibility index cache (`datasets/raw/dblp/derived/`, gitignored)
Files: `eligible_index.tsv.gz` (eligible records), `eligible_index.summary.json` (observed counts)
and `eligible_index.meta.json` (validity record, written last). The metadata holds the XML and
DTD SHA-256, the XML size and mtime, the SHA-256 and size of the other two files, the versions
(metadata 1, index format 1, eligibility policy "2") and a fingerprint, which is the SHA-256 of
canonical JSON of the index-format version, policy version, XML SHA-256 and DTD SHA-256.
Bump the policy version whenever eligibility rules or DOI normalisation change.

Reuse rule (`index`, `select`, `all`): the cache is used only if the metadata is present and
well formed, all versions are supported and current, the fingerprint is consistent, both cached
files match their recorded checksums, and the DTD and XML match the recorded SHA-256 (full hash
by default; `select --fast-xml-identity` checks size and mtime only). Anything else is invalid:
`index` rebuilds, `select` exits 2 naming the reasons and writes nothing. `select` runs with
`need_inputs=False`: when the XML or DTD file is absent it proceeds on the verified cache alone
(the XML and DTD checks are then skipped). `--xml-sha256` is an expected digest. In `index`, `all`
and `adopt` it is checked against the actual file. In `select` it is compared with the metadata,
and reaches the file only through the default full hash; a mismatch exits 2 with no rebuild. New cache
files are written to temporary names and moved into place with `os.replace`, metadata last.
The existing cache (script 1.0.0, no metadata) was built under eligibility policy 1 and is invalid
under the current policy 2 (canonical DOI rule). `dblp_select.py adopt` refuses it (exit 2, nothing
written); adopt and the full XML hash were skipped by human decision, and the reindex is deferred
pending human approval. Three doi.org `<ee>` URLs in dblp.xml (`10.1109/SWAT%20.1970`,
`10.37789/icusi%202026.15`, `10.17706/jsw.16.4.%20182-199`) are newly rejected: reproduce with
`python3 -I -B datasets/scripts/renormalize_check.py --dblp-xml datasets/raw/dblp/dblp.xml`
(read-only, about 75 s, report-only exit 0; last run: 10,405,443 ee elements, 7,932,877 doi.org
ee URLs, 7,932,867 accepted by the old rule, 7,932,864 by the canonical rule, 3 newly rejected,
0 newly accepted, 0 accepted with a different value). The old-rule baseline in that script is the
recorded policy-1 rule from the pre-repair `dblp_select.py` (lines 87-91): `DOI_URL_RE =
re.compile(r"^https?://(?:dx\.)?doi\.org/(10\.[0-9]{4,9}/\S+)$", re.IGNORECASE)`; `m =
DOI_URL_RE.match(raw.strip())`; no match gives no DOI, else `unquote(m.group(1)).strip().lower()`
(shape checked on the still-encoded text before unquote, default `errors="replace"`). Observed
fact: no DOI in the 1K, 10K or 100K selection TSVs matches any of the three (exact match on the doi
column, 0 in each) or contains whitespace or `%20`, so none of the three is selected (direct
effect 0). The indirect effect through per-year counts
and per-venue square-root weights in the allocation is unquantified until the human-approved
reindex, for all of 1K, 10K and 100K. Supported selection
sizes are 1000, 10000, 50000 and 100000. Not implemented (deferred by human
decision): multi-generation publication with a metadata pointer, and interruption-recovery tests.

## Known quirks
- Author text can carry a numeric suffix such as `Manish Singh 0001` (DBLP disambiguation).
- Some records have no `author` element (editorial notes, reference entries).
- DBLP already contains records dated 2027.
- DBLP year can differ from the publisher's year (9 of 99 Crossref comparisons).
