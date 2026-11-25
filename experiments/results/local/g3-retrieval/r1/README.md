# Group 3 retrieval (local only)

Evidence class R, restricted: the only label-backed local retrieval run on disk is the Phase 08 smoke set (`modules/services/target/embedding-runs/L1..L4/smoke.json`, 25 known-item queries each, embedder `local-hash-bow`, dimension 1024, only the top 5 keys stored). K is capped at 5. Queries are built from tokens that occur in the target document and in no other document of its type (the `why` field of each smoke entry), so these are lexical known-item lookups, not relevance-judged retrieval. They say nothing about semantic quality, graph-only or fused retrieval. Graph-only, real-embedder vector-only, fused and OpenSearch comparisons are `[[PENDING-AWS]]` (Phase 15c/16).

Phase 09 benchmark identity resolution is reported in group 4, not here.
