package scholarchain.rag.runtime

import scholarchain.core.*

/** Stage 3: exact stored collaboration metrics. It reads the `researcher-metrics`, `window-summary`
  * and `temporal-changes` tables through the key-value port and copies the stored values into
  * facts. Nothing is computed from text, and no stored number is rounded, summed or derived. Every
  * fact carries the Spark run ID (`provenanceRunId`) and the metric (policy) version of the stored
  * item, and the stored payload hash as its input checksum.
  *
  * Metric names (Phase 06): P_r `publicationCount`, C_r `coauthorCount`, T_r `teamSizeTotal`, A_r
  * `avgTeamSize`, M_r `maxTeamSize` (with `maxTeamWorkCount`, `maxTeamWorkIds`), W_r
  * `weightedDegree`, plus `componentId`, `communityId`, `coreness`, `crossCommunityDegree`,
  * `participationCoefficient`, `communitiesTouched` and `tieStrengthWeightedDegree` when stored.
  */
final class CollaborationMetricsAgent(reader: AnalyticsReader):
  import CollaborationMetricsAgent.*

  def label(w: Option[GraphWindow]): String =
    w.map(GraphWindow.label).getOrElse(RuntimeCode.AllTimeLabel)

  def metricsItem(id: String, window: String): Either[RuntimeError, Option[StoredItem]] =
    reader.get("researcher-metrics", id, List(window))

  /** One `metrics` fact per subject in the window; absent items are reported as missing. */
  def metrics(subjects: List[String], window: String): StageOutput =
    subjects.foldLeft(StageOutput.empty) { (acc, id) =>
      acc ++ (metricsItem(id, window) match
        case Left(e) =>
          StageOutput(
            missingEvidence = List(s"metrics read failed for $id in $window"),
            reasonCodes = List(e.code.replace('-', '_')),
          )
        case Right(None) =>
          StageOutput(missingEvidence =
            List(s"stored metrics are absent for $id in window $window")
          )
        case Right(Some(item)) => StageOutput(List(metricsDraft(item, window))))
    }

  def metricsDraft(item: StoredItem, window: String): EvidenceDraft =
    val a      = item.obj.value
    val fields =
      ujson.Obj.from(MetricFields.flatMap(k => a.get(k).filter(_ != ujson.Null).map(k -> _)))
    val policy = a.get("policyVersion").flatMap(_.strOpt)
    val fact   = Fact(
      Fact.Metrics,
      item.pk,
      Some(window),
      a.get("provenanceRunId").flatMap(_.strOpt),
      policy,
      fields,
    )
    EvidenceDraft.fromFact(
      EvidenceType.Metric,
      item.pk,
      s"${policy.getOrElse("unversioned")}@$window",
      fact,
      List(EvidenceSupport.inputRef(item, policy)),
      Map("table" -> "researcher-metrics", "window" -> window),
    )

  /** The window summary: the largest team in the window and the graph totals. */
  def windowSummary(window: String): StageOutput =
    reader.get("window-summary", window) match
      case Left(e) =>
        StageOutput(
          missingEvidence = List(s"window summary read failed for $window"),
          reasonCodes = List(e.code.replace('-', '_')),
        )
      case Right(None) =>
        StageOutput(missingEvidence = List(s"no stored window summary for $window"))
      case Right(Some(item)) =>
        val a      = item.obj.value
        val fields =
          ujson.Obj.from(SummaryFields.flatMap(k => a.get(k).filter(_ != ujson.Null).map(k -> _)))
        val fact = Fact(Fact.WindowSummary, window, Some(window), None, None, fields)
        StageOutput(
          List(
            EvidenceDraft.fromFact(
              EvidenceType.Metric,
              s"window:$window",
              "window-summary",
              fact,
              List(EvidenceSupport.inputRef(item, None)),
              Map("table" -> "window-summary", "window" -> window),
            )
          )
        )

  /** Stored field behind a ranking metric, or `None` when the metric is not stored. */
  def fieldOf(m: CollaborationMetric): Option[String] = m match
    case CollaborationMetric.Degree         => Some("coauthorCount")
    case CollaborationMetric.WeightedDegree => Some("weightedDegree")
    case CollaborationMetric.BridgeScore    => Some("crossCommunityDegree")
    case _                                  => None

  /** Ranks `scope` by a stored field (descending, ties by researcher ID ascending). Emits the
    * metrics facts of the scope and one `ranking` fact. Researchers without a stored value are
    * reported as missing and left out of the ranking.
    */
  def ranking(
      scope: List[String],
      window: String,
      field: String,
      secondary: Option[String] = None,
  ): StageOutput =
    val reads   = scope.distinct.map(id => id -> metricsItem(id, window))
    val items   = reads.collect { case (id, Right(Some(it))) => id -> it }
    val missing = reads.collect {
      case (id, Right(None)) => s"stored metrics are absent for $id in window $window"
      case (id, Left(_))     => s"metrics read failed for $id in window $window"
    }
    def num(it: StoredItem, k: String): Double =
      it.obj.value.get(k).flatMap(Jnum.value).map(_.toDouble).getOrElse(Double.NegativeInfinity)
    val ranked = items.sortBy { (id, it) =>
      (-num(it, field), -secondary.map(num(it, _)).getOrElse(0.0), id)
    }
    val drafts = items.map((_, it) => metricsDraft(it, window))
    if ranked.isEmpty then StageOutput(drafts, missing.distinct)
    else
      val fields = ujson.Obj(
        "metricField" -> ujson.Str(field),
        "order"       -> ujson.Arr.from(ranked.map((id, _) => ujson.Str(id))),
        "values" -> ujson.Arr.from(ranked.map((_, it) => it.obj.value.getOrElse(field, ujson.Null))),
      )
      secondary.foreach { s =>
        fields("secondaryField") = ujson.Str(s)
        fields("secondaryValues") =
          ujson.Arr.from(ranked.map((_, it) => it.obj.value.getOrElse(s, ujson.Null)))
      }
      val runId =
        ranked.headOption.flatMap((_, it) => it.obj.value.get("provenanceRunId").flatMap(_.strOpt))
      val policy =
        ranked.headOption.flatMap((_, it) => it.obj.value.get("policyVersion").flatMap(_.strOpt))
      val fact = Fact(Fact.Ranking, s"ranking:$field:$window", Some(window), runId, policy, fields)
      StageOutput(
        drafts :+ EvidenceDraft.fromFact(
          EvidenceType.Metric,
          s"ranking:$field:$window",
          s"${policy.getOrElse("unversioned")}@$window",
          fact,
          ranked.map((_, it) => EvidenceSupport.inputRef(it, policy)),
          Map("rankedBy" -> field, "window" -> window, "scope" -> ranked.size.toString),
        ),
        missing.distinct,
      )

  /** Metrics in both windows plus the stored `temporal-changes` item of the window, per subject. */
  def temporal(subjects: List[String], window: String, comparison: String): StageOutput =
    val perWindow = metrics(subjects, window) ++ metrics(subjects, comparison)
    subjects.foldLeft(perWindow) { (acc, id) =>
      List(window, comparison).foldLeft(acc) { (a2, w) =>
        a2 ++ (reader.get("temporal-changes", id, List(w)) match
          case Right(Some(item)) =>
            val a      = item.obj.value
            val fields = ujson.Obj.from(TemporalFields.flatMap(k => a.get(k).map(k -> _)))
            val fact   = Fact(Fact.TemporalChange, id, Some(w), None, None, fields)
            StageOutput(
              List(
                EvidenceDraft.fromFact(
                  EvidenceType.Metric,
                  id,
                  s"temporal-changes@$w",
                  fact,
                  List(EvidenceSupport.inputRef(item, None)),
                  Map("table" -> "temporal-changes", "window" -> w),
                )
              )
            )
          case _ => StageOutput.empty)
      }
    }

object CollaborationMetricsAgent:
  val MetricFields: List[String] = List(
    "publicationCount",
    "coauthorCount",
    "teamSizeTotal",
    "avgTeamSize",
    "maxTeamSize",
    "weightedDegree",
    "maxTeamWorkCount",
    "maxTeamWorkIds",
    "communityId",
    "componentId",
    "coreness",
    "crossCommunityDegree",
    "participationCoefficient",
    "communitiesTouched",
    "tieStrengthWeightedDegree",
  )

  val SummaryFields: List[String] = List(
    "nodeCount",
    "edgeCount",
    "workCount",
    "maxTeamSize",
    "maxTeamWorkId",
    "largestComponentSize",
    "componentCount",
    "communityCount",
    "maxCoreness",
    "singletonCount",
    "largestComponentFraction",
  )

  val TemporalFields: List[String] = List(
    "status",
    "prevWindow",
    "coauthorCount",
    "prevCoauthorCount",
    "newCoauthors",
    "lostCoauthors",
    "retainedCoauthors",
    "avgTeamSize",
    "prevAvgTeamSize",
    "maxTeamSize",
    "prevMaxTeamSize",
    "communitySize",
    "prevCommunitySize",
    "componentSize",
    "prevComponentSize",
    "crossCommunityDegree",
    "prevCrossCommunityDegree",
  )
