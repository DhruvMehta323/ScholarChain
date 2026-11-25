package scholarchain.services

import java.nio.charset.StandardCharsets

import scala.util.Try

import software.amazon.awssdk.regions.Region

import scholarchain.core.*
import scholarchain.rag.{EmbeddingDocumentRenderer, RenderResult, TextUris}
import scholarchain.storage.*
import scholarchain.storage.LiveLoad.EmbeddingInputFile

/** Run-time arguments of the one-off ops task (`OpsLoadMain`). Every value comes from a
  * `SCHOLARCHAIN_OPS_*` environment variable or the equally named flag (argv wins). Nothing here is
  * a secret; error messages name the variable and never echo a value.
  */
final case class OpsArgs(
    neptuneSource: String,
    itemsSource: String,
    runId: String,
    codeVersion: String,
    maxVectors: Int,
    neptuneTimeoutSeconds: Long,
    maxItems: Long,
    dryRun: Boolean,
)

object OpsArgs:
  /** Hard cap on rendered embedding documents (vectors) per run. Above it is a usage error. */
  val HardMaxVectors: Int         = 10000
  val DefaultTimeoutSeconds: Long = 1800L
  private val Prefix              = "SCHOLARCHAIN_OPS_"
  private val Flags: List[String] = List(
    "neptune-source",
    "items-source",
    "run-id",
    "code-version",
    "max-vectors",
    "neptune-timeout-seconds",
    "max-items",
    "dry-run",
  )

  def envName(flag: String): String = Prefix + flag.toUpperCase.replace('-', '_')

  /** `Left(message)` is a usage error (exit 2). The message names a variable, never a value. */
  def parse(env: Map[String, String], argv: List[String]): Either[String, OpsArgs] =
    val fromEnv: Map[String, String] =
      Flags.flatMap(f => env.get(envName(f)).map(_.trim).filter(_.nonEmpty).map(f -> _)).toMap

    @scala.annotation.tailrec
    def argvLoop(
        rest: List[String],
        acc: Map[String, String],
    ): Either[String, Map[String, String]] =
      rest match
        case Nil                                                   => Right(acc)
        case "--dry-run" :: v :: tl if v == "true" || v == "false" =>
          argvLoop(tl, acc.updated("dry-run", v))
        case "--dry-run" :: tl => argvLoop(tl, acc.updated("dry-run", "true"))
        case f :: v :: tl
            if f.startsWith("--") && Flags.contains(f.drop(2)) && !v.startsWith("--") =>
          argvLoop(tl, acc.updated(f.drop(2), v.trim))
        case f :: _ if f.startsWith("--") && Flags.contains(f.drop(2)) =>
          Left(s"flag $f needs a value")
        case _ => Left("unknown or malformed argument")

    def num(
        m: Map[String, String],
        flag: String,
        default: Long,
        lo: Long,
        hi: Long,
    ): Either[String, Long] =
      m.get(flag) match
        case None    => Right(default)
        case Some(v) =>
          v.toLongOption
            .filter(n => n >= lo && n <= hi)
            .toRight(s"${envName(flag)} must be an integer in $lo..$hi")

    for
      a <- argvLoop(argv, Map.empty)
      m    = fromEnv ++ a
      need = (f: String) => m.get(f).toRight(s"${envName(f)} is required")
      ns  <- need("neptune-source")
      is  <- need("items-source")
      run <- need("run-id")
      cv  <- need("code-version")
      mv  <- num(m, "max-vectors", HardMaxVectors.toLong, 1L, HardMaxVectors.toLong)
      nt  <- num(
        m,
        "neptune-timeout-seconds",
        DefaultTimeoutSeconds,
        1L,
        LiveLoad.HardMaxTimeoutSeconds,
      )
      mi  <- num(m, "max-items", LiveLoad.HardMaxItems, 1L, LiveLoad.HardMaxItems)
      dry <- m.get("dry-run").map(_.toLowerCase) match
        case None          => Right(false)
        case Some("true")  => Right(true)
        case Some("false") => Right(false)
        case Some(_)       => Left(s"${envName("dry-run")} must be true or false")
    yield OpsArgs(ns, is, run, cv, mv.toInt, nt, mi, dry)

/** Everything `OpsLoad` needs besides its ports: the resolved core configuration and where the
  * content-addressed texts go (`s3://<textBucket>/<textPrefix>/<sha256>.txt`).
  */
final case class OpsContext(
    storage: StorageModuleConfig,
    embedding: EmbeddingSettings,
    region: String,
    textBucket: String,
    textPrefix: String = TextUris.DefaultPrefix,
    imageTag: Option[String] = None,
    pollIntervalSeconds: Long = 10L,
)

/** The ports of the ops task. `live` carries the object store used for every read and write. */
final case class OpsPorts(live: LiveLoadPorts, queue: WorkQueue)

/** Result of a run. `exitCode`: 0 completed or dry-run verified; 1 runtime failure; 2 usage or
  * configuration; 3 refusal (checksum, source, cap, nothing to enqueue, rejected documents).
  * `manifestStatus`: `written`, `exists-not-overwritten`, `dry-run-not-persisted`, `not-written`.
  */
final case class OpsOutcome(
    exitCode: Int,
    status: String,
    detail: String,
    manifestJson: Option[String],
    manifestStatus: String,
    producer: Option[ProducerReport],
)

/** Converts the `LocalLoad` embedding-input JSONL files (Spark-free) into core records. It mirrors
  * the test-scope `ParquetInput` (`LocalEmbeddingRun`) rule for rule: a cell is read through the
  * column's declared Spark type, then the same accessors, conversions and contract validation
  * apply. A row that does not convert is counted under a short fixed reason; row content is never
  * reported.
  */
object EmbeddingJsonl:
  final case class Converted[A](records: List[A], rows: Int, rejects: Map[String, Int])

  type Row = Map[String, Cell]

  private val Mismatch: Cell = Cell.Obj(Nil) // a cell that no accessor accepts

  /** The inverse of `Cell.toJson`, guided by the column's Spark type. */
  def cellOf(v: ujson.Value, sparkType: String): Cell =
    (v, sparkType) match
      case (ujson.Null, _)                                                              => Cell.Null
      case (ujson.Str(s), "string")                                                     => Cell.S(s)
      case (ujson.Num(d), "int") if d.isWhole && d >= Int.MinValue && d <= Int.MaxValue =>
        Cell.I(d.toInt)
      case (ujson.Num(d), "bigint" | "long") if d.isWhole && d.abs < (1L << 53).toDouble =>
        Cell.L(d.toLong)
      case (ujson.Str(s), "bigint" | "long") => s.toLongOption.map(Cell.L(_)).getOrElse(Mismatch)
      case (ujson.Num(d), "double")          => Cell.D(d)
      case (ujson.Str(s), "double")          => s.toDoubleOption.map(Cell.D(_)).getOrElse(Mismatch)
      case (ujson.Bool(b), "boolean")        => Cell.B(b)
      case (ujson.Arr(xs), "array<string>")  =>
        Cell.Arr(xs.toList.map {
          case ujson.Null   => Cell.Null
          case ujson.Str(s) => Cell.S(s)
          case _            => Mismatch
        })
      case _ => Mismatch

  /** `Left(())` for a line that is not a JSON object. */
  private def parseLine(line: String, types: Map[String, String]): Either[Unit, Row] =
    Try(ujson.read(line)).toOption.flatMap(_.objOpt) match
      case None    => Left(())
      case Some(o) => Right(o.value.toMap.map((k, v) => k -> cellOf(v, types.getOrElse(k, ""))))

  private def str(r: Row, c: String): Either[String, String] =
    r.get(c) match
      case None            => Left("missing-column")
      case Some(Cell.S(v)) => Right(v)
      case Some(_)         => Left("null-or-wrong-type")

  private def optStr(r: Row, c: String): Either[String, Option[String]] =
    r.get(c) match
      case None            => Left("missing-column")
      case Some(Cell.Null) => Right(None)
      case Some(Cell.S(v)) => Right(Some(v))
      case Some(_)         => Left("wrong-type")

  private def int(r: Row, c: String): Either[String, Int] =
    r.get(c) match
      case None                                                      => Left("missing-column")
      case Some(Cell.I(v))                                           => Right(v)
      case Some(Cell.L(v)) if v >= Int.MinValue && v <= Int.MaxValue => Right(v.toInt)
      case Some(_)                                                   => Left("null-or-wrong-type")

  private def strList(r: Row, c: String): Either[String, List[String]] =
    r.get(c) match
      case None               => Left("missing-column")
      case Some(Cell.Null)    => Right(Nil)
      case Some(Cell.Arr(vs)) =>
        vs.foldRight[Either[String, List[String]]](Right(Nil)) {
          case (Cell.S(s), acc) => acc.map(s :: _)
          case (_, _)           => Left("wrong-type")
        }
      case Some(_) => Left("wrong-type")

  private def convert[A](bytes: Array[Byte], types: Map[String, String])(
      build: Row => Either[String, A]
  )(using c: Contract[A]): Converted[A] =
    val lines   = new String(bytes, StandardCharsets.UTF_8).split("\n").toList.filter(_.nonEmpty)
    val results = lines.map { line =>
      parseLine(line, types) match
        case Left(_)    => Left("malformed-line")
        case Right(row) =>
          build(row).flatMap(a => c.validate(a).left.map(e => s"invalid-${e.code}"))
    }
    Converted(
      results.flatMap(_.toOption),
      lines.size,
      results.flatMap(_.left.toOption).groupBy(identity).view.mapValues(_.size).toMap,
    )

  def summaries(
      bytes: Array[Byte],
      types: Map[String, String],
      prov: Provenance,
  ): Converted[SemanticSummary] =
    convert(bytes, types) { r =>
      for
        entity <- str(r, "entityId")
        tpe    <- str(r, "documentType")
        hash   <- str(r, "textHash")
        text   <- str(r, "text")
        ws     <- int(r, "windowStart")
        we     <- int(r, "windowEnd")
        run    <- str(r, "runId")
      yield SemanticSummary(1, entity, tpe, hash, text, GraphWindow(ws, we), run, prov)
    }

  def works(
      bytes: Array[Byte],
      types: Map[String, String],
      prov: Provenance,
  ): Converted[WorkRecord] =
    convert(bytes, types) { r =>
      for
        id    <- str(r, "workId")
        title <- str(r, "title")
        year  <- int(r, "year")
        doi   <- optStr(r, "doi")
        venue <- optStr(r, "venueId")
        tops  <- strList(r, "topicIds")
      yield WorkRecord(1, id, title, year, doi, venue, tops, prov)
    }

  def venues(bytes: Array[Byte], types: Map[String, String], prov: Provenance): Converted[Venue] =
    convert(bytes, types) { r =>
      for
        id   <- str(r, "venueId")
        name <- str(r, "name")
      yield Venue(1, id, name, None, prov)
    }

  def topics(bytes: Array[Byte], types: Map[String, String], prov: Provenance): Converted[Topic] =
    convert(bytes, types) { r =>
      for
        id    <- str(r, "topicId")
        label <- str(r, "label")
      yield Topic(1, id, label, prov)
    }

/** The one-off ops flow: Neptune bulk load and DynamoDB items (`LiveLoad`), then render the
  * embedding documents from the verified JSONL inputs, enqueue them (`EmbeddingProducer`) and write
  * `ops-manifest.json`. Pure of AWS: every effect goes through `OpsPorts`.
  *
  * Order and safety:
  *   1. PLAN (reads only): verify the four embedding-input files, convert, render under the hard
  *      cap, so a refusal (checksum, source, cap, nothing to enqueue) happens before any write.
  *   2. `LiveLoad.run` (dry-run: verification only).
  *   3. `EmbeddingProducer.enqueue` (dry-run: skipped; no text object is written, nothing is sent).
  *   4. `<items-source>ops-manifest.json` with `putIfAbsent` (never overwritten). Dry-run: the
  *      manifest (`dryRun=true`) is emitted on stdout only and nothing is written to S3, because
  *      the ops role may write only `.../ops-manifest.json` and a dry-run object there would block
  *      the real run.
  *
  * Cap: at most `maxVectors` (hard 10000) rendered documents. Summaries first, then
  * `paperLimit = cap - summaryDocs` papers (sorted by work id), re-rendered; still over is a
  * refusal.
  */
object OpsLoad:
  val ManifestSchema: String = "scholarchain.ops-manifest.v1"
  val Tool: String           = "scholarchain.services.OpsLoadMain"
  val ManifestName: String   = "ops-manifest.json"

  private final case class Fail(exit: Int, status: String, detail: String)
  private type R[A] = Either[Fail, A]

  private final case class TableMeta(partFilesDigest: String, types: Map[String, String])
  private final case class Meta(
      sparkRunId: String,
      sparkCodeVersion: String,
      manifestSha256: String,
      checksumsSha256: String,
      tables: Map[String, TableMeta],
  )

  private final case class Plan(
      meta: Meta,
      files: List[EmbeddingInputFile],
      rows: Map[String, Int],
      rejects: Map[String, Map[String, Int]],
      records: Map[String, Int],
      uncapped: RenderResult,
      capped: RenderResult,
      paperLimit: Option[Int],
      summaryDocs: Int,
      sources: List[EmbeddingSource],
  )

  private final class Logger(emit: String => Unit):
    def apply(level: String, event: String, fields: (String, ujson.Value)*): Unit =
      emit(
        ujson.write(
          ujson.Obj.from(
            List[(String, ujson.Value)](
              "level"   -> ujson.Str(level),
              "service" -> ujson.Str("ops"),
              "event"   -> ujson.Str(event),
            ) ++ fields
          )
        )
      )

  private def n(v: Long): ujson.Value = ujson.Num(v.toDouble)

  def run(args: OpsArgs, ctx: OpsContext, ports: OpsPorts, emit: String => Unit): OpsOutcome =
    val log                       = new Logger(emit)
    def stop(f: Fail): OpsOutcome =
      log(
        "error",
        "ops.refused",
        "status"   -> ujson.Str(f.status),
        "exitCode" -> n(f.exit.toLong),
        "detail"   -> ujson.Str(f.detail.take(300)),
      )
      OpsOutcome(f.exit, f.status, f.detail, None, "not-written", None)

    log(
      "info",
      "ops.start",
      "runId"  -> ujson.Str(args.runId),
      "dryRun" -> ujson.Bool(args.dryRun),
      "cap"    -> n(args.maxVectors.toLong),
    )
    plan(args, ctx, ports) match
      case Left(f)     => stop(f)
      case Right(plan) =>
        log(
          "info",
          "ops.plan",
          "documents"  -> n(plan.capped.documents.size.toLong),
          "uncapped"   -> n(plan.uncapped.documents.size.toLong),
          "paperLimit" -> plan.paperLimit.map(p => n(p.toLong)).getOrElse(ujson.Null),
        )
        val live = LiveLoad.run(
          ports.live,
          ctx.storage,
          ctx.region,
          LiveLoadParams(
            args.neptuneSource,
            args.itemsSource,
            args.runId,
            args.codeVersion,
            args.maxItems,
            args.neptuneTimeoutSeconds,
            ctx.pollIntervalSeconds,
            args.dryRun,
          ),
        )
        log(
          "info",
          "ops.liveload",
          "status"   -> ujson.Str(live.status.label),
          "exitCode" -> n(live.exitCode.toLong),
        )
        if live.exitCode != 0 then
          // The LiveLoad provenance is the failure record (also on stdout, one compact line).
          log(
            "error",
            "ops.liveload.provenance",
            "provenance" -> Try(ujson.read(live.provenanceJson)).getOrElse(ujson.Null),
          )
          stop(Fail(live.exitCode, s"liveload-${live.status.label}", live.detail))
        else if args.dryRun then
          val m = manifest(args, ctx, plan, live, None, "dry-run-verified", 0)
          log("info", "ops.manifest.dry-run", "manifest" -> m)
          OpsOutcome(
            0,
            "dry-run-verified",
            "ok",
            Some(ujson.write(m)),
            "dry-run-not-persisted",
            None,
          )
        else enqueueAndRecord(args, ctx, ports, plan, live, log)

  // ---- enqueue and manifest -------------------------------------------------------------------

  private def enqueueAndRecord(
      args: OpsArgs,
      ctx: OpsContext,
      ports: OpsPorts,
      plan: Plan,
      live: LiveLoadReport,
      log: Logger,
  ): OpsOutcome =
    val producer = new EmbeddingProducer(
      EmbeddingProducerConfig.fromSettings(ctx.embedding, args.maxVectors),
      ports.queue,
      ports.live.objects,
      None,
    )
    producer.enqueue(plan.meta.sparkRunId, plan.sources) match
      case Left(e) =>
        val f = Fail(1, "enqueue-failed", s"enqueue failed (${e.code})")
        log(
          "error",
          "ops.refused",
          "status"   -> ujson.Str(f.status),
          "exitCode" -> n(1L),
          "detail"   -> ujson.Str(f.detail),
        )
        OpsOutcome(1, f.status, f.detail, None, "not-written", None)
      case Right(rep) =>
        log(
          "info",
          "ops.enqueue",
          "textsWritten"   -> n(rep.textsWritten.toLong),
          "textsUnchanged" -> n(rep.textsUnchanged.toLong),
          "jobs"           -> n(rep.jobsEnqueued.toLong),
          "rejected"       -> n(rep.rejected.size.toLong),
        )
        val rejected = rep.rejected.nonEmpty
        val status   = if rejected then "producer-rejected" else "completed"
        val exit     = if rejected then 3 else 0
        val m        = manifest(args, ctx, plan, live, Some(rep), status, exit)
        val body     = ujson.write(m, indent = 2) + "\n"
        val written  =
          for
            loc <- ObjectLocation
              .parse(args.itemsSource)
              .map(l => ObjectLocation(l.bucket, l.objectKey + ManifestName))
              .left
              .map(_ => StorageError.InvalidRequest("items source"))
            _ <- ports.live.objects.putIfAbsent(
              loc,
              body.getBytes(StandardCharsets.UTF_8),
              Some("application/json"),
            )
          yield ()
        val (mStatus, code, finalStatus, detail) = written match
          case Right(_) =>
            (
              "written",
              exit,
              status,
              if rejected then s"${rep.rejected.size} document(s) rejected by the producer"
              else "ok",
            )
          case Left(StorageError.AlreadyExists(_)) =>
            (
              "exists-not-overwritten",
              exit,
              status,
              if rejected then s"${rep.rejected.size} document(s) rejected by the producer"
              else "ok",
            )
          case Left(e) =>
            ("not-written", 1, "manifest-failed", s"manifest write failed (${e.code})")
        log(
          if code == 0 then "info" else "error",
          "ops.manifest",
          "manifestStatus" -> ujson.Str(mStatus),
          "exitCode"       -> n(code.toLong),
          "manifest"       -> m,
        )
        OpsOutcome(code, finalStatus, detail, Some(ujson.write(m)), mStatus, Some(rep))

  // ---- planning (reads only) ------------------------------------------------------------------

  private def plan(args: OpsArgs, ctx: OpsContext, ports: OpsPorts): R[Plan] =
    for
      files <- LiveLoad
        .verifyEmbeddingInputs(ports.live, ctx.storage, args.itemsSource)
        .left
        .map((s, d) => Fail(s.exitCode, s.label, d))
      meta <- readMeta(args.itemsSource, ctx, ports)
      _    <- Ids
        .checkRunId(meta.sparkRunId)
        .left
        .map(_ => Fail(3, "source-invalid", "the source run id is not run:<64 hex>"))
      byTable = files.map(f => f.table -> f).toMap
      cs <- table(meta, byTable, "semantic-summaries")(EmbeddingJsonl.summaries)
      cw <- table(meta, byTable, "works")(EmbeddingJsonl.works)
      cv <- table(meta, byTable, "venues")(EmbeddingJsonl.venues)
      ct <- table(meta, byTable, "topics")(EmbeddingJsonl.topics)
      uriFor = TextUris.contentAddressed(ctx.textBucket, ctx.textPrefix)
      render = (works: List[WorkRecord], limit: Option[Int]) =>
        EmbeddingDocumentRenderer.renderDetailed(
          cs.records,
          works,
          cv.records,
          ct.records,
          meta.sparkRunId,
          uriFor,
          paperLimit = limit,
        )
      full = render(cw.records, None)
      cap  = args.maxVectors
      picked <-
        if full.documents.size <= cap then
          Right((full, Option.empty[Int], full.documents.count(isSummary)))
        else
          val summaryDocs = render(Nil, None).documents.size
          if summaryDocs > cap then
            Left(
              Fail(
                3,
                "cap-exceeded",
                s"summary documents ($summaryDocs) exceed the cap $cap; nothing was written",
              )
            )
          else
            val limit  = cap - summaryDocs
            val capped = render(cw.records, Some(limit))
            if capped.documents.size > cap then
              Left(
                Fail(
                  3,
                  "cap-exceeded",
                  s"${capped.documents.size} documents exceed the cap $cap after limiting papers to $limit; nothing was written",
                )
              )
            else Right((capped, Some(limit), summaryDocs))
      (capped, limit, summaryDocs) = picked
      numberFailures = capped.documents.count(d => scholarchain.rag.NumberCheck.check(d).isLeft)
      _ <-
        if numberFailures == 0 then Right(())
        else
          Left(
            Fail(
              3,
              "number-check-failed",
              s"$numberFailures rendered text(s) hold a number absent from the source row",
            )
          )
      sources = capped.documents
        .sortBy(_.document.documentId)
        .map(d => EmbeddingSource(d.document, d.text))
      _ <-
        if sources.nonEmpty then Right(())
        else Left(Fail(3, "nothing-to-enqueue", "no embedding document was rendered"))
    yield Plan(
      meta,
      files,
      Map(
        "semantic-summaries" -> cs.rows,
        "works"              -> cw.rows,
        "venues"             -> cv.rows,
        "topics"             -> ct.rows,
      ),
      Map(
        "semantic-summaries" -> cs.rejects,
        "works"              -> cw.rejects,
        "venues"             -> cv.rejects,
        "topics"             -> ct.rejects,
      ),
      Map(
        "semantic-summaries" -> cs.records.size,
        "works"              -> cw.records.size,
        "venues"             -> cv.records.size,
        "topics"             -> ct.records.size,
      ),
      full,
      capped,
      limit,
      summaryDocs,
      sources,
    )

  private def isSummary(d: scholarchain.rag.RenderedDocument): Boolean =
    d.document.documentType != EmbeddingDocumentTypes.PaperAbstract

  private def table[A](
      meta: Meta,
      files: Map[String, EmbeddingInputFile],
      name: String,
  )(
      conv: (Array[Byte], Map[String, String], Provenance) => EmbeddingJsonl.Converted[A]
  ): R[EmbeddingJsonl.Converted[A]] =
    for
      f <- files.get(name).toRight(Fail(3, "source-invalid", "embedding input table missing"))
      m <- meta.tables
        .get(name)
        .toRight(Fail(3, "source-invalid", "embedding input has no manifest entry"))
      prov = Provenance(
        1,
        SourceType.ScholarChain,
        List(InputRef(s"scholarchain-graph/$name", meta.sparkRunId, Some(m.partFilesDigest))),
        meta.sparkCodeVersion,
        Map("table" -> name),
        Some(meta.sparkRunId),
        None,
      )
    yield conv(f.bytes, m.types, prov)

  private def readMeta(itemsSource: String, ctx: OpsContext, ports: OpsPorts): R[Meta] =
    def get(loc: ObjectLocation, label: String): R[Array[Byte]] =
      Retry
        .run(ctx.storage.retry, ports.live.sleeper)(ports.live.objects.get(loc))
        .left
        .map {
          case _: StorageError.NotFound => Fail(3, "source-invalid", s"object missing: $label")
          case e                        => Fail(1, "store-error", s"object read failed (${e.code})")
        }
        .map(_.bytes)
    def str(o: ujson.Value, k: String): Option[String] =
      o.objOpt.flatMap(_.get(k)).flatMap(_.strOpt)
    def bad[A](msg: String): R[A] = Left(Fail(3, "source-invalid", msg))
    for
      loc <- ObjectLocation
        .parse(itemsSource)
        .left
        .map(_ => Fail(2, "usage-invalid", "items source must be an s3://bucket/prefix/ URI"))
      sumB <- get(
        ObjectLocation(loc.bucket, loc.objectKey + "checksums.sha256"),
        "checksums.sha256",
      )
      manB <- get(
        ObjectLocation(loc.bucket, loc.objectKey + "load-manifest.json"),
        "load-manifest.json",
      )
      manSha = Hashing.sha256Hex(manB)
      sumSha = Hashing.sha256Hex(sumB)
      listed = new String(sumB, StandardCharsets.UTF_8).linesIterator.collectFirst {
        case l
            if l.endsWith(
              "  load-manifest.json"
            ) && l.length == 64 + 2 + "load-manifest.json".length =>
          l.take(64)
      }
      _ <-
        if listed.contains(manSha) then Right(())
        else Left(Fail(3, "checksum-mismatch", "load-manifest.json differs from checksums.sha256"))
      man <- Try(ujson.read(new String(manB, StandardCharsets.UTF_8))).toEither.left
        .map(_ => Fail(3, "source-invalid", "load-manifest.json is not JSON"))
      emb <- man.objOpt
        .flatMap(_.get("embeddingInputs"))
        .toRight(Fail(3, "source-invalid", "manifest has no embeddingInputs"))
      run <- str(emb, "sparkRunId")
        .map(Right(_))
        .getOrElse(bad("embeddingInputs has no sparkRunId"))
      cv <- str(emb, "sparkCodeVersion")
        .map(Right(_))
        .getOrElse(bad("embeddingInputs has no sparkCodeVersion"))
      fs <- emb.objOpt
        .flatMap(_.get("files"))
        .flatMap(_.arrOpt)
        .map(_.toList)
        .map(Right(_))
        .getOrElse(bad("embeddingInputs has no files"))
      tables <- fs.foldRight[R[Map[String, TableMeta]]](Right(Map.empty)) { (e, acc) =>
        for
          rest <- acc
          t    <- str(e, "table").map(Right(_)).getOrElse(bad("embedding file without a table"))
          d    <- str(e, "partFilesDigest")
            .map(Right(_))
            .getOrElse(bad("embedding file without partFilesDigest"))
          cols = e.objOpt
            .flatMap(_.get("columns"))
            .flatMap(_.arrOpt)
            .map(_.toList.flatMap(c => str(c, "name").zip(str(c, "sparkType"))))
            .getOrElse(Nil)
        yield rest.updated(t, TableMeta(d, cols.toMap))
      }
    yield Meta(run, cv, manSha, sumSha, tables)

  // ---- manifest -------------------------------------------------------------------------------

  private def canonical(v: ujson.Value): ujson.Value = v match
    case o: ujson.Obj =>
      ujson.Obj.from(o.value.toList.sortBy(_._1).map((k, x) => k -> canonical(x)))
    case a: ujson.Arr => ujson.Arr.from(a.value.toList.map(canonical))
    case other        => other

  private def countsJson(m: Map[String, Int]): ujson.Value =
    ujson.Obj.from(m.toList.sortBy(_._1).map((k, c) => k -> n(c.toLong)))

  private def manifest(
      args: OpsArgs,
      ctx: OpsContext,
      plan: Plan,
      live: LiveLoadReport,
      producer: Option[ProducerReport],
      status: String,
      exit: Int,
  ): ujson.Value =
    val papers = (r: RenderResult) =>
      r.documents.count(_.document.documentType == EmbeddingDocumentTypes.PaperAbstract)
    canonical(
      ujson.Obj(
        "schema"      -> ManifestSchema,
        "tool"        -> Tool,
        "status"      -> status,
        "exitCode"    -> n(exit.toLong),
        "dryRun"      -> args.dryRun,
        "runId"       -> args.runId,
        "codeVersion" -> args.codeVersion,
        "imageTag"    -> ctx.imageTag.map(ujson.Str(_)).getOrElse(ujson.Null),
        "source"      -> ujson.Obj(
          "sparkRunId"       -> plan.meta.sparkRunId,
          "sparkCodeVersion" -> plan.meta.sparkCodeVersion,
        ),
        "parameters" -> ujson.Obj(
          "itemsSource"           -> args.itemsSource,
          "neptuneSource"         -> args.neptuneSource,
          "maxVectors"            -> n(args.maxVectors.toLong),
          "maxItems"              -> n(args.maxItems),
          "neptuneTimeoutSeconds" -> n(args.neptuneTimeoutSeconds),
          "embeddingModelId"      -> ctx.embedding.modelId,
          "embeddingModelVersion" -> ctx.embedding.modelVersion,
          "maxDocumentsPerJob"    -> n(ctx.embedding.maxDocumentsPerJob.toLong),
          "textPrefix" -> s"s3://${ctx.textBucket}/${ctx.textPrefix.stripPrefix("/").stripSuffix("/")}/",
        ),
        "inputs" -> ujson.Obj(
          "loadManifestSha256" -> plan.meta.manifestSha256,
          "checksumsSha256"    -> plan.meta.checksumsSha256,
          "embeddingInputs"    -> ujson.Arr.from(
            plan.files
              .sortBy(_.table)
              .map(f =>
                ujson.Obj(
                  "table"  -> f.table,
                  "uri"    -> f.uri,
                  "sha256" -> f.sha256,
                  "rows"   -> n(f.rows),
                  "bytes"  -> n(f.bytes.length.toLong),
                )
              )
          ),
        ),
        "records" -> ujson.Obj.from(
          plan.rows.toList
            .sortBy(_._1)
            .map((t, rows) =>
              t -> ujson.Obj(
                "rows"      -> n(rows.toLong),
                "converted" -> n(plan.records.getOrElse(t, 0).toLong),
                "rejected"  -> countsJson(plan.rejects.getOrElse(t, Map.empty)),
              )
            )
        ),
        "documents" -> ujson.Obj(
          "cap"                -> n(args.maxVectors.toLong),
          "total"              -> n(plan.capped.documents.size.toLong),
          "uncappedTotal"      -> n(plan.uncapped.documents.size.toLong),
          "summaryDocuments"   -> n(plan.summaryDocs.toLong),
          "paperLimit"         -> plan.paperLimit.map(p => n(p.toLong)).getOrElse(ujson.Null),
          "papersDroppedByCap" -> n((papers(plan.uncapped) - papers(plan.capped)).toLong),
          "byType"             -> countsJson(plan.capped.documentsByType),
          "entitiesByType"     -> countsJson(plan.capped.entitiesByType),
          "skippedByReason"    -> countsJson(
            plan.capped.skipped.groupBy(_.reason).view.mapValues(_.size).toMap
          ),
        ),
        "producer" -> producer
          .map(p =>
            ujson.Obj(
              "documentsSeen"    -> n(p.documentsSeen.toLong),
              "textsWritten"     -> n(p.textsWritten.toLong),
              "textsUnchanged"   -> n(p.textsUnchanged.toLong),
              "jobsEnqueued"     -> n(p.jobsEnqueued.toLong),
              "jobIds"           -> ujson.Arr.from(p.jobIds.map(ujson.Str(_))),
              "rejectedByReason" -> countsJson(p.rejectedByReason),
            )
          )
          .getOrElse(ujson.Null),
        "liveLoad" -> Try(ujson.read(live.provenanceJson)).getOrElse(ujson.Null),
      )
    )

/** The ops task entry (`SERVICE_MAIN=scholarchain.services.OpsLoadMain` in the embedding-worker
  * image). No listener, no loop: one run, then the process exits with `OpsOutcome.exitCode`. Exit
  * codes: 0 completed or dry-run verified; 1 runtime failure; 2 usage or configuration; 3 refusal.
  * Settings and secrets come from the core configuration loaders and the process environment; no
  * value is printed.
  */
object OpsLoadMain:
  val ImageTagEnv: String = "SCHOLARCHAIN_IMAGE_TAG"
  val QueueUrlEnv: String = "SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL"
  private val TagPattern  = "^[A-Za-z0-9_.\\-]{1,128}$".r

  private def need(name: String, v: Option[String]): Either[String, String] =
    v.toRight(s"$name is required and is not set")

  private def cfgErrors(what: String, es: List[ValidationError]): String =
    s"$what configuration is invalid: " + es.map(_.toString).mkString("; ").take(400)

  /** Builds the context and the AWS ports. Nothing is called on the network here (the clients are
    * lazy). `Left(message)` names variables, never values.
    */
  def assemble(
      env: Map[String, String],
      seams: AwsSeams = AwsSeams.Production,
  ): Either[String, (OpsContext, OpsPorts)] =
    for
      s        <- ServiceSettings.fromEnv(env)
      _        <- Either.cond(!s.isLocal, (), "the ops task has no local mode")
      region   <- need("SCHOLARCHAIN_AWS_REGION", s.region)
      endpoint <- need("SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT", s.neptuneEndpoint)
      uri      <- Try(java.net.URI.create(endpoint)).toOption
        .filter(u => u.getScheme == "https" && u.getHost != null && u.getHost.nonEmpty)
        .toRight("SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT must be an https URI with a host")
      bucket <- need(
        Composition.CuratedBucketEnv,
        env.get(Composition.CuratedBucketEnv).map(_.trim).filter(_.nonEmpty),
      )
      _ <- Either.cond(
        !bucket.contains("/") && !bucket.toLowerCase.startsWith("s3:"),
        (),
        s"${Composition.CuratedBucketEnv} must be a bare bucket name",
      )
      qurl <- need(QueueUrlEnv, env.get(QueueUrlEnv).map(_.trim).filter(_.nonEmpty))
      sc   <- StorageConfigLoader.load(env).left.map(cfgErrors("storage", _))
      es   <- EmbeddingConfigLoader.load(env).left.map(cfgErrors("embedding", _))
      rag = sc.config.s3.rag.trim
      _ <- Either.cond(
        rag.nonEmpty && !rag.startsWith("/") && !rag.toLowerCase.startsWith("s3:") && !rag.contains(
          ".."
        ),
        (),
        "the rag prefix must be a relative key prefix",
      )
      imageTag = env.get(ImageTagEnv).map(_.trim).filter(TagPattern.matches(_))
      clients  = new AwsClients(
        seams.provider,
        Region.of(region),
        Some(seams.neptuneEndpoint.getOrElse(uri)),
      )
      live <- Try(LiveLoad.awsPorts(clients)).toEither.left
        .map(_ => "the Neptune client could not be configured")
        .flatMap(_.left.map(_ => "the Neptune client could not be configured"))
    yield (
      OpsContext(
        sc.config,
        es.settings,
        region,
        bucket,
        rag.stripSuffix("/") + "/text",
        imageTag,
      ),
      OpsPorts(live, clients.workQueue(Map(es.settings.queueName -> qurl))),
    )

  def execute(
      argv: List[String],
      env: Map[String, String],
      emit: String => Unit,
      build: Map[String, String] => Either[String, (OpsContext, OpsPorts)] = e => assemble(e),
  ): Int =
    def refuse(code: Int, event: String, message: String): Int =
      emit(
        ujson.write(
          ujson.Obj(
            "level"    -> "error",
            "service"  -> "ops",
            "event"    -> event,
            "exitCode" -> ujson.Num(code.toDouble),
            "message"  -> message.take(500),
          )
        )
      )
      code
    OpsArgs.parse(env, argv) match
      case Left(msg)   => refuse(2, "ops.usage", msg)
      case Right(args) =>
        Try(build(env)) match
          case scala.util.Failure(e) =>
            refuse(1, "ops.failed", s"unexpected failure (${e.getClass.getSimpleName})")
          case scala.util.Success(Left(msg))           => refuse(2, "ops.config", msg)
          case scala.util.Success(Right((ctx, ports))) =>
            try OpsLoad.run(args, ctx, ports, emit).exitCode
            catch
              case scala.util.control.NonFatal(e) =>
                refuse(1, "ops.failed", s"unexpected failure (${e.getClass.getSimpleName})")

  def main(args: Array[String]): Unit =
    sys.exit(execute(args.toList, sys.env, line => System.out.println(line)))
