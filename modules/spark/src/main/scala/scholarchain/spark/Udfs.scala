package scholarchain.spark

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.api.java.{UDF1, UDF2, UDF5}
import org.apache.spark.sql.types.DataTypes

import scholarchain.core.{GraphWindow, Hashing, Ids, Orcid}

/** Spark SQL functions that delegate to the stable ID and validation functions of `core`. They are
  * the only way identifiers are derived, so Spark output and `core` can never disagree. Scala 3
  * cannot use `functions.udf` (no Scala 2 type tags), so they are Java-style UDF classes.
  *
  * Call `Udfs.register(spark)` once per session (idempotent); every job function does so itself.
  */
object Udfs:
  val DblpPerson      = "sc_researcher_dblp_person"
  val Unresolved      = "sc_researcher_unresolved"
  val ClusterV2       = "sc_researcher_for_cluster_v2"
  val ClusterNoOrcid  = "sc_researcher_for_cluster_no_orcid"
  val OrcidNormalize  = "sc_orcid"
  val CoauthorEdgeId  = "sc_coauthor_edge_id"
  val TextHash        = "sc_text_hash"
  val WorksDigest     = "sc_works_digest"
  val ValidResearcher = "sc_valid_researcher_id"
  val ValidEdge       = "sc_valid_edge_id"

  /** Tag of the digest that points at the full supporting-work list of an aggregated edge. */
  val WorksDigestTag = "scholarchain.coauthor-works.v1"

  def register(spark: SparkSession): Unit =
    val u = spark.udf
    u.register(DblpPerson, new DblpPersonUdf, DataTypes.StringType)
    u.register(Unresolved, new UnresolvedUdf, DataTypes.StringType)
    u.register(ClusterV2, new ClusterV2Udf, DataTypes.StringType)
    u.register(ClusterNoOrcid, new ClusterNoOrcidUdf, DataTypes.StringType)
    u.register(OrcidNormalize, new OrcidUdf, DataTypes.StringType)
    u.register(CoauthorEdgeId, new EdgeIdUdf, DataTypes.StringType)
    u.register(TextHash, new TextHashUdf, DataTypes.StringType)
    u.register(WorksDigest, new WorksDigestUdf, DataTypes.StringType)
    u.register(ValidResearcher, new ValidResearcherUdf, DataTypes.BooleanType)
    u.register(ValidEdge, new ValidEdgeUdf, DataTypes.BooleanType)

final class DblpPersonUdf extends UDF1[String, String]:
  override def call(name: String): String =
    if name == null then null else Ids.researcherIdDblpPerson(name).toOption.orNull

final class UnresolvedUdf extends UDF1[String, String]:
  override def call(mentionId: String): String =
    if mentionId == null then null else Ids.researcherIdUnresolved(mentionId).toOption.orNull

/** `Ids.researcherIdForClusterV2(dblpPersonKeys)`: the DBLP base ID of the smallest member key (the
  * canonical researcher ID of a cluster that contains a DBLP person; ORCID is never used). Null for
  * an empty key list.
  */
final class ClusterV2Udf extends UDF1[scala.collection.Seq[String], String]:
  override def call(keys: scala.collection.Seq[String]): String =
    if keys == null || keys.isEmpty then null
    else Ids.researcherIdForClusterV2(keys.toList).toOption.orNull

/** Cluster WITHOUT a DBLP person (external mentions only): the single base ID, or the
  * merged-cluster ID of two or more base IDs. Never an ORCID ID.
  */
final class ClusterNoOrcidUdf extends UDF1[scala.collection.Seq[String], String]:
  override def call(baseIds: scala.collection.Seq[String]): String =
    if baseIds == null || baseIds.isEmpty then null
    else Ids.researcherIdForCluster(baseIds.toList, Nil).toOption.orNull

/** Canonical ORCID or null when the input is null or not a valid ORCID. */
final class OrcidUdf extends UDF1[String, String]:
  override def call(s: String): String =
    if s == null then null else Orcid.parse(s).toOption.orNull

final class EdgeIdUdf extends UDF5[String, String, Integer, Integer, String, String]:
  override def call(a: String, b: String, start: Integer, end: Integer, policy: String): String =
    if a == null || b == null || start == null || end == null || policy == null then null
    else
      Ids
        .coauthorEdgeId(a, b, GraphWindow(start.intValue, end.intValue), policy)
        .toOption
        .orNull

final class TextHashUdf extends UDF1[String, String]:
  override def call(text: String): String = if text == null then null else Hashing.textHash(text)

/** Digest of a sorted work-ID list: the stable pointer to the full supporting list of an edge. */
final class WorksDigestUdf extends UDF1[scala.collection.Seq[String], String]:
  override def call(ids: scala.collection.Seq[String]): String =
    if ids == null then null else Hashing.digest(Udfs.WorksDigestTag, ids.toList)

final class ValidResearcherUdf extends UDF1[String, java.lang.Boolean]:
  override def call(id: String): java.lang.Boolean =
    java.lang.Boolean.valueOf(id != null && Ids.checkResearcherId(id).isRight)

final class ValidEdgeUdf extends UDF1[String, java.lang.Boolean]:
  override def call(id: String): java.lang.Boolean =
    java.lang.Boolean.valueOf(id != null && Ids.checkCoauthorEdgeId(id).isRight)
