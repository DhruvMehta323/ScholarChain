package scholarchain.core.rest

import java.nio.charset.StandardCharsets

import scala.util.control.NonFatal

import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}

/** Filter constraints of a query. Lists are data, not query text: at most `MaxFilterEntries` (10)
  * distinct entries, each non-blank, at most `MaxFilterLength` (128) characters, no control
  * characters. `yearFrom` and `yearTo` are four-digit years with `yearFrom <= yearTo`.
  */
final case class QueryConstraints(
    yearFrom: Option[Int] = None,
    yearTo: Option[Int] = None,
    topics: List[String],
    institutions: List[String],
    venues: List[String],
) derives ReadWriter

/** What to compute. `rankingMetric` is absent when no ranking is requested. */
final case class AnalysisOptions(
    rankingMetric: Option[RankingMetric] = None,
    includeTeamSize: Boolean,
    includeBridgeMetrics: Boolean,
    includeCommunities: Boolean,
    includePaths: Boolean,
) derives ReadWriter

/** Retrieval bounds. `graphLimit` in 0..100, `vectorLimit` in 0..100, `maxPathDepth` in 1..6. At
  * least one of the two limits must be positive (otherwise no retrieval mode is selected: 422).
  */
final case class RetrievalOptions(graphLimit: Int, vectorLimit: Int, maxPathDepth: Int)
    derives ReadWriter

/** What the answer includes. */
final case class OutputOptions(
    includeEvidence: Boolean,
    includeScores: Boolean,
    includeUncertainty: Boolean,
) derives ReadWriter

/** What a deployment supports; a request asking for more is refused with 422. */
final case class RestCapabilities(
    graphRetrieval: Boolean,
    vectorRetrieval: Boolean,
    rankingMetrics: Set[RankingMetric],
    bridgeMetrics: Boolean,
    communities: Boolean,
    paths: Boolean,
)

object RestCapabilities:
  val all: RestCapabilities =
    RestCapabilities(true, true, RankingMetric.values.toSet, true, true, true)

/** `POST /v1/query` body. No schema version in the body (the path is `/v1`).
  *
  * Parsing is strict: any field outside the contract is rejected with 400 `UNKNOWN_FIELD` at every
  * nesting level. Sections other than `question` may be absent and take the defaults of
  * `QueryRequest.Defaults`. Bounds: body at most 32768 UTF-8 bytes, JSON nesting at most 8,
  * question 1..2000 characters without control characters.
  *
  * 400 vs 422: a request that is malformed, mistyped, out of range, or has an unknown field is 400.
  * A well-formed request that names a ranking metric outside the closed set, or (through
  * `checkSupported`) asks for a retrieval mode or analysis this deployment does not offer, is 422.
  */
final case class QueryRequest(
    question: String,
    constraints: QueryConstraints,
    analysis: AnalysisOptions,
    retrieval: RetrievalOptions,
    output: OutputOptions,
) derives ReadWriter:
  /** Result-count and path-depth limits for the Phase 09 workflow. */
  def limits: QueryLimits =
    QueryLimits(math.max(1, retrieval.graphLimit), retrieval.maxPathDepth)

  /** The year window when both years are given. */
  def window: Option[GraphWindow] =
    for f <- constraints.yearFrom; t <- constraints.yearTo yield GraphWindow(f, t)

object QueryRequest:
  object Defaults:
    val Constraints: QueryConstraints = QueryConstraints(None, None, Nil, Nil, Nil)
    val Analysis: AnalysisOptions     = AnalysisOptions(None, false, false, false, false)
    val Retrieval: RetrievalOptions   =
      RetrievalOptions(
        RestLimits.DefaultGraphLimit,
        RestLimits.DefaultVectorLimit,
        RestLimits.MaxPathDepth,
      )
    val Output: OutputOptions = OutputOptions(true, true, true)

  def of(question: String): QueryRequest =
    QueryRequest(
      question,
      Defaults.Constraints,
      Defaults.Analysis,
      Defaults.Retrieval,
      Defaults.Output,
    )

  /** Parses and validates a request body. Total: never throws. */
  def parse(body: String): Either[RestRejection, QueryRequest] =
    if body.getBytes(StandardCharsets.UTF_8).length > RestLimits.MaxBodyBytes then
      Left(RestRejection(ApiErrorCode.MalformedRequest, "body", "is too large"))
    else if !nestingOk(body) then
      Left(RestRejection(ApiErrorCode.MalformedRequest, "body", "is nested too deeply"))
    else
      val tree =
        try Right(ujson.read(body))
        catch
          case NonFatal(_) =>
            Left(RestRejection(ApiErrorCode.MalformedRequest, "body", "is not valid JSON"))
      tree.flatMap(fromJson)

  /** Checks a request built in code (the same rules as `parse`, without the body checks). */
  def validate(r: QueryRequest): Either[RestRejection, QueryRequest] =
    import RestRejection.invalid
    def text(field: String, s: String, max: Int): Either[RestRejection, Unit] =
      RuntimeChecks.text(field, s, max).left.map(RestRejection.fromValidation)
    def list(field: String, xs: List[String]): Either[RestRejection, Unit] =
      for
        _ <- Either.cond(
          xs.size <= RestLimits.MaxFilterEntries,
          (),
          invalid(field, s"must have at most ${RestLimits.MaxFilterEntries} entries"),
        )
        _ <- xs.foldLeft[Either[RestRejection, Unit]](Right(()))((a, x) =>
          a.flatMap(_ => text(field, x, RestLimits.MaxFilterLength))
        )
        _ <- Either.cond(
          xs.distinct.size == xs.size,
          (),
          invalid(field, "must not contain duplicates"),
        )
      yield ()
    def year(field: String, y: Option[Int]): Either[RestRejection, Unit] =
      y.fold(Right(()))(v =>
        Either.cond(
          v >= PublicationYear.Min && v <= PublicationYear.Max,
          (),
          invalid(field, "must be a four-digit year"),
        )
      )
    def range(field: String, v: Int, lo: Int, hi: Int): Either[RestRejection, Unit] =
      Either.cond(v >= lo && v <= hi, (), invalid(field, s"must be in $lo..$hi"))
    for
      _ <- text("question", r.question, RestLimits.MaxQuestionLength)
      _ <- year("constraints.yearFrom", r.constraints.yearFrom)
      _ <- year("constraints.yearTo", r.constraints.yearTo)
      _ <- (r.constraints.yearFrom, r.constraints.yearTo) match
        case (Some(f), Some(t)) if f > t =>
          Left(invalid("constraints.yearFrom", "must not be after yearTo"))
        case _ => Right(())
      _ <- list("constraints.topics", r.constraints.topics)
      _ <- list("constraints.institutions", r.constraints.institutions)
      _ <- list("constraints.venues", r.constraints.venues)
      _ <- range("retrieval.graphLimit", r.retrieval.graphLimit, 0, RestLimits.MaxGraphLimit)
      _ <- range("retrieval.vectorLimit", r.retrieval.vectorLimit, 0, RestLimits.MaxVectorLimit)
      _ <- range("retrieval.maxPathDepth", r.retrieval.maxPathDepth, 1, RestLimits.MaxPathDepth)
    yield r

  /** 422 checks against what the deployment offers. Call after `parse` or `validate`. */
  def checkSupported(r: QueryRequest, caps: RestCapabilities): Either[RestRejection, QueryRequest] =
    import ApiErrorCode.*
    val graph  = r.retrieval.graphLimit > 0
    val vector = r.retrieval.vectorLimit > 0
    if !graph && !vector then
      Left(RestRejection(UnsupportedRetrievalPlan, "retrieval", "selects no retrieval mode"))
    else if graph && !caps.graphRetrieval then
      Left(
        RestRejection(
          UnsupportedRetrievalMode,
          "retrieval.graphLimit",
          "graph retrieval is not enabled in this deployment",
        )
      )
    else if vector && !caps.vectorRetrieval then
      Left(
        RestRejection(
          UnsupportedRetrievalMode,
          "retrieval.vectorLimit",
          "vector retrieval is not enabled in this deployment",
        )
      )
    else if r.analysis.rankingMetric.exists(m => !caps.rankingMetrics.contains(m)) then
      Left(RestRejection.unsupported("analysis.rankingMetric", "is not enabled in this deployment"))
    else if r.analysis.includeBridgeMetrics && !caps.bridgeMetrics then
      Left(
        RestRejection
          .unsupported("analysis.includeBridgeMetrics", "is not enabled in this deployment")
      )
    else if r.analysis.includeCommunities && !caps.communities then
      Left(
        RestRejection
          .unsupported("analysis.includeCommunities", "is not enabled in this deployment")
      )
    else if r.analysis.includePaths && !caps.paths then
      Left(RestRejection.unsupported("analysis.includePaths", "is not enabled in this deployment"))
    else Right(r)

  /** Canonical JSON of the normalized request (defaults filled in, fixed field order). */
  def canonicalJson(r: QueryRequest): String = WirePickle.write(r)

  /** SHA-256 over the canonical JSON: two bodies that mean the same request (whitespace, absent
    * sections versus defaults) have the same hash. This is the idempotency body hash.
    */
  def requestHash(r: QueryRequest): String =
    Hashing.digest("scholarchain.rest.query-request.v1", List(canonicalJson(r)))

  // ---- strict JSON mapping -------------------------------------------------------------------

  private type Res[A] = Either[RestRejection, A]

  /** Rejects bodies nested deeper than the bound before the parser recurses into them. */
  private def nestingOk(body: String): Boolean =
    var depth    = 0
    var inString = false
    var escape   = false
    var i        = 0
    var ok       = true
    while ok && i < body.length do
      val c = body.charAt(i)
      if inString then
        if escape then escape = false
        else if c == '\\' then escape = true
        else if c == '"' then inString = false
      else if c == '"' then inString = true
      else if c == '{' || c == '[' then
        depth += 1
        if depth > RestLimits.MaxJsonNesting then ok = false
      else if c == '}' || c == ']' then depth -= 1
      i += 1
    ok

  private def obj(
      path: String,
      v: ujson.Value,
      allowed: Set[String],
  ): Res[Map[String, ujson.Value]] =
    v match
      case o: ujson.Obj =>
        if o.value.keys.forall(allowed.contains) then Right(o.value.toMap)
        else Left(RestRejection.unknownField(path))
      case _ => Left(RestRejection.invalid(path, "must be an object"))

  private def str(path: String, v: ujson.Value): Res[String] = v match
    case ujson.Str(s) => Right(s)
    case _            => Left(RestRejection.invalid(path, "must be a string"))

  private def bool(path: String, v: Option[ujson.Value], default: Boolean): Res[Boolean] = v match
    case None                => Right(default)
    case Some(ujson.Bool(b)) => Right(b)
    case Some(_)             => Left(RestRejection.invalid(path, "must be a boolean"))

  private def int(path: String, v: ujson.Value): Res[Int] = v match
    case ujson.Num(d) if d.isWhole && d.abs <= Int.MaxValue.toDouble => Right(d.toInt)
    case _ => Left(RestRejection.invalid(path, "must be an integer"))

  private def optInt(path: String, v: Option[ujson.Value]): Res[Option[Int]] = v match
    case None | Some(ujson.Null) => Right(None)
    case Some(x)                 => int(path, x).map(Some(_))

  private def intOr(path: String, v: Option[ujson.Value], default: Int): Res[Int] = v match
    case None    => Right(default)
    case Some(x) => int(path, x)

  private def strList(path: String, v: Option[ujson.Value]): Res[List[String]] = v match
    case None | Some(ujson.Null) => Right(Nil)
    case Some(ujson.Arr(xs))     =>
      if xs.size > RestLimits.MaxFilterEntries then
        Left(
          RestRejection.invalid(path, s"must have at most ${RestLimits.MaxFilterEntries} entries")
        )
      else
        xs.toList.foldLeft[Res[List[String]]](Right(Nil))((acc, x) =>
          acc.flatMap(l => str(path, x).map(l :+ _))
        )
    case Some(_) => Left(RestRejection.invalid(path, "must be an array of strings"))

  private def section(
      path: String,
      parent: Map[String, ujson.Value],
      allowed: Set[String],
  ) =
    parent.get(path) match
      case None | Some(ujson.Null) => Right(None)
      case Some(v)                 => obj(path, v, allowed).map(Some(_))

  private val RankToken = """^[A-Z][A-Z0-9_]{0,63}$""".r

  private def fromJson(tree: ujson.Value): Res[QueryRequest] =
    for
      top <- obj("body", tree, Set("question", "constraints", "analysis", "retrieval", "output"))
      q   <- top.get("question") match
        case None    => Left(RestRejection.invalid("question", "is required"))
        case Some(v) => str("question", v)
      cons <- section(
        "constraints",
        top,
        Set("yearFrom", "yearTo", "topics", "institutions", "venues"),
      )
      constraints <- cons match
        case None    => Right(Defaults.Constraints)
        case Some(c) =>
          for
            f  <- optInt("constraints.yearFrom", c.get("yearFrom"))
            t  <- optInt("constraints.yearTo", c.get("yearTo"))
            tp <- strList("constraints.topics", c.get("topics"))
            in <- strList("constraints.institutions", c.get("institutions"))
            vn <- strList("constraints.venues", c.get("venues"))
          yield QueryConstraints(f, t, tp, in, vn)
      ana <- section(
        "analysis",
        top,
        Set(
          "rankingMetric",
          "includeTeamSize",
          "includeBridgeMetrics",
          "includeCommunities",
          "includePaths",
        ),
      )
      analysis <- ana match
        case None    => Right(Defaults.Analysis)
        case Some(a) =>
          for
            m <- a.get("rankingMetric") match
              case None | Some(ujson.Null)                    => Right(None)
              case Some(ujson.Str(s)) if RankToken.matches(s) =>
                RankingMetric.parse(s) match
                  case Some(rm) => Right(Some(rm))
                  case None     =>
                    Left(
                      RestRejection.unsupported(
                        "analysis.rankingMetric",
                        "is not a supported ranking metric",
                      )
                    )
              case Some(ujson.Str(_)) =>
                Left(RestRejection.invalid("analysis.rankingMetric", "must be an upper-case token"))
              case Some(_) =>
                Left(RestRejection.invalid("analysis.rankingMetric", "must be a string"))
            ts <- bool("analysis.includeTeamSize", a.get("includeTeamSize"), false)
            bm <- bool("analysis.includeBridgeMetrics", a.get("includeBridgeMetrics"), false)
            cm <- bool("analysis.includeCommunities", a.get("includeCommunities"), false)
            ph <- bool("analysis.includePaths", a.get("includePaths"), false)
          yield AnalysisOptions(m, ts, bm, cm, ph)
      ret       <- section("retrieval", top, Set("graphLimit", "vectorLimit", "maxPathDepth"))
      retrieval <- ret match
        case None    => Right(Defaults.Retrieval)
        case Some(r) =>
          for
            g <- intOr("retrieval.graphLimit", r.get("graphLimit"), RestLimits.DefaultGraphLimit)
            v <- intOr("retrieval.vectorLimit", r.get("vectorLimit"), RestLimits.DefaultVectorLimit)
            d <- intOr("retrieval.maxPathDepth", r.get("maxPathDepth"), RestLimits.MaxPathDepth)
          yield RetrievalOptions(g, v, d)
      out <- section("output", top, Set("includeEvidence", "includeScores", "includeUncertainty"))
      output <- out match
        case None    => Right(Defaults.Output)
        case Some(o) =>
          for
            e <- bool("output.includeEvidence", o.get("includeEvidence"), true)
            s <- bool("output.includeScores", o.get("includeScores"), true)
            u <- bool("output.includeUncertainty", o.get("includeUncertainty"), true)
          yield OutputOptions(e, s, u)
      req <- validate(QueryRequest(q, constraints, analysis, retrieval, output))
    yield req
