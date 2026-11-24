package scholarchain.rag.runtime

import scala.util.Try

import scholarchain.core.*

/** Typed constraints a caller may send with a question (the structured part of a request). They
  * always win over what a model proposes, so a caller can pin names, windows and operations.
  */
final case class StructuredConstraints(
    operations: List[QueryOperationV2] = Nil,
    researcherNames: List[String] = Nil,
    subjectIds: List[String] = Nil,
    window: Option[GraphWindow] = None,
    comparisonWindow: Option[GraphWindow] = None,
    filters: QueryFilters = QueryFilters.none,
    metric: Option[CollaborationMetric] = None,
    resultCount: Option[Int] = None,
    pathDepth: Option[Int] = None,
):
  def isEmpty: Boolean = this == StructuredConstraints()

  /** Canonical JSON of the constraints, sent to the planner as labeled data. */
  def toJson: ujson.Value =
    def win(w: GraphWindow): ujson.Value =
      ujson.Obj("startYear" -> w.startYear, "endYear" -> w.endYear)
    val entries = List[Option[(String, ujson.Value)]](
      Option.when(operations.nonEmpty)(
        "operations" -> ujson.Arr.from(operations.map(o => ujson.Str(o.wire)))
      ),
      Option.when(researcherNames.nonEmpty)(
        "researcherNames" -> ujson.Arr.from(researcherNames.map(ujson.Str(_)))
      ),
      Option.when(subjectIds.nonEmpty)(
        "subjectIds" -> ujson.Arr.from(subjectIds.map(ujson.Str(_)))
      ),
      window.map(w => "window" -> win(w)),
      comparisonWindow.map(w => "comparisonWindow" -> win(w)),
      Option.when(!filters.isEmpty)(
        "filters" -> (ujson.Obj.from(
          filters.topic.toList.map(t => "topic" -> (ujson.Str(t): ujson.Value)) ++
            filters.venue.toList.map(t => "venue" -> (ujson.Str(t): ujson.Value)) ++
            filters.institution.toList.map(t => "institution" -> (ujson.Str(t): ujson.Value))
        ): ujson.Value)
      ),
      metric.map(m => "metric" -> (ujson.Str(m.wire): ujson.Value)),
      resultCount.map(n => "resultCount" -> (ujson.Num(n.toDouble): ujson.Value)),
      pathDepth.map(n => "pathDepth" -> (ujson.Num(n.toDouble): ujson.Value)),
    ).flatten
    ujson.Obj.from(entries)

/** A question and its optional typed constraints. */
final case class QueryRequest(
    question: String,
    constraints: StructuredConstraints = StructuredConstraints(),
)

/** Result of planning: a validated plan or a typed failure, whether the rule-based fallback made
  * the plan, the reason codes, and the `ModelCall` record(s).
  */
final case class PlannerResult(
    plan: Either[RuntimeError, QueryPlanV2],
    usedFallback: Boolean,
    reasonCodes: List[String],
    modelCalls: List[ModelCall],
)

/** Stage 1: turns a question and optional constraints into a typed `QueryPlanV2`.
  *
  * The model proposes; code decides. The model output is validated against
  * `query-planner-v1.schema.json` (an unknown operation is a schema violation), then again by code
  * (allowlist, bounds, `QueryPlanV2` contract). Typed constraints override the proposal field by
  * field. When the model call fails, the output is invalid or the merged plan violates the
  * contract, the deterministic rule-based fallback builds the plan from the constraints (and, only
  * to choose among the allowlisted operations, keywords of the question).
  *
  * USER TEXT NEVER BECOMES A QUERY. The question is kept as data (`QueryPlanV2.question`, after
  * control characters are replaced by spaces) and sent to the model as an untrusted data segment.
  * Names are kept as data and are only compared with stored names by the resolver. No stage builds
  * a query string from either: the stages call only the six allowlisted graph templates with typed
  * parameters.
  */
final class QueryPlannerAgent(
    caller: StructuredModelCaller,
    prompts: PromptRepository,
    settings: RuntimeSettings,
):
  import QueryPlannerAgent.*

  def plan(queryId: String, req: QueryRequest): PlannerResult =
    val question = normalizeQuestion(req.question)
    val fallback = (why: String, calls: List[ModelCall]) =>
      ruleBased(queryId, question, req.constraints, settings, "fallback") match
        case Right(p) => PlannerResult(Right(p), true, List(why, "planner_fallback_used"), calls)
        case Left(e)  => PlannerResult(Left(e), true, List(why, "planner_fallback_invalid"), calls)
    prompts.load(PromptRepository.PlannerId, PromptRepository.Version) match
      case Left(_)     => fallback("planner_prompt_unavailable", Nil)
      case Right(spec) =>
        val user = List(
          ModelSegment(
            ModelSegmentKind.Instruction,
            "task",
            "Produce the query plan for the question in the untrusted data segments.",
          ),
          ModelSegment(ModelSegmentKind.Data, "user_question", question),
        ) ++ Option.when(!req.constraints.isEmpty)(
          ModelSegment(
            ModelSegmentKind.Data,
            "structured_constraints",
            CanonicalJson.render(req.constraints.toJson),
          )
        )
        if question.isBlank then fallback("planner_question_blank", Nil)
        else
          caller.call(queryId, ModelStage.QueryPlanner, spec, user, 512) match
            case Left(f) =>
              fallback(s"planner_model_failed_${f.error.code.replace('-', '_')}", List(f.call))
            case Right(s) =>
              parseProposal(s.json) match
                case Left(why)       => fallback(why, List(s.call))
                case Right(proposal) =>
                  merge(queryId, question, req.constraints, proposal, settings, spec) match
                    case Right(p) =>
                      PlannerResult(Right(p), false, List("planner_model_plan_used"), List(s.call))
                    case Left(_) => fallback("planner_merged_plan_invalid", List(s.call))

object QueryPlannerAgent:
  /** A proposal after parsing the schema-valid model JSON. Nothing here is trusted yet. */
  final case class Proposal(
      operations: List[QueryOperationV2],
      names: List[String],
      window: Option[GraphWindow],
      comparisonWindow: Option[GraphWindow],
      filters: QueryFilters,
      metric: Option[CollaborationMetric],
      resultCount: Option[Int],
      pathDepth: Option[Int],
  )

  val DefaultResultCount: Int = 10

  /** Replaces control characters (newlines and tabs included) by spaces and collapses blanks. */
  def normalizeQuestion(q: String): String =
    q.map(c => if Character.isISOControl(c) then ' ' else c).replaceAll("\\s+", " ").trim

  private def window(v: Option[ujson.Value]): Either[String, Option[GraphWindow]] = v match
    case None    => Right(None)
    case Some(o) =>
      val w = for
        s <- o.objOpt.flatMap(_.value.get("startYear")).flatMap(_.numOpt).map(_.toInt)
        e <- o.objOpt.flatMap(_.value.get("endYear")).flatMap(_.numOpt).map(_.toInt)
      yield GraphWindow(s, e)
      w.toRight("planner_proposal_invalid_window")
        .flatMap(x => GraphWindow.validate(x).left.map(_ => "planner_proposal_invalid_window"))
        .map(Some(_))

  def parseProposal(json: ujson.Value): Either[String, Proposal] =
    json.objOpt match
      case None    => Left("planner_proposal_not_object")
      case Some(o) =>
        val m   = o.value
        val ops = m.get("operations").flatMap(_.arrOpt).map(_.toList).getOrElse(Nil).map { v =>
          v.strOpt.flatMap(s => QueryOperationV2.values.find(_.wire == s))
        }
        if ops.isEmpty || ops.exists(_.isEmpty) then Left("planner_proposal_unknown_operation")
        else
          val metric =
            m.get("metric").flatMap(_.strOpt).map(s => CollaborationMetric.values.find(_.wire == s))
          if metric.exists(_.isEmpty) then Left("planner_proposal_unknown_metric")
          else
            for
              w  <- window(m.get("window"))
              cw <- window(m.get("comparisonWindow"))
            yield
              val f = m.get("filters").flatMap(_.objOpt).map(_.value)
              Proposal(
                ops.flatten,
                m.get("researcherNames")
                  .flatMap(_.arrOpt)
                  .map(_.toList.flatMap(_.strOpt))
                  .getOrElse(Nil),
                w,
                cw,
                QueryFilters(
                  f.flatMap(_.get("topic")).flatMap(_.strOpt),
                  f.flatMap(_.get("venue")).flatMap(_.strOpt),
                  f.flatMap(_.get("institution")).flatMap(_.strOpt),
                ),
                metric.flatten,
                m.get("resultCount").flatMap(_.numOpt).map(_.toInt),
                m.get("pathDepth").flatMap(_.numOpt).map(_.toInt),
              )

  private def limits(resultCount: Option[Int], pathDepth: Option[Int], s: RuntimeSettings) =
    QueryLimits(
      math.max(
        1,
        math.min(
          resultCount.getOrElse(DefaultResultCount),
          math.min(s.graphMaxResults, QueryLimits.MaxResultCount),
        ),
      ),
      math.max(
        1,
        math.min(
          pathDepth.getOrElse(s.graphMaxDepth),
          math.min(s.graphMaxDepth, QueryLimits.MaxPathDepth),
        ),
      ),
    )

  private def provenance(question: String, path: String, spec: Option[PromptSpec]): Provenance =
    Provenance(
      SchemaVersion.Current,
      SourceType.ScholarChain,
      List(InputRef("question", "sha256", Some(Hashing.textHash(question)))),
      RuntimeCode.Version,
      Map("plannerPath" -> path) ++ spec.map(s => "promptVersion" -> s"${s.promptId}-${s.version}"),
    )

  private def build(
      queryId: String,
      question: String,
      ops: List[QueryOperationV2],
      names: List[String],
      ids: List[String],
      w: Option[GraphWindow],
      cw: Option[GraphWindow],
      f: QueryFilters,
      metric: Option[CollaborationMetric],
      lim: QueryLimits,
      prov: Provenance,
  ): Either[RuntimeError, QueryPlanV2] =
    val plan = QueryPlanV2(
      SchemaVersion.Current,
      queryId,
      question,
      ops.distinct,
      names,
      ids,
      w,
      cw,
      f,
      metric,
      lim,
      prov,
    )
    summon[Contract[QueryPlanV2]].validate(plan).left.map(e => RuntimeError("plan-invalid", e.code))

  /** Merges a proposal with the typed constraints (constraints win) and validates the result. */
  def merge(
      queryId: String,
      question: String,
      c: StructuredConstraints,
      p: Proposal,
      s: RuntimeSettings,
      spec: PromptSpec,
  ): Either[RuntimeError, QueryPlanV2] =
    val ops   = if c.operations.nonEmpty then c.operations else p.operations
    val names = if c.researcherNames.nonEmpty then c.researcherNames
    else if c.subjectIds.nonEmpty then Nil
    else p.names
    val f = QueryFilters(
      c.filters.topic.orElse(p.filters.topic),
      c.filters.venue.orElse(p.filters.venue),
      c.filters.institution.orElse(p.filters.institution),
    )
    val cw = c.comparisonWindow.orElse(p.comparisonWindow)
    val w  = c.window.orElse(p.window)
    for
      _    <- QueryFilters.validate(f).left.map(e => RuntimeError("plan-invalid", e.code))
      plan <- build(
        queryId,
        question,
        ops,
        names,
        c.subjectIds,
        w,
        cw.filter(_ => ops.contains(QueryOperationV2.TemporalComparison)),
        f,
        c.metric.orElse(p.metric),
        limits(c.resultCount.orElse(p.resultCount), c.pathDepth.orElse(p.pathDepth), s),
        provenance(question, "model", Some(spec)),
      )
    yield plan

  /** Keyword choice among the allowlisted operations. The question text decides only WHICH
    * allowlisted operation; it supplies no parameter.
    */
  def keywordOperations(question: String, c: StructuredConstraints): List[QueryOperationV2] =
    val q                         = question.toLowerCase(java.util.Locale.ROOT)
    def has(ws: String*): Boolean = ws.exists(q.contains)
    val named                     = c.researcherNames.size.max(c.subjectIds.size)
    val picked                    =
      if has("shortest path", "path between", "connection between", "connected to") && named >= 2
      then QueryOperationV2.ShortestPath
      else if has("bridge", "bridging") then QueryOperationV2.BridgeRanking
      else if has("team size", "largest team", "biggest team") then QueryOperationV2.MaxTeamSize
      else if has("component") && named >= 1 then QueryOperationV2.ComponentLookup
      else if has("communit") && named >= 2 then QueryOperationV2.CommunityComparison
      else if c.comparisonWindow.isDefined && named >= 1 then QueryOperationV2.TemporalComparison
      else if has("coauthor", "co-author", "collaborator") && named == 1 then
        QueryOperationV2.DirectCoauthors
      else if !c.filters.isEmpty then QueryOperationV2.EntityFilter
      else if c.metric.isDefined && has("rank", "top ", "most ", "highest") then
        QueryOperationV2.RankByMetric
      else if named >= 1 then QueryOperationV2.ResearcherMetricsLookup
      else QueryOperationV2.SemanticExplanation
    List(picked)

  /** The deterministic fallback. Never calls a model. */
  def ruleBased(
      queryId: String,
      question: String,
      c: StructuredConstraints,
      s: RuntimeSettings,
      path: String = "fallback",
  ): Either[RuntimeError, QueryPlanV2] =
    val ops = if c.operations.nonEmpty then c.operations else keywordOperations(question, c)
    build(
      queryId,
      question,
      ops,
      c.researcherNames,
      c.subjectIds,
      c.window,
      c.comparisonWindow.filter(_ => ops.contains(QueryOperationV2.TemporalComparison)),
      c.filters,
      c.metric,
      limits(c.resultCount, c.pathDepth, s),
      provenance(question, path, None),
    )
