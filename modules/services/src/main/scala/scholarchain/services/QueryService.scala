package scholarchain.services

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.{
  Callable,
  ExecutionException,
  LinkedBlockingQueue,
  RejectedExecutionException,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit,
  TimeoutException,
}
import java.util.concurrent.atomic.AtomicLong

import scala.util.control.NonFatal

import scholarchain.core.*
import scholarchain.core.rest.*
import scholarchain.rag.runtime.{QueryOutcome, StructuredConstraints, RuntimeCode}
import scholarchain.rag.runtime.QueryRequest as RagQueryRequest

/** Result of running a query for a request: a finished answer, or a job that continues. */
enum Executed:
  case Sync(response: SyncQueryResponse)
  case Async(jobId: String)

/** The public query logic. Routes call it; it contains no retrieval algorithm: every answer comes
  * from the Phase 09 `QueryWorkflow` (planning, resolution, evidence, synthesis, claim
  * verification, audit). The service stores jobs, idempotency records, the evidence views and the
  * explain view of each answer in the QuerySession table through `KeyValueStore`, under the
  * prefixes `job#`, `idem#` (core `RestKeys`) and `evid#`, `qevid#`, `explain#` (this module).
  */
final class QueryService(
    rt: ServiceRuntime,
    cfg: ServiceSettings,
    ids: IdGen,
    log: JsonLog,
    nowMillis: () => Long = () => System.currentTimeMillis(),
):
  /** What this deployment does. The workflow plans graph and semantic retrieval itself, ranks by
    * the requested metric, and answers path, community and bridge questions from the question text;
    * it has no switch for the `analysis.include*` outputs, so those are refused (422), not ignored.
    */
  private val caps = RestCapabilities(
    graphRetrieval = true,
    vectorRetrieval = true,
    rankingMetrics = RankingMetric.values.toSet,
    bridgeMetrics = false,
    communities = false,
    paths = false,
  )
  private val table   = rt.tables.querySession
  private val counter = new AtomicLong(0)

  private def daemon(name: String): ThreadFactory = (r: Runnable) =>
    val t = new Thread(r, s"$name-${counter.incrementAndGet()}")
    t.setDaemon(true)
    t
  // Bounded executors: when the queue is full a submission is rejected (429) before any model work.
  private def bounded(threads: Int, queue: Int, name: String): ThreadPoolExecutor =
    new ThreadPoolExecutor(
      threads,
      threads,
      0L,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue[Runnable](math.max(1, queue)),
      daemon(name),
      new ThreadPoolExecutor.AbortPolicy,
    )
  private val pool      = bounded(cfg.maxConcurrentQueries, cfg.maxQueuedQueries, "query-run")
  private val completer =
    bounded(
      cfg.maxConcurrentQueries * 2,
      cfg.maxQueuedQueries + cfg.maxConcurrentQueries * 2,
      "query-complete",
    )

  /** Time an `in_progress` idempotency record is trusted before it may be reclaimed. */
  private val LeaseMarginMillis = 10000L
  private def leaseMillis: Long = cfg.syncDeadlineMillis + LeaseMarginMillis

  private val RetentionSeconds       = 24L * 3600
  private val MaxStoredEvidence      = 100
  private val NeighborCollectCap     = 1000
  private val NeighborQueryCap       = 60
  private val NeighborDeadlineMillis = 8000L
  private val EvidencePrefix         = "evid#"
  private val QueryEvidencePrefix    = "qevid#"
  private val ExplainPrefix          = "explain#"

  private def nowIso: String =
    Instant.ofEpochMilli(nowMillis()).truncatedTo(ChronoUnit.SECONDS).toString
  private def nowSec: Long = nowMillis() / 1000

  // ---- storage helpers --------------------------------------------------------------------

  private def putItem(
      pk: String,
      json: String,
      expires: Long,
      cond: PutCondition = PutCondition.Always,
  ): Either[ApiFailure, PutOutcome] =
    rt.kv
      .put(
        table,
        KvItem(
          KvKey(pk),
          Map("payload" -> json, "payloadHash" -> Hashing.textHash(json)),
          Some(expires),
        ),
        cond,
      )
      .left
      .map(Downstream.toFailure)

  private def getPayload(pk: String): Either[ApiFailure, Option[String]] =
    rt.kv
      .get(table, KvKey(pk))
      .left
      .map(Downstream.toFailure)
      .map(_.flatMap(_.attributes.get("payload")))

  private def internal(msg: String): ApiFailure = ApiFailure(ApiErrorCode.Internal, msg)

  // ---- POST /v1/query -----------------------------------------------------------------------

  private def toRag(r: QueryRequest): Either[ApiFailure, RagQueryRequest] =
    def unsupported(m: String) = Left(ApiFailure(ApiErrorCode.UnsupportedConstraint, m))
    def one(field: String, xs: List[String]): Either[ApiFailure, Option[String]] = xs match
      case Nil                                                                => Right(None)
      case x :: Nil if """^[A-Za-z0-9][A-Za-z0-9._:\-]{0,63}$""".r.matches(x) => Right(Some(x))
      case _                                                                  =>
        unsupported(s"constraints.$field: supports at most one entry that is a single token")
    for
      topic       <- one("topics", r.constraints.topics)
      venue       <- one("venues", r.constraints.venues)
      institution <- one("institutions", r.constraints.institutions)
      window      <- (r.constraints.yearFrom, r.constraints.yearTo) match
        case (Some(f), Some(t)) => Right(Some(GraphWindow(f, t)))
        case (None, None)       => Right(None)
        case _ => unsupported("constraints: yearFrom and yearTo must be given together")
    yield RagQueryRequest(
      r.question,
      StructuredConstraints(
        window = window,
        filters = QueryFilters(topic, venue, institution),
        metric = r.analysis.rankingMetric.map(_.metric),
        resultCount = Some(r.retrieval.graphLimit),
        pathDepth = Some(r.retrieval.maxPathDepth),
      ),
    )

  /** Options the workflow cannot honor are refused, never silently dropped. */
  private def checkOptions(r: QueryRequest): Either[ApiFailure, Unit] =
    def no(code: ApiErrorCode, m: String) = Left(ApiFailure(code, m))
    if r.retrieval.graphLimit == 0 then
      no(
        ApiErrorCode.UnsupportedRetrievalMode,
        "retrieval.graphLimit: vector-only retrieval is not supported",
      )
    else if r.retrieval.vectorLimit != RestLimits.DefaultVectorLimit then
      no(
        ApiErrorCode.UnsupportedRetrievalMode,
        s"retrieval.vectorLimit: only the default ${RestLimits.DefaultVectorLimit} is supported",
      )
    else if !r.output.includeEvidence then
      no(ApiErrorCode.UnsupportedConstraint, "output.includeEvidence: false is not supported")
    else if r.analysis.includeTeamSize then
      no(ApiErrorCode.UnsupportedConstraint, "analysis.includeTeamSize: is not supported")
    else Right(())

  def submit(req: Req, scope: String): Either[ApiFailure, Resp] =
    for
      parsed <- QueryRequest.parse(req.body).left.map(ApiFailure.from)
      _      <- checkOptions(parsed)
      _      <- QueryRequest.checkSupported(parsed, caps).left.map(ApiFailure.from)
      key    <- req.header("idempotency-key") match
        case None    => Right(None)
        case Some(k) => IdempotencyKeys.check(k).left.map(ApiFailure.from).map(Some(_))
      rag <- toRag(parsed)
      queryId = rt.workflow.queryIdFor(rag) // a fresh workflow: stateless for this use
      reqHash = QueryRequest.requestHash(parsed)
      resp <- key match
        case None    => run(req, rag, queryId, reqHash, None)
        case Some(k) =>
          idempotent(req, RestKeys.idempotencyPartition(scope, k), rag, queryId, reqHash)
    yield resp

  private def readIdem(pk: String): Either[ApiFailure, Option[(String, IdempotencyRecord)]] =
    getPayload(pk).flatMap {
      case None    => Right(None)
      case Some(j) =>
        Wire
          .read[IdempotencyRecord](j)
          .left
          .map(_ => internal("stored record is not valid"))
          .map(r => Some(j -> r))
    }

  private def writeIdem(
      pk: String,
      rec: IdempotencyRecord,
      cond: PutCondition,
  ): Either[ApiFailure, PutOutcome] =
    Wire
      .write(rec)
      .left
      .map(_ => internal("record could not be stored"))
      .flatMap(j => putItem(pk, j, rec.expiresAtEpochSeconds, cond))

  /** The error kept in a record: an unexpected internal failure is stored as retryable, so a retry
    * with the same key runs again instead of replaying a permanent 500.
    */
  private def storedError(f: ApiFailure, traceId: String): ApiError =
    if f.code == ApiErrorCode.Internal then
      ApiError(ApiErrorCode.Internal, f.message.take(RestLimits.MaxMessageLength), traceId, true)
    else ApiError.of(f.code, f.message, traceId)

  private def leaseExpired(rec: IdempotencyRecord): Boolean =
    scala.util.Try(Instant.parse(rec.createdAt).toEpochMilli).toOption match
      case Some(t) => nowMillis() >= t + leaseMillis
      case None    => true

  private def idempotent(
      req: Req,
      pk: String,
      rag: RagQueryRequest,
      queryId: String,
      reqHash: String,
  ): Either[ApiFailure, Resp] =
    def base(state: IdempotencyState, now: String, exp: Long) = IdempotencyRecord(
      SchemaVersion.Current,
      pk.stripPrefix(RestKeys.IdempotencyPrefix),
      reqHash,
      queryId,
      state,
      None,
      None,
      None,
      now,
      exp,
    )
    val stillRunning =
      ApiFailure(ApiErrorCode.IncompatibleState, "the request is still being processed")
    def claim(cond: PutCondition): Either[ApiFailure, Resp] =
      val now = nowIso
      val exp = nowSec + RetentionSeconds
      writeIdem(pk, base(IdempotencyState.InProgress, now, exp), cond).flatMap {
        case PutOutcome.ConditionFailed(_) => Left(stillRunning)
        case PutOutcome.Written            =>
          val result = run(req, rag, queryId, reqHash, Some(pk))
          val done   = result match
            case Right(r) if r.status == 200 =>
              if r.body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >
                  RestLimits.MaxResultJsonBytes
              then
                base(IdempotencyState.Failed, now, exp).copy(error =
                  Some(
                    ApiError(
                      ApiErrorCode.Internal,
                      "the result is too large to store",
                      req.traceId,
                      true,
                    )
                  )
                )
              else base(IdempotencyState.Completed, now, exp).copy(responseJson = Some(r.body))
            case Right(r) =>
              val jobId = ujson.read(r.body)("jobId").str
              base(IdempotencyState.Completed, now, exp).copy(jobId = Some(jobId))
            case Left(f) =>
              base(IdempotencyState.Failed, now, exp)
                .copy(error = Some(storedError(f, req.traceId)))
          // One retry; if both fail the lease expires and the key can be reclaimed.
          writeIdem(pk, done, PutCondition.Always)
            .orElse(writeIdem(pk, done, PutCondition.Always))
            .left
            .foreach(f =>
              log.log(
                "warn",
                req.traceId,
                req.requestId,
                "idempotency.update_failed",
                "code" -> f.code.wire,
              )
            )
          result
      }
    readIdem(pk).flatMap {
      case None                                                  => claim(PutCondition.NotExists)
      case Some((j, rec)) if rec.expiresAtEpochSeconds <= nowSec =>
        // Lazy TTL: an expired record is ignored and replaced only if it is still the one we read.
        claim(PutCondition.AttributeEquals("payload", j))
      case Some((_, rec)) if rec.requestHash != reqHash =>
        Left(
          ApiFailure(
            ApiErrorCode.IdempotencyConflict,
            "the Idempotency-Key was already used with a different request",
          )
        )
      case Some((j, rec)) =>
        rec.state match
          case IdempotencyState.InProgress =>
            if leaseExpired(rec) then claim(PutCondition.AttributeEquals("payload", j))
            else Left(stillRunning)
          case IdempotencyState.Failed =>
            val e = rec.error
            if e.exists(!_.retryable) then Left(ApiFailure(e.get.error, e.get.message))
            else claim(PutCondition.AttributeEquals("payload", j))
          case IdempotencyState.Completed =>
            rec.responseJson match
              case Some(r) => Right(Respond.raw(200, r, RestContracts.QueryResponse))
              case None    => replayJob(rec.jobId.getOrElse(""), queryId)
    }

  private def replayJob(jobId: String, queryId: String): Either[ApiFailure, Resp] =
    readJob(jobId).flatMap { rec =>
      rec.state match
        case JobState.Succeeded =>
          Right(Respond.raw(200, rec.resultJson.getOrElse("{}"), RestContracts.QueryResponse))
        case JobState.Failed =>
          val e = rec.error.get
          Left(ApiFailure(e.error, e.message))
        case _ => asyncBody(jobId, queryId, rec.state)
    }

  private def asyncBody(jobId: String, queryId: String, state: JobState): Either[ApiFailure, Resp] =
    val a = QueryResponse.accepted(queryId, jobId).copy(status = state)
    Respond.json(202, a, RestContracts.QueryResponse)

  private val rateLimited =
    ApiFailure(ApiErrorCode.RateLimited, "too many queries are in progress; retry later")

  private def run(
      req: Req,
      rag: RagQueryRequest,
      queryId: String,
      reqHash: String,
      idem: Option[String],
  ): Either[ApiFailure, Resp] =
    // A workflow per run: its resolver cache is never shared between threads.
    val submitted: Either[ApiFailure, java.util.concurrent.Future[(QueryOutcome, Probe)]] =
      try
        Right(pool.submit(new Callable[(QueryOutcome, Probe)]:
          def call(): (QueryOutcome, Probe) =
            val p = Probe.begin()
            try (rt.workflow.run(rag), p)
            finally Probe.end()))
      catch case _: RejectedExecutionException => Left(rateLimited)
    submitted.flatMap(fut => await(req, queryId, reqHash, fut))

  private def await(
      req: Req,
      queryId: String,
      reqHash: String,
      fut: java.util.concurrent.Future[(QueryOutcome, Probe)],
  ): Either[ApiFailure, Resp] =
    val sync: Option[Either[ApiFailure, (QueryOutcome, Probe)]] =
      if cfg.syncDeadlineMillis <= 0 then None
      else
        try Some(Right(fut.get(cfg.syncDeadlineMillis, TimeUnit.MILLISECONDS)))
        catch
          case _: TimeoutException     => None
          case _: ExecutionException   => Some(Left(internal("the query could not be run")))
          case _: InterruptedException =>
            Thread.currentThread().interrupt()
            fut.cancel(true)
            Some(Left(internal("the query could not be run")))
    sync match
      case Some(Left(f))       => Left(f)
      case Some(Right((o, p))) =>
        finalizeOutcome(req, o, p, queryId).flatMap { resp =>
          log.log(
            "info",
            req.traceId,
            req.requestId,
            "query.completed",
            "mode"          -> "synchronous",
            "queryId"       -> queryId,
            "abstained"     -> resp.abstained.toString,
            "abstainReason" -> resp.abstainReason.getOrElse(""),
          )
          Respond.json(200, resp, RestContracts.QueryResponse)
        }
      case None =>
        val jobId   = RestIds.newJobId(ids.next("job"))
        val created = nowIso
        val exp     = nowSec + RetentionSeconds
        val rec     = JobRecord(
          SchemaVersion.Current,
          jobId,
          queryId,
          reqHash,
          JobState.Queued,
          None,
          created,
          created,
          exp,
        )
        writeJob(rec) match
          case Left(f) =>
            fut.cancel(true)
            Left(f)
          case Right(_) =>
            try
              completer.submit(new Runnable:
                def run(): Unit = complete(req, rec, fut, queryId))
              log.log(
                "info",
                req.traceId,
                req.requestId,
                "query.accepted",
                "mode"    -> "asynchronous",
                "queryId" -> queryId,
              )
              asyncBody(jobId, queryId, JobState.Queued)
            catch
              case _: RejectedExecutionException =>
                fut.cancel(true)
                writeJob(
                  rec.copy(
                    state = JobState.Failed,
                    error = Some(ApiError.of(rateLimited.code, rateLimited.message, req.traceId)),
                    updatedAt = nowIso,
                  )
                )
                Left(rateLimited)

  private def writeJob(rec: JobRecord): Either[ApiFailure, Unit] =
    Wire
      .write(rec)
      .left
      .map(_ => internal("job could not be stored"))
      .flatMap(j => putItem(RestKeys.jobPartition(rec.jobId), j, rec.expiresAtEpochSeconds))
      .map(_ => ())

  private def complete(
      req: Req,
      created: JobRecord,
      fut: java.util.concurrent.Future[(QueryOutcome, Probe)],
      queryId: String,
  ): Unit =
    def update(r: JobRecord): Unit =
      // One retry; a job whose final write fails is left for its expiry, never reported as done.
      val rr = r.copy(updatedAt = nowIso)
      writeJob(rr)
        .orElse(writeJob(rr))
        .left
        .foreach(f =>
          log.log("warn", req.traceId, req.requestId, "job.update_failed", "code" -> f.code.wire)
        )
    def fail(f: ApiFailure): Unit =
      update(
        created.copy(
          state = JobState.Failed,
          stage = None,
          error = Some(storedError(f, req.traceId)),
        )
      )
    try
      update(created.copy(state = JobState.Running, stage = Some(JobStage.Planning)))
      val (o, p) = fut.get(cfg.asyncDeadlineMillis, TimeUnit.MILLISECONDS)
      finalizeOutcome(req, o, p, queryId) match
        case Left(f)     => fail(f)
        case Right(resp) =>
          RestJson.write(resp) match
            case Right(json)
                if json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <=
                  RestLimits.MaxResultJsonBytes =>
              update(
                created.copy(state = JobState.Succeeded, stage = None, resultJson = Some(json))
              )
            case _ => fail(internal("the result could not be built"))
      log.log("info", req.traceId, req.requestId, "job.finished", "jobId" -> created.jobId)
    catch
      case _: TimeoutException =>
        fut.cancel(true)
        fail(ApiFailure(ApiErrorCode.Timeout, "the query exceeded its deadline"))
      case NonFatal(_) => fail(internal("the query could not be run"))

  // ---- outcome to REST view -------------------------------------------------------------------

  private def finalizeOutcome(
      req: Req,
      o: QueryOutcome,
      p: Probe,
      queryId: String,
  ): Either[ApiFailure, SyncQueryResponse] =
    if o.abstainReason.contains("plan_invalid") then
      Left(
        ApiFailure(
          ApiErrorCode.UnsupportedRetrievalPlan,
          "the question could not be turned into a supported plan",
        )
      )
    else if p.anyFailure then
      // A downstream call that finally failed, timed out or hit an open breaker is reported as
      // such (502, 504, 503) before any response is built: a partial answer is not presented as
      // complete.
      Left(p.asFailure.get)
    else
      val claims: List[ClaimView] =
        if o.abstained then Nil
        else
          o.answer
            .map(_.claims)
            .getOrElse(Nil)
            .filter(c => clean(c.text).nonEmpty)
            .take(RestLimits.MaxClaims)
            .map { c =>
              val status = o.verification
                .flatMap(_.all.find(_.claimId == c.claimId))
                .map(v => ClaimStatus.from(v.verification.status))
                .getOrElse(ClaimStatus.Unsupported)
              ClaimView(
                c.claimId,
                clean(c.text).take(RestLimits.MaxClaimTextLength),
                c.evidenceIds.distinct.take(RestLimits.MaxEvidenceIdsPerClaim),
                status,
                None, // the workflow does not expose per-claim uncertainty
              )
            }
      val researchers = o.resolution
        .filter(_.allResolved)
        .map(_.subjectIds.distinct.take(RestLimits.MaxResearchers))
        .getOrElse(Nil)
        .map(id => ResearcherView(researcherId = id, claimIds = Nil))
      val summary = Option(o.answerText).map(clean).filter(_.nonEmpty).getOrElse("No answer")
      val reason  =
        if o.abstained then Some(o.abstainReason.filter(_.nonEmpty).getOrElse("unspecified"))
        else None
      val resp = QueryResponse.sync(
        queryId,
        AnswerView(summary.take(RestLimits.MaxSummaryLength), researchers),
        claims,
        o.abstained,
        reason,
      )
      persist(req, o, p, queryId)
      Right(resp)

  /** Control characters (newlines, tabs) become single spaces: the contract forbids them. */
  private def clean(s: String): String =
    s.map(ch => if Character.isISOControl(ch) then ' ' else ch).trim.replaceAll(" {2,}", " ")

  private def persist(req: Req, o: QueryOutcome, p: Probe, queryId: String): Unit =
    val exp                      = nowSec + RetentionSeconds
    def warn(what: String): Unit =
      log.log("warn", req.traceId, req.requestId, "persist.failed", "what" -> what)
    val items  = o.evidence.map(_.bundle.items).getOrElse(Nil).take(MaxStoredEvidence)
    val stored = items.flatMap { item =>
      EvidenceView.from(item).toOption.flatMap(v => RestJson.write(v).toOption).map { json =>
        putItem(EvidencePrefix + item.evidenceId, json, exp).left.foreach(_ => warn("evidence"))
        item.evidenceId
      }
    }
    if stored.nonEmpty then
      putItem(
        QueryEvidencePrefix + queryId,
        ujson.write(ujson.Arr.from(stored.map(ujson.Str(_)))),
        exp,
      ).left.foreach(_ => warn("query-evidence"))
    explainOf(o, p).flatMap(v => RestJson.write(v).toOption) match
      case Some(json) =>
        putItem(ExplainPrefix + queryId, json, exp).left.foreach(_ => warn("explain"))
      case None => ()

  private def explainOf(o: QueryOutcome, p: Probe): Option[ExplainView] =
    o.plan.map { plan =>
      val a      = o.audit
      val stages = List(
        "plan"       -> JobStage.Planning,
        "resolve"    -> JobStage.EntityResolution,
        "retrieve"   -> JobStage.Retrieval,
        "synthesize" -> JobStage.AnswerSynthesis,
        "verify"     -> JobStage.ClaimVerification,
      ).flatMap((k, s) => a.stageLatenciesMillis.get(k).map(StageRunView(s, _)))
      val ops       = plan.operations.map(_.wire)
      val templates = plan.operations.flatMap { op =>
        op.wire match
          case "shortest_path"        => List(GraphTemplates.ShortestPath)
          case "direct_coauthors"     => List(GraphTemplates.Neighbors)
          case "component_lookup"     => List(GraphTemplates.Component)
          case "community_comparison" => List(GraphTemplates.Community)
          case "bridge_ranking"       => List(GraphTemplates.Community)
          case _                      => Nil
      }.distinct
      val modes =
        (if templates.nonEmpty then List(RetrievalMode.Graph) else Nil) ++
          (if a.graphEvidenceIds.nonEmpty then List(RetrievalMode.Metrics) else Nil) ++
          (if a.semanticEvidenceIds.nonEmpty then List(RetrievalMode.Vector) else Nil)
      ExplainView(
        queryId = plan.queryId,
        queryPlan = PlanView.from(plan),
        stagesExecuted = stages,
        retrievalModes = modes,
        queryTemplateIds = templates,
        indexesQueried = if a.semanticEvidenceIds.nonEmpty then VectorIndexes.all else Nil,
        metadataFilters = PlanView.from(plan).filters,
        scoreComponents = Nil,
        promptVersions =
          a.modelCalls.map(PromptVersionView.from).distinct.take(RestLimits.MaxExplainEntries),
        modelVersions =
          a.modelCalls.map(ModelVersionView.from).distinct.take(RestLimits.MaxExplainEntries),
        evidenceIds = o.evidence
          .map(_.bundle.items.map(_.evidenceId))
          .getOrElse(Nil)
          .take(RestLimits.MaxExplainEvidenceIds),
        claimOutcomes = a.verifications.map(ClaimOutcomeView.from).take(RestLimits.MaxClaims),
        counters = ExplainCounters(
          a.totalLatencyMillis,
          a.modelCalls.size.toLong,
          a.modelCalls.map(_.retries.toLong).sum,
          p.retries.toLong,
        ),
        cacheHits = Nil,
      )
    }

  // ---- jobs ---------------------------------------------------------------------------------------

  private def readJob(jobId: String): Either[ApiFailure, JobRecord] =
    for
      id      <- RestIds.checkJobId(jobId).left.map(ApiFailure.from)
      payload <- getPayload(RestKeys.jobPartition(id))
      rec     <- payload match
        case None    => Left(ApiFailure(ApiErrorCode.JobNotFound, "no such job"))
        case Some(j) => Wire.read[JobRecord](j).left.map(_ => internal("stored job is not valid"))
    yield rec

  def job(jobId: String): Either[ApiFailure, Resp] =
    readJob(jobId).flatMap(r => Respond.json(200, r.toStatus, RestContracts.JobStatus))

  def jobResult(jobId: String): Either[ApiFailure, Resp] =
    readJob(jobId).flatMap { r =>
      r.state match
        case JobState.Succeeded =>
          Right(Respond.raw(200, r.resultJson.getOrElse("{}"), RestContracts.QueryResponse))
        case JobState.Failed =>
          val e = r.error.get
          Left(ApiFailure(e.error, e.message))
        case _ => Left(ApiFailure(ApiErrorCode.IncompatibleState, "the job has not finished"))
    }

  // ---- evidence and explain -------------------------------------------------------------------

  def evidence(evidenceId: String): Either[ApiFailure, Resp] =
    for
      id <- RestIds.checkEvidenceId(evidenceId).left.map(ApiFailure.from)
      p  <- getPayload(EvidencePrefix + id)
      r  <- p.toRight(ApiFailure(ApiErrorCode.EvidenceNotFound, "no such evidence"))
    yield Respond.raw(200, r, RestContracts.Evidence)

  def queryEvidence(queryId: String): Either[ApiFailure, Resp] =
    for
      id       <- RestIds.checkQueryId(queryId).left.map(ApiFailure.from)
      p        <- getPayload(QueryEvidencePrefix + id)
      listJson <- p.toRight(ApiFailure(ApiErrorCode.QueryNotFound, "no evidence for this query"))
      items    <- ujson
        .read(listJson)
        .arr
        .toList
        .map(_.str)
        .foldLeft[Either[ApiFailure, List[ujson.Value]]](Right(Nil)) { (acc, eid) =>
          acc.flatMap(l =>
            getPayload(EvidencePrefix + eid).map(o => l ++ o.map(ujson.read(_)).toList)
          )
        }
    yield Respond.raw(
      200,
      ujson.write(ujson.Obj("queryId" -> id, "items" -> ujson.Arr.from(items))),
      RestContracts.Evidence,
    )

  def explain(queryId: String): Either[ApiFailure, Resp] =
    for
      id <- RestIds.checkQueryId(queryId).left.map(ApiFailure.from)
      p  <- getPayload(ExplainPrefix + id)
      r  <- p.toRight(ApiFailure(ApiErrorCode.QueryNotFound, "no explanation for this query"))
    yield Respond.raw(200, r, RestContracts.Explain)

  // ---- graph ------------------------------------------------------------------------------------------

  private def identityOf(nodeId: String): Either[ApiFailure, Option[ujson.Value]] =
    rt.tables.analytics.get("identity-clusters") match
      case None    => Left(internal("identity table is not configured"))
      case Some(t) =>
        rt.kv
          .get(t, KvKey(nodeId))
          .left
          .map(Downstream.toFailure)
          .map(
            _.flatMap(_.attributes.get("payload"))
              .flatMap(s => scala.util.Try(ujson.read(s)).toOption)
          )

  private def nodeView(
      nodeId: String,
      identity: Option[ujson.Value],
  ): Either[ApiFailure, GraphNodeView] =
    val obj = identity
      .flatMap(_.objOpt)
      .map(_.value)
      .getOrElse(scala.collection.mutable.LinkedHashMap.empty[String, ujson.Value])
    val label =
      obj.get("displayName").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty).getOrElse(nodeId)
    val props = List("policyVersion", "memberCount", "ruleFired", "cannotLinkStatus").flatMap { k =>
      obj.get(k).collect {
        case ujson.Str(s) => k -> s
        case ujson.Num(n) => k -> (if n.isWhole then n.toLong.toString else n.toString)
      }
    }.toMap
    val v = GraphNodeView(nodeId, "researcher", label.take(256), props)
    summon[Contract[GraphNodeView]].validate(v).left.map(_ => internal("node could not be built"))

  private def existingNode(nodeId: String): Either[ApiFailure, ujson.Value] =
    for
      id <- RestIds.checkNodeId(nodeId).left.map(ApiFailure.from)
      r  <-
        if !id.startsWith("researcher:") then
          Left(ApiFailure(ApiErrorCode.NodeNotFound, "no such node"))
        else
          identityOf(id).flatMap(_.toRight(ApiFailure(ApiErrorCode.NodeNotFound, "no such node")))
    yield r

  def node(nodeId: String): Either[ApiFailure, Resp] =
    existingNode(nodeId)
      .flatMap(i => nodeView(nodeId, Some(i)))
      .flatMap(v => Respond.json(200, v, RestContracts.Node))

  def neighbors(nodeId: String, query: Map[String, List[String]]): Either[ApiFailure, Resp] =
    for
      nreq   <- NeighborsRequest.fromParams(nodeId, query).left.map(ApiFailure.from)
      cursor <- nreq.decodedCursor.left.map(ApiFailure.from)
      after  <- cursor match
        case None    => Right(None)
        case Some(c) =>
          c.afterKey.split("\\|", 2).toList match
            case List(d, id) if d.length <= 1 && d.toIntOption.isDefined =>
              Right(Some(d.toInt -> id))
            case _ => Left(ApiFailure(ApiErrorCode.InvalidField, "cursor: is not a valid cursor"))
      self  <- existingNode(nodeId)
      found <- collectNeighbors(nreq)
      page  <- buildPage(nreq, found, after)
      resp  <- Respond.json(200, page, RestContracts.Neighbors)
    yield resp

  private final case class Found(distance: Int, id: String, via: String)

  private def collectNeighbors(n: NeighborsRequest): Either[ApiFailure, List[Found]] =
    if !n.effectiveEdgeTypes.contains(EdgeType.CoauthoredWith) then Right(Nil)
    else
      val deadline = nowMillis() + NeighborDeadlineMillis
      var visited  = Set(n.nodeId)
      var frontier = List(n.nodeId)
      var out      = List.empty[Found]
      var queries  = 0
      var depth    = 1
      var failure  = Option.empty[ApiFailure]
      while failure.isEmpty && depth <= n.depth && frontier.nonEmpty && out.size < NeighborCollectCap && queries < NeighborQueryCap
      do
        var next = List.empty[String]
        val it   = frontier.iterator
        while failure.isEmpty && it.hasNext && out.size < NeighborCollectCap && queries < NeighborQueryCap
        do
          val f = it.next()
          if nowMillis() > deadline then
            failure = Some(ApiFailure(ApiErrorCode.Timeout, "the graph read timed out"))
          else
            queries += 1
            rt.graph.query(
              GraphQuery(
                GraphTemplates.Neighbors,
                Map(
                  "researcherId" -> GraphValue.Str(f),
                  "window"       -> GraphValue.Str(RuntimeCode.AllTimeLabel),
                ),
                QueryBounds(1, RestLimits.MaxNeighborLimit, rt.settings.graphTimeoutMillis.toLong),
              )
            ) match
              case Left(e)  => failure = Some(Downstream.toFailure(e))
              case Right(r) =>
                r.rows.foreach { row =>
                  row.columns.get("researcherId").collect { case GraphValue.Str(o) => o }.foreach {
                    o =>
                      if !visited.contains(o) && out.size < NeighborCollectCap then
                        visited += o
                        out = Found(depth, o, f) :: out
                        next = o :: next
                  }
                }
        frontier = next.reverse
        depth += 1
      failure.toLeft(out.sortBy(f => (f.distance, f.id)))

  private def buildPage(
      n: NeighborsRequest,
      found: List[Found],
      after: Option[(Int, String)],
  ): Either[ApiFailure, NeighborsPage] =
    val remaining = after match
      case None         => found
      case Some((d, i)) => found.filter(f => f.distance > d || (f.distance == d && f.id > i))
    val pageItems = remaining.take(n.limit)
    val more      = remaining.size > n.limit
    val views = pageItems.foldLeft[Either[ApiFailure, List[NeighborView]]](Right(Nil)) { (acc, f) =>
      acc.flatMap { l =>
        for
          ident <- identityOf(f.id)
          node  <- nodeView(f.id, ident)
          edge  <- Ids
            .graphEdgeId("coauthored_with", f.via, f.id, false, "coauthor-graph.v1", Nil)
            .left
            .map(_ => internal("edge could not be built"))
        yield NeighborView(
          node,
          GraphEdgeView(edge, EdgeType.CoauthoredWith, f.via, f.id, false, Nil),
          f.distance,
        ) :: l
      }
    }
    views.map { vs =>
      val last = pageItems.lastOption
      NeighborsPage(
        n.nodeId,
        n.direction,
        n.depth,
        n.limit,
        vs.reverse,
        if more then last.map(l => NeighborCursor(s"${l.distance}|${l.id}", n.scopeHash).encode)
        else None,
      )
    }

/** Routes of the public query-api. Authentication applies to every `/v1` route; `/healthz`,
  * `/health` and `/readyz` are open and expose nothing but names and states.
  */
final class QueryApiRoutes(rt: ServiceRuntime, svc: QueryService, auth: ApiKeyAuth):
  def handle(req: Req): Resp =
    val result: Either[ApiFailure, Resp] = (req.method, req.segments) match
      case ("GET", List("healthz")) | ("GET", List("health")) =>
        Respond.json(200, HealthView.alive("query-api", None), RestContracts.Health)
      case ("GET", List("readyz")) => Readiness.respond("query-api", rt)
      case (_, "v1" :: rest)       =>
        if !auth.accepts(req.header("x-api-key")) then
          Left(ApiFailure(ApiErrorCode.Unauthenticated, "missing or invalid credentials"))
        else
          (req.method, rest) match
            case ("POST", List("query"))   => svc.submit(req, auth.scopeOf(req.header("x-api-key")))
            case ("GET", List("jobs", id)) => svc.job(id)
            case ("GET", List("jobs", id, "result"))              => svc.jobResult(id)
            case ("GET", List("evidence", id))                    => svc.evidence(id)
            case ("GET", List("queries", id, "evidence"))         => svc.queryEvidence(id)
            case ("GET", List("graph", "nodes", id))              => svc.node(id)
            case ("GET", List("graph", "nodes", id, "neighbors")) => svc.neighbors(id, req.query)
            case ("GET", List("explain", id))                     => svc.explain(id)
            case _ => Left(ApiFailure(ApiErrorCode.NotFound, "no such route"))
      case _ => Left(ApiFailure(ApiErrorCode.NotFound, "no such route"))
    result.fold(_.toResp(req.traceId), identity)
