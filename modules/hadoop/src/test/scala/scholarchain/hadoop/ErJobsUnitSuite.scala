package scholarchain.hadoop

import java.nio.file.{Files, Path as JPath}

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.Text
import org.apache.hadoop.mapreduce.Reducer
import org.apache.hadoop.mapreduce.lib.input.FileSplit
import scholarchain.core.*
import scholarchain.er.*

import UnitSupport.*

/** UNIT tests of the HJ6 and HJ7 mappers and reducers, driven in-process (no job is launched). All
  * data is SYNTHETIC.
  */
class ErJobsUnitSuite extends munit.FunSuite:
  private val Orcid1 = "0000-0002-1825-0097"

  private def tmp(): JPath =
    Files.createTempDirectory(repoRoot.resolve("modules/hadoop/target"), "er-unit-")

  private def run(max: Int): RunContext =
    runCtx.copy(er = Map("er.max-block-size" -> max.toString))

  private def person(
      id: String,
      source: SourceType,
      name: String = "Wei Wang",
      year: Int = 2020,
      authors: List[String] = Nil,
      orcid: Option[String] = None,
  ): EntityView =
    EntityView(
      id,
      EntityKind.Researcher,
      source,
      name = Some(name),
      position = Some(0),
      orcid = orcid,
      title = Some("Graph Neural Networks"),
      year = Some(year),
      authors = authors,
    )

  private def js(v: EntityView): String = WirePickle.write(v)

  private def counter(m: Map[String, Long], c: PipelineCounter): Long = m.getOrElse(c.toString, 0L)

  private def jsonRows(r: Reduced, table: String): List[ujson.Value] =
    r.table(table).map((_, j) => ujson.read(j))

  // ---- HJ6a ---------------------------------------------------------------------------------

  private val ViewProv = prov

  private val WorkId = "work:doi:10.1000/v.x"

  private def workJson(title: String = "Graph Learning"): String =
    Wire
      .write(
        WorkRecord(
          SchemaVersion.Current,
          WorkId,
          title,
          2020,
          Some("10.1000/v.x"),
          None,
          Nil,
          ViewProv,
        )
      )
      .fold(e => fail(e.toString), identity)

  private def mentionJson(pos: Int, name: String, orcid: Option[String] = None): String =
    val id = Ids.mentionId(WorkId, pos).fold(e => fail(e.toString), identity)
    Wire
      .write(AuthorMention(SchemaVersion.Current, id, WorkId, pos, name, orcid, ViewProv))
      .fold(e => fail(e.toString), identity)

  test("HJ6a ViewSourceMapper: tags rows by their input directory and keys them by work id") {
    val works = mapLines(
      new ViewSourceMapper,
      List(workJson()),
      split = new FileSplit(new Path("/x/curated/works/part-0"), 0L, 0L, Array.empty[String]),
    )
    assertEquals(works._1, List((WorkId, "W\t" + workJson())))
    val line  = mentionJson(0, "Ann One")
    val ments = mapLines(
      new ViewSourceMapper,
      List(line),
      split =
        new FileSplit(new Path("/x/curated/author-mentions/part-0"), 0L, 0L, Array.empty[String]),
    )
    assertEquals(ments._1, List((WorkId, "M\t" + line)))
  }

  test("HJ6a ViewSourceMapper: a row without a work id or not JSON counts SCHEMA_FAILURE") {
    val (out, c) = mapLines(
      new ViewSourceMapper,
      List("not json", """{"noWork":1}""", workJson()),
      split = new FileSplit(new Path("/x/curated/works/p"), 0L, 0L, Array.empty[String]),
    )
    assertEquals(out.size, 1)
    assertEquals(counter(c, PipelineCounter.INPUT_RECORDS), 3L)
    assertEquals(counter(c, PipelineCounter.SCHEMA_FAILURE), 2L)
  }

  test("HJ6a ViewReducer: one work and two mentions give 3 views with coauthors and the DOI") {
    val m0 = mentionJson(0, "Ann One")
    val m1 = mentionJson(1, "Bob Two")
    val r  = reduce(
      new ViewReducer,
      List((WorkId, "W\t" + workJson()), (WorkId, "M\t" + m0), (WorkId, "M\t" + m1)),
    )
    assertEquals(counter(r.counters, PipelineCounter.ENTITY_VIEWS), 3L)
    val views = jsonRows(r, "entity-views").map(j => j("id").str -> j).toMap
    val w     = views(WorkId)
    assertEquals(w("kind").str, "work")
    assertEquals(w("doi").str, "10.1000/v.x")
    assertEquals(w("authors").arr.map(_.str).toList, List("Ann One", "Bob Two"))
    val a = views(ujson.read(m0)("mentionId").str)
    assertEquals(a("kind").str, "researcher")
    assertEquals(a("name").str, "Ann One")
    assertEquals(
      a("authors").arr.map(_.str).toList,
      List("Bob Two"),
      "coauthors exclude the mention itself",
    )
    assertEquals(a("doi").str, "10.1000/v.x")
    assertEquals(a("title").str, "Graph Learning")
  }

  test("HJ6a ViewReducer: two work rows for one work id are rejected and produce no view") {
    val r = reduce(
      new ViewReducer,
      List((WorkId, "W\t" + workJson("One")), (WorkId, "W\t" + workJson("Two"))),
    )
    assertEquals(r.table("entity-views"), Nil)
    assertEquals(counter(r.counters, PipelineCounter.SCHEMA_FAILURE), 1L)
    assertEquals(r.table("rejects").size, 1)
  }

  // ---- HJ6b: blocking mapper, combiner, reducer ---------------------------------------------------

  test(
    "HJ6b BlockCountMapper emits (primary key, 1); an unblockable view is counted, not emitted"
  ) {
    val named    = person("mention:a1", SourceType.Dblp)
    val named2   = person("mention:a2", SourceType.Crossref, name = "W. Wang")
    val noName   = EntityView("mention:a3", EntityKind.Researcher, SourceType.Dblp)
    val (out, c) = mapLines(new BlockCountMapper, List(js(named), js(noName), "{", js(named2)))
    assertEquals(out, List(("r1|wang|w", "1"), ("r1|wang|w", "1")))
    assertEquals(counter(c, PipelineCounter.INPUT_RECORDS), 4L)
    assertEquals(counter(c, PipelineCounter.UNBLOCKED_VIEW), 1L)
    assertEquals(counter(c, PipelineCounter.SCHEMA_FAILURE), 1L)
  }

  test("HJ6b BlockCountMapper: a work view is keyed by its title tokens and year bucket") {
    val w = EntityView(
      "work:w1",
      EntityKind.Work,
      SourceType.Dblp,
      title = Some("Efficient Graph Neural Network Training with Sampling"),
      year = Some(2021),
    )
    val (out, _) = mapLines(new BlockCountMapper, List(js(w)))
    assertEquals(out, List(("w1|efficient-network-sampling-training|y673", "1")))
  }

  test("HJ6b BlockCountCombiner sums partial counts per key (an associative, safe combiner)") {
    val r = reduce(
      new BlockCountCombiner,
      List(("k", "1"), ("k", "1"), ("k", "3"), ("j", "2"), ("j", "5")),
    )
    assertEquals(r.emitted, List(("j", "7"), ("k", "5")))
  }

  test("HJ6b BlockCountCombiner output feeds the reducer to the same total as no combiner") {
    val raw      = List.fill(7)(("k", "1"))
    val combined = reduce(new BlockCountCombiner, raw).emitted
    val viaComb  = reduce(new BlockCountReducer, combined, run(100))
    val direct   = reduce(new BlockCountReducer, raw, run(100))
    assertEquals(viaComb.table("block-sizes"), direct.table("block-sizes"))
    assertEquals(ujson.read(direct.table("block-sizes").head._2)("members").num.toInt, 7)
  }

  test(
    "HJ6b BlockCountReducer: sizes over er.max-block-size (3) go to oversize-keys; 3 itself does not"
  ) {
    val r = reduce(
      new BlockCountReducer,
      List(("big", "2"), ("big", "1"), ("big", "1"), ("edge", "3"), ("small", "1")),
      run(3),
    )
    assertEquals(r.table("block-sizes").map(_._1), List("big", "edge", "small"))
    assertEquals(
      r.table("oversize-keys")
        .map((k, j) =>
          (k, ujson.read(j)("members").num.toInt, ujson.read(j)("maxBlockSize").num.toInt)
        ),
      List(("big", 4, 3)),
    )
    assertEquals(counter(r.counters, PipelineCounter.BLOCK_KEYS), 3L)
    assertEquals(counter(r.counters, PipelineCounter.OVERSIZE_KEY), 1L)
  }

  // ---- HJ6c: routing mapper, candidate reducer --------------------------------------------------------

  private def oversizeDir(keys: List[String]): JPath =
    val d = tmp()
    Files.writeString(
      d.resolve("part-r-00000"),
      keys.map(k => s"""$k\t{"blockKey":"$k"}""").mkString("", "\n", "\n"),
    )
    d

  test(
    "HJ6c CandidateMapper: a view in an oversize block is routed to its fallback keys, plus exact-id keys"
  ) {
    val dir = oversizeDir(List("r1|wang|w"))
    val big =
      person("mention:d1", SourceType.Dblp, authors = List("Li Zhang"), orcid = Some(Orcid1))
    val ok       = person("mention:d2", SourceType.Crossref, name = "Ann Lee")
    val (out, c) = mapLines(
      new CandidateMapper,
      List(js(big), js(ok), "{"),
      extraConf = Map(ErKeys.OversizeKeysDir -> dir.toString),
    )
    assertEquals(
      out.map(_._1),
      List(
        "F|r1|wang|w|y404",
        "F|r1|wang|w|czhang",
        s"X|x1|orcid|$Orcid1",
        "P|r1|lee|a",
      ),
    )
    assertEquals(
      out.take(3).map(_._2).distinct,
      List(js(big)),
      "the whole view travels with the key",
    )
    assertEquals(counter(c, PipelineCounter.SCHEMA_FAILURE), 1L)
  }

  test("HJ6c CandidateMapper: with no oversize key every view goes to its primary key only") {
    val dir      = oversizeDir(Nil)
    val v        = person("mention:d1", SourceType.Dblp, authors = List("Li Zhang"))
    val (out, _) = mapLines(
      new CandidateMapper,
      List(js(v)),
      extraConf = Map(ErKeys.OversizeKeysDir -> dir.toString),
    )
    assertEquals(out.map(_._1), List("P|r1|wang|w"))
  }

  test("HJ6c CandidateMapper: a view with no primary key is routed nowhere") {
    val dir      = oversizeDir(Nil)
    val (out, _) = mapLines(
      new CandidateMapper,
      List(js(EntityView("mention:z", EntityKind.Researcher, SourceType.Dblp))),
      extraConf = Map(ErKeys.OversizeKeysDir -> dir.toString),
    )
    assertEquals(out, Nil)
  }

  private def evidenceDir(rows: List[ExactIdentifierEvidence]): JPath =
    val d = tmp()
    Files.writeString(
      d.resolve("part-r-00000"),
      rows
        .map(e => "k\t" + Wire.write(e).fold(x => fail(x.toString), identity))
        .mkString("", "\n", "\n"),
    )
    d

  private def conflictEvidence(ids: List[String]): ExactIdentifierEvidence =
    ExactIdentifierEvidence(
      SchemaVersion.Current,
      IdentifierType.Orcid,
      Orcid1,
      ids,
      ExactIdentifierOutcome.Conflict,
      ViewProv,
    )

  private def candidates(
      key: String,
      views: List[EntityView],
      max: Int = 500,
      evidence: List[ExactIdentifierEvidence] = Nil,
  ): Reduced =
    reduce(
      new CandidateReducer,
      views.map(v => (key, js(v))),
      run(max),
      Map(ErKeys.EvidenceDir -> evidenceDir(evidence).toString),
    )

  private val Group = List(
    person("mention:d1", SourceType.Dblp),
    person("mention:d2", SourceType.Dblp),
    person("mention:c1", SourceType.Crossref, name = "W. Wang"),
  )

  test(
    "HJ6c CandidateReducer: stages only cross-source pairs (DBLP x non-DBLP), in canonical order"
  ) {
    val r = candidates("P|r1|wang|w", Group)
    assertEquals(
      r.table("candidate-stage").map(_._1),
      List("mention:c1|mention:d1", "mention:c1|mention:d2"),
    )
    assertEquals(counter(r.counters, PipelineCounter.CANDIDATE_STAGED), 2L)
    assertEquals(counter(r.counters, PipelineCounter.BLOCK_GROUPS), 1L)
    assertEquals(counter(r.counters, PipelineCounter.OVERSIZE_BLOCK), 0L)
    val j = jsonRows(r, "candidate-stage").head
    assertEquals(j("leftId").str, "mention:c1")
    assertEquals(j("blockKey").str, "r1|wang|w")
    assertEquals(j("blockKeyType").str, "primary")
    assertEquals(j("features")("name_sim").num, 1.0)
    assertEquals(j("features")("source_reliability").num, 0.85)
    assertEquals(j("provenance")("parameters")("leftSource").str, "crossref")
    assertEquals(j("provenance")("parameters")("rightSource").str, "dblp")
  }

  test(
    "HJ6c CandidateReducer: a fallback shuffle key gives a fallback candidate with the prefix removed"
  ) {
    val r = candidates("F|r1|wang|w|y404", Group)
    val j = jsonRows(r, "candidate-stage").head
    assertEquals(j("blockKey").str, "r1|wang|w|y404")
    assertEquals(j("blockKeyType").str, "fallback")
  }

  test("HJ6c CandidateReducer: an exact-identifier group stages its pair under the x1 key") {
    val a = person("mention:d1", SourceType.Dblp, name = "Wei Wang", orcid = Some(Orcid1))
    val b = person("mention:c1", SourceType.Crossref, name = "Zed Qin", orcid = Some(Orcid1))
    val r = candidates(s"X|x1|orcid|$Orcid1", List(a, b))
    val j = jsonRows(r, "candidate-stage").head
    assertEquals(j("blockKey").str, s"x1|orcid|$Orcid1")
    assertEquals(j("features")("orcid_agreement").num, 1.0)
  }

  test(
    "OVERSIZE: a group above er.max-block-size (3) produces NO candidate and is listed with its size"
  ) {
    val four = Group :+ person("mention:c2", SourceType.OpenAlex)
    val r    = candidates("P|r1|wang|w", four, max = 3)
    assertEquals(r.table("candidate-stage"), Nil)
    assertEquals(counter(r.counters, PipelineCounter.OVERSIZE_BLOCK), 1L)
    assertEquals(counter(r.counters, PipelineCounter.CANDIDATE_STAGED), 0L)
    val row = jsonRows(r, "oversize-blocks").head
    assertEquals(row("blockKey").str, "r1|wang|w")
    assertEquals(row("blockKeyType").str, "primary")
    assertEquals(row("members").num.toInt, 4)
    assertEquals(row("maxBlockSize").num.toInt, 3)
  }

  test(
    "OVERSIZE: exactly the cap (3 members) is processed; every member is counted for a huge group"
  ) {
    val atCap = candidates("P|r1|wang|w", Group, max = 3)
    assertEquals(counter(atCap.counters, PipelineCounter.OVERSIZE_BLOCK), 0L)
    assertEquals(counter(atCap.counters, PipelineCounter.CANDIDATE_STAGED), 2L)
    val ten = (1 to 10).toList.map(i => person(f"mention:c$i%02d", SourceType.Crossref))
    val big = candidates("P|r1|wang|w", ten, max = 3)
    assertEquals(jsonRows(big, "oversize-blocks").head("members").num.toInt, 10)
    assertEquals(big.table("candidate-stage"), Nil)
  }

  test(
    "HJ6c CandidateReducer: HJ3 CONFLICT evidence on a pair sets exact_id_conflict on that pair only"
  ) {
    val ev     = List(conflictEvidence(List("mention:c1", "mention:d1")))
    val r      = candidates("P|r1|wang|w", Group, evidence = ev)
    val byPair = r.table("candidate-stage").map((k, j) => k -> ujson.read(j)).toMap
    assertEquals(byPair("mention:c1|mention:d1")("features")("exact_id_conflict").num, 1.0)
    assert(!byPair("mention:c1|mention:d2")("features").obj.contains("exact_id_conflict"))
    assertEquals(counter(r.counters, PipelineCounter.CANDIDATE_CONFLICT_FLAGGED), 1L)
  }

  test("HJ6c CandidateReducer: the same group in any value order stages identical rows") {
    val a = candidates("P|r1|wang|w", Group)
    val b = candidates("P|r1|wang|w", Group.reverse)
    assertEquals(a.table("candidate-stage"), b.table("candidate-stage"))
  }

  // ---- ConflictIndex and exact-id keys -------------------------------------------------------------------

  test("ConflictIndex: two ids conflict only when a CONFLICT evidence row links both") {
    val d = evidenceDir(
      List(
        conflictEvidence(List("mention:a", "mention:b", "mention:c")),
        conflictEvidence(List("mention:x", "mention:y")),
      )
    )
    val idx = ConflictIndex.load(new org.apache.hadoop.conf.Configuration(), new Path(d.toString))
    assert(idx.conflicts("mention:a", "mention:b"))
    assert(idx.conflicts("mention:b", "mention:c"))
    assert(idx.conflicts("mention:c", "mention:a"))
    assert(idx.conflicts("mention:x", "mention:y"))
    assert(!idx.conflicts("mention:a", "mention:x"), "ids from different evidence rows")
    assert(!idx.conflicts("mention:a", "mention:zzz"))
    assert(!ConflictIndex.Empty.conflicts("mention:a", "mention:b"))
  }

  test("ConflictIndex: a missing evidence directory gives an empty index") {
    val idx = ConflictIndex.load(
      new org.apache.hadoop.conf.Configuration(),
      new Path(tmp().resolve("does-not-exist").toString),
    )
    assertEquals(idx.size, 0)
  }

  test("ErKeys.exactIdKeys: normalized, typed and sorted; a researcher carries only its ORCID") {
    val work = EntityView(
      "work:w1",
      EntityKind.Work,
      SourceType.Crossref,
      doi = Some("https://doi.org/10.1000/ABC"),
      arxiv = Some("arXiv:2101.00001"),
    )
    assertEquals(ErKeys.exactIdKeys(work), List("x1|arxiv|2101.00001", "x1|doi|10.1000/abc"))
    val p = person("mention:a", SourceType.Dblp, orcid = Some(s"https://orcid.org/$Orcid1"))
      .copy(doi = Some("10.1000/abc"))
    assertEquals(ErKeys.exactIdKeys(p), List(s"x1|orcid|$Orcid1"))
    assertEquals(ErKeys.exactIdKeys(person("mention:b", SourceType.Dblp)), Nil)
  }

  // ---- HJ6d ---------------------------------------------------------------------------------------------------

  private def candJson(key: String, t: BlockKeyType): String =
    val a = person("mention:c1", SourceType.Crossref)
    val b = person("mention:d1", SourceType.Dblp)
    val c = Blocking
      .candidate(a, b, BlockKey(key, t), runCtx.provAll("unit", withEr = true))
      .fold(e => fail(e.toString), identity)
    Wire.write(c).fold(e => fail(e.toString), identity)

  test(
    "HJ6d dedupe: a pair found under two keys keeps the primary candidate and counts one duplicate"
  ) {
    val prim = candJson("r1|wang|w", BlockKeyType.Primary)
    val fb   = candJson("r1|wang|w|y404", BlockKeyType.Fallback)
    val r    = reduce(
      new CandidateDedupeReducer,
      List(("mention:c1|mention:d1", fb), ("mention:c1|mention:d1", prim)),
    )
    assertEquals(r.table("match-candidates").map(_._2), List(prim))
    assertEquals(counter(r.counters, PipelineCounter.CANDIDATE_DUPLICATE), 1L)
    assertEquals(counter(r.counters, PipelineCounter.ACCEPTED), 1L)
  }

  test("HJ6d dedupe: among fallbacks the smaller key wins; three copies count two duplicates") {
    val a = candJson("r1|wang|w|cchen", BlockKeyType.Fallback)
    val b = candJson("r1|wang|w|y404", BlockKeyType.Fallback)
    val c = candJson("r1|wang|w|czhang", BlockKeyType.Fallback)
    val r = reduce(new CandidateDedupeReducer, List(("p", b), ("p", c), ("p", a)))
    assertEquals(r.table("match-candidates").map(_._2), List(a))
    assertEquals(counter(r.counters, PipelineCounter.CANDIDATE_DUPLICATE), 2L)
  }

  test("HJ6d dedupe: distinct pairs both survive; an unreadable row is dropped silently") {
    val one = candJson("r1|wang|w", BlockKeyType.Primary)
    val r   = reduce(new CandidateDedupeReducer, List(("p1", one), ("p2", one), ("p3", "garbage")))
    assertEquals(r.table("match-candidates").size, 2)
  }

  // ---- HJ7: decision mapper and reducer -------------------------------------------------------------------------

  private val Mid =
    Map("name_sim" -> 1.0, "initials_compat" -> 1.0, "coauthor_overlap" -> 1.0) // .79

  private def decCand(tag: String, features: Map[String, Double]): (String, String) =
    val c = MatchCandidate
      .build(
        s"mention:${tag}a",
        s"mention:${tag}b",
        EntityKind.Researcher,
        "r1|wang|w",
        BlockKeyType.Primary,
        features,
        Features.Version,
        runCtx.provAll("unit", withEr = true),
      )
      .fold(e => fail(e.toString), identity)
    (s"${c.leftId}|${c.rightId}", Wire.write(c).fold(e => fail(e.toString), identity))

  test(
    "HJ7 CandidateKeyMapper: keys each candidate by its pair; an unreadable line counts SCHEMA_FAILURE"
  ) {
    val (k, line) = decCand("x", Mid)
    val (out, c) = mapLines(new CandidateKeyMapper, List(line, "{", """{"leftId":1,"rightId":2}"""))
    assertEquals(out, List((k, line)))
    assertEquals(counter(c, PipelineCounter.INPUT_RECORDS), 3L)
    assertEquals(counter(c, PipelineCounter.SCHEMA_FAILURE), 2L)
  }

  private val Five = List(
    // plain ORCID disagreement (repair 03 M1): REJECTED on exact_id, no longer CONFLICT
    decCand("conf", Mid + ("orcid_agreement" -> 0.0)),
    decCand("hj3", Map("doi_agreement" -> 1.0, "exact_id_conflict" -> 1.0)),
    decCand("exact", Map("doi_agreement" -> 1.0)),
    decCand("band", Mid),
    decCand("rej", Map("family_exact" -> 1.0, "initials_compat" -> 0.0)),
  )

  test(
    "HJ7 DecisionReducer: outcomes are routed to outcome=<x> tables, CONFLICT is never accepted"
  ) {
    val r              = reduce(new DecisionReducer, Five)
    def ids(t: String) = jsonRows(r, t).map(_("leftId").str).sorted
    assertEquals(ids("outcome=conflict"), List("mention:hj3a"))
    assertEquals(ids("outcome=accepted"), List("mention:exacta"))
    assertEquals(ids("outcome=unresolved"), List("mention:banda"))
    assertEquals(ids("outcome=rejected"), List("mention:confa", "mention:reja"))
    assertEquals(counter(r.counters, PipelineCounter.DECISIONS), 5L)
    assertEquals(counter(r.counters, PipelineCounter.CONFLICT), 1L)
    assertEquals(counter(r.counters, PipelineCounter.LLM_ELIGIBLE), 1L)
    assertEquals(
      r.decisions,
      Map(
        "conflict.exact_id"        -> 1L,
        "rejected.exact_id"        -> 1L,
        "accepted.exact_id"        -> 1L,
        "unresolved.probabilistic" -> 1L,
        "rejected.deterministic"   -> 1L,
      ),
    )
    val conf = jsonRows(r, "outcome=rejected").find(_("leftId").str == "mention:confa").get
    assertEquals(conf("matchPath").str, "exact_id")
    assertEquals(conf("reasonCodes").arr.map(_.str).toList, List("orcid_disagree"))
  }

  test(
    "HJ7 DecisionReducer: an ORCID disagreement beside the same author position is still CONFLICT (never accepted)"
  ) {
    val r = reduce(
      new DecisionReducer,
      List(decCand("pos", Mid ++ Map("orcid_agreement" -> 0.0, "position_agreement" -> 1.0))),
    )
    assertEquals(jsonRows(r, "outcome=conflict").map(_("leftId").str), List("mention:posa"))
    assertEquals(jsonRows(r, "outcome=accepted"), Nil)
    assertEquals(r.decisions, Map("conflict.exact_id" -> 1L))
    assertEquals(counter(r.counters, PipelineCounter.CONFLICT), 1L)
  }

  test(
    "HJ7 DecisionReducer: a decision row carries the policy version, score, thresholds and a fixed timestamp"
  ) {
    val r = reduce(new DecisionReducer, List(decCand("band", Mid)))
    val d = jsonRows(r, "outcome=unresolved").head
    assertEquals(d("policyVersion").str, "er-v1")
    assertEquals(d("matchPath").str, "probabilistic")
    assertEquals(d("decisionTimestamp").str, "2026-01-01T00:00:00Z")
    assertEquals(d("runId").str, runCtx.runId)
    assertEquals(d("features")("name_sim").num, 1.0)
    assert(math.abs(d("features")("policy.score").num - 0.79) < 1e-9)
    assertEquals(d("features")("policy.threshold.accept").num, 0.85)
    assertEquals(d("features")("policy.threshold.band-low").num, 0.55)
  }

  test(
    "HJ7 DecisionReducer: thresholds come from the run context (accept .75 turns .79 into accepted)"
  ) {
    val ctx = runCtx.copy(er =
      Map(
        "er.researcher.threshold.accept"        -> "0.75",
        "er.researcher.threshold.llm-band-high" -> "0.70",
      )
    )
    val r = reduce(new DecisionReducer, List(decCand("band", Mid)), ctx)
    assertEquals(jsonRows(r, "outcome=accepted").map(_("leftId").str), List("mention:banda"))
    assertEquals(counter(r.counters, PipelineCounter.LLM_ELIGIBLE), 0L)
    assertEquals(
      jsonRows(r, "outcome=accepted").head("features")("policy.threshold.accept").num,
      0.75,
    )
  }

  test("HJ7 DecisionReducer: the same candidates in a different order give identical tables") {
    val a = reduce(new DecisionReducer, Five)
    val b = reduce(new DecisionReducer, Five.reverse)
    assertEquals(a.tables, b.tables)
    assertEquals(a.decisions, b.decisions)
  }

  test("HJ7 DecisionReducer: an unreadable candidate is rejected and counted, not decided") {
    val r = reduce(new DecisionReducer, List(("k", "{not a candidate}")))
    assertEquals(counter(r.counters, PipelineCounter.DECISIONS), 0L)
    assertEquals(counter(r.counters, PipelineCounter.SCHEMA_FAILURE), 1L)
    assertEquals(r.table("rejects").size, 1)
  }

  // ---- Dedupe.instantKey (N4) ---------------------------------------------------------------------------------------

  test("Dedupe.instantKey parses ISO-8601 instants, including offsets and fractions") {
    import java.time.Instant
    assertEquals(
      Dedupe.instantKey(Some("2026-01-01T00:00:00Z")),
      Instant.parse("2026-01-01T00:00:00Z"),
    )
    assertEquals(
      Dedupe.instantKey(Some("2026-01-01T01:00:00+01:00")),
      Instant.parse("2026-01-01T00:00:00Z"),
    )
    assertEquals(
      Dedupe.instantKey(Some("2026-01-01T00:00:00.500Z")),
      Instant.parse("2026-01-01T00:00:00.5Z"),
    )
  }

  test("Dedupe.instantKey: absent or unparsable sorts first (Instant.MIN)") {
    import java.time.Instant
    assertEquals(Dedupe.instantKey(None), Instant.MIN)
    assertEquals(Dedupe.instantKey(Some("not a date")), Instant.MIN)
    assertEquals(Dedupe.instantKey(Some("")), Instant.MIN)
    assertEquals(
      Dedupe.instantKey(Some("2026-01-01")),
      Instant.MIN,
      "a date without a time is not an instant",
    )
    assert(Dedupe.instantKey(None).isBefore(Dedupe.instantKey(Some("1970-01-01T00:00:00Z"))))
  }

  test("Dedupe.instantKey orders by instant where string order would be wrong") {
    // string order: "...T01:10:00+01:00" > "...T00:30:00Z", but the instants are 00:10Z < 00:30Z
    val early = Dedupe.instantKey(Some("2026-01-01T01:10:00+01:00"))
    val late  = Dedupe.instantKey(Some("2026-01-01T00:30:00Z"))
    assert(early.isBefore(late))
    assert("2026-01-01T01:10:00+01:00" > "2026-01-01T00:30:00Z", "string order disagrees")
    // string order: "...00Z" > "...00.500Z" ('Z' > '.'), but .5 s is later
    assert(
      Dedupe
        .instantKey(Some("2026-01-01T00:00:00.500Z"))
        .isAfter(Dedupe.instantKey(Some("2026-01-01T00:00:00Z")))
    )
    assert("2026-01-01T00:00:00Z" > "2026-01-01T00:00:00.500Z")
  }

  // ---- Dedupe.rows with and without `known` (N1, N4) -------------------------------------------------------------------

  private def in(id: String, v: String = "v1") = InputRef(id, v, Some(Hex))

  private def dprov(at: Option[String], inputs: List[InputRef]): Provenance =
    Provenance(
      SchemaVersion.Current,
      SourceType.Orcid,
      inputs,
      "unit-test",
      Map("job" -> "unit"),
      Some(runCtx.runId),
      at,
    )

  private def dwork(title: String, at: Option[String], inputs: List[InputRef]): String =
    Wire
      .write(
        WorkRecord(
          SchemaVersion.Current,
          "work:doi:10.1000/dd.x",
          title,
          2020,
          Some("10.1000/dd.x"),
          None,
          Nil,
          dprov(at, inputs),
        )
      )
      .fold(e => fail(e.toString), identity)

  private final class KnownProbe(known: Option[Set[InputRef]])
      extends Reducer[Text, Text, Text, Text]:
    private type Ctx = Reducer[Text, Text, Text, Text]#Context
    var result: List[String]                                                         = Nil
    override def reduce(key: Text, values: java.lang.Iterable[Text], ctx: Ctx): Unit =
      result = Dedupe.rows("works", values.asScala.map(_.toString).toList, ctx, known)

  private def merge(rows: List[String], known: Option[Set[InputRef]]): (ujson.Value, Reduced) =
    val probe = new KnownProbe(known)
    val r     = reduce(probe, rows.map(j => ("k", j)))
    assertEquals(probe.result.size, 1)
    (ujson.read(probe.result.head), r)

  private def inputIds(j: ujson.Value): List[String] =
    j("provenance")("inputs").arr.map(_("inputId").str).toList

  test("Dedupe.rows(known): the winner keeps its own inputs; only known loser inputs are added") {
    val early  = dwork("Early", Some("2026-01-01T00:00:00Z"), List(in("orcid:x"), in("orcid:y")))
    val late   = dwork("Late", Some("2026-02-01T00:00:00Z"), List(in("orcid:y"), in("orcid:z")))
    val (j, r) = merge(List(early, late), Some(Set(in("orcid:z"))))
    assertEquals(j("title").str, "Late")
    // own [y, z] + (loser inputs that are known: none); x is dropped and summarized, y is the winner's own
    assertEquals(inputIds(j), List("orcid:y", "orcid:z"))
    val p = j("provenance")("parameters")
    assertEquals(p("merged.rows").str, "1")
    assertEquals(p("merged.files").str, "1")
    assertEquals(
      p("merged.inputs-digest").str,
      Hashing.digest("scholarchain.hadoop.merged-inputs.v1", List(s"orcid:x:v1:$Hex")),
    )
    assertEquals(counter(r.counters, PipelineCounter.WORK_MERGED), 1L)
  }

  test("Dedupe.rows(known): a known loser input is kept, sorted with the winner's inputs") {
    val early =
      dwork("Early", Some("2026-01-01T00:00:00Z"), List(in("orcid:b"), in("orcid:a", "v2")))
    val late = dwork("Late", Some("2026-02-01T00:00:00Z"), List(in("orcid:c"), in("orcid:a", "v2")))
    val (j, _) = merge(List(late, early), Some(Set(in("orcid:a", "v2"), in("orcid:c"))))
    assertEquals(
      j("provenance")("inputs").arr.map(i => (i("inputId").str, i("version").str)).toList,
      List(("orcid:a", "v2"), ("orcid:c", "v1")),
    )
    val p = j("provenance")("parameters")
    assertEquals(p("merged.files").str, "1", "only orcid:b is not retained")
    assertEquals(
      p("merged.inputs-digest").str,
      Hashing.digest("scholarchain.hadoop.merged-inputs.v1", List(s"orcid:b:v1:$Hex")),
    )
  }

  test(
    "Dedupe.rows(known): provenance size is bounded by the winner plus known, not by the row count"
  ) {
    val shared = in("orcid:a")
    val rows   = (1 to 28).toList.map { i =>
      dwork(f"T$i%02d", Some(f"2020-01-$i%02dT00:00:00Z"), List(shared, in(s"orcid:p$i")))
    }
    val (j, r) = merge(rows.reverse, Some(Set(shared)))
    assertEquals(j("title").str, "T28", "the latest retrievedAt wins")
    assertEquals(inputIds(j), List("orcid:a", "orcid:p28"))
    val p = j("provenance")("parameters")
    assertEquals(p("merged.rows").str, "27")
    assertEquals(p("merged.files").str, "27")
    val gone = (1 to 27).map(i => s"orcid:p$i").sorted.map(id => s"$id:v1:$Hex").toList
    assertEquals(
      p("merged.inputs-digest").str,
      Hashing.digest("scholarchain.hadoop.merged-inputs.v1", gone),
    )
    assertEquals(counter(r.counters, PipelineCounter.WORK_MERGED), 27L)
    // the legacy unbounded form on the same rows grows with the row count
    val (legacy, _) = merge(rows.reverse, None)
    assertEquals(inputIds(legacy).size, 29)
    assert(!legacy("provenance")("parameters").obj.contains("merged.rows"))
  }

  test("Dedupe.rows(known): the result does not depend on the order of the rows") {
    val rows = (1 to 6).toList.map(i =>
      dwork(s"T$i", Some(s"2021-0$i-01T00:00:00Z"), List(in("orcid:a"), in(s"orcid:p$i")))
    )
    val known = Some(Set(in("orcid:a")))
    val a     = merge(rows, known)._1
    val b     = merge(rows.reverse, known)._1
    val c     = merge(rows.sortBy(_.hashCode), known)._1
    assertEquals(a, b)
    assertEquals(a, c)
  }

  test("Dedupe.rows picks the winner by INSTANT: an offset time earlier in the day-string loses") {
    // string order would pick the first ("T01:10:00+01:00" > "T00:30:00Z"); by instant the second is later
    val a = dwork("Offset", Some("2026-01-01T01:10:00+01:00"), List(in("orcid:a")))
    val b = dwork("Utc", Some("2026-01-01T00:30:00Z"), List(in("orcid:b")))
    assertEquals(merge(List(a, b), None)._1("title").str, "Utc")
    assertEquals(merge(List(b, a), None)._1("title").str, "Utc")
    assertEquals(merge(List(a, b), Some(Set.empty[InputRef]))._1("title").str, "Utc")
  }

  test("Dedupe.rows picks the later fractional second although its string sorts lower") {
    val a = dwork("Whole", Some("2026-01-01T00:00:00Z"), List(in("orcid:a")))
    val b = dwork("Fraction", Some("2026-01-01T00:00:00.500Z"), List(in("orcid:b")))
    assertEquals(merge(List(a, b), None)._1("title").str, "Fraction")
    assertEquals(merge(List(b, a), Some(Set.empty[InputRef]))._1("title").str, "Fraction")
  }

  test("Dedupe.rows: equal instants written differently tie-break on the larger JSON text") {
    val a = dwork("Alpha", Some("2026-01-01T01:00:00+01:00"), List(in("orcid:a")))
    val b = dwork("Bravo", Some("2026-01-01T00:00:00Z"), List(in("orcid:b")))
    assertEquals(
      Dedupe.instantKey(Some("2026-01-01T01:00:00+01:00")),
      Dedupe.instantKey(Some("2026-01-01T00:00:00Z")),
    )
    val expected = ujson.read(List(a, b).max)("title").str
    assertEquals(merge(List(a, b), None)._1("title").str, expected)
    assertEquals(merge(List(b, a), None)._1("title").str, expected)
  }

  test("Dedupe.rows: a row with no retrievedAt loses to any row that has one") {
    val none = dwork("NoTime", None, List(in("orcid:a")))
    val old  = dwork("Old", Some("1999-01-01T00:00:00Z"), List(in("orcid:b")))
    assertEquals(merge(List(none, old), Some(Set.empty[InputRef]))._1("title").str, "Old")
  }

  test("Dedupe.rows(known) on a single row passes it through unchanged and counts nothing") {
    val one   = dwork("Only", Some("2026-01-01T00:00:00Z"), List(in("orcid:a")))
    val probe = new KnownProbe(Some(Set(in("orcid:a"))))
    val r     = reduce(probe, List(("k", one)))
    assertEquals(probe.result, List(one))
    assertEquals(counter(r.counters, PipelineCounter.WORK_MERGED), 0L)
  }
