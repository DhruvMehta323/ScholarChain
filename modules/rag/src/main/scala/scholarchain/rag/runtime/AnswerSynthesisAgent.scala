package scholarchain.rag.runtime

import scholarchain.core.*

/** A structured assertion a claim makes, to be checked field by field against a cited fact: the
  * fact of `kind` about `subject` (in `window`, when given) must hold `value` in `field`.
  */
final case class ProposedAssertion(
    kind: String,
    subject: String,
    window: Option[String],
    field: String,
    value: ujson.Value,
)

/** A claim as the model (or the template synthesizer) proposes it: text, the evidence IDs it cites
  * and its structured assertions. It is not yet verified; the claim ID is assigned by code.
  */
final case class ProposedClaim(
    text: String,
    evidenceIds: List[String],
    assertions: List[ProposedAssertion],
)

final case class Synthesis(
    claims: List[ProposedClaim],
    uncertainty: List[String],
    missingEvidence: List[String],
)

final case class SynthesisResult(
    synthesis: Synthesis,
    usedFallback: Boolean,
    reasonCodes: List[String],
    modelCalls: List[ModelCall],
)

/** Renders stored numbers as text: a whole number has no decimal point, nothing is rounded. */
object Nums:
  def render(v: ujson.Value): String = v match
    case ujson.Num(d) =>
      val bd = BigDecimal(d)
      if bd.isWhole then bd.toBigInt.toString else bd.bigDecimal.stripTrailingZeros.toPlainString
    case ujson.Str(s)  => s
    case ujson.Bool(b) => b.toString
    case other         => ujson.write(other)

/** Stage 8: a concise analysis as proposed claims, each citing evidence IDs.
  *
  * The model (prompt `answer-synthesis-v1`) receives the question, the plan and the evidence as
  * labeled untrusted data; its output must satisfy `answer-synthesis-v1.schema.json`. The model's
  * claims are only PROPOSALS: the claim verifier checks every one against the bundle. When the
  * model call fails (a typed failure after the bounded retries) the stage falls back to the
  * deterministic `TemplateSynthesizer`, whose claims come straight from the stored facts, and the
  * fallback is recorded. The model's free text is never shown unless its claims verify.
  *
  * The prompt asks for no reasoning, and nothing but the schema-valid JSON is kept.
  */
final class AnswerSynthesisAgent(
    caller: StructuredModelCaller,
    prompts: PromptRepository,
    settings: RuntimeSettings,
):
  def synthesize(
      queryId: String,
      plan: QueryPlanV2,
      set: EvidenceSet,
      missing: List[String],
  ): SynthesisResult =
    val fallback = (why: String, calls: List[ModelCall]) =>
      SynthesisResult(
        TemplateSynthesizer.synthesize(set, missing),
        true,
        List(why, "synthesis_template_fallback"),
        calls,
      )
    prompts.load(PromptRepository.SynthesisId, PromptRepository.Version) match
      case Left(_)     => fallback("synthesis_prompt_unavailable", Nil)
      case Right(spec) =>
        val (evidenceJson, trimmed) = evidenceSegment(set)
        val user                    = List(
          ModelSegment(
            ModelSegmentKind.Instruction,
            "task",
            "Write the analysis for the question in the untrusted data segments, using only the retrieved evidence.",
          ),
          ModelSegment(ModelSegmentKind.Data, "user_question", plan.question),
          ModelSegment(ModelSegmentKind.Data, "query_plan", planJson(plan)),
          ModelSegment(ModelSegmentKind.Data, "retrieved_evidence", evidenceJson),
        ) ++ Option.when(missing.nonEmpty)(
          ModelSegment(
            ModelSegmentKind.Data,
            "missing_evidence",
            ujson.write(ujson.Arr.from(missing.map(ujson.Str(_)))),
          )
        )
        caller.call(queryId, ModelStage.AnswerSynthesis, spec, user, settings.outputTokens) match
          case Left(f) =>
            fallback(s"synthesis_model_failed_${f.error.code.replace('-', '_')}", List(f.call))
          case Right(s) =>
            parse(s.json) match
              case Left(why)  => fallback(why, List(s.call))
              case Right(syn) =>
                val extra = Option.when(trimmed > 0)(
                  s"$trimmed evidence items were not shown to the synthesis model because of the context budget"
                )
                SynthesisResult(
                  syn.copy(uncertainty = (syn.uncertainty ++ extra).distinct),
                  false,
                  List("synthesis_model_used"),
                  List(s.call),
                )

  private def planJson(p: QueryPlanV2): String =
    CanonicalJson.render(
      ujson.Obj(
        "operations" -> ujson.Arr.from(p.operations.map(o => ujson.Str(o.wire))),
        "window"     -> p.window
          .map(w => ujson.Str(GraphWindow.label(w)): ujson.Value)
          .getOrElse(ujson.Null),
        "comparisonWindow" -> p.comparisonWindow
          .map(w => ujson.Str(GraphWindow.label(w)): ujson.Value)
          .getOrElse(ujson.Null),
        "metric"      -> p.metric.map(m => ujson.Str(m.wire): ujson.Value).getOrElse(ujson.Null),
        "resultCount" -> ujson.Num(p.limits.resultCount.toDouble),
        "pathDepth"   -> ujson.Num(p.limits.pathDepth.toDouble),
      )
    )

  /** The evidence array within the context budget (deterministic items first). */
  private def evidenceSegment(set: EvidenceSet): (String, Int) =
    val budget = math.max(1000, settings.contextCharacters - 8000)
    val all    = set.graphItems ++ set.semanticItems
    var used   = 2
    val shown  = all.takeWhile { i =>
      val n = ujson.write(AnswerSynthesisAgent.itemJson(i)).length + 1
      if used + n <= budget then { used += n; true }
      else false
    }
    (ujson.write(ujson.Arr.from(shown.map(AnswerSynthesisAgent.itemJson))), all.size - shown.size)

  private def parse(json: ujson.Value): Either[String, Synthesis] =
    json.objOpt match
      case None    => Left("synthesis_output_not_object")
      case Some(o) =>
        val m               = o.value
        def strs(k: String) =
          m.get(k).flatMap(_.arrOpt).map(_.toList.flatMap(_.strOpt)).getOrElse(Nil)
        val claims = m.get("claims").flatMap(_.arrOpt).map(_.toList).getOrElse(Nil).map { c =>
          for
            co   <- c.objOpt
            text <- co.value.get("text").flatMap(_.strOpt)
            ids = co.value
              .get("evidenceIds")
              .flatMap(_.arrOpt)
              .map(_.toList.flatMap(_.strOpt))
              .getOrElse(Nil)
            as = co.value.get("assertions").flatMap(_.arrOpt).map(_.toList).getOrElse(Nil).flatMap {
              a =>
                for
                  ao    <- a.objOpt
                  kind  <- ao.value.get("kind").flatMap(_.strOpt)
                  subj  <- ao.value.get("subject").flatMap(_.strOpt)
                  field <- ao.value.get("field").flatMap(_.strOpt)
                  value <- ao.value.get("value")
                yield ProposedAssertion(
                  kind,
                  subj,
                  ao.value.get("window").flatMap(_.strOpt),
                  field,
                  value,
                )
            }
          yield ProposedClaim(text, ids, as)
        }
        if claims.exists(_.isEmpty) then Left("synthesis_claim_malformed")
        else Right(Synthesis(claims.flatten, strs("uncertainty"), strs("missingEvidence")))

object AnswerSynthesisAgent:
  def itemJson(i: EvidenceItem): ujson.Value =
    val o = ujson.Obj(
      "evidenceId"     -> ujson.Str(i.evidenceId),
      "type"           -> ujson.Str(i.evidenceType.wire),
      "text"           -> ujson.Str(i.text.getOrElse("")),
      "sourceEntityId" -> ujson.Str(i.sourceEntityId),
    )
    i.score.foreach(s => o("score") = ujson.Num(s))
    o

/** The deterministic synthesizer: claims straight from the stored facts, with structured assertions
  * copied from the fact, and no number that is not stored. It is the synthesis fallback and the
  * logic behind the benchmark's fake synthesis responder. It is code, not a model.
  */
object TemplateSynthesizer:
  import Fact.*

  private final case class F(item: EvidenceItem, fact: Fact)

  private def as(
      kind: String,
      subject: String,
      window: Option[String],
      field: String,
      value: ujson.Value,
  ) =
    ProposedAssertion(kind, subject, window, field, value)

  def facts(set: EvidenceSet): List[(EvidenceItem, Fact)] =
    set.graphItems.flatMap(i => i.text.flatMap(Fact.parse).map(i -> _))

  def synthesize(set: EvidenceSet, missing: List[String]): Synthesis =
    val fs    = facts(set).map((i, f) => F(i, f))
    val names = fs.collect {
      case F(_, f) if f.kind == Identity =>
        f.subject -> f.fields.value.get("displayName").flatMap(_.strOpt).getOrElse(f.subject)
    }.toMap
    val idEv = fs.collect { case F(i, f) if f.kind == Identity => f.subject -> i.evidenceId }.toMap
    def who(id: String): String = names.get(id).map(n => s"$n ($id)").getOrElse(id)
    def cite(i: EvidenceItem, ids: String*): List[String] =
      (i.evidenceId :: ids.toList.flatMap(idEv.get)).distinct

    val claims = fs.flatMap { case F(item, f) =>
      val w                                 = f.window
      val wl                                = w.getOrElse("")
      val fv                                = f.fields.value
      def v(k: String): Option[ujson.Value] = fv.get(k)
      def n(k: String): String              = v(k).map(Nums.render).getOrElse("")
      f.kind match
        case Identity if v("resolutionStatus").flatMap(_.strOpt).contains("resolved") =>
          List(
            ProposedClaim(
              s"The researcher named ${n("displayName")} is identified as ${f.subject} with identity confidence ${n("confidence")}.",
              cite(item),
              List(
                as(
                  Identity,
                  f.subject,
                  None,
                  "displayName",
                  v("displayName").getOrElse(ujson.Null),
                ),
                as(Identity, f.subject, None, "confidence", v("confidence").getOrElse(ujson.Null)),
              ),
            )
          )
        case Metrics =>
          val c       = cite(item, f.subject)
          val breadth = for
            a <- v("coauthorCount"); p <- v("publicationCount"); wd <- v("weightedDegree")
          yield ProposedClaim(
            s"In window $wl, ${who(f.subject)} has ${Nums.render(a)} distinct coauthors (breadth), ${Nums.render(p)} publications and a weighted degree of ${Nums.render(wd)} (intensity).",
            c,
            List(
              as(Metrics, f.subject, w, "coauthorCount", a),
              as(Metrics, f.subject, w, "publicationCount", p),
              as(Metrics, f.subject, w, "weightedDegree", wd),
            ),
          )
          val team = for a <- v("avgTeamSize"); m <- v("maxTeamSize")
          yield ProposedClaim(
            s"In window $wl, the average team size of ${who(f.subject)} is ${Nums.render(a)} and the maximum team size is ${Nums.render(m)}.",
            c,
            List(
              as(Metrics, f.subject, w, "avgTeamSize", a),
              as(Metrics, f.subject, w, "maxTeamSize", m),
            ),
          )
          val conn = for comp <- v("componentId"); comm <- v("communityId")
          yield ProposedClaim(
            s"In window $wl, ${who(f.subject)} is in component ${Nums.render(comp)} and community ${Nums.render(comm)}.",
            c,
            List(
              as(Metrics, f.subject, w, "componentId", comp),
              as(Metrics, f.subject, w, "communityId", comm),
            ),
          )
          List(breadth, team, conn).flatten
        case WindowSummary =>
          (for
            m     <- v("maxTeamSize"); wk <- v("maxTeamWorkId"); nodes <- v("nodeCount");
            works <- v("workCount")
          yield ProposedClaim(
            s"In window $wl the largest team has ${Nums.render(m)} members (work ${Nums.render(wk)}); the graph has ${Nums.render(nodes)} researchers and ${Nums.render(works)} works.",
            cite(item),
            List(
              as(WindowSummary, f.subject, w, "maxTeamSize", m),
              as(WindowSummary, f.subject, w, "maxTeamWorkId", wk),
              as(WindowSummary, f.subject, w, "nodeCount", nodes),
              as(WindowSummary, f.subject, w, "workCount", works),
            ),
          )).toList
        case CoauthorEdge
            if item.evidenceType == EvidenceType.CoauthorEdge && !f.subject.startsWith(
              "neighborhood:"
            ) =>
          (for
            a <- v("researcherA").flatMap(_.strOpt); b <- v("researcherB").flatMap(_.strOpt);
            c <- v("jointPublicationCount")
          yield
            val works = v("supportingWorkIds")
              .flatMap(_.arrOpt)
              .map(_.toList.flatMap(_.strOpt))
              .getOrElse(Nil)
            val base = List(
              as(CoauthorEdge, f.subject, w, "researcherA", ujson.Str(a)),
              as(CoauthorEdge, f.subject, w, "researcherB", ujson.Str(b)),
              as(CoauthorEdge, f.subject, w, "jointPublicationCount", c),
            )
            val complete = !v("worksTruncated").flatMap(_.boolOpt).getOrElse(true)
            val workText =
              if complete && works.nonEmpty then s" Supporting works: ${works.mkString(", ")}."
              else ""
            ProposedClaim(
              s"${who(a)} and ${who(b)} coauthored ${Nums.render(c)} works in window $wl.$workText",
              cite(item, a, b),
              base ++ Option.when(complete && works.nonEmpty)(
                as(
                  CoauthorEdge,
                  f.subject,
                  w,
                  "supportingWorkIds",
                  ujson.Arr.from(works.map(ujson.Str(_))),
                )
              ),
            )
          ).toList
        case Neighborhood =>
          (for ids <- v("coauthorIds").flatMap(_.arrOpt).map(_.toList.flatMap(_.strOpt))
          yield ProposedClaim(
            s"Direct coauthors of ${who(f.subject)} in window $wl, strongest collaboration first: ${ids.map(who).mkString("; ")}.",
            cite(item, f.subject :: ids*),
            List(
              as(Neighborhood, f.subject, w, "coauthorIds", ujson.Arr.from(ids.map(ujson.Str(_))))
            ) ++ v("jointPublicationCounts").map(c =>
              as(Neighborhood, f.subject, w, "jointPublicationCounts", c)
            ),
          )).toList
        case Path =>
          val from = v("from").flatMap(_.strOpt).getOrElse("")
          val to   = v("to").flatMap(_.strOpt).getOrElse("")
          if v("found").flatMap(_.boolOpt).contains(false) then
            List(
              ProposedClaim(
                s"No collaboration path between ${who(from)} and ${who(to)} was found within ${n("maxHops")} hops in window $wl.",
                cite(item, from, to),
                List(
                  as(Path, f.subject, w, "found", ujson.Bool(false)),
                  as(Path, f.subject, w, "maxHops", v("maxHops").getOrElse(ujson.Null)),
                  as(Path, f.subject, w, "from", ujson.Str(from)),
                  as(Path, f.subject, w, "to", ujson.Str(to)),
                ),
              )
            )
          else
            val hops    = v("hops").flatMap(_.arrOpt).map(_.toList.flatMap(_.strOpt)).getOrElse(Nil)
            val weights = v("hopWeights").flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
            val works   = v("hopWorkIds").flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)
            val detail  =
              hops.zip(hops.drop(1)).zip(weights).zip(works).map { case (((a, b), wt), ws) =>
                val wsText = ws.arrOpt.map(_.toList.flatMap(_.strOpt).mkString(", ")).getOrElse("")
                s"${a.takeRight(8)} to ${b.takeRight(8)}: ${Nums.render(wt)} joint works ($wsText)"
              }
            List(
              ProposedClaim(
                s"The shortest collaboration path in window $wl has length ${n("length")}: ${hops.map(who).mkString(" -> ")}.",
                cite(item, hops*),
                List(
                  as(Path, f.subject, w, "found", ujson.Bool(true)),
                  as(Path, f.subject, w, "length", v("length").getOrElse(ujson.Null)),
                  as(Path, f.subject, w, "hops", ujson.Arr.from(hops.map(ujson.Str(_)))),
                  as(Path, f.subject, w, "hopWeights", ujson.Arr.from(weights)),
                  as(Path, f.subject, w, "hopWorkIds", ujson.Arr.from(works)),
                ) ++ v("edgeIds").map(e => as(Path, f.subject, w, "edgeIds", e)) ++ List(
                  as(Path, f.subject, w, "from", ujson.Str(from)),
                  as(Path, f.subject, w, "to", ujson.Str(to)),
                ),
              )
            ).filter(_ => detail.nonEmpty || hops.nonEmpty)
        case Component =>
          (for c <- v("componentId"); s <- v("size")
          yield ProposedClaim(
            s"${who(f.subject)} belongs to component ${Nums.render(c)} of size ${Nums.render(s)} in window $wl.",
            cite(item, f.subject),
            List(
              as(Component, f.subject, w, "componentId", c),
              as(Component, f.subject, w, "size", s),
            ),
          )).toList
        case Community =>
          (for c <- v("communityId"); s <- v("size"); x <- v("crossEdgeCount")
          yield ProposedClaim(
            s"${who(f.subject)} belongs to community ${Nums.render(c)} of size ${Nums.render(s)} in window $wl, which has ${Nums.render(x)} cross-community edges.",
            cite(item, f.subject),
            List(
              as(Community, f.subject, w, "communityId", c),
              as(Community, f.subject, w, "size", s),
              as(Community, f.subject, w, "crossEdgeCount", x),
            ),
          )).toList
        case DegreeVsBridge =>
          (for
            c   <- v("coauthorCount"); x <- v("crossCommunityDegree"); sh <- v("crossShare");
            cls <- v("classification").flatMap(_.strOpt)
          yield
            val reading = cls match
              case "bridge"    => "a bridge between communities"
              case "local_hub" =>
                "a well-connected hub inside its own community rather than a bridge"
              case _ => "neither a clear bridge nor a local hub"
            ProposedClaim(
              s"In window $wl, ${who(f.subject)} has ${Nums.render(c)} direct coauthors, of which ${Nums.render(x)} are outside the community (cross share ${Nums.render(sh)}): $reading. High direct degree alone is not bridge importance.",
              cite(item, f.subject),
              List(
                as(DegreeVsBridge, f.subject, w, "coauthorCount", c),
                as(DegreeVsBridge, f.subject, w, "crossCommunityDegree", x),
                as(DegreeVsBridge, f.subject, w, "crossShare", sh),
                as(DegreeVsBridge, f.subject, w, "classification", ujson.Str(cls)),
              ),
            )
          ).toList
        case Ranking =>
          (for
            field <- v("metricField").flatMap(_.strOpt);
            order <- v("order").flatMap(_.arrOpt).map(_.toList.flatMap(_.strOpt));
            vals  <- v("values").flatMap(_.arrOpt).map(_.toList)
          yield
            val line =
              order.zip(vals).map((id, x) => s"${who(id)} (${Nums.render(x)})").mkString(", then ")
            ProposedClaim(
              s"Ordered by $field in window $wl, highest first: $line.",
              cite(item, order*),
              List(
                as(Ranking, f.subject, w, "metricField", ujson.Str(field)),
                as(Ranking, f.subject, w, "order", ujson.Arr.from(order.map(ujson.Str(_)))),
                as(Ranking, f.subject, w, "values", ujson.Arr.from(vals)),
              ),
            )
          ).toList
        case TemporalChange =>
          (for
            c  <- v("coauthorCount"); p <- v("prevCoauthorCount"); nw <- v("newCoauthors");
            l  <- v("lostCoauthors"); r <- v("retainedCoauthors");
            pw <- v("prevWindow").flatMap(_.strOpt)
          yield ProposedClaim(
            s"From window $pw to window $wl, ${who(f.subject)} went from ${Nums.render(p)} to ${Nums.render(c)} distinct coauthors (${Nums.render(nw)} new, ${Nums.render(l)} lost, ${Nums.render(r)} retained).",
            cite(item, f.subject),
            List(
              as(TemporalChange, f.subject, w, "coauthorCount", c),
              as(TemporalChange, f.subject, w, "prevCoauthorCount", p),
              as(TemporalChange, f.subject, w, "newCoauthors", nw),
              as(TemporalChange, f.subject, w, "lostCoauthors", l),
              as(TemporalChange, f.subject, w, "retainedCoauthors", r),
              as(TemporalChange, f.subject, w, "prevWindow", ujson.Str(pw)),
            ),
          )).toList
        case _ => Nil
    }
    val semantic = set.semanticItems.take(3).map { i =>
      ProposedClaim(
        s"Semantic search found related context for ${i.sourceEntityId}; this is similarity-based context, not a graph fact.",
        List(i.evidenceId),
        Nil,
      )
    }
    val all = (claims ++ semantic).take(20)
    val unc = List(
      Option.when(set.semanticEvidenceIds.nonEmpty)(
        "Semantic similarity context is not evidence for counts, coauthorship, paths, communities or bridges."
      ),
      Option.when(set.dropped > 0)(
        s"${set.dropped} evidence items were left out by the evidence bound."
      ),
      Option.when(claims.size + semantic.size > 20)("Further evidence items were not summarized."),
    ).flatten
    Synthesis(all, unc, missing.take(10))
