Use the dataset-engineer and domain-engineer, in separate delegated work orders, to create the ScholarChain data contracts and dataset manifest.

Dataset-engineer work:
- Create datasets/dataset-manifest.yaml for DBLP and every selected enrichment dataset.
- Include source, license, snapshot, format, size estimate, S3 prefixes, checksums, subset rule, schema, linkage keys, update method, limitations, privacy notes, and validation commands.
- Create bounded download and sample-generation scripts under datasets/scripts.
- Create representative legal sample files under datasets/samples, small enough for the repository.
- Create schema notes under datasets/schemas.
- Scripts must be idempotent and must not download a full corpus by default.

Domain-engineer work:
- Create immutable Scala domain records for WorkRecord, AuthorMention, CanonicalResearcher, AuthorshipRecord, PublicationTeam, CoauthorPairCandidate, CoauthorEdge, ResearcherMetrics, Venue, Institution, Topic, Provenance, MatchDecision, GraphNode, GraphEdge, EvidenceItem, EvidenceBundle, QueryPlan, AnswerClaim, and AnswerResponse.
- Define stable researcher, work, edge, evidence, and run ID functions.
- Define enums for source type, match outcome, evidence type, collaboration metric, query operation, and verification status.
- Define a configuration loader using environment variables plus versioned HOCON or YAML files. Do not hardcode bucket names, endpoints, thresholds, model IDs, AWS account IDs, graph windows, or bridge-algorithm parameters.
- Create serialization and schema-version tests.
- Freeze representative JSON wire examples in contract tests.
- Add validation that rejects missing provenance, invalid confidence ranges, unsupported schema versions, malformed stable IDs, invalid undirected pair ordering, and time windows where start year exceeds end year.

Run sbt check.
Report sample serialized records, stable ID formulas, pair canonicalization rule, configuration precedence, schema versions, and known unresolved dataset questions.

Required stable ID principles:
- work ID: namespace plus authoritative source ID when available
- researcher ID: canonical external identifier when validated, otherwise a versioned ScholarChain identity ID
- coauthor edge ID: hash of canonical ordered researcher IDs plus graph-window and policy version
- chunk or profile ID: hash of entity ID, source version, document type, ordinal when needed, and text hash
- graph edge ID: hash of edge type, canonical endpoints, policy version, and source evidence
- run ID: timestamp-independent unique identifier stored with an immutable run manifest

ScholarChain-specific constraints (added by the human architect):

Datasets
- Selected datasets: DBLP (mandatory seed), OpenAlex, Crossref, and ORCID. Every enrichment dataset is a bounded, DOI-driven subset linked to selected DBLP works, not a full snapshot. The course instructor has approved bounded DOI-driven subsets of OpenAlex, Crossref and ORCID provided that the selection criteria, retrieval procedure, matching rules, actual coverage, source information, and missing or inconsistent data are documented. Record this in the manifest.
- DBLP is already downloaded locally at datasets/raw/dblp/dblp.xml and datasets/raw/dblp/dblp.dtd (gitignored). Record its observed size and SHA-256 checksum, and derive the snapshot date from the file or from the DBLP release information rather than guessing. Parse it only with an entity-resolving XML parser (python3 lxml iterparse with load_dtd=True, resolve_entities=True, no_network=True, huge_tree=True), because the file uses DTD entities and records can share lines.
- DBLP selection policy for later scales: a documented, reproducible, seeded selection over article and inproceedings records with a valid key, a non-empty title, a numeric year, at least two authors and a DOI, excluding dblpnote records and duplicate keys, stratified across publication years and venues. The scripts must support target sizes of 1,000, 10,000 and 100,000 works. Do not take "the first N eligible records".
- Enrichment retrieval scripts (OpenAlex, Crossref, ORCID) must call the official public APIs, encode DOIs safely, pause between requests, retry with backoff on timeouts, HTTP 429 and 5xx, record 404s as missing, resume without re-downloading existing responses, and store every raw response with its retrieval timestamp and request URL. Contact email and any API key come from environment variables only (for example SCHOLARCHAIN_CONTACT_EMAIL, OPENALEX_API_KEY); never hardcode or commit them. Verify current access and authentication requirements from the official documentation of each API and record them in the manifest.
- Default script limits must be small (at most 100 DOIs) and the scripts must refuse to fetch more without an explicit flag. Run each retrieval script once at its default small limit to produce the committed samples and to report actual coverage counts. Do not run any larger fetch in this phase.
- Licenses: record each dataset's license or reuse terms only from its official source page, with the URL. If a license cannot be verified, write UNVERIFIED rather than a guess.
- Privacy: ORCID and author records contain personal data. Record the handling rules in the manifest.
- S3 prefixes are placeholders built from configuration (for example s3://${RAW_BUCKET}/dblp/snapshot_date=YYYY-MM-DD/); do not invent real bucket names.
- The existing files datasets/samples/sample_dlbp.py, analyze_dblp.py, crossref_db_script.py, dblp_100.csv and dblp_1000.csv are human-authored, pre-agent exploratory artifacts. Do not modify or delete them. Record them in the manifest as exploratory and non-graded, and do not use them as the reproducible selection.
- Scripts use python3 with the standard library and lxml. If manifest validation needs a YAML parser that is not installed, report that as MANUAL ACTION REQUIRED instead of installing packages.
- Provide a manifest validation command (for example python3 datasets/scripts/validate_manifest.py) and run it.

Contracts and configuration
- Follow the ownership map in docs/agents.md. If contract, serialization or schema-version tests belong to test-engineer's test territory, domain-engineer specifies the cases and the orchestrator dispatches test-engineer to write them. If the configuration loader needs a library that is not yet in the build, return BLOCKED-ON; the orchestrator routes the version value to dependency-updater and the dependency reference to build-engineer.
- Use uPickle for JSON and MUnit for tests, as pinned in Phase 02.
- Every record carries a schema version and provenance. Pair canonicalization must be deterministic (for example lexicographic ordering of researcher IDs) and tested.
- DOI handling: normalize DOIs (case, whitespace, and the https://doi.org/, http://dx.doi.org/ and doi: prefixes) and reject malformed DOIs.
- Researcher identity contracts must carry provenance and uncertainty (match confidence and outcome), so that later stages can keep uncertain identities uncertain.

Contract tests (test-engineer, in its assigned test paths)
- Write meaningful contract tests covering at least: stable identifier generation, DOI normalization, DOI parsing and invalid identifiers, serialization and deserialization round trips, frozen JSON wire examples, schema-version rejection, publication-year and time-window validation, ordered coauthor pairs (including self-pair rejection), optional fields, identity uncertainty, confidence-range validation, missing-provenance rejection, and configuration validation and precedence.
- Every test must check real contract behavior. A test that only asserts a constant or a non-null value does not count.
- Show at least one mutation proof: temporarily break one expectation and show that the test fails, then restore it.

DOI spot-check
- Check three distinct DOIs that appear both in the selected DBLP records and in the retrieved Crossref samples: compare title, year and author count, and report each comparison. Do not claim the spot-check passed unless three distinct matches were actually inspected. If fewer matches exist, report the exact number and the limitation.

Verification and evidence
- In addition to sbt check, run sbt jdkInfo and include its output, to show that the forked test JVM is Temurin 17.
- After the implementing agents report COMPLETE, dispatch code-reviewer for a read-only review of the full Phase 03 diff. The reviewer must specifically verify that no dataset license, identifier, snapshot date, coverage number or source is invented. Route REQUEST_CHANGES findings to the owning agents, then re-review. Stop for human ratification when code-reviewer returns APPROVE or APPROVE WITH NOTES.
- Save the implementing agents' final reports verbatim, one after another under clear headings, as docs/agent-runs/phase-03-contracts-datasets/agent-report.md, and code-reviewer's final verdict verbatim as docs/agent-runs/phase-03-contracts-datasets/review-report.md.
- Do not create Hadoop, Spark, storage, service, Terraform or deployment code. Do not create paid AWS resources. Do not modify the ratified Phase 01 constitutional files. Do not commit.

Human gate report
Stop after implementation, validation and code review, and report:
1. Created or modified files, grouped by owning agent.
2. Test and validation commands with their observed results and exit codes.
3. The manifest path and source coverage (retrieved versus planned, with actual counts).
4. Actual DOI spot-check and license verification outcomes.
5. Blocking findings and the repairs made.
6. Unresolved risks.
7. Decisions that need human approval.
Do not mark Phase 03 as accepted. The student reviews the evidence and writes the human gate before Phase 04.

