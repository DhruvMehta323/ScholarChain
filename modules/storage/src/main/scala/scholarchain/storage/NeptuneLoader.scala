package scholarchain.storage

import scala.util.control.NonFatal

/** Result of converting the Phase 06 graph tables of a Neptune manifest into openCypher CSV. */
final case class NeptuneBuild(
    files: List[CsvFile],
    report: InvariantReport,
    coauthorEdgesValidated: Long,
    provenanceDigests: Map[String, scholarchain.core.Provenance],
    tables: Map[String, TableData],
)

/** Converts the vertex and edge tables listed in a Neptune load manifest into CSV files, driven by
  * the manifest only: the labels, ID columns, property columns and key columns all come from it.
  * Fails when a table's row count differs from the manifest, or when an invariant is violated.
  *
  * Not produced by Phase 06 and therefore absent here (nothing is fabricated): `AFFILIATED_WITH`,
  * `DERIVED_FROM`, `SUPPORTED_BY` edges. `Institution` vertices are loaded, but no edge points to
  * them.
  */
object NeptuneLoader:
  type TableSource = TableRef => Either[String, TableData]

  /** `lineage` (M6): when given, every vertex and edge row carries `loadRunId`, `sparkRunId` and
    * `provenanceDigest` columns (see `Lineage`). `None` writes the columns of the manifest only.
    */
  def build(
      manifest: NeptuneManifest,
      source: TableSource,
      lineage: Option[Lineage] = None,
  ): Either[List[String], NeptuneBuild] =
    try
      val vTables    = manifest.vertices.map(v => (v, source(v.ref)))
      val eTables    = manifest.edges.map(e => (e, source(e.ref)))
      val readErrors = (vTables.map(_._2) ++ eTables.map(_._2)).collect { case Left(e) => e }
      if readErrors.nonEmpty then Left(readErrors)
      else
        val vt        = vTables.map((v, t) => (v, t.toOption.get))
        val et        = eTables.map((e, t) => (e, t.toOption.get))
        val rowErrors = (vt.map((v, t) => (v.ref, t)) ++ et.map((e, t) => (e.ref, t))).collect {
          case (ref, t) if t.rows.size.toLong != ref.rowCount =>
            s"${ref.table}: ${t.rows.size} rows read, manifest says ${ref.rowCount}"
        }
        if rowErrors.nonEmpty then Left(rowErrors)
        else
          val vFiles = vt.map((v, t) => NeptuneCsv.vertexFile(v, t, lineage).map(f => (v, t, f)))
          val eFiles = et.map((e, t) => NeptuneCsv.edgeFile(e, t, lineage).map(f => (e, t, f)))
          val fileErrors = (vFiles ++ eFiles).collect { case Left(e) => e }
          if fileErrors.nonEmpty then Left(fileErrors)
          else
            val vs       = vFiles.map(_.toOption.get)
            val es       = eFiles.map(_.toOption.get)
            val dupPaths = (vs.map(_._3.relativePath) ++ es.map(_._3.relativePath))
              .groupBy(identity)
              .collect { case (p, l) if l.size > 1 => p }
              .toList
            if dupPaths.nonEmpty then
              Left(List(s"manifest lists a label twice: ${dupPaths.mkString(", ")}"))
            else
              val byName = (vt.map((_, t) => t.name -> t) ++ et.map((_, t) => t.name -> t)).toMap
              for
                report <- NeptuneInvariants
                  .check(
                    vs.map((s, t, f) => NeptuneInvariants.VertexInput(s, t, f)),
                    es.map((s, t, f) => NeptuneInvariants.EdgeInput(s, t, f)),
                  )
                  .left
                  .map(List(_))
                edgeCheck <- coauthor(manifest, byName).left.map(List(_))
                _         <-
                  if report.ok then Right(())
                  else Left(List(s"invariants violated: ${report.examples.mkString("; ")}"))
              yield NeptuneBuild(
                vs.map(_._3) ++ es.map(_._3),
                report,
                edgeCheck._1,
                edgeCheck._2,
                byName,
              )
    catch case NonFatal(e) => Left(List(s"neptune build failed (${e.getClass.getSimpleName})"))

  /** Validates every `provenance`-carrying edge table: provenance parses into core `Provenance`
    * with the manifest runId, and COAUTHORED_WITH rows are valid `CoauthorEdgeV2` records.
    */
  private def coauthor(
      manifest: NeptuneManifest,
      tables: Map[String, TableData],
  ): Either[String, (Long, Map[String, scholarchain.core.Provenance])] =
    val provTables = manifest.edges.map(_.ref.table).flatMap(tables.get).filter(_.has("provenance"))
    provTables.foldLeft[Either[String, (Long, Map[String, scholarchain.core.Provenance])]](
      Right((0L, Map.empty))
    ) { (acc, t) =>
      for
        a <- acc
        p <- RowContracts.provenances(t, manifest.header.runId)
        n <-
          if t.name == "coauthor-edges" then RowContracts.coauthorEdges(t, manifest.header.runId)
          else Right(0L)
      yield (a._1 + n, a._2 ++ p)
    }
