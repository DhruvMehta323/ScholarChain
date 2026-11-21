package scholarchain.er.tools

import java.io.PrintStream
import java.nio.file.{Files, Path, Paths}

import scala.util.Try

import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}
import scholarchain.er.*

private[tools] object TableIo:
  /** Reads a CSV with a header into rows keyed by column name. */
  def read(p: Path, required: List[String]): Either[String, List[Map[String, String]]] =
    Try(Files.readString(p)).toEither.left.map(_ => s"cannot read $p").flatMap { text =>
      Csv.parse(text) match
        case Nil            => Left(s"$p is empty")
        case header :: body =>
          val missing = required.filterNot(header.contains)
          if missing.nonEmpty then Left(s"$p lacks column(s): ${missing.mkString(", ")}")
          else Right(body.map(r => header.zip(r.padTo(header.size, "")).toMap))
    }

  def kind(s: String): Either[String, EntityKind] =
    EntityKind.values.find(_.wire == s.trim).toRight(s"unknown entity_kind '$s'")

  def codeVersion(a: Args): String = a.get("code-version").getOrElse("unversioned")

/** `SplitGold`: deterministic seeded split of the human labels, stratified by entity kind x label.
  * Arguments: `--labels <csv> --out <split csv> [--seed 20260101] [--fraction 0.6] [--code-version
  * X]`. Writes the split CSV (`pair_id,entity_kind,label,split`) and `<out>.summary.json` (counts
  * by kind x label x split, the number of UNSURE rows excluded). Never overwrites an existing file.
  * UNSURE rows are excluded and counted; an empty or unknown label is an error.
  */
object SplitGold:
  def run(a: Args, out: PrintStream): Either[String, Unit] =
    for
      lp   <- a.path("labels")
      op   <- a.path("out")
      seed <- a.long("seed", 20260101L)
      frac = a.get("fraction").flatMap(_.toDoubleOption).getOrElse(0.6)
      _      <- Either.cond(frac > 0.0 && frac < 1.0, (), "--fraction must be in (0,1)")
      rows   <- TableIo.read(lp, List("pair_id", "entity_kind", "label"))
      labels <- rows.zipWithIndex.foldLeft[Either[String, List[LabelRow]]](Right(Nil)) {
        case (acc, (r, i)) =>
          for
            xs <- acc
            k  <- TableIo.kind(r("entity_kind"))
            l  <- GoldLabel
              .parse(r("label"))
              .toRight(s"row ${i + 2}: label must be MATCH, NON_MATCH or UNSURE")
            _ <- Either.cond(r("pair_id").nonEmpty, (), s"row ${i + 2}: empty pair_id")
          yield xs :+ LabelRow(r("pair_id"), k, l)
      }
      _ <- Either.cond(
        labels.map(_.pairId).distinct.size == labels.size,
        (),
        "duplicate pair_id in labels",
      )
      res = GoldSplitter.split(labels, seed, frac)
      csv = Csv.write(
        List("pair_id", "entity_kind", "label", "split") ::
          res.assignments.map((r, s) => List(r.pairId, r.kind.wire, r.label.wire, s))
      )
      counts = res.assignments
        .groupBy((r, s) => (r.kind.wire, r.label.wire, s))
        .toList
        .sortBy(_._1)
        .map((k, v) =>
          ujson.Obj("kind" -> k._1, "label" -> k._2, "split" -> k._3, "count" -> v.size)
        )
      summary = ujson.Obj(
        "tool"           -> "SplitGold",
        "codeVersion"    -> TableIo.codeVersion(a),
        "seed"           -> ujson.Num(seed.toDouble),
        "fraction"       -> frac,
        "labelsChecksum" -> Io.sha256(lp),
        "rows"           -> labels.size,
        "excludedUnsure" -> res.excludedUnsure,
        "calibration"    -> res.assignments.count(_._2 == Split.Calibration),
        "heldout"        -> res.assignments.count(_._2 == Split.HeldOut),
        "counts"         -> ujson.Arr.from(counts),
      )
      _ <- Io.writeNew(op, csv)
      _ <- Io.writeNew(
        op.resolveSibling(op.getFileName.toString.stripSuffix(".csv") + ".summary.json"),
        ujson.write(summary, indent = 2) + "\n",
      )
    yield out.println(
      s"split: calibration ${res.assignments.count(_._2 == Split.Calibration)}, heldout ${res.assignments.count(_._2 == Split.HeldOut)}, " +
        s"excluded UNSURE ${res.excludedUnsure}, seed $seed -> $op"
    )

  def main(argv: Array[String]): Unit = Tooling.exit(run(Args.parse(argv.toSeq), System.out))

/** `Calibrate`: tunes the accept, reject and LLM-band thresholds on CALIBRATION rows only.
  * Arguments: `--split <csv> --candidates <dir|file> --out-policy <json> --out-sensitivity <csv>
  * [--code-version X] [--override k=v]`. Rows whose split is not `calibration` are discarded at the
  * first step and are never looked up or scored. Objective: see `Calibration.Objective`. Never
  * overwrites an existing file.
  */
object Calibrate:
  def loadCalibration(split: Path): Either[String, List[LabelRow]] =
    TableIo.read(split, List("pair_id", "entity_kind", "label", "split")).flatMap { rows =>
      rows
        .filter(_("split") == Split.Calibration)
        .foldLeft[Either[String, List[LabelRow]]](Right(Nil)) { (acc, r) =>
          for
            xs <- acc
            k  <- TableIo.kind(r("entity_kind"))
            l  <- GoldLabel
              .parse(r("label"))
              .filter(_ != GoldLabel.Unsure)
              .toRight("a calibration row has no definitive label")
          yield xs :+ LabelRow(r("pair_id"), k, l)
        }
    }

  def run(a: Args, out: PrintStream, env: Map[String, String]): Either[String, Unit] =
    for
      sp   <- a.path("split")
      cp   <- a.path("candidates")
      pol  <- a.path("out-policy")
      sens <- a.path("out-sensitivity")
      conf <- Tooling.config(a.copy(values = a.values - "policy"), env)
      cfg = conf._1
      cal <- loadCalibration(sp)
      need = cal.map(r => r.pairId -> r).toMap
      cands <- Io.readCandidates(cp)
      byPair = cands.map(c => ErIds.pairId(c.entityKind, c.leftId, c.rightId) -> c).toMap
      points <- cal.foldLeft[Either[String, List[GoldPoint]]](Right(Nil)) { (acc, r) =>
        for
          xs <- acc
          c  <- byPair.get(r.pairId).toRight(s"no candidate for calibration pair ${r.pairId}")
          _ <- Either.cond(c.entityKind == r.kind, (), s"entity kind mismatch for pair ${r.pairId}")
        yield xs :+ GoldPoint(r.pairId, r.kind, r.label == GoldLabel.Match, c.features)
      }
      outcomes <- List(EntityKind.Researcher, EntityKind.Work)
        .foldLeft[Either[String, List[(EntityKind, Calibration.Outcome)]]](Right(Nil)) { (acc, k) =>
          for
            xs <- acc
            o  <- Calibration.calibrate(k, points, cfg.thresholds(k))
          yield xs :+ (k -> o)
        }
      thr     = outcomes.toMap
      metrics = outcomes.map { (k, o) =>
        val c = o.chosen.confusion
        k.wire -> Map(
          "n"         -> c.n.toDouble,
          "tp"        -> c.tp.toDouble,
          "fp"        -> c.fp.toDouble,
          "fn"        -> c.fn.toDouble,
          "tn"        -> c.tn.toDouble,
          "precision" -> c.precision,
          "recall"    -> c.recall,
          "f1"        -> c.f1,
        )
      }.toMap
      frozen = FrozenPolicy(
        SchemaVersion.Current,
        cfg.policyVersion,
        cfg.featureSetVersion,
        "calibrated-on-calibration-split-only",
        thr(EntityKind.Researcher).thresholds,
        thr(EntityKind.Work).thresholds,
        Calibration.Objective,
        s"accept in {${Calibration.Grid.head} .. ${Calibration.Grid.last}} step 0.01",
        metrics,
        List(
          Io.inputRef("er-gold-split", "gold-split-v1", sp),
          Io.inputRef("er-candidates", cfg.featureSetVersion, cp),
        ),
        TableIo.codeVersion(a),
        Map("calibrationRows" -> need.size.toString, "policyVersion" -> cfg.policyVersion) ++
          a.overrides.map((k, v) => s"override.$k" -> v),
        Some(cfg.llm.minConfidence),
        Some(FrozenPolicy.erEnv(env)),
        Some(cfg.llm.model),
      )
      _ <- Io.writeNew(pol, ujson.write(ujson.read(WirePickle.write(frozen)), indent = 2) + "\n")
      table = Csv.write(
        List(
          "entity_kind",
          "accept",
          "tp",
          "fp",
          "fn",
          "tn",
          "precision",
          "recall",
          "f1",
          "chosen",
        ) ::
          outcomes.flatMap((k, o) =>
            o.sweep.map(r =>
              List(
                k.wire,
                f"${r.accept}%.2f",
                r.confusion.tp.toString,
                r.confusion.fp.toString,
                r.confusion.fn.toString,
                r.confusion.tn.toString,
                f"${r.confusion.precision}%.4f",
                f"${r.confusion.recall}%.4f",
                f"${r.confusion.f1}%.4f",
                if r.accept == o.chosen.accept then "yes" else "",
              )
            )
          )
      )
      _ <- Io.writeNew(sens, table)
    yield outcomes.foreach { (k, o) =>
      out.println(
        s"${k.wire}: accept=${o.thresholds.accept} reject=${o.thresholds.reject} band=[${o.thresholds.llmBandLow},${o.thresholds.llmBandHigh}] " +
          f"calibration P=${o.chosen.confusion.precision}%.4f R=${o.chosen.confusion.recall}%.4f F1=${o.chosen.confusion.f1}%.4f"
      )
    }

  def main(argv: Array[String]): Unit =
    Tooling.exit(run(Args.parse(argv.toSeq), System.out, sys.env))

/** `Evaluate`: the held-out evaluation. Reads HELD-OUT rows (split `heldout`) with the frozen
  * policy and, optionally, the adjudication cache. Run it once, after the policy is frozen.
  * Arguments: `--split <csv> --candidates <dir|file> --policy <frozen json> --out-dir <dir> [--name
  * er-v1] [--views <file> --cache <file>] [--code-version X] [--new-evaluation-reason "<text>"]`.
  * Outputs `<name>-heldout-eval.json` and `.md`. Held-out evaluated once: the evaluation is
  * recorded in a ledger (`--ledger <file>`, default `heldout-evaluations.jsonl` beside the split
  * file) keyed by the split file checksum. A second evaluation of the same split, under ANY
  * `--out-dir` or `--name`, is refused unless `--new-evaluation-reason` is given: that is a new,
  * disclosed evaluation and the reason is written into the result and the ledger. The frozen policy
  * must have been calibrated on exactly this split (its `er-gold-split` checksum), and the frozen
  * `llm.min-confidence` and `SCHOLARCHAIN_ER_*` environment are recorded in the header.
  */
/** The held-out evaluation ledger: one JSON line per evaluation, keyed by the split checksum. */
private[tools] object Ledger:
  val DefaultName = "heldout-evaluations.jsonl"

  def read(p: Path): Either[String, List[String]] =
    if !Files.exists(p) then Right(Nil)
    else
      Try(
        Files.readAllLines(p).toArray.toList.map(_.toString).filter(_.trim.nonEmpty)
      ).toEither.left
        .map(_ => s"cannot read ledger $p")
        .flatMap { ls =>
          Try(ls.map(l => ujson.read(l)("splitSha256").str)).toEither.left
            .map(_ => s"ledger $p is not valid")
        }

  def append(
      p: Path,
      splitSha: String,
      name: String,
      reason: Option[String],
  ): Either[String, Unit] =
    Try {
      Option(p.getParent).foreach(Files.createDirectories(_))
      val line = ujson.write(
        ujson.Obj(
          "splitSha256"           -> splitSha,
          "name"                  -> name,
          "disclosedReEvaluation" -> reason.map(ujson.Str(_)).getOrElse(ujson.Null),
        )
      )
      Files.writeString(
        p,
        line + "\n",
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND,
      )
      ()
    }.toEither.left.map(_ => s"cannot write ledger $p")

object Evaluate:
  private def confJson(c: Confusion): ujson.Obj =
    ujson.Obj(
      "n"         -> c.n,
      "tp"        -> c.tp,
      "fp"        -> c.fp,
      "fn"        -> c.fn,
      "tn"        -> c.tn,
      "precision" -> c.precision,
      "recall"    -> c.recall,
      "f1"        -> c.f1,
    )

  final case class Pred(row: LabelRow, scored: Scored, result: PolicyResult):
    def accepted: Boolean = result.outcome == ResolutionOutcome.Accepted

  def metrics(preds: Seq[Pred]): ujson.Obj =
    val n   = preds.size.max(1).toDouble
    val cnt = (o: ResolutionOutcome) => preds.count(_.result.outcome == o)
    ujson.Obj(
      "confusion" -> confJson(
        Confusion.of(preds.map(p => (p.row.label == GoldLabel.Match, p.accepted)))
      ),
      "counts" -> ujson.Obj(
        "accepted"   -> cnt(ResolutionOutcome.Accepted),
        "rejected"   -> cnt(ResolutionOutcome.Rejected),
        "unresolved" -> cnt(ResolutionOutcome.Unresolved),
        "conflict"   -> cnt(ResolutionOutcome.Conflict),
      ),
      "coverage"       -> (cnt(ResolutionOutcome.Accepted) + cnt(ResolutionOutcome.Rejected)) / n,
      "unresolvedRate" -> cnt(ResolutionOutcome.Unresolved) / n,
      "conflictRate"   -> cnt(ResolutionOutcome.Conflict) / n,
    )

  def evaluate(
      preds: Seq[Pred],
      header: ujson.Obj,
  ): ujson.Obj =
    val perKind = ujson.Obj.from(
      EntityKind.values.toList.map { k =>
        val ps     = preds.filter(_.row.kind == k)
        val byPath = ujson.Obj.from(
          MatchPath.values.toList.map(p =>
            p.wire -> {
              val xs = ps.filter(_.result.path == p)
              ujson.Obj(
                "n"         -> xs.size,
                "confusion" -> confJson(
                  Confusion.of(xs.map(x => (x.row.label == GoldLabel.Match, x.accepted)))
                ),
              )
            }
          )
        )
        val m = metrics(ps)
        m("byPath") = byPath
        m("n") = ps.size
        k.wire -> m
      }
    )
    def rec(p: Pred) = ujson.Obj(
      "pairId"  -> p.row.pairId,
      "kind"    -> p.row.kind.wire,
      "label"   -> p.row.label.wire,
      "outcome" -> p.result.outcome.wire,
      "path"    -> p.result.path.wire,
      "score"   -> p.result.score,
    )
    val errors = preds.filter(p => p.accepted != (p.row.label == GoldLabel.Match)).map { p =>
      val e = rec(p)
      e("error") = if p.accepted then "FP" else "FN"
      e("reasonCodes") = ujson.Arr.from(p.result.reasonCodes)
      e("features") = ujson.Obj.from(
        p.scored.candidate.features.toList.sortBy(_._1).map((k, v) => k -> ujson.Num(v))
      )
      e
    }
    header("perKind") = perKind
    header("errors") = ujson.Arr.from(errors.sortBy(e => (e("error").str, e("pairId").str)))
    header("predictions") = ujson.Arr.from(preds.sortBy(_.row.pairId).map(rec))
    header

  def markdown(j: ujson.Obj): String =
    val sb = StringBuilder()
    sb ++= s"# ${j("name").str}: held-out evaluation\n\n"
    sb ++= s"Policy `${j("policyVersion").str}`, thresholds origin `${j("thresholdsOrigin").str}`, code `${j("codeVersion").str}`.\n"
    val ledger = j.obj.get("ledger").map(_.str).getOrElse("unknown")
    val split  = j.obj.get("splitSha256").map(_.str).getOrElse("unknown")
    val ovr = j.obj.get("overrides").map(_.obj.map((k, v) => s"$k=${v.str}").toList).getOrElse(Nil)
    sb ++= s"Ledger `$ledger`, split checksum `$split`, overrides: ${
        if ovr.isEmpty then "none" else ovr.sorted.mkString(", ")
      }.\n"
    if !j("disclosedReEvaluation").isNull then
      sb ++= s"\nDISCLOSED RE-EVALUATION: ${j("disclosedReEvaluation").str}\n"
    sb ++= s"\nHeld-out rows: ${j("heldOutRows").num.toInt}. Positive class: ACCEPTED vs MATCH; rejected, unresolved and conflict count as predicted non-match.\n"
    j("perKind").obj.foreach { (k, m) =>
      val c = m("confusion")
      sb ++= s"\n## $k (n=${m("n").num.toInt})\n\n"
      sb ++= "| TP | FP | FN | TN | precision | recall | F1 | coverage | unresolved | conflict |\n|---|---|---|---|---|---|---|---|---|---|\n"
      sb ++= f"| ${c("tp").num.toInt} | ${c("fp").num.toInt} | ${c("fn").num.toInt} | ${c("tn").num.toInt} | ${c("precision").num}%.4f | ${c("recall").num}%.4f | ${c("f1").num}%.4f | ${m("coverage").num}%.4f | ${m("unresolvedRate").num}%.4f | ${m("conflictRate").num}%.4f |\n"
      sb ++= "\nBy match path:\n\n| path | n | TP | FP | FN | TN |\n|---|---|---|---|---|---|\n"
      m("byPath").obj.foreach { (p, v) =>
        val pc = v("confusion")
        sb ++= s"| $p | ${v("n").num.toInt} | ${pc("tp").num.toInt} | ${pc("fp").num.toInt} | ${pc("fn").num.toInt} | ${pc("tn").num.toInt} |\n"
      }
    }
    sb ++= s"\n## Errors (${j("errors").arr.size}: every FP and FN)\n\n| pair | kind | error | label | outcome | path | score |\n|---|---|---|---|---|---|---|\n"
    j("errors").arr.foreach { e =>
      sb ++= f"| ${e("pairId").str} | ${e("kind").str} | ${e("error").str} | ${e("label").str} | ${e("outcome").str} | ${e("path").str} | ${e("score").num}%.3f |\n"
    }
    sb.toString

  def run(a: Args, out: PrintStream, env: Map[String, String]): Either[String, Unit] =
    for
      sp   <- a.path("split")
      cp   <- a.path("candidates")
      dir  <- a.path("out-dir")
      pol  <- a.req("policy")
      conf <- Tooling.config(a, env)
      (cfg, frozen) = conf
      name          = a.get("name").getOrElse("er-v1")
      jsonP         = dir.resolve(s"$name-heldout-eval.json")
      mdP           = dir.resolve(s"$name-heldout-eval.md")
      reason        = a.get("new-evaluation-reason")
      fp <- frozen.toRight("--policy must be a frozen policy file")
      splitSha = Io.sha256(sp)
      _ <- Either.cond(
        FrozenPolicy.splitChecksum(fp).contains(splitSha),
        (),
        "the frozen policy was not calibrated on this split (er-gold-split checksum differs or is missing)",
      )
      ledgerP = a
        .get("ledger")
        .map(Paths.get(_))
        .getOrElse(
          Option(sp.toAbsolutePath.getParent).getOrElse(Paths.get(".")).resolve(Ledger.DefaultName)
        )
      prior <- Ledger.read(ledgerP)
      _     <- Either.cond(
        reason.isDefined || !(prior.exists(_ == splitSha) ||
          Files.exists(jsonP) || Files.exists(mdP)),
        (),
        s"held-out evaluation of this split already exists (ledger $ledgerP or $jsonP); a new evaluation must be disclosed with --new-evaluation-reason",
      )
      rows <- TableIo.read(sp, List("pair_id", "entity_kind", "label", "split"))
      held <- rows
        .filter(_("split") == Split.HeldOut)
        .foldLeft[Either[String, List[LabelRow]]](Right(Nil)) { (acc, r) =>
          for
            xs <- acc
            k  <- TableIo.kind(r("entity_kind"))
            l  <- GoldLabel
              .parse(r("label"))
              .filter(_ != GoldLabel.Unsure)
              .toRight("a held-out row has no definitive label")
          yield xs :+ LabelRow(r("pair_id"), k, l)
        }
      cands <- Io.readCandidates(cp)
      byPair = cands.map(c => ErIds.pairId(c.entityKind, c.leftId, c.rightId) -> c).toMap
      prompt <- PromptTemplate.load()
      adj    <- (a.get("views"), a.get("cache")) match
        case (Some(v), Some(c)) =>
          Io.readViews(Paths.get(v)).map(vs => Some((vs, AdjudicationCache.open(Paths.get(c))._1)))
        case (None, None) => Right(None)
        case _            => Left("--views and --cache must be given together")
      preds <- held.foldLeft[Either[String, List[Pred]]](Right(Nil)) { (acc, r) =>
        for
          xs <- acc
          c  <- byPair.get(r.pairId).toRight(s"no candidate for held-out pair ${r.pairId}")
          _ <- Either.cond(c.entityKind == r.kind, (), s"entity kind mismatch for pair ${r.pairId}")
          rec = adj.flatMap((vs, cache) => Resolver.cached(c, cfg, vs, cache, prompt.version))
          sc  = Resolver.scored(c, cfg)
        yield xs :+ Pred(r, sc, Resolver.decide(c, cfg, rec))
      }
      inputs = ujson.Obj(
        "split"      -> Io.sha256(sp),
        "candidates" -> Io.sha256(cp),
        "policy"     -> Io.sha256(Paths.get(pol)),
      )
      _ = a
        .get("cache")
        .foreach(c => if Files.exists(Paths.get(c)) then inputs("cache") = Io.sha256(Paths.get(c)))
      header = ujson.Obj(
        "tool"                  -> "Evaluate",
        "name"                  -> name,
        "policyVersion"         -> cfg.policyVersion,
        "featureSetVersion"     -> cfg.featureSetVersion,
        "thresholdsOrigin"      -> frozen.map(_.thresholdsOrigin).getOrElse("config-defaults"),
        "codeVersion"           -> TableIo.codeVersion(a),
        "disclosedReEvaluation" -> reason.map(ujson.Str(_)).getOrElse(ujson.Null),
        "inputs"                -> inputs,
        "heldOutRows"           -> preds.size,
        "thresholds"            -> ujson.Obj.from(
          EntityKind.values.toList.map(k =>
            k.wire -> ujson.read(WirePickle.write(cfg.thresholds(k)))
          )
        ),
        "llmUsed"          -> adj.isDefined,
        "llmMinConfidence" -> cfg.llm.minConfidence,
        "erEnv"            -> ujson.Obj.from(
          FrozenPolicy.erEnv(env).toList.sortBy(_._1).map((k, v) => k -> ujson.Str(v))
        ),
        "ledger"      -> ledgerP.toString,
        "splitSha256" -> splitSha,
        "overrides"   -> ujson.Obj.from(
          a.overrides.toList.sortBy(_._1).map((k, v) => k -> ujson.Str(v))
        ),
      )
      result = evaluate(preds, header)
      _ <- Io.writeNew(
        if reason.isDefined && Files.exists(jsonP) then
          dir.resolve(s"$name-heldout-eval-reeval-${Hashing.textHash(reason.get).take(8)}.json")
        else jsonP,
        ujson.write(result, indent = 2) + "\n",
      )
      _ <- Io.writeNew(
        if reason.isDefined && Files.exists(mdP) then
          dir.resolve(s"$name-heldout-eval-reeval-${Hashing.textHash(reason.get).take(8)}.md")
        else mdP,
        markdown(result),
      )
      _ <- Ledger.append(ledgerP, splitSha, name, reason)
    yield out.println(s"held-out evaluation written: ${preds.size} rows -> $dir")

  def main(argv: Array[String]): Unit =
    Tooling.exit(run(Args.parse(argv.toSeq), System.out, sys.env))

/** `FinalDecisions`: the policy decisions merged with the adjudication cache, as `MatchDecisionV2`
  * JSON Lines. Abstain stays UNRESOLVED; CONFLICT is never overridden. Arguments: `--candidates
  * <dir|file> --out <jsonl> --summary <json> [--policy <frozen json>] [--views <file> --cache
  * <file>] [--code-version X] [--override k=v]`.
  */
object FinalDecisions:
  def run(a: Args, out: PrintStream, env: Map[String, String]): Either[String, Unit] =
    for
      cp   <- a.path("candidates")
      op   <- a.path("out")
      sm   <- a.path("summary")
      conf <- Tooling.config(a, env)
      (cfg, _) = conf
      cands  <- Io.readCandidates(cp)
      prompt <- PromptTemplate.load()
      adj    <- (a.get("views"), a.get("cache")) match
        case (Some(v), Some(c)) =>
          Io.readViews(Paths.get(v)).map(vs => Some((vs, AdjudicationCache.open(Paths.get(c))._1)))
        case (None, None) => Right(None)
        case _            => Left("--views and --cache must be given together")
      inputs = List(
        Some(Io.inputRef("er-candidates", cfg.featureSetVersion, cp)),
        a.get("policy").map(p => Io.inputRef("er-frozen-policy", cfg.policyVersion, Paths.get(p))),
        a.get("cache")
          .filter(c => Files.exists(Paths.get(c)))
          .map(c => Io.inputRef("er-llm-cache", prompt.version, Paths.get(c))),
      ).flatten
      params = Map(
        "policyVersion"        -> cfg.policyVersion,
        "promptVersion"        -> prompt.version,
        "llmModel"             -> cfg.llm.model,
        "researcher.threshold" -> ujson.write(ujson.read(WirePickle.write(cfg.researcher))),
        "work.threshold"       -> ujson.write(ujson.read(WirePickle.write(cfg.work))),
        "llm.min-confidence"   -> cfg.llm.minConfidence.toString,
      )
      runId <- Ids
        .runId("er-final-decisions", TableIo.codeVersion(a), 1, params, inputs)
        .left
        .map(_.message)
      prov = Provenance(
        SchemaVersion.Current,
        SourceType.ScholarChain,
        inputs,
        TableIo.codeVersion(a),
        params,
        Some(runId),
        None,
      )
      decisions <- cands
        .sortBy(c => (c.leftId, c.rightId))
        .foldLeft[Either[String, List[MatchDecisionV2]]](Right(Nil)) { (acc, c) =>
          for
            xs <- acc
            rec = adj.flatMap((vs, cache) => Resolver.cached(c, cfg, vs, cache, prompt.version))
            d <- Decisions
              .build(c, Resolver.decide(c, cfg, rec), cfg, runId, prov)
              .left
              .map(_.message)
          yield xs :+ d
        }
      lines <- decisions.foldLeft[Either[String, List[String]]](Right(Nil))((acc, d) =>
        for xs <- acc; j <- Wire.write(d).left.map(_.message) yield xs :+ j
      )
      _ <- Io.writeNew(op, lines.mkString("", "\n", if lines.isEmpty then "" else "\n"))
      grouped = decisions.groupBy(d => (d.entityKind.wire, d.outcome.wire, d.matchPath.wire))
      summary = ujson.Obj(
        "tool"          -> "FinalDecisions",
        "policyVersion" -> cfg.policyVersion,
        "runId"         -> runId,
        "codeVersion"   -> TableIo.codeVersion(a),
        "decisions"     -> decisions.size,
        "byOutcome"     -> ujson.Obj.from(
          decisions
            .groupBy(_.outcome.wire)
            .toList
            .sortBy(_._1)
            .map((k, v) => k -> ujson.Num(v.size))
        ),
        "byPath" -> ujson.Obj.from(
          decisions
            .groupBy(_.matchPath.wire)
            .toList
            .sortBy(_._1)
            .map((k, v) => k -> ujson.Num(v.size))
        ),
        "byKindOutcomePath" -> ujson.Arr.from(
          grouped.toList
            .sortBy(_._1)
            .map((k, v) =>
              ujson.Obj("kind" -> k._1, "outcome" -> k._2, "path" -> k._3, "count" -> v.size)
            )
        ),
        "llmDecisions" -> decisions.count(_.matchPath == MatchPath.Llm),
      )
      _ <- Io.writeNew(sm, ujson.write(summary, indent = 2) + "\n")
    yield out.println(s"final decisions: ${decisions.size} -> $op (summary $sm)")

  def main(argv: Array[String]): Unit =
    Tooling.exit(run(Args.parse(argv.toSeq), System.out, sys.env))

/** `AdjudicateBand`: LLM adjudication of the unresolved, in-band candidates. Run by the HUMAN
  * (Phase 05 P2). It prints its plan (count, model, estimated cost) and makes NO call unless
  * `--confirm` is given. Bounds: `er.llm.max-calls` (or `--max-calls`) live calls, and
  * `er.llm.max-cost-usd`. Cached answers cost no call.
  *
  * Arguments: `--candidates <dir|file> --views <file> [--policy <frozen json>] [--cache <file>]
  * [--priority-pairs <csv with a pair_id column>] [--max-calls N] [--confirm] [--code-version X]
  * [--override k=v]`. The API key is read from `SCHOLARCHAIN_ANTHROPIC_API_KEY` only and is never
  * printed.
  */
object AdjudicateBand:
  final case class Plan(
      eligible: Int,
      cached: Int,
      toCall: List[Scored],
      estInputTokens: Long,
      estOutputTokens: Long,
      estCostUsd: Double,
  )

  /** Eligible = policy UNRESOLVED and in the LLM band (CONFLICT never). Cached ones are not called.
    * Order: priority pairs first, then closeness to the band centre, then pair id; the list is
    * capped at `maxCalls`.
    */
  def plan(
      cands: Seq[MatchCandidate],
      views: Map[String, EntityView],
      cache: AdjudicationCache,
      cfg: ErConfig,
      prompt: PromptTemplate,
      priority: Set[String],
      maxCalls: Int,
  ): Plan =
    val elig = cands
      .map(Resolver.scored(_, cfg))
      .filter(s => s.result.outcome == ResolutionOutcome.Unresolved && s.result.llmEligible)
      .filter(s => views.contains(s.candidate.leftId) && views.contains(s.candidate.rightId))
    val (hit, miss) =
      elig.partition(s => Resolver.cached(s.candidate, cfg, views, cache, prompt.version).isDefined)
    def mid(s: Scored) =
      val t = cfg.thresholds(s.candidate.entityKind)
      math.abs(s.result.score - (t.llmBandLow + t.llmBandHigh) / 2)
    val ordered = miss
      .sortBy(s => (if priority.contains(s.pairId) then 0 else 1, mid(s), s.pairId))
      .take(maxCalls)
      .toList
    val inTok = ordered.map { s =>
      val (sys, user) = AdjudicationInput.render(
        prompt,
        s.candidate,
        views(s.candidate.leftId),
        views(s.candidate.rightId),
      )
      CostEstimate.tokens(sys.length + user.length)
    }.sum
    val outTok = ordered.size.toLong * cfg.llm.maxOutputTokens
    Plan(elig.size, hit.size, ordered, inTok, outTok, CostEstimate.usd(cfg.llm, inTok, outTok))

  def run(a: Args, out: PrintStream, env: Map[String, String]): Either[String, Unit] =
    for
      cp   <- a.path("candidates")
      vp   <- a.path("views")
      conf <- Tooling.config(a, env)
      (base, _) = conf
      maxCalls <- a
        .get("max-calls")
        .fold(Right(base.llm.maxCalls))(s =>
          s.toIntOption.filter(_ >= 0).toRight("--max-calls must be a non-negative integer")
        )
      cfg = base.copy(llm = base.llm.copy(maxCalls = maxCalls))
      cands  <- Io.readCandidates(cp)
      views  <- Io.readViews(vp)
      prompt <- PromptTemplate.load()
      cachePath        = Paths.get(a.get("cache").getOrElse(cfg.llm.cachePath))
      (cache, skipped) = AdjudicationCache.open(cachePath)
      priority <- a
        .get("priority-pairs")
        .fold[Either[String, Set[String]]](Right(Set.empty))(p =>
          TableIo.read(Paths.get(p), List("pair_id")).map(_.map(_("pair_id")).toSet)
        )
      pl = plan(cands, views, cache, cfg, prompt, priority, maxCalls)
      _  = out.println(
        s"""AdjudicateBand plan
           |  policy:            ${cfg.policyVersion}  prompt: ${prompt.version}
           |  provider / model:  ${cfg.llm.provider} / ${cfg.llm.model}
           |  eligible (unresolved, in LLM band): ${pl.eligible}
           |  already cached (no call):           ${pl.cached}
           |  calls planned (cap max-calls=$maxCalls):  ${pl.toCall.size}
           |  est. input tokens: ${pl.estInputTokens}, est. output tokens (upper bound): ${pl.estOutputTokens}
           |  est. cost: USD ${f"${pl.estCostUsd}%.4f"} (max-cost-usd=${cfg.llm.maxCostUsd}; prices are config placeholders)
           |  cache: $cachePath (${cache.size} entries, $skipped unreadable lines skipped)""".stripMargin
      )
      _ <- Either.cond(
        pl.estCostUsd <= cfg.llm.maxCostUsd,
        (),
        s"estimated cost exceeds er.llm.max-cost-usd (${cfg.llm.maxCostUsd}); lower --max-calls",
      )
      _ <-
        if !a.flags.contains("confirm") then
          out.println("No call made. Re-run with --confirm to execute the plan.")
          Right(())
        else
          for
            client <- LlmClients.create(cfg.llm, env)
            adjud   = Adjudicator(client, cache, cfg, prompt, TableIo.codeVersion(a), maxCalls)
            results = pl.toCall.map(s =>
              adjud.adjudicate(s.candidate, views(s.candidate.leftId), views(s.candidate.rightId))
            )
          yield
            val fresh   = results.collect { case r @ AdjudicationResult.Fresh(_) => r.record }
            val abst    = fresh.count(_.outcome == AdjudicationOutcome.Abstain)
            val invalid = results.count {
              case AdjudicationResult.Invalid(_, _) => true; case _ => false
            }
            val skipped = results.collect { case AdjudicationResult.Skipped(why) => why }
            out.println(
              s"""AdjudicateBand summary
                 |  live calls: ${adjud.liveCalls}, cache hits: ${adjud.cacheHits}
                 |  valid answers: ${fresh.size} (model abstains: $abst), unusable outputs (abstain, not cached): $invalid
                 |  skipped: ${skipped.size}${
                  if skipped.isEmpty then "" else skipped.distinct.mkString(" (", ", ", ")")
                }
                 |  observed tokens: in ${adjud.usage.inputTokens}, out ${adjud.usage.outputTokens}; observed cost: USD ${f"${adjud.costUsd}%.4f"}""".stripMargin
            )
    yield ()

  def main(argv: Array[String]): Unit =
    Tooling.exit(run(Args.parse(argv.toSeq), System.out, sys.env))
