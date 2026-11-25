package scholarchain.storage

import java.nio.charset.StandardCharsets

import scala.util.Try

import scholarchain.core.*

/** Wall clock port (millisecond epoch). Injected so tests control time and never sleep. */
trait LiveClock:
  def nowMillis(): Long

object LiveClock:
  val Real: LiveClock = () => java.lang.System.currentTimeMillis()

/** The ports `LiveLoad` works through. Every one is faked in tests; with `dryRun` neither `loader`
  * nor `items` is ever called.
  */
final case class LiveLoadPorts(
    objects: ObjectStore,
    loader: BulkLoader,
    items: KeyValueStore,
    sleeper: Sleeper,
    clock: LiveClock,
)

/** Caller parameters. `maxItems` caps the TOTAL number of DynamoDB items (refused before any write,
  * Neptune included). `neptuneSource` / `itemsSource` are `s3://bucket/prefix/` URIs whose last
  * path segment is `runId`.
  */
final case class LiveLoadParams(
    neptuneSource: String,
    itemsSource: String,
    runId: String,
    codeVersion: String,
    maxItems: Long,
    neptuneTimeoutSeconds: Long,
    pollIntervalSeconds: Long = 10L,
    dryRun: Boolean = false,
)

/** Distinct outcomes. `exitCode`: 0 ok, 1 failure, 2 usage, 3 cap or invariant refusal. */
enum LiveLoadStatus(val exitCode: Int, val label: String):
  case Completed        extends LiveLoadStatus(0, "completed")
  case DryRunVerified   extends LiveLoadStatus(0, "dry-run-verified")
  case UsageInvalid     extends LiveLoadStatus(2, "usage-invalid")
  case ChecksumMismatch extends LiveLoadStatus(3, "checksum-mismatch")
  case SourceInvalid    extends LiveLoadStatus(3, "source-invalid")
  case CapExceeded      extends LiveLoadStatus(3, "cap-exceeded")
  case NeptuneFailed    extends LiveLoadStatus(1, "neptune-failed")
  case NeptuneCancelled extends LiveLoadStatus(1, "neptune-cancelled")
  case NeptuneTimeout   extends LiveLoadStatus(1, "neptune-timeout")
  case ItemsConflict    extends LiveLoadStatus(1, "items-conflict")
  case StoreError       extends LiveLoadStatus(1, "store-error")

/** Result: the status, a short sanitized detail, and the provenance record as JSON text (always
  * produced, also on failure and dry-run) for the caller to write to S3.
  */
final case class LiveLoadReport(status: LiveLoadStatus, detail: String, provenanceJson: String):
  def exitCode: Int = status.exitCode

/** Spark-free live loader. Reads the output of the unchanged `LocalLoad` (uploaded by the operator)
  * from S3, verifies every file it will use against `checksums.sha256` and `load-manifest.json`,
  * starts the Neptune openCypher bulk load and polls it, then puts the DynamoDB analytics items
  * idempotently (conditional writes: a rerun is all `Unchanged`).
  *
  * Source layout (the `LocalLoad` output tree, with the Neptune files split off):
  * {{{
  * <neptuneSource>vertices/<Label>.csv, <neptuneSource>edges/<TYPE>.csv      (LocalLoad neptune/...)
  * <itemsSource>load-manifest.json, <itemsSource>checksums.sha256
  * <itemsSource>dynamodb/items/<table>.jsonl                                 (LocalLoad dynamodb/...)
  * }}}
  * The Neptune prefix must hold exactly the files the manifest lists (Neptune loads every object
  * under the prefix), so an extra or missing object is refused. Nothing is deleted or overwritten.
  * The Neptune client signs with SigV4 (`neptunedata`) using the injected credentials.
  */
object LiveLoad:
  val Loader: String           = "scholarchain.storage.LiveLoad"
  val ProvenanceSchema: String = "scholarchain.ops-load-provenance.v1"
  val HardMaxItems: Long       = 1000000L
  val HardMaxTimeoutSeconds    = 21600L
  val HardMaxPollSeconds       = 300L
  private val MaxListed        = 20000
  private val ManifestName     = "load-manifest.json"
  private val ChecksumsName    = "checksums.sha256"
  private val LocalLoadName    = "scholarchain.storage.LocalLoad"
  private val RunIdPattern     = "^[A-Za-z0-9_.\\-]{1,128}$".r

  private type Fail = (LiveLoadStatus, String)
  private type R[A] = Either[Fail, A]

  /** Ports over the real AWS clients. Builds nothing until a port is used; `Left` when no Neptune
    * endpoint was configured.
    */
  def awsPorts(clients: AwsClients): StorageResult[LiveLoadPorts] =
    clients.neptuneData.map(n =>
      LiveLoadPorts(
        clients.objectStore,
        NeptuneAws.loader(n),
        clients.keyValueStore,
        Sleeper.System,
        LiveClock.Real,
      )
    )

  /** Writes the provenance JSON once (`putIfAbsent`): never overwrites. */
  def writeProvenance(
      store: ObjectStore,
      location: ObjectLocation,
      report: LiveLoadReport,
  ): StorageResult[ObjectMeta] =
    store.putIfAbsent(
      location,
      report.provenanceJson.getBytes(StandardCharsets.UTF_8),
      Some("application/json"),
    )

  private final case class FileFact(role: String, uri: String, sha256: String, bytes: Long)

  private final case class Parsed(
      neptuneFiles: List[(String, ObjectLocation, String, Long)], // path, location, sha, rows
      itemFiles: List[(String, ObjectLocation, String, Long)],    // table, location, sha, rows
      loadRunId: String,
      sparkRunId: String,
      manifestCodeVersion: String,
  )

  private final class Facts:
    var startedAt           = 0L
    var verifyMillis        = 0L
    var neptuneMillis       = 0L
    var itemsMillis         = 0L
    var files               = List.empty[FileFact]
    var neptuneLoadId       = Option.empty[String]
    var neptuneStatus       = "not-started"
    var neptuneRecords      = Option.empty[Long]
    var loadRunId           = ""
    var sparkRunId          = ""
    var manifestCodeVersion = ""
    var plannedItems        = Map.empty[String, Long]
    var itemReports         = Map.empty[String, AnalyticsLoadReport]
    var neptuneRows         = 0L

  def run(
      ports: LiveLoadPorts,
      cfg: StorageModuleConfig,
      region: String,
      p: LiveLoadParams,
  ): LiveLoadReport =
    val facts = new Facts
    facts.startedAt = ports.clock.nowMillis()
    val outcome: R[Unit] = execute(ports, cfg, region, p, facts)
    val total            = ports.clock.nowMillis() - facts.startedAt
    val (status, detail) = outcome match
      case Right(_) =>
        (if p.dryRun then LiveLoadStatus.DryRunVerified else LiveLoadStatus.Completed, "ok")
      case Left((s, d)) => (s, d)
    LiveLoadReport(status, detail, render(p, facts, status, detail, total))

  private def execute(
      ports: LiveLoadPorts,
      cfg: StorageModuleConfig,
      region: String,
      p: LiveLoadParams,
      f: Facts,
  ): R[Unit] =
    for
      locs <- validate(cfg, region, p)
      (neptuneLoc, itemsLoc, request) = locs
      t0                              = ports.clock.nowMillis()
      parsed <- verify(ports, cfg, neptuneLoc, itemsLoc, f)
      items  <- readItems(ports, cfg, parsed, f)
      _      <- cap(p, items, f)
      _ = f.verifyMillis = ports.clock.nowMillis() - t0
      _ <- if p.dryRun then Right(()) else loadNeptune(ports, cfg, request, p, f)
      _ <- if p.dryRun then Right(()) else putItems(ports, cfg, items, f)
    yield ()

  // ---- validation ----------------------------------------------------------------------------

  private def validate(
      cfg: StorageModuleConfig,
      region: String,
      p: LiveLoadParams,
  ): R[(ObjectLocation, ObjectLocation, NeptuneLoaderRequest)] =
    def usage[A](cond: Boolean, msg: String): R[Unit] =
      if cond then Right(()) else Left((LiveLoadStatus.UsageInvalid, msg))
    def src(name: String, uri: String): R[ObjectLocation] =
      ObjectLocation
        .parse(uri)
        .left
        .map(_ => (LiveLoadStatus.UsageInvalid, s"$name: not an s3 URI"))
        .flatMap { l =>
          if !uri.endsWith("/") || uri.contains("/../") || uri.contains("//" + "..") then
            Left((LiveLoadStatus.UsageInvalid, s"$name: must end with '/' and not contain '..'"))
          else if !uri.dropRight(1).endsWith("/" + p.runId) then
            Left((LiveLoadStatus.UsageInvalid, s"$name: last path segment must equal the run id"))
          else Right(l)
        }
    for
      _ <- usage(RunIdPattern.matches(p.runId), "run id has an invalid form")
      _ <- usage(
        p.codeVersion.nonEmpty && p.codeVersion.length <= 64 && !p.codeVersion.exists(
          _.isWhitespace
        ),
        "code version has an invalid form",
      )
      _ <- usage(
        p.maxItems >= 1L && p.maxItems <= HardMaxItems,
        s"maxItems must be 1..$HardMaxItems",
      )
      _ <- usage(
        p.neptuneTimeoutSeconds >= 1L && p.neptuneTimeoutSeconds <= HardMaxTimeoutSeconds,
        s"neptune timeout must be 1..$HardMaxTimeoutSeconds seconds",
      )
      _ <- usage(
        p.pollIntervalSeconds >= 1L && p.pollIntervalSeconds <= HardMaxPollSeconds,
        s"poll interval must be 1..$HardMaxPollSeconds seconds",
      )
      n   <- src("neptune source", p.neptuneSource)
      i   <- src("items source", p.itemsSource)
      req <- NeptuneBulkLoad
        .buildRequest(
          cfg.neptune,
          BulkLoadRequest(p.neptuneSource, cfg.neptune.format, p.runId),
          region,
        )
        .left
        .map(e => (LiveLoadStatus.UsageInvalid, s"neptune request refused (${e.code})"))
    yield (n, i, req)

  // ---- reading and verification --------------------------------------------------------------

  private def fetch(
      ports: LiveLoadPorts,
      cfg: StorageModuleConfig,
      l: ObjectLocation,
  ): R[Array[Byte]] =
    Retry
      .run(cfg.retry, ports.sleeper)(ports.objects.get(l))
      .left
      .map {
        case _: StorageError.NotFound =>
          (LiveLoadStatus.SourceInvalid, s"object missing: ${l.objectKey.split('/').last}")
        case e => (LiveLoadStatus.StoreError, s"object read failed (${e.code})")
      }
      .map(_.bytes)

  private def listKeys(
      ports: LiveLoadPorts,
      cfg: StorageModuleConfig,
      l: ObjectLocation,
  ): R[List[String]] =
    def loop(token: Option[String], acc: List[String]): R[List[String]] =
      Retry.run(cfg.retry, ports.sleeper)(
        ports.objects.list(l.bucket, l.objectKey, 1000, token)
      ) match
        case Left(e)     => Left((LiveLoadStatus.StoreError, s"object list failed (${e.code})"))
        case Right(page) =>
          val all = acc ++ page.objects.map(_.location.objectKey).filterNot(_.endsWith("/"))
          if all.size > MaxListed then
            Left((LiveLoadStatus.SourceInvalid, "too many objects under the source prefix"))
          else
            page.nextToken match
              case Some(t) => loop(Some(t), all)
              case None    => Right(all)
    loop(None, Nil)

  private def parseChecksums(text: String): R[Map[String, String]] =
    val lines  = text.split("\n").toList.filter(_.nonEmpty)
    val parsed = lines.map { ln =>
      val i = ln.indexOf("  ")
      if i == 64 && ln.take(64).matches("[0-9a-f]{64}") then Right(ln.substring(66) -> ln.take(64))
      else Left((LiveLoadStatus.SourceInvalid, "checksums.sha256 has a malformed line"))
    }
    parsed.collectFirst { case Left(e) => e } match
      case Some(e) => Left(e)
      case None    =>
        val entries = parsed.collect { case Right(x) => x }
        if entries.map(_._1).distinct.size != entries.size then
          Left((LiveLoadStatus.SourceInvalid, "checksums.sha256 lists a path twice"))
        else Right(entries.toMap)

  private def str(o: ujson.Value, k: String): Option[String] =
    o.objOpt.flatMap(_.get(k)).flatMap(_.strOpt)
  private def num(o: ujson.Value, k: String): Option[Long] =
    o.objOpt.flatMap(_.get(k)).flatMap(_.numOpt).filter(d => d >= 0 && d == d.toLong).map(_.toLong)

  private def verify(
      ports: LiveLoadPorts,
      cfg: StorageModuleConfig,
      neptuneLoc: ObjectLocation,
      itemsLoc: ObjectLocation,
      f: Facts,
  ): R[Parsed] =
    def sourceBad[A](msg: String): R[A] = Left((LiveLoadStatus.SourceInvalid, msg))
    def mismatch[A](msg: String): R[A]  = Left((LiveLoadStatus.ChecksumMismatch, msg))
    def uri(l: ObjectLocation)          = l.uri
    def record(role: String, l: ObjectLocation, bytes: Array[Byte]): String =
      val h = Cells.sha256(bytes)
      f.files = f.files :+ FileFact(role, uri(l), h, bytes.length.toLong)
      h
    val sumLoc = ObjectLocation(itemsLoc.bucket, itemsLoc.objectKey + ChecksumsName)
    for
      sumB <- fetch(ports, cfg, sumLoc)
      _ = record("checksums", sumLoc, sumB)
      sums <- parseChecksums(new String(sumB, StandardCharsets.UTF_8))
      manLoc = ObjectLocation(itemsLoc.bucket, itemsLoc.objectKey + ManifestName)
      manB <- fetch(ports, cfg, manLoc)
      manSha = record("load-manifest", manLoc, manB)
      _ <-
        if sums.get(ManifestName).contains(manSha) then Right(())
        else mismatch("load-manifest.json differs from checksums.sha256")
      man <- Try(ujson.read(new String(manB, StandardCharsets.UTF_8))).toEither.left.map(_ =>
        (LiveLoadStatus.SourceInvalid, "load-manifest.json is not JSON")
      )
      _ <-
        if str(man, "loader").contains(LocalLoadName) then Right(())
        else sourceBad("load-manifest.json is not a LocalLoad manifest")
      lrun <- str(man, "loadRunId").toRight(
        (LiveLoadStatus.SourceInvalid, "manifest has no loadRunId")
      )
      nep   = man.objOpt.flatMap(_.get("neptune")).getOrElse(ujson.Null)
      ddb   = man.objOpt.flatMap(_.get("dynamodb")).getOrElse(ujson.Null)
      invOk = nep.objOpt
        .flatMap(_.get("invariants"))
        .flatMap(_.objOpt)
        .exists(m => m.nonEmpty && m.values.forall(_.numOpt.contains(0.0)))
      _ <- if invOk then Right(()) else sourceBad("manifest neptune invariants are not all zero")
      nFiles <- entries(nep, "files").flatMap(
        _.foldRight[R[List[(String, String, Long)]]](Right(Nil)) { (e, acc) =>
          for
            rest <- acc
            path <- str(e, "path")
              .filter(x => x.startsWith("neptune/vertices/") || x.startsWith("neptune/edges/"))
              .filter(x => x.endsWith(".csv") && !x.contains(".."))
              .toRight((LiveLoadStatus.SourceInvalid, "bad neptune file path in manifest"))
            sha <- str(e, "sha256").toRight(
              (LiveLoadStatus.SourceInvalid, "neptune file without sha256")
            )
            rows <- num(e, "rows").toRight(
              (LiveLoadStatus.SourceInvalid, "neptune file without rows")
            )
          yield (path, sha, rows) :: rest
        }
      )
      dFiles <- entries(ddb, "files").flatMap(
        _.foldRight[R[List[(String, String, String, Long)]]](Right(Nil)) { (e, acc) =>
          for
            rest  <- acc
            table <- str(e, "table")
              .filter(t => cfg.tables.analytics.contains(t))
              .toRight(
                (LiveLoadStatus.SourceInvalid, "items file for a table that is not configured")
              )
            path <- str(e, "path")
              .filter(_ == s"items/${str(e, "table").getOrElse("")}.jsonl")
              .toRight((LiveLoadStatus.SourceInvalid, "bad items file path in manifest"))
            sha <- str(e, "sha256").toRight(
              (LiveLoadStatus.SourceInvalid, "items file without sha256")
            )
            rows <- num(e, "rows").toRight(
              (LiveLoadStatus.SourceInvalid, "items file without rows")
            )
          yield (table, path, sha, rows) :: rest
        }
      )
      _ <-
        if nFiles.nonEmpty && nFiles.map(_._1).distinct.size == nFiles.size then Right(())
        else sourceBad("manifest lists no neptune files or lists one twice")
      _ <-
        if dFiles.nonEmpty && dFiles.map(_._1).distinct.size == dFiles.size then Right(())
        else sourceBad("manifest lists no items files or lists one twice")
      // The Neptune prefix must hold exactly the manifest's files: Neptune loads every object there.
      listed <- listKeys(ports, cfg, neptuneLoc)
      expectK = nFiles.map(x => neptuneLoc.objectKey + x._1.stripPrefix("neptune/")).sorted
      _ <-
        if listed.sorted == expectK then Right(())
        else sourceBad("neptune prefix holds other objects than the manifest lists")
      nParsed <- nFiles.foldRight[R[List[(String, ObjectLocation, String, Long)]]](Right(Nil)) {
        (e, acc) =>
          val (path, sha, rows) = e
          val loc               =
            ObjectLocation(neptuneLoc.bucket, neptuneLoc.objectKey + path.stripPrefix("neptune/"))
          for
            rest  <- acc
            bytes <- fetch(ports, cfg, loc)
            actual = record("neptune-csv", loc, bytes)
            _ <-
              if sums.get(path).contains(sha) && actual == sha then Right(())
              else mismatch(s"checksum mismatch: $path")
          yield (path, loc, sha, rows) :: rest
      }
      dParsed <- dFiles.foldRight[R[List[(String, ObjectLocation, String, Long)]]](Right(Nil)) {
        (e, acc) =>
          val (table, path, sha, rows) = e
          val loc = ObjectLocation(itemsLoc.bucket, itemsLoc.objectKey + "dynamodb/" + path)
          for
            rest <- acc
            _    <-
              if sums.contains(s"dynamodb/$path") then Right(())
              else mismatch(s"no checksum entry: dynamodb/$path")
            bytes <- fetch(ports, cfg, loc)
            actual = record("items-jsonl", loc, bytes)
            _ <-
              if sums.get(s"dynamodb/$path").contains(sha) && actual == sha then Right(())
              else mismatch(s"checksum mismatch: dynamodb/$path")
          yield (table, loc, sha, rows) :: rest
      }
      _ <-
        if man.objOpt.exists(_.contains("embeddingInputs")) then
          verifyEmbedding(ports, cfg, itemsLoc, sums, man, f).map(_ => ())
        else Right(())
    yield
      f.loadRunId = lrun
      f.sparkRunId = str(man, "sparkRunId")
        .orElse(man.objOpt.flatMap(_.get("input")).flatMap(i => str(i, "sparkRunId")))
        .getOrElse("")
      f.manifestCodeVersion = str(man, "codeVersion").getOrElse("")
      f.neptuneRows = nParsed.map(_._4).sum
      Parsed(nParsed, dParsed, lrun, f.sparkRunId, f.manifestCodeVersion)

  /** A verified embedding-input JSONL file (see `LocalLoad.EmbeddingFile` for the line schema). */
  final case class EmbeddingInputFile(
      table: String,
      uri: String,
      sha256: String,
      rows: Long,
      bytes: Array[Byte],
  )

  private def verifyEmbedding(
      ports: LiveLoadPorts,
      cfg: StorageModuleConfig,
      itemsLoc: ObjectLocation,
      sums: Map[String, String],
      man: ujson.Value,
      f: Facts,
  ): R[List[EmbeddingInputFile]] =
    for
      es <- man.objOpt
        .flatMap(_.get("embeddingInputs"))
        .toRight((LiveLoadStatus.SourceInvalid, "manifest has no embeddingInputs"))
        .flatMap(entries(_, "files"))
      specs <- es.foldRight[R[List[(String, String, String, Long)]]](Right(Nil)) { (e, acc) =>
        for
          rest  <- acc
          table <- str(e, "table")
            .filter(LocalLoad.EmbeddingTables.contains)
            .toRight((LiveLoadStatus.SourceInvalid, "unknown embedding input table"))
          path <- str(e, "path")
            .filter(_ == s"embedding-inputs/$table.jsonl")
            .toRight((LiveLoadStatus.SourceInvalid, "bad embedding input path in manifest"))
          sha <- str(e, "sha256")
            .toRight((LiveLoadStatus.SourceInvalid, "embedding input without sha256"))
          rows <- num(e, "rows")
            .toRight((LiveLoadStatus.SourceInvalid, "embedding input without rows"))
        yield (table, path, sha, rows) :: rest
      }
      _ <-
        if specs.map(_._1).sorted == LocalLoad.EmbeddingTables.sorted then Right(())
        else
          Left((LiveLoadStatus.SourceInvalid, "embedding inputs must list exactly the four tables"))
      out <- specs.foldRight[R[List[EmbeddingInputFile]]](Right(Nil)) { (e, acc) =>
        val (table, path, sha, rows) = e
        val loc                      = ObjectLocation(itemsLoc.bucket, itemsLoc.objectKey + path)
        for
          rest  <- acc
          bytes <- fetch(ports, cfg, loc)
          actual = Cells.sha256(bytes)
          _ = f.files = f.files :+ FileFact("embedding-input", loc.uri, actual, bytes.length.toLong)
          _ <-
            if sums.get(path).contains(sha) && actual == sha then Right(())
            else Left((LiveLoadStatus.ChecksumMismatch, s"checksum mismatch: $path"))
          lines = new String(bytes, StandardCharsets.UTF_8).split("\n").count(_.nonEmpty).toLong
          _ <-
            if lines == rows then Right(())
            else
              Left(
                (LiveLoadStatus.SourceInvalid, s"$table: line count differs from the manifest rows")
              )
        yield EmbeddingInputFile(table, loc.uri, sha, rows, bytes) :: rest
      }
    yield out

  /** For `OpsLoadMain`: reads `checksums.sha256` and `load-manifest.json` from `itemsSource`,
    * verifies them and the four `embedding-inputs/<table>.jsonl` files (SHA-256 against both, line
    * count against the manifest) and returns the verified bytes. `Left((status, detail))` uses the
    * same distinct statuses as `run`.
    */
  def verifyEmbeddingInputs(
      ports: LiveLoadPorts,
      cfg: StorageModuleConfig,
      itemsSource: String,
  ): Either[(LiveLoadStatus, String), List[EmbeddingInputFile]] =
    for
      loc <- ObjectLocation
        .parse(itemsSource)
        .filterOrElse(_ => itemsSource.endsWith("/"), ())
        .left
        .map(_ => (LiveLoadStatus.UsageInvalid, "items source must be an s3://bucket/prefix/ URI"))
      sumB <- fetch(ports, cfg, ObjectLocation(loc.bucket, loc.objectKey + ChecksumsName))
      sums <- parseChecksums(new String(sumB, StandardCharsets.UTF_8))
      manB <- fetch(ports, cfg, ObjectLocation(loc.bucket, loc.objectKey + ManifestName))
      _    <-
        if sums.get(ManifestName).contains(Cells.sha256(manB)) then Right(())
        else
          Left(
            (LiveLoadStatus.ChecksumMismatch, "load-manifest.json differs from checksums.sha256")
          )
      man <- Try(ujson.read(new String(manB, StandardCharsets.UTF_8))).toEither.left
        .map(_ => (LiveLoadStatus.SourceInvalid, "load-manifest.json is not JSON"))
      out <- verifyEmbedding(ports, cfg, loc, sums, man, new Facts)
    yield out

  private def entries(o: ujson.Value, k: String): R[List[ujson.Value]] =
    o.objOpt
      .flatMap(_.get(k))
      .flatMap(_.arrOpt)
      .map(_.toList)
      .toRight((LiveLoadStatus.SourceInvalid, s"manifest has no '$k' list"))

  /** Items parsed from the verified JSONL files, in manifest-table order. */
  private def readItems(
      ports: LiveLoadPorts,
      cfg: StorageModuleConfig,
      parsed: Parsed,
      f: Facts,
  ): R[List[(String, List[AnalyticsItems.AnalyticsItem])]] =
    parsed.itemFiles
      .sortBy(_._1)
      .foldRight[R[List[(String, List[AnalyticsItems.AnalyticsItem])]]](Right(Nil)) { (e, acc) =>
        val (table, loc, _, rows) = e
        for
          rest  <- acc
          bytes <- fetch(ports, cfg, loc) // verified above; read again only for parsing
          its   <- parseLines(table, new String(bytes, StandardCharsets.UTF_8), rows)
        yield
          f.plannedItems = f.plannedItems.updated(table, its.size.toLong)
          (table, its) :: rest
      }

  private def parseLines(
      table: String,
      text: String,
      rows: Long,
  ): R[List[AnalyticsItems.AnalyticsItem]] =
    val hasSort = AnalyticsRepository.HasSortKey.getOrElse(table, false)
    val lines   = text.split("\n").toList.filter(_.nonEmpty)
    val items   = lines.map { ln =>
      Try(ujson.read(ln)).toOption.flatMap { j =>
        for
          t     <- str(j, "table") if t == table
          pk    <- str(j, "pk")
          attrs <- j.objOpt.flatMap(_.get("attributes")).filter(_.objOpt.isDefined)
          ph    <- str(j, "payloadHash")
          sk = str(j, "sk")
          if sk.isDefined == hasSort
          text = ujson.write(attrs)
          if Cells.sha256(text) == ph
        yield AnalyticsItems.AnalyticsItem(table, pk, sk, text, ph)
      }
    }
    if items.exists(_.isEmpty) then
      Left(
        (
          LiveLoadStatus.SourceInvalid,
          s"$table: a JSONL line is malformed or its payloadHash differs",
        )
      )
    else if items.size.toLong != rows then
      Left((LiveLoadStatus.SourceInvalid, s"$table: line count differs from the manifest rows"))
    else Right(items.flatten)

  private def cap(
      p: LiveLoadParams,
      items: List[(String, List[AnalyticsItems.AnalyticsItem])],
      f: Facts,
  ): R[Unit] =
    val total = items.map(_._2.size.toLong).sum
    if total > p.maxItems then
      Left(
        (
          LiveLoadStatus.CapExceeded,
          s"$total items exceed the cap ${p.maxItems}; nothing was written",
        )
      )
    else Right(())

  // ---- Neptune -------------------------------------------------------------------------------

  private def loadNeptune(
      ports: LiveLoadPorts,
      cfg: StorageModuleConfig,
      request: NeptuneLoaderRequest,
      p: LiveLoadParams,
      f: Facts,
  ): R[Unit] =
    val t0                     = ports.clock.nowMillis()
    val deadline               = t0 + p.neptuneTimeoutSeconds * 1000L
    def done[A](r: R[A]): R[A] =
      f.neptuneMillis = ports.clock.nowMillis() - t0
      r
    // Not retried: a second start after an unseen success could queue a duplicate job.
    ports.loader.start(request) match
      case Left(e) =>
        f.neptuneStatus = "start-failed"
        done(Left((LiveLoadStatus.NeptuneFailed, s"bulk load start failed (${e.code})")))
      case Right(h) =>
        f.neptuneLoadId = Some(h.loadId)
        f.neptuneStatus = "submitted"
        @scala.annotation.tailrec
        def poll(): R[Unit] =
          Retry.run(cfg.retry, ports.sleeper)(ports.loader.status(h)) match
            case Left(e) =>
              f.neptuneStatus = "status-unavailable"
              Left((LiveLoadStatus.StoreError, s"bulk load status failed (${e.code})"))
            case Right(BulkLoadStatus.Completed(n)) =>
              f.neptuneStatus = "completed"; f.neptuneRecords = n; Right(())
            case Right(BulkLoadStatus.LoadFailed(r)) =>
              f.neptuneStatus = "failed"
              Left((LiveLoadStatus.NeptuneFailed, s"bulk load failed: ${r.take(80)}"))
            case Right(BulkLoadStatus.Cancelled) =>
              f.neptuneStatus = "cancelled"
              Left((LiveLoadStatus.NeptuneCancelled, "bulk load was cancelled"))
            case Right(other) =>
              f.neptuneStatus = other match
                case BulkLoadStatus.InProgress(_) => "in-progress"
                case _                            => "submitted"
              if ports.clock.nowMillis() >= deadline then
                f.neptuneStatus = "timeout"
                Left(
                  (
                    LiveLoadStatus.NeptuneTimeout,
                    s"no completion within ${p.neptuneTimeoutSeconds} s; the job was left running",
                  )
                )
              else
                ports.sleeper.sleep(p.pollIntervalSeconds * 1000L)
                poll()
        done(poll())

  // ---- DynamoDB ------------------------------------------------------------------------------

  private def putItems(
      ports: LiveLoadPorts,
      cfg: StorageModuleConfig,
      items: List[(String, List[AnalyticsItems.AnalyticsItem])],
      f: Facts,
  ): R[Unit] =
    val t0   = ports.clock.nowMillis()
    val repo = AnalyticsRepository.fromConfig(ports.items, cfg.tables)
    val res  = items.foldLeft[R[Unit]](Right(())) { (acc, e) =>
      acc.flatMap { _ =>
        val retried = Retry.run(cfg.retry, ports.sleeper)(repo.putAll(e._2))
        retried match
          case Left(err) => Left((LiveLoadStatus.StoreError, s"${e._1}: put failed (${err.code})"))
          case Right(r)  => f.itemReports = f.itemReports.updated(e._1, r); Right(())
      }
    }
    f.itemsMillis = ports.clock.nowMillis() - t0
    res.flatMap { _ =>
      val conflicts = f.itemReports.values.map(_.conflicts).sum
      if conflicts == 0L then Right(())
      else
        Left(
          (
            LiveLoadStatus.ItemsConflict,
            s"$conflicts item(s) exist with different content; none overwritten",
          )
        )
    }

  // ---- provenance ----------------------------------------------------------------------------

  private def render(
      p: LiveLoadParams,
      f: Facts,
      status: LiveLoadStatus,
      detail: String,
      totalMillis: Long,
  ): String =
    def n(v: Long) = ujson.Num(v.toDouble)
    val o          = ujson.Obj()
    o("schema") = ProvenanceSchema
    o("loader") = Loader
    o("codeVersion") = p.codeVersion
    o("runId") = p.runId
    o("startedAtEpochMillis") = n(f.startedAt)
    o("status") = status.label
    o("exitCode") = n(status.exitCode.toLong)
    o("detail") = detail
    o("parameters") = ujson.Obj(
      "dryRun"                -> p.dryRun,
      "itemsSource"           -> p.itemsSource,
      "maxItems"              -> n(p.maxItems),
      "neptuneFormat"         -> NeptuneBulkLoad.RequiredFormat,
      "neptuneMode"           -> "AUTO",
      "neptuneSource"         -> p.neptuneSource,
      "neptuneTimeoutSeconds" -> n(p.neptuneTimeoutSeconds),
      "pollIntervalSeconds"   -> n(p.pollIntervalSeconds),
    )
    o("inputs") = ujson.Obj(
      "loadRunId"            -> f.loadRunId,
      "localLoadCodeVersion" -> f.manifestCodeVersion,
      "sparkRunId"           -> f.sparkRunId,
      "files"                -> ujson.Arr.from(
        f.files
          .sortBy(x => (x.role, x.uri))
          .map(x =>
            ujson.Obj("bytes" -> n(x.bytes), "role" -> x.role, "sha256" -> x.sha256, "uri" -> x.uri)
          )
      ),
    )
    o("neptune") = ujson.Obj(
      "loadId"           -> f.neptuneLoadId.map(ujson.Str(_)).getOrElse(ujson.Null),
      "processedRecords" -> f.neptuneRecords.map(n).getOrElse(ujson.Null),
      "rowsPlanned"      -> n(f.neptuneRows),
      "status"           -> f.neptuneStatus,
    )
    o("items") = ujson.Obj(
      "planned" -> ujson.Obj.from(f.plannedItems.toList.sortBy(_._1).map((t, c) => t -> n(c))),
      "total"   -> n(f.plannedItems.values.sum),
      "written" -> ujson.Obj.from(
        f.itemReports.toList
          .sortBy(_._1)
          .map((t, r) =>
            t -> ujson.Obj(
              "conflicts" -> n(r.conflicts),
              "unchanged" -> n(r.unchanged),
              "written"   -> n(r.written),
            )
          )
      ),
    )
    o("durationsMillis") = ujson.Obj(
      "items"   -> n(f.itemsMillis),
      "neptune" -> n(f.neptuneMillis),
      "total"   -> n(totalMillis),
      "verify"  -> n(f.verifyMillis),
    )
    ujson.write(o, indent = 2) + "\n"
