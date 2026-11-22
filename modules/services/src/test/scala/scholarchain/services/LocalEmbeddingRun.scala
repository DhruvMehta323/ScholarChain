package scholarchain.services

import java.nio.file.{Path, Paths}

import scala.util.control.NonFatal

import scholarchain.core.*
import scholarchain.rag.RetrievalSmoke
import scholarchain.storage.*

/** Converts Phase 06 table rows (storage's `TableData`) into core records. Pure (no Spark), so it
  * is testable on an in-memory table. A row that does not convert or does not validate is counted
  * under a fixed reason code; row content is never reported.
  */
object ParquetInput:
  final case class Converted[A](records: List[A], rows: Int, rejects: Map[String, Int])

  private def reject(reason: String): Either[String, Nothing] = Left(reason)

  private def str(t: TableData, r: Vector[Cell], c: String): Either[String, String] =
    t.cell(r, c) match
      case Left(_)          => Left("missing-column")
      case Right(Cell.S(v)) => Right(v)
      case Right(_)         => Left("null-or-wrong-type")

  private def optStr(t: TableData, r: Vector[Cell], c: String): Either[String, Option[String]] =
    t.cell(r, c) match
      case Left(_)          => Left("missing-column")
      case Right(Cell.Null) => Right(None)
      case Right(Cell.S(v)) => Right(Some(v))
      case Right(_)         => Left("wrong-type")

  private def int(t: TableData, r: Vector[Cell], c: String): Either[String, Int] =
    t.cell(r, c) match
      case Left(_)                                                    => Left("missing-column")
      case Right(Cell.I(v))                                           => Right(v)
      case Right(Cell.L(v)) if v >= Int.MinValue && v <= Int.MaxValue => Right(v.toInt)
      case Right(_)                                                   => Left("null-or-wrong-type")

  private def strList(t: TableData, r: Vector[Cell], c: String): Either[String, List[String]] =
    t.cell(r, c) match
      case Left(_)             => Left("missing-column")
      case Right(Cell.Null)    => Right(Nil)
      case Right(Cell.Arr(vs)) =>
        vs.foldRight[Either[String, List[String]]](Right(Nil)) {
          case (Cell.S(s), acc) => acc.map(s :: _)
          case (_, _)           => Left("wrong-type")
        }
      case Right(_) => Left("wrong-type")

  private def convert[A](t: TableData, build: Vector[Cell] => Either[String, A])(using
      c: Contract[A]
  ): Converted[A] =
    val results = t.rows.toList.map { row =>
      build(row).flatMap(a => c.validate(a).left.map(e => s"invalid-${e.code}"))
    }
    Converted(
      results.flatMap(_.toOption),
      t.rows.size,
      results.flatMap(_.left.toOption).groupBy(identity).view.mapValues(_.size).toMap,
    )

  def summaries(t: TableData, prov: Provenance): Converted[SemanticSummary] =
    convert(
      t,
      r =>
        for
          entity <- str(t, r, "entityId")
          tpe    <- str(t, r, "documentType")
          hash   <- str(t, r, "textHash")
          text   <- str(t, r, "text")
          ws     <- int(t, r, "windowStart")
          we     <- int(t, r, "windowEnd")
          run    <- str(t, r, "runId")
        yield SemanticSummary(1, entity, tpe, hash, text, GraphWindow(ws, we), run, prov),
    )

  def works(t: TableData, prov: Provenance): Converted[WorkRecord] =
    convert(
      t,
      r =>
        for
          id    <- str(t, r, "workId")
          title <- str(t, r, "title")
          year  <- int(t, r, "year")
          doi   <- optStr(t, r, "doi")
          venue <- optStr(t, r, "venueId")
          tops  <- strList(t, r, "topicIds")
        yield WorkRecord(1, id, title, year, doi, venue, tops, prov),
    )

  def venues(t: TableData, prov: Provenance): Converted[Venue] =
    convert(
      t,
      r =>
        for
          id   <- str(t, r, "venueId")
          name <- str(t, r, "name")
        yield Venue(1, id, name, None, prov),
    )

  def topics(t: TableData, prov: Provenance): Converted[Topic] =
    convert(
      t,
      r =>
        for
          id    <- str(t, r, "topicId")
          label <- str(t, r, "label")
        yield Topic(1, id, label, prov),
    )

  // ---- reading a Phase 06 run directory ------------------------------------------------------------

  private def tableRefs(runRoot: Path): Either[String, (String, String, Map[String, TableRef])] =
    val dir = runRoot.resolve("load-manifests")
    for
      vecJson <- Manifests.readJson(dir.resolve("s3-vectors.json"))
      s3Json  <- Manifests.readJson(dir.resolve("s3.json"))
      runId   <- vecJson.obj.get("runId").flatMap(_.strOpt).toRight("s3-vectors.json: no runId")
      code    <- vecJson.obj
        .get("codeVersion")
        .flatMap(_.strOpt)
        .toRight("s3-vectors.json: no codeVersion")
      docs <- vecJson.obj
        .get("documents")
        .flatMap(_.arrOpt)
        .toRight("s3-vectors.json: no documents")
      tabs <- s3Json.obj.get("tables").flatMap(_.arrOpt).toRight("s3.json: no tables")
    yield
      def ref(v: ujson.Value): Option[TableRef] =
        for
          o     <- v.objOpt
          table <- o.value.get("table").flatMap(_.strOpt)
          path  <- o.value.get("path").flatMap(_.strOpt)
          fmt   <- o.value.get("format").flatMap(_.strOpt)
          parts <- o.value.get("partitionColumns").flatMap(_.arrOpt).map(_.toList.flatMap(_.strOpt))
          rows  <- o.value.get("rowCount").flatMap(_.numOpt).map(_.toLong)
        yield TableRef(table, path, fmt, parts, rows)
      (runId, code, (docs.toList ++ tabs.toList).flatMap(ref).map(r => r.table -> r).toMap)

  /** Reads sparkS1r2-style Phase 06 output through storage's `ParquetTables` (Spark SQL, Test
    * scope), converts the five tables the renderer needs, and returns the pipeline input.
    */
  def read(runRoot: Path): Either[List[String], PipelineInput] =
    tableRefs(runRoot).left.map(List(_)).flatMap { (runId, phase06Code, refs) =>
      val needed = List("semantic-summaries", "works", "venues", "topics")
      val absent = needed.filterNot(refs.contains)
      if absent.nonEmpty then Left(absent.map(t => s"manifest has no table $t"))
      else
        val spark = ParquetTables.newSession()
        spark.sparkContext.setLogLevel("ERROR")
        try
          def provFor(ref: TableRef): Either[String, Provenance] =
            LocalLoad
              .tableChecksum(ParquetTables.tableDir(runRoot, ref))
              .map((sum, _) =>
                Provenance(
                  1,
                  SourceType.ScholarChain,
                  List(InputRef(s"scholarchain-graph/${ref.table}", runId, Some(sum))),
                  phase06Code,
                  Map("table" -> ref.table),
                  Some(runId),
                  None,
                )
              )
          def table(name: String): Either[String, (TableData, Provenance)] =
            val ref = refs(name)
            for
              p <- provFor(ref)
              t <- ParquetTables.read(spark, runRoot, ref)
            yield (t, p)
          for
            s  <- table("semantic-summaries").left.map(List(_))
            w  <- table("works").left.map(List(_))
            v  <- table("venues").left.map(List(_))
            tp <- table("topics").left.map(List(_))
          yield
            val cs = summaries(s._1, s._2)
            val cw = works(w._1, w._2)
            val cv = venues(v._1, v._2)
            val ct = topics(tp._1, tp._2)
            PipelineInput(
              runId,
              cs.records,
              cw.records,
              cv.records,
              ct.records,
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
              ).filter(_._2.nonEmpty),
            )
        finally spark.stop()
    }

/** Command line entry of the local embedding run (test-only; never in src/main). Run from the
  * repository root with ABSOLUTE paths:
  * {{{
  * sbt "services/Test/runMain scholarchain.services.LocalEmbeddingRun --run-root $PWD/modules/spark/target/runs/sparkS1r2 \
  *   --out-dir $PWD/modules/services/target/embedding-runs/L1 --code-version <v>"
  * }}}
  * Optional: `--dimension <n>` (default 1024), `--smoke-per-type <n>` (default 5),
  * `--smoke-min-distinctive <n>` (default RetrievalSmoke.DefaultMinDistinctive, currently 3). Exit
  * status 0 only when every invariant holds; otherwise `FAILED:` lines on stderr and exit 1 (2 for
  * a usage error). The output directory must not exist and is the only place written.
  */
object LocalEmbeddingRun:
  final case class Args(
      runRoot: Path,
      outDir: Path,
      codeVersion: String,
      dimension: Int,
      smokePerType: Int,
      smokeMinDistinctive: Int,
  )

  def parse(args: List[String]): Either[String, Args] =
    def get(name: String): Option[String] =
      args.sliding(2).collectFirst { case List(`name`, v) => v }
    def int(name: String, default: Int): Either[String, Int] =
      get(name).fold[Either[String, Int]](Right(default))(
        _.toIntOption.filter(_ >= 1).toRight(s"bad $name")
      )
    for
      rr <- get("--run-root").toRight("missing --run-root")
      od <- get("--out-dir").toRight("missing --out-dir")
      cv <- get("--code-version").toRight("missing --code-version")
      _  <-
        if Paths.get(rr).isAbsolute && Paths.get(od).isAbsolute then Right(())
        else Left("paths must be absolute")
      _ <-
        if cv.nonEmpty && !cv.exists(_.isWhitespace) then Right(()) else Left("bad --code-version")
      dim <- int("--dimension", LocalEmbeddingPipeline.DefaultDimension)
      per <- int("--smoke-per-type", LocalEmbeddingPipeline.DefaultSmokePerType)
      min <- int("--smoke-min-distinctive", RetrievalSmoke.DefaultMinDistinctive)
    yield Args(Paths.get(rr), Paths.get(od), cv, dim, per, min)

  def main(argv: Array[String]): Unit =
    parse(argv.toList) match
      case Left(e) =>
        System.err.println(s"usage error: $e")
        sys.exit(2)
      case Right(a) =>
        val result =
          try
            ParquetInput.read(a.runRoot).flatMap { in =>
              LocalEmbeddingPipeline.run(
                in,
                PipelineParams(
                  a.codeVersion,
                  a.dimension,
                  smokePerType = a.smokePerType,
                  smokeMinDistinctive = a.smokeMinDistinctive,
                ),
                a.outDir,
              )
            }
          catch case NonFatal(e) => Left(List(s"run failed (${e.getClass.getSimpleName})"))
        result match
          case Left(errors) =>
            errors.foreach(e => System.err.println(s"FAILED: $e"))
            sys.exit(1)
          case Right(r) =>
            println(
              s"documents=${r.documentCount} pass1.vectorsWritten=${r.pass1.vectorsWritten} " +
                s"pass2.skippedUnchanged=${r.pass2.skippedUnchanged} deadLettered=${r.pass1.documentsDeadLettered + r.pass2.documentsDeadLettered} " +
                s"smoke=${r.smoke.passedCount}/${r.smoke.outcomes.size}"
            )
            r.invariantFailures.foreach(f => System.err.println(s"FAILED: $f"))
            sys.exit(if r.invariantFailures.isEmpty then 0 else 1)
