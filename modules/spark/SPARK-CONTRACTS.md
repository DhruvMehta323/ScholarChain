# Spark analytics contracts (Phase 06, with repairs 01 and 02)

Owner: spark-engineer. Module `sparkAnalytics` (`modules/spark`), package `scholarchain.spark`. Depends on `core` only (it reads the
Hadoop and entity-resolution outputs as FILES; there is no module edge to `hadoop` or `entityResolution`). Spark SQL 4.1.1 is `Provided`
(Scala 2.13 artifacts through `CrossVersion.for3Use2_13`); everything is the DataFrame API with explicit `StructType` schemas
(Scala 3 cannot derive encoders). No GraphX, no GraphFrames, no new library, no UDF through `functions.udf` (the stable-ID UDFs are Java-style
`UDF1/2/5` classes in `Udfs.scala` that call `core`).

Layout (`src/main/scala/scholarchain/spark`): `Run` (main), `Compare` (main), `Pipeline` (wiring), `Inputs` (file edge), `Identity`, `Graph` and
`Metrics`, `Components`, `Communities`, `Bridges`, `KCore`, `ShortestPaths` (+ `Sampling`), `Temporal`, `Enrichment`, `Summaries`, `Export`,
`LoadManifests`, `Invariants`, `Report`, `RunMetrics`, `Udfs`, `Checkpointer`, `DriverCollect`, `Schemas` (input schemas and the table registry).

## 1. Running

Spark is `Provided`, so it is on the sbt Test classpath (forked JVM with the JDK 17 `--add-opens` flags), not on the Compile run classpath. Run
from the repository root. The forked JVM's working directory is `modules/spark`, so pass ABSOLUTE paths (`$PWD`):

```
sbt "sparkAnalytics/Test/runMain scholarchain.spark.Run --master local[*] \
  --output-root $PWD/modules/spark/target/runs/<new-root> --code-version \"$(git rev-parse --short HEAD)+phase06r2\" \
  --checkpoint-dir $PWD/modules/spark/target/checkpoints \
  --set input.root-i-dir=$PWD/modules/hadoop/target/runs/rootI/curated \
  --set input.hj6a-entity-views-dir=$PWD/modules/hadoop/target/runs/rootI/work/hj6a/entity-views \
  --set input.match-decisions-final-path=$PWD/modules/entity-resolution/target/p3/match-decisions-final.jsonl"

sbt "sparkAnalytics/Test/runMain scholarchain.spark.Compare <rootA> <rootB>"
```
(each on one line). `--set <key>=<value>` sets any `scholarchain.spark.<key>` (see `scholarchain-spark.v1.conf` in core). Precedence: environment
`SCHOLARCHAIN_SPARK_<KEY>`, then the arguments, then the versioned defaults. Exit codes of `Run`: 0 success, 1 job or invariant failure, 2 bad
arguments, bad configuration or an existing output root (a run NEVER overwrites; use a new root). `Compare`: 0 equal, 1 different, 2 usage.

`Compare` ignores RUN IDENTITY ONLY, so that runs with different code-version labels compare cleanly and a real data difference is still reported. The ignored set is explicit (`Compare.scala`):
the columns `runId` and `codeVersion` (today only `semantic-summaries.runId`, the one column derived from the run ID); inside every row-level `provenance` string the keys `runId` and `codeVersion`
(and any parameter named in `RunIdentityParameterKeys`, a parameter that carries only a label or a path: none exists today, paths are not parameters); in the JSON companions (`run-summary.json`,
`run-manifest.json`, the four load manifests, every `_provenance.json`) the keys `runId`, `codeVersion`, `outputRoot`, `configOrigins` at any depth. NOT ignored: input checksums and versions, algorithm
parameters, every data column. Run-level wall-clock numbers live only in `run-metrics.json`, which is never compared.
On EMR the same classes run through `spark-submit` (no `--master`).

## 2. Inputs (file edge, `Inputs.scala`)

Read as JSON Lines (gz or plain) with explicit schemas and `FAILFAST`; the nested Hadoop rows are flattened into the flat `InputSchemas`.

| Input | Path | Used for |
|---|---|---|
| `author-mentions` | `<root-i-dir>/author-mentions/` | base identities, ORCIDs, source type (`provenance.sourceType`) |
| `authorship` | `.../authorship/` | DBLP authorship (AUTHORED edges, nodes, team sizes) |
| `pair-candidates` | `.../pair-candidates/` | coauthor pairs (mention level) |
| `works`, `topics`, `institutions` | `.../works/` etc. | canonical works, enrichment, topic and institution nodes |
| `teams` | `.../teams/` | cross-check of canonical team sizes (informational) |
| `person-aliases` | `.../person-aliases/` | HJ1 alias groups; **absent in rootI** (no `www` records in the 1K selection): treated as EMPTY and listed in `absentOrEmptyTables` |
| `hj6a/entity-views` | `<hj6a-entity-views-dir>` | invariant: every decision endpoint is an entity view |
| `match-decisions-final` | `<match-decisions-final-path>` | researcher links, cannot-link evidence, work enrichment |

`exact-identifier-evidence` is NOT read: merging happens only through ACCEPTED researcher decisions (section 3). A table must have at most one
`run_id=` directory (otherwise the run is refused: it would double-count rows). Each table input is recorded as an `InputRef`
(`curated/<table>`, version `snapshot=<s>;run_id=<hex>`, checksum = SHA-256 over the sorted `<relative path>:<file sha256>` lines); the decisions
file is recorded by the SHA-256 of its bytes. Outcome wire names are lowercase: `accepted`, `rejected`, `unresolved`, `conflict`.
Only the Decisions summary (`byOutcome`) is trusted for outcome counts; HJ7's `ACCEPTED` counter (the known defect) is never read.

## 3. Identity (human decisions of the work order, section 4.1)

1. **Base identity.** All DBLP mentions with the EXACT same author string (homonym suffix included, no folding or trimming) form one base identity
   `Ids.researcherIdDblpPerson(name)`. HJ1 alias records join several strings into one base identity (the smallest string of the alias record names it;
   not transitive across records). Every non-DBLP mention (crossref, openalex) is its own base identity `Ids.researcherIdUnresolved(mentionId)`.
2. **Merging.** Base identities are linked ONLY by decisions with `entityKind = researcher` and `outcome = accepted`. Candidate merges are the connected
   components of those links (min-label propagation, the same loop as the graph components, capped by `connected-components-max-iterations`).
   `unresolved` and `conflict` never link. Identity comes from the decisions and the base keys only; `ResolvedMention` records are never read.
3. **M5 cannot-link (mandatory).** A component with two or more base identities is NOT merged when (a) any `rejected` or `conflict` researcher decision joins
   two of its base identities (mention endpoints are mapped to their base identities first, so a rejection against ANY mention of a person counts against the
   person), or (b) its base identities carry two or more different VALIDATED ORCIDs (`Orcid.parse`, shape and checksum; mention ORCIDs and alias ORCIDs), or
   (c) (repair M1 and repair 02, an implicit cannot-link, see 3.1) it holds two different DBLP names that are homonyms of each other and belong to DIFFERENT base identities.
   Its members fall back to their own base identities (`cannotLinkStatus = blocked`, rule `cannot_link_fallback`) and the component is written to
   `cannot-link-blocked` with its reason and counted. The reason is the reasons that apply, in this order, joined with `+`: `rejected_or_conflict_pair`,
   `two_orcids`, `distinct_dblp_homonyms` (so `rejected_or_conflict_pair+distinct_dblp_homonyms` is possible). Counters: `cannotLinkBlockedComponents` (total),
   `cannotLinkBlockedByReason` (one counter per reason; a component with two reasons is counted under both) and `cannotLinkBlockedUnsuffixedVsSuffixed` (3.1). Note N1 (Phase 05): two sides sharing ONE ORCID that
   disagree otherwise are caught only by (a), never by (b).
   - **3.1 Homonym rule (M1, all names, repair 02), exact.** The DBLP NAMES of a base identity are ALL of: the raw author strings of its DBLP mentions, its base name (the
     smallest string of its HJ1 alias record) and every alias string of the records that map to it (an alias string maps to the base of its record; not transitive).
     The STEM of a name is the name without ONE trailing homonym suffix, where a suffix is a single space followed by exactly four ASCII digits at the end
     (regular expression `^.+ [0-9]{4}$`, at least one character before the space; `Zhen Wang 0008` has stem `Zhen Wang`; `Zhen Wang`, `Wang 12`, `Wang 00008` and `Z. Wang` are their
     own stems; no trimming, case folding or normalisation). **A component is blocked (`distinct_dblp_homonyms`) when it holds two DIFFERENT DBLP names with the same stem that
     belong to DIFFERENT base identities.** Equivalently, per (component, stem): at least two distinct base identities and at least two distinct names. The rule is distributed (the
     names are exploded and grouped by (component, stem); nothing is collected). `distinctDblpHomonymGroups` in `cannot-link-blocked` is the number of such stems of the component.
     - **Alias exemption.** Names inside ONE alias group are ONE person (rule 4.1.1) and never block each other: `Zhen Wang 0008` aliased with `Zhen Wang` is one base identity and
       merges normally. But a name of an alias group is compared with the names of OTHER base identities: the alias record `["Zhen Wang 0008", "Z. Wang"]` joined (by an accepted link)
       with the mention `Zhen Wang 0009` IS blocked, because `Zhen Wang 0008` and `Zhen Wang 0009` share the stem `Zhen Wang` (repair 01 compared only the smallest alias string, `Z. Wang`, and
       let this merge through; that was R-M1). An alias group with no conflicting suffix still merges.
     - **Unsuffixed versus suffixed (confirmed by the human).** `X` and `X 0008` are a homonym pair (separate DBLP person profiles), so an accepted link joining them is blocked. This costs
       recall only. The counter `cannotLinkBlockedUnsuffixedVsSuffixed` (`unsuffixedVsSuffixedGroups` column of `cannot-link-blocked`) is the number of BLOCKED components with at least one
       homonym group that holds an unsuffixed name (the stem itself) and a suffixed name of two different base identities. It OVERLAPS the other reasons and counters: a component with
       another reason (rejected pair, two ORCIDs), or with a second suffixed homonym (`X`, `X 0008`, `X 0009`), is counted here as well, so it is "involves", not "blocked only because".
     - External (non-DBLP) mentions have no DBLP name and never trigger the rule; a component can still hold ONE homonym person plus external mentions (the usual absorption). Limit: only the
       shape of DBLP suffixes is used; two persons who merely share a name without a suffix are one base identity by 3.1 point 1 and are never split.
   - **3.2 Rejections inside one base identity (M2).** A `rejected` or `conflict` researcher decision whose two endpoints map to the SAME base identity is IGNORED for
     blocking (rule 4.1.1: a base identity is never split). It is counted (`withinBaseRejectionsIgnored`), listed in full in the table `within-base-rejections-ignored`
     (leftId, rightId, outcome, baseIdentityId, baseName) and as a bounded sorted sample (20 rows) in `run-summary.json` (`identity.withinBaseRejectionsIgnoredSample`).
     The M5 invariant excludes exactly these pairs (it only counts rejected or conflict pairs whose mentions have DIFFERENT base identities and share a researcher ID).
4. **Canonical researcher ID (repair decision b).** A cluster that contains DBLP persons gets `Ids.researcherIdForClusterV2(its DBLP person keys)`: the DBLP base ID
   `researcherIdDblpPerson(smallest key)` (code-point order). An unmerged DBLP person: its own base ID. ORCID is NEVER the canonical ID.
   A cluster WITHOUT a DBLP key is handled as before: a single external mention keeps `researcherIdUnresolved` (rule `unresolved_singleton`, not a graph node: no AUTHORED edge);
   several external mentions joined by accepted decisions (rare) get `researcherIdMergedCluster(base IDs)`.
   The cluster's DBLP person keys are listed in `identity-clusters.dblpPersonKeys` (ascending), so the ID is recomputable from the output.
   - **ORCID as property plus RESOLVES_TO.** Every validated ORCID of a cluster (mention and alias ORCIDs) is kept: `identity-clusters.orcids` and `researchers.orcids` (all, sorted),
     `orcid` (the single ORCID when exactly one, otherwise null). Each researcher in the `researchers` table gets one `RESOLVES_TO` row (table `resolves-to`, type
     `GraphEdgeTypes.ResolvesTo`) per ORCID: `sourceId` = researcher ID, `targetId` = `researcher:orcid:<orcid>`, `orcid`, `evidenceMentionIds` (mentions that carried it; empty
     when it came only from an alias record) and the row `provenance`. Every distinct target is a vertex of the table `external-identities` (repair 02, R-V1; section 4), so no `RESOLVES_TO` edge has a missing endpoint.
   - **Two ORCIDs in one cluster.** Merged clusters never have two (blocked by M5). A single base identity can (its own mentions disagree; it is never split): it keeps its DBLP ID,
     `orcid` is null, `orcids` lists both, and it gets TWO `RESOLVES_TO` rows. Counted: `clustersWithTwoOrMoreOrcids`.
   - **One ORCID in two clusters.** Not merged (merging is only by accepted decisions). Both clusters keep their own IDs and both get a `RESOLVES_TO` edge to the same target.
     Counted: `orcidsSharedByMultipleClusters` (distinct ORCIDs) and `clustersSharingAnOrcid` (clusters involved; this counts all clusters, including external singletons, which get no
     edge because they are not graph nodes). The old "ORCID-ID collision fallback" no longer exists (DBLP-based IDs cannot collide); `Result.orcidCollisions` and
     `Counts.orcidCollisionClusters` remain only as deprecated aliases of the shared-ORCID counters.
5. **Works.** The canonical work is the DBLP work (`work:dblp:...`). ACCEPTED work decisions with exactly one DBLP side attach the external work ID
   (`works.externalWorkIds`) and that work's topics (`has-topic`, `works.topicIds`) as ENRICHMENT only. Work thresholds are uncalibrated (Phase 05), so
   nothing else is derived from them. Institutions have no link to works or mentions in the Phase 04 tables, so they are nodes only (no affiliation edge).

## 4. Output tables

Root `<output-root>/scholarchain-graph/<table>/` (Parquet; every table also has `_provenance.json`, ignored by Parquet readers). Windowed tables carry
`window string, windowStart int, windowEnd int` as their FIRST columns and are partitioned by `window` (`window=2000-2004/`, `window=1000-9999/` = all-time);
read back through Spark the partition column `window` comes LAST and, with `spark.sql.sources.partitionColumnTypeInference.enabled=false` (set by
`Export.read`), stays a string. Every frame is `repartition(output-partitions)`ed before the write (so at most `output-partitions` files per window directory);
a run refuses an existing root. The declared schema is `Tables.all` (`Schemas.scala`); `Export.write` refuses a frame whose column names or types differ.
Types below: `S` string, `I` int, `L` long, `D` double, `B` boolean, `A<x>` array.

| Table (windowed = W) | Columns after the window columns | Notes |
|---|---|---|
| `identity-clusters` | researcherId S, memberMentionIds A<S> (strictly ascending), memberCount I, baseIdentityIds A<S>, dblpPersonKeys A<S>, displayName S, orcid S?, orcids A<S>, ruleFired S, cannotLinkStatus S (`clear`\|`blocked`), policyVersion S | `IdentityCluster` shape plus `dblpPersonKeys` and `orcids`; rules `dblp_person_exact`, `accepted_link_component`, `cannot_link_fallback`, `unresolved_singleton` |
| `cannot-link-blocked` | componentKey S, memberBaseIds A<S>, memberCount I, reason S, rejectedOrConflictPairs L, distinctOrcids I, distinctDblpHomonymGroups I, unsuffixedVsSuffixedGroups I | one row per blocked component; reason = `rejected_or_conflict_pair`, `two_orcids`, `distinct_dblp_homonyms` joined with `+`; the last two columns are the homonym stems of 3.1 (all of them, and those that pair an unsuffixed with a suffixed name) |
| `within-base-rejections-ignored` | leftId S, rightId S, outcome S, baseIdentityId S, baseName S | M2: rejected or conflict decisions inside one base identity (ignored for blocking) |
| `mention-researcher` | mentionId S, researcherId S, sourceType S, baseIdentityId S | every mention of every source, exactly once |
| `researchers` | researcherId S, displayName S, orcid S?, orcids A<S>, identityRule S, cannotLinkStatus S, memberMentionCount I, firstYear I, lastYear I | nodes: clusters with at least one authorship |
| `resolves-to` | edgeType S (`RESOLVES_TO`), sourceId S, targetId S (`researcher:orcid:<orcid>`), orcid S, evidenceMentionIds A<S>, provenance S | one row per (researcher, validated ORCID); `provenance` as below |
| `external-identities` | externalId S (`researcher:orcid:<orcid>`), kind S (`orcid`), value S (the validated ORCID) | vertex table (Neptune label `ExternalIdentity`): one row per DISTINCT `resolves-to.targetId`, derived from that table. Like the other node tables it has no row-level provenance column; its provenance is the table's `_provenance.json` |
| `works` | workId S, title S, year I, doi S?, venueId S?, topicIds A<S>, teamSize I, externalWorkIds A<S> | DBLP works only |
| `authored` | researcherId S, workId S, year I, position I (smallest), mentionCount I | AUTHORED edges (researcher to work); one row per (researcher, work) |
| `topics` / `institutions` / `venues` | topicId, label, linkedWorkCount L / institutionId, name, country / venueId, name (id minus `venue:<ns>:`), workCount L | node tables |
| `has-topic`, `published-in` | workId, topicId, viaWorkId / workId, venueId | enrichment and venue edges |
| `coauthor-edges` (W) | schemaVersion I, edgeId S, researcherA S, researcherB S (A < B), policyVersion S, jointPublicationCount I, firstCollaborationYear I, lastCollaborationYear I, representativeWorkIds A<S>, supportingWorksDigest S, supportingWorksTruncated B, evidenceTable S (the constant coauthor-edge-works), evidenceKey S (= edgeId), evidencePartition S (= window label), provenance S | EXACTLY the fields of core `CoauthorEdgeV2` (`windowStart`/`windowEnd` are the window columns; `window` is the partition column, not a record field); one row per record. `representativeWorkIds` = the first `representative-work-ids-bound` work IDs in sorted order; `supportingWorksDigest` = `Hashing.digest("scholarchain.coauthor-works.v1", all sorted work IDs)` |
| `coauthor-edge-works` (W) | edgeId S, workId S | the FULL evidence the edge's pointer references: one row per (edge, joint work); rows per edge = `jointPublicationCount`, and their sorted work IDs hash to `supportingWorksDigest` |
| `researcher-metrics` (W) | schemaVersion I, researcherId S, policyVersion S, publicationCount L (P_r), coauthorCount L (C_r), teamSizeTotal L (T_r), avgTeamSize D (A_r), maxTeamSize I (M_r), weightedDegree L (W_r), maxTeamWorkCount I, maxTeamWorkIds A<S>, communityId S, componentId S, coreness I?, crossCommunityDegree L, participationCoefficient D, communitiesTouched I, tieStrengthWeightedDegree D? (always null: not computed), provenance S | EXACTLY the fields of core `ResearcherMetricsV2`; bridges (`crossCommunityDegree`, `participationCoefficient`, `communitiesTouched` = `distinct_communities`), community, component and coreness are joined from their tables; W_r is the sum of `jointPublicationCount` (not tie strength); no composite score |
| `window-summary` (W) | nodeCount L, edgeCount L, workCount L, componentCount L, singletonCount L, largestComponentSize L, largestComponentFraction D, communityCount L, maxCoreness I, maxTeamSize I, maxTeamWorkId S | one row per configured window |
| `components` (W) / `component-membership` (W) | componentId S, size L / componentId S, researcherId S | `ComponentRecord` shape; id `component:<window>:<smallest member>` |
| `communities` (W) | communityId S, size L, method S, parameters M<S,S>, internalEdgeCount L, crossEdgeCount L | `CommunityRecord` shape; id `community:<window>:<smallest member>`; `crossEdgeCount` = edges with exactly one endpoint in the community |
| `community-membership` (W), `community-cross-edges` (W) | communityId S, researcherId S / communityA S, communityB S (A < B), edgeCount L | |
| `bridges` (W) | researcherId S, measure S, value D, parameters M<S,S> | `BridgeRecord` shape, long form: 3 rows per node (`cross_community_degree`, `participation_coefficient`, `distinct_communities`) |
| `coreness` (W) | researcherId S, coreness I | |
| `paths` (W) | startId S, endId S, hops A<S>, edgeIds A<S>, supportingWorkIds A<A<S>>, length I | `PathEvidence` shape (hops are researcher IDs; `edgeIds(i)` and `supportingWorkIds(i)` belong to the hop `hops(i) -> hops(i+1)`; the work list is the bounded `representativeWorkIds` of that edge) |
| `path-estimates` (W) | sourcesRequested I, sourcesUsed L, seed L, maxDepth I, reachedPairs L, unreachedWithinDepthPairs L, avgPathLength D?, effectiveDiameter90 I?, maxObservedDistance I? | an ESTIMATE from `sourcesUsed` sampled sources of the largest component; null when no pair |
| `temporal-changes` (W) | researcherId S, prevWindow S, status S (`both`\|`new`\|`dropped`), coauthorCount L, prevCoauthorCount L, retainedCoauthors L, newCoauthors L, lostCoauthors L, avgTeamSize D?, prevAvgTeamSize D?, maxTeamSize I?, prevMaxTeamSize I?, componentSize L?, prevComponentSize L?, communitySize L?, prevCommunitySize L?, crossCommunityDegree L?, prevCrossCommunityDegree L? | consecutive EXPLICIT windows only (`window` is the later one) |
| `semantic-summaries` (W) | entityId S, documentType S, textHash S, text S, runId S | `SemanticSummary` shape; `textHash = Hashing.textHash(text)` |
| `algorithm-status` | algorithm S, scope S, iterations I, converged B, iterationCap I | convergence of every iterative algorithm of the run |

**Row-level `provenance` (v2 tables and `resolves-to`): a JSON STRING column (accepted by the human in repair 02).**
- Column name `provenance`, Parquet/Spark type `string` (`Tables.Provenance.ddl`), the LAST column of `coauthor-edges`, `researcher-metrics` and `resolves-to`.
- Content: the compact wire JSON of a core `Provenance` (`Wire.write`: `schemaVersion` 1, `sourceType` `scholarchain`, `inputs` with their versions and checksums, `codeVersion`, `parameters`, `runId`; no `retrievedAt`).
  It is built once per run (`V2Export.provenanceJson`, validated by `Wire.write`) and is the same text on every row of the run.
- Why a string and not a struct: core `Provenance.parameters` is a map, and Spark cannot run set operations (`exceptAll`, `distinct`) on a struct that contains a MAP column. `Compare` and the determinism
  checks need `exceptAll`. A struct with `array<struct<key,value>>` parameters would have changed the record shape; the string keeps the exact core wire form. The one deliberate difference between a table row
  and the core record type is therefore this column's Parquet type.
- How to parse it: `Wire.read[Provenance](row.provenance)` (core `Wire`, in Scala), or any JSON parser. To read a whole row as the core record, replace the string by its parsed JSON object and read the row as
  `CoauthorEdgeV2` or `ResearcherMetricsV2`: `V2Export.inlineProvenance(rowJson)` does exactly that, and the output invariants run it with `Wire.read` on the executors over every row (section 10).
- `resolves-to` is NOT the core `GraphEdge` (that record needs `evidenceIds` of `EvidenceItem`s, which Phase 06 does not produce).
- Run-identity fields inside the string (`runId`, `codeVersion`) are ignored by `Compare` (section 1, the ignored set).

Run-level files at the root: `run-manifest.json`, `run-summary.json`, `run-metrics.json`, `load-manifests/*.json` (sections 8 and 9).

## 5. Algorithms (all DataFrame, one loop for all windows at once; frames carry a `window` string key)

Common frames: `nodes(window, id)` and `arcs(window, src, dst)` (both directions of every edge). Every iteration is checkpointed (section 7).

- **Connected components** (`Components.labels`): iterative min-label propagation. Every node starts with its own ID; each round a node takes the minimum
  of its own and its neighbours' labels. Fixed point: every node carries the smallest node ID of its component. Rounds are bounded by the largest component
  diameter + 1. Convergence check: the number of changed labels is 0 (a count). Cap: `connected-components-max-iterations` (50); on the cap `converged = false`
  (recorded in `algorithm-status`; the run's invariant `components_and_kcore_converged` then fails). Weakness: round count grows with the diameter (long chains are slow).
- **Communities** (`Communities.labelPropagation`, method `label_propagation.v1`): deterministic label propagation. Every node starts with its own ID. In round `i`
  only nodes whose parity class `pmod(xxhash64(id), 2)` equals `i mod 2` update; an updating node adopts the label with the most votes, ties to the SMALLEST
  label, where the votes are its neighbours' labels plus ONE vote for its own label (so two adjacent nodes agree instead of swapping; an isolated node keeps its
  label). Stops after two consecutive rounds without a change (one per parity class) or at `label-propagation-max-iterations` (20); `converged` is false when the cap
  stopped it. Afterwards every label class is renamed to its smallest member ID. Known weaknesses: a heuristic local optimum, not a modularity optimum; the result
  depends on the schedule (deterministic here, but not canonical); label flooding can merge loosely linked groups; a large team (one paper of 42 authors is a
  42-clique) pulls its members into one community and hides internal structure; the parity classes are a hash split, not a graph colouring, so adjacent nodes
  of one class still update simultaneously (the self-vote removes the swap case but convergence is not guaranteed); no resolution parameter; no overlap;
  community IDs are not comparable across windows. Singleton communities are isolated researchers.
- **K-core** (`KCore.coreness`): batch peeling per window. Level `k` starts at 0; each round compute degrees in the remaining graph, raise `k` to `max(k,
  min degree)`, remove every node of degree <= `k` with `coreness = k`. This gives the standard core number. Isolated nodes get 0. Cap `k-core-max-iterations`
  (50 rounds); on the cap the remaining nodes get the current level (a lower bound) and `converged = false`.
- **Shortest paths** (`ShortestPaths.bfs`, `.evidence`): bounded BFS by iterative joins from SELECTED sources only (never all-pairs): per depth a join of the frontier with
  the arcs, an anti-join against visited, and a min-parent aggregation (so the chosen parent, hence the path, is deterministic: the smallest parent ID wins).
  Depth cap `bfs-max-depth` (6); nodes farther than the cap are not visited (`converged = false` means the cap was reached while the frontier was non-empty).
  Path evidence for `path-sample-pairs` (20) pairs per window: members of the window's LARGEST component (ties: smallest component ID) ranked by
  `xxhash64(seed, id)` (seed `path-sample-seed`), paired consecutively; hops are rebuilt by walking the parent chain; a pair without a path within the cap yields no row.
- **Average path length and effective diameter** (`ShortestPaths.estimate`): BFS from `avg-path-sources-k` (50) sources of the largest component of each window
  (seed `avg-path-seed`; fewer when the component is smaller; the sample is the `k` smallest `xxhash64(seed, id)`). `avgPathLength` = sum of distances / reached
  (source, node) pairs (source excluded); `effectiveDiameter90` = smallest d with at least 90% of the reached pairs within d hops (integer, no interpolation).
  It is an ESTIMATE: it reports `sourcesUsed` and `seed`; pairs missing because of the depth cap are counted in `unreachedWithinDepthPairs` (a downward bias).
  It describes the largest component only, not the graph.
- **Bridge measures** (`Bridges.measures`), per researcher `i` and window, with `k_i` = distinct coauthors and `k_ic` = coauthors in community `c`:
  - `cross_community_degree` = `k_i - k_i,own` (coauthors outside i's community).
  - `participation_coefficient` P_i = `1 - sum_c (k_ic / k_i)^2` (computed as `1 - (sum_c k_ic^2) / k_i^2` in exact integer arithmetic, one final division); in
    [0, 1); defined as 0 when `k_i = 0` or when all coauthors share one community.
  - `distinct_communities` = number of distinct communities among i's coauthors plus i's own (1 for an isolated researcher).
  Approximate betweenness was NOT computed (the optional measure was not done); `BridgeMeasure.ApproxBetweenness` is unused.
  Bridge values inherit every weakness of the communities they are computed from.

## 6. Time windows

Windows come from config (`windows`, default `2000-2004,...,2020-2024`, plus the all-time window `1000-9999` when `include-all-time`). Inclusion rule: a work belongs
to window `[start, end]` (inclusive) iff its publication year `y` satisfies `start <= y <= end`. Explicit windows never overlap, so a work is in at most one explicit
window; the all-time window contains every work. A researcher is a node of a window iff they authored at least one work in it; an edge needs a work in the window that both
authors share; component, community, bridge, coreness and path results are computed per window. Identity (merging) is global and not redone per window. Real DBLP work years
span 1938 to 2027, so works before the first window or after the last appear ONLY in the all-time graph (the run summary reports `workYears` with these counts; the defaults
were kept on purpose).

Limits when windows are compared (`temporal-changes`): windows are independent graphs (no edge links two windows); a window with fewer works has smaller degrees for that
reason alone (counts are not normalized by activity); a researcher absent from a window may simply not have published there (`status` `new` or `dropped` is about
publication, not about career); component and community IDs embed the window, so windows are compared by SIZE, not by identity; community detection is per window and
unstable across windows; the all-time window is never compared with the others; a merge can add history to a researcher in every window.

## 7. Shuffle boundaries, partitions and checkpointing

`spark.sql.shuffle.partitions = shuffle-partitions` (8), adaptive execution OFF in `Run` (so partition counts are reproducible; turn it on in production if wanted),
output files per window directory <= `output-partitions` (4). Small tables are broadcast (`windows`, the alias map). Shuffles, in order: (1) identity: the group-by of mentions
per base identity, the two decision joins, the component loop (2 shuffles per round: join with the arcs, group-by min), the M5 joins and group-bys, the cluster group-by and one
window function partitioned by the candidate researcher ID; (2) authorship x mention map join and the (researcher, work) group-by; (3) the pair joins (mention to researcher, twice)
and the group-by that builds edges per (window, A, B); (4) metrics: group-bys and joins on (window, researcher); (5) components, communities, k-core and BFS: per round 2 to 4
shuffles; (6) path evidence: joins on (window, start, end); (7) summaries and temporal: joins on (window, researcher). **Skew:** any shuffle keyed only by `window`
(minimum degree per window, window summary aggregations) has at most as many busy partitions as windows (6 by default), but those results are tiny; a researcher with a very large
team is a hot key in the edge group-by only through its (window, A, B) keys, which stay small. The top-n samples (`Sampling`) use one `orderBy ... limit` per window, never a full sort
of the population on one task. Checkpoints (reliable, eager, directory `checkpoint-dir`, under `modules/spark/target/`): every iteration of the component, community, k-core and BFS loops
(truncating lineage) and every frame that is reused (mention-researcher map, authored, teams, pairs, edges, arcs, nodes, metrics, memberships, coreness, paths, links, researchers, ...).
Run totals (stages, tasks, shuffle read and write bytes) are written to `run-metrics.json` by a `SparkListener`. The checkpoint directory is not cleaned by the job.

**Driver collection rule.** No full table, edge list or node list is ever brought to the driver. Allowed: counts, convergence counts, one small aggregate row, and report statistics from
aggregated or `limit`ed frames. Every collection goes through `DriverCollect` (`count`, `isEmpty`, `longValue`, `rows(df, maxRows <= 1000)`); no other source file contains `.collect()`,
`.collectAsList()`, `.toLocalIterator()`, `.take(`, `.head(` or `.first()` on a frame (a test can scan the sources for these tokens outside `DriverCollect.scala`).
The input checksums are computed from the file system, not from Spark.

## 8. Load manifests (`<output-root>/load-manifests/`)

JSON, no timestamps, paths relative to the output root. Every file: `manifestVersion` (1), `target`, `runId`, `codeVersion`, `root` (`scholarchain-graph`). A table reference has
`table`, `path` (`scholarchain-graph/<table>`), `format` (`parquet`), `partitionColumns`, `rowCount` (observed by reading the written table back).
- `neptune.json`: `vertices` (a table reference + `label`, `idColumn`, `windowed`, `propertyColumns`) for researchers, external-identities (label `ExternalIdentity`, the `researcher:orcid:*` targets of `RESOLVES_TO`), works, topics, institutions, venues, communities, components;
  `edges` (+ `label`, `idColumn` or null, `keyColumns`, `fromColumn`, `toColumn`, `directed`, `windowed`, `propertyColumns`) for `AUTHORED`, `COAUTHORED_WITH` (undirected, `edgeId`), `HAS_TOPIC`,
  `PUBLISHED_IN`, `MEMBER_OF`, `IN_COMPONENT`, `COMMUNITY_LINK`, `RESOLVES_TO` (directed, `sourceId` to `targetId`, `resolves-to`). The Neptune researcher vertex has the property columns `orcid` and `orcids`.
- `dynamodb.json`: `tables` (table reference + `partitionKey`, `sortKey` list) for researcher-metrics, coauthor-edge-works (partition key `edgeId`, sort key `workId`), bridges, coreness, temporal-changes, paths, path-estimates, window-summary, identity-clusters, mention-researcher.
- `s3-vectors.json`: `documents`: the `semantic-summaries` table with `keyColumns` (entityId, window, documentType), `textColumn`, `textHashColumn`, `metadataColumns`; embeddings are NOT computed here.
- `s3.json`: `tables`: every table with `keyPrefix`, `partFiles`, `provenance` (the `_provenance.json` key); `companionFiles`: the run manifest, summary and metrics.

## 9. Run manifest, summary, metrics and provenance

- `run-manifest.json`: `runManifest` (core `RunManifest`: `runId` = `Ids.runId("spark-analytics", codeVersion, 1, parameters, inputs)`, `stage`, `codeVersion`, `attempt`, `parameters`, `inputs` with checksums,
  no `createdAt`), plus `outputRoot`, `configOrigins` (origin of each key, never values of secrets: there are none), `absentOrEmptyInputTables`, `inputRowCounts`, `tables` (rows and part files),
  `algorithms` (convergence), `invariants`, `observations`. The run ID is content-derived: the parameters are the algorithm settings (paths are NOT parameters), the inputs carry checksums, the code version is
  the label given on the command line (a hand-chosen string such as `<HEAD short>+phase06r1`, not a content address; use a new label for each source change).
- `run-summary.json`: the observed statistics of the run (input row counts, identity counts, window summaries, community size distribution, top 10 by coauthor count, path results and estimates,
  coreness and bridge distributions, algorithm convergence, partition counts, table row counts, invariants). Small aggregates only; no wall-clock values.
- `run-metrics.json`: jobs, stages, tasks, shuffle read and write bytes, per-stage durations (wall-clock; a measurement, excluded from the compare tool).
- `scholarchain-graph/<table>/_provenance.json`: table, schema, rowCount, partition columns, runId, codeVersion, inputs (with checksums), parameters.

## 10. Output invariants (checked on every run; a failing one gives exit 1)

DBLP authorship rows all mapped; clusters cover all mentions exactly once; researcher IDs unique and valid; AUTHORED rows point at existing researchers and works; edge endpoints are window nodes;
edges are canonical (A < B, no self-loop) with unique valid IDs; sum of coauthorCount = 2 x edges; sum of weightedDegree = 2 x sum of jointPublicationCount; all-time joint publications = distinct
(pair, work) rows; every pair candidate maps to two researchers; components, communities and coreness cover every node exactly once; coreness <= max degree; 3 bridge rows per node; every path is
consistent (hops, edge IDs, work lists, length <= depth, endpoints); M5: no REJECTED or CONFLICT decision between two DIFFERENT base identities has both mentions in one cluster, recomputed from
the output (decisions inside one base identity are excluded: section 3.2); every decision endpoint is an entity view and a known mention; components, identity components and k-core converged.
Added by repair 01: the canonical ID of every cluster with a DBLP person equals `researcherIdForClusterV2(dblpPersonKeys)`; single external mentions keep their base ID; no canonical ID is an ORCID ID;
no cluster holds two different DBLP keys with the same homonym stem (M1); **every exported `coauthor-edges` row validates as `CoauthorEdgeV2` and every `researcher-metrics` row as
`ResearcherMetricsV2`** (`Wire.read` on the executors over the whole table; the driver only counts the failing rows and reads at most 3 messages); no metric row lacks its community, component or
bridge columns; `coauthor-edge-works` rows = sum of `jointPublicationCount`, unique per (edge, work), and for every edge the row count equals `jointPublicationCount` and the sorted work IDs
hash to `supportingWorksDigest`; `resolves-to` rows = sum of the researchers' ORCID counts, every source is a researcher, every target a valid `researcher:orcid:` reference.
Repair 02: the homonym invariant uses the all-names rule of 3.1 (recomputed from the inputs and the output mapping, `Identity.dblpNames` + `Identity.homonymGroups`); `external-identities` has one row per distinct
`resolves-to` target, unique and well-formed IDs; **no dangling edge endpoint in ANY edge table**: for each of `authored`, `coauthor-edges`, `resolves-to`, `has-topic`, `published-in`, `community-membership`,
`component-membership` and `community-cross-edges` (the list `EdgeLinks.all`), both endpoint columns are anti-joined (distributed, counts only) with the vertex table they must be in (the Neptune vertex sets; windowed
vertex tables on (window, ID)), one invariant `edge_endpoints_exist_<table>` per edge table, whose detail lists both counts (expected 0 and 0).
The former `identity_orcid_collisions_resolved` check (constant `true`) is removed. Informational (not pass or fail, in `observations` and the run summary): self-pairs dropped after a merge,
within-base rejections ignored, shared ORCIDs, clusters with two or more ORCIDs, works whose canonical team size differs from the Hadoop teams table.

## 11. Public API (what tests call; all pure `DataFrame => DataFrame` with a shared session, no file I/O)

All functions register the UDFs themselves (`Udfs.register(spark)`, idempotent). `cfg` is `scholarchain.core.SparkConfig` (use `SparkConfigLoader.resolve` or `load` with overrides). See the
class comments for exact column lists; schemas of the inputs are `InputSchemas.*` (`Schemas.scala`).
`Identity.build(mentions, aliases, decisions, policyVersion, cfg): Identity.Result`; `Identity.counts(Result): Identity.Counts`; `Graph.windowsFrame(spark, cfg)`, `Graph.authored(authorship, mentionResearcher)`,
`Graph.workTeams(authored)`, `Graph.nodes(authored, windows)`, `Graph.mappedPairs(pairs, mentionResearcher)`, `Graph.canonicalPairs(mapped)`, `Graph.edges(pairs, windows, cfg)`, `Graph.arcs(edges)`;
`Metrics.researcherMetrics(authored, teams, edges, windows, cfg)`; `Components.labels(nodes, arcs, maxIter, ck)` + `.membership` + `.sizes`; `Communities.labelPropagation(nodes, arcs, maxIter, ck)` +
`.membership` + `.records` + `.crossEdges`; `Bridges.measures(membership, arcs, parameters)`; `KCore.coreness(nodes, arcs, maxRounds, ck)`; `ShortestPaths.bfs(arcs, sources, maxDepth, ck)`, `.evidence(visited, pairs, edges, maxDepth)`,
`.estimate(...)`; `Sampling.smallestHash`, `.largestComponentMembers`, `.samplePairs`; `Temporal.changes(...)`; `Enrichment.*`; `Summaries.*`; `Export.write/read`, `LoadManifests.build`, `Invariants.check`, `Report.build`.
`Checkpointer(dir)` is the checkpoint helper (the directory is set once per SparkContext).

## 12. Known limits

1K sample only (10K and 50K are out of scope); the bounded EMR run is Phase 15 and was NOT run. A hand-set code-version label. Real DBLP works sample is sparse: most researchers have a single work, so components
are the paper cliques and the largest component is one 42-author paper, which makes shortest paths trivially short; this is a property of the sample, not of the algorithm. Work decisions are uncalibrated, only
enrichment uses them. No affiliation edges (no affiliation link in Phase 04). Venue names are the DBLP venue key (no venue table exists). Label propagation is a heuristic (section 5). The semi-structured
`run-summary.json` is an extra artifact of this module (not a core contract).
