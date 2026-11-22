package scholarchain.spark

import org.apache.spark.sql.{Column, DataFrame, Row}
import org.apache.spark.sql.functions as F
import org.apache.spark.sql.types.StructType

import scholarchain.core.{
  BridgeMeasure,
  CoauthorEdgeV2,
  InputRef,
  Provenance,
  ResearcherMetricsV2,
  SourceType,
  SparkConfig,
  Wire,
  WirePickle,
}

/** The v2 export of Phase 06 repair 01: `coauthor-edges` and `researcher-metrics` laid out EXACTLY
  * as core `CoauthorEdgeV2` and `ResearcherMetricsV2` (same field names, one row per record; the
  * window columns `windowStart` / `windowEnd` are the record's window fields, and the string column
  * `window` is the Parquet partition column that is not a record field), the `coauthor-edge-works`
  * evidence table, and the executor-side validation that every exported row is a valid core record.
  *
  * Row-level `provenance` is a JSON STRING column (not a struct): the compact wire JSON of a core
  * `Provenance` (source type `scholarchain`: the rows are derived; inputs with checksums, code
  * version, parameters and the run ID of the run that wrote them; no `retrievedAt`). See
  * `provenanceJson` for why, and SPARK-CONTRACTS.md section 4 for how to parse it.
  */
object V2Export:
  /** Row-level provenance: the compact wire JSON of a core `Provenance` (validated before it is
    * used), the same text on every row of a run. It is a STRING column (a struct with a map column
    * cannot take part in Spark set operations, which the compare tool and the determinism checks
    * use); `invalidRows` parses it back into the nested `provenance` object before it validates the
    * row as the core record.
    */
  def provenanceJson(
      refs: List[InputRef],
      codeVersion: String,
      params: Map[String, String],
      runId: String,
  ): String =
    val p = Provenance(
      1,
      SourceType.ScholarChain,
      refs,
      codeVersion,
      scala.collection.immutable.ListMap.from(params.toList.sortBy(_._1)),
      Some(runId),
      None,
    )
    Wire.write(p) match
      case Right(json) => json
      case Left(e)     => throw new IllegalStateException(s"row provenance is invalid: $e")

  def provenance(
      refs: List[InputRef],
      codeVersion: String,
      params: Map[String, String],
      runId: String,
  ): Column = F.lit(provenanceJson(refs, codeVersion, params, runId))

  /** `edges` is the internal edge frame (`Graph.edges`). */
  def coauthorEdges(edges: DataFrame, prov: Column): DataFrame =
    edges.select(
      F.col(Cols.Window),
      F.col(Cols.WindowStart),
      F.col(Cols.WindowEnd),
      F.lit(1).as("schemaVersion"),
      F.col("edgeId"),
      F.col("researcherA"),
      F.col("researcherB"),
      F.col("policyVersion"),
      F.col("jointPublicationCount"),
      F.col("firstCollaborationYear"),
      F.col("lastCollaborationYear"),
      F.col("representativeWorkIds"),
      F.col("supportingWorksDigest"),
      F.col("supportingWorksTruncated"),
      F.lit(CoauthorEdgeV2.EvidenceTable).as("evidenceTable"),
      F.col("edgeId").as("evidenceKey"),
      F.col(Cols.Window).as("evidencePartition"),
      prov.as("provenance"),
    )

  /** Full edge-to-work evidence: one row per (edge, work). `pairs` is `Graph.canonicalPairs`. */
  def edgeWorks(pairs: DataFrame, windows: DataFrame, cfg: SparkConfig): DataFrame =
    Udfs.register(pairs.sparkSession)
    Graph
      .expandWindows(pairs, windows)
      .select(
        F.col(Cols.Window),
        F.col(Cols.WindowStart),
        F.col(Cols.WindowEnd),
        F.call_udf(
          Udfs.CoauthorEdgeId,
          F.col("researcherA"),
          F.col("researcherB"),
          F.col(Cols.WindowStart),
          F.col(Cols.WindowEnd),
          F.lit(cfg.policyVersion),
        ).as("edgeId"),
        F.col("workId"),
      )

  /** Joins bridge measures (long form), community, component and coreness into the metrics frame.
    */
  def researcherMetrics(
      metrics: DataFrame,
      coreness: DataFrame,
      compMem: DataFrame,
      commMem: DataFrame,
      bridges: DataFrame,
      cfg: SparkConfig,
      prov: Column,
  ): DataFrame =
    val keys                           = Seq(Cols.Window, "researcherId")
    def pick(m: BridgeMeasure): Column =
      F.max(F.when(F.col("measure") === m.wire, F.col("value")))
    val wide = bridges
      .groupBy(keys.map(F.col)*)
      .agg(
        pick(BridgeMeasure.CrossCommunityDegree).cast("long").as("crossCommunityDegree"),
        pick(BridgeMeasure.ParticipationCoefficient).as("participationCoefficient"),
        pick(BridgeMeasure.DistinctCommunities).cast("int").as("communitiesTouched"),
      )
    metrics
      .join(
        coreness.select(F.col(Cols.Window), F.col("researcherId"), F.col("coreness")),
        keys,
        "left",
      )
      .join(
        compMem.select(F.col(Cols.Window), F.col("researcherId"), F.col("componentId")),
        keys,
        "left",
      )
      .join(
        commMem.select(F.col(Cols.Window), F.col("researcherId"), F.col("communityId")),
        keys,
        "left",
      )
      .join(wide, keys, "left")
      .select(
        F.col(Cols.Window),
        F.col(Cols.WindowStart),
        F.col(Cols.WindowEnd),
        F.lit(1).as("schemaVersion"),
        F.col("researcherId"),
        F.lit(cfg.policyVersion).as("policyVersion"),
        F.col("publicationCount"),
        F.col("coauthorCount"),
        F.col("teamSizeTotal"),
        F.col("avgTeamSize"),
        F.col("maxTeamSize"),
        F.col("weightedDegree"),
        F.col("maxTeamWorkCount"),
        F.col("maxTeamWorkIds"),
        F.col("communityId"),
        F.col("componentId"),
        F.col("coreness"),
        F.col("crossCommunityDegree"),
        F.col("participationCoefficient"),
        F.col("communitiesTouched"),
        F.lit(null).cast("double").as("tieStrengthWeightedDegree"),
        prov.as("provenance"),
      )

  // ---- validation as the core records (runs on the executors) ---------------------------------

  /** `None` when `json` is a valid `CoauthorEdgeV2`, else the error text. */
  def checkEdge(json: String): Option[String] =
    Wire.read[CoauthorEdgeV2](json).left.toOption.map(_.toString)

  /** `None` when `json` is a valid `ResearcherMetricsV2`, else the error text. */
  def checkMetrics(json: String): Option[String] =
    Wire.read[ResearcherMetricsV2](json).left.toOption.map(_.toString)

  /** Replaces the string-valued `provenance` field of a row JSON by the parsed JSON object. */
  def inlineProvenance(rowJson: String): String =
    val o = ujson.read(rowJson).obj
    o.get("provenance").collect { case ujson.Str(text) => o("provenance") = ujson.read(text) }
    ujson.write(o)

  private val MsgSchema: StructType = StructType.fromDDL("message string")

  /** Frame (`message`) of every row of `table` that does NOT validate as its core record, each
    * message prefixed by the row key. Runs `Wire.read` on the executors (the whole table, never
    * collected); the returned frame is empty for a valid table, so the driver only counts it or
    * looks at a bounded `limit`. `table` is a frame of the declared v2 `Tables.Spec` (with
    * `window`).
    */
  def invalidRows(table: DataFrame, spec: Tables.Spec): DataFrame =
    val fields = spec.columns.filterNot(_ == Cols.Window)
    val key    = if spec.name == Tables.CoauthorEdges.name then "edgeId" else "researcherId"
    val json   = table.select(
      F.to_json(
        F.struct(fields.map(F.col)*),
        Map("ignoreNullFields" -> "false"),
      ).as("j"),
      F.col(Cols.Window).as("w"),
      F.col(key).as("k"),
    )
    val rdd = json.rdd.mapPartitions(new InvalidRowsFn(spec.name == Tables.CoauthorEdges.name))
    table.sparkSession.createDataFrame(rdd, MsgSchema)
end V2Export

/** Per-partition validation function (a top-level serializable class, so no closure is captured).
  */
final class InvalidRowsFn(edges: Boolean) extends (Iterator[Row] => Iterator[Row]), Serializable:
  override def apply(it: Iterator[Row]): Iterator[Row] =
    it.flatMap { r =>
      val json   = V2Export.inlineProvenance(r.getString(0))
      val result =
        if edges then V2Export.checkEdge(json) else V2Export.checkMetrics(json)
      result.map(m => Row(s"${r.getString(1)} ${r.getString(2)}: $m"))
    }
