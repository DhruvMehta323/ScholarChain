package scholarchain.services

import java.nio.file.Files

import scholarchain.core.*
import scholarchain.rag.runtime.*

/** The composed Phase 09 benchmark: rag over storage's in-memory fakes loaded from the Phase 07 L4
  * output and the Phase 08 vectors, with the fake model client. Every benchmark question runs end
  * to end; the whole benchmark runs twice into R1 and R2 and the outputs must be byte-identical.
  *
  * The suite FAILS (does not skip) when an input is absent. Per-question tests assert specific
  * values (resolved IDs, stored numbers, hops, abstentions, candidates), never just "non-empty".
  */
class Phase09BenchmarkSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")

  private lazy val r1: BenchRun = Phase09Benchmark.run(Phase09Paths.benchmarkOut.resolve("R1"))
  private lazy val r2: BenchRun = Phase09Benchmark.run(Phase09Paths.benchmarkOut.resolve("R2"))

  private val questions = Phase09Benchmark.questions()

  test("the L4 loader output and the Phase 08 vectors are present (this suite never skips)") {
    Phase09Paths.requireInputs()
    assert(Files.size(Phase09Paths.embeddings.resolve("vectors.jsonl")) > 0)
    assert(r1.world.loadedItems("researcher-metrics") > 1000, r1.world.loadedItems.toString)
    assert(r1.world.loadedItems("identity-clusters") > 1000)
    assert(r1.world.loadedItems("coauthor-edge-works") > 1000)
    assert(r1.world.vectorCount > 5000, r1.world.vectorCount.toString)
  }

  test("the benchmark has at least 30 data-derived questions, 3 injections and 3 ambiguous names") {
    assertEquals(questions.size, 44)
    assert(questions.count(_("injection").bool) >= 3)
    assert(questions.count(_("category").str == "ambiguous_name") >= 3)
    assertEquals(questions.map(_("id").str).distinct.size, questions.size)
    assert(questions.forall(_("expected").obj.contains("abstain")))
  }

  questions.foreach { q =>
    val id = q("id").str
    test(s"benchmark $id (${q("category").str}) end to end") {
      val r = r1.results.find(_.id == id).getOrElse(fail(s"no result for $id"))
      assert(
        r.pass,
        s"$id failed ${r.failed.size} check(s): " +
          r.failed.map(c => s"${c.name} [${c.detail.take(200)}]").mkString("; "),
      )
    }
  }

  test("every question ran: 44 results and 44 per-question outcome files in R1") {
    assertEquals(r1.results.size, 44)
    val files = Files.list(r1.dir.resolve("questions"))
    try assertEquals(files.count().toInt, 44)
    finally files.close()
  }

  test("per-category pass counts (printed and written to summary.json)") {
    val rows = r1.results.groupBy(_.category).toList.sortBy(_._1).map { (c, rs) =>
      f"$c%-22s ${rs.count(_.pass)}%2d / ${rs.size}%2d   (evidence-only ${rs.count(_.evidencePass)}%2d)   failed=${rs.filterNot(_.pass).map(_.id).mkString(",")}"
    }
    println("PHASE09 benchmark R1 per-category (strict / evidence-only):\n" + rows.mkString("\n"))
    println(s"PHASE09 benchmark R1 total ${r1.results.count(_.pass)} / ${r1.results.size}")
    assertEquals(r1.results.groupBy(_.category).values.map(_.size).sum, 44)
    assert(Files.exists(r1.dir.resolve("summary.json")))
  }

  test(
    "injection questions: plan allowlisted, no foreign graph parameter, no write outside audit"
  ) {
    val inj = r1.results.filter(_.injection)
    assert(inj.size >= 3)
    inj.foreach { r =>
      val byName = r.checks.map(c => c.name -> c).toMap
      assert(byName("injection-plan-allowlisted").ok, r.id)
      assert(byName("graph-params-are-ids-or-windows").ok, r.id)
      assert(byName("writes-only-audit-tables").ok, r.id)
    }
  }

  test("ambiguous-name questions abstain and list at least two candidates, never one") {
    val amb = r1.results.filter(_.category == "ambiguous_name")
    assert(amb.size >= 3)
    amb.foreach { r =>
      assertEquals(r.json("abstained").bool, true, r.id)
      assert(r.json("candidateIds").arr.size >= 2, r.id)
      assertEquals(r.json("claims").arr.size, 0, r.id)
    }
  }

  test("no model, AWS or network client was created or called beyond the fake") {
    assertEquals(SdkConverseInvoker.clientsConstructed, 0)
    assert(r1.world.model.callCount > 0)
    assertEquals(r1.world.model.provider, ModelProvider.Fake)
    assertEquals(r1.world.settings.provider, ModelProvider.Fake)
    assertEquals(r1.world.objects.refused, Nil)
  }

  test("R2: a second full run is byte-identical to R1 (every file)") {
    val a = LocalEmbeddingPipeline.snapshot(r1.dir)
    val b = LocalEmbeddingPipeline.snapshot(r2.dir)
    assert(a.nonEmpty)
    assertEquals(a.map(_._1), b.map(_._1), "different file sets")
    a.zip(b).foreach { case ((pa, ba), (_, bb)) =>
      assert(ba == bb, s"$pa differs between R1 and R2")
    }
    assertEquals(r1.results.map(r => (r.id, r.pass)), r2.results.map(r => (r.id, r.pass)))
    assertEquals(ujson.write(r1.summary), ujson.write(r2.summary))
    // the run holds 44 question files, a summary and the audit objects
    assert(a.count(_._1.startsWith("questions/")) == 44)
    assert(a.exists(_._1.startsWith("audit-objects/")))
  }

  test("a re-run with the same inputs reuses query IDs: the audit of R2 equals that of R1") {
    val ids1 = r1.results.map(r => r.json("queryId").str)
    val ids2 = r2.results.map(r => r.json("queryId").str)
    assertEquals(ids1, ids2)
    assertEquals(ids1.distinct.size, ids1.size)
  }

  // ---- controls: the judge must notice a wrong expectation --------------------------------------

  private def judgeAgain(id: String)(mutate: ujson.Value => Unit): List[Check] =
    val q   = ujson.read(ujson.write(questions.find(_("id").str == id).get))
    val out = r1.world.workflow.run(
      QueryRequest(q("question").str, Phase09Benchmark.constraints(q("constraints")))
    )
    mutate(q)
    Phase09Benchmark.judge(q, out, Nil, Nil, Nil, r1.world.tables)

  test("control: an unmodified expectation passes the judge") {
    val checks = judgeAgain("coauthor_breadth-01")(_ => ())
    assert(checks.forall(_.ok), checks.filterNot(_.ok).toString)
    assert(checks.exists(_.name.startsWith("fact:")))
    assert(checks.exists(_.name.startsWith("assert:")))
  }

  test("control: a stored number that is off by one is detected") {
    val checks = judgeAgain("coauthor_breadth-01") { q =>
      val f = q("expected")("facts")(1)
      f("value") = ujson.Num(f("value").num + 1)
    }
    assert(checks.exists(c => c.name.startsWith("fact:1:") && !c.ok), checks.toString)
    assert(checks.exists(c => c.name.startsWith("assert:1:") && !c.ok))
  }

  test("control: a wrong resolved ID, a wrong abstain flag and a forbidden string are detected") {
    val wrongId = judgeAgain("coauthor_breadth-01") { q =>
      q("expected")("resolvedIds") = ujson.Arr(ujson.Str("researcher:sc1:" + "0" * 64))
    }
    assert(wrongId.exists(c => c.name == "resolved-ids" && !c.ok))
    val wrongAbstain =
      judgeAgain("coauthor_breadth-01")(q => q("expected")("abstain") = ujson.Bool(true))
    assert(wrongAbstain.exists(c => c.name == "abstain-matches" && !c.ok))
    val forbidden = judgeAgain("coauthor_breadth-01") { q =>
      q("expected")("forbiddenInAnswer") = ujson.Arr(ujson.Str("distinct coauthors"))
    }
    assert(forbidden.exists(c => c.name.startsWith("forbidden-absent") && !c.ok))
    val ambiguous = judgeAgain("ambiguous_name-01") { q =>
      q("expected")("candidateIds") = ujson.Arr(ujson.Str("researcher:sc1:" + "1" * 64))
    }
    assert(ambiguous.exists(c => c.name == "candidates-subset" && !c.ok))
  }

  // ---- end-to-end adversarial: a model that fabricates cannot change the answer -----------------

  private def fabricatingResponder(r: ModelRequest): Option[String] =
    if r.promptId != "answer-synthesis" then BenchmarkResponders.responder(r)
    else
      val items = r.user
        .find(_.label == "retrieved_evidence")
        .map(s => ujson.read(s.text).arr.toList)
        .getOrElse(Nil)
      val metrics = items.flatMap { i =>
        Fact.parse(i("text").str).filter(_.kind == Fact.Metrics).map(f => (i("evidenceId").str, f))
      }.headOption
      metrics.map { (evId, f) =>
        ujson.write(
          ujson.Obj(
            "claims" -> ujson.Arr(
              ujson.Obj(
                "text"        -> s"In window 1000-9999, ${f.subject} has 9999 distinct coauthors.",
                "evidenceIds" -> ujson.Arr(evId),
                "assertions"  -> ujson.Arr(),
              ),
              ujson.Obj(
                "text"        -> s"${f.subject} has 41 distinct coauthors.",
                "evidenceIds" -> ujson.Arr("evidence:" + "f" * 64),
                "assertions"  -> ujson.Arr(),
              ),
            ),
            "uncertainty"     -> ujson.Arr(),
            "missingEvidence" -> ujson.Arr(),
          )
        )
      }

  test("a model that fabricates a number and an evidence ID cannot put either into the answer") {
    val dir   = Phase09Paths.benchmarkOut.resolve("adversarial-audit")
    val model = new FakeModelClient(responder = Some(fabricatingResponder))
    val world = new Phase09World(dir, Some(model))
    val out   = world.workflow.run(
      QueryRequest(
        "How many coauthors does Ben Jeurissen have?",
        StructuredConstraints(
          operations = List(QueryOperationV2.ResearcherMetricsLookup),
          researcherNames = List("Ben Jeurissen"),
        ),
      )
    )
    val statuses = out.verification.toList.flatMap(_.all)
    assertEquals(statuses.size, 2)
    val byText = statuses.map(v => v.claim.text -> v.verification.status).toMap
    assertEquals(
      byText.find(_._1.contains("has 41 distinct")).map(_._2),
      Some(ClaimVerificationStatus.Unsupported),
      "a fabricated evidence ID must be unsupported",
    )
    assertNotEquals(
      byText.find(_._1.contains("has 9999 distinct")).map(_._2),
      Some(ClaimVerificationStatus.Verified),
      "a wrong number (9999 coauthors) was verified",
    )
    // the verifier rules: no assertion states 9999 (the digits of the window label never count)
    val wrong = statuses.find(_.claim.text.contains("has 9999 distinct")).get
    assertEquals(wrong.verification.status, ClaimVerificationStatus.Unsupported)
    assert(
      wrong.verification.reasonCodes.contains("number_without_matching_assertion"),
      wrong.verification.reasonCodes.toString,
    )
    assert(!out.answerText.contains("9999 distinct"), out.answerText)
    assert(!out.answerText.contains("has 41 distinct"), out.answerText)
  }

  /** Claims the review found verifiable by mistake (H2, H3), stated over real L4 metrics. */
  private def h2h3Responder(r: ModelRequest): Option[String] =
    if r.promptId != "answer-synthesis" then BenchmarkResponders.responder(r)
    else
      val items = r.user
        .find(_.label == "retrieved_evidence")
        .map(s => ujson.read(s.text).arr.toList)
        .getOrElse(Nil)
      val metrics = items.flatMap { i =>
        Fact.parse(i("text").str).filter(_.kind == Fact.Metrics).map(f => (i("evidenceId").str, f))
      }.headOption
      metrics.map { (evId, f) =>
        val w    = f.window.get
        val n    = f.fields.value("coauthorCount").num.toInt
        val word = if n == 12 then "thirteen" else "twelve"
        val team = f.fields.value("maxTeamSize").num.toInt
        def claim(text: String, assertions: ujson.Value*) = ujson.Obj(
          "text"        -> text,
          "evidenceIds" -> ujson.Arr(evId),
          "assertions"  -> ujson.Arr.from(assertions),
        )
        def asr(field: String, v: Int) = ujson.Obj(
          "kind"    -> "metrics",
          "subject" -> f.subject,
          "window"  -> w,
          "field"   -> field,
          "value"   -> v,
        )
        ujson.write(
          ujson.Obj(
            "claims" -> ujson.Arr(
              claim(s"In window $w, ${f.subject} has $word distinct coauthors."),
              claim(
                s"In window $w, Grace Hopper (${f.subject}) has $n distinct coauthors.",
                asr("coauthorCount", n),
              ),
              claim(
                s"In window $w, ${f.subject} has $team distinct coauthors.",
                asr("maxTeamSize", team),
              ),
              claim(
                s"In window 1500-1504, ${f.subject} has $n distinct coauthors.",
                asr("coauthorCount", n),
              ),
            ),
            "uncertainty"     -> ujson.Arr(),
            "missingEvidence" -> ujson.Arr(),
          )
        )
      }

  test(
    "H2/H3 on real L4 data: a number word, a wrong name, a wrong field or window is not verified"
  ) {
    val dir   = Phase09Paths.benchmarkOut.resolve("adversarial-audit-h2h3")
    val model = new FakeModelClient(responder = Some(h2h3Responder))
    val world = new Phase09World(dir, Some(model))
    val out   = world.workflow.run(
      QueryRequest(
        "How many coauthors does Ben Jeurissen have?",
        StructuredConstraints(
          operations = List(QueryOperationV2.ResearcherMetricsLookup),
          researcherNames = List("Ben Jeurissen"),
        ),
      )
    )
    val all = out.verification.toList.flatMap(_.all)
    assertEquals(all.size, 4, all.map(_.claim.text).toString)
    val st = all.map(_.verification.status)
    // proposed order: number word, wrong name, wrong field, wrong window
    assertEquals(st(0), ClaimVerificationStatus.Unsupported, "a number word with no assertion")
    assertEquals(
      st(1),
      ClaimVerificationStatus.Conflicting,
      "a wrong display name beside the ID",
    )
    assertEquals(st(2), ClaimVerificationStatus.Unsupported, "an assertion on another field")
    assertEquals(st(3), ClaimVerificationStatus.Unsupported, "a window no cited fact carries")
    assert(!all.exists(_.verification.status == ClaimVerificationStatus.Verified), all.toString)
    assert(!out.answerText.contains("twelve"), out.answerText)
    assert(!out.answerText.contains("1500-1504"), out.answerText)
  }

  test("semantic-only answers name the retrieved entity, not a placeholder") {
    val topic = r1.results.find(_.id == "topic-01").get
    val inj   = r1.results.find(_.id == "prompt_injection-04").get
    for r <- List(topic, inj) do
      assert(
        !r.json("answerText").str.contains("entity:unknown"),
        s"${r.id}: ${r.json("answerText").str.take(200)}",
      )
  }

  /** Round-2 inputs (N1 negation against a true path, N2 a number given to the wrong owner) stated
    * over real L4 facts of two researchers.
    */
  private def n1n2Responder(r: ModelRequest): Option[String] =
    if r.promptId != "answer-synthesis" then BenchmarkResponders.responder(r)
    else
      val items = r.user
        .find(_.label == "retrieved_evidence")
        .map(s => ujson.read(s.text).arr.toList)
        .getOrElse(Nil)
      val parsed = items.flatMap { i =>
        Fact.parse(i("text").str).map(f => (i("evidenceId").str, f))
      }
      val path = parsed.find((_, f) => f.kind == Fact.Path && f.fields.value.contains("hops"))
      val ms   = parsed.filter((_, f) => f.kind == Fact.Metrics)
      val ids  = parsed.filter((_, f) => f.kind == Fact.Identity)
      for
        (pEv, pf) <- path
        if ms.size >= 2
        (aEv, af) = ms(0)
        (bEv, bf) = ms(1)
      yield
        val from = pf.fields.value("hops").arr.head.str
        val to   = pf.fields.value("hops").arr.last.str
        val idEv = ids.map(_._1)
        def asr(kind: String, f: Fact, field: String, v: ujson.Value) = ujson.Obj(
          "kind"    -> kind,
          "subject" -> f.subject,
          "window"  -> f.window.map(ujson.Str(_)).getOrElse(ujson.Null),
          "field"   -> field,
          "value"   -> v,
        )
        val bCount = bf.fields.value("coauthorCount")
        ujson.write(
          ujson.Obj(
            "claims" -> ujson.Arr(
              ujson.Obj(
                "text" -> s"No collaboration path between $from and $to was found in window ${pf.window.getOrElse("")}.",
                "evidenceIds" -> ujson.Arr.from((pEv :: idEv).map(ujson.Str(_))),
                "assertions"  -> ujson.Arr(asr(Fact.Path, pf, "found", ujson.Bool(true))),
              ),
              ujson.Obj(
                "text" -> s"In window ${af.window.getOrElse("")}, ${af.subject} has ${bCount.num.toInt} distinct coauthors.",
                "evidenceIds" -> ujson.Arr.from((aEv :: bEv :: idEv).map(ujson.Str(_))),
                "assertions"  -> ujson.Arr(asr(Fact.Metrics, bf, "coauthorCount", bCount)),
              ),
            ),
            "uncertainty"     -> ujson.Arr(),
            "missingEvidence" -> ujson.Arr(),
          )
        )

  test("N1 negation and N2 wrong owner on real L4 data are neither verified nor in the answer") {
    val dir   = Phase09Paths.benchmarkOut.resolve("adversarial-audit-n1n2")
    val model = new FakeModelClient(responder = Some(n1n2Responder))
    val world = new Phase09World(dir, Some(model))
    val out   = world.workflow.run(
      QueryRequest(
        "How are C. Henry and V. Viswanathan connected?",
        StructuredConstraints(
          operations = List(
            QueryOperationV2.ShortestPath,
            QueryOperationV2.ResearcherMetricsLookup,
          ),
          researcherNames = List("C. Henry", "V. Viswanathan"),
          pathDepth = Some(4),
        ),
      )
    )
    val all = out.verification.toList.flatMap(_.all)
    assertEquals(all.size, 2, all.map(_.claim.text).toString)
    val neg   = all.find(_.claim.text.startsWith("No collaboration path")).get
    val owner = all.find(_.claim.text.contains("distinct coauthors")).get
    assertEquals(neg.verification.status, ClaimVerificationStatus.Conflicting)
    assert(
      neg.verification.reasonCodes.contains("negation_contradicts_assertion"),
      neg.verification.reasonCodes.toString,
    )
    assertEquals(owner.verification.status, ClaimVerificationStatus.Unsupported)
    assert(
      owner.verification.reasonCodes.contains("number_without_matching_assertion"),
      owner.verification.reasonCodes.toString,
    )
    assert(!all.exists(_.verification.status == ClaimVerificationStatus.Verified), all.toString)
    all.foreach(v => assert(!out.answerText.contains(v.claim.text), out.answerText))
    assert(!out.answerText.contains("No collaboration path"), out.answerText)
  }
