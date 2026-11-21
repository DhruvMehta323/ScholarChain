package scholarchain.er

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scholarchain.core.*
import scholarchain.er.tools.*

import ErFx.*

/** The runnable tools, driven through their `run` functions on SYNTHETIC files in temp dirs. */
class ToolsSuite extends munit.FunSuite:
  private val R = EntityKind.Researcher
  private val W = EntityKind.Work

  private def dir(): Path = Files.createTempDirectory("er-tools-test-")

  private final class Sink:
    val bytes        = new ByteArrayOutputStream()
    val stream       = new PrintStream(bytes, true, "UTF-8")
    def text: String = bytes.toString(StandardCharsets.UTF_8)

  private def argv(kv: (String, String)*): Args =
    Args.parse(kv.flatMap((k, v) => Seq(s"--$k", v)))

  // ---- gold fixture -----------------------------------------------------------------------------

  private final case class Item(
      name: String,
      kind: EntityKind,
      label: GoldLabel,
      split: String,
      features: Map[String, Double],
  ):
    private val pre               = if kind == R then "mention" else "work"
    val left: String              = s"$pre:${name}a"
    val right: String             = s"$pre:${name}b"
    def pairId: String            = ErIds.pairId(kind, left, right)
    def candidate: MatchCandidate = cand(left, right, kind, features)

  private def rf(ns: Double, co: Double) = Map("name_sim" -> ns, "coauthor_overlap" -> co)

  // researcher score = .28 ns + .20 co + .26 ; work score = .35 title_sim + .325
  private val Cal = List(
    Item("rm1", R, GoldLabel.Match, Split.Calibration, rf(1.0, 1.0)),    // .74
    Item("rm2", R, GoldLabel.Match, Split.Calibration, rf(0.9, 0.8)),    // .672
    Item("rn1", R, GoldLabel.NonMatch, Split.Calibration, rf(0.9, 0.0)), // .512
    Item("rn2", R, GoldLabel.NonMatch, Split.Calibration, rf(0.2, 0.1)), // .336
    Item("wm", W, GoldLabel.Match, Split.Calibration, Map("title_sim" -> 1.0)), // .675
    Item("wn", W, GoldLabel.NonMatch, Split.Calibration, Map("title_sim" -> 0.5)), // .5
  )

  // Poison: three held-out MATCH rows scored .612 and a NON_MATCH scored .512. If they were read as
  // calibration rows the best researcher threshold would fall from .67 to .61.
  private val Held = List(
    Item("h1", R, GoldLabel.Match, Split.HeldOut, rf(0.9, 0.5)),
    Item("h2", R, GoldLabel.Match, Split.HeldOut, rf(0.9, 0.5)),
    Item("h3", R, GoldLabel.Match, Split.HeldOut, rf(0.9, 0.5)),
    Item("h4", R, GoldLabel.NonMatch, Split.HeldOut, rf(0.9, 0.0)),
    Item("hwm", W, GoldLabel.Match, Split.HeldOut, Map("title_sim" -> 1.0)), // .675
    Item("hwn", W, GoldLabel.NonMatch, Split.HeldOut, Map("title_sim" -> 0.5)), // .5
  )

  private def splitCsv(items: List[Item]): String =
    Csv.write(
      List("pair_id", "entity_kind", "label", "split") ::
        items.map(i => List(i.pairId, i.kind.wire, i.label.wire, i.split))
    )

  private def candJsonl(cs: List[MatchCandidate]): String =
    cs.map(c => Wire.write(c).fold(e => throw new AssertionError(e.toString), identity))
      .mkString("", "\n", "\n")

  private def put(d: Path, name: String, content: String): Path =
    Files.writeString(d.resolve(name), content)

  private def runCalibrate(
      split: Path,
      cands: Path,
      env: Map[String, String] = Map.empty,
  ): (Either[String, Unit], Path, Path) =
    val o   = dir()
    val pol = o.resolve("policy.json")
    val sen = o.resolve("sensitivity.csv")
    val s   = new Sink
    val r   = Calibrate.run(
      argv(
        "split"           -> split.toString,
        "candidates"      -> cands.toString,
        "out-policy"      -> pol.toString,
        "out-sensitivity" -> sen.toString,
      ),
      s.stream,
      env,
    )
    (r, pol, sen)

  private def frozen(p: Path): FrozenPolicy =
    FrozenPolicy.read(Files.readString(p)).fold(e => fail(e.toString), identity)

  // ---- Calibrate reads calibration rows only ---------------------------------------------------------

  test("Calibrate: thresholds on a clean calibration split equal the hand-computed .67 / .67") {
    val d             = dir()
    val (r, pol, sen) = runCalibrate(
      put(d, "split.csv", splitCsv(Cal)),
      put(d, "cands.jsonl", candJsonl(Cal.map(_.candidate))),
    )
    assertEquals(r, Right(()))
    val f = frozen(pol)
    assertEquals(f.researcher, Thresholds(0.67, 0.67, 0.67, 0.67))
    assertEquals(f.work, Thresholds(0.67, 0.67, 0.67, 0.67))
    assertEquals(f.thresholdsOrigin, "calibrated-on-calibration-split-only")
    assertEquals(f.calibrationMetrics("researcher")("n"), 4.0)
    assertEquals(f.calibrationMetrics("researcher")("f1"), 1.0)
    val table = Csv.parse(Files.readString(sen))
    assertEquals(table.size, 1 + 100, "header + 50 grid rows per kind")
    val chosen = table.tail.filter(_.last == "yes")
    assertEquals(
      chosen.map(row => (row.head, row(1))),
      List(("researcher", "0.67"), ("work", "0.67")),
    )
  }

  test(
    "SAFETY Calibrate: poisoned held-out rows change nothing (result identical with and without)"
  ) {
    val d           = dir()
    val cands       = put(d, "cands.jsonl", candJsonl((Cal ++ Held).map(_.candidate)))
    val clean       = put(d, "clean.csv", splitCsv(Cal))
    val full        = put(d, "full.csv", splitCsv(Cal ++ Held))
    val (r1, p1, _) = runCalibrate(clean, cands)
    val (r2, p2, _) = runCalibrate(full, cands)
    assertEquals((r1, r2), (Right(()), Right(())))
    val a = frozen(p1)
    val b = frozen(p2)
    assertEquals(b.researcher, a.researcher)
    assertEquals(b.work, a.work)
    assertEquals(b.calibrationMetrics, a.calibrationMetrics)
    assertEquals(b.researcher, Thresholds(0.67, 0.67, 0.67, 0.67))
    assertEquals(b.parameters("calibrationRows"), "6", "only the six calibration rows are counted")
  }

  test(
    "the poison is real: calibrating on calibration + held-out points WOULD move the threshold"
  ) {
    // This makes the previous test non-vacuous: if Calibrate.run stopped filtering by split, it
    // would compute exactly this (accept .61) and the identical-result assertion would fail.
    def pt(i: Item) = GoldPoint(i.pairId, i.kind, i.label == GoldLabel.Match, i.candidate.features)
    val polluted    = Calibration
      .calibrate(R, (Cal ++ Held).map(pt), ErFx.cfg.researcher)
      .fold(fail(_), identity)
    assertEquals(polluted.thresholds.accept, 0.61)
    val clean = Calibration.calibrate(R, Cal.map(pt), ErFx.cfg.researcher).fold(fail(_), identity)
    assertEquals(clean.thresholds.accept, 0.67)
  }

  test(
    "SAFETY Calibrate: held-out rows are never looked up (no candidate, garbage kind-free ids)"
  ) {
    val d = dir()
    // the candidates file holds only the calibration candidates; the held-out rows have none
    val cands     = put(d, "cands.jsonl", candJsonl(Cal.map(_.candidate)))
    val full      = put(d, "full.csv", splitCsv(Cal ++ Held))
    val (r, p, _) = runCalibrate(full, cands)
    assertEquals(r, Right(()))
    assertEquals(frozen(p).researcher, Thresholds(0.67, 0.67, 0.67, 0.67))
  }

  test("Calibrate: a calibration row with no candidate is an error naming the pair") {
    val d         = dir()
    val (r, _, _) = runCalibrate(
      put(d, "split.csv", splitCsv(Cal)),
      put(d, "cands.jsonl", candJsonl(Cal.tail.map(_.candidate))),
    )
    assert(
      r.left.exists(_.contains(s"no candidate for calibration pair ${Cal.head.pairId}")),
      r.toString,
    )
  }

  test("Calibrate refuses to overwrite an existing output") {
    val d   = dir()
    val pol = put(d, "policy.json", "{}")
    val s   = new Sink
    val r   = Calibrate.run(
      argv(
        "split"           -> put(d, "split.csv", splitCsv(Cal)).toString,
        "candidates"      -> put(d, "cands.jsonl", candJsonl(Cal.map(_.candidate))).toString,
        "out-policy"      -> pol.toString,
        "out-sensitivity" -> d.resolve("s.csv").toString,
      ),
      s.stream,
      Map.empty,
    )
    assert(r.left.exists(_.contains("refusing to overwrite")), r.toString)
    assertEquals(Files.readString(pol), "{}")
  }

  // ---- Evaluate -------------------------------------------------------------------------------------

  private def pred(
      id: String,
      kind: EntityKind,
      label: GoldLabel,
      outcome: ResolutionOutcome,
      path: MatchPath,
  ): Evaluate.Pred =
    val c = cand(
      if kind == R then s"mention:${id}a" else s"work:${id}a",
      if kind == R then s"mention:${id}b" else s"work:${id}b",
      kind,
      Map("name_sim" -> 0.5),
    )
    Evaluate.Pred(
      LabelRow(id, kind, label),
      Scored(c, id, PolicyResult(outcome, path, 0.5, 0.5, List("rule"), List("reason_x"), false)),
      PolicyResult(outcome, path, 0.5, 0.5, List("rule"), List("reason_x"), false),
    )

  private val Preds = List(
    pred("r1", R, GoldLabel.Match, ResolutionOutcome.Accepted, MatchPath.ExactId),          // TP
    pred("r2", R, GoldLabel.Match, ResolutionOutcome.Accepted, MatchPath.Probabilistic),    // TP
    pred("r3", R, GoldLabel.NonMatch, ResolutionOutcome.Accepted, MatchPath.Probabilistic), // FP
    pred("r4", R, GoldLabel.Match, ResolutionOutcome.Unresolved, MatchPath.Probabilistic),  // FN
    pred("r5", R, GoldLabel.NonMatch, ResolutionOutcome.Rejected, MatchPath.Probabilistic), // TN
    pred("r6", R, GoldLabel.NonMatch, ResolutionOutcome.Conflict, MatchPath.ExactId),       // TN
    pred("r7", R, GoldLabel.Match, ResolutionOutcome.Conflict, MatchPath.ExactId),          // FN
    pred("w1", W, GoldLabel.Match, ResolutionOutcome.Accepted, MatchPath.ExactId),          // TP
    pred("w2", W, GoldLabel.NonMatch, ResolutionOutcome.Rejected, MatchPath.Deterministic), // TN
    pred("w3", W, GoldLabel.Match, ResolutionOutcome.Rejected, MatchPath.Deterministic),    // FN
  )

  private lazy val Eval = Evaluate.evaluate(Preds, ujson.Obj())

  private def conf(kind: String, path: Option[String] = None): Map[String, Int] =
    val c = path match
      case None    => Eval("perKind")(kind)("confusion")
      case Some(p) => Eval("perKind")(kind)("byPath")(p)("confusion")
    List("tp", "fp", "fn", "tn").map(k => k -> c(k).num.toInt).toMap

  test(
    "Evaluate: researcher confusion matrix tp2 fp1 fn2 tn2 (unresolved and conflict are non-matches)"
  ) {
    assertEquals(conf("researcher"), Map("tp" -> 2, "fp" -> 1, "fn" -> 2, "tn" -> 2))
    val m = Eval("perKind")("researcher")
    assertEquals(m("n").num.toInt, 7)
    assert(close(m("confusion")("precision").num, 2.0 / 3.0))
    assert(close(m("confusion")("recall").num, 0.5))
    assert(close(m("confusion")("f1").num, 4.0 / 7.0))
  }

  test("Evaluate: work confusion matrix tp1 fp0 fn1 tn1, precision 1, recall .5, F1 2/3") {
    assertEquals(conf("work"), Map("tp" -> 1, "fp" -> 0, "fn" -> 1, "tn" -> 1))
    val c = Eval("perKind")("work")("confusion")
    assertEquals(c("precision").num, 1.0)
    assertEquals(c("recall").num, 0.5)
    assert(close(c("f1").num, 2.0 / 3.0))
  }

  test("Evaluate: counts, coverage, unresolved and conflict rates per kind") {
    val m = Eval("perKind")("researcher")
    val c = m("counts")
    assertEquals(
      List("accepted", "rejected", "unresolved", "conflict").map(k => c(k).num.toInt),
      List(3, 1, 1, 2),
    )
    assert(close(m("coverage").num, 4.0 / 7.0))
    assert(close(m("unresolvedRate").num, 1.0 / 7.0))
    assert(close(m("conflictRate").num, 2.0 / 7.0))
  }

  test("Evaluate: metrics by match path") {
    assertEquals(
      conf("researcher", Some("exact_id")),
      Map("tp" -> 1, "fp" -> 0, "fn" -> 1, "tn" -> 1),
    )
    assertEquals(
      conf("researcher", Some("probabilistic")),
      Map("tp" -> 1, "fp" -> 1, "fn" -> 1, "tn" -> 1),
    )
    assertEquals(Eval("perKind")("researcher")("byPath")("exact_id")("n").num.toInt, 3)
    assertEquals(Eval("perKind")("researcher")("byPath")("llm")("n").num.toInt, 0)
    assertEquals(Eval("perKind")("work")("byPath")("deterministic")("n").num.toInt, 2)
  }

  test("Evaluate: every FP and FN is listed with features and reason codes, FN before FP") {
    val errs = Eval("errors").arr.toList
    assertEquals(
      errs.map(e => (e("pairId").str, e("error").str)),
      List(("r4", "FN"), ("r7", "FN"), ("w3", "FN"), ("r3", "FP")),
    )
    errs.foreach { e =>
      assertEquals(e("reasonCodes").arr.map(_.str).toList, List("reason_x"))
      assertEquals(e("features")("name_sim").num, 0.5)
    }
  }

  test("Evaluate: all predictions are stored, sorted by pair id, so metrics can be recomputed") {
    val ps = Eval("predictions").arr.toList
    assertEquals(
      ps.map(_("pairId").str),
      List("r1", "r2", "r3", "r4", "r5", "r6", "r7", "w1", "w2", "w3"),
    )
    assertEquals(ps.head("outcome").str, "accepted")
    assertEquals(ps.head("path").str, "exact_id")
  }

  test("Evaluate: an empty kind yields zero metrics and no division by zero") {
    val only = Evaluate.evaluate(Preds.filter(_.row.kind == R), ujson.Obj())
    val w    = only("perKind")("work")
    assertEquals(w("n").num.toInt, 0)
    assertEquals(w("coverage").num, 0.0)
    assertEquals(w("confusion")("f1").num, 0.0)
  }

  private def runEvaluateEnv(
      env: Map[String, String],
      split: Path,
      cands: Path,
      pol: Path,
      out: Path,
      extra: Seq[(String, String)],
  ) =
    val s = new Sink
    val r = Evaluate.run(
      argv(
        Seq(
          "split"      -> split.toString,
          "candidates" -> cands.toString,
          "policy"     -> pol.toString,
          "out-dir"    -> out.toString,
        ) ++ extra*
      ),
      s.stream,
      env,
    )
    (r, s)

  private def runEvaluate(
      split: Path,
      cands: Path,
      pol: Path,
      out: Path,
      extra: (String, String)*
  ) = runEvaluateEnv(Map.empty, split, cands, pol, out, extra)

  /** The frozen policy of a Calibrate run on exactly `split` (calibration rows only are read). The
    * Evaluate run must read the same split file: the policy records its checksum.
    */
  private def frozenPolicy(
      d: Path,
      split: Path,
      calItems: List[Item] = Cal,
      env: Map[String, String] = Map.empty,
  ): Path =
    val (r, pol, _) = runCalibrate(
      split,
      put(d, "cal-cands.jsonl", candJsonl(calItems.map(_.candidate))),
      env,
    )
    assertEquals(r, Right(()))
    pol

  test("Evaluate.run: held-out metrics with the frozen policy (hand-computed)") {
    val d      = dir()
    val split  = put(d, "full.csv", splitCsv(Cal ++ Held))
    val pol    = frozenPolicy(d, split)
    val out    = dir()
    val (r, _) = runEvaluate(
      split,
      put(d, "all.jsonl", candJsonl((Cal ++ Held).map(_.candidate))),
      pol,
      out,
    )
    assertEquals(r, Right(()))
    val j = ujson.read(Files.readString(out.resolve("er-v1-heldout-eval.json")))
    assertEquals(j("heldOutRows").num.toInt, 6)
    assertEquals(j("thresholdsOrigin").str, "calibrated-on-calibration-split-only")
    assertEquals(j("llmUsed").bool, false)
    // researcher held-out scores .612 < reject .67: three MATCH rows are false negatives
    val rc = j("perKind")("researcher")("confusion")
    assertEquals(List("tp", "fp", "fn", "tn").map(k => rc(k).num.toInt), List(0, 0, 3, 1))
    assertEquals(j("perKind")("researcher")("coverage").num, 1.0)
    // work: .675 >= accept .67 is a TP; .5 is rejected
    val wc = j("perKind")("work")("confusion")
    assertEquals(List("tp", "fp", "fn", "tn").map(k => wc(k).num.toInt), List(1, 0, 0, 1))
    assert(Files.exists(out.resolve("er-v1-heldout-eval.md")))
  }

  test("Evaluate.run reads held-out rows only: calibration rows without candidates are ignored") {
    val d     = dir()
    val ghost = Item("ghost", R, GoldLabel.Match, Split.Calibration, rf(1.0, 1.0))
    val split = put(d, "full.csv", splitCsv(ghost :: Cal ++ Held))
    // the policy is calibrated on this very split (ghost included); Evaluate gets no candidate for it
    val pol    = frozenPolicy(d, split, ghost :: Cal)
    val (r, _) = runEvaluate(
      split,
      put(d, "held.jsonl", candJsonl(Held.map(_.candidate))),
      pol,
      dir(),
    )
    assertEquals(r, Right(()))
  }

  test("Evaluate.run: a held-out row with no candidate is an error") {
    val d      = dir()
    val split  = put(d, "full.csv", splitCsv(Cal ++ Held))
    val pol    = frozenPolicy(d, split)
    val (r, _) = runEvaluate(
      split,
      put(d, "held.jsonl", candJsonl(Held.tail.map(_.candidate))),
      pol,
      dir(),
    )
    assert(
      r.left.exists(_.contains(s"no candidate for held-out pair ${Held.head.pairId}")),
      r.toString,
    )
  }

  test("SAFETY Evaluate: a second evaluation is refused unless disclosed with a reason") {
    val d     = dir()
    val split = put(d, "full.csv", splitCsv(Cal ++ Held))
    val pol   = frozenPolicy(d, split)
    val out   = dir()
    val cands = put(d, "all.jsonl", candJsonl((Cal ++ Held).map(_.candidate)))
    assertEquals(runEvaluate(split, cands, pol, out)._1, Right(()))
    val first = Files.readString(out.resolve("er-v1-heldout-eval.json"))
    val again = runEvaluate(split, cands, pol, out)._1
    assert(again.left.exists(_.contains("already exist")), again.toString)
    assertEquals(
      Files.readString(out.resolve("er-v1-heldout-eval.json")),
      first,
      "first result untouched",
    )
    val disclosed =
      runEvaluate(split, cands, pol, out, "new-evaluation-reason" -> "unit test re-evaluation")._1
    assertEquals(disclosed, Right(()))
    val reeval = Files
      .list(out)
      .toArray
      .map(_.toString)
      .filter(_.contains("-reeval-"))
      .filter(_.endsWith(".json"))
    assertEquals(reeval.length, 1)
    val j = ujson.read(Files.readString(Path.of(reeval.head)))
    assertEquals(j("disclosedReEvaluation").str, "unit test re-evaluation")
    assertEquals(
      Files.readString(out.resolve("er-v1-heldout-eval.json")),
      first,
      "original is never overwritten",
    )
  }

  private def ledgerLines(split: Path): List[ujson.Value] =
    val p = split.toAbsolutePath.getParent.resolve("heldout-evaluations.jsonl")
    if Files.exists(p) then
      Files
        .readAllLines(p)
        .toArray
        .toList
        .map(_.toString)
        .filter(_.nonEmpty)
        .map(l => ujson.read(l))
    else Nil

  test("SAFETY Evaluate L5: the ledger blocks a second evaluation under a different --out-dir") {
    val d     = dir()
    val split = put(d, "full.csv", splitCsv(Cal ++ Held))
    val pol   = frozenPolicy(d, split)
    val cands = put(d, "all.jsonl", candJsonl((Cal ++ Held).map(_.candidate)))
    val out1  = dir()
    val out2  = dir()
    assertEquals(runEvaluate(split, cands, pol, out1)._1, Right(()))
    val again = runEvaluate(split, cands, pol, out2)._1
    assert(again.left.exists(_.contains("already exists")), again.toString)
    assert(again.left.exists(_.contains("--new-evaluation-reason")), again.toString)
    assert(!Files.exists(out2.resolve("er-v1-heldout-eval.json")), "nothing written to the new dir")
    assert(!Files.exists(out2.resolve("er-v1-heldout-eval.md")))
    assertEquals(ledgerLines(split).size, 1, "a refused run is not recorded")
  }

  test("SAFETY Evaluate L5: the ledger blocks a second evaluation under a different --name") {
    val d     = dir()
    val split = put(d, "full.csv", splitCsv(Cal ++ Held))
    val pol   = frozenPolicy(d, split)
    val cands = put(d, "all.jsonl", candJsonl((Cal ++ Held).map(_.candidate)))
    val out   = dir()
    assertEquals(runEvaluate(split, cands, pol, out, "name" -> "first")._1, Right(()))
    val again = runEvaluate(split, cands, pol, out, "name" -> "second")._1
    assert(again.left.exists(_.contains("already exists")), again.toString)
    assert(!Files.exists(out.resolve("second-heldout-eval.json")))
    assert(!Files.exists(out.resolve("second-heldout-eval.md")))
    // both a new dir and a new name together are refused as well
    val both = runEvaluate(split, cands, pol, dir(), "name" -> "third")._1
    assert(both.left.exists(_.contains("already exists")), both.toString)
    assertEquals(ledgerLines(split).size, 1)
  }

  test("L5: --new-evaluation-reason lets a new evaluation through and writes it to the ledger") {
    val d      = dir()
    val split  = put(d, "full.csv", splitCsv(Cal ++ Held))
    val pol    = frozenPolicy(d, split)
    val cands  = put(d, "all.jsonl", candJsonl((Cal ++ Held).map(_.candidate)))
    val out1   = dir()
    val out2   = dir()
    val splitH = Io.sha256(split)
    assertEquals(runEvaluate(split, cands, pol, out1)._1, Right(()))
    val ok = runEvaluate(
      split,
      cands,
      pol,
      out2,
      "name"                  -> "second",
      "new-evaluation-reason" -> "unit test: disclosed second look",
    )._1
    assertEquals(ok, Right(()))
    val j = ujson.read(Files.readString(out2.resolve("second-heldout-eval.json")))
    assertEquals(j("disclosedReEvaluation").str, "unit test: disclosed second look")
    val ls = ledgerLines(split)
    assertEquals(ls.size, 2)
    assertEquals(ls.map(_("splitSha256").str), List(splitH, splitH))
    assertEquals(ls.map(_("name").str), List("er-v1", "second"))
    assert(ls(0)("disclosedReEvaluation").isNull, "the first evaluation carries no reason")
    assertEquals(ls(1)("disclosedReEvaluation").str, "unit test: disclosed second look")
    // a third evaluation without a reason is still refused
    assert(runEvaluate(split, cands, pol, dir(), "name" -> "third")._1.isLeft)
  }

  test("L5: an explicit --ledger path is used instead of the default beside the split") {
    val d      = dir()
    val split  = put(d, "full.csv", splitCsv(Cal ++ Held))
    val pol    = frozenPolicy(d, split)
    val cands  = put(d, "all.jsonl", candJsonl((Cal ++ Held).map(_.candidate)))
    val ledger = dir().resolve("custom-ledger.jsonl")
    val out    = dir()
    assertEquals(runEvaluate(split, cands, pol, out, "ledger" -> ledger.toString)._1, Right(()))
    assert(Files.exists(ledger))
    assertEquals(ledgerLines(split), Nil, "the default ledger was not written")
    val again = runEvaluate(split, cands, pol, dir(), "ledger" -> ledger.toString)._1
    assert(again.left.exists(_.contains("already exists")), again.toString)
    val j = ujson.read(Files.readString(out.resolve("er-v1-heldout-eval.json")))
    assertEquals(j("ledger").str, ledger.toString)
  }

  test("SAFETY Evaluate L5: a frozen policy calibrated on a different split is refused") {
    val d     = dir()
    val split = put(d, "full.csv", splitCsv(Cal ++ Held))
    val pol   = frozenPolicy(d, split)
    // the same rows in a different order: same content class, different bytes, different checksum
    val other = put(d, "other.csv", splitCsv((Cal ++ Held).reverse))
    val cands = put(d, "all.jsonl", candJsonl((Cal ++ Held).map(_.candidate)))
    val out   = dir()
    val r     = runEvaluate(other, cands, pol, out)._1
    assert(r.left.exists(_.contains("not calibrated on this split")), r.toString)
    assert(!Files.exists(out.resolve("er-v1-heldout-eval.json")), "no result was written")
    assertEquals(ledgerLines(other), Nil, "a refused run leaves no ledger entry")
    // the matching split still works
    assertEquals(runEvaluate(split, cands, pol, dir())._1, Right(()))
  }

  test("SAFETY Evaluate L5: a frozen policy with no er-gold-split checksum is refused") {
    val d     = dir()
    val split = put(d, "full.csv", splitCsv(Cal ++ Held))
    val f     = frozen(frozenPolicy(d, split))
    assert(FrozenPolicy.splitChecksum(f).contains(Io.sha256(split)), "the calibration records it")
    val noSplit = f.copy(inputs = f.inputs.filterNot(_.inputId == "er-gold-split"))
    assertEquals(FrozenPolicy.splitChecksum(noSplit), None)
    val noChecksum =
      f.copy(inputs =
        f.inputs.map(i => if i.inputId == "er-gold-split" then i.copy(checksum = None) else i)
      )
    val cands = put(d, "all.jsonl", candJsonl((Cal ++ Held).map(_.candidate)))
    List("no-input" -> noSplit, "no-checksum" -> noChecksum).foreach { (n, p) =>
      val pol = put(d, s"$n.json", WirePickle.write(p))
      val out = dir()
      val r   = runEvaluate(split, cands, pol, out)._1
      assert(r.left.exists(_.contains("not calibrated on this split")), s"$n: $r")
      assert(!Files.exists(out.resolve("er-v1-heldout-eval.json")), n)
    }
  }

  // ---- L5: llm.min-confidence and the SCHOLARCHAIN_ER_* environment are frozen ------------------------------

  private val MinConfEnv = Map("SCHOLARCHAIN_ER_LLM_MIN_CONFIDENCE" -> "0.91")

  test("L5 Calibrate freezes llm.min-confidence and the SCHOLARCHAIN_ER_* environment") {
    val d     = dir()
    val split = put(d, "full.csv", splitCsv(Cal ++ Held))
    val plain = frozen(frozenPolicy(d, split))
    assertEquals(plain.llmMinConfidence, Some(0.7), "the default in force")
    assertEquals(plain.erEnv, Some(Map.empty[String, String]))
    val withEnv =
      frozen(frozenPolicy(dir(), put(dir(), "full.csv", splitCsv(Cal ++ Held)), Cal, MinConfEnv))
    assertEquals(withEnv.llmMinConfidence, Some(0.91))
    assertEquals(withEnv.erEnv, Some(MinConfEnv))
  }

  test("L5 Evaluate header records llmMinConfidence, erEnv and the ledger path") {
    val d     = dir()
    val split = put(d, "full.csv", splitCsv(Cal ++ Held))
    val pol   = frozenPolicy(d, split, Cal, MinConfEnv)
    val cands = put(d, "all.jsonl", candJsonl((Cal ++ Held).map(_.candidate)))
    val out   = dir()
    assertEquals(runEvaluateEnv(MinConfEnv, split, cands, pol, out, Nil)._1, Right(()))
    val j = ujson.read(Files.readString(out.resolve("er-v1-heldout-eval.json")))
    assertEquals(j("llmMinConfidence").num, 0.91)
    assertEquals(j("erEnv").obj.map((k, v) => k -> v.str).toMap, MinConfEnv)
    assertEquals(
      j("ledger").str,
      split.toAbsolutePath.getParent.resolve("heldout-evaluations.jsonl").toString,
    )
    // a run with no SCHOLARCHAIN_ER_* variables records an empty erEnv
    val d2     = dir()
    val split2 = put(d2, "full.csv", splitCsv(Cal ++ Held))
    val out2   = dir()
    assertEquals(
      runEvaluate(split2, cands, frozenPolicy(d2, split2), out2)._1,
      Right(()),
    )
    val j2 = ujson.read(Files.readString(out2.resolve("er-v1-heldout-eval.json")))
    assertEquals(j2("erEnv").obj.size, 0)
    assertEquals(j2("llmMinConfidence").num, 0.7)
  }

  test(
    "SAFETY L5: env drift against the frozen policy is refused, naming variables and no values"
  ) {
    val d       = dir()
    val split   = put(d, "full.csv", splitCsv(Cal ++ Held))
    val pol     = frozenPolicy(d, split, Cal, MinConfEnv)
    val cands   = put(d, "all.jsonl", candJsonl((Cal ++ Held).map(_.candidate)))
    val drifted = List(
      Map.empty[String, String], // variable removed
      Map("SCHOLARCHAIN_ER_LLM_MIN_CONFIDENCE" -> "0.55"), // value changed
      MinConfEnv ++ Map("SCHOLARCHAIN_ER_MAX_BLOCK_SIZE" -> "123456"), // variable added
    )
    drifted.foreach { env =>
      val out = dir()
      val r   = runEvaluateEnv(env, split, cands, pol, out, Nil)._1
      val msg = r.left.getOrElse(fail(s"expected a refusal for env $env, got $r"))
      assert(msg.contains("environment differs from the frozen policy"), msg)
      assert(msg.contains("SCHOLARCHAIN_ER_"), msg)
      List("0.91", "0.55", "123456").foreach(v =>
        assert(!msg.contains(v), s"value $v leaked: $msg")
      )
      assert(!Files.exists(out.resolve("er-v1-heldout-eval.json")), "no result on a drifted env")
    }
    val removed = runEvaluateEnv(Map.empty, split, cands, pol, dir(), Nil)._1
    assert(removed.left.exists(_.contains("SCHOLARCHAIN_ER_LLM_MIN_CONFIDENCE")), removed.toString)
    val added = runEvaluateEnv(
      MinConfEnv ++ Map("SCHOLARCHAIN_ER_MAX_BLOCK_SIZE" -> "123456"),
      split,
      cands,
      pol,
      dir(),
      Nil,
    )._1
    assert(added.left.exists(_.contains("SCHOLARCHAIN_ER_MAX_BLOCK_SIZE")), added.toString)
    assert(
      !added.left.exists(_.contains("SCHOLARCHAIN_ER_LLM_MIN_CONFIDENCE")),
      "an unchanged variable is not named",
    )
    assertEquals(ledgerLines(split), Nil, "refused runs leave no ledger entry")
    // control: the frozen environment itself runs
    assertEquals(runEvaluateEnv(MinConfEnv, split, cands, pol, dir(), Nil)._1, Right(()))
  }

  test("L5 FrozenPolicy.envDrift: names only, sorted; an older policy without erEnv has no drift") {
    val f =
      frozen(frozenPolicy(dir(), put(dir(), "full.csv", splitCsv(Cal ++ Held)), Cal, MinConfEnv))
    assertEquals(FrozenPolicy.envDrift(f, MinConfEnv), Nil)
    assertEquals(
      FrozenPolicy.envDrift(f, Map("SCHOLARCHAIN_ER_B" -> "1", "SCHOLARCHAIN_ER_A" -> "2")),
      List("SCHOLARCHAIN_ER_A", "SCHOLARCHAIN_ER_B", "SCHOLARCHAIN_ER_LLM_MIN_CONFIDENCE"),
    )
    assertEquals(
      FrozenPolicy.envDrift(f, MinConfEnv ++ Map("HOME" -> "/x", "PATH" -> "/y")),
      Nil,
      "only SCHOLARCHAIN_ER_* count",
    )
    assertEquals(FrozenPolicy.envDrift(f.copy(erEnv = None), Map("SCHOLARCHAIN_ER_A" -> "1")), Nil)
  }

  test("L5 FrozenPolicy.applyTo applies the frozen min-confidence; None leaves the config value") {
    val f  = frozen(frozenPolicy(dir(), put(dir(), "full.csv", splitCsv(Cal ++ Held))))
    val c0 = ErFx.cfg
    assertEquals(c0.llm.minConfidence, 0.7)
    val hi = FrozenPolicy
      .applyTo(c0, f.copy(llmMinConfidence = Some(0.95)))
      .fold(e => fail(e.toString), identity)
    assertEquals(hi.llm.minConfidence, 0.95)
    assertEquals(hi.researcher, f.researcher)
    val none = FrozenPolicy
      .applyTo(c0, f.copy(llmMinConfidence = None))
      .fold(e => fail(e.toString), identity)
    assertEquals(none.llm.minConfidence, 0.7)
  }

  test("L5 Tooling.config with --policy applies the frozen min-confidence over the default") {
    val d     = dir()
    val split = put(d, "full.csv", splitCsv(Cal ++ Held))
    val f     = frozen(frozenPolicy(d, split))
    val pol   = put(
      d,
      "p95.json",
      WirePickle.write(f.copy(llmMinConfidence = Some(0.95), erEnv = Some(Map.empty))),
    )
    val (cfg, fr) =
      Tooling.config(argv("policy" -> pol.toString), Map.empty).fold(e => fail(e), identity)
    assertEquals(cfg.llm.minConfidence, 0.95)
    assert(fr.isDefined)
    // an env that drifted from the frozen (empty) one is refused by every tool, naming the variable only
    val drift =
      Tooling.config(argv("policy" -> pol.toString), Map("SCHOLARCHAIN_ER_LLM_MAX_CALLS" -> "77"))
    val msg = drift.left.getOrElse(fail(s"expected a refusal, got $drift"))
    assert(msg.contains("SCHOLARCHAIN_ER_LLM_MAX_CALLS"), msg)
    assert(!msg.contains("77"), msg)
  }

  // ---- F3 (repair-04): the LLM model is frozen; header discloses split checksum and overrides ----

  private val ModelKey      = "scholarchain.er.llm.model"
  private val DefaultModel  = "claude-haiku-4-5" // the versioned default in scholarchain-er.v1.conf
  private val OtherModel    = "claude-other-model-9"
  private def modelOverride = Map(ModelKey -> OtherModel)

  private def frozenFx(): (Path, Path, FrozenPolicy, Path) =
    val d     = dir()
    val split = put(d, "full.csv", splitCsv(Cal ++ Held))
    val pol   = frozenPolicy(d, split)
    val cands = put(d, "all.jsonl", candJsonl((Cal ++ Held).map(_.candidate)))
    (split, pol, frozen(pol), cands)

  test("F3 Calibrate freezes llmModel (the model in force)") {
    val (_, _, f, _) = frozenFx()
    assertEquals(f.llmModel, Some(DefaultModel))
  }

  test(
    "F3 FrozenPolicy.modelOverrideDiffers: differing model true; same, absent, other keys false"
  ) {
    val (_, _, f, _) = frozenFx()
    assertEquals(FrozenPolicy.ModelOverrideKey, ModelKey)
    assert(FrozenPolicy.modelOverrideDiffers(f, modelOverride))
    assert(!FrozenPolicy.modelOverrideDiffers(f, Map(ModelKey -> DefaultModel)))
    assert(!FrozenPolicy.modelOverrideDiffers(f, Map.empty))
    assert(!FrozenPolicy.modelOverrideDiffers(f, Map("scholarchain.er.llm.max-calls" -> "5")))
    // an older policy that froze no model never differs
    assert(!FrozenPolicy.modelOverrideDiffers(f.copy(llmModel = None), modelOverride))
  }

  test("F3 an older frozen file without the llmModel field still loads") {
    val (_, pol, f, _) = frozenFx()
    val json           = ujson.read(Files.readString(pol))
    json.obj.remove("llmModel")
    val old = FrozenPolicy.read(ujson.write(json))
    assertEquals(old.map(_.llmModel), Right(None))
    assertEquals(old.map(_.policyVersion), Right(f.policyVersion))
  }

  test("SAFETY F3 Tooling.config refuses a differing model --override: key named, no values") {
    val (_, pol, _, _) = frozenFx()
    val r              = Tooling.config(
      argv("policy" -> pol.toString, "override" -> s"$ModelKey=$OtherModel"),
      Map.empty,
    )
    val msg = r.left.getOrElse(fail(s"expected a refusal, got $r"))
    assert(msg.contains(ModelKey), msg)
    assert(!msg.contains(OtherModel), s"override value leaked: $msg")
    assert(!msg.contains(DefaultModel), s"frozen value leaked: $msg")
  }

  test("F3 Tooling.config accepts a same-model --override, an unrelated one, and no override") {
    val (_, pol, _, _) = frozenFx()
    val same           =
      Tooling.config(
        argv("policy" -> pol.toString, "override" -> s"$ModelKey=$DefaultModel"),
        Map.empty,
      )
    assert(same.isRight, same.toString)
    assertEquals(same.map(_._1.llm.model), Right(DefaultModel))
    assert(Tooling.config(argv("policy" -> pol.toString), Map.empty).isRight)
    // without --policy there is no frozen model, so any model is allowed
    val free = Tooling.config(argv("override" -> s"$ModelKey=$OtherModel"), Map.empty)
    assertEquals(free.map(_._1.llm.model), Right(OtherModel))
  }

  test("F3 Tooling.config: an older frozen file without llmModel skips the model check") {
    val (split, pol, f, _) = frozenFx()
    val old                = put(
      split.getParent,
      "old-policy.json",
      WirePickle.write(f.copy(llmModel = None)),
    )
    val r = Tooling.config(
      argv("policy" -> old.toString, "override" -> s"$ModelKey=$OtherModel"),
      Map.empty,
    )
    assert(r.isRight, r.toString)
    assertEquals(r.map(_._1.llm.model), Right(OtherModel))
  }

  test("SAFETY F3 Evaluate refuses a differing model --override and writes nothing") {
    val (split, pol, _, cands) = frozenFx()
    val out                    = dir()
    val r   = runEvaluate(split, cands, pol, out, "override" -> s"$ModelKey=$OtherModel")._1
    val msg = r.left.getOrElse(fail(s"expected a refusal, got $r"))
    assert(msg.contains(ModelKey), msg)
    assert(!msg.contains(OtherModel), msg)
    assert(!Files.exists(out.resolve("er-v1-heldout-eval.json")), "no result was written")
    assertEquals(ledgerLines(split), Nil, "a refused run leaves no ledger entry")
  }

  test("F3 Evaluate header (JSON and markdown) carries splitSha256, overrides and the ledger") {
    val (split, pol, _, cands) = frozenFx()
    val out                    = dir()
    assertEquals(runEvaluate(split, cands, pol, out)._1, Right(()))
    val j = ujson.read(Files.readString(out.resolve("er-v1-heldout-eval.json")))
    assertEquals(j("splitSha256").str, Io.sha256(split))
    assertEquals(j("overrides").obj.size, 0)
    val ledger = split.toAbsolutePath.getParent.resolve("heldout-evaluations.jsonl").toString
    assertEquals(j("ledger").str, ledger)
    val md = Files.readString(out.resolve("er-v1-heldout-eval.md"))
    assert(md.contains(s"Ledger `$ledger`"), md)
    assert(md.contains(s"split checksum `${Io.sha256(split)}`"), md)
    assert(md.contains("overrides: none"), md)
    // a disclosed same-model override is accepted and listed (key=value) in both headers
    val (split2, pol2, _, cands2) = frozenFx()
    val out2                      = dir()
    val ok = runEvaluate(split2, cands2, pol2, out2, "override" -> s"$ModelKey=$DefaultModel")._1
    assertEquals(ok, Right(()))
    val j2 = ujson.read(Files.readString(out2.resolve("er-v1-heldout-eval.json")))
    assertEquals(j2("overrides").obj.map((k, v) => k -> v.str).toMap, Map(ModelKey -> DefaultModel))
    val md2 = Files.readString(out2.resolve("er-v1-heldout-eval.md"))
    assert(md2.contains(s"overrides: $ModelKey=$DefaultModel"), md2)
  }

  test("SAFETY F3 Evaluate: a split whose checksum differs from the frozen policy is refused") {
    val (split, pol, _, cands) = frozenFx()
    // one byte appended: same rows, different checksum
    val changed = put(split.getParent, "changed.csv", Files.readString(split) + "\n")
    assertNotEquals(Io.sha256(changed), Io.sha256(split))
    val out = dir()
    val r   = runEvaluate(changed, cands, pol, out)._1
    assert(r.left.exists(_.contains("not calibrated on this split")), r.toString)
    assert(!Files.exists(out.resolve("er-v1-heldout-eval.json")), "no result was written")
    assertEquals(ledgerLines(changed), Nil)
  }

  test(
    "L5 the frozen min-confidence is used by FinalDecisions: an LLM accept below it stays UNRESOLVED"
  ) {
    val d     = dir()
    val split = put(d, "full.csv", splitCsv(Cal ++ Held))
    val f     = frozen(frozenPolicy(d, split))
    // default-like thresholds (the calibrated .67 would accept InBand before the LLM is asked)
    val base = f.copy(
      researcher = ErFx.cfg.researcher,
      work = ErFx.cfg.work,
      erEnv = Some(Map.empty),
    )
    val pol95  = put(d, "p95.json", WirePickle.write(base.copy(llmMinConfidence = Some(0.95))))
    val pol70  = put(d, "p70.json", WirePickle.write(base.copy(llmMinConfidence = Some(0.7))))
    val cache  = AdjudicationCache.open(d.resolve("cache.jsonl"))._1
    val client = new FakeLlmClient(
      ErFx.cfg.llm.model,
      (_, u) =>
        val l = "leftId: (\\S+)".r.findFirstMatchIn(u).get.group(1)
        val r = "rightId: (\\S+)".r.findFirstMatchIn(u).get.group(1)
        Right(
          s"""{"leftId":"$l","rightId":"$r","outcome":"accept","confidence":0.9,"citedFields":["left.name"],"reasonCodes":["same_name"]}"""
        ),
    )
    new Adjudicator(client, cache, ErFx.cfg, prompt, "unit", 10)
      .adjudicate(InBand, ViewMap(InBand.leftId), ViewMap(InBand.rightId))
    var runs                                      = 0
    def outcome(extra: (String, String)*): String =
      runs += 1
      val n = runs
      val o = d.resolve(s"out-${n}.jsonl")
      val r = FinalDecisions.run(
        argv(
          Seq(
            "candidates" -> put(d, s"c${n}.jsonl", candJsonl(List(InBand))).toString,
            "views"      -> put(d, s"v${n}.jsonl", viewsJsonl(AllViews)).toString,
            "cache"      -> d.resolve("cache.jsonl").toString,
            "out"        -> o.toString,
            "summary"    -> d.resolve(s"s${n}.json").toString,
          ) ++ extra*
        ),
        new Sink().stream,
        Map.empty,
      )
      assertEquals(r, Right(()))
      ujson.read(Files.readAllLines(o).get(0))("outcome").str
    assertEquals(
      outcome("policy" -> pol70.toString),
      "accepted",
      "frozen 0.7: the 0.9 accept applies",
    )
    assertEquals(outcome("policy" -> pol95.toString), "unresolved", "frozen 0.95: 0.9 is too low")
  }

  // ---- SplitGold (tool) -------------------------------------------------------------------------------

  private def labelsCsv(rs: List[(String, String, String)]): String =
    Csv.write(List("pair_id", "entity_kind", "label") :: rs.map((a, b, c) => List(a, b, c)))

  private def mk(kind: String, label: String, n: Int, tag: String): List[(String, String, String)] =
    (1 to n).toList.map(i => (f"P$tag$i%03d", kind, label))

  private val Labels170 =
    mk("researcher", "MATCH", 60, "rm") ++ mk("researcher", "NON_MATCH", 50, "rn") ++
      mk("work", "MATCH", 30, "wm") ++ mk("work", "NON_MATCH", 30, "wn")

  private def runSplit(labels: String, extra: (String, String)*): (Either[String, Unit], Path) =
    val d   = dir()
    val out = d.resolve("split.csv")
    val r   = SplitGold.run(
      argv(Seq("labels" -> put(d, "labels.csv", labels).toString, "out" -> out.toString) ++ extra*),
      new Sink().stream,
    )
    (r, out)

  test("SplitGold.run: 170 definitive + 7 UNSURE -> 102 / 68, UNSURE excluded and counted") {
    val unsure   = mk("researcher", "UNSURE", 4, "ru") ++ mk("work", "UNSURE", 3, "wu")
    val (r, out) = runSplit(labelsCsv(Labels170 ++ unsure))
    assertEquals(r, Right(()))
    val rows = Csv.parse(Files.readString(out))
    assertEquals(rows.head, List("pair_id", "entity_kind", "label", "split"))
    assertEquals(rows.tail.size, 170)
    assertEquals(rows.tail.count(_.last == "calibration"), 102)
    assertEquals(rows.tail.count(_.last == "heldout"), 68)
    assert(!rows.tail.exists(_(2) == "UNSURE"))
    val s = ujson.read(Files.readString(out.resolveSibling("split.summary.json")))
    assertEquals(s("excludedUnsure").num.toInt, 7)
    assertEquals(s("rows").num.toInt, 177)
    assertEquals((s("calibration").num.toInt, s("heldout").num.toInt), (102, 68))
    val c = s("counts").arr
      .find(x =>
        x("kind").str == "researcher" && x("label").str == "MATCH" && x(
          "split"
        ).str == "calibration"
      )
      .get
    assertEquals(c("count").num.toInt, 36)
  }

  test("SplitGold.run: the same seed gives byte-identical output, another seed does not") {
    val csv = labelsCsv(Labels170)
    val a   = Files.readString(runSplit(csv, "seed" -> "11")._2)
    val b   = Files.readString(runSplit(csv, "seed" -> "11")._2)
    val c   = Files.readString(runSplit(csv, "seed" -> "12")._2)
    assertEquals(a, b)
    assertNotEquals(a, c)
  }

  test(
    "SplitGold.run refuses an unknown label, an empty label, a duplicate id and a bad fraction"
  ) {
    val bad = runSplit(labelsCsv(List(("P1", "work", "MAYBE"))))._1
    assert(bad.left.exists(_.contains("label must be MATCH, NON_MATCH or UNSURE")), bad.toString)
    val empty = runSplit(labelsCsv(List(("P1", "work", ""))))._1
    assert(empty.left.exists(_.contains("label must be")), empty.toString)
    val dup = runSplit(labelsCsv(List(("P1", "work", "MATCH"), ("P1", "work", "NON_MATCH"))))._1
    assertEquals(dup, Left("duplicate pair_id in labels"))
    val kind = runSplit(labelsCsv(List(("P1", "paper", "MATCH"))))._1
    assert(kind.left.exists(_.contains("unknown entity_kind")), kind.toString)
    val frac = runSplit(labelsCsv(Labels170), "fraction" -> "1.5")._1
    assertEquals(frac, Left("--fraction must be in (0,1)"))
  }

  test("SplitGold.run refuses to overwrite an existing split") {
    val d   = dir()
    val out = put(d, "split.csv", "keep")
    val r   = SplitGold.run(
      argv("labels" -> put(d, "l.csv", labelsCsv(Labels170)).toString, "out" -> out.toString),
      new Sink().stream,
    )
    assert(r.left.exists(_.contains("refusing to overwrite")), r.toString)
    assertEquals(Files.readString(out), "keep")
  }

  test("SplitGold.run: a missing column is named") {
    val d = dir()
    val r = SplitGold.run(
      argv(
        "labels" -> put(d, "l.csv", "pair_id,label\nP1,MATCH\n").toString,
        "out"    -> d.resolve("s.csv").toString,
      ),
      new Sink().stream,
    )
    assert(r.left.exists(_.contains("lacks column(s): entity_kind")), r.toString)
  }

  // ---- AdjudicateBand and FinalDecisions ---------------------------------------------------------------

  private def pairViews(l: String, r: String): List[EntityView] =
    List(
      person(l, SourceType.Dblp, "Wei Wang", authors = List("Li Zhang")),
      person(r, SourceType.Crossref, "W. Wang", authors = List("Zhang, Li")),
    )

  private val Mid =
    Map("name_sim" -> 1.0, "initials_compat" -> 1.0, "coauthor_overlap" -> 1.0) // .79
  private val InBand = cand("mention:a", "mention:b", R, Mid)
  private val Closer =
    cand("mention:c", "mention:d", R, rf(0.9, 1.0) + ("initials_compat" -> 1.0)) // .762
  // HJ3 evidence makes this pair CONFLICT (a plain ORCID disagreement is REJECTED since repair 03)
  private val Conflict = cand("mention:e", "mention:f", R, Mid + ("exact_id_conflict" -> 1.0))
  private val Outside  = cand("mention:g", "mention:h", R, Map.empty)
  private val AllCands = List(InBand, Closer, Conflict, Outside)
  private val AllViews =
    List("a" -> "b", "c" -> "d", "e" -> "f", "g" -> "h").flatMap((x, y) =>
      pairViews(s"mention:$x", s"mention:$y")
    )
  private val ViewMap = AllViews.map(v => v.id -> v).toMap
  private val prompt  = PromptTemplate.load().fold(e => throw new AssertionError(e), identity)

  private def viewsJsonl(vs: List[EntityView]): String =
    vs.map(v => WirePickle.write(v)).mkString("", "\n", "\n")

  private def emptyCache(): AdjudicationCache = AdjudicationCache.open(dir().resolve("c.jsonl"))._1

  private def pid(c: MatchCandidate) = ErIds.pairId(c.entityKind, c.leftId, c.rightId)

  test("plan: only UNRESOLVED candidates inside the LLM band are eligible; CONFLICT never") {
    val p = AdjudicateBand.plan(AllCands, ViewMap, emptyCache(), ErFx.cfg, prompt, Set.empty, 60)
    assertEquals(p.eligible, 2)
    assertEquals(p.cached, 0)
    assertEquals(p.toCall.map(_.pairId).toSet, Set(pid(InBand), pid(Closer)))
    assert(!p.toCall.exists(_.candidate == Conflict))
    assert(!p.toCall.exists(_.candidate == Outside))
    assert(p.estCostUsd > 0.0)
  }

  test(
    "plan: order is closeness to the band centre (.675), priority pairs first, capped at max-calls"
  ) {
    val none = AdjudicateBand.plan(AllCands, ViewMap, emptyCache(), ErFx.cfg, prompt, Set.empty, 60)
    assertEquals(
      none.toCall.map(_.pairId),
      List(pid(Closer), pid(InBand)),
    ) // .762 is nearer than .79
    val pri =
      AdjudicateBand.plan(AllCands, ViewMap, emptyCache(), ErFx.cfg, prompt, Set(pid(InBand)), 60)
    assertEquals(pri.toCall.map(_.pairId), List(pid(InBand), pid(Closer)))
    val capped =
      AdjudicateBand.plan(AllCands, ViewMap, emptyCache(), ErFx.cfg, prompt, Set.empty, 1)
    assertEquals(capped.toCall.size, 1)
    assertEquals(capped.eligible, 2)
    val zero = AdjudicateBand.plan(AllCands, ViewMap, emptyCache(), ErFx.cfg, prompt, Set.empty, 0)
    assertEquals(zero.toCall, Nil)
  }

  test("plan: a cached candidate is counted as cached and is not planned for a call") {
    val cache  = emptyCache()
    val client = new FakeLlmClient(
      ErFx.cfg.llm.model,
      (_, u) =>
        val l = "leftId: (\\S+)".r.findFirstMatchIn(u).get.group(1)
        val r = "rightId: (\\S+)".r.findFirstMatchIn(u).get.group(1)
        Right(
          s"""{"leftId":"$l","rightId":"$r","outcome":"accept","confidence":0.9,"citedFields":["left.name"],"reasonCodes":["same_name"]}"""
        ),
    )
    new Adjudicator(client, cache, ErFx.cfg, prompt, "unit", 10)
      .adjudicate(InBand, ViewMap(InBand.leftId), ViewMap(InBand.rightId))
    val p = AdjudicateBand.plan(AllCands, ViewMap, cache, ErFx.cfg, prompt, Set.empty, 60)
    assertEquals((p.eligible, p.cached), (2, 1))
    assertEquals(p.toCall.map(_.pairId), List(pid(Closer)))
  }

  private def bandFiles(): (Path, Path, Path) =
    val d = dir()
    (
      put(d, "cands.jsonl", candJsonl(AllCands)),
      put(d, "views.jsonl", viewsJsonl(AllViews)),
      d.resolve("cache.jsonl"),
    )

  private def runBand(
      env: Map[String, String],
      extra: List[String],
  ): (Either[String, Unit], Sink, Path) =
    val (c, v, cache) = bandFiles()
    val s             = new Sink
    val r             = AdjudicateBand.run(
      Args.parse(
        List("--candidates", c.toString, "--views", v.toString, "--cache", cache.toString) ++ extra
      ),
      s.stream,
      env,
    )
    (r, s, cache)

  test("SAFETY AdjudicateBand: no call without --confirm (and no client is even built)") {
    // No API key in the environment. If the tool tried to build a client or call the model without
    // --confirm it would fail with 'environment variable ... is not set'; it must succeed instead.
    val (r, s, cache) = runBand(Map.empty, Nil)
    assertEquals(r, Right(()))
    assert(s.text.contains("No call made. Re-run with --confirm"), s.text)
    assert(s.text.contains("eligible (unresolved, in LLM band): 2"), s.text)
    assert(s.text.contains("calls planned (cap max-calls=60):  2"), s.text)
    assert(!Files.exists(cache), "nothing was cached, so nothing was called")
  }

  test("SAFETY AdjudicateBand: --confirm without the key is refused, naming the variable only") {
    val (r, _, cache) = runBand(Map.empty, List("--confirm"))
    assertEquals(r, Left("environment variable SCHOLARCHAIN_ANTHROPIC_API_KEY is not set"))
    assert(!Files.exists(cache))
  }

  test("AdjudicateBand: ANTHROPIC_API_KEY (the Claude Code variable) is never read") {
    val (r, _, _) =
      runBand(Map("ANTHROPIC_API_KEY" -> "sk-ant-WRONG-VARIABLE-0001"), List("--confirm"))
    assertEquals(r, Left("environment variable SCHOLARCHAIN_ANTHROPIC_API_KEY is not set"))
  }

  test("AdjudicateBand: provider bedrock fails with the documented message, before any call") {
    val (r, s, _) = runBand(
      Map(ApiKey.EnvName -> SecretSuite.DummyKey),
      List("--override", "scholarchain.er.llm.provider=bedrock", "--confirm"),
    )
    assertEquals(r, Left("LLM provider 'bedrock' is not implemented in Phase 05"))
    assert(!s.text.contains(SecretSuite.DummyKey))
  }

  test("AdjudicateBand: an estimated cost above max-cost-usd is refused even without --confirm") {
    val (r, _, _) = runBand(Map.empty, List("--override", "scholarchain.er.llm.max-cost-usd=0"))
    assert(r.left.exists(_.contains("estimated cost exceeds er.llm.max-cost-usd")), r.toString)
  }

  test("AdjudicateBand: --max-calls must be a non-negative integer") {
    val (r, _, _) = runBand(Map.empty, List("--max-calls", "-3"))
    assertEquals(r, Left("--max-calls must be a non-negative integer"))
  }

  test(
    "AdjudicateBand: --confirm with a dummy key and --max-calls 0 makes zero calls and prints no key"
  ) {
    // max-calls 0 plans no call, so the (real) client is built but never used: no network traffic.
    val (r, s, cache) =
      runBand(Map(ApiKey.EnvName -> SecretSuite.DummyKey), List("--max-calls", "0", "--confirm"))
    assertEquals(r, Right(()))
    assert(s.text.contains("live calls: 0"), s.text)
    assert(!s.text.contains(SecretSuite.DummyKey), "the key must never be printed")
    assert(!Files.exists(cache))
  }

  test("SAFETY FinalDecisions: CONFLICT stays CONFLICT even when the cache holds an LLM accept") {
    val d      = dir()
    val cache  = AdjudicationCache.open(d.resolve("cache.jsonl"))._1
    val client = new FakeLlmClient(
      ErFx.cfg.llm.model,
      (_, u) =>
        val l = "leftId: (\\S+)".r.findFirstMatchIn(u).get.group(1)
        val r = "rightId: (\\S+)".r.findFirstMatchIn(u).get.group(1)
        Right(
          s"""{"leftId":"$l","rightId":"$r","outcome":"accept","confidence":0.95,"citedFields":["left.name"],"reasonCodes":["same_name"]}"""
        ),
    )
    val adj = new Adjudicator(client, cache, ErFx.cfg, prompt, "unit", 10)
    // the cache holds an accept for BOTH the in-band pair and the CONFLICT pair
    adj.adjudicate(InBand, ViewMap(InBand.leftId), ViewMap(InBand.rightId))
    adj.adjudicate(Conflict, ViewMap(Conflict.leftId), ViewMap(Conflict.rightId))
    assertEquals(cache.size, 2)
    val out     = d.resolve("decisions.jsonl")
    val summary = d.resolve("summary.json")
    val r       = FinalDecisions.run(
      argv(
        "candidates" -> put(d, "cands.jsonl", candJsonl(List(InBand, Conflict))).toString,
        "views"      -> put(d, "views.jsonl", viewsJsonl(AllViews)).toString,
        "cache"      -> d.resolve("cache.jsonl").toString,
        "out"        -> out.toString,
        "summary"    -> summary.toString,
      ),
      new Sink().stream,
      Map.empty,
    )
    assertEquals(r, Right(()))
    val ds     = Files.readAllLines(out).toArray.toList.map(l => ujson.read(l.toString))
    val byLeft = ds.map(j => j("leftId").str -> j).toMap
    assertEquals(byLeft("mention:a")("outcome").str, "accepted")
    assertEquals(byLeft("mention:a")("matchPath").str, "llm")
    assertEquals(
      byLeft("mention:a")("modelVersion").str,
      s"${ErFx.cfg.llm.model}|er-adjudication-v1",
    )
    assertEquals(byLeft("mention:e")("outcome").str, "conflict")
    assertEquals(byLeft("mention:e")("matchPath").str, "exact_id")
    assert(
      byLeft("mention:e").obj.get("modelVersion").forall(_.isNull),
      "a CONFLICT has no model version",
    )
    val sm = ujson.read(Files.readString(summary))
    assertEquals(sm("byOutcome")("accepted").num.toInt, 1)
    assertEquals(sm("byOutcome")("conflict").num.toInt, 1)
    assertEquals(sm("llmDecisions").num.toInt, 1)
  }

  test(
    "FinalDecisions: without a cache no decision uses the llm path and output is deterministic"
  ) {
    val d              = dir()
    val c              = put(d, "cands.jsonl", candJsonl(AllCands))
    def run(n: String) =
      val o = d.resolve(s"$n.jsonl")
      val r = FinalDecisions.run(
        argv(
          "candidates" -> c.toString,
          "out"        -> o.toString,
          "summary"    -> d.resolve(s"$n.json").toString,
        ),
        new Sink().stream,
        Map.empty,
      )
      assertEquals(r, Right(()))
      Files.readString(o)
    val a = run("a")
    assertEquals(a, run("b"))
    assert(!a.contains("\"matchPath\":\"llm\""))
    assertEquals(a.linesIterator.size, 4)
  }

  test("Args.parse: values, repeatable options, bare flags; a flag before a value-less option") {
    val a = Args.parse(
      List("--x", "1", "--x", "2", "--confirm", "--override", "k=v", "--override", "a=b=c")
    )
    assertEquals(a.values("x"), List("1", "2"))
    assertEquals(a.get("x"), Some("2"))
    assert(a.flags.contains("confirm"))
    assertEquals(a.overrides, Map("k" -> "v", "a" -> "b=c"))
    assertEquals(Args.parse(List("--a", "--b", "v")).flags, Set("a"))
  }
