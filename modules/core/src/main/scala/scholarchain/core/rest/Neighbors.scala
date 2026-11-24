package scholarchain.core.rest

import java.nio.charset.StandardCharsets
import java.util.Base64

import scala.util.control.NonFatal

import scholarchain.core.*

/** Opaque pagination cursor of a neighbors page: the last key returned and a hash of the request
  * scope (node, direction, depth, edge types), so a cursor cannot be replayed against a different
  * query. It is base64url (no padding) of a small JSON object and at most 512 characters. It is NOT
  * a security token (core holds no secret): the service still authorizes and bounds every page, and
  * treats `afterKey` as untrusted input.
  */
final case class NeighborCursor(afterKey: String, scopeHash: String):
  def encode: String =
    val js = ujson.Obj("v" -> 1, "k" -> afterKey, "s" -> scopeHash)
    Base64.getUrlEncoder.withoutPadding.encodeToString(
      ujson.write(js).getBytes(StandardCharsets.UTF_8)
    )

object NeighborCursor:
  private val Charset = """^[A-Za-z0-9_\-]+$""".r

  def isWellFormed(c: String): Boolean =
    c.nonEmpty && c.length <= RestLimits.MaxCursorLength && Charset.matches(c)

  /** Decodes and checks a cursor. Total: never throws. */
  def decode(c: String): Either[RestRejection, NeighborCursor] =
    val bad = RestRejection.invalid("cursor", "is not a valid cursor")
    if !isWellFormed(c) then Left(bad)
    else
      try
        val js  = ujson.read(new String(Base64.getUrlDecoder.decode(c), StandardCharsets.UTF_8))
        val o   = js.obj
        val key = o("k").str
        val s   = o("s").str
        val ver = o("v").num
        if ver == 1.0 && key.nonEmpty && key.length <= RestLimits.MaxIdLength &&
          !RuntimeChecks.hasControl(key) && Hashing.isSha256Hex(s)
        then Right(NeighborCursor(key, s))
        else Left(bad)
      catch case NonFatal(_) => Left(bad)

/** Parameters of `GET /v1/graph/nodes/{nodeId}/neighbors`. Bounded by construction: depth 1..3
  * (default 1), limit 1..200 (default 50), at most five distinct edge types from the closed
  * `EdgeType` allowlist (empty means all five). Exploration beyond these bounds cannot be
  * expressed.
  */
final case class NeighborsRequest(
    nodeId: String,
    direction: NeighborDirection,
    depth: Int,
    limit: Int,
    edgeTypes: List[EdgeType],
    cursor: Option[String] = None,
):
  /** The edge types to traverse: the request's, or all five when none were named. */
  def effectiveEdgeTypes: List[EdgeType] =
    if edgeTypes.isEmpty then EdgeType.values.toList else edgeTypes

  /** Hash of everything that defines the result set except `limit` and `cursor`. */
  def scopeHash: String =
    Hashing.digest(
      "scholarchain.rest.neighbors-scope.v1",
      List(nodeId, direction.wire, depth.toString) ++ effectiveEdgeTypes.map(_.wire).sorted,
    )

  /** The decoded cursor, checked against this request's scope. */
  def decodedCursor: Either[RestRejection, Option[NeighborCursor]] = cursor match
    case None    => Right(None)
    case Some(c) =>
      NeighborCursor.decode(c).flatMap { d =>
        if d.scopeHash == scopeHash then Right(Some(d))
        else Left(RestRejection.invalid("cursor", "does not belong to this request"))
      }

object NeighborsRequest:
  val Params: Set[String] = Set("direction", "depth", "limit", "edgeTypes", "cursor")
  private val EdgeToken   = """^[A-Z][A-Z_]{0,31}$""".r
  private val Digits      = """^[0-9]{1,4}$""".r

  /** Builds a request from decoded query parameters. Strict: an unknown or repeated parameter is a
    * 400, a value outside a bound is a 400, an edge type that is well formed but not on the
    * allowlist is a 422. Messages never contain the supplied value.
    */
  def fromParams(
      nodeId: String,
      params: Map[String, List[String]],
  ): Either[RestRejection, NeighborsRequest] =
    def single(name: String): Either[RestRejection, Option[String]] =
      params.get(name) match
        case None           => Right(None)
        case Some(Nil)      => Right(None)
        case Some(v :: Nil) =>
          if v.length <= 256 then Right(Some(v))
          else Left(RestRejection.invalid(name, "is too long"))
        case Some(_) => Left(RestRejection.invalid(name, "must be given at most once"))
    def number(name: String, default: Int, lo: Int, hi: Int): Either[RestRejection, Int] =
      single(name).flatMap {
        case None                                                           => Right(default)
        case Some(v) if Digits.matches(v) && v.toInt >= lo && v.toInt <= hi => Right(v.toInt)
        case Some(_) => Left(RestRejection.invalid(name, s"must be an integer in $lo..$hi"))
      }
    def edges(raw: Option[String]): Either[RestRejection, List[EdgeType]] = raw match
      case None    => Right(Nil)
      case Some(s) =>
        val parts = s.split(",", -1).toList
        if parts.size > EdgeType.values.length then
          Left(RestRejection.invalid("edgeTypes", "names too many edge types"))
        else
          parts.foldLeft[Either[RestRejection, List[EdgeType]]](Right(Nil))((acc, p) =>
            acc.flatMap { l =>
              if !EdgeToken.matches(p) then
                Left(
                  RestRejection
                    .invalid("edgeTypes", "must be a comma-separated list of upper-case edge types")
                )
              else
                EdgeType.parse(p) match
                  case None =>
                    Left(
                      RestRejection.unsupported(
                        "edgeTypes",
                        "names an edge type that is not on the allowlist",
                      )
                    )
                  case Some(t) if l.contains(t) =>
                    Left(RestRejection.invalid("edgeTypes", "must not repeat an edge type"))
                  case Some(t) => Right(l :+ t)
            }
          )
    for
      id <- RestIds.checkNodeId(nodeId)
      _ <- Either.cond(params.keys.forall(Params.contains), (), RestRejection.unknownField("query"))
      dir <- single("direction").flatMap {
        case None    => Right(NeighborDirection.Both)
        case Some(v) =>
          NeighborDirection
            .parse(v)
            .toRight(RestRejection.invalid("direction", "must be in, out or both"))
      }
      depth <- number("depth", RestLimits.DefaultNeighborDepth, 1, RestLimits.MaxNeighborDepth)
      limit <- number("limit", RestLimits.DefaultNeighborLimit, 1, RestLimits.MaxNeighborLimit)
      et    <- single("edgeTypes").flatMap(edges)
      cur   <- single("cursor").flatMap {
        case None    => Right(None)
        case Some(c) =>
          Either.cond(
            NeighborCursor.isWellFormed(c),
            Some(c),
            RestRejection.invalid("cursor", "is not a valid cursor"),
          )
      }
    yield NeighborsRequest(id, dir, depth, limit, et, cur)
