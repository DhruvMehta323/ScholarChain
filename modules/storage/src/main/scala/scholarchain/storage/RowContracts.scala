package scholarchain.storage

import scholarchain.core.*

/** Row-level checks that tie Phase 06 tables to the core contracts they are frozen to. */
object RowContracts:
  private def sc[A](r: Either[ValidationError, A]): Either[String, A] = r.left.map(_.message)

  /** Parses every DISTINCT `provenance` string of a table into a core `Provenance`, requiring
    * `runId` to equal the run of the manifest. Returns the parsed provenance by its SHA-256 digest.
    */
  def provenances(
      table: TableData,
      expectedRunId: String,
  ): Either[String, Map[String, Provenance]] =
    if !table.has("provenance") then Right(Map.empty)
    else
      table.col("provenance").flatMap { i =>
        val texts = table.rows.map(_(i)).map(_.str).distinct
        texts.foldLeft[Either[String, Map[String, Provenance]]](Right(Map.empty)) { (acc, t) =>
          for
            m    <- acc
            text <- t.toRight(s"table '${table.name}': null provenance")
            p    <- Wire
              .read[Provenance](text)
              .left
              .map(e => s"table '${table.name}': provenance does not parse (${e.code})")
            _ <-
              if p.runId.contains(expectedRunId) then Right(())
              else Left(s"table '${table.name}': provenance runId differs from the manifest run")
          yield m.updated(Cells.sha256(text), p)
        }
      }

  private def i(t: TableData, r: Vector[Cell], c: String): Either[String, Int] =
    t.cell(r, c).flatMap {
      case Cell.I(v) => Right(v)
      case _         => Left(s"table '${t.name}' column '$c' is not an int")
    }

  private def b(t: TableData, r: Vector[Cell], c: String): Either[String, Boolean] =
    t.cell(r, c).flatMap {
      case Cell.B(v) => Right(v)
      case _         => Left(s"table '${t.name}' column '$c' is not a boolean")
    }

  private def strs(t: TableData, r: Vector[Cell], c: String): Either[String, List[String]] =
    t.cell(r, c).flatMap {
      case Cell.Arr(vs) =>
        vs.foldRight[Either[String, List[String]]](Right(Nil))((x, acc) =>
          for rest <- acc; s <- x.str.toRight(s"table '${t.name}' column '$c' holds a non-string")
          yield s :: rest
        )
      case _ => Left(s"table '${t.name}' column '$c' is not an array")
    }

  /** Rebuilds each `coauthor-edges` row as a core `CoauthorEdgeV2` and validates it. Returns the
    * number of rows checked.
    */
  def coauthorEdges(table: TableData, runId: String): Either[String, Long] =
    for
      provs <- provenances(table, runId)
      n     <- table.rows.foldLeft[Either[String, Long]](Right(0L)) { (acc, r) =>
        for
          count <- acc
          text  <- table.string(r, "provenance")
          prov  <- provs.get(Cells.sha256(text)).toRight("provenance missing")
          edge  <-
            (for
              v   <- i(table, r, "schemaVersion")
              id  <- table.string(r, "edgeId")
              a   <- table.string(r, "researcherA")
              bb  <- table.string(r, "researcherB")
              ws  <- i(table, r, "windowStart")
              we  <- i(table, r, "windowEnd")
              pv  <- table.string(r, "policyVersion")
              jp  <- i(table, r, "jointPublicationCount")
              fy  <- i(table, r, "firstCollaborationYear")
              ly  <- i(table, r, "lastCollaborationYear")
              rep <- strs(table, r, "representativeWorkIds")
              dg  <- table.string(r, "supportingWorksDigest")
              tr  <- b(table, r, "supportingWorksTruncated")
              et  <- table.string(r, "evidenceTable")
              ek  <- table.string(r, "evidenceKey")
              ep  <- table.string(r, "evidencePartition")
            yield CoauthorEdgeV2(
              v,
              id,
              a,
              bb,
              ws,
              we,
              pv,
              jp,
              fy,
              ly,
              rep,
              dg,
              tr,
              et,
              ek,
              ep,
              prov,
            ))
          _ <- sc(summon[Contract[CoauthorEdgeV2]].validate(edge))
        yield count + 1
      }
    yield n

  private def l(t: TableData, r: Vector[Cell], c: String): Either[String, Long] =
    t.cell(r, c).flatMap {
      case Cell.L(v) => Right(v)
      case _         => Left(s"table '${t.name}' column '$c' is not a long")
    }

  private def d(t: TableData, r: Vector[Cell], c: String): Either[String, Double] =
    t.cell(r, c).flatMap {
      case Cell.D(v) => Right(v)
      case _         => Left(s"table '${t.name}' column '$c' is not a double")
    }

  private def optInt(t: TableData, r: Vector[Cell], c: String): Either[String, Option[Int]] =
    t.cell(r, c).flatMap {
      case Cell.Null => Right(None)
      case Cell.I(v) => Right(Some(v))
      case _         => Left(s"table '${t.name}' column '$c' is not an int")
    }

  private def optDouble(t: TableData, r: Vector[Cell], c: String): Either[String, Option[Double]] =
    t.cell(r, c).flatMap {
      case Cell.Null => Right(None)
      case Cell.D(v) => Right(Some(v))
      case _         => Left(s"table '${t.name}' column '$c' is not a double")
    }

  /** Rebuilds each `researcher-metrics` row as a core `ResearcherMetricsV2` and validates it. */
  def researcherMetrics(table: TableData, runId: String): Either[String, Long] =
    for
      provs <- provenances(table, runId)
      n     <- table.rows.foldLeft[Either[String, Long]](Right(0L)) { (acc, r) =>
        for
          count <- acc
          text  <- table.string(r, "provenance")
          prov  <- provs.get(Cells.sha256(text)).toRight("provenance missing")
          m     <-
            (for
              v    <- i(table, r, "schemaVersion")
              id   <- table.string(r, "researcherId")
              ws   <- i(table, r, "windowStart")
              we   <- i(table, r, "windowEnd")
              pv   <- table.string(r, "policyVersion")
              pc   <- l(table, r, "publicationCount")
              cc   <- l(table, r, "coauthorCount")
              ts   <- l(table, r, "teamSizeTotal")
              avg  <- d(table, r, "avgTeamSize")
              mx   <- i(table, r, "maxTeamSize")
              wd   <- l(table, r, "weightedDegree")
              mwc  <- i(table, r, "maxTeamWorkCount")
              mwi  <- strs(table, r, "maxTeamWorkIds")
              com  <- table.string(r, "communityId")
              cmp  <- table.string(r, "componentId")
              core <- optInt(table, r, "coreness")
              ccd  <- l(table, r, "crossCommunityDegree")
              pcf  <- d(table, r, "participationCoefficient")
              ct   <- i(table, r, "communitiesTouched")
              tsw  <- optDouble(table, r, "tieStrengthWeightedDegree")
            yield ResearcherMetricsV2(
              v,
              id,
              ws,
              we,
              pv,
              pc,
              cc,
              ts,
              avg,
              mx,
              wd,
              mwc,
              mwi,
              com,
              cmp,
              core,
              ccd,
              pcf,
              ct,
              tsw,
              prov,
            ))
          _ <- sc(summon[Contract[ResearcherMetricsV2]].validate(m))
        yield count + 1
      }
    yield n

  /** Checks that the evidence table backs every edge pointer: per edge, the rows of
    * `coauthor-edge-works` number `jointPublicationCount`, their work IDs hash to
    * `supportingWorksDigest`, and `representativeWorkIds` are among them. Also counts evidence work
    * IDs that are not Work vertices (`works` table).
    */
  final case class TraceReport(
      edgesChecked: Long,
      edgesWithoutEvidence: Long,
      countMismatches: Long,
      digestMismatches: Long,
      representativeNotSubset: Long,
      evidenceRows: Long,
      evidenceWorksNotInWorks: Long,
  ):
    def ok: Boolean =
      edgesWithoutEvidence == 0 && countMismatches == 0 && digestMismatches == 0 &&
        representativeNotSubset == 0 && evidenceWorksNotInWorks == 0

  def traceEvidence(
      edges: TableData,
      evidence: TableData,
      works: TableData,
  ): Either[String, TraceReport] =
    for
      workIds <- works.col("workId").map(ix => works.rows.map(_(ix).str.getOrElse("")).toSet)
      ei      <- evidence.col("edgeId")
      wi      <- evidence.col("workId")
      byEdge = evidence.rows.groupMap(r => r(ei).str.getOrElse(""))(r => r(wi).str.getOrElse(""))
      eid <- edges.col("edgeId")
      cnt <- edges.col("jointPublicationCount")
      dig <- edges.col("supportingWorksDigest")
      rep <- edges.col("representativeWorkIds")
    yield
      var none, bad, dg, notSub = 0L
      edges.rows.foreach { r =>
        val id = r(eid).str.getOrElse("")
        byEdge.get(id) match
          case None     => none += 1
          case Some(ws) =>
            val n = r(cnt) match
              case Cell.I(v) => v.toLong
              case _         => -1L
            if ws.size.toLong != n then bad += 1
            if r(dig).str.getOrElse("") != CoauthorEdgeV2.expectedDigest(ws.toList) then dg += 1
            val repIds = r(rep) match
              case Cell.Arr(vs) => vs.flatMap(_.str)
              case _            => Nil
            if !repIds.forall(ws.toSet.contains) then notSub += 1
      }
      val outside = evidence.rows.count(r => !workIds.contains(r(wi).str.getOrElse("")))
      TraceReport(
        edges.rows.size.toLong,
        none,
        bad,
        dg,
        notSub,
        evidence.rows.size.toLong,
        outside.toLong,
      )
