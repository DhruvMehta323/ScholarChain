package scholarchain.core.rest

import java.nio.charset.StandardCharsets

import scala.util.Try

import scholarchain.core.*
import ValidationError.*

/** Hard bounds of the REST contracts. Every string, list and map in a REST record is bounded by one
  * of these (or by a bound stated in the record's Scaladoc).
  */
object RestLimits:
  val MaxBodyBytes: Int           = 32768
  val MaxJsonNesting: Int         = 8
  val MaxQuestionLength: Int      = QueryPlanV2.MaxQuestionLength
  val MaxFilterEntries: Int       = 10
  val MaxFilterLength: Int        = 128
  val MaxGraphLimit: Int          = QueryLimits.MaxResultCount
  val MaxVectorLimit: Int         = 100
  val MaxPathDepth: Int           = QueryLimits.MaxPathDepth
  val DefaultGraphLimit: Int      = 100
  val DefaultVectorLimit: Int     = 30
  val MaxSummaryLength: Int       = 4000
  val MaxResearchers: Int         = 100
  val MaxClaims: Int              = 100
  val MaxClaimTextLength: Int     = 1000
  val MaxEvidenceIdsPerClaim: Int = 100
  val MaxTeamWorkIds: Int         = 20
  val MaxEvidenceTextChars: Int   = 1500
  val MaxIdLength: Int            = 200
  val MaxUriLength: Int           = 1100
  val MaxPathNodes: Int           = QueryLimits.MaxPathDepth + 1
  val MaxProvenanceInputs: Int    = 20
  val MaxNodeProperties: Int      = 16
  val MaxPropertyValueLength: Int = 256
  val MaxEdgeEvidenceIds: Int     = 20
  val MaxNeighborDepth: Int       = 3
  val DefaultNeighborDepth: Int   = 1
  val MaxNeighborLimit: Int       = 200
  val DefaultNeighborLimit: Int   = 50
  val MaxCursorLength: Int        = 512
  val MaxMessageLength: Int       = 500
  val MaxExplainEntries: Int      = 50
  val MaxExplainEvidenceIds: Int  = 200
  val MaxHealthChecks: Int        = 20
  val MaxResultJsonBytes: Int     = 300000

/** Why a request was refused, split by the HTTP status it maps to: a 400 code is a malformed
  * request or invalid field, a 422 code is a well-formed request that asks for something this
  * deployment does not support. `field` and `reason` are fixed strings chosen by the contract; they
  * never contain a value taken from the request (so they are safe to log and to return).
  */
final case class RestRejection(code: ApiErrorCode, field: String, reason: String):
  def httpStatus: Int        = code.httpStatus
  def isMalformed: Boolean   = httpStatus == 400
  def isUnsupported: Boolean = httpStatus == 422
  def message: String        = s"$field: $reason".take(RestLimits.MaxMessageLength)

  /** The typed error body for this rejection. `traceId` must satisfy `TraceIds.isValid`. */
  def toApiError(traceId: String): ApiError =
    ApiError(code, message, traceId, code.retryable)

object RestRejection:
  def invalid(field: String, reason: String): RestRejection =
    RestRejection(ApiErrorCode.InvalidField, field, reason)
  def unknownField(parent: String): RestRejection =
    RestRejection(
      ApiErrorCode.UnknownField,
      parent,
      "contains a field that is not part of the contract",
    )
  def unsupported(field: String, reason: String): RestRejection =
    RestRejection(ApiErrorCode.UnsupportedConstraint, field, reason)

  /** A core `ValidationError` as a 400 rejection (the field name is kept, values are not). */
  def fromValidation(e: ValidationError): RestRejection = e match
    case InvalidField(f, r) => invalid(f, r)
    case MissingField(f)    => invalid(f, "is required")
    case _                  => invalid("request", "is not valid")

/** Shared checks (internal). */
private[core] object RestChecks:
  import V.*

  val TokenPattern = """^[A-Za-z0-9][A-Za-z0-9._:\-]{0,127}$""".r

  def token(field: String, s: String, max: Int = 128): R =
    if s.length <= max && TokenPattern.matches(s) then ok
    else Left(InvalidField(field, s"must be a token of 1 to $max characters"))

  def optText(field: String, s: Option[String], max: Int): R =
    s.fold(ok)(RuntimeChecks.text(field, _, max))

  def uri(field: String, s: String): R =
    if s.length <= RestLimits.MaxUriLength && s.nonEmpty && !s.exists(c =>
        c.isWhitespace || Character.isISOControl(c)
      )
    then ok
    else Left(InvalidField(field, "must be a URI without whitespace or control characters"))

  def relativePath(field: String, s: String): R =
    if s.startsWith("/v1/") then uri(field, s)
    else Left(InvalidField(field, "must be a path starting with /v1/"))

  def nonNegative(field: String, v: Long): R =
    if v >= 0L then ok else Left(InvalidField(field, "must be >= 0"))

  def reasonCode(field: String, s: String): R =
    if RuntimeChecks.ReasonCode.matches(s) then ok
    else Left(InvalidField(field, "must match [a-z][a-z0-9_]{0,63}"))

  def finite(field: String, d: Double): R = V.finite(field, d)

/** Trace and request IDs. A caller-supplied value is accepted only when it is a bounded token; any
  * other value must be replaced by a generated one (never echoed).
  */
object TraceIds:
  def isValid(s: String): Boolean = RestChecks.TokenPattern.matches(s)

  /** The header value when it is valid, otherwise `None` (the service then generates one). */
  def accept(header: Option[String]): Option[String] = header.filter(isValid)

/** Header names of the REST contract. Names are constants; the contract name/version header is the
  * only place a schema version appears outside the URL path (see `REST-CONTRACTS.md`).
  */
object RestHeaders:
  val TraceId: String        = "X-Trace-Id"
  val RequestId: String      = "X-Request-Id"
  val IdempotencyKey: String = "Idempotency-Key"
  val Contract: String       = "X-ScholarChain-Contract"

/** Contract names carried in the `X-ScholarChain-Contract` response header, `<name>/<version>`. */
object RestContracts:
  val ApiVersion: String    = "v1"
  val QueryRequest: String  = "rest.query-request/1"
  val QueryResponse: String = "rest.query-response/1"
  val JobStatus: String     = "rest.job-status/1"
  val Evidence: String      = "rest.evidence/1"
  val Node: String          = "rest.graph-node/1"
  val Neighbors: String     = "rest.neighbors-page/1"
  val Explain: String       = "rest.explain/1"
  val Error: String         = "rest.api-error/1"
  val Health: String        = "rest.health/1"

/** Idempotency-Key header values. */
object IdempotencyKeys:
  def check(key: String): Either[RestRejection, String] =
    if TraceIds.isValid(key) then Right(key)
    else Left(RestRejection.invalid("Idempotency-Key", "must be a token of 1 to 128 characters"))

/** Identifiers of the REST surface. Core keeps its own reference forms (`query:`, `job:`): the
  * spec's `qry-01J...` examples are illustrations. Path-segment values must be percent-encoded with
  * `RestPaths`.
  */
object RestIds:
  private val QueryIdPattern = """^query:[A-Za-z0-9][A-Za-z0-9._\-]{5,127}$""".r
  private val JobIdPattern   = """^job:[A-Za-z0-9][A-Za-z0-9\-]{5,63}$""".r

  /** `job:<32 hex>` derived from a caller-supplied seed (random in production, fixed in tests). */
  def newJobId(seed: String): String =
    "job:" + Hashing.digest("scholarchain.rest.job.v1", List(seed)).take(32)

  def isQueryId(s: String): Boolean = QueryIdPattern.matches(s)
  def isJobId(s: String): Boolean   = JobIdPattern.matches(s)

  def checkQueryId(s: String): Either[RestRejection, String] =
    Either.cond(isQueryId(s), s, RestRejection.invalid("queryId", "is not a valid query id"))

  def checkJobId(s: String): Either[RestRejection, String] =
    Either.cond(isJobId(s), s, RestRejection.invalid("jobId", "is not a valid job id"))

  def checkEvidenceId(s: String): Either[RestRejection, String] =
    Ids
      .checkEvidenceId(s)
      .left
      .map(_ => RestRejection.invalid("evidenceId", "is not a valid evidence id"))

  /** Graph node id: any core entity reference (`researcher:`, `work:`, ...), at most 200 chars. */
  def checkNodeId(s: String): Either[RestRejection, String] =
    if s.length <= RestLimits.MaxIdLength && !RuntimeChecks
        .hasControl(s) && Ids.checkAnyRef(s).isRight
    then Right(s)
    else Left(RestRejection.invalid("nodeId", "is not a valid node id"))

/** Relative URL builders and the strict decoder for one path segment. */
object RestPaths:
  private def unreserved(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
      c == '-' || c == '.' || c == '_' || c == '~' || c == ':'

  /** Percent-encodes one path segment (UTF-8). `:` stays literal (a valid path character). */
  def encode(segment: String): String =
    val sb = new StringBuilder
    segment.getBytes(StandardCharsets.UTF_8).foreach { b =>
      val c = (b & 0xff).toChar
      if b >= 0 && unreserved(c) then sb.append(c) else sb.append(f"%%${b & 0xff}%02X")
    }
    sb.toString

  /** Decodes one segment. `None` for a bad escape or invalid UTF-8; never throws. */
  def decode(raw: String): Option[String] =
    Try {
      val out = new java.io.ByteArrayOutputStream
      var i   = 0
      while i < raw.length do
        raw.charAt(i) match
          case '%' =>
            val hex = raw.substring(i + 1, i + 3)
            require(hex.forall(ch => Character.digit(ch, 16) >= 0))
            out.write(Integer.parseInt(hex, 16))
            i += 3
          case c =>
            val bytes = c.toString.getBytes(StandardCharsets.UTF_8)
            out.write(bytes, 0, bytes.length)
            i += 1
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(out.toByteArray))
        .toString
    }.toOption

  def queryEvidence(queryId: String): String = s"/v1/queries/${encode(queryId)}/evidence"
  def explain(queryId: String): String       = s"/v1/explain/${encode(queryId)}"
  def job(jobId: String): String             = s"/v1/jobs/${encode(jobId)}"
  def jobResult(jobId: String): String       = s"${job(jobId)}/result"
  def evidence(evidenceId: String): String   = s"/v1/evidence/${encode(evidenceId)}"
  def node(nodeId: String): String           = s"/v1/graph/nodes/${encode(nodeId)}"
  def neighbors(nodeId: String): String      = s"${node(nodeId)}/neighbors"
