package scholarchain.rag.runtime

import scholarchain.core.*

/** One coauthor edge as the graph template returned it, with its supporting works. */
final case class EdgeInfo(
    edgeId: String,
    first: String,
    second: String,
    window: String,
    jointPublicationCount: Long,
    firstYear: Option[Long],
    lastYear: Option[Long],
    digest: Option[String],
    workIds: List[String],
    worksTruncated: Boolean,
    inputs: List[InputRef],
)

/** Output of a graph stage that also yields IDs the workflow may use (neighbor IDs). */
final case class GraphOutput(stage: StageOutput, ids: List[String] = Nil)

/** Typed access to the six allowlisted graph templates. The ONLY way a stage reaches the graph: a
  * template name from `GraphTemplates`, typed parameters (`GraphValue`) and bounds. No query text
  * is ever built; the user's question and the researcher names never appear here (only resolved
  * researcher IDs and window labels do).
  */
private[runtime] final class TemplateCalls(graph: GraphStore, settings: RuntimeSettings):
  def bounds(depth: Int, results: Int): QueryBounds =
    QueryBounds(
      math.max(1, math.min(depth, settings.graphMaxDepth)),
      math.max(1, math.min(results, settings.graphMaxResults)),
      settings.graphTimeoutMillis.toLong,
    )

  def run(
      template: String,
      params: Map[String, GraphValue],
      depth: Int,
      results: Int,
  ): Either[RuntimeError, GraphResult] =
    if !GraphTemplates.all.contains(template) then Left(RuntimeError("unknown-template", template))
    else
      graph.query(GraphQuery(template, params, bounds(depth, results))) match
        case Left(e)  => Left(RuntimeError.fromStorage(s"graph template $template", e))
        case Right(r) => Right(r)

private[runtime] object EdgeSupport:
  val MaxWorksPerEdge: Int = 20

  def edgeFrom(
      reader: AnalyticsReader,
      row: GraphRow,
      subject: String,
      other: String,
      fallbackWindow: String,
  ): Either[RuntimeError, EdgeInfo] =
    Rows.str(row, "edgeId") match
      case None         => Left(RuntimeError("malformed-row", "edge row has no edge id"))
      case Some(edgeId) =>
        val count = Rows.long(row, "jointPublicationCount").getOrElse(0L)
        val works = reader.query("coauthor-edge-works", edgeId, MaxWorksPerEdge)
        works.map { ws =>
          val ids    = ws.flatMap(_.obj.value.get("workId").flatMap(_.strOpt))
          val (a, b) = if subject.compareTo(other) <= 0 then (subject, other) else (other, subject)
          EdgeInfo(
            edgeId,
            a,
            b,
            Rows.str(row, "window").getOrElse(fallbackWindow),
            count,
            Rows.long(row, "firstCollaborationYear"),
            Rows.long(row, "lastCollaborationYear"),
            Rows.str(row, "supportingWorksDigest"),
            ids,
            ids.size < count,
            ws.map(EvidenceSupport.inputRef(_, None)).take(MaxWorksPerEdge),
          )
        }

  def edgeFact(e: EdgeInfo, runId: Option[String], policy: Option[String]): Fact =
    val fields = ujson.Obj(
      "researcherA"           -> ujson.Str(e.first),
      "researcherB"           -> ujson.Str(e.second),
      "jointPublicationCount" -> ujson.Num(e.jointPublicationCount.toDouble),
      "supportingWorkIds"     -> ujson.Arr.from(e.workIds.map(ujson.Str(_))),
      "worksTruncated"        -> ujson.Bool(e.worksTruncated),
    )
    e.firstYear.foreach(y => fields("firstCollaborationYear") = ujson.Num(y.toDouble))
    e.lastYear.foreach(y => fields("lastCollaborationYear") = ujson.Num(y.toDouble))
    e.digest.foreach(d => fields("supportingWorksDigest") = ujson.Str(d))
    Fact(Fact.CoauthorEdge, e.edgeId, Some(e.window), runId, policy, fields)

  def edgeDraft(e: EdgeInfo, runId: Option[String], policy: Option[String]): EvidenceDraft =
    EvidenceDraft.fromFact(
      EvidenceType.CoauthorEdge,
      e.edgeId,
      s"${policy.getOrElse("coauthor-graph")}@${e.window}",
      edgeFact(e, runId, policy),
      e.inputs.headOption.toList ++ List(
        InputRef(s"graph:COAUTHORED_WITH#${e.edgeId}", policy.getOrElse("unversioned"), e.digest)
      ),
      Map("template" -> "coauthor-edge", "window" -> e.window),
    )

/** Stage 4: bounded path and collaborator retrieval over the parameterized graph templates
  * `neighbors`, `coauthor-edge` and `shortest-path`.
  *
  * Bounds: every call carries `QueryBounds(depth, results, timeout)` clamped to the configured
  * limits (`rag.graph.*`, hard caps depth 6 and 100 rows); the shortest-path depth is the plan's
  * `pathDepth` clamped the same way. Every hop is returned with its edge weight
  * (`jointPublicationCount`), the supporting work IDs (from the `coauthor-edge-works` table), the
  * first and last collaboration year and the provenance of the edge and its works. A path whose
  * returned shape is inconsistent (wrong endpoints, wrong length, a hop edge the edge template does
  * not confirm) is dropped and reported as missing; it is never repaired or guessed.
  */
final class PathFinderAgent(
    graph: GraphStore,
    reader: AnalyticsReader,
    settings: RuntimeSettings,
):
  private val calls = new TemplateCalls(graph, settings)

  private def runInfo(subject: String, window: String): (Option[String], Option[String]) =
    reader.get("researcher-metrics", subject, List(window)).toOption.flatten match
      case Some(it) =>
        (
          it.obj.value.get("provenanceRunId").flatMap(_.strOpt),
          it.obj.value.get("policyVersion").flatMap(_.strOpt),
        )
      case None => (None, None)

  /** Direct collaborators of `subject` in `window`, strongest first, at most `limit`. */
  def directCollaborators(subject: String, window: String, limit: Int): GraphOutput =
    calls.run(
      GraphTemplates.Neighbors,
      Map("researcherId" -> GraphValue.Str(subject), "window" -> GraphValue.Str(window)),
      1,
      limit,
    ) match
      case Left(e) =>
        GraphOutput(
          StageOutput(
            missingEvidence = List(s"neighbors query failed for $subject"),
            reasonCodes = List(e.code.replace('-', '_')),
          )
        )
      case Right(res) =>
        val (runId, policy) = runInfo(subject, window)
        val rows            = res.rows.take(math.max(1, math.min(limit, settings.graphMaxResults)))
        val edges           = rows.flatMap { r =>
          Rows
            .str(r, "researcherId")
            .flatMap(o => EdgeSupport.edgeFrom(reader, r, subject, o, window).toOption.map(o -> _))
        }
        if edges.isEmpty then
          GraphOutput(
            StageOutput(missingEvidence =
              List(s"no coauthors of $subject were returned for window $window")
            )
          )
        else
          val fields = ujson.Obj(
            "coauthorIds"            -> ujson.Arr.from(edges.map((o, _) => ujson.Str(o))),
            "edgeIds"                -> ujson.Arr.from(edges.map((_, e) => ujson.Str(e.edgeId))),
            "jointPublicationCounts" -> ujson.Arr.from(
              edges.map((_, e) => ujson.Num(e.jointPublicationCount.toDouble))
            ),
            "returnedCount" -> ujson.Num(edges.size.toDouble),
            "truncated"     -> ujson.Bool(res.truncated || rows.size < res.rows.size),
          )
          val fact = Fact(Fact.Neighborhood, subject, Some(window), runId, policy, fields)
          val hood = EvidenceDraft.fromFact(
            EvidenceType.CoauthorEdge,
            s"neighborhood:$subject",
            s"${policy.getOrElse("coauthor-graph")}@$window",
            fact,
            edges.flatMap((_, e) => e.inputs.headOption).take(5) ++
              List(InputRef(s"graph:neighbors#$subject", policy.getOrElse("unversioned"), None)),
            Map("template" -> "neighbors", "window" -> window, "limit" -> limit.toString),
          )
          GraphOutput(
            StageOutput(hood :: edges.map((_, e) => EdgeSupport.edgeDraft(e, runId, policy))),
            edges.map(_._1),
          )

  /** The shortest collaboration path between two researchers in a window, within `depth` hops. */
  def shortestPath(from: String, to: String, window: String, depth: Int): GraphOutput =
    if from == to then
      GraphOutput(
        StageOutput(
          missingEvidence = List("path endpoints are the same researcher"),
          reasonCodes = List("path_endpoints_identical"),
        )
      )
    else
      val maxHops =
        math.max(1, math.min(depth, math.min(settings.graphMaxDepth, QueryLimits.MaxPathDepth)))
      val key             = s"path:$window:$from:$to"
      val (runId, policy) = runInfo(from, window)
      calls.run(
        GraphTemplates.ShortestPath,
        Map(
          "from"    -> GraphValue.Str(from),
          "to"      -> GraphValue.Str(to),
          "window"  -> GraphValue.Str(window),
          "maxHops" -> GraphValue.Num(maxHops.toLong),
        ),
        maxHops,
        1,
      ) match
        case Left(e) =>
          GraphOutput(
            StageOutput(
              missingEvidence =
                List(s"shortest-path query failed for the requested pair in $window"),
              reasonCodes = List(e.code.replace('-', '_')),
            )
          )
        case Right(res) =>
          res.rows.headOption match
            case None =>
              val fields = ujson.Obj(
                "from"    -> ujson.Str(from),
                "to"      -> ujson.Str(to),
                "found"   -> ujson.Bool(false),
                "maxHops" -> ujson.Num(maxHops.toDouble),
              )
              val fact = Fact(Fact.Path, key, Some(window), runId, policy, fields)
              GraphOutput(
                StageOutput(
                  List(
                    EvidenceDraft.fromFact(
                      EvidenceType.GraphPath,
                      key,
                      s"${policy.getOrElse("coauthor-graph")}@$window",
                      fact,
                      List(
                        InputRef(
                          s"graph:shortest-path#$from~$to",
                          policy.getOrElse("unversioned"),
                          None,
                        )
                      ),
                      Map(
                        "template" -> "shortest-path",
                        "window"   -> window,
                        "maxHops"  -> maxHops.toString,
                      ),
                    )
                  ),
                  List(s"no collaboration path within $maxHops hops was found in window $window"),
                )
              )
            case Some(row) => assemblePath(row, from, to, window, maxHops, key, runId, policy)

  private def assemblePath(
      row: GraphRow,
      from: String,
      to: String,
      window: String,
      maxHops: Int,
      key: String,
      runId: Option[String],
      policy: Option[String],
  ): GraphOutput =
    def bad(why: String) =
      GraphOutput(
        StageOutput(
          missingEvidence = List(s"the returned path was rejected: $why"),
          reasonCodes = List("path_rejected"),
        )
      )
    val hops    = Rows.strList(row, "hops").getOrElse(Nil)
    val edgeIds = Rows.strList(row, "edgeIds").getOrElse(Nil)
    val length  = Rows.long(row, "length").getOrElse(-1L)
    if hops.headOption != Some(from) || hops.lastOption != Some(to) then
      bad("endpoints differ from the request")
    else if length < 1 || length > maxHops || hops.size.toLong != length + 1 || edgeIds.size.toLong != length
    then bad("length is inconsistent or beyond the depth bound")
    else if hops.distinct.size != hops.size then bad("a researcher repeats on the path")
    else
      val hopEdges = hops.zip(hops.drop(1)).zip(edgeIds).map { case ((a, b), eid) =>
        calls
          .run(
            GraphTemplates.CoauthorEdge,
            Map(
              "researcherA" -> GraphValue.Str(a),
              "researcherB" -> GraphValue.Str(b),
              "window"      -> GraphValue.Str(window),
            ),
            1,
            1,
          )
          .toOption
          .flatMap(_.rows.headOption)
          .filter(r => Rows.str(r, "edgeId").contains(eid))
          .flatMap(r => EdgeSupport.edgeFrom(reader, r, a, b, window).toOption)
      }
      if hopEdges.exists(_.isEmpty) then bad("a hop edge was not confirmed by the edge template")
      else
        val es     = hopEdges.flatten
        val fields = ujson.Obj(
          "from"       -> ujson.Str(from),
          "to"         -> ujson.Str(to),
          "found"      -> ujson.Bool(true),
          "maxHops"    -> ujson.Num(maxHops.toDouble),
          "length"     -> ujson.Num(length.toDouble),
          "hops"       -> ujson.Arr.from(hops.map(ujson.Str(_))),
          "edgeIds"    -> ujson.Arr.from(es.map(e => ujson.Str(e.edgeId))),
          "hopWeights" -> ujson.Arr.from(es.map(e => ujson.Num(e.jointPublicationCount.toDouble))),
          "hopWorkIds" -> ujson.Arr.from(es.map(e => ujson.Arr.from(e.workIds.map(ujson.Str(_))))),
          "hopFirstYears" -> ujson.Arr.from(
            es.map(e =>
              e.firstYear.map(y => ujson.Num(y.toDouble): ujson.Value).getOrElse(ujson.Null)
            )
          ),
          "hopLastYears" -> ujson.Arr.from(
            es.map(e =>
              e.lastYear.map(y => ujson.Num(y.toDouble): ujson.Value).getOrElse(ujson.Null)
            )
          ),
        )
        val fact = Fact(Fact.Path, key, Some(window), runId, policy, fields)
        val path = EvidenceDraft.fromFact(
          EvidenceType.GraphPath,
          key,
          s"${policy.getOrElse("coauthor-graph")}@$window",
          fact,
          es.flatMap(_.inputs.headOption) ++ List(
            InputRef(s"graph:shortest-path#$from~$to", policy.getOrElse("unversioned"), None)
          ),
          Map("template" -> "shortest-path", "window" -> window, "maxHops" -> maxHops.toString),
        )
        GraphOutput(StageOutput(path :: es.map(e => EdgeSupport.edgeDraft(e, runId, policy))), hops)

/** Stage 5: community and component facts, and the degree-versus-bridge distinction.
  *
  * Direct degree (`coauthorCount`) counts a researcher's coauthors; bridge importance is about how
  * many of them lie OUTSIDE the researcher's community (`crossCommunityDegree`) and how evenly they
  * spread over communities (`participationCoefficient`, `communitiesTouched`). The
  * `degree_vs_bridge` fact states both side by side with `crossShare` = cross / coauthors and a
  * classification by a fixed rule (version `v1`): `bridge` when crossShare >= 0.5 and
  * crossCommunityDegree >= 2; `local_hub` when coauthorCount >= 5 and crossShare < 0.2; `mixed`
  * otherwise. The rule is part of the fact, so a reader can see it.
  *
  * Cross-community edges per community are the stored `crossEdgeCount` of the community vertex.
  * Neighboring communities are NOT available through the allowlisted templates (no template returns
  * `COMMUNITY_LINK` edges); the stage does not invent them and says so in its output.
  */
final class CommunityAnalystAgent(
    graph: GraphStore,
    reader: AnalyticsReader,
    metrics: CollaborationMetricsAgent,
    settings: RuntimeSettings,
):
  import CommunityAnalystAgent.*
  private val calls = new TemplateCalls(graph, settings)

  def component(subject: String, window: String): StageOutput =
    calls.run(
      GraphTemplates.Component,
      Map("researcherId" -> GraphValue.Str(subject), "window" -> GraphValue.Str(window)),
      1,
      1,
    ) match
      case Left(e) =>
        StageOutput(
          missingEvidence = List(s"component query failed for $subject"),
          reasonCodes = List(e.code.replace('-', '_')),
        )
      case Right(r) =>
        r.rows.headOption.flatMap(row => Rows.str(row, "componentId").map(_ -> row)) match
          case None =>
            StageOutput(missingEvidence =
              List(s"no component was returned for $subject in window $window")
            )
          case Some((cid, row)) =>
            val fields = ujson.Obj("componentId" -> ujson.Str(cid))
            Rows.long(row, "size").foreach(n => fields("size") = ujson.Num(n.toDouble))
            val fact = Fact(Fact.Component, subject, Some(window), None, None, fields)
            StageOutput(
              List(
                EvidenceDraft.fromFact(
                  EvidenceType.Metric,
                  subject,
                  s"component@$window",
                  fact,
                  List(InputRef(s"graph:IN_COMPONENT#$subject", "coauthor-graph", None)),
                  Map("template" -> "component", "window" -> window),
                )
              )
            )

  def community(subject: String, window: String): StageOutput =
    calls.run(
      GraphTemplates.Community,
      Map("researcherId" -> GraphValue.Str(subject), "window" -> GraphValue.Str(window)),
      1,
      1,
    ) match
      case Left(e) =>
        StageOutput(
          missingEvidence = List(s"community query failed for $subject"),
          reasonCodes = List(e.code.replace('-', '_')),
        )
      case Right(r) =>
        r.rows.headOption.flatMap(row => Rows.str(row, "communityId").map(_ -> row)) match
          case None =>
            StageOutput(missingEvidence =
              List(s"no community was returned for $subject in window $window")
            )
          case Some((cid, row)) =>
            val fields = ujson.Obj("communityId" -> ujson.Str(cid))
            Rows.long(row, "size").foreach(n => fields("size") = ujson.Num(n.toDouble))
            Rows.str(row, "method").foreach(m => fields("method") = ujson.Str(m))
            Rows
              .long(row, "internalEdgeCount")
              .foreach(n => fields("internalEdgeCount") = ujson.Num(n.toDouble))
            Rows
              .long(row, "crossEdgeCount")
              .foreach(n => fields("crossEdgeCount") = ujson.Num(n.toDouble))
            val fact = Fact(Fact.Community, subject, Some(window), None, None, fields)
            StageOutput(
              List(
                EvidenceDraft.fromFact(
                  EvidenceType.Metric,
                  subject,
                  s"community@$window",
                  fact,
                  List(InputRef(s"graph:MEMBER_OF#$subject", "coauthor-graph", None)),
                  Map("template" -> "community", "window" -> window),
                )
              )
            )

  /** The degree versus bridge fact of one researcher, from the stored metrics. */
  def degreeVsBridge(subject: String, window: String): StageOutput =
    metrics.metricsItem(subject, window) match
      case Right(Some(item)) =>
        val a                                = item.obj.value
        def n(k: String): Option[BigDecimal] = a.get(k).flatMap(Jnum.value)
        (n("coauthorCount"), n("crossCommunityDegree")) match
          case (Some(c), Some(x)) =>
            val share =
              if c == 0 then BigDecimal(0) else (x / c).setScale(6, BigDecimal.RoundingMode.HALF_UP)
            val cls =
              if share >= BigDecimal("0.5") && x >= 2 then "bridge"
              else if c >= 5 && share < BigDecimal("0.2") then "local_hub"
              else "mixed"
            val fields = ujson.Obj(
              "coauthorCount"        -> ujson.Num(c.toDouble),
              "crossCommunityDegree" -> ujson.Num(x.toDouble),
              "crossShare"           -> ujson.Num(share.toDouble),
              "classification"       -> ujson.Str(cls),
              "ruleVersion"          -> ujson.Str(RuleVersion),
            )
            List("participationCoefficient", "communitiesTouched").foreach { k =>
              a.get(k).foreach(v => fields(k) = v)
            }
            val policy = a.get("policyVersion").flatMap(_.strOpt)
            val fact   = Fact(
              Fact.DegreeVsBridge,
              subject,
              Some(window),
              a.get("provenanceRunId").flatMap(_.strOpt),
              policy,
              fields,
            )
            StageOutput(
              List(
                EvidenceDraft.fromFact(
                  EvidenceType.Metric,
                  subject,
                  s"${policy.getOrElse("unversioned")}@$window#degree-vs-bridge",
                  fact,
                  List(EvidenceSupport.inputRef(item, policy)),
                  Map("table" -> "researcher-metrics", "window" -> window, "rule" -> RuleVersion),
                )
              )
            )
          case _ =>
            StageOutput(missingEvidence =
              List(s"bridge measures are not stored for $subject in window $window")
            )
      case _ =>
        StageOutput(missingEvidence =
          List(s"stored metrics are absent for $subject in window $window")
        )

  /** Communities of every subject, the bridge distinction, and the stored metrics behind it. */
  def compare(subjects: List[String], window: String): StageOutput =
    val per = subjects.foldLeft(StageOutput.empty)((acc, s) =>
      acc ++ community(s, window) ++ degreeVsBridge(s, window)
    )
    per ++ StageOutput(missingEvidence =
      List("neighboring communities are not available through the allowlisted graph templates")
    )

object CommunityAnalystAgent:
  val RuleVersion: String = "v1"
