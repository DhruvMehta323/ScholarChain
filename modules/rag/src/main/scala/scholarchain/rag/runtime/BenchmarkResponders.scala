package scholarchain.rag.runtime

import scala.util.Try

import scholarchain.core.*

/** Model-free "scripted outputs" for the benchmark, derived from the request itself so no model is
  * involved and nothing is typed per question:
  *
  *   - `query-planner`: the operations of the typed `structured_constraints` segment when present,
  *     otherwise the rule-based keyword choice; researcher names come only from the constraints.
  *   - `answer-synthesis`: `TemplateSynthesizer` over the evidence items in the
  *     `retrieved_evidence` segment (the same deterministic logic as the synthesis fallback), so
  *     claims and their assertions come straight from the stored facts.
  *   - `claim-verification`: `verified` for every claim in `claims_to_check` (the optional wording
  *     check; it can never upgrade, so this changes nothing).
  *
  * Use as `new FakeModelClient(responder = Some(BenchmarkResponders.responder))`.
  */
object BenchmarkResponders:
  private def segment(r: ModelRequest, label: String): Option[String] =
    r.user.find(_.label == label).map(_.text)

  def responder(r: ModelRequest): Option[String] = r.promptId match
    case PromptRepository.PlannerId      => Some(planner(r))
    case PromptRepository.SynthesisId    => Some(synthesis(r))
    case PromptRepository.VerificationId => Some(verification(r))
    case _                               => None

  private def planner(r: ModelRequest): String =
    val question = segment(r, "user_question").getOrElse("")
    val cons     = segment(r, "structured_constraints")
      .flatMap(t => Try(ujson.read(t)).toOption)
      .flatMap(_.objOpt)
    val ops = cons
      .flatMap(_.value.get("operations"))
      .flatMap(_.arrOpt)
      .map(_.toList.flatMap(_.strOpt))
      .filter(_.nonEmpty)
      .getOrElse {
        val c = StructuredConstraints(
          researcherNames = cons
            .flatMap(_.value.get("researcherNames"))
            .flatMap(_.arrOpt)
            .map(_.toList.flatMap(_.strOpt))
            .getOrElse(Nil),
          metric = cons
            .flatMap(_.value.get("metric"))
            .flatMap(_.strOpt)
            .flatMap(w => CollaborationMetric.values.find(_.wire == w)),
          comparisonWindow =
            cons.flatMap(_.value.get("comparisonWindow")).map(_ => GraphWindow(2000, 2004)),
        )
        QueryPlannerAgent.keywordOperations(question, c).map(_.wire)
      }
    ujson.write(ujson.Obj("operations" -> ujson.Arr.from(ops.map(ujson.Str(_)))))

  private def evidenceSet(r: ModelRequest): EvidenceSet =
    val prov =
      Provenance(1, SourceType.ScholarChain, List(InputRef("fake", "1", None)), "fake", Map.empty)
    val items = segment(r, "retrieved_evidence")
      .flatMap(t => Try(ujson.read(t)).toOption)
      .flatMap(_.arrOpt)
      .map(_.toList)
      .getOrElse(Nil)
      .flatMap { v =>
        for
          o   <- v.objOpt
          id  <- o.value.get("evidenceId").flatMap(_.strOpt)
          tpe <- o.value
            .get("type")
            .flatMap(_.strOpt)
            .flatMap(w => EvidenceType.values.find(_.wire == w))
          text <- o.value.get("text").flatMap(_.strOpt)
        yield EvidenceItem(
          1,
          id,
          tpe,
          Fact
            .parse(text)
            .map(_.subject)
            .orElse(o.value.get("sourceEntityId").flatMap(_.strOpt))
            .getOrElse("entity:unknown"),
          "v",
          Some(text),
          Hashing.textHash(text),
          None,
          prov,
        )
      }
    val sem = items.filter(_.evidenceType == EvidenceType.TextChunk).map(_.evidenceId)
    val det = items.filterNot(_.evidenceType == EvidenceType.TextChunk).map(_.evidenceId)
    EvidenceSet(EvidenceBundle(1, "query:fake", items, prov), det, sem, 0)

  private def synthesis(r: ModelRequest): String =
    val missing = segment(r, "missing_evidence")
      .flatMap(t => Try(ujson.read(t)).toOption)
      .flatMap(_.arrOpt)
      .map(_.toList.flatMap(_.strOpt))
      .getOrElse(Nil)
    val s = TemplateSynthesizer.synthesize(evidenceSet(r), missing)
    ujson.write(
      ujson.Obj(
        "claims" -> ujson.Arr.from(s.claims.map { c =>
          ujson.Obj(
            "text"        -> ujson.Str(c.text.take(600)),
            "evidenceIds" -> ujson.Arr.from(c.evidenceIds.map(ujson.Str(_))),
            "assertions"  -> ujson.Arr.from(c.assertions.map { a =>
              val o = ujson.Obj(
                "kind"    -> ujson.Str(a.kind),
                "subject" -> ujson.Str(a.subject),
                "field"   -> ujson.Str(a.field),
                "value"   -> a.value,
              )
              a.window.foreach(w => o("window") = ujson.Str(w))
              o
            }),
          )
        }),
        "uncertainty"     -> ujson.Arr.from(s.uncertainty.map(u => ujson.Str(u.take(300)))),
        "missingEvidence" -> ujson.Arr.from(s.missingEvidence.map(u => ujson.Str(u.take(300)))),
      )
    )

  private def verification(r: ModelRequest): String =
    val ids = segment(r, "claims_to_check")
      .flatMap(t => Try(ujson.read(t)).toOption)
      .flatMap(_.arrOpt)
      .map(_.toList.flatMap(_.objOpt.flatMap(_.value.get("claimId")).flatMap(_.strOpt)))
      .getOrElse(Nil)
    ujson.write(
      ujson.Obj(
        "verdicts" -> ujson.Arr.from(
          ids.map(id =>
            ujson.Obj(
              "claimId"     -> ujson.Str(id),
              "status"      -> ujson.Str("verified"),
              "reasonCodes" -> ujson.Arr(),
            )
          )
        )
      )
    )
