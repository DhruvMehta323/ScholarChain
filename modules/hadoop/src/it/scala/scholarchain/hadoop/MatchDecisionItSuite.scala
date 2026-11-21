package scholarchain.hadoop

import scholarchain.core.*
import scholarchain.er.*

import ErItSupport.*
import ItSupport.newRoot

/** HJ7 on SYNTHETIC candidates: decisions are split by outcome, every decision equals the pure
  * policy applied to the candidate, exact-identifier evidence drives the `exact_id` path, and a
  * CONFLICT is never accepted. Researcher `reject` is raised to 0.60 so that a weak pair is
  * rejected (an override recorded in the run configuration).
  *
  * Job launches: 5 (hj6a, hj6b, hj6c, hj6d, hj7), one LocalJobRunner run in `beforeAll`.
  */
class MatchDecisionItSuite extends munit.FunSuite:
  private var s: Setup                   = scala.compiletime.uninitialized
  private var outcomes: List[JobOutcome] = Nil
  private var cfg: ErConfig              = scala.compiletime.uninitialized

  private val sameTitle = "Isotropic graphene membrane permeability"
  private val twoTitle  = "Anisotropic perovskite photovoltaic degradation"

  private val recs: List[Rec] = List(
    // exact id, no conflict: the same DOI on both sides, same title
    dblp(
      "e1",
      "Cryogenic neutrino detector calibration",
      2018,
      List("Eva Lindqvist"),
      Some("10.9999/it.e"),
    ),
    crossref(
      "e1",
      "Cryogenic neutrino detector calibration",
      2018,
      List("Eva Lindqvist"),
      Some("10.9999/it.e"),
    ),
    // HJ3 CONFLICT evidence: the DOI agrees, but the evidence says two DBLP works carry it
    dblp("k1", sameTitle, 2019, List("Finn Aaltonen"), Some("10.9999/it.k")),
    crossref("k1", sameTitle, 2019, List("Finn Aaltonen"), Some("10.9999/it.k")),
    // two different DOIs on identical title and year: an identifier disagreement
    dblp("n1", twoTitle, 2020, List("Gita Narayan"), Some("10.9999/it.n1")),
    crossref("n2", twoTitle, 2020, List("Gita Narayan"), Some("10.9999/it.n2")),
    // a weak researcher pair: same family and initial, other given name, other works, far years
    dblp(
      "w1",
      "Seismic tomography of the mantle transition",
      2001,
      List("Anna Mortenson", "Hugo Alder"),
    ),
    crossref(
      "w2",
      "Coral reef bleaching thermal thresholds",
      2020,
      List("Alicia Mortenson", "Ivy Brandt"),
    ),
  )

  override def beforeAll(): Unit =
    val c = conf(
      "scholarchain.er.researcher.threshold.reject"       -> "0.60",
      "scholarchain.er.researcher.threshold.llm-band-low" -> "0.60",
    )
    s = setup(newRoot("er-decision"), c)
    val (w, m) = build(s, recs)
    writeCurated(s, w, m)
    writeConflict(s, "10.9999/it.k", List(recs(2).workId, recs(3).workId, "work:dblp:conf/it/k2"))
    outcomes = runEr(s, c)
    cfg = s.run.erConfig

  private def decs = decisions(s)

  test("decisions are written split by outcome directory and the jobs succeed") {
    assert(outcomes.forall(_.success), outcomes.map(o => o.jobId -> o.success).toString)
    val dirs = decs.map(_._1).toSet
    assert(dirs.forall(_.startsWith("outcome=")), dirs.toString)
    decs.foreach((d, x) => assertEquals(d, s"outcome=${x.outcome.wire}"))
    assertEquals(decs.size, candidates(s).size)
  }

  test("every outcome occurs: accepted, rejected, unresolved and conflict") {
    val got = decs.map(_._2.outcome).toSet
    assertEquals(
      got,
      ResolutionOutcome.values.toSet,
      decs.map(d => d._2.outcome.wire -> d._2.matchPath.wire).toString,
    )
  }

  test(
    "each decision equals the pure er-v1 policy applied to its candidate (policy, thresholds, path)"
  ) {
    val byPair = candidates(s).map(c => (c.leftId, c.rightId) -> c).toMap
    decs.foreach { (_, d) =>
      val c = byPair((d.leftId, d.rightId))
      val r = Policy.decide(c.entityKind, c.features, cfg.thresholds(c.entityKind))
      assertEquals(
        (d.outcome, d.matchPath, d.rulesFired, d.reasonCodes),
        (r.outcome, r.path, r.rulesFired, r.reasonCodes),
      )
      assertEquals(d.policyVersion, "er-v1")
      assertEquals(d.runId, s.run.runId)
      assertEquals(d.features("policy.threshold.reject"), cfg.thresholds(c.entityKind).reject)
      assertEquals(d.provenance.parameters("er.researcher.threshold.reject"), "0.6")
      assertEquals(d.modelVersion, None) // no LLM in MapReduce
    }
  }

  test(
    "exact-identifier evidence feeds the exact_id path: agreeing DOI accepted, disagreeing DOIs CONFLICT"
  ) {
    def of(a: Rec, b: Rec) =
      decs.map(_._2).find(d => Set(d.leftId, d.rightId) == Set(a.workId, b.workId)).get
    val ok = of(recs(0), recs(1))
    assertEquals((ok.outcome, ok.matchPath), (ResolutionOutcome.Accepted, MatchPath.ExactId))
    val bad = of(recs(4), recs(5))
    assertEquals((bad.outcome, bad.matchPath), (ResolutionOutcome.Conflict, MatchPath.ExactId))
  }

  test(
    "a pair in HJ3 CONFLICT evidence is CONFLICT even though its DOIs agree; no CONFLICT is accepted"
  ) {
    val flagged =
      decs.map(_._2).find(d => Set(d.leftId, d.rightId) == Set(recs(2).workId, recs(3).workId)).get
    assertEquals(flagged.features("doi_agreement"), 1.0)
    assertEquals(flagged.features("exact_id_conflict"), 1.0)
    assertEquals(flagged.outcome, ResolutionOutcome.Conflict)
    val conflictRows = decs.filter(_._2.features.get("exact_id_conflict").contains(1.0))
    assert(conflictRows.nonEmpty)
    assert(conflictRows.forall(_._2.outcome == ResolutionOutcome.Conflict))
    assert(
      !decs.exists((dir, d) =>
        dir == "outcome=accepted" && d.features.get("exact_id_conflict").contains(1.0)
      )
    )
    // Without the evidence the same pair would be accepted: the pure policy says so.
    val c = candidates(s).find(c => c.leftId == flagged.leftId && c.rightId == flagged.rightId).get
    val without =
      Policy.decide(c.entityKind, c.features - "exact_id_conflict", cfg.thresholds(c.entityKind))
    assertEquals(without.outcome, ResolutionOutcome.Accepted)
  }

  test(
    "counters: decisions by outcome and path add up to the rows written; conflicts are counted"
  ) {
    val h = outcomes.find(_.jobId == ErKeys.DecisionJobId).get
    assertEquals(h.decisions.values.sum, decs.size.toLong)
    assertEquals(h.c(PipelineCounter.DECISIONS), decs.size.toLong)
    assertEquals(
      h.c(PipelineCounter.CONFLICT),
      decs.count(_._2.outcome == ResolutionOutcome.Conflict).toLong,
    )
    val byKey = decs
      .groupBy(x => s"${x._2.outcome.wire}.${x._2.matchPath.wire}")
      .map((k, v) => k -> v.size.toLong)
    assertEquals(h.decisions, byKey)
    assertEquals(h.rows("match-decisions"), decs.size.toLong)
  }
end MatchDecisionItSuite
