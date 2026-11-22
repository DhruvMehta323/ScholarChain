package scholarchain.storage

import scholarchain.core.*

/** Executes one openCypher query with a `parameters` JSON document. The only implementation that
  * talks to AWS is `NeptuneAws.executor`; tests use a recording fake.
  */
trait CypherExecutor:
  def execute(
      cypher: String,
      parametersJson: String,
      timeoutMillis: Long,
  ): StorageResult[List[GraphRow]]

/** Starts and polls Neptune bulk-load jobs. */
trait BulkLoader:
  def start(request: NeptuneLoaderRequest): StorageResult[BulkLoadHandle]

  def status(handle: BulkLoadHandle): StorageResult[BulkLoadStatus]

/** Body of the Neptune `StartLoaderJob` call, built from configuration only. Field names follow
  * https://docs.aws.amazon.com/neptune/latest/userguide/load-api-reference-load.html (accessed
  * 2026-11-21): `source`, `format` (`opencypher` for openCypher CSV), `iamRoleArn`, `mode`,
  * `failOnError`, `parallelism`, `updateSingleCardinalityProperties`, `queueRequest`, region.
  */
final case class NeptuneLoaderRequest(
    source: String,
    format: String,
    iamRoleArn: String,
    s3BucketRegion: String,
    mode: String,
    failOnError: Boolean,
    parallelism: String,
    updateSingleCardinalityProperties: Boolean,
    queueRequest: Boolean,
)

object NeptuneBulkLoad:
  /** Builds the loader request. `source` must lie under the configured source prefix (a caller
    * cannot point the loader at another bucket), `format` must equal the configured one, and the
    * role comes from configuration only.
    *
    * Idempotency choices: `mode = AUTO` (a load from the same source resumes a previous job instead
    * of duplicating work), `failOnError = true` (stop at the first bad row), and
    * `updateSingleCardinalityProperties = false` (the default): reloading identical data is a no-op
    * because IDs are deterministic, and a CHANGED value of an existing single-cardinality property
    * is an error instead of a silent overwrite.
    */
  def buildRequest(
      cfg: StorageNeptuneLoad,
      request: BulkLoadRequest,
      region: String,
  ): StorageResult[NeptuneLoaderRequest] =
    if !ObjectLocation.parse(request.sourceUri).isRight || !request.sourceUri.endsWith("/") then
      Left(StorageError.InvalidRequest("source must be an s3://<bucket>/<prefix>/ URI"))
    else if !request.sourceUri.startsWith(cfg.sourceS3Uri) then
      Left(StorageError.InvalidRequest("source is outside the configured load prefix"))
    else if request.sourceUri.contains("/../") then
      Left(StorageError.InvalidRequest("source contains '..'"))
    else if request.format != cfg.format then
      Left(StorageError.InvalidRequest("format differs from the configured format"))
    else if !region.matches("^[a-z]{2}(-[a-z]+)+-[0-9]$") then
      Left(StorageError.InvalidRequest("invalid region"))
    else
      Right(
        NeptuneLoaderRequest(
          source = request.sourceUri,
          format = cfg.format,
          iamRoleArn = cfg.roleArn,
          s3BucketRegion = region,
          mode = "AUTO",
          failOnError = true,
          parallelism = "MEDIUM",
          updateSingleCardinalityProperties = false,
          queueRequest = false,
        )
      )

/** Parameter kinds of a template. */
enum ParamKind:
  /** A researcher ID: a string accepted by `Ids.checkResearcherId`. */
  case ResearcherId

  /** A window label `YYYY-YYYY`. */
  case Window

  /** An integer in `1..bounds.maxDepth`. */
  case Hops

/** A template parameter: its name and kind; optional parameters may be omitted. */
final case class ParamSpec(name: String, kind: ParamKind, required: Boolean = true)

/** One allowlisted query. `cypher` is a constant: the only holes are `{{DEPTH}}` and `{{TIMEOUT}}`,
  * which are replaced by integers computed from validated bounds (never by request text); every
  * request value travels in the `parameters` JSON as `$name`.
  */
final case class QueryTemplate(
    name: String,
    params: List[ParamSpec],
    cypher: String,
    usesDepth: Boolean,
)

/** The allowlist of read-only graph queries (the core `GraphTemplates` names), with their bounds.
  *
  * Bounds: every call needs `QueryBounds` with `maxDepth` in `1..ceiling.maxDepth`, `maxResults` in
  * `1..ceiling.maxResults`, `timeoutMillis` in `1..ceiling.timeoutMillis`, where the ceiling is the
  * configured bound (itself capped by `HardMaxDepth`, `HardMaxResults`, `HardMaxTimeoutMillis`).
  * The result limit is the `$limit` parameter (`maxResults + 1`, to detect truncation); the timeout
  * is the query hint `USING QUERY:TIMEOUTMILLISECONDS <n>` (hint syntax:
  * https://docs.aws.amazon.com/neptune/latest/userguide/opencypher-query-hints.html, accessed
  * 2026-11-21) plus a client-side call timeout; depth is the bound of the variable-length pattern.
  *
  * The Cypher texts have NOT been run against a Neptune cluster (no AWS in Phase 07); the live
  * `NeptuneReadAwsItSuite` is the first execution.
  */
object GraphTemplateCatalog:
  val HardMaxDepth: Int          = 10
  val HardMaxResults: Int        = 1000
  val HardMaxTimeoutMillis: Long = 60000L

  private val WindowPattern = "^[0-9]{4}-[0-9]{4}$".r

  private val CoauthorReturn =
    "id(r) AS edgeId, r.window AS window, r.jointPublicationCount AS jointPublicationCount, " +
      "r.firstCollaborationYear AS firstCollaborationYear, " +
      "r.lastCollaborationYear AS lastCollaborationYear, " +
      "r.supportingWorksDigest AS supportingWorksDigest, r.evidenceTable AS evidenceTable, " +
      "r.evidenceKey AS evidenceKey, r.evidencePartition AS evidencePartition"

  val Hint: String = "USING QUERY:TIMEOUTMILLISECONDS {{TIMEOUT}} "

  val templates: Map[String, QueryTemplate] = List(
    QueryTemplate(
      GraphTemplates.Neighbors,
      List(
        ParamSpec("researcherId", ParamKind.ResearcherId),
        ParamSpec("window", ParamKind.Window),
      ),
      Hint + "MATCH (a:Researcher)-[r:COAUTHORED_WITH]-(b:Researcher) " +
        "WHERE id(a) = $researcherId AND r.window = $window " +
        s"RETURN id(b) AS researcherId, $CoauthorReturn " +
        "ORDER BY jointPublicationCount DESC, researcherId ASC LIMIT $limit",
      usesDepth = false,
    ),
    QueryTemplate(
      GraphTemplates.CoauthorEdge,
      List(
        ParamSpec("researcherA", ParamKind.ResearcherId),
        ParamSpec("researcherB", ParamKind.ResearcherId),
        ParamSpec("window", ParamKind.Window),
      ),
      Hint + "MATCH (a:Researcher)-[r:COAUTHORED_WITH]-(b:Researcher) " +
        "WHERE id(a) = $researcherA AND id(b) = $researcherB AND r.window = $window " +
        s"RETURN $CoauthorReturn ORDER BY edgeId ASC LIMIT $$limit",
      usesDepth = false,
    ),
    QueryTemplate(
      GraphTemplates.ShortestPath,
      List(
        ParamSpec("from", ParamKind.ResearcherId),
        ParamSpec("to", ParamKind.ResearcherId),
        ParamSpec("window", ParamKind.Window),
        ParamSpec("maxHops", ParamKind.Hops, required = false),
      ),
      Hint + "MATCH (a:Researcher), (b:Researcher) WHERE id(a) = $from AND id(b) = $to " +
        "MATCH p = shortestPath((a)-[:COAUTHORED_WITH*1..{{DEPTH}}]-(b)) " +
        "WHERE all(r IN relationships(p) WHERE r.window = $window) " +
        "RETURN [n IN nodes(p) | id(n)] AS hops, [r IN relationships(p) | id(r)] AS edgeIds, " +
        "length(p) AS length LIMIT $limit",
      usesDepth = true,
    ),
    QueryTemplate(
      GraphTemplates.Component,
      List(
        ParamSpec("researcherId", ParamKind.ResearcherId),
        ParamSpec("window", ParamKind.Window),
      ),
      Hint + "MATCH (a:Researcher)-[m:IN_COMPONENT]->(c:Component) " +
        "WHERE id(a) = $researcherId AND m.window = $window " +
        "RETURN id(c) AS componentId, c.size AS size, c.window AS window LIMIT $limit",
      usesDepth = false,
    ),
    QueryTemplate(
      GraphTemplates.Community,
      List(
        ParamSpec("researcherId", ParamKind.ResearcherId),
        ParamSpec("window", ParamKind.Window),
      ),
      Hint + "MATCH (a:Researcher)-[m:MEMBER_OF]->(c:Community) " +
        "WHERE id(a) = $researcherId AND m.window = $window " +
        "RETURN id(c) AS communityId, c.size AS size, c.method AS method, c.window AS window, " +
        "c.internalEdgeCount AS internalEdgeCount, c.crossEdgeCount AS crossEdgeCount LIMIT $limit",
      usesDepth = false,
    ),
    // Degree-based metrics computed from the graph. The authoritative metrics (ResearcherMetricsV2)
    // are the DynamoDB `researcher-metrics` items; this template needs no second copy of them.
    QueryTemplate(
      GraphTemplates.ResearcherMetrics,
      List(
        ParamSpec("researcherId", ParamKind.ResearcherId),
        ParamSpec("window", ParamKind.Window),
      ),
      Hint + "MATCH (a:Researcher)-[r:COAUTHORED_WITH]-(b:Researcher) " +
        "WHERE id(a) = $researcherId AND r.window = $window " +
        "RETURN id(a) AS researcherId, count(b) AS coauthorCount, " +
        "sum(r.jointPublicationCount) AS weightedDegree LIMIT $limit",
      usesDepth = false,
    ),
  ).map(t => t.name -> t).toMap

  require(
    templates.keySet == GraphTemplates.all.toSet,
    "catalog must cover exactly the core allowlist",
  )

  /** A query ready to send: constant text, parameter JSON, effective limits. */
  final case class Prepared(cypher: String, parametersJson: String, bounds: QueryBounds)

  def checkCeiling(ceiling: QueryBounds): Either[StorageError, QueryBounds] =
    QueryBounds.validate(ceiling).left.map(e => StorageError.InvalidRequest(e.message)).flatMap {
      c =>
        if c.maxDepth <= HardMaxDepth && c.maxResults <= HardMaxResults && c.timeoutMillis <= HardMaxTimeoutMillis
        then Right(c)
        else Left(StorageError.InvalidRequest("configured bounds exceed the hard ceilings"))
    }

  def prepare(q: GraphQuery, ceiling: QueryBounds): Either[StorageError, Prepared] =
    for
      c <- checkCeiling(ceiling)
      t <- templates.get(q.template).toRight(StorageError.InvalidRequest("unknown template"))
      b <- QueryBounds.validate(q.bounds).left.map(e => StorageError.InvalidRequest(e.message))
      _ <-
        if b.maxDepth <= c.maxDepth && b.maxResults <= c.maxResults && b.timeoutMillis <= c.timeoutMillis
        then Right(())
        else Left(StorageError.InvalidRequest("bounds exceed the configured ceiling"))
      known = t.params.map(_.name).toSet
      _ <-
        if q.parameters.keySet.subsetOf(known) then Right(())
        else Left(StorageError.InvalidRequest("unknown parameter"))
      values <- t.params.foldRight[Either[StorageError, List[(String, ujson.Value)]]](Right(Nil)) {
        (p, acc) =>
          for
            rest <- acc
            one  <- value(p, q.parameters.get(p.name), b)
          yield one.toList ::: rest
      }
      depth = values
        .collectFirst { case ("maxHops", ujson.Num(d)) => d.toInt }
        .getOrElse(b.maxDepth)
    yield
      val withLimit =
        (values.filterNot(_._1 == "maxHops")) :+ ("limit" -> ujson.Num((b.maxResults + 1).toDouble))
      val text = t.cypher
        .replace("{{TIMEOUT}}", b.timeoutMillis.toString)
        .replace("{{DEPTH}}", depth.toString)
      Prepared(text, ujson.write(ujson.Obj.from(withLimit.sortBy(_._1))), b)

  private def value(
      p: ParamSpec,
      given_ : Option[GraphValue],
      b: QueryBounds,
  ): Either[StorageError, Option[(String, ujson.Value)]] =
    given_ match
      case None =>
        if p.required then Left(StorageError.InvalidRequest(s"missing parameter '${p.name}'"))
        else Right(None)
      case Some(v) =>
        (p.kind, v) match
          case (ParamKind.ResearcherId, GraphValue.Str(s)) =>
            if Ids.checkResearcherId(s).isRight then Right(Some(p.name -> ujson.Str(s)))
            else Left(StorageError.InvalidRequest(s"parameter '${p.name}' is not a researcher ID"))
          case (ParamKind.Window, GraphValue.Str(s)) =>
            if WindowPattern.matches(s) then Right(Some(p.name -> ujson.Str(s)))
            else Left(StorageError.InvalidRequest(s"parameter '${p.name}' is not a window label"))
          case (ParamKind.Hops, GraphValue.Num(n)) =>
            if n >= 1 && n <= b.maxDepth.toLong then Right(Some(p.name -> ujson.Num(n.toDouble)))
            else
              Left(StorageError.InvalidRequest(s"parameter '${p.name}' is outside the depth bound"))
          case _ => Left(StorageError.InvalidRequest(s"parameter '${p.name}' has the wrong type"))

/** `GraphStore` over an executor and a bulk loader. Read-only: it exposes only the allowlisted
  * templates. `ceiling` is the configured query bound.
  */
final class TemplateGraphStore(
    ceiling: QueryBounds,
    neptune: StorageNeptuneLoad,
    region: String,
    executor: CypherExecutor,
    loader: BulkLoader,
) extends GraphStore:

  def query(q: GraphQuery): StorageResult[GraphResult] =
    GraphTemplateCatalog.prepare(q, ceiling).flatMap { p =>
      executor.execute(p.cypher, p.parametersJson, p.bounds.timeoutMillis).map { rows =>
        GraphResult(rows.take(p.bounds.maxResults), rows.size > p.bounds.maxResults)
      }
    }

  def submitBulkLoad(request: BulkLoadRequest): StorageResult[BulkLoadHandle] =
    NeptuneBulkLoad.buildRequest(neptune, request, region).flatMap(loader.start)

  def bulkLoadStatus(handle: BulkLoadHandle): StorageResult[BulkLoadStatus] = loader.status(handle)

/** Recording fake of the executor and loader (no AWS classes): returns canned rows and remembers
  * every call, so tests can assert on the exact text and parameters that would be sent.
  */
final class RecordingCypherExecutor(rows: List[GraphRow] = Nil) extends CypherExecutor:
  final case class Call(cypher: String, parametersJson: String, timeoutMillis: Long)

  private val calls0 = scala.collection.mutable.ListBuffer.empty[Call]

  def execute(
      cypher: String,
      parametersJson: String,
      timeoutMillis: Long,
  ): StorageResult[List[GraphRow]] =
    synchronized {
      calls0 += Call(cypher, parametersJson, timeoutMillis)
      Right(rows)
    }

  def calls: List[Call] = synchronized(calls0.toList)

final class FakeBulkLoader extends BulkLoader:
  private val started = scala.collection.mutable.ListBuffer.empty[NeptuneLoaderRequest]

  def start(request: NeptuneLoaderRequest): StorageResult[BulkLoadHandle] = synchronized {
    started += request
    Right(BulkLoadHandle(s"fake-load-${started.size}"))
  }

  def status(handle: BulkLoadHandle): StorageResult[BulkLoadStatus] = Right(
    BulkLoadStatus.Completed(None)
  )

  def requests: List[NeptuneLoaderRequest] = synchronized(started.toList)
