package scholarchain.spark

import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.functions as F
import org.apache.spark.sql.types.StructType

import scala.jdk.CollectionConverters.*

import scholarchain.core.{GraphWindow, SparkConfig}

/** Temporal weighted coauthorship graph and the breadth / team-size measures.
  *
  * Window inclusion rule: a work belongs to window `[start, end]` (inclusive) iff its publication
  * year `y` satisfies `start <= y <= end`. Explicit windows never overlap, so a work is in at most
  * one explicit window; the all-time window (`1000-9999`) contains every work. A researcher is a
  * node of a window iff they authored at least one work in it. An edge needs a work in the window
  * that both authors share. Identity (merging) is decided once, globally, never per window.
  */
object Graph:
  private val WindowSchema: StructType =
    StructType.fromDDL("window string, windowStart int, windowEnd int")

  /** The configured windows (plus all-time when configured) as a small broadcastable frame. */
  def windowsFrame(spark: SparkSession, cfg: SparkConfig): DataFrame =
    val rows = cfg.allWindows.map(w => Row(GraphWindow.label(w), w.startYear, w.endYear))
    spark.createDataFrame(rows.asJava, WindowSchema)

  /** Joins every row to each window containing its `year` column (inclusive bounds). */
  def expandWindows(df: DataFrame, windows: DataFrame): DataFrame =
    df.join(
      F.broadcast(windows),
      F.col("year").between(F.col(Cols.WindowStart), F.col(Cols.WindowEnd)),
    )

  /** AUTHORED: one row per (canonical researcher, work). `authorship` is `InputSchemas.Authorship`;
    * `mentionResearcher` has `mentionId, researcherId`.
    */
  def authored(authorship: DataFrame, mentionResearcher: DataFrame): DataFrame =
    authorship
      .join(mentionResearcher.select("mentionId", "researcherId"), "mentionId")
      .groupBy("researcherId", "workId")
      .agg(
        F.min("year").as("year"),
        F.min("position").as("position"),
        F.count(F.lit(1)).cast("int").as("mentionCount"),
      )

  /** Team size of a work = number of distinct canonical researchers on it. */
  def workTeams(authored: DataFrame): DataFrame =
    authored
      .groupBy("workId")
      .agg(F.count(F.lit(1)).cast("int").as("teamSize"), F.min("year").as("year"))

  /** Researchers active in each window. */
  def nodes(authored: DataFrame, windows: DataFrame): DataFrame =
    expandWindows(authored.select("researcherId", "year"), windows)
      .select(Cols.Window, Cols.WindowStart, Cols.WindowEnd, "researcherId")
      .distinct()

  /** Maps mention-level pair candidates onto canonical researchers and orders each pair
    * (`researcherA <= researcherB`). Pairs that became self-pairs after a merge are KEPT here
    * (`researcherA == researcherB`) so that they can be counted; use `canonicalPairs` to drop them.
    * `pairs` is `InputSchemas.PairCandidates`.
    */
  def mappedPairs(pairs: DataFrame, mentionResearcher: DataFrame): DataFrame =
    val a = mentionResearcher.select(F.col("mentionId").as("m1"), F.col("researcherId").as("r1"))
    val b = mentionResearcher.select(F.col("mentionId").as("m2"), F.col("researcherId").as("r2"))
    pairs
      .join(a, F.col("first") === F.col("m1"))
      .join(b, F.col("second") === F.col("m2"))
      .select(
        F.least(F.col("r1"), F.col("r2")).as("researcherA"),
        F.greatest(F.col("r1"), F.col("r2")).as("researcherB"),
        F.col("workId"),
        F.col("year"),
      )

  /** Distinct (A, B, work) rows with `A < B`: self-pairs dropped, duplicates removed. */
  def canonicalPairs(mapped: DataFrame): DataFrame =
    mapped.where(F.col("researcherA") =!= F.col("researcherB")).distinct()

  /** One undirected weighted edge per (window, A, B): `jointPublicationCount` distinct works,
    * first/last collaboration year, a bounded sorted list of representative work IDs plus a digest
    * pointing at the full sorted list. `pairs` is the output of `canonicalPairs`.
    */
  def edges(pairs: DataFrame, windows: DataFrame, cfg: SparkConfig): DataFrame =
    Udfs.register(pairs.sparkSession)
    val bound = cfg.representativeWorkIdsBound
    expandWindows(pairs, windows)
      .groupBy(Cols.Window, Cols.WindowStart, Cols.WindowEnd, "researcherA", "researcherB")
      .agg(
        F.count(F.lit(1)).cast("int").as("jointPublicationCount"),
        F.min("year").as("firstCollaborationYear"),
        F.max("year").as("lastCollaborationYear"),
        F.sort_array(F.collect_set("workId")).as("allWorkIds"),
      )
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
        F.col("researcherA"),
        F.col("researcherB"),
        F.col("jointPublicationCount"),
        F.col("firstCollaborationYear"),
        F.col("lastCollaborationYear"),
        F.slice(F.col("allWorkIds"), 1, bound).as("representativeWorkIds"),
        F.call_udf(Udfs.WorksDigest, F.col("allWorkIds")).as("supportingWorksDigest"),
        (F.size(F.col("allWorkIds")) > bound).as("supportingWorksTruncated"),
        F.lit(cfg.policyVersion).as("policyVersion"),
      )

  /** Both directions of every edge: `window, src, dst, weight` (weight = joint publications). */
  def arcs(edges: DataFrame): DataFrame =
    val fwd = edges.select(
      F.col(Cols.Window),
      F.col("researcherA").as("src"),
      F.col("researcherB").as("dst"),
      F.col("jointPublicationCount").as("weight"),
    )
    val bwd = edges.select(
      F.col(Cols.Window),
      F.col("researcherB").as("src"),
      F.col("researcherA").as("dst"),
      F.col("jointPublicationCount").as("weight"),
    )
    fwd.union(bwd)
end Graph

/** Breadth and team size per researcher and window.
  *   - `publicationCount` P_r: distinct works of r in the window.
  *   - `coauthorCount` C_r: distinct coauthors (degree in the window graph).
  *   - `teamSizeTotal` T_r: sum over r's works of the work's team size.
  *   - `avgTeamSize` A_r = T_r / P_r.
  *   - `maxTeamSize` M_r: largest team size among r's works in the window.
  *   - `weightedDegree` W_r: sum of `jointPublicationCount` over r's edges.
  *   - `maxTeamWorkIds`: the (bounded, sorted) works that attain M_r, `maxTeamWorkCount` their
  *     number.
  * No composite score is computed; every measure stays separate.
  */
object Metrics:
  def researcherMetrics(
      authored: DataFrame,
      teams: DataFrame,
      edges: DataFrame,
      windows: DataFrame,
      cfg: SparkConfig,
  ): DataFrame =
    val g  = Cols.windowCols
    val aw = Graph.expandWindows(
      authored.join(teams.select("workId", "teamSize"), "workId"),
      windows,
    )
    val base = aw
      .groupBy((g :+ "researcherId").map(F.col)*)
      .agg(
        F.count(F.lit(1)).as("publicationCount"),
        F.sum("teamSize").as("teamSizeTotal"),
        F.max("teamSize").as("maxTeamSize"),
      )
    val maxWorks = aw
      .join(
        base.select(F.col(Cols.Window), F.col("researcherId"), F.col("maxTeamSize").as("mx")),
        Seq(Cols.Window, "researcherId"),
      )
      .where(F.col("teamSize") === F.col("mx"))
      .groupBy(Cols.Window, "researcherId")
      .agg(F.sort_array(F.collect_set("workId")).as("mw"))
    val deg = Graph
      .arcs(edges)
      .groupBy(F.col(Cols.Window), F.col("src").as("researcherId"))
      .agg(F.count(F.lit(1)).as("coauthorCount"), F.sum("weight").as("weightedDegree"))
    base
      .join(maxWorks, Seq(Cols.Window, "researcherId"), "left")
      .join(deg, Seq(Cols.Window, "researcherId"), "left")
      .select(
        F.col(Cols.Window),
        F.col(Cols.WindowStart),
        F.col(Cols.WindowEnd),
        F.col("researcherId"),
        F.col("publicationCount").cast("long").as("publicationCount"),
        F.coalesce(F.col("coauthorCount"), F.lit(0L)).as("coauthorCount"),
        F.col("teamSizeTotal").cast("long").as("teamSizeTotal"),
        (F.col("teamSizeTotal") / F.col("publicationCount")).as("avgTeamSize"),
        F.col("maxTeamSize").cast("int").as("maxTeamSize"),
        F.coalesce(F.col("weightedDegree"), F.lit(0L)).as("weightedDegree"),
        F.size(F.col("mw")).as("maxTeamWorkCount"),
        F.slice(F.col("mw"), 1, cfg.representativeWorkIdsBound).as("maxTeamWorkIds"),
      )
end Metrics
