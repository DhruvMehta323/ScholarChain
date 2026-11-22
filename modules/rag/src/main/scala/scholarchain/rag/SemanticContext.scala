package scholarchain.rag

import scholarchain.core.*

/** A vector hit that could not become a `SemanticContextItem`, with a fixed short reason. */
final case class SkippedHit(vectorKey: String, reason: String)

final case class SemanticContextResult(items: List[SemanticContextItem], skipped: List[SkippedHit])

/** Turns `VectorHit`s into `SemanticContextItem`s for the Phase 09 SemanticContextAgent.
  *
  * SEMANTIC CONTEXT ONLY. These items are similarity evidence kept apart from graph facts. Exact
  * counts, paths, components and bridges come from the graph store or the metric stores, NEVER from
  * vector similarity, a score or an excerpt. A consumer must not cite a `SemanticContextItem` as a
  * graph fact; `GraphFactNotice` is the sentence to attach to any answer section built from them.
  *
  * Inputs are the hit metadata the storage adapter writes (`documentType`, `entityId`, `window`
  * label such as `2000-2004`, `textS3Uri`) and an excerpt source resolved by the caller from the S3
  * text through the pointer (`excerptFor(vectorKey)`). The excerpt is whitespace-normalized,
  * bounded to `SemanticContextItem.MaxExcerptLength` (300) characters and cut at a whitespace
  * boundary when it is longer, never mid-token; it is a prefix of the text, never the whole
  * document. Hits keep their input order (descending score).
  */
object SemanticContext:
  val GraphFactNotice: String =
    "Semantic similarity context only. Counts, paths, components and bridges must come from the graph or metric stores."

  val SkipNoMetadata     = "missing-hit-metadata"
  val SkipNoEntity       = "entity-id-not-in-metadata"
  val SkipNoExcerpt      = "no-excerpt"
  val SkipInvalid        = "invalid-item"
  val SkipKeyUnparseable = "vector-key-unparseable"
  val SkipKeyMismatch    = "vector-key-metadata-mismatch"

  private val WindowLabel = """^(\d{4})-(\d{4})$""".r

  def build(hits: List[VectorHit], excerptFor: String => Option[String]): SemanticContextResult =
    val results =
      hits.map(h => item(h, excerptFor(h.vectorKey)).left.map(SkippedHit(h.vectorKey, _)))
    SemanticContextResult(results.flatMap(_.toOption), results.flatMap(_.left.toOption))

  def item(hit: VectorHit, excerpt: Option[String]): Either[String, SemanticContextItem] =
    val m = hit.metadata
    for
      docType <- m.get("documentType").toRight(SkipNoMetadata)
      entity  <- entityIdOf(hit)
      uri     <- m.get("textS3Uri").toRight(SkipNoMetadata)
      window  <- m.get("window").flatMap(parseWindow).toRight(SkipNoMetadata)
      text    <- excerpt.map(boundExcerpt).filter(_.nonEmpty).toRight(SkipNoExcerpt)
      it = SemanticContextItem(
        SchemaVersion.Current,
        hit.vectorKey,
        hit.score,
        docType,
        entity,
        window,
        uri,
        text,
      )
      valid <- summon[Contract[SemanticContextItem]].validate(it).left.map(_ => SkipInvalid)
    yield valid

  /** The entity ID of a hit. Metadata `entityId` when present. Otherwise (storage keeps only
    * `entityIdHash` for IDs longer than 128 characters) it is recovered from the vector key.
    *
    * Recovery: the key is core `EmbeddingDocument.idFor(type, entityId, window, ordinal)` =
    * `<type>:<entityId>:<window label>:<ordinal>`. The type is the prefix before the first ':' and
    * must be in `EmbeddingDocumentTypes.all`; window and ordinal are the last two ':' segments; the
    * entity ID is the middle. The result is accepted only if all of these hold: the type, window
    * label and ordinal equal the metadata `documentType`, `window`, `chunkOrdinal`;
    * `Hashing.textHash(entityId)` equals metadata `entityIdHash` (storage's rule, mirrored here
    * from storage `EmbeddingWorker.metadataFor`: the full lowercase SHA-256 of the entity ID, never
    * truncated); and `EmbeddingDocument.idFor` of the parts equals the vector key. Anything else is
    * skipped with `SkipKeyUnparseable` or `SkipKeyMismatch`.
    */
  def entityIdOf(hit: VectorHit): Either[String, String] =
    val m = hit.metadata
    m.get("entityId") match
      case Some(e) => Right(e)
      case None    =>
        m.get("entityIdHash") match
          case None       => Left(SkipNoEntity)
          case Some(hash) => recoverEntityId(hit.vectorKey, m, hash)

  private def recoverEntityId(
      key: String,
      m: Map[String, String],
      hash: String,
  ): Either[String, String] =
    val first = key.indexOf(':')
    val parts = key.split(":", -1).toList
    if first <= 0 || parts.length < 4 then Left(SkipKeyUnparseable)
    else
      val docType = key.substring(0, first)
      val ordinal = parts.last
      val label   = parts(parts.length - 2)
      val entity  = parts.slice(1, parts.length - 2).mkString(":")
      val parsed  = for
        w <- parseWindow(label)
        o <- ordinal.toIntOption.filter(i => i >= 0 && i.toString == ordinal)
        if EmbeddingDocumentTypes.all.contains(docType) && entity.nonEmpty
      yield (w, o)
      parsed match
        case None         => Left(SkipKeyUnparseable)
        case Some((w, o)) =>
          val ok =
            m.get("documentType").contains(docType) &&
              m.get("window").contains(label) &&
              m.get("chunkOrdinal").contains(ordinal) &&
              Hashing.textHash(entity) == hash &&
              EmbeddingDocument.idFor(docType, entity, w, o) == key
          if ok then Right(entity) else Left(SkipKeyMismatch)

  def parseWindow(label: String): Option[GraphWindow] = label match
    case WindowLabel(a, b) => Some(GraphWindow(a.toInt, b.toInt))
    case _                 => None

  /** Collapses whitespace runs to one space, trims, and bounds to 300 characters at a whitespace
    * boundary (a single token longer than 300 is hard-cut, not split inside a surrogate pair).
    */
  def boundExcerpt(raw: String): String =
    val s   = raw.trim.replaceAll("\\s+", " ")
    val max = SemanticContextItem.MaxExcerptLength
    if s.length <= max then s
    else
      val cut = s.lastIndexWhere(_.isWhitespace, max)
      if cut > 0 then s.substring(0, cut)
      else if s(max - 1).isHighSurrogate then s.substring(0, max - 1)
      else s.substring(0, max)
