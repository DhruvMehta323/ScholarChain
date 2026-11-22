package scholarchain.spark

import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.functions as F

/** Helpers for `map<string,string>` parameter columns (no Scala 2 type tags in Scala 3). */
object Params:
  /** A map column built from literals, keys sorted so the result is deterministic. */
  def mapColumn(p: Map[String, String]): Column =
    F.map(p.toList.sortBy(_._1).flatMap((k, v) => List(F.lit(k), F.lit(v)))*)

/** Communities by deterministic label propagation (LPA), DataFrame only.
  *
  * Frames as in `Components` (`nodes: window,id`; `arcs: window,src,dst`, both directions).
  *
  * Rule: every node starts with its own ID as label. In round `i` only nodes whose parity class
  * (`pmod(xxhash64(id), 2)`) equals `i mod 2` update; an updating node adopts the label with the
  * MOST votes, ties broken towards the SMALLEST label. The votes are the labels of its neighbours
  * plus ONE vote for its own current label (two adjacent nodes then agree instead of swapping
  * labels, and an isolated node keeps its label). The alternating schedule (semi-synchronous)
  * removes the label swapping that a fully synchronous update shows on bipartite structures. The
  * loop stops after two consecutive rounds without any change (one per parity class) or after
  * `maxIterations`. After the loop every label class is renamed to its smallest member ID, so
  * community names do not depend on which label survived. Each round is checkpointed.
  *
  * Known weaknesses: the result is a local optimum of a heuristic, not a modularity optimum;
  * different schedules give different partitions (this schedule is deterministic, not canonical);
  * label flooding can merge loosely linked groups into one community; large dense teams (a
  * 40-author paper is a clique) pull their members into one community; the cap can stop it before a
  * fixed point (reported in `algorithm-status`); communities are not stable across windows and are
  * not overlapping; resolution is not tunable.
  */
object Communities:
  val Method = "label_propagation.v1"

  def labelPropagation(
      nodes: DataFrame,
      arcs: DataFrame,
      maxIterations: Int,
      ck: Checkpointer,
  ): (DataFrame, Convergence) =
    val a          = ck(arcs.select("window", "src", "dst"))
    var cur        = ck(nodes.select(F.col("window"), F.col("id"), F.col("id").as("label")))
    var iter       = 0
    var zeroStreak = 0
    while zeroStreak < 2 && iter < maxIterations do
      val parity         = (iter % 2).toLong
      val neighbourVotes = a
        .join(
          cur.select(
            F.col("window"),
            F.col("id").as("dst"),
            F.col("label").as("dlabel"),
          ),
          Seq("window", "dst"),
        )
        .select("window", "src", "dlabel")
      val selfVotes = cur.select(
        F.col("window"),
        F.col("id").as("src"),
        F.col("label").as("dlabel"),
      )
      val votes = neighbourVotes
        .union(selfVotes)
        .groupBy("window", "src", "dlabel")
        .agg(F.count(F.lit(1)).as("cnt"))
      val best = votes
        .groupBy("window", "src")
        .agg(
          F.min_by(
            F.col("dlabel"),
            F.struct((F.lit(0L) - F.col("cnt")).as("negCnt"), F.col("dlabel").as("lbl")),
          ).as("best")
        )
        .select(F.col("window"), F.col("src").as("id"), F.col("best"))
      val updates = F.pmod(F.xxhash64(F.col("id")), F.lit(2L)) === parity
      val next    = ck(
        cur
          .join(best, Seq("window", "id"), "left")
          .select(
            F.col("window"),
            F.col("id"),
            F.when(updates && F.col("best").isNotNull, F.col("best"))
              .otherwise(F.col("label"))
              .as("label"),
          )
      )
      val changed = DriverCollect.count(
        next
          .join(
            cur.select(F.col("window"), F.col("id"), F.col("label").as("old")),
            Seq("window", "id"),
          )
          .where(F.col("label") =!= F.col("old"))
      )
      cur = next
      iter += 1
      zeroStreak = if changed == 0L then zeroStreak + 1 else 0
    (cur, Convergence(Method, iter, zeroStreak >= 2, maxIterations))

  /** `community:<window label>:<smallest member ID>`. */
  def communityId(window: Column, minMember: Column): Column =
    F.concat(F.lit("community:"), window, F.lit(":"), minMember)

  /** Membership (`window cols, communityId, researcherId`) from final labels. */
  def membership(labels: DataFrame, windows: DataFrame): DataFrame =
    val key = labels
      .groupBy("window", "label")
      .agg(F.min("id").as("minId"))
    labels
      .join(key, Seq("window", "label"))
      .join(F.broadcast(windows), Seq(Cols.Window))
      .select(
        F.col(Cols.Window),
        F.col(Cols.WindowStart),
        F.col(Cols.WindowEnd),
        communityId(F.col("window"), F.col("minId")).as("communityId"),
        F.col("id").as("researcherId"),
      )

  /** Edge classification: one row per edge with the communities of both endpoints. */
  private def edgeCommunities(edges: DataFrame, membership: DataFrame): DataFrame =
    val ma = membership.select(
      F.col(Cols.Window).as("w"),
      F.col("researcherId").as("ra"),
      F.col("communityId").as("ca"),
    )
    val mb = membership.select(
      F.col(Cols.Window).as("w2"),
      F.col("researcherId").as("rb"),
      F.col("communityId").as("cb"),
    )
    edges
      .select(F.col(Cols.Window), F.col("researcherA"), F.col("researcherB"))
      .join(
        ma,
        F.col(Cols.Window) === F.col("w") && F.col("researcherA") === F.col("ra"),
      )
      .join(
        mb,
        F.col(Cols.Window) === F.col("w2") && F.col("researcherB") === F.col("rb"),
      )
      .select(F.col(Cols.Window), F.col("ca"), F.col("cb"))

  /** Community records: size, method, parameters, internal and cross-community edge counts. */
  def records(
      membership: DataFrame,
      edges: DataFrame,
      parameters: Map[String, String],
  ): DataFrame =
    val ec       = edgeCommunities(edges, membership)
    val internal = ec
      .where(F.col("ca") === F.col("cb"))
      .groupBy(F.col(Cols.Window), F.col("ca").as("communityId"))
      .agg(F.count(F.lit(1)).as("internalEdgeCount"))
    val crossSides = ec
      .where(F.col("ca") =!= F.col("cb"))
      .select(F.col(Cols.Window), F.col("ca").as("communityId"))
      .union(
        ec.where(F.col("ca") =!= F.col("cb"))
          .select(F.col(Cols.Window), F.col("cb").as("communityId"))
      )
      .groupBy(Cols.Window, "communityId")
      .agg(F.count(F.lit(1)).as("crossEdgeCount"))
    membership
      .groupBy(Cols.Window, Cols.WindowStart, Cols.WindowEnd, "communityId")
      .agg(F.count(F.lit(1)).as("size"))
      .join(internal, Seq(Cols.Window, "communityId"), "left")
      .join(crossSides, Seq(Cols.Window, "communityId"), "left")
      .select(
        F.col(Cols.Window),
        F.col(Cols.WindowStart),
        F.col(Cols.WindowEnd),
        F.col("communityId"),
        F.col("size"),
        F.lit(Method).as("method"),
        Params.mapColumn(parameters).as("parameters"),
        F.coalesce(F.col("internalEdgeCount"), F.lit(0L)).as("internalEdgeCount"),
        F.coalesce(F.col("crossEdgeCount"), F.lit(0L)).as("crossEdgeCount"),
      )

  /** Edge counts between each pair of communities (communityA < communityB). */
  def crossEdges(membership: DataFrame, edges: DataFrame, windows: DataFrame): DataFrame =
    edgeCommunities(edges, membership)
      .where(F.col("ca") =!= F.col("cb"))
      .groupBy(
        F.col(Cols.Window),
        F.least(F.col("ca"), F.col("cb")).as("communityA"),
        F.greatest(F.col("ca"), F.col("cb")).as("communityB"),
      )
      .agg(F.count(F.lit(1)).as("edgeCount"))
      .join(F.broadcast(windows), Seq(Cols.Window))
      .select(
        F.col(Cols.Window),
        F.col(Cols.WindowStart),
        F.col(Cols.WindowEnd),
        F.col("communityA"),
        F.col("communityB"),
        F.col("edgeCount"),
      )
end Communities
