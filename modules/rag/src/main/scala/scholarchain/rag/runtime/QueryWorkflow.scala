package scholarchain.rag.runtime

import scholarchain.core.*

/** Everything a query run returns. `answer` is always present when the run reached the end
  * (abstentions included, with no claims). `abstained` is true when identity or evidence was not
  * sufficient; `candidates` then lists what the resolver found.
  */
final case class QueryOutcome(
    queryId: String,
    plan: Option[QueryPlanV2],
    resolution: Option[ResolutionResult],
    evidence: Option[EvidenceSet],
    verification: Option[VerificationResult],
    answer: Option[AnswerResponse],
    answerText: String,
    abstained: Boolean,
    abstainReason: Option[String],
    missingEvidence: List[String],
    audit: QueryAudit,
    auditResult: AuditResult,
    error: Option[RuntimeError],
)

/** Composes the ten stages over core ports. Construct with `QueryWorkflow.create`.
  *
  * Flow: plan, resolve (abstain unless every subject resolved to exactly one researcher), collect
  * evidence per planned operation (graph, metric and semantic stages kept separate), build the
  * bounded bundle, synthesize, verify (deterministic first), compose the answer from the claims
  * that verified (verified or partially supported), audit. Abstains with a reason code when the
  * plan is invalid, identity is not resolved, there is no evidence, or no claim verified.
  */
final class QueryWorkflow(
    settings: RuntimeSettings,
    clock: RuntimeClock,
    planner: QueryPlannerAgent,
    resolver: ResearcherResolverAgent,
    metrics: CollaborationMetricsAgent,
    paths: PathFinderAgent,
    community: CommunityAnalystAgent,
    semantic: SemanticContextAgent,
    builder: EvidenceBuilderAgent,
    synthesis: AnswerSynthesisAgent,
    verifier: ClaimVerificationAgent,
    audit: AuditAgent,
    prompts: PromptRepository,
    modelId: String,
):
  import QueryWorkflow.*

  def queryIdFor(req: QueryRequest): String =
    "query:" + Hashing.digest(
      "scholarchain.rag.query.v1",
      List(
        QueryPlannerAgent.normalizeQuestion(req.question),
        CanonicalJson.render(req.constraints.toJson),
      ),
    )

  def run(req: QueryRequest): QueryOutcome =
    val started = clock.epochMillis()
    val queryId = queryIdFor(req)
    val qHash   = Hashing.textHash(QueryPlannerAgent.normalizeQuestion(req.question))
    // A session is one run: its identity adds the run's start time to the query identity, so asking
    // the same question again opens a new session (and points at the new run's bundle) instead of
    // conflicting with the earlier one. Under `RuntimeClock.Fixed` the identity is stable, so a
    // repeated run is byte-identical and idempotent.
    val sessionId = "session-" + Hashing
      .digest("scholarchain.rag.session.v1", List(queryId, started.toString))
      .take(40)
    val lat = scala.collection.mutable.LinkedHashMap.empty[String, Long]
    def timed[A](name: String)(f: => A): A =
      val t = clock.epochMillis()
      val r = f
      lat(name) = math.max(0L, clock.epochMillis() - t)
      r
    audit.begin(queryId, sessionId, qHash, started)

    val planned = timed("plan")(planner.plan(queryId, req))
    planned.plan match
      case Left(e) =>
        finish(
          queryId,
          sessionId,
          qHash,
          started,
          lat,
          planned,
          None,
          None,
          None,
          None,
          "plan_invalid",
          Nil,
          Some(e),
          None,
        )
      case Right(plan) =>
        val resolution =
          timed("resolve")(Option.when(plan.subjectCount > 0)(resolver.resolve(plan)))
        resolution match
          case Some(r) if !r.allResolved =>
            val reason = if r.entries.exists(_.status == ResolutionStatus.Ambiguous) then
              "identity_ambiguous"
            else "identity_unresolved"
            val set = builder.build(queryId, r.evidence.drafts, Nil).toOption
            finish(
              queryId,
              sessionId,
              qHash,
              started,
              lat,
              planned,
              Some(plan),
              resolution,
              set,
              None,
              reason,
              Nil,
              None,
              None,
            )
          case _ =>
            val subjects                   = resolution.map(_.subjectIds).getOrElse(Nil)
            val (det, sem, missing, codes) = timed("retrieve")(collect(plan, subjects))
            val allDet = resolution.map(_.evidence.drafts).getOrElse(Nil) ++ det.drafts
            if allDet.isEmpty && sem.isEmpty then
              finish(
                queryId,
                sessionId,
                qHash,
                started,
                lat,
                planned,
                Some(plan),
                resolution,
                None,
                None,
                "no_evidence",
                missing,
                None,
                None,
              )
            else
              builder.build(queryId, allDet, sem) match
                case Left(e) =>
                  finish(
                    queryId,
                    sessionId,
                    qHash,
                    started,
                    lat,
                    planned,
                    Some(plan),
                    resolution,
                    None,
                    None,
                    "evidence_invalid",
                    missing,
                    Some(e),
                    None,
                  )
                case Right(set) =>
                  val syn   = timed("synthesize")(synthesis.synthesize(queryId, plan, set, missing))
                  val ver   = timed("verify")(verifier.verify(queryId, set, syn.synthesis.claims))
                  val extra = ExtraCalls(
                    syn.modelCalls ++ ver.modelCalls,
                    syn.usedFallback,
                    syn.synthesis.uncertainty,
                    codes,
                  )
                  if ver.kept.isEmpty then
                    finish(
                      queryId,
                      sessionId,
                      qHash,
                      started,
                      lat,
                      planned,
                      Some(plan),
                      resolution,
                      Some(set),
                      Some(ver),
                      "no_verified_claims",
                      missing,
                      None,
                      Some(extra),
                    )
                  else
                    finish(
                      queryId,
                      sessionId,
                      qHash,
                      started,
                      lat,
                      planned,
                      Some(plan),
                      resolution,
                      Some(set),
                      Some(ver),
                      "",
                      missing,
                      None,
                      Some(extra),
                    )

  private final case class ExtraCalls(
      calls: List[ModelCall],
      synthesisFallback: Boolean,
      uncertainty: List[String],
      codes: List[String],
  )

  /** Evidence per planned operation: (deterministic stage output, semantic drafts, missing, codes).
    */
  private def collect(
      plan: QueryPlanV2,
      subjects: List[String],
  ): (StageOutput, List[EvidenceDraft], List[String], List[String]) =
    val window = plan.window.map(GraphWindow.label).getOrElse(RuntimeCode.AllTimeLabel)
    var det    = StageOutput.empty
    var sem    = List.empty[EvidenceDraft]
    def semanticSearch(types: List[String], useWindow: Boolean): Unit =
      val r =
        semantic.search(plan.question, types, if useWindow then plan.window else None, SemanticTopK)
      sem = sem ++ r.stage.drafts
      det = det ++ StageOutput(
        missingEvidence = r.stage.missingEvidence,
        reasonCodes = r.stage.reasonCodes,
      )
    plan.operations.foreach {
      case QueryOperationV2.MaxTeamSize             => det = det ++ metrics.windowSummary(window)
      case QueryOperationV2.ResearcherMetricsLookup =>
        det = det ++ metrics.metrics(subjects, window)
      case QueryOperationV2.RankByMetric =>
        plan.metric.flatMap(metrics.fieldOf) match
          case None =>
            det = det ++ StageOutput(missingEvidence =
              List(
                "the requested ranking metric is not stored; stored rankable metrics are degree, weighted_degree and bridge_score"
              )
            )
          case Some(f) =>
            if subjects.isEmpty then
              det = det ++ StageOutput(missingEvidence =
                List("ranking needs named researchers to rank among")
              )
            else det = det ++ metrics.ranking(subjects, window, f)
      case QueryOperationV2.BridgeRanking =>
        if subjects.isEmpty then
          det = det ++ StageOutput(missingEvidence =
            List("bridge ranking needs named researchers to rank among")
          )
        else
          det = det ++ metrics.ranking(
            subjects,
            window,
            "crossCommunityDegree",
            Some("participationCoefficient"),
          )
          subjects.foreach(s => det = det ++ community.degreeVsBridge(s, window))
      case QueryOperationV2.DirectCoauthors =>
        val g = paths.directCollaborators(subjects.head, window, plan.limits.resultCount)
        det = det ++ g.stage ++ metrics.metrics(subjects, window)
      case QueryOperationV2.ShortestPath =>
        det =
          det ++ paths.shortestPath(subjects(0), subjects(1), window, plan.limits.pathDepth).stage
      case QueryOperationV2.ComponentLookup =>
        det = det ++ community.component(subjects.head, window) ++ metrics.metrics(subjects, window)
      case QueryOperationV2.CommunityComparison =>
        det = det ++ community.compare(subjects, window) ++ metrics.metrics(subjects, window)
      case QueryOperationV2.TemporalComparison =>
        val cw = plan.comparisonWindow.map(GraphWindow.label).getOrElse(window)
        det = det ++ metrics.temporal(subjects, window, cw)
      case QueryOperationV2.EntityFilter =>
        det = det ++ StageOutput(missingEvidence =
          List(
            "the allowlisted graph templates cannot filter by topic, venue or institution; only semantic context is offered"
          )
        )
        semanticSearch(List(EmbeddingDocumentTypes.TopicSummary), useWindow = false)
      case QueryOperationV2.SemanticExplanation =>
        semanticSearch(
          List(
            EmbeddingDocumentTypes.ResearcherProfile,
            EmbeddingDocumentTypes.CommunityProfile,
            EmbeddingDocumentTypes.PathSummary,
            EmbeddingDocumentTypes.TopicSummary,
          ),
          useWindow = plan.window.isDefined,
        )
    }
    (det, sem, det.missingEvidence, det.reasonCodes)

  private def finish(
      queryId: String,
      sessionId: String,
      qHash: String,
      started: Long,
      lat: scala.collection.mutable.LinkedHashMap[String, Long],
      planned: PlannerResult,
      plan: Option[QueryPlanV2],
      resolution: Option[ResolutionResult],
      set: Option[EvidenceSet],
      ver: Option[VerificationResult],
      abstainReason: String,
      missing: List[String],
      error: Option[RuntimeError],
      extra: Option[ExtraCalls],
  ): QueryOutcome =
    val abstained = abstainReason.nonEmpty
    val text      = composeText(abstained, abstainReason, resolution, ver, extra, missing)
    val bundle    = set.map(_.bundle)
    val claims    = ver.map(_.kept).getOrElse(Nil)
    val answer    = bundle.flatMap { b =>
      val a = AnswerResponse(
        SchemaVersion.Current,
        queryId,
        text,
        if abstained then Nil else claims,
        b,
        Provenance(
          SchemaVersion.Current,
          SourceType.ScholarChain,
          List(
            InputRef(
              "evidence-bundle",
              queryId,
              Some(Hashing.textHash(b.items.map(_.evidenceId).mkString(","))),
            )
          ),
          RuntimeCode.Version,
          Map("abstained" -> abstained.toString),
        ),
      )
      summon[Contract[AnswerResponse]].validate(a).toOption
    }
    val facts      = set.toList.flatMap(_.graphItems).flatMap(i => i.text.flatMap(Fact.parse))
    val usedKeys   = prompts // keep prompt handles alive for hashing below
    val promptUses = List(
      PromptRepository.PlannerId,
      PromptRepository.SynthesisId,
      PromptRepository.VerificationId,
    ).flatMap { id =>
      usedKeys
        .load(id, PromptRepository.Version)
        .toOption
        .map(p => PromptUse(id, p.version, p.promptSha256, p.schemaSha256))
    }
    val calls       = planned.modelCalls ++ extra.map(_.calls).getOrElse(Nil)
    val resolutions = resolution.toList.flatMap(_.entries).map { e =>
      ResolutionAudit(
        e.position,
        e.status.wire,
        e.chosen.map(_.researcherId),
        e.candidates.map(_.researcherId),
        e.reasonCodes,
      )
    }
    val total = math.max(0L, clock.epochMillis() - started)
    val base  = QueryAudit(
      SchemaVersion.Current,
      queryId,
      sessionId,
      qHash,
      plan,
      planned.usedFallback,
      planned.reasonCodes,
      resolutions,
      resolution.map(_.subjectIds).getOrElse(Nil),
      set.map(_.graphEvidenceIds).getOrElse(Nil),
      set.map(_.semanticEvidenceIds).getOrElse(Nil),
      None,
      facts.flatMap(_.policyVersion).distinct.sorted,
      facts.flatMap(_.runId).distinct.sorted,
      promptUses,
      modelId,
      calls,
      lat.toMap,
      total,
      ver.map(_.verifications).getOrElse(Nil),
      ver.map(_.removed.map(_.claimId)).getOrElse(Nil),
      extra.exists(_.synthesisFallback),
      abstained,
      Option.when(abstained)(abstainReason),
      Some(Hashing.textHash(text)),
      RuntimeCode.Version,
    )
    val status =
      if error.isDefined then QuerySessionStatus.Failed
      else if abstained then QuerySessionStatus.Rejected
      else QuerySessionStatus.Answered
    val (res, finalAudit) = audit.finish(status, started, bundle, base)
    QueryOutcome(
      queryId,
      plan,
      resolution,
      set,
      ver,
      answer,
      text,
      abstained,
      Option.when(abstained)(abstainReason),
      missing,
      finalAudit,
      res,
      error,
    )

  private def composeText(
      abstained: Boolean,
      reason: String,
      resolution: Option[ResolutionResult],
      ver: Option[VerificationResult],
      extra: Option[ExtraCalls],
      missing: List[String],
  ): String =
    if abstained then
      val cands = resolution.toList.flatMap(_.abstainEntries).map { e =>
        val label = e.queriedName.orElse(e.queriedId).getOrElse("subject")
        if e.candidates.isEmpty then
          s"No researcher could be matched to the name at position ${e.position + 1}."
        else
          s"The name at position ${e.position + 1} could not be resolved to one researcher (${e.reasonCodes.mkString(", ")}). Candidates: " +
            e.candidates
              .map(c =>
                s"${c.displayName} (${c.researcherId}, confidence ${Nums.render(ujson.Num(c.confidence))})"
              )
              .mkString("; ") + "."
      }
      val head = s"I cannot answer this question reliably (${reason.replace('_', ' ')})."
      (head :: cands ++ missing.take(3)).mkString(" ")
    else
      val claims = ver.map(_.kept.map(_.text)).getOrElse(Nil)
      val unc    = extra.map(_.uncertainty).getOrElse(Nil)
      (claims ++ unc.map("Uncertainty: " + _) ++ missing.take(3).map("Missing evidence: " + _))
        .mkString("\n")

object QueryWorkflow:
  val SemanticTopK: Int = 3

  /** Builds the workflow over core ports only. */
  def create(
      settings: RuntimeSettings,
      model: ModelClient,
      kv: KeyValueStore,
      graph: GraphStore,
      vectors: VectorStore,
      objects: ObjectStore,
      embedder: Embedder,
      prompts: PromptRepository,
      tables: RuntimeTables = RuntimeTables.defaults,
      clock: RuntimeClock = RuntimeClock.System,
      llmVerification: Boolean = false,
  ): QueryWorkflow =
    val caller  = new StructuredModelCaller(model, settings, clock)
    val reader  = new AnalyticsReader(kv, tables)
    val metrics = new CollaborationMetricsAgent(reader)
    new QueryWorkflow(
      settings,
      clock,
      new QueryPlannerAgent(caller, prompts, settings),
      new ResearcherResolverAgent(reader, vectors, embedder),
      metrics,
      new PathFinderAgent(graph, reader, settings),
      new CommunityAnalystAgent(graph, reader, metrics, settings),
      new SemanticContextAgent(embedder, vectors, objects),
      new EvidenceBuilderAgent(settings),
      new AnswerSynthesisAgent(caller, prompts, settings),
      new ClaimVerificationAgent(Some(caller), prompts, llmVerification),
      new AuditAgent(kv, objects, tables, clock),
      prompts,
      model.modelId,
    )
