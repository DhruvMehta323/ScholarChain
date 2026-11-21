package scholarchain.er

import java.util.Locale

import scholarchain.core.*

/** Human labels. Wire values in the CSV: MATCH, NON_MATCH, UNSURE (case-insensitive). */
enum GoldLabel(val wire: String):
  case Match    extends GoldLabel("MATCH")
  case NonMatch extends GoldLabel("NON_MATCH")
  case Unsure   extends GoldLabel("UNSURE")

object GoldLabel:
  def parse(s: String): Option[GoldLabel] =
    values.find(_.wire == s.trim.toUpperCase(Locale.ROOT))

final case class LabelRow(pairId: String, kind: EntityKind, label: GoldLabel)

object Split:
  val Calibration = "calibration"
  val HeldOut     = "heldout"

/** Confusion matrix of the ACCEPTED class against MATCH. A pair that is not accepted (rejected,
  * unresolved or conflict) counts as a predicted non-match: an unresolved MATCH is a false
  * negative.
  */
final case class Confusion(tp: Int, fp: Int, fn: Int, tn: Int):
  def n: Int            = tp + fp + fn + tn
  def precision: Double = if tp + fp == 0 then 0.0 else tp.toDouble / (tp + fp)
  def recall: Double    = if tp + fn == 0 then 0.0 else tp.toDouble / (tp + fn)
  def f1: Double        =
    val (p, r) = (precision, recall)
    if p + r == 0.0 then 0.0 else 2 * p * r / (p + r)

object Confusion:
  def of(pairs: Iterable[(Boolean, Boolean)]): Confusion =
    // (isMatch, predictedAccepted)
    pairs.foldLeft(Confusion(0, 0, 0, 0)) { case (c, (m, p)) =>
      (m, p) match
        case (true, true)   => c.copy(tp = c.tp + 1)
        case (false, true)  => c.copy(fp = c.fp + 1)
        case (true, false)  => c.copy(fn = c.fn + 1)
        case (false, false) => c.copy(tn = c.tn + 1)
    }

object GoldSplitter:
  final case class Result(
      assignments: List[(LabelRow, String)],
      excludedUnsure: Int,
  )

  /** Deterministic seeded split, stratified by entity kind x label. UNSURE rows are excluded and
    * counted. The calibration size is `round(fraction * N)` over the N definitive rows (102 of 170
    * at 0.6) and is spread over the strata by largest remainder (ties: stratum name), so the total
    * is exact. Inside a stratum, rows are sorted by pair id and shuffled with `java.util.Random`
    * seeded from `seed` and the stratum name.
    */
  def split(rows: Seq[LabelRow], seed: Long, calibrationFraction: Double): Result =
    val definitive = rows.filter(_.label != GoldLabel.Unsure)
    val strata     = definitive
      .groupBy(r => (r.kind.wire, r.label.wire))
      .toList
      .sortBy(_._1)
    val target = math.round(calibrationFraction * definitive.size).toInt
    val exact  = strata.map((k, rs) => (k, rs.size * calibrationFraction))
    val floors = exact.map((k, e) => k -> math.floor(e).toInt).toMap
    var left   = target - floors.values.sum
    val extra  = exact
      .sortBy((k, e) => (-(e - math.floor(e)), k))
      .map(_._1)
    val quota = scala.collection.mutable.Map.from(floors)
    var i     = 0
    while left > 0 && extra.nonEmpty do
      val k = extra(i % extra.size)
      if quota(k) < strata.find(_._1 == k).get._2.size then
        quota(k) += 1
        left -= 1
      i += 1
    val assigned = strata.flatMap { (k, rs) =>
      val sorted = rs.sortBy(_.pairId)
      val rnd    = java.util.Random(seed * 31L + (k._1 + "|" + k._2).hashCode)
      val order  = shuffle(sorted.toVector, rnd)
      order.zipWithIndex.map((r, idx) =>
        (r, if idx < quota(k) then Split.Calibration else Split.HeldOut)
      )
    }
    Result(assigned.sortBy(_._1.pairId), rows.size - definitive.size)

  private def shuffle[A](v: Vector[A], rnd: java.util.Random): Vector[A] =
    val a = v.toArray[Any]
    var i = a.length - 1
    while i > 0 do
      val j = rnd.nextInt(i + 1)
      val t = a(i)
      a(i) = a(j)
      a(j) = t
      i -= 1
    a.toVector.asInstanceOf[Vector[A]]

  def shuffled[A](v: Vector[A], seed: Long): Vector[A] = shuffle(v, java.util.Random(seed))

/** A labelled pair with the candidate features needed to score it. */
final case class GoldPoint(
    pairId: String,
    kind: EntityKind,
    isMatch: Boolean,
    features: Map[String, Double],
)

final case class SweepRow(accept: Double, confusion: Confusion)

object Calibration:
  /** Accept-threshold grid: 0.50, 0.51, ... 0.99. */
  val Grid: List[Double] = (50 to 99).map(_ / 100.0).toList

  val Objective: String =
    "maximize F1 of the accepted class against MATCH on calibration rows; ties go to higher " +
      "precision, then to the higher accept threshold. reject = largest 0.01 step not above the " +
      "lowest score of a calibration MATCH that reached the probabilistic path (capped at accept); " +
      "LLM band = [reject, accept]."

  /** Predicted accept for a point at an accept threshold: exact-id and deterministic rules are
    * unchanged by the threshold; the probabilistic path accepts at `score >= accept`.
    */
  private def accepted(p: GoldPoint, accept: Double): Boolean =
    Policy.decide(p.kind, p.features, Thresholds(accept, 0.0, 0.0, accept)).outcome ==
      ResolutionOutcome.Accepted

  def sweep(points: Seq[GoldPoint], grid: List[Double] = Grid): List[SweepRow] =
    grid.map(a => SweepRow(a, Confusion.of(points.map(p => (p.isMatch, accepted(p, a))))))

  /** Best row: max F1, then max precision, then max threshold. */
  def choose(rows: List[SweepRow]): SweepRow =
    rows.maxBy(r => (r.confusion.f1, r.confusion.precision, r.accept))

  final case class Outcome(thresholds: Thresholds, sweep: List[SweepRow], chosen: SweepRow)

  def calibrate(
      kind: EntityKind,
      points: Seq[GoldPoint],
      base: Thresholds,
  ): Either[String, Outcome] =
    val mine = points.filter(_.kind == kind)
    if !mine.exists(_.isMatch) || !mine.exists(!_.isMatch) then
      Left(s"calibration rows for '${kind.wire}' need both MATCH and NON_MATCH")
    else
      val rows            = sweep(mine)
      val chosen          = choose(rows)
      val probMatchScores = mine
        .filter(_.isMatch)
        .filter { p =>
          Policy
            .decide(kind, p.features, Thresholds(chosen.accept, 0.0, 0.0, chosen.accept))
            .path ==
            MatchPath.Probabilistic
        }
        .map(p => Policy.score(kind, p.features))
      val reject = probMatchScores.minOption match
        case Some(m) => math.min(math.floor(m * 100.0) / 100.0, chosen.accept)
        case None    => math.min(base.reject, chosen.accept)
      Right(Outcome(Thresholds(chosen.accept, reject, reject, chosen.accept), rows, chosen))
