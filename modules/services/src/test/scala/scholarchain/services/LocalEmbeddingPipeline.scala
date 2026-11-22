package scholarchain.services

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import scholarchain.core.*
import scholarchain.rag.*
import scholarchain.storage.*

/** Test-only composition of the Phase 08 local embedding run (it lives in the services TEST tree
  * because rag may not depend on storage, and services is the only composer; see the recorded human
  * decision in docs/agent-runs/phase-08-embeddings-vectors/agent-report.md).
  *
  * Pipeline: render (rag) -> enqueue through the producer (storage) into the in-memory queue ->
  * drain with the worker using rag's deterministic local embedder into the in-memory vector store
  * -> a SECOND pass on the same stores with a new run ID (all documents must be skipped) -> the
  * retrieval smoke queries. No AWS client, no network, no wall clock, no randomness: every ID is
  * derived from the input run and the code version, the queue and ledger clocks are fixed, and
  * every output is sorted. Nothing is written outside `outDir`, and no output holds a host path.
  */
final case class PipelineInput(
    inputRunId: String,
    summaries: List[SemanticSummary],
    works: List[WorkRecord],
    venues: List[Venue],
    topics: List[Topic],
    inputRows: Map[String, Int],
    convertRejects: Map[String, Map[String, Int]],
)

final case class PipelineParams(
    codeVersion: String,
    dimension: Int = LocalEmbeddingPipeline.DefaultDimension,
    bucket: String = LocalEmbeddingPipeline.Bucket,
    smokePerType: Int = LocalEmbeddingPipeline.DefaultSmokePerType,
    smokeMinDistinctive: Int = RetrievalSmoke.DefaultMinDistinctive,
)

final case class PipelineResult(
    documentCount: Int,
    documentsByIndex: Map[String, Int],
    documentsByType: Map[String, Int],
    entitiesByType: Map[String, Int],
    renderSkips: Map[String, Int],
    numberCheckFailures: Int,
    runIds: List[String],
    pass1: EmbeddingCounts,
    pass2: EmbeddingCounts,
    producer1: ProducerReport,
    producer2: ProducerReport,
    stoppedBy1: String,
    stoppedBy2: String,
    deadLettered: List[String],
    smoke: SmokeReport,
    invariantFailures: List[String],
    vectors: InMemoryVectorStore,
    objects: FileObjectStore,
)

object LocalEmbeddingPipeline:
  val DefaultDimension: Int      = 1024
  val DefaultSmokePerType: Int   = 5
  val Bucket: String             = "scholarchain-local-text"
  val QueueName: String          = "embedding-jobs"
  val DeadLetterName: String     = "embedding-jobs-dlq"
  val LedgerTable: String        = "run-ledger"
  val ClaimsTable: String        = EmbeddingSettings.DefaultClaimsTableName
  val MaxAttempts: Int           = 3
  val FixedQueueSeconds: Long    = 1_000_000L
  val FixedLedgerInstant: String = "2026-01-01T00:00:00Z"

  /** `run:<64 hex>` derived from the input run, the code version and the pass label. */
  def runIdFor(inputRunId: String, codeVersion: String, pass: Int): String =
    "run:" + Hashing.digest(
      "scholarchain.local-embedding-run.v1",
      Seq(inputRunId, codeVersion, s"pass-$pass"),
    )

  private def write(path: Path, text: String): Unit =
    Files.createDirectories(path.getParent)
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))

  private def jsonLines(values: List[ujson.Value]): String =
    values.map(ujson.write(_)).mkString("", "\n", if values.isEmpty then "" else "\n")

  private def sortedInts(m: Map[String, Int]): ujson.Obj =
    ujson.Obj.from(m.toList.sortBy(_._1).map((k, v) => k -> (ujson.Num(v.toDouble): ujson.Value)))

  private def wire[A: WirePickle.Writer](a: A): ujson.Value = ujson.read(WirePickle.write(a))

  def countsJson(c: EmbeddingCounts): ujson.Value =
    ujson.Obj(
      "jobsProcessed"         -> c.jobsProcessed,
      "documentsSeen"         -> c.documentsSeen,
      "documentsEmbedded"     -> c.documentsEmbedded,
      "vectorsWritten"        -> c.vectorsWritten,
      "skippedUnchanged"      -> c.skippedUnchanged,
      "jobsRetried"           -> c.jobsRetried,
      "documentsRetried"      -> c.documentsRetried,
      "jobsDeadLettered"      -> c.jobsDeadLettered,
      "documentsDeadLettered" -> c.documentsDeadLettered,
      "embedCalls"            -> c.embedCalls,
      "ledgerFailures"        -> c.ledgerFailures,
      "queueFailures"         -> c.queueFailures,
      "rejectedByReason"      -> sortedInts(c.rejectedByReason),
    )

  private def producerJson(p: ProducerReport): ujson.Value =
    ujson.Obj(
      "runId"            -> p.runId,
      "documentsSeen"    -> p.documentsSeen,
      "textsWritten"     -> p.textsWritten,
      "textsUnchanged"   -> p.textsUnchanged,
      "jobsEnqueued"     -> p.jobsEnqueued,
      "rejectedByReason" -> sortedInts(p.rejectedByReason),
    )

  /** Embeds a sparse, lossless view of the vector: the dimension, the non-zero components and a
    * digest of the full float list (the local embedder is a hashed bag of words, so it is sparse).
    */
  private def vectorJson(index: String, e: VectorEntry): ujson.Value =
    val nonZero = e.embedding.zipWithIndex.collect { case (x, i) if x != 0f => i -> x }
    ujson.Obj(
      "index"     -> index,
      "record"    -> wire(e.record),
      "dimension" -> e.embedding.length,
      "nonZero"   -> ujson.Arr.from(
        nonZero.map((i, x) => ujson.Arr(ujson.Num(i.toDouble), ujson.Num(x.toDouble)))
      ),
      "embeddingSha256" -> Hashing.digest("scholarchain.vector.v1", e.embedding.map(_.toString)),
    )

  def run(
      input: PipelineInput,
      params: PipelineParams,
      outDir: Path,
  ): Either[List[String], PipelineResult] =
    if Files.exists(outDir) then
      Left(List("output directory already exists; a run never overwrites, choose a new --out-dir"))
    else if params.dimension < 1 || params.dimension > VectorRecord.MaxDimension then
      Left(List("dimension must be in 1..4096"))
    else if params.codeVersion.isEmpty || params.codeVersion.exists(_.isWhitespace) then
      Left(List("bad code version"))
    else if Ids.checkRunId(input.inputRunId).isLeft then
      Left(List("input run ID is not run:<64 hex>"))
    else Right(execute(input, params, outDir))

  private def execute(input: PipelineInput, params: PipelineParams, outDir: Path): PipelineResult =
    Files.createDirectories(outDir)
    val embedder = EmbedderFactory.localTest(params.dimension)

    // ---- render ---------------------------------------------------------------------------------
    val rendered = EmbeddingDocumentRenderer.renderDetailed(
      input.summaries,
      input.works,
      input.venues,
      input.topics,
      input.inputRunId,
      TextUris.contentAddressed(params.bucket),
    )
    val docs: List[RenderedDocument]  = rendered.documents.sortBy(_.document.documentId)
    val numberFailures                = docs.count(d => NumberCheck.check(d).isLeft)
    val renderSkips: Map[String, Int] =
      rendered.skipped.groupBy(_.reason).view.mapValues(_.size).toMap
    val sources = docs.map(d => EmbeddingSource(d.document, d.text))

    // ---- wiring: fixed clocks, in-memory stores, files only under outDir -----------------------
    val queue   = InMemoryWorkQueue(() => FixedQueueSeconds)
    val objects = FileObjectStore(outDir.resolve("objects"))
    val vectors = InMemoryVectorStore(VectorIndexes.all.map(_ -> params.dimension).toMap)
    val kv      = InMemoryKeyValueStore(Map(LedgerTable -> KvTableSchema(true)))
    val repo    = RunLedgerRepository(kv, LedgerTable)
    // the vector-key claims table (Phase 08 repair 01, M3): in memory, no sort key, no clock
    val claims = InMemoryKeyValueStore(Map(ClaimsTable -> EmbeddingKeyClaims.Schema))
    val ledger = EmbeddingLedger(repo, () => FixedLedgerInstant, params.codeVersion)

    val producer = EmbeddingProducer(
      EmbeddingProducerConfig(
        QueueName,
        EmbeddingJob.MaxDocuments,
        math.max(1, sources.size),
        embedder.modelId,
        embedder.modelVersion,
      ),
      queue,
      objects,
      Some(ledger),
    )
    val worker = EmbeddingWorker(
      EmbeddingWorkerConfig(
        QueueName,
        DeadLetterName,
        MaxAttempts,
        300,
        1,
        None,
        Some(embedder.dimension),
        ClaimsTable,
      ),
      queue,
      objects,
      embedder,
      vectors,
      claims,
      Some(ledger),
    )

    val emptyReport = (id: String) => ProducerReport(id, 0, 0, 0, Nil, Nil, 0, None)
    def pass(n: Int): (ProducerReport, EmbeddingDrainResult, String) =
      val id = runIdFor(input.inputRunId, params.codeVersion, n)
      if sources.isEmpty then
        (emptyReport(id), EmbeddingDrainResult(EmbeddingCounts.zero, "empty"), id)
      else
        val rep = producer
          .enqueue(id, sources)
          .fold(e => throw IllegalStateException(s"enqueue failed: ${e.code}"), identity)
        val drain = worker.drain(rep.jobsEnqueued * (MaxAttempts + 1) + 10)
        (rep, drain, id)

    val (rep1, drain1, id1) = pass(1)
    val (rep2, drain2, id2) = pass(2)

    val dead: List[String] =
      Iterator
        .continually(queue.receive(DeadLetterName, 10, 300).fold(_ => Nil, identity))
        .takeWhile(_.nonEmpty)
        .flatten
        .map(_.body)
        .toList
        .sorted

    // ---- smoke ------------------------------------------------------------------------------------
    val pairs   = docs.map(d => d.document -> d.text)
    val queries = RetrievalSmoke.define(
      pairs,
      perType = params.smokePerType,
      minDistinctive = params.smokeMinDistinctive,
      bucketDimension = embedder.dimension,
    )
    val smoke = RetrievalSmoke.run(vectors, embedder, queries)

    // ---- outputs ----------------------------------------------------------------------------------
    write(
      outDir.resolve("documents.jsonl"),
      jsonLines(docs.map(d => wire(d.document))),
    )

    val missing     = scala.collection.mutable.ListBuffer.empty[String]
    val vectorLines = VectorIndexes.all.flatMap { index =>
      docs
        .map(_.document)
        .filter(d => EmbeddingDocumentTypes.indexFor(d.documentType).contains(index))
        .map(_.documentId)
        .sorted
        .flatMap { key =>
          vectors.get(index, key) match
            case Right(Some(e)) => List(vectorJson(index, e))
            case _              =>
              missing += key
              Nil
        }
    }
    write(outDir.resolve("vectors.jsonl"), jsonLines(vectorLines))

    val ledgerEntries: List[RunLedgerEntry] =
      List(EmbeddingLedger.ProducerStage, EmbeddingLedger.JobStage).flatMap { stage =>
        repo
          .latestForStage(stage, 10_000_000)
          .fold(_ => Nil, identity)
          .flatMap(id => repo.get(id).fold(_ => None, identity))
          .sortBy(e => (e.stage, e.attempt, e.runId))
      }
    write(outDir.resolve("ledger.jsonl"), jsonLines(ledgerEntries.map(wire(_))))

    if dead.nonEmpty then write(outDir.resolve("dlq.jsonl"), dead.mkString("", "\n", "\n"))

    val docsByIndex: Map[String, Int] = docs
      .groupBy(d => EmbeddingDocumentTypes.indexFor(d.document.documentType).getOrElse("unknown"))
      .view
      .mapValues(_.size)
      .toMap

    val rejectsJson = ujson.Obj.from(
      input.convertRejects.toList.sortBy(_._1).map((t, m) => t -> (sortedInts(m): ujson.Value))
    )
    val failures = invariants(
      docs.size,
      numberFailures,
      rep1,
      drain1,
      rep2,
      drain2,
      dead,
      missing.toList,
      smoke,
    )
    val counts = ujson.Obj(
      "inputRunId"  -> input.inputRunId,
      "codeVersion" -> params.codeVersion,
      "embedder"    -> ujson.Obj(
        "modelId"      -> embedder.modelId,
        "modelVersion" -> embedder.modelVersion,
        "dimension"    -> embedder.dimension,
      ),
      "embeddingRunIds" -> ujson.Arr(id1, id2),
      "inputRows"       -> sortedInts(input.inputRows),
      "convertRejects"  -> rejectsJson,
      "documents"       -> ujson.Obj(
        "total"          -> docs.size,
        "byIndex"        -> sortedInts(docsByIndex),
        "byType"         -> sortedInts(rendered.documentsByType),
        "entitiesByType" -> sortedInts(rendered.entitiesByType),
      ),
      "renderSkipsByReason" -> sortedInts(renderSkips),
      "numberCheckFailures" -> numberFailures,
      "pass1"               -> ujson.Obj(
        "producer"  -> producerJson(rep1),
        "worker"    -> countsJson(drain1.counts),
        "stoppedBy" -> drain1.stoppedBy,
      ),
      "pass2" -> ujson.Obj(
        "producer"  -> producerJson(rep2),
        "worker"    -> countsJson(drain2.counts),
        "stoppedBy" -> drain2.stoppedBy,
      ),
      "deadLettered" -> (drain1.counts.documentsDeadLettered + drain2.counts.documentsDeadLettered),
      "ledgerEntries" -> ledgerEntries.size,
      "smoke"         -> ujson.Obj(
        "queries" -> smoke.outcomes.size,
        "passed"  -> smoke.passedCount,
        "failed"  -> smoke.failedCount,
      ),
      "invariantFailures" -> ujson.Arr.from(failures.map(ujson.Str(_))),
    )
    write(outDir.resolve("counts.json"), ujson.write(counts, indent = 2) + "\n")

    val smokeJson = ujson.Obj(
      "queries" -> ujson.Arr.from(
        smoke.outcomes.map { o =>
          ujson.Obj(
            "name"             -> o.query.name,
            "documentType"     -> o.query.documentType,
            "index"            -> o.query.indexName,
            "expectedKey"      -> o.query.expectedVectorKey,
            "expectedEntityId" -> o.query.expectedEntityId,
            "query"            -> o.query.queryText,
            "why"              -> o.query.why,
            "expectedRank"     -> o.expectedRank.fold[ujson.Value](ujson.Null)(r =>
              ujson.Num(r.toDouble)
            ),
            "topKeys" -> ujson.Arr.from(o.topKeys.map(ujson.Str(_))),
            "passed"  -> o.passed,
            "detail"  -> o.detail,
          )
        }
      ),
      "passed" -> smoke.passedCount,
      "failed" -> smoke.failedCount,
    )
    write(outDir.resolve("smoke.json"), ujson.write(smokeJson, indent = 2) + "\n")

    PipelineResult(
      docs.size,
      docsByIndex,
      rendered.documentsByType,
      rendered.entitiesByType,
      renderSkips,
      numberFailures,
      List(id1, id2),
      drain1.counts,
      drain2.counts,
      rep1,
      rep2,
      drain1.stoppedBy,
      drain2.stoppedBy,
      dead,
      smoke,
      failures,
      vectors,
      objects,
    )

  private def invariants(
      n: Int,
      numberFailures: Int,
      rep1: ProducerReport,
      d1: EmbeddingDrainResult,
      rep2: ProducerReport,
      d2: EmbeddingDrainResult,
      dead: List[String],
      missing: List[String],
      smoke: SmokeReport,
  ): List[String] =
    List(
      Option.when(n == 0)("no documents were rendered"),
      Option.when(numberFailures > 0)(
        s"$numberFailures rendered text(s) hold a number absent from the source row"
      ),
      Option.when(rep1.rejected.nonEmpty)(
        s"pass 1: producer rejected ${rep1.rejected.size} document(s)"
      ),
      Option.when(d1.stoppedBy != "empty")(s"pass 1: drain stopped by ${d1.stoppedBy}"),
      Option.when(d1.counts.vectorsWritten != n)(
        s"pass 1: wrote ${d1.counts.vectorsWritten} vectors for $n documents"
      ),
      Option.when(d1.counts.documentsDeadLettered > 0)(
        s"pass 1: ${d1.counts.documentsDeadLettered} document(s) dead-lettered"
      ),
      Option.when(rep2.rejected.nonEmpty)(
        s"pass 2: producer rejected ${rep2.rejected.size} document(s)"
      ),
      Option.when(d2.stoppedBy != "empty")(s"pass 2: drain stopped by ${d2.stoppedBy}"),
      Option.when(d2.counts.skippedUnchanged != n)(
        s"pass 2: skipped ${d2.counts.skippedUnchanged} of $n documents"
      ),
      Option.when(d2.counts.vectorsWritten != 0 || d2.counts.embedCalls != 0)(
        "pass 2: wrote vectors or called the embedder"
      ),
      Option.when(d2.counts.documentsDeadLettered > 0)(
        s"pass 2: ${d2.counts.documentsDeadLettered} document(s) dead-lettered"
      ),
      Option.when(dead.nonEmpty)(s"${dead.size} message(s) in the dead-letter queue"),
      Option.when(missing.nonEmpty)(s"${missing.size} vector(s) missing from the store"),
      Option.when(!smoke.allPassed)(
        s"smoke: ${smoke.failedCount} of ${smoke.outcomes.size} queries failed"
      ),
    ).flatten

  /** Every regular file under `dir` as (relative path, bytes), sorted: used to compare two runs. */
  def snapshot(dir: Path): List[(String, Vector[Byte])] =
    val s = Files.walk(dir)
    try
      s.iterator.asScala
        .filter(Files.isRegularFile(_))
        .map(p => dir.relativize(p).toString.replace('\\', '/') -> Files.readAllBytes(p).toVector)
        .toList
        .sortBy(_._1)
    finally s.close()
