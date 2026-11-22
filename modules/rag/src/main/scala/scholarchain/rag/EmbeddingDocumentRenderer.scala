package scholarchain.rag

import scholarchain.core.*

/** One rendered, chunk-level document: the core reference, its exact text, and the source row
  * (every source field value, joined with a newline) used by `NumberCheck`.
  */
final case class RenderedDocument(document: EmbeddingDocument, text: String, sourceRow: String)

/** A row or document that was not rendered, with a short fixed reason (never row content). */
final case class RenderSkip(documentType: String, entityId: String, reason: String)

final case class RenderResult(documents: List[RenderedDocument], skipped: List[RenderSkip]):
  def pairs: List[(EmbeddingDocument, String)] = documents.map(d => d.document -> d.text)

  /** Chunk-level document count per document type (types with none are absent). */
  def documentsByType: Map[String, Int] =
    documents.groupBy(_.document.documentType).view.mapValues(_.size).toMap

  /** Distinct entity count per document type. */
  def entitiesByType: Map[String, Int] =
    documents
      .groupBy(_.document.documentType)
      .view
      .mapValues(_.map(_.document.entityId).distinct.size)
      .toMap

/** Content-addressed text URIs: `s3://<bucket>/<prefix>/<sha256>.txt`. */
object TextUris:
  val DefaultPrefix: String = "rag/text"

  def contentAddressed(bucket: String, prefix: String = DefaultPrefix): String => String =
    val p = prefix.stripPrefix("/").stripSuffix("/")
    (textHash: String) => s"s3://$bucket/$p/$textHash.txt"

/** Deterministic rendering of Phase 06 semantic summaries and source records into embedding
  * documents, one per chunk, for the five indexes.
  *
  *   - `researcher_profile`, `community_profile`, `path_summary`, `topic_summary`: the document
  *     text is the Phase 06 `SemanticSummary.text` verbatim (chunked only if longer than the policy
  *     allows). The numbers in it are the graph and metric values Phase 06 wrote; the renderer adds
  *     nothing, so every number is in its source row.
  *   - `paper_abstract`: Phase 06 has titles and NO abstracts. The text is rendered only from
  *     fields that are present on the `WorkRecord` (and its venue and topic labels): title, year,
  *     venue name, topic labels, DOI, work ID. No abstract is fabricated; the index holds
  *     title-level metadata under the type name `paper_abstract`. The window is all-time.
  *   - Templates contain no numeric literal. Rendered numbers are checkable with `NumberCheck`.
  *
  * Determinism: no clock, no randomness; output is sorted by (type order of
  * `EmbeddingDocumentTypes.all`, entityId, window start, window end, chunk ordinal). Duplicate
  * (type, entity, window): an identical text is dropped, a different text is skipped
  * (`duplicate-key-different-text`); the first by sort order is kept.
  *
  * Only `EmbeddingDocument` references are produced; `textUriFor(textHash)` supplies the pointer.
  */
object EmbeddingDocumentRenderer:
  val SkipUnknownType        = "unknown-document-type"
  val SkipBlankText          = "blank-text"
  val SkipChunking           = "chunking-failed"
  val SkipInvalidDocument    = "invalid-document"
  val SkipDuplicateDifferent = "duplicate-key-different-text"

  private val typeOrder: Map[String, Int] = EmbeddingDocumentTypes.all.zipWithIndex.toMap

  /** Pairs for the storage producer: `EmbeddingSource(document, text)`. */
  def render(
      summaries: List[SemanticSummary],
      works: List[WorkRecord],
      venues: List[Venue],
      topics: List[Topic],
      runId: String,
      textUriFor: String => String,
      policy: ChunkPolicy = ChunkPolicy.Default,
      paperLimit: Option[Int] = None,
  ): List[(EmbeddingDocument, String)] =
    renderDetailed(summaries, works, venues, topics, runId, textUriFor, policy, paperLimit).pairs

  def renderDetailed(
      summaries: List[SemanticSummary],
      works: List[WorkRecord],
      venues: List[Venue],
      topics: List[Topic],
      runId: String,
      textUriFor: String => String,
      policy: ChunkPolicy = ChunkPolicy.Default,
      paperLimit: Option[Int] = None,
  ): RenderResult =
    val candidates =
      summaryCandidates(summaries) ++ paperCandidates(works, venues, topics, paperLimit)
    val sorted = candidates.sortBy(c =>
      (
        typeOrder.getOrElse(c.documentType, Int.MaxValue),
        c.entityId,
        c.window.startYear,
        c.window.endYear,
        c.text,
      )
    )
    val (kept, dupSkips) = dedupe(sorted)
    val rendered         = kept.map(chunkAndBuild(_, runId, textUriFor, policy))
    RenderResult(
      rendered.flatMap(_.toOption.getOrElse(Nil)),
      dupSkips ++ rendered.flatMap(_.left.toOption),
    )

  // ---- candidates ------------------------------------------------------------------------

  private final case class Candidate(
      documentType: String,
      entityId: String,
      window: GraphWindow,
      text: String,
      sourceRow: String,
  )

  private def summaryCandidates(summaries: List[SemanticSummary]): List[Candidate] =
    summaries.map(s =>
      Candidate(
        s.documentType,
        s.entityId,
        s.window,
        s.text,
        List(s.entityId, GraphWindow.label(s.window), s.text).mkString("\n"),
      )
    )

  private def paperCandidates(
      works: List[WorkRecord],
      venues: List[Venue],
      topics: List[Topic],
      limit: Option[Int],
  ): List[Candidate] =
    val venueById = venues.map(v => v.venueId -> v).toMap
    val topicById = topics.map(t => t.topicId -> t).toMap
    val sorted    = works.sortBy(_.workId)
    limit.fold(sorted)(sorted.take(_)).map { w =>
      val venue       = w.venueId.flatMap(venueById.get)
      val topicLabels = w.topicIds.distinct.flatMap(topicById.get).map(_.label).sorted
      val lines       = List(
        Some(s"Paper: ${w.workId}"),
        Some(s"Title: ${w.title.trim}"),
        Some(s"Year: ${w.year}"),
        venue.map(v => s"Venue: ${v.name.trim}"),
        Option.when(topicLabels.nonEmpty)(s"Topics: ${topicLabels.mkString("; ")}"),
        w.doi.map(d => s"DOI: $d"),
      ).flatten
      val source = (List(w.workId, w.title, w.year.toString) ++ venue.map(_.name) ++ topicLabels ++
        w.doi).mkString("\n")
      Candidate(
        EmbeddingDocumentTypes.PaperAbstract,
        w.workId,
        GraphWindows.AllTime,
        lines.mkString("\n"),
        source,
      )
    }

  private def dedupe(sorted: List[Candidate]): (List[Candidate], List[RenderSkip]) =
    val seen =
      scala.collection.mutable.LinkedHashMap.empty[(String, String, GraphWindow), Candidate]
    val skips = List.newBuilder[RenderSkip]
    sorted.foreach { c =>
      val k = (c.documentType, c.entityId, c.window)
      seen.get(k) match
        case None                                => seen.update(k, c)
        case Some(first) if first.text == c.text => ()
        case Some(_) => skips += RenderSkip(c.documentType, c.entityId, SkipDuplicateDifferent)
    }
    (seen.values.toList, skips.result())

  private def chunkAndBuild(
      c: Candidate,
      runId: String,
      textUriFor: String => String,
      policy: ChunkPolicy,
  ): Either[RenderSkip, List[RenderedDocument]] =
    def skip(why: String) = RenderSkip(c.documentType, c.entityId, why)
    if !EmbeddingDocumentTypes.all.contains(c.documentType) then Left(skip(SkipUnknownType))
    else if c.text.isBlank then Left(skip(SkipBlankText))
    else
      Chunker.chunk(c.text, policy).left.map(_ => skip(SkipChunking)).flatMap { chunks =>
        val built = chunks.zipWithIndex.map { (t, i) =>
          val hash = Hashing.textHash(t)
          val doc  = EmbeddingDocument(
            schemaVersion = SchemaVersion.Current,
            documentId = EmbeddingDocument.idFor(c.documentType, c.entityId, c.window, i),
            documentType = c.documentType,
            entityId = c.entityId,
            window = c.window,
            textS3Uri = textUriFor(hash),
            textHash = hash,
            chunkOrdinal = i,
            chunkCount = chunks.size,
            sourceRunId = runId,
          )
          summon[Contract[EmbeddingDocument]]
            .validate(doc)
            .map(_ => RenderedDocument(doc, t, c.sourceRow))
        }
        built.collectFirst { case Left(_) => skip(SkipInvalidDocument) } match
          case Some(s) => Left(s)
          case None    => Right(built.toList.collect { case Right(d) => d })
      }
