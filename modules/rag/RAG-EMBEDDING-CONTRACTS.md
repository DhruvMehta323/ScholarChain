# RAG embedding contracts (Phase 08, rag-engineer)

Package `scholarchain.rag`, module `modules/rag`. `rag` depends on `core` only: no storage type, no Spark, no Parquet. It consumes the core contracts `EmbeddingDocument`, `EmbeddingDocumentTypes`, `SemanticContextItem`, `Embedder`, `EmbeddingError`, `EmbeddingSettings`, `SemanticSummary`, `WorkRecord`, `Venue`, `Topic`, `VectorStore`, `VectorHit`. No AWS, Voyage or LLM call is made anywhere in this phase.

## 1. Rendering (`EmbeddingDocumentRenderer`)

| Document type | Source | Text |
|---|---|---|
| `researcher_profile`, `community_profile`, `path_summary`, `topic_summary` | Phase 06 `SemanticSummary` | `SemanticSummary.text` verbatim (chunked only when longer than the policy) |
| `paper_abstract` | `WorkRecord` + its `Venue` + its `Topic` labels | Lines `Paper: <workId>`, `Title: <title>`, `Year: <year>`, `Venue: <name>`, `Topics: <labels sorted, ; separated>`, `DOI: <doi>`; a line is omitted when its field is absent |

**Honest naming of `paper_abstract`.** Phase 06 has titles and no abstracts. No abstract is fabricated. The `paper_abstract` index holds title-level metadata text under the frozen document-type name. Its window is all-time (`GraphWindows.AllTime`).

**No invented numbers.** Summary text is copied verbatim. Paper templates contain no numeric literal. `NumberCheck.unsupportedNumbers(rendered, source)` extracts numbers (`\d+(\.\d+)?`) and returns those of the rendered text that are not numbers of the source row. `RenderedDocument.sourceRow` is the joined source field values (summary: entityId, window label, text; paper: workId, title, year, venue name, topic labels, DOI). `NumberCheck.check(rd)` is `Right(())` or `Left(offending numbers)`.

**Determinism and order.** No clock, no randomness. Output is sorted by (type order `researcher_profile, community_profile, path_summary, topic_summary, paper_abstract`, entityId, window start, window end, text by `String` ordering, chunk ordinal). Duplicate (type, entity, window): identical text is dropped; with different text, the candidate with the smallest text (String ordering) is KEPT and each other one is recorded in `RenderResult.skipped` as `duplicate-key-different-text`. The kept document therefore never depends on input order. Unknown type, blank text, failed chunking and an invalid `EmbeddingDocument` are skipped with a fixed reason in `RenderResult.skipped` (never row content).

**Text pointer.** `textUriFor(textHash)`; `TextUris.contentAddressed(bucket, prefix = "rag/text")` gives `s3://<bucket>/<prefix>/<sha256>.txt` (the A1 example form). The bucket must be a valid S3 bucket name (3 to 63 characters), otherwise every document fails the core contract and is skipped as `invalid-document`.

## 2. Chunking (`Chunker`, `ChunkPolicy`)

Unit: UTF-16 characters, not tokens. Default `ChunkPolicy(maxChars = 2000, overlapChars = 200)`; valid `maxChars` 200..20,000, `overlapChars` 0 until `maxChars/2`.

1. Text of at most `maxChars` is one chunk, verbatim: ordinal 0, count 1.
2. Otherwise chunk k covers `[start, end)` with `end = min(start + maxChars, length)`; when not at the end of text it moves back to the nearest whitespace boundary (cut right before or after a whitespace character) in the second half of the window, so no token and no number is split. Only a window with no whitespace boundary is hard-cut (never inside a surrogate pair).
3. The next chunk starts at the first token start at or after `end - overlapChars`; if none exists before `end` there is no overlap. Start strictly increases.
4. Chunks are exact substrings (no trim), in order. Ordinals are 0-based, `chunkCount` is the total, at most 1000 (`EmbeddingDocument.MaxChunkCount`), else the document is skipped (`chunking-failed`).

## 3. Embedders (all behind core `Embedder`)

| Class | Use | Notes |
|---|---|---|
| `BedrockEmbedder` | Default. Titan Text Embeddings V2 | model ID, version, dimension from `embedding.model.*` (`amazon.titan-embed-text-v2:0`, `v2`, 1024; Titan accepts 256/512/1024). One model call per text, `maxBatchSize` 16, text at most 50,000 characters. Request body `{inputText, dimensions, normalize: true}` |
| `VoyageEmbedder` | Approved fallback | identity from `embedding.model.*`; endpoint `https://api.voyageai.com/v1/embeddings`; `input_type` document (or query); `maxBatchSize` 64 |
| `LocalHashEmbedder` | Tests and the local run | `local-hash-bow` / `v1` / default dimension 1024; hashed bag of words, see below |
| `ValidatingEmbedder` | wrapper | enforces the port rules |

Throttling: HTTP 429 or an SDK throttling exception becomes `Throttled`. HTTP 408, 5xx, network and SDK client errors become `Unavailable`. Other 4xx and unexpected errors become `Failed`. The reason is a fixed phrase plus an HTTP status class (`http 5xx`); never a body, header, URL, text or key.

**AWS off by default.** `SdkBedrockInvoker` creates its `BedrockRuntimeClient` lazily on the first `invoke`, and each construction increments `SdkBedrockInvoker.clientsConstructed`. `EmbedderFactory.bedrock(settings, region, liveProfile, credentials, invokerFor)` returns `Left(Failed("bedrock requires an explicit live profile"))` unless `liveProfile = true`. Tests and local runs use `EmbedderFactory.localTest` or `LocalHashEmbedder` and never build a client; a test asserts `clientsConstructed == 0`. **Hermetic credentials (repair M2).** `SdkBedrockInvoker(region, credentials: AwsCredentialsProvider)` and `EmbedderFactory.bedrock` take an injected `AwsCredentialsProvider` with no default; nothing here uses the default credential chain, so no shared AWS credentials file, `AWS_PROFILE` or instance metadata is consulted. The client builder sets `credentialsProvider(credentials)` and `overrideConfiguration(SdkBedrockInvoker.hermetic)` (an empty `ProfileFile` of type CONFIGURATION and the fixed profile name `scholarchain-hermetic`), as storage `AwsClients` does. The client stays lazy, `clientsConstructed` still counts constructions, and the factory is `Left` unless `liveProfile = true`. `invokerFor` is now `(region, credentials) => BedrockInvoker`. The `BedrockInvoker` trait lets a test drive `BedrockEmbedder` with a fake invoker.

**Voyage secret rule.** The key is read once, at construction, from the environment variable whose NAME is `embedding.voyage.api-key-env-var` (default `SCHOLARCHAIN_VOYAGE_API_KEY`) through an injected `env: String => Option[String]`. It is held in a private field and appears only as the `Authorization` header passed to the injected `VoyageTransport`. It is in no `toString` (printed `apiKey=<redacted>`), no `EmbeddingError` reason (an unset or malformed variable is reported by NAME only) and no exception message (`JdkVoyageTransport` returns a fixed kind `timeout|io|interrupted|error`, never the exception message). Responses are parsed and never echoed. No log call exists in the module. Only ASCII blanks (space, tab, CR, LF) are trimmed from both ends of the value; any other control or whitespace character left in the key, including a trailing one, makes the variable malformed. Endpoint must be https.

**Local test embedder.** Tokens are lower-cased (`Locale.ROOT`) runs of `\p{L}\p{N}`. Each token goes to bucket `floorMod(FNV-1a-64(UTF-8 bytes), dimension)`; counts are L2-normalized. A text without a token is `InvalidRequest`. Deterministic across JVMs.

## 4. Model versioning

`ModelStamp(modelId, modelVersion, dimension)` (`ModelVersioning.stampOf`). `ModelVersioning.entryFor` stamps the embedder's identity onto a `VectorRecord`; `ModelVersioning.checkEntry` rejects an entry whose model ID or version differs, or whose record dimension or vector length differs (`DimensionMismatch(expected, actual)`). `ValidatingEmbedder` rejects a returned vector of the wrong length as `DimensionMismatch`, a wrong vector count and non-finite values as `Failed`, an empty batch, an oversized batch and blank text as `InvalidRequest`. All three embedders also check the length themselves. Storage's worker and `VectorStore` enforce the same on write.

## 5. Semantic context (`SemanticContext`)

`SemanticContext.build(hits, excerptFor): SemanticContextResult(items, skipped)`; `SemanticContext.item(hit, excerpt)`. Reads hit metadata `documentType`, `entityId`, `window` (label `YYYY-YYYY`), `textS3Uri`; the caller resolves the text through the pointer and supplies the excerpt per vector key. Excerpt: whitespace collapsed, bounded to 300 characters at a whitespace boundary, prefix only. **Entity recovery (repair M1).** Storage stores entity IDs longer than 128 characters only as metadata `entityIdHash` (the full SHA-256, `Hashing.textHash`, never truncated; rule mirrored from storage `EmbeddingWorker.metadataFor` without depending on storage). When `entityId` is absent, `SemanticContext.entityIdOf(hit)` recovers it from the vector key `EmbeddingDocument.idFor(type, entityId, window, ordinal)` = `<type>:<entityId>:<window label>:<ordinal>`: type = prefix before the first ':' (must be in `EmbeddingDocumentTypes.all`), window and ordinal = the last two ':' segments, entityId = the middle. It is accepted only if type, window label and ordinal equal metadata `documentType`, `window`, `chunkOrdinal`, `Hashing.textHash(entityId) == entityIdHash`, and `idFor(recovered...) == vectorKey`. An unparseable key is skipped with `vector-key-unparseable`; any mismatch (including a missing `chunkOrdinal`) with `vector-key-metadata-mismatch`; neither `entityId` nor `entityIdHash` with `entity-id-not-in-metadata`. A hit that lacks other metadata or an excerpt is skipped with a fixed reason. `RetrievalSmoke.run` confirms the expected entity through the same `entityIdOf`; a top hit whose entity cannot be confirmed FAILS the query (detail `expected entity could not be confirmed for the top hit`). Order is preserved.

**Exact counts, paths, components and bridges come from the graph or metric stores, never from vector similarity, a score or an excerpt.** A `SemanticContextItem` is similarity evidence only and is never cited as a graph fact; `SemanticContext.GraphFactNotice` carries the sentence.

## 6. Retrieval smoke (`RetrievalSmoke`)

`define(docs, perType = 3, maxQueryTokens = 8, minDistinctive = 3, bucketDimension = 1024, minRelativeMargin = 0.1)` is self-deriving. Per document type, a document's query is up to 8 tokens taken from anywhere in its own rendered text that occur in no other document of the same type (document frequency 1); a document with fewer than `minDistinctive` such tokens is not used. Candidates are tried most-distinctive-first (count capped at `maxQueryTokens`), ties by SHA-256 of the document ID, and the first `perType` passing the collision guard are kept. Collision guard: under the hashed bag-of-words embedder of dimension `bucketDimension`, the expected document's cosine to the query must exceed every other document's of the type by at least `1 + minRelativeMargin`; this is computed from the data, not typed. Each `SmokeQuery.why` states this. The pass criterion of `run` is unchanged (expected document top-1); a collision invisible to the guard (an embedder of another dimension) still fails and is reported. A type with fewer than `perType` qualifying documents yields fewer queries, and the run must say which and why. `run(store, embedder, queries, topK = 5)` embeds each query, queries the type's index (`EmbeddingDocumentTypes.indexFor`) and returns a `SmokeReport` (top-1 pass, expected rank, top keys).

Sanity check observed on 3,000 synthetic researcher documents (a throwaway main, deleted; not the sparkS1r2 data): 30 of 30 queries top-1. With `minDistinctive = 3` the same run was 29 of 30, which is why the default is 5. Real results are for the services harness to report.

Semantic-summary counts by document type in sparkS1r2: NOT OBSERVED (no Parquet reader outside the harness; the run summary gives only the table total of 8,958).

## 7. Public API for the services test harness

```scala
EmbeddingDocumentRenderer.render(summaries: List[SemanticSummary], works: List[WorkRecord],
  venues: List[Venue], topics: List[Topic], runId: String, textUriFor: String => String,
  policy: ChunkPolicy = ChunkPolicy.Default, paperLimit: Option[Int] = None): List[(EmbeddingDocument, String)]
EmbeddingDocumentRenderer.renderDetailed(...same...): RenderResult   // documents, skipped, documentsByType, entitiesByType, pairs
TextUris.contentAddressed(bucket: String, prefix: String = "rag/text"): String => String
new LocalHashEmbedder(dimension = 1024)            // or EmbedderFactory.localTest(dimension)
RetrievalSmoke.define(docs: List[(EmbeddingDocument, String)], ...): List[SmokeQuery]
RetrievalSmoke.run(store: VectorStore, embedder: Embedder, queries: List[SmokeQuery], topK: Int = 5): SmokeReport
SemanticContext.build(hits: List[VectorHit], excerptFor: String => Option[String]): SemanticContextResult
NumberCheck.check(rd: RenderedDocument): Either[List[String], Unit]
```

Harness notes: `runId` must be `run:<64 hex>` (the sparkS1r2 run ID). The embedder dimension must equal the in-memory index dimension. A topic summary has an all-time window and every other summary its Phase 06 window.

## 8. What test-engineer must encode (`modules/rag/src/test/**`)

1. Deterministic rendering: the same input in any order gives the identical document list and texts; sorted by the stated key; a duplicate with identical text is dropped, one with different text is skipped.
2. Every number in a rendered text exists in its source row: `NumberCheck.check` is `Right` for every rendered summary and paper document; a doctored text with an extra number is `Left` with that number.
3. Chunk boundaries and ordinals: text at the limit is one chunk (0 of 1); longer text gives ordinals 0..n-1, `chunkCount = n`, each chunk at most `maxChars`, cuts on whitespace, overlap at most `overlapChars` starting at a token start, concatenation covers the text, invalid policy is Left, over 1000 chunks is skipped.
4. Paper text: no abstract line; absent venue, topics or DOI omit their lines; topics sorted.
5. Dimension mismatch rejected: `ValidatingEmbedder` over a fake returning a short vector gives `DimensionMismatch(expected, actual)`; `ModelVersioning.checkEntry` rejects a different model ID, version, record dimension and vector length; Bedrock and Voyage adapters reject a wrong-length reply.
6. Voyage key never leaks: with a distinctive fake key, for 200, 429, 500, 401 and malformed replies and for transport failures, no `EmbeddingError` string, no thrown exception message, no `toString` contains the key; unset, blank and malformed variables name only the variable; the key reaches the transport only in the `Authorization` header; 429 is `Throttled`, 5xx and transport failure `Unavailable`.
7. A vector result resolves to its S3 text pointer: documents from the renderer have `textS3Uri = s3://<bucket>/rag/text/<textHash>.txt` and `textHash = Hashing.textHash(text)`; with storage's fake and `VectorTextResolver` (services harness) the hit's text equals the rendered text.
8. Smoke neighbors: over a small fixed corpus with `LocalHashEmbedder` and a fake `VectorStore`, every `define`d query ranks its expected document top-1; a store that returns another key gives a failing outcome.
9. Semantic context: excerpt at most 300 characters and cut at whitespace; entityIdHash-only hits yield an item with the full entity ID when key and metadata agree; mismatching hash, unparseable key, no-excerpt and bad-window hits are skipped with the stated reasons; items validate under their core contract; order kept.
10. No Bedrock client with `SCHOLARCHAIN_AWS_IT` unset: `EmbedderFactory.bedrock(..., liveProfile = false)` is `Left`; `SdkBedrockInvoker.clientsConstructed == 0` after every test; `BedrockEmbedder` over a fake invoker sends `dimensions`, `normalize` and maps throttling to `Throttled` and 5xx to `Unavailable`.
11. `LocalHashEmbedder`: same text gives the same vector; unit norm; a text without a token is `InvalidRequest`; FNV-1a is stable (fixed expected bucket values).

## 9. Not done in this phase

No live Bedrock or Voyage call; no embedding of real data; the 10K ingestion is Phase 15. Voyage queries should use `inputType = query` (`VoyageEmbedder.create(..., inputType = VoyageEmbedder.QueryInputType)`).
