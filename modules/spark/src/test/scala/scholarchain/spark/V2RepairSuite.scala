package scholarchain.spark

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.functions as F

import scholarchain.core.*

/** The hand-checkable fixture of the Phase 06 repair 01 pipeline tests: the Phase 06 fixture plus
  *
  *   - Pam and Quo: seven joint works E01..E07 (2001-2004), so their edge has MORE than the five
  *     representative work IDs of the default bound (truncated edge);
  *   - Pam absorbs the crossref mention Xp (ORCID A): one ORCID, one RESOLVES_TO row;
  *   - Quo has an HJ1 alias record with two ORCIDs (B and C): `orcid` null, two RESOLVES_TO rows;
  *   - Rob and Sam each absorb a crossref mention carrying the SAME ORCID (S): two clusters, one
  *     ORCID, both keep their DBLP IDs and both point at the same target;
  *   - "Hom 0008" and "Hom 0009" are both accepted to the crossref mention Xh: a homonym block
  *     (M1);
  *   - Ada and Bob each have a REJECTED / CONFLICT decision between two of their own mentions (M2).
  */
object ExtFixture:
  import Fixture.{Work, mention, workId}

  /** The ORCID check digit (ISO 7064 mod 11-2), for a 15-digit base. */
  private def orcid(base15: String): String =
    val total = base15.foldLeft(0)((acc, c) => (acc + (c - '0')) * 2)
    val r     = (12 - total % 11) % 11
    val check = if r == 10 then "X" else r.toString
    val all   = base15 + check
    all.grouped(4).mkString("-")

  val OrcA: String = "0000-0002-1825-0097"
  val OrcB: String = "0000-0001-5109-3700"
  val OrcC: String = "0000-0002-1694-233X"
  val OrcS: String = orcid("000000010000000")

  val extraWorks: List[Work] = List(
    Work("E01", 2001, List("Pam", "Quo")),
    Work("E02", 2001, List("Pam", "Quo")),
    Work("E03", 2002, List("Pam", "Quo")),
    Work("E04", 2002, List("Pam", "Quo")),
    Work("E05", 2003, List("Pam", "Quo")),
    Work("E06", 2003, List("Pam", "Quo")),
    Work("E07", 2004, List("Pam", "Quo")),
    Work("R01", 2002, List("Rob")),
    Work("S01", 2002, List("Sam")),
    Work("H01", 2003, List("Hom 0008")),
    Work("H02", 2003, List("Hom 0009")),
  )

  private def w(key: String): Work  = extraWorks.find(x => x.key == key).get
  private def fw(key: String): Work = Fixture.works.find(x => x.key == key).get

  /** A crossref mention: (work key, name, ORCID). */
  final case class External(key: String, name: String, orcid: Option[String])
  val externals: List[External] = List(
    External("Xp", "P. Pam", Some(OrcA)),
    External("Xr", "R. Rob", Some(OrcS)),
    External("Xs", "S. Sam", Some(OrcS)),
    External("Xh", "H.", None),
  )
  def extMention(key: String): String = Ok.get(Ids.mentionId(s"work:crossref:$key", 0))

  private val pam0 = mention(w("E01"), 0)
  private val rob0 = mention(w("R01"), 0)
  private val sam0 = mention(w("S01"), 0)
  private val hom8 = mention(w("H01"), 0)
  private val hom9 = mention(w("H02"), 0)
  val ada1: String = mention(fw("W01"), 0)
  val ada2: String = mention(fw("W10"), 0)
  val bob1: String = mention(fw("W01"), 1)
  val bob2: String = mention(fw("W10"), 1)

  private val newDecisions: List[(String, String, String)] = List(
    (pam0, extMention("Xp"), "accepted"),
    (rob0, extMention("Xr"), "accepted"),
    (sam0, extMention("Xs"), "accepted"),
    (hom8, extMention("Xh"), "accepted"),
    (extMention("Xh"), hom9, "accepted"),
    (ada1, ada2, "rejected"),
    (bob1, bob2, "conflict"),
  )

  def loaded(spark: SparkSession): Inputs.LoadedInputs =
    val b = Fixture.loaded(spark)
    def add(df: DataFrame, schema: org.apache.spark.sql.types.StructType, rows: Seq[Row]) =
      df.union(TestSpark.frame(schema, rows))

    val mentionRows = extraWorks.flatMap(x =>
      x.authors.zipWithIndex.map((n, p) => Row(mention(x, p), workId(x.key), p, n, null, "dblp"))
    ) ++ externals.map(e =>
      Row(extMention(e.key), s"work:crossref:${e.key}", 0, e.name, e.orcid.orNull, "crossref")
    )
    val authorshipRows = extraWorks.flatMap(x =>
      x.authors.indices.map(p => Row(mention(x, p), workId(x.key), p, x.year))
    )
    val pairRows = extraWorks.flatMap { x =>
      x.authors.indices.map(mention(x, _)).sorted.combinations(2).toList.map { p =>
        Row(workId(x.key), x.year, p(0), p(1))
      }
    }
    val workRows =
      extraWorks.map(x =>
        Row(workId(x.key), s"Title ${x.key}", x.year, null, "venue:dblp:conf/t", Seq.empty[String])
      )
    val teamRows = extraWorks.map(x =>
      Row(workId(x.key), x.year, x.authors.size, x.authors.indices.map(mention(x, _)).sorted.toSeq)
    )
    val decisionRows = newDecisions.map((l, r, o) => Row(l, r, "researcher", o, Fixture.Policy))
    val sourceOf     = (id: String) =>
      if externals.exists(e => extMention(e.key) == id) then "crossref" else "dblp"
    val viewRows = newDecisions
      .flatMap((l, r, _) => List(l, r))
      .distinct
      .map(id => Row(id, "researcher", sourceOf(id)))
    b.copy(
      mentions = add(b.mentions, InputSchemas.Mentions, mentionRows),
      authorship = add(b.authorship, InputSchemas.Authorship, authorshipRows),
      pairs = add(b.pairs, InputSchemas.PairCandidates, pairRows),
      works = add(b.works, InputSchemas.Works, workRows),
      teams = add(b.teams, InputSchemas.Teams, teamRows),
      aliases = TestSpark.frame(
        InputSchemas.Aliases,
        List(Row("key", Seq("Quo"), Seq(OrcB, OrcC))),
      ),
      decisions = add(b.decisions, InputSchemas.Decisions, decisionRows),
      entityViews = add(b.entityViews, InputSchemas.EntityViews, viewRows),
      rowCounts = b.rowCounts ++ Map(
        "author-mentions"       -> (b.rowCounts("author-mentions") + mentionRows.size),
        "authorship"            -> (b.rowCounts("authorship") + authorshipRows.size),
        "pair-candidates"       -> (b.rowCounts("pair-candidates") + pairRows.size),
        "works"                 -> (b.rowCounts("works") + workRows.size),
        "teams"                 -> (b.rowCounts("teams") + teamRows.size),
        "match-decisions-final" -> (b.rowCounts("match-decisions-final") + decisionRows.size),
      ),
    )

/** Two runs of the extended fixture (computed once per JVM, lazily). */
object ExtRun:
  import TestSpark.spark

  lazy val cfg: SparkConfig = TestSpark.config(TestSpark.useCheckpointFor("ext"))
  lazy val runId: String    =
    Ok.get(Ids.runId("spark-analytics", "test", 1, Map("fixture" -> "ext"), Nil))
  lazy val inputs: Inputs.LoadedInputs = ExtFixture.loaded(spark)

  lazy val computed: Computed      = Pipeline.compute(spark, cfg, inputs, runId)
  lazy val computedAgain: Computed =
    computed
    Pipeline.compute(spark, cfg, ExtFixture.loaded(spark), runId)

  private lazy val cache = scala.collection.concurrent.TrieMap.empty[String, List[Row]]

  def rows(table: String): List[Row] =
    cache.getOrElseUpdate(table, computed.tables(table).collect().toList)
  def rows(table: String, window: String): List[Row] = rows(table).filter(_.s("window") == window)

/** Phase 06 repair 01 at pipeline level: the identity decisions (M1, M2), the ID rule, ORCID as a
  * property plus `RESOLVES_TO`, the v2 export contract, the metrics join, the load manifests and
  * determinism of the new tables. All expected values are written out by hand (see `ExtFixture`).
  */
class V2RepairSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")

  import ExtFixture.{OrcA, OrcB, OrcC, OrcS}
  import Fixture.{rid, workId, W1, All}

  private def ext(name: String): String =
    Ok.get(Ids.researcherIdUnresolved(ExtFixture.extMention(name)))
  private def prov(json: String): Provenance = Wire.read[Provenance](json) match
    case Right(p)  => p
    case Left(err) => fail(s"provenance does not parse: $err")

  private def sha(fields: Seq[String]): String =
    val out = ByteArrayOutputStream()
    val dos = DataOutputStream(out)
    ("scholarchain.coauthor-works.v1" +: fields).foreach { f =>
      val b = f.getBytes(StandardCharsets.UTF_8)
      dos.writeInt(b.length)
      dos.write(b)
    }
    dos.flush()
    MessageDigest
      .getInstance("SHA-256")
      .digest(out.toByteArray)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** A table row as the JSON of its record: every column except `window`, `provenance` parsed. */
  private def rowJson(r: Row, spec: Tables.Spec): String =
    def v(x: Any): ujson.Value = x match
      case null                       => ujson.Null
      case s: String                  => ujson.Str(s)
      case i: Int                     => ujson.Num(i.toDouble)
      case l: Long                    => ujson.Num(l.toDouble)
      case d: Double                  => ujson.Num(d)
      case b: Boolean                 => ujson.Bool(b)
      case q: scala.collection.Seq[?] => ujson.Arr.from(q.map(v))
      case other                      => fail(s"unexpected cell type ${other.getClass}")
    val o = ujson.Obj()
    spec.columns.filterNot(_ == "window").foreach { f =>
      val x = r.get(r.fieldIndex(f))
      o(f) = if f == "provenance" then ujson.read(x.asInstanceOf[String]) else v(x)
    }
    ujson.write(o)

  private val pamId = rid("Pam"); private val quoId     = rid("Quo")
  private val robId = rid("Rob"); private val samId     = rid("Sam")
  private val h8Id  = rid("Hom 0008"); private val h9Id = rid("Hom 0009")

  private def clusterRows =
    ExtRun.rows("identity-clusters").map(r => r.s("researcherId") -> r).toMap

  // ---- identity at pipeline level (M1, M2, ID rule, ORCID) ----------------------------------

  test("the extended fixture's ORCIDs are valid") {
    List(OrcA, OrcB, OrcC, OrcS).foreach(o => assertEquals(Orcid.parse(o), Right(o)))
    assertEquals(Set(OrcA, OrcB, OrcC, OrcS).size, 4)
  }

  test(
    "ID rule: DBLP base IDs for unmerged persons, no canonical ORCID ID, external singleton unresolved"
  ) {
    val cs = clusterRows
    assert(!cs.keySet.exists(_.startsWith("researcher:orcid:")), cs.keySet.toString)
    List("Pam", "Quo", "Rob", "Sam", "Hom 0008", "Hom 0009", "Ada", "Bob").foreach { n =>
      assert(cs.contains(rid(n)), n)
      assertEquals(cs(rid(n)).strs("dblpPersonKeys"), List(n))
    }
    // Pam absorbed the ORCID-carrying external and still has the DBLP base ID
    assertEquals(cs(pamId).i("memberCount"), 8) // 7 DBLP mentions + the crossref mention Xp
    assertEquals(cs(pamId).s("ruleFired"), "accepted_link_component")
    // the homonym-blocked external keeps researcherIdUnresolved (it fell back to its base identity)
    assertEquals(cs(ext("Xh")).s("ruleFired"), "cannot_link_fallback")
    assertEquals(cs(ext("Xh")).strs("dblpPersonKeys"), Nil)
    assertEquals(cs(ext("Xh")).strs("memberMentionIds"), List(ExtFixture.extMention("Xh")))
    assertEquals(cs.size, 20)
  }

  test("ORCID: one ORCID -> orcid set, orcids = [it]; a base identity with two -> orcid null") {
    val cs = clusterRows
    assertEquals(cs(pamId).s("orcid"), OrcA)
    assertEquals(cs(pamId).strs("orcids"), List(OrcA))
    assert(cs(quoId).isNull("orcid"), cs(quoId).toString)
    assertEquals(cs(quoId).strs("orcids"), List(OrcB, OrcC).sorted)
    assertEquals(cs(quoId).i("memberCount"), 7) // E01..E07, never split, no externals
    assertEquals(cs(robId).s("orcid"), OrcS)
    assertEquals(cs(samId).s("orcid"), OrcS)
    // the same properties on the researchers table
    val rs = ExtRun.rows("researchers").map(r => r.s("researcherId") -> r).toMap
    assertEquals(rs(pamId).s("orcid"), OrcA)
    assertEquals(rs(pamId).strs("orcids"), List(OrcA))
    assert(rs(quoId).isNull("orcid"))
    assertEquals(rs(quoId).strs("orcids"), List(OrcB, OrcC).sorted)
    assertEquals(rs.size, 19) // the external singleton is not a researcher node
    assert(!rs.contains(ext("Xh")))
  }

  test(
    "RESOLVES_TO: one row per (researcher, ORCID), valid ORCID targets, evidence and provenance"
  ) {
    val rows = ExtRun.rows("resolves-to")
    assertEquals(
      rows.map(r => (r.s("sourceId"), r.s("orcid"), r.s("targetId"))).toSet,
      Set(
        (pamId, OrcA, "researcher:orcid:" + OrcA),
        (quoId, OrcB, "researcher:orcid:" + OrcB),
        (quoId, OrcC, "researcher:orcid:" + OrcC),
        (robId, OrcS, "researcher:orcid:" + OrcS),
        (samId, OrcS, "researcher:orcid:" + OrcS),
      ),
    )
    assertEquals(rows.size, 5)
    assertEquals(rows.map(_.s("edgeType")).distinct, List(GraphEdgeTypes.ResolvesTo))
    rows.foreach { r =>
      assertEquals(Ids.researcherIdFromOrcid(r.s("orcid")), Right(r.s("targetId")))
      assertEquals(Ids.checkResearcherId(r.s("targetId")), Right(r.s("targetId")))
      assert(Ids.checkResearcherId(r.s("sourceId")).isRight)
      assert(!r.s("targetId").startsWith("researcher:sc1:"))
      assertEquals(prov(r.s("provenance")).runId, Some(ExtRun.runId))
    }
    // evidence mentions: the mention that carried the ORCID; none for an alias-only ORCID
    def ev(src: String, o: String) =
      rows.find(r => r.s("sourceId") == src && r.s("orcid") == o).get.strs("evidenceMentionIds")
    assertEquals(ev(pamId, OrcA), List(ExtFixture.extMention("Xp")))
    assertEquals(ev(robId, OrcS), List(ExtFixture.extMention("Xr")))
    assertEquals(ev(samId, OrcS), List(ExtFixture.extMention("Xs")))
    assertEquals(ev(quoId, OrcB), Nil)
    assertEquals(ev(quoId, OrcC), Nil)
    // two researchers share OrcS: both have an edge to the same target
    assertEquals(
      rows.filter(_.s("orcid") == OrcS).map(_.s("sourceId")).sorted,
      List(robId, samId).sorted,
    )
  }

  test("M1 at pipeline level: Hom 0008 + external + Hom 0009 is blocked, members keep base IDs") {
    val blocked = ExtRun.rows("cannot-link-blocked")
    assertEquals(blocked.size, 1)
    assertEquals(blocked.head.s("reason"), "distinct_dblp_homonyms")
    assertEquals(blocked.head.i("distinctDblpHomonymGroups"), 1)
    assertEquals(blocked.head.l("rejectedOrConflictPairs"), 0L)
    assertEquals(blocked.head.strs("memberBaseIds"), List(h8Id, h9Id, ext("Xh")).sorted)
    val cs = clusterRows
    List(h8Id, h9Id, ext("Xh")).foreach { id =>
      assertEquals(cs(id).s("cannotLinkStatus"), "blocked", id)
      assertEquals(cs(id).s("ruleFired"), "cannot_link_fallback", id)
    }
    val n = ExtRun.computed.identityCounts
    assertEquals(
      (n.cannotLinkBlockedComponents, n.cannotLinkBlockedBaseIdentities),
      (1L, 3L),
    )
    assertEquals(
      (n.blockedByDistinctDblpHomonyms, n.blockedByRejectedOrConflict, n.blockedByTwoOrcids),
      (1L, 0L, 0L),
    )
  }

  test("M2 at pipeline level: within-base REJECTED and CONFLICT are ignored, listed and counted") {
    val listed = ExtRun.rows("within-base-rejections-ignored")
    assertEquals(
      listed
        .map(r =>
          (r.s("leftId"), r.s("rightId"), r.s("outcome"), r.s("baseIdentityId"), r.s("baseName"))
        )
        .toSet,
      Set(
        (ExtFixture.ada1, ExtFixture.ada2, "rejected", rid("Ada"), "Ada"),
        (ExtFixture.bob1, ExtFixture.bob2, "conflict", rid("Bob"), "Bob"),
      ),
    )
    assertEquals(ExtRun.computed.identityCounts.withinBaseRejectionsIgnored, 2L)
    // not split, not blocked: Ada and Bob keep both mentions and a clear status
    val cs = clusterRows
    assertEquals(cs(rid("Ada")).i("memberCount"), 2)
    assertEquals(cs(rid("Ada")).s("cannotLinkStatus"), "clear")
    assertEquals(cs(rid("Bob")).i("memberCount"), 2)
    assertEquals(cs(rid("Bob")).s("cannotLinkStatus"), "clear")
    // the only blocked component is the homonym one
    assertEquals(ExtRun.rows("cannot-link-blocked").size, 1)
  }

  test("M2: the run summary counts the ignored rejections and carries a sample of them") {
    val invariants = Invariants.check(ExtRun.computed, ExtRun.cfg, ExtRun.inputs)
    val summary    = Report.build(ExtRun.computed, ExtRun.cfg, ExtRun.inputs, invariants, Nil)
    val id         = summary("identity")
    assertEquals(id("withinBaseRejectionsIgnored").num.toLong, 2L)
    val sample = id("withinBaseRejectionsIgnoredSample").arr.toList
    assertEquals(sample.size, 2)
    assertEquals(
      sample.map(o => o("outcome").str).sorted,
      List("conflict", "rejected"),
    )
    assertEquals(id("cannotLinkBlockedComponents").num.toLong, 1L)
    assertEquals(id("cannotLinkBlockedByReason")("distinct_dblp_homonyms").num.toLong, 1L)
    assertEquals(id("cannotLinkBlockedByReason")("rejected_or_conflict_pair").num.toLong, 0L)
    assertEquals(id("resolvesToEdges").num.toLong, 5L)
    assertEquals(id("researchersWithTwoOrMoreOrcids").num.toLong, 1L)
  }

  test("identity counts of the extended fixture, by hand") {
    val n = ExtRun.computed.identityCounts
    assertEquals(n.mentions, 48L) // 26 + 18 new DBLP + 4 crossref
    assertEquals(n.dblpMentions, 44L)
    assertEquals(n.baseIdentitiesDblp, 19L) // 13 + Pam Quo Rob Sam Hom0008 Hom0009
    assertEquals(n.externalMentions, 4L)
    assertEquals(n.clusters, 20L) // 19 DBLP persons + the blocked external Xh
    assertEquals(n.mergedClusters, 0L)
    assertEquals(n.absorbedExternalClusters, 3L) // Pam, Rob, Sam
    assertEquals(
      n.unresolvedSingletons,
      0L,
    )                                     // Xh is a blocked fallback, not an unresolved_singleton
    assertEquals(n.clustersWithOrcid, 4L) // Pam, Quo, Rob, Sam
    assertEquals(n.clustersWithTwoOrMoreOrcids, 1L) // Quo
    assertEquals(n.orcidsSharedByClusters, 1L)      // OrcS
    assertEquals(n.clustersSharingAnOrcid, 2L)      // Rob and Sam
    assertEquals(n.unmappedDecisions, 0L)
    assertEquals(
      Invariants
        .observations(ExtRun.computed)
        .toMap
        .view
        .filterKeys(k =>
          Set(
            "withinBaseRejectionsIgnored",
            "orcidsSharedByMultipleClusters",
            "clustersWithTwoOrMoreOrcids",
          )(k)
        )
        .toMap,
      Map(
        "withinBaseRejectionsIgnored"    -> 2L,
        "orcidsSharedByMultipleClusters" -> 1L,
        "clustersWithTwoOrMoreOrcids"    -> 1L,
      ),
    )
  }

  test("every output invariant holds on the extended fixture (the run does not fail)") {
    val inv     = Invariants.check(ExtRun.computed, ExtRun.cfg, ExtRun.inputs)
    val failing = inv.filterNot(_.ok).map(i => s"${i.name}: ${i.detail}")
    assertEquals(failing, Nil)
    assert(inv.size >= 40, inv.size.toString)
    val byName = inv.map(i => i.name -> i).toMap
    List(
      "m5_no_rejected_or_conflict_inside_a_cluster",
      "identity_canonical_id_is_smallest_dblp_key",
      "identity_no_orcid_canonical_ids",
      "identity_no_distinct_dblp_homonyms_in_cluster",
      "coauthor_edges_validate_as_core_v2",
      "researcher_metrics_validate_as_core_v2",
      "resolves_to_rows_equal_researcher_orcids",
      "coauthor_edge_works_rows_equal_joint_publications",
    ).foreach(n => assert(byName.get(n).exists(_.ok), n))
  }

  // ---- the v2 export contract ----------------------------------------------------------------

  test("every exported coauthor-edges row validates as CoauthorEdgeV2") {
    val rows = ExtRun.rows("coauthor-edges")
    assert(rows.size > 20, rows.size.toString)
    rows.foreach { r =>
      val e = Wire.read[CoauthorEdgeV2](rowJson(r, Tables.CoauthorEdges)) match
        case Right(e)  => e
        case Left(err) =>
          fail(s"edge ${r.s("edgeId")} (${r.s("window")}) is not a CoauthorEdgeV2: $err")
      assertEquals(GraphWindow.label(GraphWindow(e.windowStart, e.windowEnd)), r.s("window"))
      assertEquals(e.evidencePartition, r.s("window"))
      assertEquals(e.evidenceTable, "coauthor-edge-works")
      assertEquals(e.evidenceKey, e.edgeId)
      assertEquals(e.provenance.sourceType, SourceType.ScholarChain)
      assertEquals(e.provenance.runId, Some(ExtRun.runId))
      assert(e.provenance.inputs.nonEmpty)
    }
    assertEquals(rows.size.toLong, ExtRun.computed.tables("coauthor-edges").count())
  }

  test("every exported researcher-metrics row validates as ResearcherMetricsV2") {
    val rows = ExtRun.rows("researcher-metrics")
    assert(rows.size > 20, rows.size.toString)
    rows.foreach { r =>
      val m = Wire.read[ResearcherMetricsV2](rowJson(r, Tables.ResearcherMetrics)) match
        case Right(m)  => m
        case Left(err) => fail(s"metrics ${r.s("researcherId")} (${r.s("window")}) invalid: $err")
      assertEquals(GraphWindow.label(GraphWindow(m.windowStart, m.windowEnd)), r.s("window"))
      assertEquals(m.tieStrengthWeightedDegree, None) // not computed in Phase 06
      assertEquals(m.provenance.runId, Some(ExtRun.runId))
    }
    assertEquals(rows.size.toLong, ExtRun.computed.tables("researcher-metrics").count())
  }

  test(
    "the Pam-Quo edge has 7 joint works: truncated, 5 bounded representatives, full list in coauthor-edge-works"
  ) {
    val bound = ExtRun.cfg.representativeWorkIdsBound
    assertEquals(bound, 5)
    val all7 = List("E01", "E02", "E03", "E04", "E05", "E06", "E07").map(workId)
    val a    = List(pamId, quoId).min; val b = List(pamId, quoId).max
    List(W1, All).foreach { w =>
      val edge = ExtRun
        .rows("coauthor-edges", w)
        .find(r => r.s("researcherA") == a && r.s("researcherB") == b)
        .getOrElse(fail(s"no Pam-Quo edge in $w"))
      assertEquals(edge.i("jointPublicationCount"), 7, w)
      assertEquals(edge.b("supportingWorksTruncated"), true, w)
      assertEquals(edge.strs("representativeWorkIds"), all7.take(bound), w) // first five, sorted
      assertEquals(edge.s("supportingWorksDigest"), sha(all7), w)
      assertEquals(
        (edge.i("firstCollaborationYear"), edge.i("lastCollaborationYear")),
        (2001, 2004),
        w,
      )
      assertEquals(edge.s("evidenceTable"), "coauthor-edge-works")
      assertEquals(edge.s("evidenceKey"), edge.s("edgeId"))
      assertEquals(edge.s("evidencePartition"), w)
      assertEquals(
        edge.s("edgeId"),
        Ok.get(
          Ids.coauthorEdgeId(
            pamId,
            quoId,
            GraphWindow(edge.i("windowStart"), edge.i("windowEnd")),
            Fixture.GraphPolicy,
          )
        ),
        w,
      )
      // the full evidence: one row per joint work, count == jointPublicationCount, digest equal
      val ev = ExtRun.rows("coauthor-edge-works", w).filter(_.s("edgeId") == edge.s("edgeId"))
      assertEquals(ev.size, edge.i("jointPublicationCount"), w)
      assertEquals(ev.map(_.s("workId")).sorted, all7, w)
      assertEquals(sha(ev.map(_.s("workId")).sorted), edge.s("supportingWorksDigest"), w)
    }
    // the same edge does not exist in 2005-2009
    assert(!ExtRun.rows("coauthor-edges", Fixture.W2).exists(_.s("researcherA") == a))
  }

  test(
    "a non-truncated edge (Jo-Kit, 2000-2004: 2 works) lists all its works and its digest covers them"
  ) {
    val a = List(rid("Jo"), rid("Kit")).min; val b = List(rid("Jo"), rid("Kit")).max
    val e = ExtRun
      .rows("coauthor-edges", W1)
      .find(r => r.s("researcherA") == a && r.s("researcherB") == b)
      .get
    assertEquals(e.i("jointPublicationCount"), 2)
    assertEquals(e.b("supportingWorksTruncated"), false)
    assertEquals(e.strs("representativeWorkIds"), List("W07", "W08").map(workId))
    assertEquals(e.s("supportingWorksDigest"), sha(List("W07", "W08").map(workId)))
  }

  test(
    "for EVERY edge: truncated <=> representatives < joint count, bounded, evidence rows == count, digest equal"
  ) {
    val bound = ExtRun.cfg.representativeWorkIdsBound
    val works = ExtRun.rows("coauthor-edge-works").groupBy(r => (r.s("window"), r.s("edgeId")))
    val edges = ExtRun.rows("coauthor-edges")
    assertEquals(works.size, edges.size)
    edges.foreach { e =>
      val reps = e.strs("representativeWorkIds")
      val ev   = works((e.s("window"), e.s("edgeId"))).map(_.s("workId")).sorted
      assert(reps.size <= bound, e.s("edgeId"))
      assertEquals(
        e.b("supportingWorksTruncated"),
        reps.size < e.i("jointPublicationCount"),
        e.s("edgeId"),
      )
      assertEquals(reps, ev.take(bound), e.s("edgeId"))
      assertEquals(ev.size, e.i("jointPublicationCount"), e.s("edgeId"))
      assertEquals(sha(ev), e.s("supportingWorksDigest"), e.s("edgeId"))
    }
    assertEquals(
      ExtRun.rows("coauthor-edge-works").size.toLong,
      edges.map(_.i("jointPublicationCount").toLong).sum,
    )
  }

  test("negative control: a wrong edgeId is caught by V2Export and by the invariants") {
    val c        = ExtRun.computed
    val goodEdge = c.tables("coauthor-edges")
    assertEquals(V2Export.invalidRows(goodEdge, Tables.CoauthorEdges).count(), 0L)
    val a        = List(pamId, quoId).min
    val bad      = "edge:coauthor:" + "0" * 64
    val tampered = goodEdge.withColumn(
      "edgeId",
      F.when(
        F.col("window") === W1 && F.col("researcherA") === a && F.col(
          "jointPublicationCount"
        ) === 7,
        F.lit(bad),
      ).otherwise(F.col("edgeId")),
    )
    val invalid = V2Export.invalidRows(tampered, Tables.CoauthorEdges).collect().toList
    assertEquals(invalid.size, 1)
    assert(invalid.head.getString(0).contains("edgeId"), invalid.head.getString(0))
    val inv = Invariants.check(
      c.copy(tables = c.tables.updated("coauthor-edges", tampered)),
      ExtRun.cfg,
      ExtRun.inputs,
    )
    assert(!inv.find(_.name == "coauthor_edges_validate_as_core_v2").get.ok)
  }

  test(
    "negative control: a participationCoefficient above 1 is caught by V2Export and by the invariants"
  ) {
    val c      = ExtRun.computed
    val metric = c.tables("researcher-metrics")
    assertEquals(V2Export.invalidRows(metric, Tables.ResearcherMetrics).count(), 0L)
    val tampered = metric.withColumn(
      "participationCoefficient",
      F.when(F.col("window") === W1 && F.col("researcherId") === pamId, F.lit(1.5))
        .otherwise(F.col("participationCoefficient")),
    )
    val invalid = V2Export.invalidRows(tampered, Tables.ResearcherMetrics).collect().toList
    assertEquals(invalid.size, 1)
    assert(invalid.head.getString(0).contains(pamId), invalid.head.getString(0))
    assert(
      invalid.head.getString(0).contains("participationCoefficient"),
      invalid.head.getString(0),
    )
    val inv = Invariants.check(
      c.copy(tables = c.tables.updated("researcher-metrics", tampered)),
      ExtRun.cfg,
      ExtRun.inputs,
    )
    assert(!inv.find(_.name == "researcher_metrics_validate_as_core_v2").get.ok)
  }

  test(
    "negative control: a joint count that disagrees with the evidence rows is caught by an invariant"
  ) {
    // jointPublicationCount 8 stays a valid CoauthorEdgeV2 on its own (still truncated), so only
    // the cross-table evidence invariant can see it
    val c        = ExtRun.computed
    val a        = List(pamId, quoId).min
    val tampered = c
      .tables("coauthor-edges")
      .withColumn(
        "jointPublicationCount",
        F.when(
          F.col("window") === W1 && F.col("researcherA") === a && F
            .col("jointPublicationCount") === 7,
          F.lit(8),
        ).otherwise(F.col("jointPublicationCount")),
      )
    assertEquals(V2Export.invalidRows(tampered, Tables.CoauthorEdges).count(), 0L)
    val inv = Invariants.check(
      c.copy(tables = c.tables.updated("coauthor-edges", tampered)),
      ExtRun.cfg,
      ExtRun.inputs,
    )
    assert(!inv.find(_.name == "coauthor_edge_works_match_edges").get.ok)
  }

  // ---- metrics join ---------------------------------------------------------------------------

  test("metrics rows equal the source tables: bridges, community, component and coreness") {
    val metrics                       = ExtRun.rows("researcher-metrics")
    def keyed(t: String, col: String) =
      ExtRun
        .rows(t)
        .map(r => (r.s("window"), r.s("researcherId")) -> r.get(r.fieldIndex(col)))
        .toMap
    val comm                     = keyed("community-membership", "communityId")
    val comp                     = keyed("component-membership", "componentId")
    val core                     = keyed("coreness", "coreness")
    def bridge(m: BridgeMeasure) =
      ExtRun
        .rows("bridges")
        .filter(_.s("measure") == m.wire)
        .map(r => (r.s("window"), r.s("researcherId")) -> r.d("value"))
        .toMap
    val ccd = bridge(BridgeMeasure.CrossCommunityDegree)
    val pc  = bridge(BridgeMeasure.ParticipationCoefficient)
    val ct  = bridge(BridgeMeasure.DistinctCommunities)
    assertEquals(metrics.size, comm.size)
    metrics.foreach { r =>
      val k = (r.s("window"), r.s("researcherId"))
      assertEquals(r.s("communityId"), comm(k), k.toString)
      assertEquals(r.s("componentId"), comp(k), k.toString)
      assertEquals(r.i("coreness"), core(k), k.toString)
      assertEquals(r.l("crossCommunityDegree").toDouble, ccd(k), k.toString)
      assertEqualsDouble(r.d("participationCoefficient"), pc(k), 1e-12, k.toString)
      assertEquals(r.i("communitiesTouched").toDouble, ct(k), k.toString)
      assert(
        !r.isNull("communityId") && !r.isNull("componentId") && !r.isNull("coreness"),
        k.toString,
      )
    }
  }

  test("W_r is the sum of jointPublicationCount over incident edges (and C_r their number)") {
    val edges = ExtRun.rows("coauthor-edges")
    ExtRun.rows("researcher-metrics").foreach { m =>
      val inc = edges.filter(e =>
        e.s("window") == m.s("window") &&
          (e.s("researcherA") == m.s("researcherId") || e.s("researcherB") == m.s("researcherId"))
      )
      assertEquals(
        m.l("weightedDegree"),
        inc.map(_.i("jointPublicationCount").toLong).sum,
        m.s("researcherId"),
      )
      assertEquals(m.l("coauthorCount"), inc.size.toLong, m.s("researcherId"))
    }
  }

  test(
    "Pam and Quo, by hand: P=7 C=1 T=14 A=2 M=2 W=7, coreness 1, one shared community and component"
  ) {
    List(W1, All).foreach { w =>
      val ms = ExtRun.rows("researcher-metrics", w).map(r => r.s("researcherId") -> r).toMap
      List(pamId, quoId).foreach { id =>
        val r = ms(id)
        assertEquals(r.l("publicationCount"), 7L, w)
        assertEquals(r.l("coauthorCount"), 1L, w)
        assertEquals(r.l("teamSizeTotal"), 14L, w)
        assertEqualsDouble(r.d("avgTeamSize"), 2.0, 1e-9, w)
        assertEquals(r.i("maxTeamSize"), 2, w)
        assertEquals(r.l("weightedDegree"), 7L, w) // one edge with 7 joint works
        assertEquals(r.i("maxTeamWorkCount"), 7, w)
        assertEquals(
          r.strs("maxTeamWorkIds"),
          List("E01", "E02", "E03", "E04", "E05").map(workId),
          w,
        )
        assertEquals(r.i("coreness"), 1, w)
        assertEquals((r.l("crossCommunityDegree"), r.i("communitiesTouched")), (0L, 1), w)
        assertEqualsDouble(r.d("participationCoefficient"), 0.0, 1e-12, w)
        assertEquals(r.i("schemaVersion"), 1)
        assertEquals(r.s("policyVersion"), Fixture.GraphPolicy)
        assert(r.isNull("tieStrengthWeightedDegree"))
      }
      assertEquals(ms(pamId).s("communityId"), ms(quoId).s("communityId"), w)
      assertEquals(ms(pamId).s("componentId"), ms(quoId).s("componentId"), w)
    }
  }

  // ---- load manifests -------------------------------------------------------------------------

  private lazy val manifests: Map[String, ujson.Obj] =
    LoadManifests.build(FixtureExports.a._2, FixtureExports.run)

  test("neptune manifest: the RESOLVES_TO edge entry and orcids on the researcher vertex") {
    val edges = manifests("neptune")("edges").arr.map(_.obj).toList
    val e     = edges.find(_("label").str == "RESOLVES_TO").getOrElse(fail("no RESOLVES_TO edge"))
    assertEquals(e("label").str, GraphEdgeTypes.ResolvesTo)
    assertEquals(e("table").str, "resolves-to")
    assertEquals(e("fromColumn").str, "sourceId")
    assertEquals(e("toColumn").str, "targetId")
    assertEquals(e("directed").bool, true)
    assertEquals(e("windowed").bool, false)
    assertEquals(e("keyColumns").arr.map(_.str).toList, List("sourceId", "targetId"))
    assertEquals(e("propertyColumns").arr.map(_.str).toList, List("orcid", "evidenceMentionIds"))
    assert(e("idColumn").isNull)
    val cols = Tables.ResolvesTo.columns.toSet
    assert(Set("sourceId", "targetId", "orcid", "evidenceMentionIds").subsetOf(cols))
    val researcher =
      manifests("neptune")("vertices").arr.map(_.obj).find(_("label").str == "Researcher").get
    val props = researcher("propertyColumns").arr.map(_.str).toList
    assert(props.contains("orcid") && props.contains("orcids"), props.toString)
    assert(props.forall(Tables.Researchers.columns.contains))
    // the edge list has exactly one more label than before repair 01
    assertEquals(edges.size, 8)
  }

  test(
    "manifests: coauthor-edge-works in dynamodb (edgeId, workId), coauthor-edges evidence properties"
  ) {
    val t = manifests("dynamodb")("tables").arr
      .map(_.obj)
      .find(_("table").str == "coauthor-edge-works")
      .get
    assertEquals(t("partitionKey").str, "edgeId")
    assertEquals(t("sortKey").arr.map(_.str).toList, List("workId"))
    val coauthor =
      manifests("neptune")("edges").arr.map(_.obj).find(_("label").str == "COAUTHORED_WITH").get
    val props = coauthor("propertyColumns").arr.map(_.str).toSet
    assert(
      Set(
        "evidenceTable",
        "evidenceKey",
        "evidencePartition",
        "supportingWorksTruncated",
        "supportingWorksDigest",
      )
        .subsetOf(props),
      props.toString,
    )
    val s3 = manifests("s3")("tables").arr.map(_.obj).map(_("table").str).toSet
    assert(
      Set("coauthor-edge-works", "resolves-to", "within-base-rejections-ignored").subsetOf(s3),
      s3.toString,
    )
    assertEquals(s3.size, 30)
  }

  // ---- determinism ----------------------------------------------------------------------------

  test("the new and changed tables are identical across two runs of the extended fixture") {
    val names = List(
      "coauthor-edge-works",
      "coauthor-edges",
      "researcher-metrics",
      "resolves-to",
      "within-base-rejections-ignored",
      "identity-clusters",
      "researchers",
      "cannot-link-blocked",
    )
    names.foreach { n =>
      val x = ExtRun.computed.tables(n); val y = ExtRun.computedAgain.tables(n)
      assertEquals(x.schema, y.schema, n)
      assert(x.count() > 0L, s"$n is empty: the comparison would be vacuous")
      assertEquals(x.count(), y.count(), n)
      assertEquals(x.exceptAll(y).count(), 0L, s"$n: rows only in run 1")
      assertEquals(y.exceptAll(x).count(), 0L, s"$n: rows only in run 2")
    }
    assertEquals(ExtRun.computed.identityCounts, ExtRun.computedAgain.identityCounts)
  }
