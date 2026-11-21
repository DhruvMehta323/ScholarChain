package scholarchain.spark

import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.functions as F

import scholarchain.core.SparkConfig

/** Researcher identity (human decisions of Phase 06, section 4.1, and repair 01 of Phase 06), all
  * DataFrame operations.
  *
  * Base identities: every DBLP mention with the same EXACT author string (homonym suffix included)
  * is one base identity `Ids.researcherIdDblpPerson`; HJ1 person-alias records join several strings
  * into one base identity (the smallest string of the alias record names the base, which is the
  * base's DBLP person KEY; not transitive). Every non-DBLP mention (crossref, openalex) is its own
  * base identity `Ids.researcherIdUnresolved(mentionId)`.
  *
  * Merging: base identities are linked only by decisions with outcome `accepted` and kind
  * `researcher`; candidate merges are the connected components of those links (min-label
  * propagation). M5 cannot-link: a component with two or more base identities is NOT merged when
  *   - (a) any `rejected` or `conflict` researcher decision joins two of its base identities
  *     (`rejected_or_conflict_pair`), or
  *   - (b) its base identities carry two or more different validated ORCIDs (`two_orcids`), or
  *   - (c) (repair M1, implicit cannot-link, all-names rule of repair 02) it holds two DIFFERENT
  *     DBLP names with the same homonym STEM that belong to DIFFERENT base identities
  *     (`distinct_dblp_homonyms`; see `homonymStem` and `dblpNames`). Names inside one alias group
  *     are one base identity and never block each other.
  * Its members then fall back to their own base identities (`cannotLinkStatus = blocked`, rule
  * `cannot_link_fallback`) and the component is listed in `cannot-link-blocked` with its reason
  * (the reasons that apply, joined with `+`). `unresolved` and `conflict` never link anything.
  *
  * Repair M2: a `rejected` or `conflict` decision whose two endpoints map to the SAME base identity
  * is IGNORED for blocking (rule 4.1.1: a base identity is never split). It is counted and listed
  * in `within-base-rejections-ignored`.
  *
  * Canonical ID (repair decision b): a cluster that contains DBLP persons gets
  * `Ids.researcherIdForClusterV2(its DBLP person keys)`, the DBLP base ID of the smallest key (an
  * unmerged person: its own base ID). ORCID is NEVER the canonical ID: validated ORCIDs are kept as
  * the `orcid` / `orcids` properties and become `RESOLVES_TO` edges (built in `Enrichment`). A
  * cluster without a DBLP person keeps `researcherIdUnresolved` (single external mention) or the
  * merged-cluster ID of its base IDs (several external mentions joined by accepted decisions).
  *
  * Never derived from `ResolvedMention` records: identity comes from the decisions and base keys
  * only (Phase 05 review: an `accepted` ResolvedMention may carry another mention's singleton ID).
  */
object Identity:
  /** Constant window key used by the generic component loop for the identity graph. */
  val IdentityWindow = "identity"

  final case class Result(
      mentionResearcher: DataFrame,
      clusters: DataFrame,
      blocked: DataFrame,
      convergence: Convergence,
      unmappedDecisions: DataFrame,
      /** ORCIDs held by two or more clusters: `orcid, clusterCount, researcherIds`. */
      sharedOrcids: DataFrame,
      /** Decisions (rejected, conflict) ignored because both ends are one base identity. */
      withinBaseRejections: DataFrame,
      /** `researcherId, orcid, mentionIds`: mentions that carried each validated ORCID. */
      orcidEvidence: DataFrame,
  ):
    /** Deprecated name of `sharedOrcids` (the old ORCID-ID collision fallback no longer exists: IDs
      * are DBLP-based, so no two clusters can collide; kept so that dependants still compile).
      */
    def orcidCollisions: DataFrame = sharedOrcids

  /** DBLP homonym STEM of a person key: DBLP separates homonyms with a single space and a 4-digit
    * suffix (`Zhen Wang 0008`). Stem = the key without ONE trailing ` NNNN` (`[0-9]{4}`, preceded
    * by at least one character); a key without such a suffix is its own stem. Two different keys
    * with the same stem are homonyms of each other (different suffixes, or one suffixed and one
    * not). Exact strings: no trimming, folding or normalization.
    */
  private val SuffixRegex              = "^.+ [0-9]{4}$"
  def homonymStem(key: Column): Column =
    F.when(key.rlike(SuffixRegex), F.regexp_replace(key, " [0-9]{4}$", "")).otherwise(key)

  /** EVERY DBLP name of every base identity, `baseId, name` (distinct): the raw names of its DBLP
    * mentions, its base name (the smallest string of its alias record) and all HJ1 alias names that
    * map to it. An alias name maps to the base of its alias record (`aliasMin`; not transitive), so
    * the name -> base map is the same one `build` uses for the mentions. Distributed: joins and a
    * union, never collected.
    */
  def dblpNames(mentions: DataFrame, aliases: DataFrame): DataFrame =
    Udfs.register(mentions.sparkSession)
    val aliasMin = aliasBases(aliases)
    val dblp     = mentions.where(F.col("sourceType") === "dblp" && F.col("rawName").isNotNull)
    val withBase = dblp
      .join(F.broadcast(aliasMin), F.col("rawName") === F.col("aliasName"), "left")
      .select(
        F.col("rawName"),
        F.coalesce(F.col("aliasBaseName"), F.col("rawName")).as("baseName"),
      )
      .select(
        F.call_udf(Udfs.DblpPerson, F.col("baseName")).as("baseId"),
        F.col("rawName"),
        F.col("baseName"),
      )
    val fromMentions = withBase
      .select(F.col("baseId"), F.col("rawName").as("name"))
      .union(withBase.select(F.col("baseId"), F.col("baseName").as("name")))
    val fromAliases = aliasMin.select(
      F.call_udf(Udfs.DblpPerson, F.col("aliasBaseName")).as("baseId"),
      F.col("aliasName").as("name"),
    )
    fromMentions.union(fromAliases).distinct()

  /** `aliasName, aliasBaseName`: one row per alias string, with the smallest alias-record minimum
    * that lists it (the base name of the string).
    */
  def aliasBases(aliases: DataFrame): DataFrame =
    aliases
      .select(F.array_min(F.col("names")).as("groupMin"), F.explode(F.col("names")).as("aliasName"))
      .groupBy("aliasName")
      .agg(F.min("groupMin").as("aliasBaseName"))

  /** The homonym groups of `names` (`group`, `baseId`, `name`): one row per (group, stem) where two
    * DIFFERENT names with that stem belong to DIFFERENT base identities (`bases >= 2` and
    * `names >= 2`; exact: some pair of such elements then exists). `unsuffixedVsSuffixed` is true
    * when the group holds an unsuffixed name (the stem itself) and a suffixed name of two different
    * base identities. Columns: `group, stem, bases, names, unsuffixedVsSuffixed`.
    */
  def homonymGroups(names: DataFrame, group: String): DataFrame =
    names
      .withColumn("stem", homonymStem(F.col("name")))
      .groupBy(group, "stem")
      .agg(
        F.countDistinct("baseId").as("bases"),
        F.countDistinct("name").as("names"),
        F.countDistinct(F.when(F.col("name") === F.col("stem"), F.col("baseId"))).as("unBases"),
        F.countDistinct(F.when(F.col("name") =!= F.col("stem"), F.col("baseId"))).as("sufBases"),
      )
      .where(F.col("bases") >= 2 && F.col("names") >= 2)
      .select(
        F.col(group),
        F.col("stem"),
        F.col("bases"),
        F.col("names"),
        (F.col("unBases") >= 1 && F.col("sufBases") >= 1).as("unsuffixedVsSuffixed"),
      )

  /** Same rule on a plain string (for tests and documentation). */
  def homonymStemOf(key: String): String =
    if java.util.regex.Pattern.compile(SuffixRegex).matcher(key).find() then
      key.replaceFirst(" [0-9]{4}$", "")
    else key

  /** @param mentions
    *   `InputSchemas.Mentions` (all sources)
    * @param aliases
    *   `InputSchemas.Aliases` (may be empty)
    * @param decisions
    *   `InputSchemas.Decisions` (any kind; only researcher decisions are used)
    * @param policyVersion
    *   match policy the decisions came from (recorded on every cluster)
    */
  def build(
      mentions: DataFrame,
      aliases: DataFrame,
      decisions: DataFrame,
      policyVersion: String,
      cfg: SparkConfig,
  ): Result =
    val spark = mentions.sparkSession
    Udfs.register(spark)
    val ck = Checkpointer(cfg.checkpointDir)

    val isDblp: Column = F.col("sourceType") === "dblp"

    // ---- alias groups: one base name per alias string -------------------------------------
    val aliasMin = aliasBases(aliases)

    // ---- one row per mention with its base identity ----------------------------------------
    val mb = mentions
      .join(
        F.broadcast(aliasMin),
        F.col("rawName") === F.col("aliasName") && isDblp,
        "left",
      )
      .select(
        F.col("mentionId"),
        F.col("rawName"),
        F.col("sourceType"),
        F.call_udf(Udfs.OrcidNormalize, F.col("orcid")).as("orcidValid"),
        F.when(isDblp, F.coalesce(F.col("aliasBaseName"), F.col("rawName"))).as("baseName"),
      )
      .withColumn(
        "baseId",
        F.coalesce(
          F.when(isDblp, F.call_udf(Udfs.DblpPerson, F.col("baseName"))),
          F.call_udf(Udfs.Unresolved, F.col("mentionId")),
        ),
      )

    // ---- validated ORCIDs per base identity (mentions plus alias records) -------------------
    val aliasOrcids = aliases
      .select(F.explode(F.col("names")).as("aliasName"), F.col("orcids"))
      .select(F.col("aliasName"), F.explode(F.col("orcids")).as("orc"))
      .join(aliasMin, "aliasName")
      .select(
        F.call_udf(Udfs.DblpPerson, F.col("aliasBaseName")).as("baseId"),
        F.call_udf(Udfs.OrcidNormalize, F.col("orc")).as("orcid"),
      )
    val memberOrcids = mb
      .select(F.col("baseId"), F.col("orcidValid").as("orcid"))
      .union(aliasOrcids)
      .where(F.col("orcid").isNotNull)
      .groupBy("baseId")
      .agg(F.sort_array(F.collect_set("orcid")).as("baseOrcids"))

    val baseTbl = mb
      .groupBy("baseId")
      .agg(
        F.max(F.when(isDblp, 1).otherwise(0)).as("isDblp"),
        F.sort_array(F.collect_list("mentionId")).as("mentionIds"),
        F.min(F.coalesce(F.col("baseName"), F.col("rawName"))).as("name"),
        F.min(F.col("baseName")).as("dblpKey"),
      )
      .join(memberOrcids, Seq("baseId"), "left")
      .withColumn(
        "baseOrcids",
        F.coalesce(F.col("baseOrcids"), F.array().cast("array<string>")),
      )

    // ---- decision links between base identities --------------------------------------------
    val epL                 = mb.select(F.col("mentionId").as("lId"), F.col("baseId").as("lBase"))
    val epR                 = mb.select(F.col("mentionId").as("rId"), F.col("baseId").as("rBase"))
    val researcherDecisions = decisions.where(F.col("entityKind") === "researcher")
    val joined              = researcherDecisions
      .join(epL, F.col("leftId") === F.col("lId"), "left")
      .join(epR, F.col("rightId") === F.col("rId"), "left")
    val unmapped = joined.where(F.col("lBase").isNull || F.col("rBase").isNull)
    val mapped   = joined.where(
      F.col("lBase").isNotNull && F.col("rBase").isNotNull && F.col("lBase") =!= F.col("rBase")
    )
    // M2: rejected / conflict decisions inside ONE base identity never block (4.1.1); listed instead.
    val withinBase = joined
      .where(
        F.col("lBase").isNotNull && F.col("rBase").isNotNull && F.col("lBase") === F.col("rBase") &&
          F.col("outcome").isin("rejected", "conflict")
      )
      .select(
        F.col("leftId"),
        F.col("rightId"),
        F.col("outcome"),
        F.col("lBase").as("baseIdentityId"),
      )
      .distinct()
      .join(
        baseTbl.select(F.col("baseId").as("baseIdentityId"), F.col("name").as("baseName")),
        "baseIdentityId",
      )
      .select("leftId", "rightId", "outcome", "baseIdentityId", "baseName")

    val accepted = mapped
      .where(F.col("outcome") === "accepted")
      .select(F.col("lBase").as("src"), F.col("rBase").as("dst"))
      .distinct()
    val arcs = accepted
      .select(F.lit(IdentityWindow).as("window"), F.col("src"), F.col("dst"))
      .union(accepted.select(F.lit(IdentityWindow).as("window"), F.col("dst"), F.col("src")))
    val cannot = mapped
      .where(F.col("outcome").isin("rejected", "conflict"))
      .select(
        F.least(F.col("lBase"), F.col("rBase")).as("a"),
        F.greatest(F.col("lBase"), F.col("rBase")).as("b"),
      )
      .distinct()

    // ---- candidate merges: connected components over ACCEPTED links ------------------------
    val nodes = baseTbl.select(F.lit(IdentityWindow).as("window"), F.col("baseId").as("id"))
    val (labels, conv) =
      Components.labels(nodes, arcs, cfg.connectedComponentsMaxIterations, ck)
    val compOf = ck(labels.select(F.col("id").as("baseId"), F.col("label").as("comp")))

    // ---- M5: cannot-link pairs and ORCID conflicts inside a component ----------------------
    val compL   = compOf.select(F.col("baseId").as("a"), F.col("comp").as("lComp"))
    val compR   = compOf.select(F.col("baseId").as("b"), F.col("comp").as("rComp"))
    val clPairs = cannot
      .join(compL, "a")
      .join(compR, "b")
      .where(F.col("lComp") === F.col("rComp"))
      .groupBy(F.col("lComp").as("comp"))
      .agg(F.count(F.lit(1)).as("pairs"))

    // M1: two or more different DBLP person keys with the same homonym stem in one component.
    // Repair 02 (R-M1): over EVERY DBLP name of each base identity (mention names, base name, alias
    // names), not only the smallest alias string. Explode, then groupBy: nothing is collected.
    val homonymGroupsPerComp = Identity.homonymGroups(
      compOf
        .join(dblpNames(mentions, aliases), "baseId")
        .select(F.col("comp"), F.col("baseId"), F.col("name")),
      "comp",
    )
    val homonymGroups = homonymGroupsPerComp
      .groupBy("comp")
      .agg(
        F.count(F.lit(1)).as("homonymGroups"),
        F.sum(F.when(F.col("unsuffixedVsSuffixed"), 1L).otherwise(0L)).as("unsuffixedVsSuffixed"),
      )

    val compInfo = compOf
      .join(baseTbl.select("baseId", "baseOrcids"), "baseId")
      .groupBy("comp")
      .agg(
        F.count(F.lit(1)).as("size"),
        F.sort_array(F.collect_list("baseId")).as("members"),
        F.size(F.array_distinct(F.flatten(F.collect_list("baseOrcids")))).as("nOrcids"),
      )
      .join(clPairs, Seq("comp"), "left")
      .join(homonymGroups, Seq("comp"), "left")
      .withColumn("pairs", F.coalesce(F.col("pairs"), F.lit(0L)))
      .withColumn("homonymGroups", F.coalesce(F.col("homonymGroups"), F.lit(0L)))
      .withColumn("unsuffixedVsSuffixed", F.coalesce(F.col("unsuffixedVsSuffixed"), F.lit(0L)))

    val blockedComps = compInfo.where(
      F.col("size") >= 2 &&
        (F.col("pairs") > 0 || F.col("nOrcids") > 1 || F.col("homonymGroups") > 0)
    )
    val blocked = blockedComps.select(
      F.col("comp").as("componentKey"),
      F.col("members").as("memberBaseIds"),
      F.col("size").cast("int").as("memberCount"),
      F.concat_ws(
        "+",
        F.when(F.col("pairs") > 0, "rejected_or_conflict_pair"),
        F.when(F.col("nOrcids") > 1, "two_orcids"),
        F.when(F.col("homonymGroups") > 0, "distinct_dblp_homonyms"),
      ).as("reason"),
      F.col("pairs").as("rejectedOrConflictPairs"),
      F.col("nOrcids").as("distinctOrcids"),
      F.col("homonymGroups").cast("int").as("distinctDblpHomonymGroups"),
      F.col("unsuffixedVsSuffixed").cast("int").as("unsuffixedVsSuffixedGroups"),
    )

    // ---- clusters --------------------------------------------------------------------------
    val keyed = compOf
      .join(blockedComps.select(F.col("comp"), F.lit(true).as("isBlocked")), Seq("comp"), "left")
      .select(
        F.col("baseId"),
        F.coalesce(F.col("isBlocked"), F.lit(false)).as("blockedFlag"),
        F.when(F.coalesce(F.col("isBlocked"), F.lit(false)), F.col("baseId"))
          .otherwise(F.col("comp"))
          .as("clusterKey"),
      )

    val agg = baseTbl
      .join(keyed, "baseId")
      .groupBy("clusterKey")
      .agg(
        F.sort_array(F.collect_list("baseId")).as("baseIdentityIds"),
        F.sort_array(F.collect_list(F.when(F.col("isDblp") === 1, F.col("baseId"))))
          .as("dblpBaseIds"),
        F.sort_array(F.collect_list(F.when(F.col("isDblp") === 1, F.col("dblpKey"))))
          .as("dblpPersonKeys"),
        F.sort_array(F.flatten(F.collect_list("mentionIds"))).as("memberMentionIds"),
        F.sort_array(F.array_distinct(F.flatten(F.collect_list("baseOrcids")))).as("orcids"),
        F.min(F.when(F.col("isDblp") === 1, F.col("name"))).as("dblpName"),
        F.min("name").as("anyName"),
        F.max("blockedFlag").as("blockedFlag"),
      )

    // Canonical ID: DBLP base ID of the smallest member key; no DBLP person: base / merged ID.
    val rid = F
      .when(
        F.size(F.col("dblpPersonKeys")) > 0,
        F.call_udf(Udfs.ClusterV2, F.col("dblpPersonKeys")),
      )
      .otherwise(F.call_udf(Udfs.ClusterNoOrcid, F.col("baseIdentityIds")))

    val clusters = agg
      .select(
        rid.as("researcherId"),
        F.col("memberMentionIds"),
        F.size(F.col("memberMentionIds")).as("memberCount"),
        F.col("baseIdentityIds"),
        F.col("dblpPersonKeys"),
        F.coalesce(F.col("dblpName"), F.col("anyName")).as("displayName"),
        F.when(F.size(F.col("orcids")) === 1, F.element_at(F.col("orcids"), 1)).as("orcid"),
        F.col("orcids"),
        F.when(F.col("blockedFlag"), "cannot_link_fallback")
          .when(F.size(F.col("baseIdentityIds")) > 1, "accepted_link_component")
          .when(F.size(F.col("dblpBaseIds")) === 0, "unresolved_singleton")
          .otherwise("dblp_person_exact")
          .as("ruleFired"),
        F.when(F.col("blockedFlag"), "blocked").otherwise("clear").as("cannotLinkStatus"),
        F.lit(policyVersion).as("policyVersion"),
      )
      .select(
        Tables.IdentityClusters.columns.map(F.col)*
      )

    val mentionResearcher = clusters
      .select(F.col("researcherId"), F.explode(F.col("memberMentionIds")).as("mentionId"))
      .join(
        mb.select(F.col("mentionId"), F.col("sourceType"), F.col("baseId").as("baseIdentityId")),
        "mentionId",
      )
      .select("mentionId", "researcherId", "sourceType", "baseIdentityId")

    // ORCIDs held by two or more clusters (both keep their DBLP IDs; counted, never merged).
    val sharedOrcids = clusters
      .select(F.col("researcherId"), F.explode(F.col("orcids")).as("orcid"))
      .groupBy("orcid")
      .agg(
        F.count(F.lit(1)).as("clusterCount"),
        F.sort_array(F.collect_list("researcherId")).as("researcherIds"),
      )
      .where(F.col("clusterCount") >= 2)

    val orcidEvidence = mb
      .where(F.col("orcidValid").isNotNull)
      .select(F.col("mentionId"), F.col("orcidValid").as("orcid"))
      .join(mentionResearcher.select("mentionId", "researcherId"), "mentionId")
      .groupBy("researcherId", "orcid")
      .agg(F.sort_array(F.collect_list("mentionId")).as("mentionIds"))

    Result(
      mentionResearcher,
      clusters,
      blocked,
      conv,
      unmapped.select("leftId", "rightId", "outcome"),
      sharedOrcids,
      withinBase,
      orcidEvidence,
    )
  end build

  /** Counts reported for a run. Small aggregates only. */
  final case class Counts(
      mentions: Long,
      dblpMentions: Long,
      baseIdentitiesDblp: Long,
      externalMentions: Long,
      clusters: Long,
      mergedClusters: Long,
      absorbedExternalClusters: Long,
      cannotLinkBlockedComponents: Long,
      cannotLinkBlockedBaseIdentities: Long,
      /** Blocked components by reason (a component can have several reasons, so they overlap). */
      blockedByRejectedOrConflict: Long,
      blockedByTwoOrcids: Long,
      blockedByDistinctDblpHomonyms: Long,
      /** Blocked components with a homonym group that pairs an unsuffixed name with a suffixed one
        * ("X" vs "X 0008", different base identities). It OVERLAPS the other counters: a component
        * with another reason, or with a second suffixed homonym ("X 0009"), is counted here too.
        */
      cannotLinkBlockedUnsuffixedVsSuffixed: Long,
      withinBaseRejectionsIgnored: Long,
      unresolvedSingletons: Long,
      clustersWithOrcid: Long,
      clustersWithTwoOrMoreOrcids: Long,
      orcidsSharedByClusters: Long,
      clustersSharingAnOrcid: Long,
      unmappedDecisions: Long,
  ):
    /** Deprecated alias of `clustersSharingAnOrcid` (see `Result.orcidCollisions`). */
    def orcidCollisionClusters: Long = clustersSharingAnOrcid

  def counts(r: Result): Counts =
    val mr                     = r.mentionResearcher
    val c                      = r.clusters
    def n(df: DataFrame): Long = DriverCollect.count(df)
    val perCluster             = mr
      .groupBy("researcherId")
      .agg(
        F.countDistinct(F.when(F.col("sourceType") === "dblp", F.col("baseIdentityId")))
          .as("dblpBases"),
        F.count(F.when(F.col("sourceType") =!= "dblp", 1)).as("externals"),
      )
    val shared = r.sharedOrcids
    Counts(
      mentions = n(mr),
      dblpMentions = n(mr.where(F.col("sourceType") === "dblp")),
      baseIdentitiesDblp = n(
        mr.where(F.col("sourceType") === "dblp").select("baseIdentityId").distinct()
      ),
      externalMentions = n(mr.where(F.col("sourceType") =!= "dblp")),
      clusters = n(c),
      mergedClusters = n(perCluster.where(F.col("dblpBases") >= 2)),
      absorbedExternalClusters = n(
        perCluster.where(F.col("dblpBases") >= 1 && F.col("externals") >= 1)
      ),
      cannotLinkBlockedComponents = n(r.blocked),
      cannotLinkBlockedBaseIdentities = n(c.where(F.col("cannotLinkStatus") === "blocked")),
      blockedByRejectedOrConflict = n(r.blocked.where(F.col("rejectedOrConflictPairs") > 0)),
      blockedByTwoOrcids = n(r.blocked.where(F.col("distinctOrcids") > 1)),
      blockedByDistinctDblpHomonyms = n(r.blocked.where(F.col("distinctDblpHomonymGroups") > 0)),
      cannotLinkBlockedUnsuffixedVsSuffixed =
        n(r.blocked.where(F.col("unsuffixedVsSuffixedGroups") > 0)),
      withinBaseRejectionsIgnored = n(r.withinBaseRejections),
      unresolvedSingletons = n(c.where(F.col("ruleFired") === "unresolved_singleton")),
      clustersWithOrcid = n(c.where(F.size(F.col("orcids")) >= 1)),
      clustersWithTwoOrMoreOrcids = n(c.where(F.size(F.col("orcids")) >= 2)),
      orcidsSharedByClusters = n(shared),
      clustersSharingAnOrcid = DriverCollect
        .rows(shared.agg(F.sum("clusterCount").cast("long")), 1)
        .headOption
        .flatMap(row => if row.isNullAt(0) then None else Some(row.getLong(0)))
        .getOrElse(0L),
      unmappedDecisions = n(r.unmappedDecisions),
    )
end Identity
