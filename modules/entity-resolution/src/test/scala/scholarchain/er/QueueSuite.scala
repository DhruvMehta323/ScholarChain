package scholarchain.er

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}
import scholarchain.er.tools.*

import ErFx.*

/** BuildViews and BuildReviewQueue on SYNTHETIC fixtures in temp dirs. No real data, no keys. */
class QueueSuite extends munit.FunSuite:
  private val R = EntityKind.Researcher
  private val W = EntityKind.Work

  private def dir(): Path = Files.createTempDirectory("er-queue-test-")

  private def argv(kv: (String, String)*): Args =
    Args.parse(kv.flatMap((k, v) => Seq(s"--$k", v)))

  private def quiet(): PrintStream = PrintStream(ByteArrayOutputStream(), true, "UTF-8")

  // ---- review-queue fixture ---------------------------------------------------------------------

  private def num(i: Int): Double = (3000 + i * 137) / 10000.0 // recognizable 4-decimal values

  /** 45 researcher candidates (every third one a hard same-surname pair) and 25 work candidates. */
  private final case class Fixture(cands: List[MatchCandidate], views: List[EntityView])

  private def fixture: Fixture =
    val rs = (0 until 45).toList.map { i =>
      val l    = f"mention:r$i%02da"
      val r    = f"mention:r$i%02db"
      val hard = i % 3 == 0
      val fs   = Map(
        "name_sim"         -> num(i),
        "coauthor_overlap" -> (num(i) + 0.0011),
        "family_exact"     -> (if hard then 1.0 else 0.0),
        "initials_compat"  -> 1.0,
      )
      (
        cand(l, r, R, fs),
        List(
          person(l, SourceType.Dblp, s"Wang A$i", authors = List(s"Coauthor X$i")),
          person(r, SourceType.Crossref, s"Wang B$i", authors = List(s"Coauthor Y$i")),
        ),
      )
    }
    val ws = (0 until 25).toList.map { i =>
      val l  = f"work:dblp:w$i%02da"
      val r  = f"work:crossref:w$i%02db"
      val fs = Map(
        "title_sim"      -> math.min(num(i) + 0.3, 0.99),
        "year_distance"  -> (if i % 2 == 0 then 1.0 else 0.0),
        "author_overlap" -> (num(i) + 0.0007),
      )
      (
        cand(l, r, W, fs),
        List(
          paper(l, SourceType.Dblp, s"Graph methods $i"),
          paper(r, SourceType.Crossref, s"Graph methods $i."),
        ),
      )
    }
    Fixture((rs ++ ws).map(_._1), (rs ++ ws).flatMap(_._2))

  private def writeFixture(fx: Fixture = fixture): (Path, Path) =
    val d = dir()
    val c = d.resolve("cands.jsonl")
    val v = d.resolve("views.jsonl")
    Files.writeString(
      c,
      fx.cands
        .map(x => Wire.write(x).fold(e => throw new AssertionError(e.toString), identity))
        .mkString("", "\n", "\n"),
    )
    Files.writeString(v, fx.views.map(x => WirePickle.write(x)).mkString("", "\n", "\n"))
    (c, v)

  private def runQueue(
      c: Path,
      v: Path,
      seed: Long,
      nR: Int = 15,
      nW: Int = 5,
  ): (Path, List[List[String]]) =
    val out = dir().resolve("queue.csv")
    val res = BuildReviewQueue.run(
      argv(
        "candidates" -> c.toString,
        "views"      -> v.toString,
        "out"        -> out.toString,
        "seed"       -> seed.toString,
        "researcher" -> nR.toString,
        "work"       -> nW.toString,
      ),
      quiet(),
      Map.empty,
    )
    assertEquals(res, Right(()))
    (out, Csv.parse(Files.readString(out)))

  // ---- tests ------------------------------------------------------------------------------------

  test("blind CSV header is exactly the allowed columns, in order") {
    val (c, v)    = writeFixture()
    val (_, rows) = runQueue(c, v, 7L)
    val side      = (p: String) =>
      List(
        "source",
        "id",
        "name_or_title",
        "coauthors_or_authors",
        "work_title",
        "year",
        "venue",
        "ids",
        "affiliation",
      ).map(p + "_" + _)
    assertEquals(
      rows.head,
      List("pair_id", "entity_kind") ++ side("left") ++ side("right") ++ List("label", "notes"),
    )
    assertEquals(rows.head, BuildReviewQueue.Header)
    assert(rows.tail.forall(_.size == rows.head.size), "ragged row")
  }

  test("blind CSV leaks no score, feature value or predicted outcome; label and notes are empty") {
    val fx        = fixture
    val (c, v)    = writeFixture(fx)
    val (_, rows) = runQueue(c, v, 7L)
    val body      = rows.tail
    assert(body.nonEmpty)
    val secrets =
      fx.cands.flatMap(_.features.values).map(_.toString).filter(_.length >= 5).toSet ++
        fx.cands.map(x => Resolver.scored(x, cfg).result.score.toString).filter(_.length >= 5)
    assert(secrets.size > 20, "fixture must hold many recognizable numbers")
    for row <- body; cellText <- row; s <- secrets do
      assert(!cellText.contains(s), s"value $s leaked into cell '$cellText'")
    val h = rows.head
    List("score", "feature", "outcome", "decision", "predict", "bin", "stratum").foreach { w =>
      assert(!h.exists(_.toLowerCase.contains(w)), s"header mentions $w")
    }
    val li = h.indexOf("label")
    val ni = h.indexOf("notes")
    assert(body.forall(r => r(li).isEmpty && r(ni).isEmpty), "label and notes must be empty")
    val outcomes = ResolutionOutcome.values.map(_.wire).toSet
    assert(!body.flatten.exists(outcomes.contains), "predicted outcome in a cell")
  }

  test("same inputs and seed give byte-identical CSV; a different seed changes the selection") {
    val (c, v)     = writeFixture()
    val (p1, r1)   = runQueue(c, v, 42L, nR = 10, nW = 4)
    val (p2, _)    = runQueue(c, v, 42L, nR = 10, nW = 4)
    val (_, other) = runQueue(c, v, 43L, nR = 10, nW = 4)
    assertEquals(Files.readAllBytes(p1).toSeq, Files.readAllBytes(p2).toSeq)
    assertEquals(r1.tail.size, 14)
    assertNotEquals(r1.tail.map(_.head), other.tail.map(_.head))
  }

  test("both entity kinds are present and match the requested split") {
    val (c, v)    = writeFixture()
    val (_, rows) = runQueue(c, v, 7L, nR = 15, nW = 5)
    val kinds     = rows.tail.map(_(1))
    assertEquals(kinds.count(_ == R.wire), 15)
    assertEquals(kinds.count(_ == W.wire), 5)
    assertEquals(rows.tail.map(_.head).distinct.size, 20, "duplicate pair rows")
  }

  test("allocate: largest remainder on hand-computed numbers") {
    val big = Map("a" -> 99, "b" -> 99, "c" -> 99)
    // shares 2.5, 2.5, 5 -> floors 2, 2, 5 = 9; the spare goes to the first tied remainder (a)
    assertEquals(
      BuildReviewQueue.allocate(10, Map("a" -> 1.0, "b" -> 1.0, "c" -> 2.0), big),
      Map("a" -> 3, "b" -> 2, "c" -> 5),
    )
    // the 150 : 50 target scaled to 200 and to 20
    val kinds = Map("researcher" -> 150.0, "work" -> 50.0)
    val avail = Map("researcher" -> 1000, "work" -> 1000)
    assertEquals(
      BuildReviewQueue.allocate(200, kinds, avail),
      Map("researcher" -> 150, "work" -> 50),
    )
    assertEquals(BuildReviewQueue.allocate(20, kinds, avail), Map("researcher" -> 15, "work" -> 5))
    // capped by availability: a holds 2, so 3 spare go to b (5 + 3)
    assertEquals(
      BuildReviewQueue.allocate(10, Map("a" -> 1.0, "b" -> 1.0), Map("a" -> 2, "b" -> 100)),
      Map("a" -> 2, "b" -> 8),
    )
  }

  test("isHard: same-surname non-clean researcher pairs and similar-title work pairs that differ") {
    def sc(k: EntityKind, fs: Map[String, Double]) =
      val pre = if k == R then "mention" else "work"
      Resolver.scored(cand(s"$pre:xa", s"$pre:xb", k, fs), cfg)
    assert(BuildReviewQueue.isHard(sc(R, Map("family_exact" -> 1.0, "name_sim" -> 0.9))))
    assert(BuildReviewQueue.isHard(sc(R, Map("family_exact" -> 1.0, "initials_compat" -> 0.0))))
    assert(
      !BuildReviewQueue.isHard(
        sc(R, Map("family_exact" -> 1.0, "name_sim" -> 1.0, "initials_compat" -> 1.0))
      )
    )
    assert(!BuildReviewQueue.isHard(sc(R, Map("family_exact" -> 0.0, "name_sim" -> 0.5))))
    assert(BuildReviewQueue.isHard(sc(W, Map("title_sim" -> 0.7, "year_distance" -> 1.0))))
    assert(
      BuildReviewQueue.isHard(
        sc(W, Map("title_sim" -> 0.9, "year_distance" -> 0.0, "author_overlap" -> 0.5))
      )
    )
    assert(
      !BuildReviewQueue.isHard(
        sc(W, Map("title_sim" -> 0.9, "year_distance" -> 0.0, "author_overlap" -> 1.0))
      )
    )
    assert(!BuildReviewQueue.isHard(sc(W, Map("title_sim" -> 0.3, "year_distance" -> 2.0))))
  }

  test("select oversamples hard negatives: at least half of the quota when hard pairs exist") {
    // 10 hard and 40 clean researcher pairs with the SAME score bin: random draw would give 2 hard.
    def mk(i: Int, hard: Boolean) =
      val fs =
        if hard then Map("family_exact" -> 1.0, "name_sim" -> 0.9, "coauthor_overlap" -> 0.5)
        else Map("family_exact"         -> 0.0, "name_sim" -> 0.9, "coauthor_overlap" -> 0.5)
      Resolver.scored(cand(f"mention:p$i%02da", f"mention:p$i%02db", R, fs), cfg)
    val pool = (0 until 50).toList.map(i => mk(i, i < 10))
    assertEquals(pool.map(BuildReviewQueue.stratum).distinct.size, 1)
    val (picked, quota) = BuildReviewQueue.select(pool, 10, 5L, cfg.thresholds(R))
    assertEquals(picked.size, 10)
    assertEquals(quota.values.map(_._2).sum, 10)
    assert(
      picked.count(BuildReviewQueue.isHard) >= 5,
      s"hard picked: ${picked.count(BuildReviewQueue.isHard)}",
    )
    assertEquals(picked.map(_.pairId).distinct.size, 10)
  }

  test("BuildViews: researcher view lists coauthors, work view lists all authors") {
    val w1                                         = "work:dblp:journals/x/1"
    val w2                                         = "work:dblp:journals/x/2"
    def work(id: String, title: String, year: Int) =
      WorkRecord(SchemaVersion.Current, id, title, year, None, None, Nil, prov())
    def men(w: String, pos: Int, name: String) =
      val id = Ids.mentionId(w, pos).fold(e => throw new AssertionError(e.toString), identity)
      AuthorMention(SchemaVersion.Current, id, w, pos, name, None, prov())
    val ms = List(
      men(w1, 2, "Carol Lee"),
      men(w1, 0, "Alice Smith"),
      men(w1, 1, "Bob Jones"),
      men(w2, 0, "Dan Roe"),
    )
    val d                                     = dir()
    def jl[A: WirePickle.Writer](xs: List[A]) =
      xs.map(x => WirePickle.write(x)).mkString("", "\n", "\n")
    val wp = Files.writeString(
      d.resolve("works.jsonl"),
      jl(List(work(w2, "Second", 2019), work(w1, "First", 2018))),
    )
    val mp = Files.writeString(d.resolve("mentions.jsonl"), jl(ms))
    val op = d.resolve("views.jsonl")
    assertEquals(
      BuildViews.run(
        argv("works" -> wp.toString, "mentions" -> mp.toString, "out" -> op.toString),
        quiet(),
      ),
      Right(()),
    )
    val vs = Io.readViews(op).fold(e => throw new AssertionError(e), identity)
    assertEquals(vs.size, 6) // 2 works + 4 mentions
    val wv = vs(w1)
    assertEquals(wv.kind, W)
    assertEquals(wv.title, Some("First"))
    assertEquals(wv.year, Some(2018))
    assertEquals(wv.authors, List("Alice Smith", "Bob Jones", "Carol Lee")) // by position
    val bob = vs(ms.find(_.rawName == "Bob Jones").get.mentionId)
    assertEquals(bob.kind, R)
    assertEquals(bob.name, Some("Bob Jones"))
    assertEquals(bob.workId, Some(w1))
    assertEquals(bob.title, Some("First"))
    assertEquals(bob.year, Some(2018))
    assertEquals(bob.authors, List("Alice Smith", "Carol Lee")) // coauthors, not self
    assertEquals(vs(ms.find(_.rawName == "Dan Roe").get.mentionId).authors, Nil)
  }

  test("rows are canonical (left id < right id) and the pair_id ignores argument order") {
    val fx        = fixture
    val (c, v)    = writeFixture(fx)
    val (_, rows) = runQueue(c, v, 7L, nR = 15, nW = 5)
    val h         = rows.head
    val (li, ri)  = (h.indexOf("left_id"), h.indexOf("right_id"))
    assert(rows.tail.forall(r => r(li).compareTo(r(ri)) < 0), "left id must sort before right id")
    assert(
      rows.tail.forall(r =>
        r(0) == ErIds.pairId(EntityKind.values.find(_.wire == r(1)).get, r(li), r(ri))
      )
    )
    assertEquals(
      ErIds.pairId(R, "mention:a", "mention:b"),
      ErIds.pairId(R, "mention:b", "mention:a"),
    )
    // candidates written with the ids reversed give the same pair id and the same row
    val a        = cand("mention:z9", "mention:a1", R, Map("name_sim" -> 0.9))
    val b        = cand("mention:a1", "mention:z9", R, Map("name_sim" -> 0.9))
    val (sa, sb) = (Resolver.scored(a, cfg), Resolver.scored(b, cfg))
    assertEquals(sa.pairId, sb.pairId)
    val vl = person("mention:a1", SourceType.Dblp, "Wang A")
    val vr = person("mention:z9", SourceType.Crossref, "Wang B")
    assertEquals(BuildReviewQueue.row(sa, vl, vr), BuildReviewQueue.row(sb, vl, vr))
    assertEquals(BuildReviewQueue.row(sa, vl, vr)(3), "mention:a1")
  }
