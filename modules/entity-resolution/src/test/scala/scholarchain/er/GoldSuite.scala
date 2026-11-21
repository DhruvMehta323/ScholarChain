package scholarchain.er

import scholarchain.core.*

import ErFx.*

/** Gold-set splitting and threshold calibration (pure functions). SYNTHETIC labels only. */
class GoldSuite extends munit.FunSuite:
  private val R = EntityKind.Researcher
  private val W = EntityKind.Work

  private def rows(kind: EntityKind, label: GoldLabel, n: Int, tag: String): List[LabelRow] =
    (1 to n).toList.map(i => LabelRow(f"P$tag$i%03d", kind, label))

  /** 60 + 50 researcher, 30 + 30 work = 170 definitive rows. */
  private val Rows170: List[LabelRow] =
    rows(R, GoldLabel.Match, 60, "rm") ++ rows(R, GoldLabel.NonMatch, 50, "rn") ++
      rows(W, GoldLabel.Match, 30, "wm") ++ rows(W, GoldLabel.NonMatch, 30, "wn")

  private def cal(res: GoldSplitter.Result, kind: EntityKind, label: GoldLabel): Int =
    res.assignments.count((r, s) => r.kind == kind && r.label == label && s == Split.Calibration)

  test("split: exactly 102 calibration and 68 held-out rows for 170 definitive rows") {
    val res = GoldSplitter.split(Rows170, 20260101L, 0.6)
    assertEquals(res.assignments.size, 170)
    assertEquals(res.assignments.count(_._2 == Split.Calibration), 102)
    assertEquals(res.assignments.count(_._2 == Split.HeldOut), 68)
    assertEquals(res.excludedUnsure, 0)
  }

  test("split: stratified by entity kind x label (36 / 30 / 18 / 18 calibration rows)") {
    val res = GoldSplitter.split(Rows170, 7L, 0.6)
    assertEquals(cal(res, R, GoldLabel.Match), 36)
    assertEquals(cal(res, R, GoldLabel.NonMatch), 30)
    assertEquals(cal(res, W, GoldLabel.Match), 18)
    assertEquals(cal(res, W, GoldLabel.NonMatch), 18)
  }

  test(
    "split: a non-integer quota is spread by largest remainder (62 of 104 -> 20 / 18 / 12 / 12)"
  ) {
    // 33 * .6 = 19.8, 31 * .6 = 18.6, 20 * .6 = 12, 20 * .6 = 12; floors 19 + 18 + 12 + 12 = 61;
    // target round(62.4) = 62; the one extra row goes to the largest remainder (.8).
    val rs = rows(R, GoldLabel.Match, 33, "a") ++ rows(R, GoldLabel.NonMatch, 31, "b") ++
      rows(W, GoldLabel.Match, 20, "c") ++ rows(W, GoldLabel.NonMatch, 20, "d")
    val res = GoldSplitter.split(rs, 1L, 0.6)
    assertEquals(res.assignments.count(_._2 == Split.Calibration), 62)
    assertEquals(cal(res, R, GoldLabel.Match), 20)
    assertEquals(cal(res, R, GoldLabel.NonMatch), 18)
    assertEquals(cal(res, W, GoldLabel.Match), 12)
    assertEquals(cal(res, W, GoldLabel.NonMatch), 12)
  }

  test("split: deterministic for a seed, different for another seed") {
    val a = GoldSplitter.split(Rows170, 20260101L, 0.6)
    val b = GoldSplitter.split(Rows170, 20260101L, 0.6)
    val c = GoldSplitter.split(Rows170, 99L, 0.6)
    assertEquals(a, b)
    assertNotEquals(a.assignments, c.assignments)
    assertEquals(c.assignments.count(_._2 == Split.Calibration), 102)
  }

  test("split: independent of the order of the input rows") {
    val a = GoldSplitter.split(Rows170, 5L, 0.6)
    val b = GoldSplitter.split(Rows170.reverse, 5L, 0.6)
    assertEquals(a.assignments, b.assignments)
  }

  test("split: UNSURE rows are excluded and counted, and do not change the 102 / 68") {
    val unsure = rows(R, GoldLabel.Unsure, 4, "ru") ++ rows(W, GoldLabel.Unsure, 3, "wu")
    val res    = GoldSplitter.split(Rows170 ++ unsure, 20260101L, 0.6)
    assertEquals(res.excludedUnsure, 7)
    assertEquals(res.assignments.size, 170)
    assert(res.assignments.forall(_._1.label != GoldLabel.Unsure))
    assertEquals(res.assignments.count(_._2 == Split.Calibration), 102)
    assertEquals(res.assignments.count(_._2 == Split.HeldOut), 68)
  }

  test("split: every row lands in exactly one set") {
    val res = GoldSplitter.split(Rows170, 3L, 0.6)
    assertEquals(res.assignments.map(_._1.pairId).distinct.size, 170)
    assertEquals(res.assignments.map(_._1.pairId), res.assignments.map(_._1.pairId).sorted)
  }

  test("GoldLabel.parse accepts the three wire values case-insensitively and nothing else") {
    assertEquals(GoldLabel.parse(" match "), Some(GoldLabel.Match))
    assertEquals(GoldLabel.parse("non_match"), Some(GoldLabel.NonMatch))
    assertEquals(GoldLabel.parse("Unsure"), Some(GoldLabel.Unsure))
    assertEquals(GoldLabel.parse(""), None)
    assertEquals(GoldLabel.parse("MAYBE"), None)
    assertEquals(GoldLabel.parse("non match"), None)
  }

  // ---- calibration (pure) --------------------------------------------------------------------------

  // Researcher scores, by hand: .28 * name_sim + .20 * coauthor_overlap + .52 * .5 (= .26).
  private def rp(id: String, m: Boolean, ns: Double, co: Double) =
    GoldPoint(id, R, m, Map("name_sim" -> ns, "coauthor_overlap" -> co))

  private val M1 = rp("m1", true, 1.0, 1.0)  // .74
  private val M2 = rp("m2", true, 0.9, 0.8)  // .252 + .16 + .26 = .672
  private val N1 = rp("n1", false, 0.9, 0.0) // .252 + .26 = .512
  private val N2 = rp("n2", false, 0.2, 0.1) // .056 + .02 + .26 = .336

  test("sweep: confusion matrices at hand-checked thresholds") {
    val rows = Calibration.sweep(List(M1, M2, N1, N2))
    assertEquals(rows.size, 50)
    def at(a: Double) = rows.find(r => close(r.accept, a)).get.confusion
    assertEquals(at(0.50), Confusion(tp = 2, fp = 1, fn = 0, tn = 1)) // N1 .512 accepted
    assertEquals(at(0.60), Confusion(tp = 2, fp = 0, fn = 0, tn = 2))
    assertEquals(at(0.70), Confusion(tp = 1, fp = 0, fn = 1, tn = 2)) // only M1 .74
    assertEquals(at(0.99), Confusion(tp = 0, fp = 0, fn = 2, tn = 2))
  }

  test("calibrate: chooses the highest threshold among the F1-maximal ones (0.67)") {
    val o =
      Calibration.calibrate(R, List(M1, M2, N1, N2), ErFx.cfg.researcher).fold(fail(_), identity)
    assertEquals(o.chosen.accept, 0.67)
    assertEquals(o.chosen.confusion, Confusion(2, 0, 0, 2))
    assertEquals(o.chosen.confusion.f1, 1.0)
    // reject = largest 0.01 step not above the lowest probabilistic MATCH score .672 -> .67
    assertEquals(o.thresholds, Thresholds(0.67, 0.67, 0.67, 0.67))
    assertEquals(o.sweep.size, 50)
  }

  test("choose: F1 first, then precision, then the higher threshold") {
    val hiF1 = SweepRow(0.6, Confusion(2, 0, 0, 2)) // F1 1.0
    val lo   = SweepRow(0.5, Confusion(2, 1, 0, 1)) // F1 .8, P .667
    assertEquals(Calibration.choose(List(lo, hiF1)).accept, 0.6)
    // equal F1 .8: (tp2 fp0 fn1) has precision 1.0, (tp2 fp1 fn0) has precision .667
    val prec = SweepRow(0.5, Confusion(2, 0, 1, 1))
    val rec  = SweepRow(0.9, Confusion(2, 1, 0, 1))
    assertEquals(prec.confusion.f1, rec.confusion.f1)
    assertEquals(
      Calibration.choose(List(rec, prec)).accept,
      0.5,
      "precision beats the higher threshold",
    )
    // equal F1 and precision: the higher threshold
    val t1 = SweepRow(0.6, Confusion(2, 0, 0, 2))
    val t2 = SweepRow(0.7, Confusion(2, 0, 0, 2))
    assertEquals(Calibration.choose(List(t1, t2)).accept, 0.7)
    assertEquals(Calibration.choose(List(t2, t1)).accept, 0.7)
  }

  test("calibrate needs both classes for the kind") {
    val onlyMatch = Calibration.calibrate(R, List(M1, M2), ErFx.cfg.researcher)
    assert(onlyMatch.left.exists(_.contains("need both MATCH and NON_MATCH")), onlyMatch.toString)
    val onlyNon = Calibration.calibrate(R, List(N1, N2), ErFx.cfg.researcher)
    assert(onlyNon.isLeft)
  }

  test("calibrate uses only the points of its own kind") {
    val noise = List(
      GoldPoint("w1", W, true, Map("title_sim" -> 0.0)),
      GoldPoint("w2", W, false, Map("title_sim" -> 1.0)),
    )
    val a = Calibration.calibrate(R, List(M1, M2, N1, N2), ErFx.cfg.researcher)
    val b = Calibration.calibrate(R, noise ++ List(M1, M2, N1, N2), ErFx.cfg.researcher)
    assertEquals(a.map(_.thresholds), b.map(_.thresholds))
  }

  test("calibrate: exact-id and deterministic rules are not moved by the accept threshold") {
    // a MATCH with an equal DOI is accepted on the exact_id path whatever the threshold is
    val pts = List(
      GoldPoint("w1", W, true, Map("doi_agreement" -> 1.0, "title_sim" -> 0.1)),
      GoldPoint("w2", W, true, Map("title_sim" -> 1.0)),
      GoldPoint("w3", W, false, Map("title_sim" -> 0.5)),
    )
    val rows = Calibration.sweep(pts)
    assert(rows.forall(r => r.confusion.tp >= 1), "w1 is always accepted")
    assertEquals(rows.find(r => close(r.accept, 0.99)).get.confusion, Confusion(1, 0, 1, 1))
  }

  // ---- CSV ------------------------------------------------------------------------------------------

  test("Csv round trip keeps commas, doubled quotes and line breaks inside quoted cells") {
    val table = List(List("a", "b,c", "d\"e"), List("line1\nline2", "", "x"))
    assertEquals(Csv.parse(Csv.write(table)), table)
    assertEquals(Csv.write(List(List("a", "b,c"))), "a,\"b,c\"\n")
  }

  test("Csv.parse ignores blank lines and tolerates CRLF") {
    assertEquals(Csv.parse("a,b\r\n\r\n1,2\r\n"), List(List("a", "b"), List("1", "2")))
  }
