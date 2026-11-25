package scholarchain.storage

// FROZEN REFERENCE (test-engineer, Phase 14 Part A): the exact LocalLoad source at git HEAD (a375c00),
// before the additive embedding-inputs output, renamed to LocalLoadBaseline. It is the "previous
// behavior" the byte-identity test compares the extended LocalLoad against. Never edit it to match
// new behavior: that would make the backward-compatibility check vacuous.

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import scholarchain.core.*

/** Local loader: reads a Phase 06 run directory (Parquet + load manifests), writes under
  * `--out-dir`
  *   - `neptune/vertices/<Label>.csv`, `neptune/edges/<TYPE>.csv` (Neptune openCypher bulk-load
  *     CSV),
  *   - `dynamodb/items/<table>.jsonl` (DynamoDB item files),
  *   - `load-manifest.json` (inputs with checksums, code version, parameters, invariants, outputs,
  *     the full provenance behind every row-level `provenanceDigest`, and the M8 reload result),
  *   - `object-store/` (a `FileObjectStore` root: the load manifest published through the
  *     `ObjectStore` port with `ObjectRunManifest`, under the run-versioned key),
  *   - `checksums.sha256` (SHA-256 of every other output file).
  * Every Neptune row carries `loadRunId`, `sparkRunId` and `provenanceDigest` (see `Lineage`). The
  * analytics items are also loaded TWICE into an `InMemoryKeyValueStore` through
  * `AnalyticsRepository`: the second pass must report only `Unchanged`. No AWS call is made and no
  * credential is read. The output directory must not exist (a load never overwrites). Output is
  * deterministic: sorted rows and keys, no timestamps, no host paths.
  *
  * Spark SQL is `Provided`, so run from the repository root through the test classpath, with
  * ABSOLUTE paths (the forked JVM's working directory is `modules/storage`):
  * {{{
  * sbt "storage/Test/runMain scholarchain.storage.LocalLoad --run-root $PWD/modules/spark/target/runs/sparkS1r2 \
  *   --out-dir $PWD/modules/storage/target/loads/L1 --code-version <git-short>+phase07"
  * }}}
  * Exit status 0 only when every invariant holds.
  */
object LocalLoadBaseline:
  /** Bucket name inside the local `FileObjectStore` that holds the published load manifest. */
  val LocalBucket: String = "local-load"

  final case class Args(runRoot: Path, outDir: Path, codeVersion: String)

  def parse(args: List[String]): Either[String, Args] =
    def get(name: String) =
      args.sliding(2).collectFirst { case List(`name`, v) => v }.toRight(s"missing $name")
    for
      rr <- get("--run-root")
      od <- get("--out-dir")
      cv <- get("--code-version")
      _  <-
        if Paths.get(rr).isAbsolute && Paths.get(od).isAbsolute then Right(())
        else Left("paths must be absolute")
      _ <-
        if cv.nonEmpty && !cv.exists(_.isWhitespace) then Right(()) else Left("bad --code-version")
    yield Args(Paths.get(rr), Paths.get(od), cv)

  def main(argv: Array[String]): Unit =
    parse(argv.toList) match
      case Left(e) =>
        System.err.println(s"usage error: $e")
        sys.exit(2)
      case Right(a) =>
        run(a) match
          case Right(summary) =>
            println(summary)
            sys.exit(0)
          case Left(errors) =>
            errors.foreach(e => System.err.println(s"FAILED: $e"))
            sys.exit(1)

  private def sha(p: Path): String = Cells.sha256(Files.readAllBytes(p))

  /** SHA-256 of the part files of a table: digest over sorted (relative path, file SHA-256). */
  def tableChecksum(dir: Path): Either[String, (String, Int)] =
    try
      val s = Files.walk(dir)
      try
        val files = s.iterator.asScala
          .filter(Files.isRegularFile(_))
          .filter(p =>
            p.getFileName.toString.startsWith("part-") && p.getFileName.toString.endsWith(
              ".parquet"
            )
          )
          .map(p => dir.relativize(p).toString.replace('\\', '/') -> sha(p))
          .toList
          .sortBy(_._1)
        if files.isEmpty then Left(s"no part files in ${dir.getFileName}")
        else
          Right(
            (
              Hashing
                .digest("scholarchain.storage.table-files.v1", files.flatMap((a, b) => List(a, b))),
              files.size,
            )
          )
      finally s.close()
    catch case NonFatal(_) => Left(s"cannot read part files of ${dir.getFileName}")

  def run(a: Args): Either[List[String], String] =
    if Files.exists(a.outDir) then
      Left(List("output directory already exists; a load never overwrites, choose a new --out-dir"))
    else
      val manifestDir = a.runRoot.resolve("load-manifests")
      val result      = for
        nep <- Manifests.neptune(manifestDir.resolve("neptune.json")).left.map(List(_))
        ddb <- Manifests.dynamo(manifestDir.resolve("dynamodb.json")).left.map(List(_))
        _   <-
          if nep.header.runId == ddb.header.runId && nep.header.codeVersion == ddb.header.codeVersion
          then Right(())
          else Left(List("neptune and dynamodb manifests describe different runs"))
        res <- execute(a, nep, ddb)
      yield res
      result

  private def execute(
      a: Args,
      nep: NeptuneManifest,
      ddb: DynamoManifest,
  ): Either[List[String], String] =
    val spark = ParquetTables.newSession()
    spark.sparkContext.setLogLevel("ERROR")
    try
      val cache = scala.collection.mutable.HashMap.empty[String, Either[String, TableData]]
      val source: NeptuneLoader.TableSource =
        ref => cache.getOrElseUpdate(ref.path, ParquetTables.read(spark, a.runRoot, ref))
      for
        prep  <- prepare(a, nep, ddb)
        build <- NeptuneLoader.build(
          nep,
          source,
          Some(
            Lineage(
              prep.loadRunId,
              nep.header.runId,
              ref =>
                prep.tableInputs
                  .find(_._1.path == ref.path)
                  .map(_._4)
                  .toRight(s"${ref.table}: no provenance file digest"),
            )
          ),
        )
        edgeWorks <- ddb.tables
          .find(_.ref.table == "coauthor-edge-works")
          .toRight(List("dynamodb manifest has no coauthor-edge-works"))
        ewData <- source(edgeWorks.ref).left.map(List(_))
        trace  <- RowContracts
          .traceEvidence(build.tables("coauthor-edges"), ewData, build.tables("works"))
          .left
          .map(List(_))
        _     <- if trace.ok then Right(()) else Left(List(s"evidence trace failed: $trace"))
        items <- sequence(
          ddb.tables.map(s =>
            source(s.ref).flatMap(t => AnalyticsItems.build(s, t)).left.map(e => e)
          )
        )
        metrics <- ddb.tables
          .find(_.ref.table == "researcher-metrics")
          .toRight(List("dynamodb manifest has no researcher-metrics"))
          .flatMap(s =>
            source(s.ref)
              .flatMap(RowContracts.researcherMetrics(_, ddb.header.runId))
              .left
              .map(List(_))
          )
        reload <- reloadItems(items)
        out    <- write(
          a,
          nep,
          ddb,
          prep,
          reload,
          build,
          trace,
          items,
          metrics,
          cache.values.flatMap(_.toOption).toList,
        )
      yield out
    catch case NonFatal(e) => Left(List(s"load failed (${e.getClass.getSimpleName})"))
    finally spark.stop()

  private def sequence[A](xs: List[Either[String, A]]): Either[List[String], List[A]] =
    val errs = xs.collect { case Left(e) => e }
    if errs.nonEmpty then Left(errs) else Right(xs.collect { case Right(v) => v })

  /** What the load is made of, known before any output is built: table inputs (checksums), the
    * parameters and the load run ID (a function of code version, parameters and inputs).
    */
  private final case class Prep(
      tableInputs: List[(TableRef, String, Int, String)],
      params: Map[String, String],
      loadRunId: String,
  )

  private def prepare(
      a: Args,
      nep: NeptuneManifest,
      ddb: DynamoManifest,
  ): Either[List[String], Prep] =
    val refs =
      (nep.vertices.map(_.ref) ++ nep.edges.map(_.ref) ++ ddb.tables.map(_.ref)).distinctBy(_.path)
    val manifestDir = a.runRoot.resolve("load-manifests")
    val inputsE     =
      refs.foldRight[Either[String, List[(TableRef, String, Int, String)]]](Right(Nil)) {
        (ref, acc) =>
          for
            rest <- acc
            c    <- tableChecksum(ParquetTables.tableDir(a.runRoot, ref))
          yield (ref, c._1, c._2, sha(ParquetTables.provenancePath(a.runRoot, ref))) :: rest
      }
    inputsE.left.map(List(_)).flatMap { tableInputs =>
      val inputRefs = tableInputs.map((ref, digest, _, _) =>
        InputRef(s"spark/${ref.table}", nep.header.runId, Some(digest))
      ) ++
        List("neptune.json", "dynamodb.json").map(n =>
          InputRef(s"spark/load-manifests/$n", nep.header.runId, Some(sha(manifestDir.resolve(n))))
        )
      val params = Map(
        "arrayEncoding"    -> "json-string",
        "csvLineEnd"       -> "LF",
        "inputRunDir"      -> a.runRoot.getFileName.toString,
        "neptuneFormat"    -> "opencypher",
        "sparkCodeVersion" -> nep.header.codeVersion,
      )
      Ids
        .runId("storage-load", a.codeVersion, 1, params, inputRefs)
        .left
        .map(e => List(e.message))
        .map(id => Prep(tableInputs, params, id))
    }

  /** M8: loads the analytics items into an in-memory key-value store twice. The first pass writes,
    * the second must be all `Unchanged` (idempotent), and neither may report a conflict.
    */
  private def reloadItems(
      items: List[AnalyticsItems.ItemFile]
  ): Either[List[String], (AnalyticsLoadReport, AnalyticsLoadReport)] =
    val names = items.map(_.table)
    val kv    = new InMemoryKeyValueStore(
      names.map(n => n -> KvTableSchema(AnalyticsRepository.HasSortKey.getOrElse(n, false))).toMap
    )
    val repo = new AnalyticsRepository(kv, names.map(n => n -> n).toMap)
    val all  = items.flatMap(_.items)
    val res  = for
      first  <- repo.putAll(all)
      second <- repo.putAll(all)
    yield (first, second)
    res.left.map(e => List(s"analytics reload failed (${e.code})")).flatMap { (f, sec) =>
      if f.ok && sec.ok && sec.written == 0L && sec.unchanged == all.size.toLong then
        Right((f, sec))
      else Left(List(s"analytics reload is not idempotent: first=$f second=$sec"))
    }

  private def write(
      a: Args,
      nep: NeptuneManifest,
      ddb: DynamoManifest,
      prep: Prep,
      reload: (AnalyticsLoadReport, AnalyticsLoadReport),
      build: NeptuneBuild,
      trace: RowContracts.TraceReport,
      items: List[AnalyticsItems.ItemFile],
      metricsChecked: Long,
      allTables: List[TableData],
  ): Either[List[String], String] =
    val manifestDir = a.runRoot.resolve("load-manifests")
    val tableInputs = prep.tableInputs
    val params      = prep.params
    val loadRunId   = prep.loadRunId
    Right(()).flatMap { _ =>
      Files.createDirectories(a.outDir.getParent)
      Files.createDirectory(a.outDir)
      def put(rel: String, bytes: Array[Byte]): Unit =
        val p = a.outDir.resolve(rel)
        Files.createDirectories(p.getParent)
        Files.write(p, bytes)
      build.files.foreach(f => put(s"neptune/${f.relativePath}", f.bytes))
      items.foreach(f => put(s"dynamodb/${f.path}", f.bytes))

      // Full provenance behind every digest: row-level texts (digest of the row `provenance`
      // string) and, for Neptune tables without that column, the table `_provenance.json`.
      val rowProv    = allTables.flatMap(AnalyticsItems.provenanceTexts).toMap
      val tableLevel =
        (nep.vertices.map(_.ref) ++ nep.edges.map(_.ref))
          .distinctBy(_.path)
          .filterNot(r => build.tables.get(r.table).exists(_.has("provenance")))
          .map { r =>
            val bytes = Files.readAllBytes(ParquetTables.provenancePath(a.runRoot, r))
            r.table -> (Cells.sha256(bytes), new String(bytes, StandardCharsets.UTF_8))
          }
      val allProv = rowProv ++ tableLevel.map(_._2)

      val rep      = build.report
      val manifest = ujson.Obj()
      manifest("absentEdgeTypes") = ujson.Arr("AFFILIATED_WITH", "DERIVED_FROM", "SUPPORTED_BY")
      manifest("codeVersion") = a.codeVersion
      manifest("dynamodb") = ujson.Obj(
        "files" -> ujson.Arr.from(
          items
            .sortBy(_.table)
            .map(f =>
              ujson.Obj(
                "path"   -> f.path,
                "rows"   -> f.rows.toDouble,
                "sha256" -> f.sha256,
                "table"  -> f.table,
              )
            )
        ),
        "researcherMetricsV2Validated" -> metricsChecked.toDouble,
        "reload"                       -> ujson.Obj(
          "conflicts"           -> (reload._1.conflicts + reload._2.conflicts).toDouble,
          "firstPassUnchanged"  -> reload._1.unchanged.toDouble,
          "firstPassWritten"    -> reload._1.written.toDouble,
          "secondPassUnchanged" -> reload._2.unchanged.toDouble,
          "secondPassWritten"   -> reload._2.written.toDouble,
        ),
      )
      manifest("input") = ujson.Obj(
        "manifests" -> ujson.Arr.from(
          List("neptune.json", "dynamodb.json").map(n =>
            ujson.Obj("name" -> s"load-manifests/$n", "sha256" -> sha(manifestDir.resolve(n)))
          )
        ),
        "sparkCodeVersion" -> nep.header.codeVersion,
        "sparkRunId"       -> nep.header.runId,
        "tables"           -> ujson.Arr.from(
          tableInputs
            .sortBy(_._1.table)
            .map((ref, digest, parts, provSha) =>
              ujson.Obj(
                "partFiles"            -> parts.toDouble,
                "partFilesDigest"      -> digest,
                "provenanceFileSha256" -> provSha,
                "rowCount"             -> ref.rowCount.toDouble,
                "table"                -> ref.table,
              )
            )
        ),
      )
      manifest("loadRunId") = loadRunId
      manifest("neptuneLineage") = ujson.Obj(
        "columns"           -> ujson.Arr.from(Lineage.Headers.map(ujson.Str(_))),
        "loadRunId"         -> loadRunId,
        "sparkRunId"        -> nep.header.runId,
        "tableLevelDigests" -> ujson.Obj.from(
          tableLevel.sortBy(_._1).map((t, d) => t -> ujson.Str(d._1))
        ),
      )
      manifest("loader") = "scholarchain.storage.LocalLoad"
      manifest("neptune") = ujson.Obj(
        "coauthorEdgesV2Validated" -> build.coauthorEdgesValidated.toDouble,
        "edgeRows"                 -> ujson.Obj
          .from(rep.edgeRows.toList.sortBy(_._1).map((k, v) => k -> ujson.Num(v.toDouble))),
        "evidenceTrace" -> ujson.Obj(
          "countMismatches"         -> trace.countMismatches.toDouble,
          "digestMismatches"        -> trace.digestMismatches.toDouble,
          "edgesChecked"            -> trace.edgesChecked.toDouble,
          "edgesWithoutEvidence"    -> trace.edgesWithoutEvidence.toDouble,
          "evidenceRows"            -> trace.evidenceRows.toDouble,
          "evidenceWorksNotInWorks" -> trace.evidenceWorksNotInWorks.toDouble,
          "representativeNotSubset" -> trace.representativeNotSubset.toDouble,
        ),
        "files" -> ujson.Arr.from(
          build.files
            .sortBy(_.relativePath)
            .map(f =>
              ujson.Obj(
                "kind"   -> f.kind,
                "label"  -> f.label,
                "path"   -> s"neptune/${f.relativePath}",
                "rows"   -> f.rows.toDouble,
                "sha256" -> f.sha256,
              )
            )
        ),
        "invariants" -> ujson.Obj(
          "danglingEndpoints"        -> rep.danglingEndpoints.toDouble,
          "duplicateEdgeIds"         -> rep.duplicateEdgeIds.toDouble,
          "duplicateVertexIds"       -> rep.duplicateVertexIds.toDouble,
          "edgeEndpointIdMismatches" -> rep.edgeEndpointIdMismatches.toDouble,
          "edgeIdMismatches"         -> rep.edgeIdMismatches.toDouble,
          "endpointLabelMismatches"  -> rep.endpointLabelMismatches.toDouble,
          "headerProblems"           -> rep.headerProblems.toDouble,
          "labelColumnMismatches"    -> rep.labelColumnMismatches.toDouble,
          "vertexIdFormatViolations" -> rep.vertexIdFormatViolations.toDouble,
          "vertexIdMismatches"       -> rep.vertexIdMismatches.toDouble,
        ),
        "vertexRows" -> ujson.Obj
          .from(rep.vertexRows.toList.sortBy(_._1).map((k, v) => k -> ujson.Num(v.toDouble))),
      )
      manifest("parameters") =
        ujson.Obj.from(params.toList.sortBy(_._1).map((k, v) => k -> ujson.Str(v)))
      manifest("provenanceDocuments") =
        ujson.Obj.from(allProv.toList.sortBy(_._1).map((k, v) => k -> ujson.read(v)))
      put(
        "load-manifest.json",
        (ujson.write(manifest, indent = 2) + "\n").getBytes(StandardCharsets.UTF_8),
      )

      val published = ObjectRunManifest.publish(
        new FileObjectStore(a.outDir.resolve("object-store")),
        LocalBucket,
        "loads/",
        "storage-load",
        ObjectRunManifestInput(
          loadRunId,
          "storage-load",
          a.codeVersion,
          params,
          tableInputs.map((ref, digest, _, _) =>
            InputRef(s"spark/${ref.table}", nep.header.runId, Some(digest))
          ),
          List("load-manifest.json" -> Files.readAllBytes(a.outDir.resolve("load-manifest.json"))),
        ),
      )
      val publishedAt = published match
        case Right(loc) => loc.uri
        case Left(e) => throw new IllegalStateException(s"manifest publication failed (${e.code})")

      val listing = a.outDir.toFile.toPath
      val s       = Files.walk(listing)
      val sums    =
        try
          s.iterator.asScala
            .filter(Files.isRegularFile(_))
            .map(p => listing.relativize(p).toString.replace('\\', '/') -> sha(p))
            .toList
            .sortBy(_._1)
        finally s.close()
      put(
        "checksums.sha256",
        sums.map((p, h) => s"$h  $p\n").mkString.getBytes(StandardCharsets.UTF_8),
      )

      val sb = new StringBuilder
      sb.append(s"loadRunId=$loadRunId\n")
      sb.append(
        s"output files: neptune=${build.files.size} dynamodb=${items.size} (+ load-manifest.json, checksums.sha256)\n"
      )
      rep.vertexRows.toList.sortBy(_._1).foreach((k, v) => sb.append(s"vertex $k $v\n"))
      rep.edgeRows.toList.sortBy(_._1).foreach((k, v) => sb.append(s"edge $k $v\n"))
      items.sortBy(_.table).foreach(f => sb.append(s"items ${f.table} ${f.rows}\n"))
      sb.append(
        s"invariants ok=${rep.ok} dangling=${rep.danglingEndpoints} vertexIdMismatches=${rep.vertexIdMismatches} edgeEndpointIdMismatches=${rep.edgeEndpointIdMismatches} edgeIdMismatches=${rep.edgeIdMismatches} duplicateVertexIds=${rep.duplicateVertexIds} duplicateEdgeIds=${rep.duplicateEdgeIds} endpointLabelMismatches=${rep.endpointLabelMismatches}\n"
      )
      sb.append(
        s"coauthorEdgesV2Validated=${build.coauthorEdgesValidated} researcherMetricsV2Validated=$metricsChecked\n"
      )
      sb.append(s"published manifest $publishedAt\n")
      sb.append(
        s"analyticsReload firstPass written=${reload._1.written} unchanged=${reload._1.unchanged} conflicts=${reload._1.conflicts}; secondPass written=${reload._2.written} unchanged=${reload._2.unchanged} conflicts=${reload._2.conflicts}\n"
      )
      sb.append(s"evidenceTrace ok=${trace.ok} $trace\n")
      Right(sb.toString)
    }
