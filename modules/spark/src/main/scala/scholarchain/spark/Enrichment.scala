package scholarchain.spark

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions as F

/** Work identity and enrichment (human decision 4.1.5): the DBLP work ID (`work:dblp:...`) is the
  * canonical work. ACCEPTED work decisions only attach the other side's external ID and its topics
  * as enrichment; they never change, merge or remove a DBLP work. Institutions have no link to a
  * work or mention in the Phase 04 tables (no affiliation link), so they are emitted as nodes only.
  */
object Enrichment:
  val DblpPrefix = "work:dblp:"

  def dblpWorks(works: DataFrame): DataFrame =
    works.where(F.col("workId").startsWith(DblpPrefix))

  /** `workId` (DBLP) and `extWorkId`: accepted work decisions with exactly one DBLP side. */
  def workLinks(decisions: DataFrame): DataFrame =
    val d  = decisions.where(F.col("entityKind") === "work" && F.col("outcome") === "accepted")
    val lD = F.col("leftId").startsWith(DblpPrefix)
    val rD = F.col("rightId").startsWith(DblpPrefix)
    d.select(
      F.when(lD && !rD, F.col("leftId")).when(rD && !lD, F.col("rightId")).as("workId"),
      F.when(lD && !rD, F.col("rightId")).when(rD && !lD, F.col("leftId")).as("extWorkId"),
    ).where(F.col("workId").isNotNull)
      .distinct()

  /** HAS_TOPIC enrichment edges: `workId, topicId, viaWorkId` (the work that carries the topic). */
  def hasTopic(works: DataFrame, links: DataFrame): DataFrame =
    val ext = works
      .where(!F.col("workId").startsWith(DblpPrefix))
      .select(F.col("workId").as("extWorkId"), F.explode(F.col("topicIds")).as("topicId"))
    val viaLinks = links
      .join(ext, "extWorkId")
      .select(F.col("workId"), F.col("topicId"), F.col("extWorkId").as("viaWorkId"))
    val own = dblpWorks(works)
      .select(
        F.col("workId"),
        F.explode(F.col("topicIds")).as("topicId"),
        F.col("workId").as("viaWorkId"),
      )
    viaLinks.union(own).distinct()

  /** The `works` table: DBLP works with canonical team size, enrichment topics and external IDs. */
  def worksTable(
      works: DataFrame,
      teams: DataFrame,
      links: DataFrame,
      hasTopic: DataFrame,
  ): DataFrame =
    val ext = links
      .groupBy("workId")
      .agg(F.sort_array(F.collect_set("extWorkId")).as("externalWorkIds"))
    val tops = hasTopic
      .groupBy("workId")
      .agg(F.sort_array(F.collect_set("topicId")).as("topics"))
    dblpWorks(works)
      .join(teams.select(F.col("workId"), F.col("teamSize")), Seq("workId"), "left")
      .join(ext, Seq("workId"), "left")
      .join(tops, Seq("workId"), "left")
      .select(
        F.col("workId"),
        F.col("title"),
        F.col("year"),
        F.col("doi"),
        F.col("venueId"),
        F.coalesce(F.col("topics"), F.array().cast("array<string>")).as("topicIds"),
        F.col("teamSize"),
        F.coalesce(F.col("externalWorkIds"), F.array().cast("array<string>"))
          .as("externalWorkIds"),
      )

  def venues(works: DataFrame): DataFrame =
    dblpWorks(works)
      .where(F.col("venueId").isNotNull)
      .groupBy("venueId")
      .agg(F.count(F.lit(1)).as("workCount"))
      .select(
        F.col("venueId"),
        F.regexp_replace(F.col("venueId"), "^venue:[a-z0-9-]+:", "").as("name"),
        F.col("workCount"),
      )

  def publishedIn(works: DataFrame): DataFrame =
    dblpWorks(works).where(F.col("venueId").isNotNull).select("workId", "venueId")

  def topics(topics: DataFrame, hasTopic: DataFrame): DataFrame =
    val n = hasTopic.groupBy("topicId").agg(F.countDistinct("workId").as("linkedWorkCount"))
    topics
      .join(n, Seq("topicId"), "left")
      .select(
        F.col("topicId"),
        F.col("label"),
        F.coalesce(F.col("linkedWorkCount"), F.lit(0L)).as("linkedWorkCount"),
      )

  def institutions(institutions: DataFrame): DataFrame =
    institutions.select("institutionId", "name", "country")

  /** The `researchers` node table: clusters that authored at least one work. */
  def researchers(clusters: DataFrame, authored: DataFrame): DataFrame =
    val span = authored
      .groupBy("researcherId")
      .agg(F.min("year").as("firstYear"), F.max("year").as("lastYear"))
    clusters
      .join(span, "researcherId")
      .select(
        F.col("researcherId"),
        F.col("displayName"),
        F.col("orcid"),
        F.col("orcids"),
        F.col("ruleFired").as("identityRule"),
        F.col("cannotLinkStatus"),
        F.col("memberCount").as("memberMentionCount"),
        F.col("firstYear"),
        F.col("lastYear"),
      )

  /** The `external-identities` vertex table (repair 02, R-V1): one row per DISTINCT `RESOLVES_TO`
    * target: `externalId` (`researcher:orcid:<orcid>`, the edge's `targetId`), `kind` (`orcid`) and
    * `value` (the validated ORCID). Derived from the edges, so every target is a vertex.
    */
  def externalIdentities(resolvesTo: DataFrame): DataFrame =
    resolvesTo
      .select(F.col("targetId").as("externalId"), F.col("orcid").as("value"))
      .distinct()
      .select(F.col("externalId"), F.lit("orcid").as("kind"), F.col("value"))

  /** `RESOLVES_TO` edges (repair decision b): canonical researcher ID ->
    * `researcher:orcid:<orcid>`, one row per (researcher, validated ORCID), only for researchers of
    * the `researchers` table (graph nodes). Every ORCID of the researcher gets an edge (a base
    * identity with two ORCIDs gets two edges and a null `orcid` property); two researchers sharing
    * an ORCID each get an edge to the same target. `evidence` is `Identity.Result.orcidEvidence`
    * (the mentions that carried the ORCID; empty when the ORCID came only from an alias record).
    * `prov` is the row-level provenance.
    */
  def resolvesTo(
      researchers: DataFrame,
      evidence: DataFrame,
      prov: org.apache.spark.sql.Column,
  ): DataFrame =
    researchers
      .select(F.col("researcherId"), F.explode(F.col("orcids")).as("orcid"))
      .join(evidence, Seq("researcherId", "orcid"), "left")
      .select(
        F.lit(scholarchain.core.GraphEdgeTypes.ResolvesTo).as("edgeType"),
        F.col("researcherId").as("sourceId"),
        F.concat(F.lit("researcher:orcid:"), F.col("orcid")).as("targetId"),
        F.col("orcid"),
        F.coalesce(F.col("mentionIds"), F.array().cast("array<string>")).as("evidenceMentionIds"),
        prov.as("provenance"),
      )
end Enrichment
