package scholarchain.services

import scala.util.Try

import scholarchain.core.*
import scholarchain.rag.runtime.*

/** LOCAL MODE ONLY. The REST request carries no researcher names, and a real planner model reads
  * them from the question. The local stand-in (no model) does the same with a fixed rule: runs of
  * two or more capitalized words (and a trailing four-digit disambiguator) in the question are the
  * names, at most five. The names are data: the resolver alone decides whether they match anyone.
  */
object LocalPlannerResponder:
  private val NamePattern = """\b[A-Z][a-z]+(?: [A-Z][a-z]+)+(?: \d{4})?\b""".r
  private val MaxNames    = 5

  def namesIn(question: String): List[String] =
    NamePattern.findAllIn(question).toList.distinct.take(MaxNames)

  def responder(r: ModelRequest): Option[String] =
    val base = BenchmarkResponders.responder(r)
    if r.promptId != PromptRepository.PlannerId then base
    else base.map(out => withNames(r.user.find(_.label == "user_question").map(_.text), out))

  private def withNames(question: Option[String], out: String): String =
    val q     = question.getOrElse("")
    val names = namesIn(q)
    Try(ujson.read(out)).toOption.flatMap(_.objOpt) match
      case Some(o) if names.nonEmpty && !o.value.contains("researcherNames") =>
        val ops = o.value.get("operations").flatMap(_.arrOpt).map(_.toList.flatMap(_.strOpt))
        // The keyword rule picks its operation from the names it is given.
        val lower  = q.toLowerCase(java.util.Locale.ROOT)
        val picked =
          if names.size >= 2 && lower.contains("shortest") && lower.contains("path") then
            List(QueryOperationV2.ShortestPath.wire)
          else if ops.contains(List(QueryOperationV2.SemanticExplanation.wire)) then
            QueryPlannerAgent
              .keywordOperations(q, StructuredConstraints(researcherNames = names))
              .map(_.wire)
          else ops.getOrElse(Nil)
        ujson.write(
          ujson.Obj(
            "operations"      -> ujson.Arr.from(picked.map(ujson.Str(_))),
            "researcherNames" -> ujson.Arr.from(names.map(ujson.Str(_))),
          )
        )
      case _ => out
