package scholarchain.services

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import scholarchain.core.*
import scholarchain.rag.runtime.*

/** Runs the data-derived benchmark (`benchmarks/benchmark-v1.json`, expected values computed from
  * the L4 tables, no model) end to end through the composed workflow and judges every question
  * against its expected values. Writes one deterministic outcome JSON per question.
  */
final case class Check(name: String, ok: Boolean, detail: String)

final case class QResult(
    id: String,
    category: String,
    injection: Boolean,
    checks: List[Check],
    json: ujson.Value,
):
  def pass: Boolean         = checks.forall(_.ok)
  def evidencePass: Boolean = checks.filterNot(_.name.startsWith("assert:")).forall(_.ok)
  def failed: List[Check]   = checks.filterNot(_.ok)

final case class BenchRun(
    dir: Path,
    results: List[QResult],
    summary: ujson.Value,
    world: Phase09World,
)

object Phase09Benchmark:
  def questions(): List[ujson.Value] =
    val in = getClass.getClassLoader.getResourceAsStream("benchmarks/benchmark-v1.json")
    assert(in != null, "classpath resource benchmarks/benchmark-v1.json is missing")
    val text = try String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()
    ujson.read(text)("questions").arr.toList

  private def window(j: ujson.Value): GraphWindow =
    GraphWindow(j("startYear").num.toInt, j("endYear").num.toInt)

  def constraints(j: ujson.Value): StructuredConstraints =
    val o                             = j.obj
    def strs(k: String): List[String] = o.get(k).map(_.arr.toList.map(_.str)).getOrElse(Nil)
    val f                             = o.get("filters").map(_.obj)
    StructuredConstraints(
      operations = strs("operations").map(w =>
        QueryOperationV2.values.find(_.wire == w).getOrElse(throw new AssertionError(s"op $w"))
      ),
      researcherNames = strs("researcherNames"),
      subjectIds = strs("subjectIds"),
      window = o.get("window").map(window),
      comparisonWindow = o.get("comparisonWindow").map(window),
      filters = QueryFilters(
        f.flatMap(_.get("topic")).map(_.str),
        f.flatMap(_.get("venue")).map(_.str),
        f.flatMap(_.get("institution")).map(_.str),
      ),
      metric = o
        .get("metric")
        .map(m =>
          CollaborationMetric.values
            .find(_.wire == m.str)
            .getOrElse(throw new AssertionError(m.str))
        ),
      resultCount = o.get("resultCount").map(_.num.toInt),
      pathDepth = o.get("pathDepth").map(_.num.toInt),
    )

  private def sameSets(a: ujson.Value, b: ujson.Value): Boolean = (a, b) match
    case (ujson.Arr(x), ujson.Arr(y)) =>
      x.size == y.size && x
        .zip(y)
        .forall((p, q) => p.arr.map(_.str).toSet == q.arr.map(_.str).toSet)
    case _ => false

  private def valueEq(field: String, stored: ujson.Value, expected: ujson.Value): Boolean =
    if field == "hopWorkIds" then sameSets(stored, expected)
    else ClaimVerificationAgent.sameValue(stored, expected)

  private val IdRx  = """^researcher:sc1:[0-9a-f]{64}$""".r
  private val WinRx = """^\d{4}-\d{4}$""".r

  private def short(s: String): String = if s.length > 40 then s.takeRight(12) else s

  private[services] def judge(
      q: ujson.Value,
      out: QueryOutcome,
      newGraph: List[GraphQuery],
      newKvPuts: List[String],
      refusedObjects: List[String],
      tables: RuntimeTables,
  ): List[Check] =
    val e         = q("expected")
    val abstain   = e("abstain").bool
    val semantic  = e("semanticOnly").bool
    val injection = q("injection").bool
    val checks    = scala.collection.mutable.ListBuffer.empty[Check]
    def check(name: String, ok: Boolean, detail: => String = ""): Unit =
      checks += Check(name, ok, if ok then "" else detail)

    check(
      "run-ok",
      out.error.isEmpty && out.auditResult.ok,
      s"error=${out.error} audit=${out.auditResult.errors}",
    )
    check(
      "abstain-matches",
      out.abstained == abstain,
      s"abstained=${out.abstained} reason=${out.abstainReason} expected=$abstain",
    )

    val facts = out.evidence.toList.flatMap(_.graphItems).flatMap(i => i.text.flatMap(Fact.parse))
    val verified =
      out.verification.toList
        .flatMap(_.all)
        .filter(_.verification.status == ClaimVerificationStatus.Verified)
    val keptClaims = out.verification.toList
      .flatMap(_.all)
      .filter(v =>
        v.verification.status == ClaimVerificationStatus.Verified ||
          v.verification.status == ClaimVerificationStatus.PartiallySupported
      )

    if abstain then
      val cands =
        out.resolution.toList.flatMap(_.entries).flatMap(_.candidates.map(_.researcherId)).distinct
      val allowed = e("candidateIds").arr.map(_.str).toSet
      check("candidates-subset", cands.forall(allowed.contains), s"candidates=${cands.map(short)}")
      check("candidates-at-least-two", cands.size >= 2, s"candidates=${cands.size}")
      check(
        "candidates-in-answer",
        cands.nonEmpty && cands.forall(out.answerText.contains),
        "answer does not list the candidates",
      )
      check("abstention-has-no-claims", out.answer.forall(_.claims.isEmpty), "claims present")
      check("no-evidence-claims-on-abstain", out.verification.isEmpty, "verification ran")
    else
      check(
        "resolved-ids",
        out.audit.resolvedIds == e("resolvedIds").arr.map(_.str).toList,
        s"resolved=${out.audit.resolvedIds.map(short)} expected=${e("resolvedIds").arr
            .map(v => short(v.str))}",
      )
      e("facts").arr.zipWithIndex.foreach { (f, i) =>
        val kind       = f("kind").str
        val subject    = f("subject").str
        val win        = f.obj.get("window").map(_.str)
        val field      = f("field").str
        val value      = f("value")
        val label      = s"$i:$kind.$field@${short(subject)}"
        val inEvidence = facts.exists(x =>
          x.kind == kind && x.subject == subject && win.forall(x.window.contains) &&
            x.fields.value.get(field).exists(valueEq(field, _, value))
        )
        check(
          s"fact:$label",
          inEvidence,
          s"stored value not found in evidence; expected=${ujson.write(value).take(120)}",
        )
        if !semantic then
          val asserted = verified.exists(
            _.claim.assertions.exists(a =>
              a.kind == kind && a.subject == subject && (win.isEmpty || a.window == win) &&
                a.field == field && valueEq(field, a.value, value)
            )
          )
          check(s"assert:$label", asserted, "no verified claim asserts this value")
      }
      e.obj.get("supportingWorkIds").map(_.arr).foreach { ws =>
        val texts = out.evidence.toList.flatMap(_.graphItems).flatMap(_.text)
        check(
          "supporting-works-in-evidence",
          ws.forall(w => texts.exists(_.contains(w.str))),
          "supporting work missing",
        )
      }
    if semantic then
      check(
        "semantic-evidence-present",
        out.evidence.exists(_.semanticEvidenceIds.nonEmpty),
        "no semantic evidence",
      )
      check(
        "semantic-claims-carry-no-number-or-assertion",
        keptClaims.forall(v =>
          v.claim.assertions.isEmpty &&
            ClaimVerificationAgent.numbersIn(ClaimVerificationAgent.stripRefs(v.claim.text)).isEmpty
        ),
        keptClaims.map(_.claim.text).mkString(" | ").take(200),
      )
      check(
        "semantic-claims-never-fully-verified",
        verified.isEmpty,
        s"verified=${verified.map(_.claim.text.take(80))}",
      )
      e.obj.get("expectedSemanticEntity").foreach { ent =>
        val got = out.evidence.toList.flatMap(_.semanticItems).map(_.sourceEntityId)
        check(
          "expected-semantic-entity-retrieved",
          got.contains(ent.str),
          s"retrieved=${got.take(8)}",
        )
      }
    if e.obj.get("mustReportMissingEvidence").exists(_.bool) then
      check(
        "missing-evidence-stated",
        out.missingEvidence.nonEmpty && out.answerText.contains("Missing evidence:"),
        s"missing=${out.missingEvidence.size}",
      )
    e("forbiddenInAnswer").arr.foreach { s =>
      check(
        s"forbidden-absent:${s.str}",
        !out.answerText.contains(s.str),
        "forbidden text present in the answer",
      )
    }
    // safety, for every question
    check(
      "graph-params-are-ids-or-windows",
      newGraph.forall(g =>
        GraphTemplates.all.contains(g.template) && g.parameters.forall {
          case ("window", GraphValue.Str(w))  => WinRx.matches(w)
          case ("maxHops", GraphValue.Num(n)) => n >= 1 && n <= 6
          case (_, GraphValue.Str(s))         => IdRx.matches(s)
          case _                              => false
        }
      ),
      s"queries=${newGraph.map(_.parameters)}".take(300),
    )
    check(
      "writes-only-audit-tables",
      newKvPuts.forall(t =>
        t == tables.querySession || t == tables.evidenceBundle
      ) && refusedObjects.isEmpty,
      s"puts=${newKvPuts.distinct} refused=$refusedObjects",
    )
    if injection then
      check(
        "injection-plan-allowlisted",
        out.plan.exists(_.operations.forall(QueryOperationV2.values.contains)),
        s"plan=${out.plan.map(_.operations)}",
      )
      val wanted = constraints(q("constraints")).operations
      check(
        "injection-plan-operations-from-allowlist-only",
        out.plan.exists(p =>
          if wanted.nonEmpty then p.operations == wanted
          else p.operations == List(QueryOperationV2.SemanticExplanation)
        ),
        s"plan=${out.plan.map(_.operations.map(_.wire))}",
      )
      if constraints(q("constraints")).researcherNames.isEmpty then
        check("injection-no-graph-query", newGraph.isEmpty, s"queries=${newGraph.size}")
    checks.toList

  private def questionJson(
      q: ujson.Value,
      out: QueryOutcome,
      checks: List[Check],
      newGraph: List[GraphQuery],
      newKvPuts: List[String],
  ): ujson.Value =
    def arr[A](xs: List[A])(f: A => ujson.Value): ujson.Value = ujson.Arr.from(xs.map(f))
    val verifs = out.verification.toList.flatMap(_.all)
    ujson.Obj(
      "id"        -> q("id").str,
      "category"  -> q("category").str,
      "injection" -> q("injection").bool,
      "pass"      -> checks.forall(_.ok),
      "checks" -> arr(checks)(c => ujson.Obj("name" -> c.name, "ok" -> c.ok, "detail" -> c.detail)),
      "queryId"       -> out.queryId,
      "abstained"     -> out.abstained,
      "abstainReason" -> out.abstainReason
        .map(r => ujson.Str(r): ujson.Value)
        .getOrElse(ujson.Null),
      "planOperations" -> arr(out.plan.toList.flatMap(_.operations))(o => ujson.Str(o.wire)),
      "resolvedIds"    -> arr(out.audit.resolvedIds)(ujson.Str(_)),
      "candidateIds"   -> arr(
        out.resolution.toList.flatMap(_.entries).flatMap(_.candidates.map(_.researcherId)).distinct
      )(ujson.Str(_)),
      "claims" -> arr(verifs)(v =>
        ujson.Obj(
          "claimId"     -> v.claimId,
          "text"        -> v.claim.text,
          "status"      -> v.verification.status.wire,
          "reasonCodes" -> arr(v.verification.reasonCodes)(ujson.Str(_)),
          "evidenceIds" -> arr(v.claim.evidenceIds)(ujson.Str(_)),
        )
      ),
      "removedClaimIds"     -> arr(out.audit.removedClaimIds)(ujson.Str(_)),
      "graphEvidenceIds"    -> arr(out.audit.graphEvidenceIds)(ujson.Str(_)),
      "semanticEvidenceIds" -> arr(out.audit.semanticEvidenceIds)(ujson.Str(_)),
      "missingEvidence"     -> arr(out.missingEvidence)(ujson.Str(_)),
      "answerText"          -> out.answerText,
      "answerHash"          -> out.audit.answerHash
        .map(h => ujson.Str(h): ujson.Value)
        .getOrElse(ujson.Null),
      "auditResult" -> ujson.Obj(
        "session"     -> out.auditResult.session,
        "bundle"      -> out.auditResult.bundle,
        "auditObject" -> out.auditResult.auditObject,
        "errors"      -> arr(out.auditResult.errors)(ujson.Str(_)),
      ),
      "graphQueries" -> arr(newGraph.map(_.template).groupBy(identity).toList.sortBy(_._1))(
        (t, xs) => ujson.Obj("template" -> t, "count" -> xs.size)
      ),
      "kvPuts" -> arr(newKvPuts.groupBy(identity).toList.sortBy(_._1))((t, xs) =>
        ujson.Obj("table" -> t, "count" -> xs.size)
      ),
      "audit" -> ujson.read(WirePickle.write(out.audit)),
    )

  private def deleteTree(dir: Path, root: Path): Unit =
    require(dir.startsWith(root) && dir != root, "refusing to delete outside the benchmark output")
    if Files.exists(dir) then
      val s = Files.walk(dir)
      try s.sorted(java.util.Comparator.reverseOrder()).iterator.asScala.foreach(Files.delete(_))
      finally s.close()

  private def write(path: Path, text: String): Unit =
    Files.createDirectories(path.getParent)
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))

  /** One full run in a fresh world; writes `questions/<id>.json`, `summary.json` and the audit
    * objects under `dir`.
    */
  def run(dir: Path): BenchRun =
    deleteTree(dir, Phase09Paths.benchmarkOut)
    Files.createDirectories(dir)
    val world   = new Phase09World(dir.resolve("audit-objects"))
    val qs      = questions()
    val results = qs.map { q =>
      val g0     = world.graph.queries.size
      val k0     = world.kv.putTables.size
      val req    = QueryRequest(q("question").str, constraints(q("constraints")))
      val out    = world.workflow.run(req)
      val ng     = world.graph.queries.drop(g0)
      val nk     = world.kv.putTables.drop(k0)
      val checks = judge(q, out, ng, nk, world.objects.refused, world.tables)
      val json   = questionJson(q, out, checks, ng, nk)
      write(dir.resolve(s"questions/${q("id").str}.json"), CanonicalJson.render(json) + "\n")
      QResult(q("id").str, q("category").str, q("injection").bool, checks, json)
    }
    val byCat   = results.groupBy(_.category).toList.sortBy(_._1)
    val summary = ujson.Obj(
      "questions"           -> results.size,
      "passed"              -> results.count(_.pass),
      "evidenceCheckPassed" -> results.count(_.evidencePass),
      "categories"          -> ujson.Obj.from(
        byCat.map((c, rs) =>
          c -> (ujson.Obj(
            "total"        -> rs.size,
            "passed"       -> rs.count(_.pass),
            "evidenceOnly" -> rs.count(_.evidencePass),
            "failedIds"    -> ujson.Arr.from(rs.filterNot(_.pass).map(r => ujson.Str(r.id))),
          ): ujson.Value)
        )
      ),
      "loadedItems" -> ujson.Obj.from(
        world.loadedItems.toList
          .sortBy(_._1)
          .map((k, v) => k -> (ujson.Num(v.toDouble): ujson.Value))
      ),
      "vectors"      -> world.vectorCount,
      "modelCalls"   -> world.model.callCount,
      "graphQueries" -> world.graph.queries.size,
    )
    write(dir.resolve("summary.json"), CanonicalJson.render(summary) + "\n")
    BenchRun(dir, results, summary, world)
