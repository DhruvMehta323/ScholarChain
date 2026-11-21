package scholarchain.hadoop

import java.nio.file.{Files, Path as JPath}

import scholarchain.core.*
import scholarchain.er.*

import UnitSupport.*

/** Repair 03 L1d: the reducer-side cap on an exact-identifier (`x1|...`) block, tested in-process.
  *
  * The integration suite counts members with `Blocking.keys`, which ignores `x1` keys, so for an
  * `x1` block it counts 0 and checks nothing (that file belongs to hadoop-engineer). Here the
  * members of a block are counted by the key function the mapper really routes with
  * (`Blocking.primary`, `Blocking.fallbacks` and `ErKeys.exactIdKeys`). All data is SYNTHETIC.
  */
class ErX1CapUnitSuite extends munit.FunSuite:
  private val Orcid = "0000-0002-1825-0097"
  private val X1Key = s"x1|orcid|$Orcid"

  private def tmp(): JPath =
    Files.createTempDirectory(repoRoot.resolve("modules/hadoop/target"), "er-x1-cap-")

  private def view(id: String, source: SourceType, name: String): EntityView =
    EntityView(
      id,
      EntityKind.Researcher,
      source,
      name = Some(name),
      position = Some(0),
      orcid = Some(Orcid),
      title = Some("Graph Neural Networks"),
      year = Some(2020),
    )

  // Four people sharing one (synthetic) ORCID. Their names differ, so no two of them share a primary
  // block key: the only block that holds all of them is the exact-identifier block.
  private val Four = List(
    view("mention:a1", SourceType.Dblp, "Ann Alpha"),
    view("mention:b1", SourceType.Crossref, "Bo Beta"),
    view("mention:c1", SourceType.Crossref, "Cy Gamma"),
    view("mention:d1", SourceType.OpenAlex, "Di Delta"),
  )

  private def js(v: EntityView): String = WirePickle.write(v)

  private def noOversize(): JPath =
    val d = tmp()
    Files.writeString(d.resolve("part-r-00000"), "")
    d

  private def noEvidence(): JPath =
    val d = tmp()
    Files.writeString(d.resolve("part-r-00000"), "")
    d

  /** Members of a block by the keys the mapper routes with (primary, fallbacks, exact-id). */
  private def members(vs: List[EntityView], key: String): Int =
    vs.count { v =>
      val keys = Blocking.primary(v).map(_.key).toList ++ Blocking.fallbacks(v).map(_.key) ++
        ErKeys.exactIdKeys(v)
      keys.contains(key)
    }

  /** Maps the views, groups by shuffle key and reduces every group; returns one Reduced per key. */
  private def pipeline(vs: List[EntityView], max: Int): Map[String, Reduced] =
    val (routed, _) = mapLines(
      new CandidateMapper,
      vs.map(js),
      extraConf = Map(ErKeys.OversizeKeysDir -> noOversize().toString),
    )
    val ctx = runCtx.copy(er = Map("er.max-block-size" -> max.toString))
    routed
      .groupMap(_._1)(_._2)
      .map((k, values) =>
        k -> reduce(
          new CandidateReducer,
          values.map(v => (k, v)),
          ctx,
          Map(ErKeys.EvidenceDir -> noEvidence().toString),
        )
      )

  test("the mapper routes all four views to the one exact-identifier key") {
    val (routed, _) = mapLines(
      new CandidateMapper,
      Four.map(js),
      extraConf = Map(ErKeys.OversizeKeysDir -> noOversize().toString),
    )
    assertEquals(routed.count(_._1 == s"X|$X1Key"), 4)
    assertEquals(members(Four, X1Key), 4, "the real key function counts the four members")
  }

  test("x1 over max-block-size (3): no candidate, listed in oversize-blocks with the true size") {
    val rs = pipeline(Four, 3)
    val x1 = rs(s"X|$X1Key")
    assertEquals(x1.table("candidate-stage"), Nil)
    assertEquals(x1.count(PipelineCounter.OVERSIZE_BLOCK), 1L)
    assertEquals(x1.count(PipelineCounter.CANDIDATE_STAGED), 0L)
    val row = x1.table("oversize-blocks").map((_, j) => ujson.read(j)).head
    assertEquals(row("blockKey").str, X1Key)
    assertEquals(row("members").num.toInt, 4)
    assertEquals(row("maxBlockSize").num.toInt, 3)
    // the listed size is exactly the count by the real key function, and it exceeds the cap
    assertEquals(row("members").num.toInt, members(Four, X1Key))
    assert(members(Four, X1Key) > 3)
  }

  test("x1 over the cap: across the whole pipeline no pair of the four views is ever staged") {
    val rs = pipeline(Four, 3)
    assertEquals(rs.values.flatMap(_.table("candidate-stage")).toList, Nil)
    assertEquals(rs.values.map(_.count(PipelineCounter.OVERSIZE_BLOCK)).sum, 1L)
    assertEquals(rs.values.map(_.count(PipelineCounter.CANDIDATE_STAGED)).sum, 0L)
  }

  test("x1 at exactly the cap (3 members) is processed: both cross-source pairs are staged") {
    val three = Four.take(3)
    assertEquals(members(three, X1Key), 3)
    val x1 = pipeline(three, 3)(s"X|$X1Key")
    assertEquals(x1.count(PipelineCounter.OVERSIZE_BLOCK), 0L)
    assertEquals(x1.table("oversize-blocks"), Nil)
    assertEquals(
      x1.table("candidate-stage").map(_._1),
      List("mention:a1|mention:b1", "mention:a1|mention:c1"),
    )
    x1.table("candidate-stage").foreach { (_, j) =>
      val c = ujson.read(j)
      assertEquals(c("blockKey").str, X1Key)
      assertEquals(c("features")("orcid_agreement").num, 1.0)
    }
  }

  test("x1: the reducer counts every member of a huge group but never stages a pair") {
    val ten = (1 to 10).toList.map(i =>
      view(
        f"mention:z$i%02d",
        if i == 1 then SourceType.Dblp else SourceType.Crossref,
        s"Name$i Surname$i",
      )
    )
    assertEquals(members(ten, X1Key), 10)
    val x1  = pipeline(ten, 3)(s"X|$X1Key")
    val row = x1.table("oversize-blocks").map((_, j) => ujson.read(j)).head
    assertEquals(row("members").num.toInt, 10)
    assertEquals(x1.table("candidate-stage"), Nil)
  }
