package scholarchain.rag.runtime

import scala.util.Try

import scholarchain.core.*

/** Constants shared by every runtime stage. */
object RuntimeCode:
  /** Recorded as the code version of every runtime provenance and audit record. */
  val Version: String = "rag-runtime.v1"

  /** The all-time window label (`GraphWindows.AllTime`). */
  val AllTimeLabel: String = GraphWindow.label(GraphWindows.AllTime)

/** Time source, injected so a run is reproducible: `Fixed` gives identical records twice. */
trait RuntimeClock:
  def epochMillis(): Long

object RuntimeClock:
  object System extends RuntimeClock:
    def epochMillis(): Long = java.lang.System.currentTimeMillis()

  final case class Fixed(millis: Long) extends RuntimeClock:
    def epochMillis(): Long = millis

/** A stage-level failure with a fixed short code and a detail that never carries user text, a key
  * or a configuration value.
  */
final case class RuntimeError(code: String, detail: String):
  def message: String = s"$code: $detail"

object RuntimeError:
  def fromStorage(what: String, e: StorageError): RuntimeError =
    RuntimeError(s"storage-${e.code}", what)

/** Physical names of the stores the stages read and write (the defaults are the names in the
  * storage configuration; a deployment passes the configured names).
  *
  * `analytics` maps the logical analytics table name (the Phase 06 load-manifest table name) to the
  * physical DynamoDB table. `auditBucket` and `auditPrefix` locate the audit documents in the
  * object store.
  */
final case class RuntimeTables(
    querySession: String,
    evidenceBundle: String,
    analytics: Map[String, String],
    auditBucket: String,
    auditPrefix: String,
):
  def analyticsTable(logical: String): Either[RuntimeError, String] =
    analytics.get(logical).toRight(RuntimeError("unknown-table", s"analytics table $logical"))

object RuntimeTables:
  val AnalyticsLogicalNames: List[String] = List(
    "researcher-metrics",
    "coauthor-edge-works",
    "bridges",
    "coreness",
    "temporal-changes",
    "paths",
    "path-estimates",
    "window-summary",
    "identity-clusters",
    "mention-researcher",
  )

  /** The default physical names from `scholarchain-storage.v1.conf`. */
  val defaults: RuntimeTables = RuntimeTables(
    querySession = "scholarchain-QuerySession",
    evidenceBundle = "scholarchain-EvidenceBundle",
    analytics = AnalyticsLogicalNames.map(n => n -> s"scholarchain-analytics-$n").toMap,
    auditBucket = "scholarchain-local-audit",
    auditPrefix = "rag/audit",
  )

/** Compact JSON with object keys sorted at every level: the same value is the same text. */
object CanonicalJson:
  def sorted(v: ujson.Value): ujson.Value = v match
    case o: ujson.Obj =>
      ujson.Obj.from(o.value.toSeq.sortBy(_._1).map((k, x) => k -> sorted(x)))
    case a: ujson.Arr => ujson.Arr.from(a.value.toSeq.map(sorted))
    case other        => other

  def render(v: ujson.Value): String = ujson.write(sorted(v))

/** One analytics item read back through the key-value port. */
final case class StoredItem(
    table: String,
    pk: String,
    sk: Option[String],
    payloadHash: String,
    json: ujson.Value,
):
  def obj: ujson.Obj = json match
    case o: ujson.Obj => o
    case _            => ujson.Obj()

/** Reads the ten Phase 06 analytics tables through `KeyValueStore`, mirroring the item layout the
  * storage loader writes: `pk` and `sk` are the key components encoded by `encodeComponent`, the
  * item holds the typed attributes as one JSON string in `payload` and its SHA-256 in
  * `payloadHash`. A read verifies the hash and never returns an item whose hash differs.
  *
  * Reads only: nothing in this class writes. The class does not depend on `modules/storage`.
  */
final class AnalyticsReader(kv: KeyValueStore, tables: RuntimeTables):
  import AnalyticsReader.*

  def get(
      logical: String,
      pk: String,
      sortComponents: List[String] = Nil,
  ): Either[RuntimeError, Option[StoredItem]] =
    tables.analyticsTable(logical).flatMap { t =>
      val sk = if sortComponents.isEmpty then None else Some(encodeSort(sortComponents))
      kv.get(t, KvKey(encodeComponent(pk), sk)) match
        case Left(StorageError.NotFound(_)) => Right(None)
        case Left(e)                        => Left(RuntimeError.fromStorage(s"read $logical", e))
        case Right(None)                    => Right(None)
        case Right(Some(i))                 => decode(logical, i).map(Some(_))
    }

  /** Items of one partition, ascending by sort key, at most `limit`. `sortPrefix` restricts the
    * sort key to a prefix (components encoded and joined like a full key).
    */
  def query(
      logical: String,
      pk: String,
      limit: Int,
      sortPrefix: List[String] = Nil,
  ): Either[RuntimeError, List[StoredItem]] =
    tables.analyticsTable(logical).flatMap { t =>
      val cond =
        if sortPrefix.isEmpty then None
        else Some(SortCondition.BeginsWith(encodeSort(sortPrefix)))
      kv.query(t, encodeComponent(pk), cond, math.max(1, limit)) match
        case Left(e)     => Left(RuntimeError.fromStorage(s"query $logical", e))
        case Right(page) =>
          page.items.take(limit).foldLeft[Either[RuntimeError, List[StoredItem]]](Right(Nil)) {
            (acc, i) => acc.flatMap(done => decode(logical, i).map(done :+ _))
          }
    }

  private def decode(logical: String, i: KvItem): Either[RuntimeError, StoredItem] =
    val payload = i.attributes.get("payload")
    val hash    = i.attributes.get("payloadHash")
    (payload, hash) match
      case (Some(p), Some(h)) =>
        if Hashing.textHash(p) != h then
          Left(RuntimeError("integrity-mismatch", s"stored $logical item does not match its hash"))
        else
          Try(ujson.read(p)).toOption match
            case Some(j) => Right(StoredItem(logical, i.key.partition, i.key.sort, h, j))
            case None => Left(RuntimeError("malformed-item", s"stored $logical item is not JSON"))
      case _ => Left(RuntimeError("malformed-item", s"stored $logical item has no payload"))

object AnalyticsReader:
  /** The loader's component encoding: `%` becomes `%25`, then `|` becomes `%7C`. */
  def encodeComponent(s: String): String = s.replace("%", "%25").replace("|", "%7C")

  def encodeSort(components: List[String]): String = components.map(encodeComponent).mkString("|")

/** A fact: the exact stored values one piece of deterministic evidence rests on, as canonical JSON
  * in the evidence text. The claim verifier parses it back; nothing else carries a number into an
  * answer.
  *
  * `kind` is one of `Fact.Kinds`. `subject` is the entity the fact is about (a researcher ID, an
  * edge ID, a window label or a path key). `fields` holds the values, named like the stored
  * attributes.
  */
final case class Fact(
    kind: String,
    subject: String,
    window: Option[String],
    runId: Option[String],
    policyVersion: Option[String],
    fields: ujson.Obj,
):
  def toJson: ujson.Value =
    val base = List[(String, ujson.Value)]("kind" -> kind, "subject" -> subject)
    val opt: List[(String, ujson.Value)] =
      window.toList.map(w => "window" -> (ujson.Str(w): ujson.Value)) ++
        runId.toList.map(r => "runId" -> (ujson.Str(r): ujson.Value)) ++
        policyVersion.toList.map(p => "policyVersion" -> (ujson.Str(p): ujson.Value))
    ujson.Obj.from(base ++ opt ++ List("fields" -> fields))

  def text: String = CanonicalJson.render(toJson)

object Fact:
  val Metrics: String        = "metrics"
  val WindowSummary: String  = "window_summary"
  val Identity: String       = "identity"
  val CoauthorEdge: String   = "coauthor_edge"
  val Path: String           = "path"
  val Component: String      = "component"
  val Community: String      = "community"
  val DegreeVsBridge: String = "degree_vs_bridge"
  val Ranking: String        = "ranking"
  val TemporalChange: String = "temporal_change"
  val Neighborhood: String   = "neighborhood"
  val Kinds: Set[String]     = Set(
    Metrics,
    WindowSummary,
    Identity,
    CoauthorEdge,
    Path,
    Component,
    Community,
    DegreeVsBridge,
    Ranking,
    TemporalChange,
    Neighborhood,
  )

  def parse(text: String): Option[Fact] =
    Try(ujson.read(text)).toOption.flatMap(_.objOpt).flatMap { o =>
      for
        kind    <- o.value.get("kind").flatMap(_.strOpt)
        subject <- o.value.get("subject").flatMap(_.strOpt)
        fields  <- o.value.get("fields").flatMap(_.objOpt)
      yield Fact(
        kind,
        subject,
        o.value.get("window").flatMap(_.strOpt),
        o.value.get("runId").flatMap(_.strOpt),
        o.value.get("policyVersion").flatMap(_.strOpt),
        fields,
      )
    }

/** Evidence a stage proposes, before the builder gives it a content hash and an ID. `semantic` is
  * true only for vector-search context, which can never support a number, an edge or a path.
  */
final case class EvidenceDraft(
    evidenceType: EvidenceType,
    sourceEntityId: String,
    sourceVersion: String,
    text: String,
    inputs: List[InputRef],
    runId: Option[String],
    parameters: Map[String, String],
    score: Option[Double] = None,
    semantic: Boolean = false,
)

object EvidenceDraft:
  def fromFact(
      evidenceType: EvidenceType,
      sourceEntityId: String,
      sourceVersion: String,
      fact: Fact,
      inputs: List[InputRef],
      parameters: Map[String, String],
  ): EvidenceDraft =
    EvidenceDraft(
      evidenceType,
      sourceEntityId,
      sourceVersion,
      fact.text,
      inputs,
      fact.runId,
      parameters,
    )

/** What one retrieval stage returns: evidence, and what it could not find (never guessed). */
final case class StageOutput(
    drafts: List[EvidenceDraft] = Nil,
    missingEvidence: List[String] = Nil,
    reasonCodes: List[String] = Nil,
):
  def ++(o: StageOutput): StageOutput =
    StageOutput(
      drafts ++ o.drafts,
      (missingEvidence ++ o.missingEvidence).distinct,
      (reasonCodes ++ o.reasonCodes).distinct,
    )

object StageOutput:
  val empty: StageOutput = StageOutput()

/** Number rendering for facts: a whole double is an integer, as the stored items show. */
object Jnum:
  def of(v: ujson.Value): ujson.Value = v

  def value(v: ujson.Value): Option[BigDecimal] = v match
    case ujson.Num(d) if !d.isNaN && !d.isInfinite => Some(BigDecimal(d))
    case _                                         => None

  def sameNumber(a: BigDecimal, b: BigDecimal): Boolean =
    (a - b).abs <= BigDecimal("0.000000001")

/** Typed accessors over graph rows (no parsing of free text). */
object Rows:
  def str(r: GraphRow, c: String): Option[String] = r.columns.get(c).collect {
    case GraphValue.Str(s) => s
  }

  def long(r: GraphRow, c: String): Option[Long] = r.columns.get(c).collect {
    case GraphValue.Num(n)              => n
    case GraphValue.Dbl(d) if d.isWhole => d.toLong
  }

  def strList(r: GraphRow, c: String): Option[List[String]] = r.columns.get(c).collect {
    case GraphValue.Lst(vs) => vs.collect { case GraphValue.Str(s) => s }
  }
