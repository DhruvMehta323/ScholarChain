package scholarchain.spark

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions as F

import scholarchain.core.SparkConfig

/** Deterministic, seeded sampling that never collects the population. */
object Sampling:
  /** For each window label, the `n` rows with the smallest `xxhash64(seed, id)` (ties by id).
    * `members` has `window, id`. One bounded top-n per window (a loop over the configured windows,
    * not over data); the frames are never brought to the driver.
    */
  def smallestHash(members: DataFrame, windowLabels: Seq[String], seed: Long, n: Int): DataFrame =
    val parts = windowLabels.map { w =>
      members
        .where(F.col("window") === w)
        .orderBy(F.xxhash64(F.lit(seed), F.col("id")), F.col("id"))
        .limit(n)
    }
    parts.reduce(_.union(_))

  /** The largest component per window (ties: smallest component ID) with its members. `membership`
    * has `window, componentId, researcherId`; the result `window, id, componentId, size`.
    */
  def largestComponentMembers(membership: DataFrame): DataFrame =
    val sizes = membership.groupBy("window", "componentId").agg(F.count(F.lit(1)).as("size"))
    val rank  = Window.partitionBy("window").orderBy(F.col("size").desc, F.col("componentId").asc)
    val top   = sizes
      .withColumn("rn", F.row_number().over(rank))
      .where(F.col("rn") === 1)
      .select("window", "componentId", "size")
    membership
      .join(top, Seq("window", "componentId"))
      .select(F.col("window"), F.col("researcherId").as("id"), F.col("componentId"), F.col("size"))

  /** Pairs for path evidence: members sorted by seeded hash, `2 * pairs` of them at most, paired
    * consecutively (1st with 2nd, 3rd with 4th, ...). Result `window, startId, endId`.
    */
  def samplePairs(
      largest: DataFrame,
      windowLabels: Seq[String],
      seed: Long,
      pairs: Int,
  ): DataFrame =
    if pairs <= 0 then
      largest.select(F.col("window"), F.col("id").as("startId"), F.col("id").as("endId")).limit(0)
    else
      val picked = smallestHash(largest.select("window", "id"), windowLabels, seed, 2 * pairs)
      val rank   = Window
        .partitionBy("window")
        .orderBy(F.xxhash64(F.lit(seed), F.col("id")), F.col("id"))
      val ranked = picked.withColumn("rn", F.row_number().over(rank))
      val starts = ranked
        .where(F.col("rn") % 2 === 1)
        .select(
          F.col("window"),
          F.col("id").as("startId"),
          ((F.col("rn") + 1) / 2).cast("int").as("p"),
        )
      val ends = ranked
        .where(F.col("rn") % 2 === 0)
        .select(
          F.col("window").as("w2"),
          F.col("id").as("endId"),
          (F.col("rn") / 2).cast("int").as("p2"),
        )
      starts
        .join(ends, F.col("window") === F.col("w2") && F.col("p") === F.col("p2"))
        .select("window", "startId", "endId")
end Sampling

/** Bounded breadth-first search by iterative joins, from selected sources only (never all-pairs).
  *
  * `bfs` takes `arcs: window,src,dst` (both directions) and `sources: window,source`. It returns
  * the visited table `window, source, node, dist, parent` (the source itself has `dist = 0`, parent
  * null). Round d joins the frontier with the arcs (shuffle 1), removes already visited nodes
  * (anti-join, shuffle 2) and keeps the smallest parent ID per new node (aggregation, shuffle 3),
  * so the parent chosen for a node is deterministic. It stops when the frontier is empty
  * (converged) or at `maxDepth` (not converged: nodes farther than `maxDepth` are not visited).
  * Cost grows with (number of sources) x (nodes within `maxDepth`), which is why sources are
  * sampled.
  */
object ShortestPaths:
  val AlgorithmName = "bfs_bounded"

  def bfs(
      arcs: DataFrame,
      sources: DataFrame,
      maxDepth: Int,
      ck: Checkpointer,
  ): (DataFrame, Convergence) =
    val a = ck(
      arcs.select(F.col("window").as("aw"), F.col("src").as("asrc"), F.col("dst").as("adst"))
    )
    val init = sources.select(
      F.col("window"),
      F.col("source"),
      F.col("source").as("node"),
      F.lit(0).as("dist"),
      F.lit(null).cast("string").as("parent"),
    )
    var visited   = ck(init)
    var frontier  = ck(init.select("window", "source", "node"))
    var depth     = 0
    var exhausted = false
    while !exhausted && depth < maxDepth do
      depth += 1
      val f = frontier.select(
        F.col("window").as("fw"),
        F.col("source").as("fsource"),
        F.col("node").as("fnode"),
      )
      val cand = f
        .join(a, F.col("fw") === F.col("aw") && F.col("fnode") === F.col("asrc"))
        .select(
          F.col("fw").as("window"),
          F.col("fsource").as("source"),
          F.col("adst").as("node"),
          F.col("fnode").as("parent"),
        )
      val seen = visited.select(
        F.col("window").as("vw"),
        F.col("source").as("vsource"),
        F.col("node").as("vnode"),
      )
      val fresh = ck(
        cand
          .join(
            seen,
            F.col("window") === F.col("vw") && F.col("source") === F.col("vsource") &&
              F.col("node") === F.col("vnode"),
            "left_anti",
          )
          .groupBy("window", "source", "node")
          .agg(F.min("parent").as("parent"))
          .select(
            F.col("window"),
            F.col("source"),
            F.col("node"),
            F.lit(depth).as("dist"),
            F.col("parent"),
          )
      )
      if DriverCollect.isEmpty(fresh) then exhausted = true
      else
        visited = ck(visited.union(fresh))
        frontier = fresh.select("window", "source", "node")
    (visited, Convergence(AlgorithmName, depth, exhausted, maxDepth))

  /** Shortest-path evidence for `pairs: window,startId,endId`. A pair with no path within the BFS
    * depth (or `start == end`) yields no row. `visited` must come from `bfs` with every start as a
    * source. Result columns: `window, startId, endId, hops, edgeIds, supportingWorkIds, length`
    * where `supportingWorkIds(i)` is the bounded `representativeWorkIds` of hop i's edge. `edges`
    * is the coauthor-edge table of the same windows.
    */
  def evidence(
      visited: DataFrame,
      pairs: DataFrame,
      edges: DataFrame,
      maxDepth: Int,
  ): DataFrame =
    val v = visited.select(
      F.col("window").as("vw"),
      F.col("source").as("vsource"),
      F.col("node").as("vnode"),
      F.col("dist").as("vdist"),
      F.col("parent").as("vparent"),
    )
    val reached = pairs
      .join(
        v,
        F.col("window") === F.col("vw") && F.col("startId") === F.col("vsource") &&
          F.col("endId") === F.col("vnode"),
      )
      .where(F.col("vdist") > 0)
      .select(
        F.col("window"),
        F.col("startId"),
        F.col("endId"),
        F.col("vdist").as("idx"),
        F.col("endId").as("node"),
      )
    var cur  = reached
    var all  = reached
    var step = 0
    while step < maxDepth do
      step += 1
      val next = cur
        .where(F.col("idx") > 0)
        .join(
          v,
          F.col("window") === F.col("vw") && F.col("startId") === F.col("vsource") &&
            F.col("node") === F.col("vnode"),
        )
        .select(
          F.col("window"),
          F.col("startId"),
          F.col("endId"),
          (F.col("idx") - 1).as("idx"),
          F.col("vparent").as("node"),
        )
      all = all.union(next)
      cur = next
    val hops = all
      .groupBy("window", "startId", "endId")
      .agg(
        F.sort_array(F.collect_list(F.struct(F.col("idx").as("idx"), F.col("node").as("node"))))
          .as("arr")
      )
      .select(
        F.col("window"),
        F.col("startId"),
        F.col("endId"),
        F.expr("transform(arr, x -> x.node)").as("hops"),
        (F.size(F.col("arr")) - 1).as("length"),
      )
    val h1 = all.select(
      F.col("window").as("w1"),
      F.col("startId").as("s1"),
      F.col("endId").as("e1"),
      F.col("idx").as("i1"),
      F.col("node").as("n1"),
    )
    val h2 = all.select(
      F.col("window").as("w2"),
      F.col("startId").as("s2"),
      F.col("endId").as("e2"),
      F.col("idx").as("i2"),
      F.col("node").as("n2"),
    )
    val e = edges.select(
      F.col("window").as("ew"),
      F.col("researcherA").as("ea"),
      F.col("researcherB").as("eb"),
      F.col("edgeId"),
      F.col("representativeWorkIds").as("works"),
    )
    val hopEdges = h1
      .join(
        h2,
        F.col("w1") === F.col("w2") && F.col("s1") === F.col("s2") && F.col("e1") === F.col("e2") &&
          F.col("i1") + 1 === F.col("i2"),
      )
      .join(
        e,
        F.col("w1") === F.col("ew") &&
          F.least(F.col("n1"), F.col("n2")) === F.col("ea") &&
          F.greatest(F.col("n1"), F.col("n2")) === F.col("eb"),
      )
      .groupBy(F.col("w1").as("window"), F.col("s1").as("startId"), F.col("e1").as("endId"))
      .agg(
        F.sort_array(
          F.collect_list(F.struct(F.col("i2").as("idx"), F.col("edgeId"), F.col("works")))
        ).as("harr")
      )
      .select(
        F.col("window"),
        F.col("startId"),
        F.col("endId"),
        F.expr("transform(harr, x -> x.edgeId)").as("edgeIds"),
        F.expr("transform(harr, x -> x.works)").as("supportingWorkIds"),
      )
    hops
      .join(hopEdges, Seq("window", "startId", "endId"))
      .select("window", "startId", "endId", "hops", "edgeIds", "supportingWorkIds", "length")

  /** Average shortest-path length and 90th-percentile (effective) diameter estimated from the BFS
    * runs of the sampled sources (all inside the largest component of their window).
    *   - `avgPathLength` = sum of distances / number of reached (source, node) pairs, nodes other
    *     than the source.
    *   - `effectiveDiameter90` = smallest d such that at least 90% of the reached pairs are within
    *     d hops (integer, no interpolation).
    *   - `unreachedWithinDepthPairs` = pairs (source, other node of the largest component) not
    *     reached within the BFS depth (a bias source: they are missing from the average).
    * `largestSize` has `window, size` (size of the largest component). `windows` is the window
    * frame; a window with no pair has null estimates and zero counts.
    */
  def estimate(
      visited: DataFrame,
      largestSize: DataFrame,
      windows: DataFrame,
      sourcesRequested: Int,
      seed: Long,
      maxDepth: Int,
  ): DataFrame =
    val reached  = visited.where(F.col("dist") > 0)
    val srcCount = visited
      .where(F.col("dist") === 0)
      .groupBy("window")
      .agg(F.count(F.lit(1)).as("sourcesUsed"))
    val stats = reached
      .groupBy("window")
      .agg(
        F.count(F.lit(1)).as("reachedPairs"),
        F.sum("dist").as("sumDist"),
        F.max("dist").as("maxObservedDistance"),
      )
    val hist = reached.groupBy("window", "dist").agg(F.count(F.lit(1)).as("cnt"))
    val cum  = Window
      .partitionBy("window")
      .orderBy("dist")
      .rowsBetween(Window.unboundedPreceding, Window.currentRow)
    val tot = Window.partitionBy("window")
    val eff = hist
      .withColumn("cum", F.sum("cnt").over(cum))
      .withColumn("total", F.sum("cnt").over(tot))
      .where(F.col("cum") * 10 >= F.col("total") * 9)
      .groupBy("window")
      .agg(F.min("dist").as("effectiveDiameter90"))
    windows
      .join(srcCount, Seq("window"), "left")
      .join(stats, Seq("window"), "left")
      .join(eff, Seq("window"), "left")
      .join(largestSize.select(F.col("window"), F.col("size").as("lsize")), Seq("window"), "left")
      .select(
        F.col("window"),
        F.col("windowStart"),
        F.col("windowEnd"),
        F.lit(sourcesRequested).as("sourcesRequested"),
        F.coalesce(F.col("sourcesUsed"), F.lit(0L)).as("sourcesUsed"),
        F.lit(seed).as("seed"),
        F.lit(maxDepth).as("maxDepth"),
        F.coalesce(F.col("reachedPairs"), F.lit(0L)).as("reachedPairs"),
        (F.coalesce(F.col("sourcesUsed"), F.lit(0L)) * (F.coalesce(F.col("lsize"), F.lit(1L)) - 1) -
          F.coalesce(F.col("reachedPairs"), F.lit(0L))).as("unreachedWithinDepthPairs"),
        (F.col("sumDist").cast("double") / F.col("reachedPairs").cast("double"))
          .as("avgPathLength"),
        F.col("effectiveDiameter90").cast("int").as("effectiveDiameter90"),
        F.col("maxObservedDistance").cast("int").as("maxObservedDistance"),
      )
end ShortestPaths
