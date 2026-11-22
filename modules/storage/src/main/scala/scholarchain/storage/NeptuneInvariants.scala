package scholarchain.storage

import java.nio.charset.StandardCharsets

import scholarchain.core.Ids

/** Result of the Neptune load invariants. Every counter is observed from the written CSV bytes,
  * which are parsed again independently of the writer.
  */
final case class InvariantReport(
    vertexRows: Map[String, Long],
    edgeRows: Map[String, Long],
    duplicateVertexIds: Long,
    duplicateEdgeIds: Long,
    danglingEndpoints: Long,
    endpointLabelMismatches: Long,
    vertexIdMismatches: Long,
    edgeIdMismatches: Long,
    edgeEndpointIdMismatches: Long,
    vertexIdFormatViolations: Long,
    labelColumnMismatches: Long,
    headerProblems: Long,
    examples: List[String],
):
  def ok: Boolean =
    duplicateVertexIds == 0 && duplicateEdgeIds == 0 && danglingEndpoints == 0 &&
      endpointLabelMismatches == 0 && vertexIdMismatches == 0 && edgeIdMismatches == 0 &&
      edgeEndpointIdMismatches == 0 && vertexIdFormatViolations == 0 && labelColumnMismatches == 0 &&
      headerProblems == 0

/** Invariants of a Neptune CSV load:
  *   1. every edge endpoint (`:START_ID`, `:END_ID`) is a vertex ID in the written vertex files, and
  *      has the expected vertex label for the edge type (when the type is known);
  *   2. IDs equal the Spark IDs byte for byte: vertex `:ID` = the table's ID column, edge endpoints
  *      = the table's from/to columns, edge `:ID` = the Spark edge ID (or the documented derived
  *      ID) of the same row;
  *   3. vertex IDs and edge IDs are unique across all files (windowed tables must not collide);
  *   4. Researcher and ExternalIdentity IDs have the core researcher-ID shape.
  */
object NeptuneInvariants:
  /** Expected (start label, end label) per edge type. Unknown types are not label-checked. */
  val EndpointLabels: Map[String, (String, String)] = Map(
    "AUTHORED"        -> ("Researcher", "Work"),
    "COAUTHORED_WITH" -> ("Researcher", "Researcher"),
    "RESOLVES_TO"     -> ("Researcher", "ExternalIdentity"),
    "HAS_TOPIC"       -> ("Work", "Topic"),
    "PUBLISHED_IN"    -> ("Work", "Venue"),
    "MEMBER_OF"       -> ("Researcher", "Community"),
    "IN_COMPONENT"    -> ("Researcher", "Component"),
    "COMMUNITY_LINK"  -> ("Community", "Community"),
    "AFFILIATED_WITH" -> ("Researcher", "Institution"),
  )

  private val MaxExamples = 5

  final case class VertexInput(spec: VertexSpec, table: TableData, file: CsvFile)
  final case class EdgeInput(spec: EdgeSpec, table: TableData, file: CsvFile)

  private def records(f: CsvFile): Either[String, Vector[Vector[String]]] =
    CsvFormat.parse(new String(f.bytes, StandardCharsets.UTF_8))

  def check(
      vertices: List[VertexInput],
      edges: List[EdgeInput],
  ): Either[String, InvariantReport] =
    var examples              = List.empty[String]
    def note(s: String): Unit = if examples.size < MaxExamples then examples = examples :+ s

    var dupV, dupE, dangling, labelMis, vIdMis, eIdMis, eEndMis, fmt, lblCol, hdr = 0L
    val idToLabel = scala.collection.mutable.HashMap.empty[String, String]
    val vRows     = scala.collection.mutable.LinkedHashMap.empty[String, Long]
    val eRows     = scala.collection.mutable.LinkedHashMap.empty[String, Long]

    val vErr = vertices.iterator
      .map { v =>
        records(v.file).flatMap { recs =>
          if recs.isEmpty || recs.head.take(2) != Vector(":ID", ":LABEL") then
            hdr += 1
            note(s"${v.spec.label}: bad vertex header")
          val body = if recs.isEmpty then Vector.empty else recs.tail
          val ids  = v.table.col(v.spec.idColumn).map(i => v.table.rows.map(_(i).str.getOrElse("")))
          ids.map { sparkIds =>
            if body.size != sparkIds.size then
              vIdMis += 1
              note(s"${v.spec.label}: ${body.size} CSV rows vs ${sparkIds.size} table rows")
            body.zip(sparkIds).foreach { (r, sid) =>
              val id = r(0)
              if id != sid || id
                  .getBytes(StandardCharsets.UTF_8)
                  .toSeq != sid.getBytes(StandardCharsets.UTF_8).toSeq
              then
                vIdMis += 1
                note(s"${v.spec.label}: vertex ID differs from the Spark ID")
              if r(1) != v.spec.label then lblCol += 1
              if idToLabel.contains(id) then
                dupV += 1
                note(s"duplicate vertex ID in ${v.spec.label}")
              else idToLabel.update(id, v.spec.label)
              if (v.spec.label == "Researcher" || v.spec.label == "ExternalIdentity") &&
                Ids.checkResearcherId(id).isLeft
              then
                fmt += 1
                note(s"${v.spec.label}: ID is not a researcher ID shape")
            }
            vRows.update(v.spec.label, vRows.getOrElse(v.spec.label, 0L) + body.size)
          }
        }
      }
      .collectFirst { case Left(e) => e }
    if vErr.isDefined then Left(vErr.get)
    else
      val seenEdge = scala.collection.mutable.HashSet.empty[String]
      val eErr     = edges.iterator
        .map { e =>
          records(e.file).flatMap { recs =>
            if recs.isEmpty || recs.head.take(4) != Vector(":ID", ":START_ID", ":END_ID", ":TYPE")
            then
              hdr += 1
              note(s"${e.spec.label}: bad edge header")
            val body = if recs.isEmpty then Vector.empty else recs.tail
            for
              fi <- e.table.col(e.spec.fromColumn)
              ti <- e.table.col(e.spec.toColumn)
            yield
              if body.size != e.table.rows.size then
                eIdMis += 1
                note(s"${e.spec.label}: ${body.size} CSV rows vs ${e.table.rows.size} table rows")
              val expected = EndpointLabels.get(e.spec.label)
              body.zip(e.table.rows).foreach { (r, row) =>
                val expId = NeptuneCsv.edgeId(e.spec, e.table, row).getOrElse("")
                if r(0) != expId then
                  eIdMis += 1
                  note(s"${e.spec.label}: edge ID differs from the Spark or derived ID")
                if !seenEdge.add(r(0)) then
                  dupE += 1
                  note(s"duplicate edge ID in ${e.spec.label}")
                if r(1) != row(fi).str.getOrElse("") || r(2) != row(ti).str.getOrElse("") then
                  eEndMis += 1
                  note(s"${e.spec.label}: edge endpoint differs from the Spark ID")
                if r(3) != e.spec.label then lblCol += 1
                List((r(1), expected.map(_._1)), (r(2), expected.map(_._2))).foreach {
                  (endpoint, want) =>
                    idToLabel.get(endpoint) match
                      case None =>
                        dangling += 1
                        note(s"${e.spec.label}: endpoint is not a written vertex")
                      case Some(l) =>
                        if want.exists(_ != l) then
                          labelMis += 1
                          note(s"${e.spec.label}: endpoint has label $l, expected ${want.get}")
                }
              }
              eRows.update(e.spec.label, eRows.getOrElse(e.spec.label, 0L) + body.size)
          }
        }
        .collectFirst { case Left(err) => err }
      eErr.toLeft(
        InvariantReport(
          vRows.toMap,
          eRows.toMap,
          dupV,
          dupE,
          dangling,
          labelMis,
          vIdMis,
          eIdMis,
          eEndMis,
          fmt,
          lblCol,
          hdr,
          examples,
        )
      )
