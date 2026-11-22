package scholarchain.rag

import scholarchain.core.*

import RagFx.*

/** Smoke neighbors over a fixed corpus, the semantic-context format, and the pointer a hit carries.
  */
class RetrievalSmokeSuite extends munit.FunSuite:

  private val embedder = new LocalHashEmbedder(1024)

  private val people =
    List("Ada Lovelace", "Alan Turing", "Grace Hopper", "Edsger Dijkstra", "Barbara Liskov")

  private val topicWords = List(
    "analytical engines notation bernoulli punched",
    "computability halting machines tapes enigma",
    "compilers cobol flowmatic navy linkers",
    "semaphores shortest paths algol structured",
    "abstraction substitution clu distributed liskov",
  )

  private val corpus: List[SemanticSummary] =
    people
      .zip(topicWords)
      .map((p, w) => summary(s"$p works on $w and writes with many coauthors.", researcher(p)))

  private val communities: List[SemanticSummary] =
    List(
      "zebra quagga okapi giraffe",
      "falcon osprey kestrel harrier",
      "granite basalt quartz feldspar",
    ).zipWithIndex
      .map((w, i) =>
        summary(
          s"Community $i studies $w together.",
          s"community:2000-2004:c$i",
          EmbeddingDocumentTypes.CommunityProfile,
        )
      )

  private def rendered = render(corpus ++ communities).pairs

  private def loaded =
    val store = new CosineStore
    store.load(rendered, embedder)
    store

  test("define is deterministic and self-deriving: same docs, same queries, in any order") {
    val a = RetrievalSmoke.define(rendered)
    assert(a.nonEmpty)
    assertEquals(RetrievalSmoke.define(rendered.reverse), a)
  }

  test("each query is built only from tokens that occur in exactly one document of its type") {
    val docs = rendered
    RetrievalSmoke.define(docs, minDistinctive = 3).foreach { q =>
      val ofType = docs.filter(_._1.documentType == q.documentType)
      val owner  = ofType.find(_._1.documentId == q.expectedVectorKey).get
      LocalHashEmbedder.tokens(q.queryText).foreach { tok =>
        assertEquals(ofType.count(d => LocalHashEmbedder.tokens(d._2).contains(tok)), 1, tok)
        assert(LocalHashEmbedder.tokens(owner._2).contains(tok))
      }
    }
  }

  test("a query records the expected key, entity, index and a why that names the derivation") {
    val q = RetrievalSmoke.define(rendered, minDistinctive = 3).head
    assert(q.expectedVectorKey.contains(q.expectedEntityId), "the key embeds the entity ID")
    assertEquals(q.name, s"${q.documentType}/${q.expectedVectorKey}")
    assert(q.why.contains(q.expectedVectorKey) && q.why.contains("no other"), q.why)
  }

  test("every defined query ranks its expected document top-1 over the fixed corpus") {
    val store   = loaded
    val queries = RetrievalSmoke.define(rendered, minDistinctive = 3)
    assert(queries.size >= 3)
    val rep = RetrievalSmoke.run(store, embedder, queries)
    assertEquals(rep.failedCount, 0, rep.outcomes.filterNot(_.passed).map(_.detail).toString)
    assertEquals(rep.passedCount, queries.size)
    assert(rep.allPassed)
    rep.outcomes.foreach { o =>
      assertEquals(o.expectedRank, Some(1))
      assertEquals(o.topKeys.head, o.query.expectedVectorKey)
      assert(o.query.why.nonEmpty)
    }
  }

  test("queries go to the index of their document type") {
    val qs = RetrievalSmoke.define(rendered, minDistinctive = 3)
    assert(qs.exists(_.indexName == VectorIndexes.ResearcherProfile))
    assert(qs.exists(_.indexName == VectorIndexes.CollaborationCommunity))
    qs.foreach(q =>
      assertEquals(Some(q.indexName), EmbeddingDocumentTypes.indexFor(q.documentType))
    )
  }

  test("perType bounds the number of queries per document type") {
    val qs = RetrievalSmoke.define(rendered, perType = 1, minDistinctive = 3)
    EmbeddingDocumentTypes.all.foreach(t => assert(qs.count(_.documentType == t) <= 1, t))
  }

  test("a document with fewer than minDistinctive distinctive tokens is not used") {
    val twins = render(
      List(
        summary("alpha beta gamma delta epsilon", researcher("Ada")),
        summary("alpha beta gamma delta zeta", researcher("Alan")),
      )
    ).pairs
    // each document has just one distinctive token
    assertEquals(RetrievalSmoke.define(twins, minDistinctive = 5), Nil)
    assert(RetrievalSmoke.define(twins, minDistinctive = 1).nonEmpty)
  }

  test("a store that returns another key gives a failing outcome with the observed rank") {
    val queries = RetrievalSmoke.define(rendered, minDistinctive = 3).take(1)
    val wrong   = ConstStore(
      List(
        VectorHit("someone:else", 0.9, Map("entityId" -> "researcher:other")),
        VectorHit(queries.head.expectedVectorKey, 0.5, Map.empty),
      )
    )
    val rep = RetrievalSmoke.run(wrong, embedder, queries)
    assertEquals(rep.failedCount, 1)
    assert(!rep.allPassed)
    assertEquals(rep.outcomes.head.expectedRank, Some(2))
    assertEquals(rep.outcomes.head.topKeys.head, "someone:else")
  }

  test("the expected key with another entity in its metadata still fails") {
    val q   = RetrievalSmoke.define(rendered, minDistinctive = 3).head
    val hit =
      VectorHit(q.expectedVectorKey, 1.0, Map("entityId" -> "researcher:not-the-expected-one"))
    val rep = RetrievalSmoke.run(ConstStore(List(hit)), embedder, List(q))
    assertEquals(rep.failedCount, 1)
  }

  test("an expected document that is absent from the top-k has no rank") {
    val q   = RetrievalSmoke.define(rendered, minDistinctive = 3).head
    val rep =
      RetrievalSmoke.run(ConstStore(List(VectorHit("x", 1.0, Map.empty))), embedder, List(q))
    assertEquals(rep.outcomes.head.expectedRank, None)
    assert(rep.outcomes.head.detail.contains("absent"))
  }

  test("a failing store or embedder gives failed outcomes with fixed details, not exceptions") {
    val q = RetrievalSmoke.define(rendered, minDistinctive = 3).head
    assertEquals(
      RetrievalSmoke.run(FailingStore, embedder, List(q)).outcomes.head.detail,
      "store query failed",
    )
    val broken = q.copy(queryText = "!!!")
    assert(
      RetrievalSmoke
        .run(loaded, embedder, List(broken))
        .outcomes
        .head
        .detail
        .startsWith("embed failed")
    )
  }

  test("an empty query list is not a pass") {
    assert(!RetrievalSmoke.run(loaded, embedder, Nil).allPassed)
  }

  test(
    "a vector hit carries the S3 text pointer of the document it came from (rag side of resolution)"
  ) {
    val store = loaded
    val docs  = render(corpus).documents
    val d     = docs.head
    val hit   = store
      .query(
        VectorIndexes.ResearcherProfile,
        VectorQuery(embedder.embed(List(d.text)).toOption.get.head, 1),
      )
      .toOption
      .get
      .head
    assertEquals(hit.vectorKey, d.document.documentId)
    assertEquals(hit.metadata("textS3Uri"), d.document.textS3Uri)
    assertEquals(d.document.textS3Uri, s"s3://$bucket/rag/text/${Hashing.textHash(d.text)}.txt")
  }

  // ---- semantic context -----------------------------------------------------------------------------------

  private def hit(
      key: String = "researcher_profile:researcher:x:2000-2004:0",
      md: Map[String, String] = Map.empty,
  ) =
    VectorHit(
      key,
      0.75,
      Map(
        "documentType" -> "researcher_profile",
        "entityId"     -> "researcher:x",
        "window"       -> "2000-2004",
        "textS3Uri"    -> "s3://bucket-name/rag/text/abc.txt",
      ) ++ md,
    )

  test(
    "SemanticContext: a hit becomes a valid item with the pointer, window and score; order is kept"
  ) {
    val hits = List(hit("k1"), hit("k2"), hit("k3"))
    val r    = SemanticContext.build(hits, k => Some(s"excerpt for $k"))
    assertEquals(r.skipped, Nil)
    assertEquals(r.items.map(_.vectorKey), List("k1", "k2", "k3"))
    r.items.foreach { i =>
      assert(summon[Contract[SemanticContextItem]].validate(i).isRight)
      assertEquals(i.window, GraphWindow(2000, 2004))
      assertEquals(i.textS3Uri, "s3://bucket-name/rag/text/abc.txt")
      assertEquals(i.score, 0.75)
      assertEquals(i.documentType, "researcher_profile")
    }
  }

  test(
    "SemanticContext: the excerpt is at most 300 characters, cut at whitespace, whitespace collapsed, a prefix"
  ) {
    val long = (1 to 200).map(i => s"word$i").mkString("  \n ")
    val ex   = SemanticContext.boundExcerpt(long)
    assert(ex.length <= 300, ex.length.toString)
    val collapsed = long.replaceAll("\\s+", " ")
    assert(collapsed.startsWith(ex), "the excerpt must be a prefix of the collapsed text")
    assert(collapsed.charAt(ex.length) == ' ', "the cut must be at a whitespace boundary")
    assert(!ex.contains("  ") && !ex.contains("\n"))
    val item = SemanticContext.item(hit(), Some(long)).toOption.get
    assertEquals(item.excerpt, ex)
  }

  test("SemanticContext: a short excerpt is only trimmed; one token longer than 300 is hard-cut") {
    assertEquals(SemanticContext.boundExcerpt("  hello   world \n"), "hello world")
    assertEquals(SemanticContext.boundExcerpt("x" * 500).length, 300)
    assertEquals(SemanticContext.boundExcerpt("a" * 300), "a" * 300)
  }

  // ---- long entity IDs (Phase 08 repair 01, M1) -------------------------------------------------------
  // Storage keeps an entity ID longer than 128 characters only as entityIdHash (full SHA-256). The
  // full ID, type, window and ordinal are recovered from the vector key and cross-checked.

  private val pathEntity: String =
    s"path:1000-9999:${researcher("Ada Lovelace")}:${researcher("Alan Turing")}"

  private val longEntity: String =
    "path:1000-9999:researcher:sc1:" + ("a" * 64) + ":researcher:sc1:" + ("b" * 64)

  private val pathWindow = GraphWindow(2000, 2004)

  private def pathKey(
      entity: String = longEntity,
      tpe: String = EmbeddingDocumentTypes.PathSummary,
      w: GraphWindow = pathWindow,
      ordinal: Int = 0,
  ): String = EmbeddingDocument.idFor(tpe, entity, w, ordinal)

  /** The metadata the storage worker writes for a long entity ID (metadataFor), overridable. */
  private def hashedMetadata(
      entity: String = longEntity,
      tpe: String = EmbeddingDocumentTypes.PathSummary,
      w: GraphWindow = pathWindow,
      ordinal: Int = 0,
  ): Map[String, String] = Map(
    "documentType" -> tpe,
    "entityIdHash" -> Hashing.textHash(entity),
    "window"       -> GraphWindow.label(w),
    "chunkOrdinal" -> ordinal.toString,
    "chunkCount"   -> "1",
    "textS3Uri"    -> "s3://bucket-name/rag/text/abc.txt",
  )

  private def longHit(key: String = pathKey(), md: Map[String, String] = hashedMetadata()) =
    VectorHit(key, 0.5, md)

  private def skipOf(h: VectorHit): Option[String] =
    SemanticContext.build(List(h), _ => Some("text")).skipped.headOption.map(_.reason)

  test("the fixture entity IDs really are longer than the 128-character metadata limit") {
    assert(longEntity.length > VectorRecord.MaxMetadataValueLength, longEntity.length.toString)
    assert(longEntity.length > 128 && pathEntity.length > 100, pathEntity.length.toString)
    assert(pathKey().length <= VectorRecord.MaxVectorKeyLength)
  }

  test(
    "SemanticContext: a >128-char entity ID stored only as entityIdHash yields an item with the full ID"
  ) {
    val r = SemanticContext.build(List(longHit()), _ => Some("A path summary text."))
    assertEquals(r.skipped, Nil)
    assertEquals(r.items.size, 1)
    val i = r.items.head
    assertEquals(i.entityId, longEntity)
    assertEquals(i.vectorKey, pathKey())
    assertEquals(i.documentType, EmbeddingDocumentTypes.PathSummary)
    assertEquals(i.window, pathWindow)
    assertEquals(i.textS3Uri, "s3://bucket-name/rag/text/abc.txt")
    assertEquals(i.excerpt, "A path summary text.")
    assert(summon[Contract[SemanticContextItem]].validate(i).isRight)
  }

  test(
    "SemanticContext: the recovered entity ID matches entityIdOf and a chunked document (ordinal 3)"
  ) {
    val h = longHit(pathKey(ordinal = 3), hashedMetadata(ordinal = 3))
    assertEquals(SemanticContext.entityIdOf(h), Right(longEntity))
    assertEquals(
      SemanticContext.build(List(h), _ => Some("t")).items.map(_.entityId),
      List(longEntity),
    )
  }

  test("SemanticContext: every document type recovers its long entity ID from the key") {
    EmbeddingDocumentTypes.all.foreach { t =>
      val h = longHit(pathKey(tpe = t), hashedMetadata(tpe = t))
      assertEquals(SemanticContext.entityIdOf(h), Right(longEntity), t)
    }
  }

  test(
    "SemanticContext: a short entity ID in metadata is used as is, whatever the key looks like"
  ) {
    val h = VectorHit("opaque-key", 0.5, hit().metadata)
    assertEquals(SemanticContext.entityIdOf(h), Right("researcher:x"))
  }

  test("SemanticContext: neither entityId nor entityIdHash is entity-id-not-in-metadata") {
    val md = hashedMetadata() - "entityIdHash"
    assertEquals(skipOf(longHit(md = md)), Some(SemanticContext.SkipNoEntity))
  }

  test(
    "SemanticContext: an entityIdHash that is not the SHA-256 of the key's entity is a mismatch"
  ) {
    val wrong = hashedMetadata() + ("entityIdHash" -> Hashing.textHash(longEntity + "x"))
    assertEquals(skipOf(longHit(md = wrong)), Some(SemanticContext.SkipKeyMismatch))
    val trunc = hashedMetadata() + ("entityIdHash" -> Hashing.textHash(longEntity).take(32))
    assertEquals(skipOf(longHit(md = trunc)), Some(SemanticContext.SkipKeyMismatch))
    val upper = hashedMetadata() + ("entityIdHash" -> Hashing.textHash(longEntity).toUpperCase)
    assertEquals(skipOf(longHit(md = upper)), Some(SemanticContext.SkipKeyMismatch))
  }

  test(
    "SemanticContext: a document type in the metadata that differs from the key's is a mismatch"
  ) {
    val md = hashedMetadata() + ("documentType" -> EmbeddingDocumentTypes.TopicSummary)
    assertEquals(skipOf(longHit(md = md)), Some(SemanticContext.SkipKeyMismatch))
  }

  test("SemanticContext: a window in the metadata that differs from the key's is a mismatch") {
    val md = hashedMetadata() + ("window" -> "2005-2009")
    assertEquals(skipOf(longHit(md = md)), Some(SemanticContext.SkipKeyMismatch))
  }

  test(
    "SemanticContext: a chunk ordinal that differs from the key's, or is missing, is a mismatch"
  ) {
    val other = hashedMetadata() + ("chunkOrdinal" -> "1")
    assertEquals(skipOf(longHit(md = other)), Some(SemanticContext.SkipKeyMismatch))
    val missing = hashedMetadata() - "chunkOrdinal"
    assertEquals(skipOf(longHit(md = missing)), Some(SemanticContext.SkipKeyMismatch))
  }

  test(
    "SemanticContext: a key that does not split into type, entity, window and ordinal is unparseable"
  ) {
    val md = hashedMetadata()
    List(
      "no-colons-at-all",
      ":" + longEntity + ":2000-2004:0",
      "path_summary:2000-2004:0",
      pathKey().stripSuffix(":0") + ":",
    ).foreach(k =>
      assertEquals(skipOf(longHit(k, md)), Some(SemanticContext.SkipKeyUnparseable), k)
    )
  }

  test("SemanticContext: an unknown document type in the key is unparseable") {
    val key = "graph_fact:" + longEntity + ":2000-2004:0"
    val md  = hashedMetadata() + ("documentType" -> "graph_fact")
    assertEquals(skipOf(longHit(key, md)), Some(SemanticContext.SkipKeyUnparseable))
  }

  test("SemanticContext: a non-numeric or signed ordinal in the key is unparseable") {
    val md = hashedMetadata()
    List("x", "-1", "1.5", "").foreach { o =>
      val key = pathKey().stripSuffix(":0") + ":" + o
      assertEquals(skipOf(longHit(key, md)), Some(SemanticContext.SkipKeyUnparseable), o)
    }
  }

  test("SemanticContext: a bad window label in the key is unparseable") {
    val key = "path_summary:" + longEntity + ":20-24:0"
    assertEquals(skipOf(longHit(key)), Some(SemanticContext.SkipKeyUnparseable))
  }

  test("SemanticContext: a key built for ANOTHER entity with this entity's hash is a mismatch") {
    val other = longEntity.replace("a" * 64, "c" * 64)
    assertEquals(
      skipOf(longHit(pathKey(other), hashedMetadata(longEntity))),
      Some(SemanticContext.SkipKeyMismatch),
    )
  }

  test("SemanticContext: long-entity hits and bad hits are separated; order is kept") {
    val bad  = longHit(md = hashedMetadata() + ("window" -> "2005-2009"))
    val good = longHit(pathKey(ordinal = 2), hashedMetadata(ordinal = 2))
    val r    = SemanticContext.build(List(longHit(), bad, good), _ => Some("t"))
    assertEquals(r.items.map(_.vectorKey), List(pathKey(), pathKey(ordinal = 2)))
    assertEquals(r.skipped, List(SkippedHit(bad.vectorKey, SemanticContext.SkipKeyMismatch)))
  }

  // ---- smoke with long entity IDs ------------------------------------------------------------------------

  private def longQuery: SmokeQuery = SmokeQuery(
    "path_summary/long",
    EmbeddingDocumentTypes.PathSummary,
    VectorIndexes.CollaborationPath,
    pathKey(),
    longEntity,
    "alpha beta gamma",
    "test",
  )

  test(
    "smoke passes when the rank-1 hit stores only entityIdHash and the full entity is recovered"
  ) {
    val rep = RetrievalSmoke.run(ConstStore(List(longHit())), embedder, List(longQuery))
    assertEquals(rep.passedCount, 1, rep.outcomes.map(_.detail).toString)
    assertEquals(rep.outcomes.head.expectedRank, Some(1))
  }

  test("smoke FAILS when the expected key is rank 1 but the entity cannot be confirmed") {
    val cases = List(
      "hash mismatch"      -> (hashedMetadata() + ("entityIdHash" -> Hashing.textHash("another"))),
      "no entity metadata" -> (hashedMetadata() - "entityIdHash"),
      "window mismatch"    -> (hashedMetadata() + ("window"       -> "2005-2009")),
      "ordinal missing"    -> (hashedMetadata() - "chunkOrdinal"),
    )
    cases.foreach { (name, md) =>
      val rep = RetrievalSmoke.run(ConstStore(List(longHit(md = md))), embedder, List(longQuery))
      assertEquals(rep.failedCount, 1, name)
      assert(!rep.allPassed, name)
      assertEquals(rep.outcomes.head.expectedRank, Some(1), name)
      assert(rep.outcomes.head.detail.contains("could not be confirmed"), rep.outcomes.head.detail)
    }
  }

  test("smoke FAILS when the expected key is rank 1 with no metadata at all (no vacuous pass)") {
    val rep = RetrievalSmoke.run(
      ConstStore(List(VectorHit(pathKey(), 1.0, Map.empty))),
      embedder,
      List(longQuery),
    )
    assertEquals(rep.failedCount, 1)
    assert(rep.outcomes.head.detail.contains("could not be confirmed"))
  }

  test("smoke FAILS when the hash belongs to a different entity than the one expected") {
    val q   = longQuery.copy(expectedEntityId = longEntity.replace("a" * 64, "c" * 64))
    val rep = RetrievalSmoke.run(ConstStore(List(longHit())), embedder, List(q))
    assertEquals(rep.failedCount, 1)
  }

  test("SemanticContext: a missing excerpt, missing metadata and a bad window label are skipped") {
    val noExcerpt = SemanticContext.build(List(hit("a")), _ => None)
    assertEquals(noExcerpt.skipped, List(SkippedHit("a", SemanticContext.SkipNoExcerpt)))
    val blankExcerpt = SemanticContext.build(List(hit("b")), _ => Some("   "))
    assertEquals(blankExcerpt.skipped, List(SkippedHit("b", SemanticContext.SkipNoExcerpt)))
    val noMeta = SemanticContext.build(List(VectorHit("c", 0.1, Map.empty)), _ => Some("t"))
    assertEquals(noMeta.skipped, List(SkippedHit("c", SemanticContext.SkipNoMetadata)))
    val badWindow = SemanticContext.build(List(hit("d", Map("window" -> "20-24"))), _ => Some("t"))
    assertEquals(badWindow.skipped, List(SkippedHit("d", SemanticContext.SkipNoMetadata)))
  }

  test(
    "SemanticContext: an item that fails its contract (unknown document type) is skipped as invalid-item"
  ) {
    val r =
      SemanticContext.build(List(hit("e", Map("documentType" -> "graph_fact"))), _ => Some("t"))
    assertEquals(r.skipped, List(SkippedHit("e", SemanticContext.SkipInvalid)))
  }

  test("SemanticContext: good and bad hits are separated and the good ones keep their order") {
    val hits = List(hit("g1"), VectorHit("bad", 0.1, Map.empty), hit("g2"))
    val r    = SemanticContext.build(hits, _ => Some("t"))
    assertEquals(r.items.map(_.vectorKey), List("g1", "g2"))
    assertEquals(r.skipped.map(_.vectorKey), List("bad"))
  }

  test(
    "SemanticContext: the notice says counts, paths, components and bridges come from graph or metric stores"
  ) {
    val n = SemanticContext.GraphFactNotice
    List("Counts", "paths", "components", "bridges", "graph or metric stores").foreach(w =>
      assert(n.contains(w), w)
    )
    assertEquals(SemanticContext.parseWindow("1999-2003"), Some(GraphWindow(1999, 2003)))
    assertEquals(SemanticContext.parseWindow("1999"), None)
  }
