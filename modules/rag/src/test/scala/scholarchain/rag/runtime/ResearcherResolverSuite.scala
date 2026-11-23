package scholarchain.rag.runtime

import scholarchain.core.*

/** Resolver: ambiguous names return candidates or abstain, never silently pick one; homonym
  * suffixes are respected; stored items are verified, similarity never decides identity.
  */
class ResearcherResolverSuite extends munit.FunSuite:
  import World.*

  private def resolver(kv: MemKv = World.kv, objects: MemObjects = new MemObjects) =
    new ResearcherResolverAgent(reader(kv), vectors(objects), embedder)

  private def resolve(names: List[String], ids: List[String] = Nil): ResolutionResult =
    val p = planOf(
      names.mkString(",") + ids.mkString(","),
      names,
      List(QueryOperationV2.ResearcherMetricsLookup),
      ids,
    )
    resolver().resolve(p)

  private def one(names: String*): NameResolution = resolve(names.toList).entries.head

  test("an exact unambiguous name resolves to its researcher with high confidence") {
    val e = one("Ada Lovelace")
    assertEquals(e.status, ResolutionStatus.Resolved)
    assertEquals(e.chosen.map(_.researcherId), Some(ada))
    assert(e.chosen.get.confidence >= 0.8)
    assertEquals(e.chosen.get.confidence, 0.99)
    assert(
      e.chosen.get.evidenceUsed
        .containsSlice(List("identity-table", "mention-table", "metric-table"))
    )
    assert(e.chosen.get.sourceIds.nonEmpty)
  }

  test("matching is case and whitespace insensitive") {
    val e = one("  ada    LOVELACE ")
    assertEquals(e.status, ResolutionStatus.Resolved)
    assertEquals(e.chosen.map(_.researcherId), Some(ada))
  }

  test("a plain homonym name is ambiguous: both candidates are listed, nothing is chosen") {
    val e = one("Yu Liu")
    assertEquals(e.status, ResolutionStatus.Ambiguous)
    assertEquals(e.chosen, None)
    assertEquals(e.candidates.map(_.researcherId).toSet, Set(yu1, yu2))
    assert(e.reasonCodes.contains("multiple_candidates"))
    // suffix-only matches carry reduced confidence, never above the resolve threshold
    assert(e.candidates.forall(_.confidence < ResearcherResolverAgent.ResolveThreshold))
  }

  test("the homonym suffix is respected: 'Yu Liu 0002' resolves to exactly that researcher") {
    val e = one("Yu Liu 0002")
    assertEquals(e.status, ResolutionStatus.Resolved)
    assertEquals(e.chosen.map(_.researcherId), Some(yu2))
    assertEquals(e.candidates.map(_.researcherId), List(yu2))
  }

  test("a wrong suffix does not fall back to another homonym") {
    val e = one("Yu Liu 0003")
    assertEquals(e.status, ResolutionStatus.Unresolved)
    assertEquals(e.candidates, Nil)
    assert(e.reasonCodes.contains("no_matching_researcher"))
  }

  test("a single suffix-only match abstains: the user must give the suffix") {
    val e = one("Wei Chen")
    assertEquals(e.status, ResolutionStatus.Ambiguous)
    assertEquals(e.chosen, None)
    assertEquals(e.candidates.map(_.researcherId), List(wei1))
    assert(e.reasonCodes.contains("homonym_suffix_required"))
    assert(e.candidates.head.evidenceUsed.contains("name-suffix-only"))
  }

  test("two clusters with the same display name are ambiguous, sorted deterministically") {
    val e = one("Sam Rivera")
    assertEquals(e.status, ResolutionStatus.Ambiguous)
    assertEquals(e.chosen, None)
    assertEquals(e.candidates.map(_.researcherId).toSet, Set(sam1, sam2))
    assertEquals(e.candidates.map(_.researcherId), e.candidates.map(_.researcherId).sorted)
    assertEquals(e.candidates.size, 2)
  }

  test("an unknown name is unresolved with no candidates") {
    val e = one("Zed Nobody")
    assertEquals(e.status, ResolutionStatus.Unresolved)
    assertEquals(e.chosen, None)
    assertEquals(e.candidates, Nil)
  }

  test("a researcher who is not in the graph is never resolved (low confidence)") {
    val e = one("Gwen Offgraph")
    assertEquals(e.status, ResolutionStatus.Unresolved)
    assertEquals(e.chosen, None)
    assert(e.reasonCodes.contains("low_confidence"))
    assert(e.candidates.head.evidenceUsed.contains("not-in-graph"))
    assert(e.candidates.head.confidence <= 0.40)
  }

  test("a tampered identity item is not trusted: no candidate from an integrity mismatch") {
    val e = one("Tam Per")
    assertEquals(e.status, ResolutionStatus.Unresolved)
    assertEquals(e.candidates, Nil)
  }

  test("a supplied ID is verified; a name that disagrees with it is unresolved") {
    val ok = resolve(Nil, List(ada)).entries.head
    assertEquals(ok.status, ResolutionStatus.Resolved)
    assertEquals(ok.reasonCodes, List("id_supplied"))
    val mismatch = resolve(List("Alan Turing"), List(ada)).entries.head
    assertEquals(mismatch.status, ResolutionStatus.Unresolved)
    assertEquals(mismatch.chosen, None)
    assert(mismatch.reasonCodes.contains("name_id_mismatch"))
    val missing = resolve(Nil, List(rid("Nobody Stored"))).entries.head
    assertEquals(missing.status, ResolutionStatus.Unresolved)
    assertEquals(missing.reasonCodes, List("id_not_found"))
  }

  // ---- M2: a saturated top-K cannot prove a name is unique -------------------------------------

  private def queryVector(name: String): Vector[Float] =
    embedder.embed(List(name)).toOption.get.head

  /** A store of `fillers` profile vectors of unknown researchers plus one profile of `target`. The
    * fillers all score the query at 1.0. The target scores 1.0 (top of the ranking) when `high`,
    * else -1.0 (the lowest-ranked hit of the query).
    */
  private def saturatedStore(
      name: String,
      target: String,
      fillers: Int,
      high: Boolean,
  ): MemVectors =
    val v                                                    = new MemVectors
    val q                                                    = queryVector(name)
    val neg                                                  = q.map(x => -x)
    def put(entity: String, e: Vector[Float], label: String) =
      val base = vectorEntry(
        VectorIndexes.ResearcherProfile,
        EmbeddingDocumentTypes.ResearcherProfile,
        entity,
        allTime,
        label,
      )
      v.add(VectorIndexes.ResearcherProfile, base.copy(embedding = e))
    (0 until fillers).foreach(i => put(rid(s"Filler Person $i"), q, s"filler $i"))
    put(target, if high then q else neg, name)
    v

  private def withStore(v: MemVectors) = new ResearcherResolverAgent(reader(World.kv), v, embedder)

  private def resolveWith(v: MemVectors, names: List[String], ids: List[String] = Nil) =
    withStore(v)
      .resolve(
        planOf(
          names.mkString(",") + ids.mkString(","),
          names,
          List(QueryOperationV2.ResearcherMetricsLookup),
          ids,
        )
      )
      .entries
      .head

  test("M2: a full top-K whose lowest hit matches the name makes a single match ambiguous") {
    val v =
      saturatedStore("Ada Lovelace", ada, ResearcherResolverAgent.VectorTopK - 1, high = false)
    assertEquals(
      v.query(
        VectorIndexes.ResearcherProfile,
        VectorQuery(queryVector("Ada Lovelace"), ResearcherResolverAgent.VectorTopK),
      ).toOption
        .get
        .size,
      ResearcherResolverAgent.VectorTopK,
    )
    val e = resolveWith(v, List("Ada Lovelace"))
    assertEquals(e.status, ResolutionStatus.Ambiguous)
    assertEquals(e.chosen, None)
    assertEquals(e.candidates.map(_.researcherId), List(ada))
    assert(e.reasonCodes.contains("candidates_truncated"), e.reasonCodes.toString)
  }

  test("M2: a supplied researcher ID bypasses the truncation check") {
    val v =
      saturatedStore("Ada Lovelace", ada, ResearcherResolverAgent.VectorTopK - 1, high = false)
    val id = resolveWith(v, Nil, List(ada))
    assertEquals(id.status, ResolutionStatus.Resolved)
    assertEquals(id.chosen.map(_.researcherId), Some(ada))
    assertEquals(id.reasonCodes, List("id_supplied"))
    val both = resolveWith(v, List("Ada Lovelace"), List(ada))
    assertEquals(both.status, ResolutionStatus.Resolved)
    assertEquals(both.chosen.map(_.researcherId), Some(ada))
    assert(!both.reasonCodes.contains("candidates_truncated"))
  }

  test("M2: a single match in an unsaturated result still resolves") {
    // 98 fillers plus the target: 99 hits, below the top-K
    val v =
      saturatedStore("Ada Lovelace", ada, ResearcherResolverAgent.VectorTopK - 2, high = false)
    val e = resolveWith(v, List("Ada Lovelace"))
    assertEquals(e.status, ResolutionStatus.Resolved)
    assertEquals(e.chosen.map(_.researcherId), Some(ada))
    assert(!e.reasonCodes.contains("candidates_truncated"))
    // the ordinary fixture store (13 profiles) resolves too (see the first test of this suite)
    assertEquals(one("Ada Lovelace").status, ResolutionStatus.Resolved)
  }

  test("M2: a full top-K whose lowest hit does not match the name still resolves (narrowed rule)") {
    // the matching profile ranks first and the ranking has already left the name behind
    val v = saturatedStore("Ada Lovelace", ada, ResearcherResolverAgent.VectorTopK - 1, high = true)
    val e = resolveWith(v, List("Ada Lovelace"))
    assertEquals(e.status, ResolutionStatus.Resolved)
    assertEquals(e.chosen.map(_.researcherId), Some(ada))
    assert(!e.reasonCodes.contains("candidates_truncated"))
  }

  test("injection text as a name resolves to nothing and never reaches a graph call") {
    val g   = FakeGraph.noGraph
    val env = World.env(graph = g)
    val out = env.workflow.run(
      QueryRequest(
        "Who is this?",
        StructuredConstraints(
          operations = List(QueryOperationV2.DirectCoauthors),
          researcherNames = List("Ada'}) DETACH DELETE (n) //"),
        ),
      )
    )
    assert(out.abstained)
    assertEquals(out.abstainReason, Some("identity_unresolved"))
    assertEquals(g.queries.toList, Nil)
  }

  test("the resolver emits identity evidence with the resolution status and confidence") {
    val r = resolve(List("Yu Liu"))
    assertEquals(r.allResolved, false)
    assertEquals(r.subjectIds, Nil)
    assertEquals(r.abstainEntries.size, 1)
    val facts = r.evidence.drafts.flatMap(d => Fact.parse(d.text))
    assertEquals(facts.map(_.subject).toSet, Set(yu1, yu2))
    assert(facts.forall(_.kind == Fact.Identity))
    assert(facts.forall(_.fields.value("resolutionStatus") == ujson.Str("ambiguous")))
    assert(facts.forall(_.fields.value("candidateCount") == ujson.Num(2)))
  }

  test("a multi-subject plan resolves each position independently; one ambiguity blocks all") {
    val r = resolve(List("Ada Lovelace", "Yu Liu"))
    assertEquals(
      r.entries.map(_.status),
      List(ResolutionStatus.Resolved, ResolutionStatus.Ambiguous),
    )
    assert(!r.allResolved)
    assertEquals(r.subjectIds, List(ada))
  }

  test("workflow: an ambiguous name abstains and lists every candidate in the answer") {
    val env = World.env()
    val out = env.workflow.run(
      QueryRequest(
        "Who are the coauthors of Yu Liu?",
        StructuredConstraints(
          operations = List(QueryOperationV2.DirectCoauthors),
          researcherNames = List("Yu Liu"),
        ),
      )
    )
    assert(out.abstained)
    assertEquals(out.abstainReason, Some("identity_ambiguous"))
    assert(out.answerText.contains(yu1) && out.answerText.contains(yu2), out.answerText)
    assertEquals(out.answer.map(_.claims), Some(Nil))
    assertEquals(env.graph.queries.toList, Nil)
  }
