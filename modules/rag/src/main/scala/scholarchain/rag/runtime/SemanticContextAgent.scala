package scholarchain.rag.runtime

import java.nio.charset.StandardCharsets

import scala.util.Try

import scholarchain.core.*
import scholarchain.rag.{SemanticContext, SkippedHit}

/** Result of the semantic stage: evidence drafts (all `semantic = true`), the typed items with
  * scores and S3 pointers, and the hits that could not become items.
  */
final case class SemanticOutput(
    stage: StageOutput,
    items: List[SemanticContextItem],
    skipped: List[SkippedHit],
)

/** Stage 6: semantic context from the vector indexes.
  *
  * The query text is embedded with the injected `Embedder` and the matching index of each document
  * type is queried through the `VectorStore` port with a metadata filter (document type, and the
  * window when the plan has one). Every hit's text is read through its S3 pointer (`textS3Uri`)
  * from the `ObjectStore` and checked against its `textHash`; a hit whose text is missing or does
  * not match is skipped, never used. The excerpt (at most 300 characters) is the only text that
  * leaves this stage.
  *
  * SEMANTIC CONTEXT ONLY. The drafts are `TextChunk` evidence with `semantic = true`. Vector
  * similarity is never evidence for a coauthorship edge, a path, a count or any number: the
  * synthesis prompt, the template synthesizer and the claim verifier all treat these items as
  * unable to support such statements. The embedder sees the query text as data (an embedding
  * input), never as a command or a query string.
  */
final class SemanticContextAgent(
    embedder: Embedder,
    vectors: VectorStore,
    objects: ObjectStore,
):
  def search(
      queryText: String,
      documentTypes: List[String],
      window: Option[GraphWindow],
      topKPerType: Int,
  ): SemanticOutput =
    val text = queryText.trim
    if text.isEmpty || documentTypes.isEmpty then SemanticOutput(StageOutput.empty, Nil, Nil)
    else
      embedder.embed(List(text)) match
        case Left(e) =>
          SemanticOutput(
            StageOutput(
              missingEvidence = List("semantic search was not possible for this question"),
              reasonCodes = List(s"semantic_embed_${e.code.replace('-', '_')}"),
            ),
            Nil,
            Nil,
          )
        case Right(vs) =>
          val perType = documentTypes.distinct.map { t =>
            val index  = EmbeddingDocumentTypes.indexFor(t)
            val filter = MetadataFilter.And(
              List(MetadataFilter.Equals("documentType", t)) ++
                window.map(w => MetadataFilter.Equals("window", GraphWindow.label(w)))
            )
            index match
              case None    => (t, Left(RuntimeError("unknown-document-type", t)))
              case Some(i) =>
                (
                  t,
                  vectors
                    .query(i, VectorQuery(vs.head, math.max(1, topKPerType), Some(filter)))
                    .left
                    .map(RuntimeError.fromStorage("vector search", _)),
                )
          }
          val hits  = perType.flatMap((_, r) => r.toOption.toList.flatten)
          val codes = perType.collect { case (_, Left(e)) =>
            s"semantic_${e.code.replace('-', '_')}"
          }
          val texts  = hits.map(h => h.vectorKey -> textOf(h)).toMap
          val built  = SemanticContext.build(hits, k => texts.get(k).flatten)
          val sorted = built.items.sortBy(i => (-i.score, i.vectorKey))
          val drafts = sorted.map { i =>
            EvidenceDraft(
              EvidenceType.TextChunk,
              i.entityId,
              i.vectorKey,
              i.excerpt,
              List(InputRef(i.textS3Uri, "text", textHashOf(hits, i.vectorKey))),
              None,
              Map(
                "documentType" -> i.documentType,
                "window"       -> GraphWindow.label(i.window),
                "source"       -> "vector-search",
              ),
              Some(i.score),
              semantic = true,
            )
          }
          SemanticOutput(StageOutput(drafts, Nil, codes), sorted, built.skipped)

  private def textHashOf(hits: List[VectorHit], key: String): Option[String] =
    hits.find(_.vectorKey == key).flatMap(_.metadata.get("textHash")).filter(Hashing.isSha256Hex)

  /** The text behind a hit: read through the S3 pointer and verified against the text hash. */
  private def textOf(h: VectorHit): Option[String] =
    for
      uri  <- h.metadata.get("textS3Uri")
      hash <- h.metadata.get("textHash")
      loc  <- ObjectLocation.parse(uri).toOption
      data <- objects.get(loc).toOption
      if Hashing.sha256Hex(data.bytes) == hash
      s <- Try(new String(data.bytes, StandardCharsets.UTF_8)).toOption
    yield s
