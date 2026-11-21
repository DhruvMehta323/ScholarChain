package scholarchain.er.tools

import java.io.PrintStream
import java.nio.file.{Files, Path}

import scala.util.Try

import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}
import scholarchain.er.*

/** `BuildViews`: builds `entity-view` JSON Lines from the curated `works` and `author-mentions`
  * tables of a pipeline run. Arguments: `--works <dir|file> --mentions <dir|file> --out <file>`.
  * Reads curated rows only (no DBLP, no network).
  */
object BuildViews:
  def run(a: Args, out: PrintStream): Either[String, Unit] =
    for
      wp <- a.path("works")
      mp <- a.path("mentions")
      op <- a.path("out")
      ws <- readTable[WorkRecord](wp)
      ms <- readTable[AuthorMention](mp)
      views = EntityViews.build(ws.distinctBy(_.workId), ms.distinctBy(_.mentionId))
      _ <- Io.writeNew(op, views.map(v => WirePickle.write(v)).mkString("", "\n", "\n"))
    yield out.println(
      s"views written: ${views.size} (works ${ws.size}, mentions ${ms.size}) -> $op"
    )

  private def readTable[A: Contract](p: Path)(using WirePickle.Reader[A]): Either[String, List[A]] =
    val rs = Io.lines(p).map(l => Wire.read[A](l))
    rs.collectFirst { case Left(e) => Left(e.message) }
      .getOrElse(Right(rs.collect { case Right(x) => x }))

  def main(argv: Array[String]): Unit = Tooling.exit(run(Args.parse(argv.toSeq), System.out))

/** `BuildReviewQueue`: samples about 200 pairs from the real candidate output and writes the BLIND
  * review CSV. The CSV shows the two records only: no score, no feature value, no predicted
  * outcome, no source-of-sample information, and rows are shuffled so row order carries no score.
  *
  * Arguments: `--candidates <dir|file> --views <file> --out <csv> [--seed 20260101] [--researcher
  * 150] [--work 50] [--code-version X] [--override k=v]`. A sidecar `<out>.sampling.json` records
  * seed, parameters, input checksums and per-stratum counts (never per-pair scores).
  */
object BuildReviewQueue:
  val Header: List[String] =
    val side = (p: String) =>
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
    List("pair_id", "entity_kind") ++ side("left") ++ side("right") ++ List("label", "notes")

  private def bin(score: Double): String = s"bin${math.min(4, (score * 5).toInt)}"

  /** Hard negatives candidates: same-surname researcher pairs that are not a clean name match, work
    * pairs with similar titles that differ in year or authors.
    */
  def isHard(s: Scored): Boolean =
    val f = s.candidate.features
    s.candidate.entityKind match
      case EntityKind.Researcher =>
        f.get("family_exact").contains(1.0) &&
        (f.get("name_sim").exists(_ < 0.95) || f.get("initials_compat").exists(_ < 1.0))
      case EntityKind.Work =>
        f.get("title_sim").exists(_ >= 0.6) &&
        (f.get("year_distance").exists(_ > 0.0) || f.get("author_overlap").exists(_ < 1.0))

  def stratum(s: Scored): String = s.result.outcome match
    case ResolutionOutcome.Conflict                                       => "conflict"
    case ResolutionOutcome.Accepted if s.result.path == MatchPath.ExactId => "exact"
    case _                                                                => bin(s.result.score)

  /** Largest-remainder allocation of `total` over strata by weight, capped by availability, with
    * redistribution of any spare. Deterministic (ties by stratum name).
    */
  def allocate(
      total: Int,
      weights: Map[String, Double],
      avail: Map[String, Int],
  ): Map[String, Int] =
    val got = scala.collection.mutable.Map.from(weights.keys.map(_ -> 0))
    var rem = total
    var go  = true
    while rem > 0 && go do
      val open = weights.keys.filter(k => got(k) < avail.getOrElse(k, 0)).toList.sorted
      if open.isEmpty then go = false
      else
        val sw      = open.map(weights).sum
        val shares  = open.map(k => k -> rem * weights(k) / sw)
        var granted = 0
        shares.foreach { (k, sh) =>
          val g = math.min(math.floor(sh).toInt, avail(k) - got(k))
          got(k) += g
          granted += g
        }
        rem -= granted
        if rem > 0 then
          val order = shares.sortBy((k, sh) => (-(sh - math.floor(sh)), k)).map(_._1)
          var idx   = 0
          var spent = 0
          while rem > 0 && idx < order.size * 2 && spent < order.size do
            val k = order(idx % order.size)
            if got(k) < avail(k) then
              got(k) += 1
              rem -= 1
              spent += 1
            idx += 1
          if granted == 0 && spent == 0 then go = false
    got.toMap

  /** Picks `n` pairs of one kind. Exact-id pairs are limited to n/10 and conflicts to n/20; the
    * rest is spread over score bins with triple weight for bins within 0.1 of the accept or reject
    * threshold. Inside a stratum, half of the quota is taken from hard negatives first.
    */
  def select(
      pool: Seq[Scored],
      n: Int,
      seed: Long,
      t: Thresholds,
  ): (List[Scored], Map[String, (Int, Int)]) =
    val by                    = pool.groupBy(stratum)
    val avail                 = by.view.mapValues(_.size).toMap
    val fixed                 = Map("exact" -> n / 10, "conflict" -> n / 20)
    val take0                 = fixed.map((k, q) => k -> math.min(q, avail.getOrElse(k, 0)))
    val bins                  = (0 to 4).map(b => s"bin$b").toList
    def near(b: Int): Boolean =
      val (lo, hi) = (b * 0.2, (b + 1) * 0.2)
      List(t.accept, t.reject).exists(x => x + 0.1 >= lo && x - 0.1 <= hi)
    val weights  = bins.zipWithIndex.map((k, b) => k -> (if near(b) then 3.0 else 1.0)).toMap
    val takeBins =
      allocate(n - take0.values.sum, weights, bins.map(k => k -> avail.getOrElse(k, 0)).toMap)
    val quota  = take0 ++ takeBins
    val picked = quota.toList.sortBy(_._1).flatMap { (k, q) =>
      val members = by.getOrElse(k, Nil).sortBy(_.pairId).toVector
      val order   = GoldSplitter.shuffled(members, seed * 17L + k.hashCode)
      val hard    = order.filter(isHard)
      val first   = hard.take((q + 1) / 2)
      val rest    = order.filterNot(first.contains).take(q - first.size)
      first ++ rest
    }
    (picked, quota.map((k, q) => k -> (avail.getOrElse(k, 0), q)))

  private def ids(v: EntityView): String =
    List(
      v.doi.map("doi:" + _),
      v.arxiv.map("arxiv:" + _),
      v.openAlexId.map("openalex:" + _),
      v.orcid.map("orcid:" + _),
    ).flatten.mkString("; ")

  def row(s: Scored, l: EntityView, r: EntityView): List[String] =
    def side(v: EntityView): List[String] =
      val nameOrTitle =
        if v.kind == EntityKind.Researcher then v.name.getOrElse("") else v.title.getOrElse("")
      List(
        v.source.wire,
        v.id,
        nameOrTitle,
        v.authors.take(8).mkString("; "),
        v.title.getOrElse(""),
        v.year.map(_.toString).getOrElse(""),
        v.venue.getOrElse(""),
        ids(v),
        v.affiliation.getOrElse(""),
      )
    List(s.pairId, s.candidate.entityKind.wire) ++ side(l) ++ side(r) ++ List("", "")

  def run(a: Args, out: PrintStream, env: Map[String, String]): Either[String, Unit] =
    for
      cp   <- a.path("candidates")
      vp   <- a.path("views")
      op   <- a.path("out")
      seed <- a.long("seed", 20260101L)
      nR   <- a.long("researcher", 150).map(_.toInt)
      nW   <- a.long("work", 50).map(_.toInt)
      conf <- Tooling.config(a, env)
      cfg = conf._1
      cands <- Io.readCandidates(cp)
      views <- Io.readViews(vp)
      scoredAll = cands.map(Resolver.scored(_, cfg))
      withViews = scoredAll.filter(s =>
        views.contains(s.candidate.leftId) && views.contains(s.candidate.rightId)
      )
      missing = scoredAll.size - withViews.size
      parts   = List(EntityKind.Researcher -> nR, EntityKind.Work -> nW).map { (k, n) =>
        k -> select(withViews.filter(_.candidate.entityKind == k), n, seed, cfg.thresholds(k))
      }
      picked = GoldSplitter.shuffled(parts.flatMap(_._2._1).toVector, seed)
      rows   = picked.map(s => row(s, views(s.candidate.leftId), views(s.candidate.rightId)).toList)
      _ <- Io.writeNew(op, Csv.write(Header :: rows.toList))
      sidecar = ujson.Obj(
        "tool"          -> "BuildReviewQueue",
        "codeVersion"   -> a.get("code-version").getOrElse("unversioned"),
        "policyVersion" -> cfg.policyVersion,
        "featureSet"    -> cfg.featureSetVersion,
        "seed"          -> ujson.Num(seed.toDouble),
        "requested"     -> ujson.Obj("researcher" -> nR, "work" -> nW),
        "selected"      -> ujson.Obj(
          "researcher" -> picked.count(_.candidate.entityKind == EntityKind.Researcher),
          "work"       -> picked.count(_.candidate.entityKind == EntityKind.Work),
        ),
        "candidatesRead"   -> cands.size,
        "candidatesNoView" -> missing,
        "inputs"           -> ujson.Obj(
          "candidates" -> Io.sha256(cp),
          "views"      -> Io.sha256(vp),
        ),
        "strata" -> ujson.Obj.from(
          parts.map((k, v) =>
            k.wire -> ujson.Obj.from(
              v._2.toList
                .sortBy(_._1)
                .map((s, c) => s -> ujson.Obj("available" -> c._1, "selected" -> c._2))
            )
          )
        ),
        "blind" -> "no score, feature or predicted outcome in the CSV; row order shuffled",
      )
      _ <- Io.writeNew(
        op.resolveSibling(op.getFileName.toString.stripSuffix(".csv") + ".sampling.json"),
        ujson.write(sidecar, indent = 2) + "\n",
      )
    yield out.println(
      s"review queue: ${rows.size} rows (researcher ${picked.count(_.candidate.entityKind == EntityKind.Researcher)}, " +
        s"work ${picked.count(_.candidate.entityKind == EntityKind.Work)}), seed $seed, " +
        s"candidates without views: $missing -> $op"
    )

  def main(argv: Array[String]): Unit =
    Tooling.exit(run(Args.parse(argv.toSeq), System.out, sys.env))
