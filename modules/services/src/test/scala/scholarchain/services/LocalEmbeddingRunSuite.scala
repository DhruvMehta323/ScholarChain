package scholarchain.services

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import scholarchain.core.*
import scholarchain.rag.*
import scholarchain.storage.*

/** Fast checks of the local embedding run composition on a tiny in-memory fixture (no Spark, no
  * Parquet): wiring across core, rag and storage, determinism, the second-pass no-op, the pointer
  * from a vector hit to its text, and the Parquet row conversion on in-memory tables.
  */
class LocalEmbeddingRunSuite extends munit.FunSuite:

  private def ok[A](e: Either[ValidationError, A]): A =
    e.fold(err => throw AssertionError(err.message), identity)

  private val inputRun: String =
    ok(
      Ids.runId(
        "spark-graph",
        "0.1.0",
        1,
        Map("k" -> "v"),
        List(InputRef("t", "v", Some("a" * 64))),
      )
    )

  private val prov = Provenance(
    1,
    SourceType.ScholarChain,
    List(InputRef("scholarchain-graph/semantic-summaries", inputRun, Some("b" * 64))),
    "0.1.0",
    Map("table" -> "t"),
    Some(inputRun),
    None,
  )

  private def researcher(n: String) = ok(Ids.researcherIdDblpPerson(n))

  private def summary(
      text: String,
      entity: String,
      tpe: String = EmbeddingDocumentTypes.ResearcherProfile,
      w: GraphWindow = GraphWindow(2000, 2004),
  ) = SemanticSummary(1, entity, tpe, Hashing.textHash(text), text, w, inputRun, prov)

  private val vocab = List(
    "analytical engines notation bernoulli punched cards looms",
    "computability halting machines tapes enigma bombe ciphers",
    "compilers cobol flowmatic navy linkers assemblers nanoseconds",
    "semaphores shortest paths algol structured mutual exclusion",
    "abstraction substitution distributed liskov clusters modules",
  )

  private val names =
    List("Ada Lovelace", "Alan Turing", "Grace Hopper", "Edsger Dijkstra", "Barbara Liskov")

  private val summaries: List[SemanticSummary] =
    names.zip(vocab).zipWithIndex.map { case ((n, v), i) =>
      summary(s"$n published ${10 + i} papers about $v with ${i + 2} coauthors.", researcher(n))
    } ++ List(
      summary(
        "Community of 5 researchers studying zebra quagga okapi giraffe antelope.",
        "community:2000-2004:c1",
        EmbeddingDocumentTypes.CommunityProfile,
      ),
      summary(
        "Community of 4 researchers studying falcon osprey kestrel harrier buzzard.",
        "community:2000-2004:c2",
        EmbeddingDocumentTypes.CommunityProfile,
      ),
      summary(
        "Topic compilers linked to 14 works about parsers lexers grammars optimizers.",
        "topic:dblp:compilers",
        EmbeddingDocumentTypes.TopicSummary,
        GraphWindows.AllTime,
      ),
    )

  private val venues = List(Venue(1, "venue:dblp:conf/pldi", "PLDI Symposium", None, prov))
  private val topics = List(Topic(1, "topic:dblp:graphs", "Graph Theory", prov))
  private val works  = List(
    WorkRecord(
      1,
      "work:dblp:conf/pldi/A01",
      "Notation for Analytical Engines",
      2001,
      None,
      Some("venue:dblp:conf/pldi"),
      List("topic:dblp:graphs"),
      prov,
    ),
    WorkRecord(
      1,
      "work:dblp:conf/pldi/B02",
      "Computable Numbers and Decision Problems",
      2002,
      Some("10.1000/xyz123"),
      None,
      Nil,
      prov,
    ),
  )

  private def input = PipelineInput(
    inputRun,
    summaries,
    works,
    venues,
    topics,
    Map("semantic-summaries" -> summaries.size, "works" -> works.size),
    Map.empty,
  )

  private def params(dim: Int = 256) = PipelineParams("code-test-1", dim)

  private def tmp(): Path = Files.createTempDirectory("sc-local-embed")

  private def runInto(
      dir: Path,
      in: PipelineInput = input,
      p: PipelineParams = params(),
  ): PipelineResult =
    LocalEmbeddingPipeline
      .run(in, p, dir.resolve("out"))
      .fold(es => fail(es.toString), identity)

  private def lines(p: Path): List[String] =
    Files.readAllLines(p, StandardCharsets.UTF_8).asScala.toList

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val s = Files.walk(root)
      try s.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists(_))
      finally s.close()

  private def withTmp[A](f: Path => A): A =
    val d = tmp()
    try f(d)
    finally deleteTree(d)

  // ---- end to end ---------------------------------------------------------------------------------

  test(
    "end to end: pass 1 writes a vector per document; pass 2 skips every one with no embed call"
  ) {
    withTmp { d =>
      val r = runInto(d)
      assertEquals(r.invariantFailures, Nil)
      assertEquals(r.documentCount, summaries.size + works.size)
      assertEquals(r.pass1.vectorsWritten, r.documentCount)
      assertEquals(r.pass1.documentsDeadLettered, 0)
      assertEquals(r.pass1.skippedUnchanged, 0)
      assertEquals(r.pass2.skippedUnchanged, r.documentCount)
      assertEquals(r.pass2.vectorsWritten, 0)
      assertEquals(r.pass2.embedCalls, 0)
      assertEquals(r.pass2.documentsEmbedded, 0)
      assertEquals(r.stoppedBy1, "empty")
      assertEquals(r.stoppedBy2, "empty")
      assertEquals(r.deadLettered, Nil)
      assertEquals(r.producer2.textsWritten, 0)
      assertEquals(r.producer2.textsUnchanged, r.documentCount)
    }
  }

  test("documents are counted per index and type, and every one of the five indexes is used") {
    withTmp { d =>
      val r = runInto(d)
      assertEquals(
        r.documentsByIndex.keySet,
        VectorIndexes.all.toSet.filter(i => i != VectorIndexes.CollaborationPath),
      )
      assertEquals(r.documentsByType(EmbeddingDocumentTypes.ResearcherProfile), 5)
      assertEquals(r.documentsByType(EmbeddingDocumentTypes.CommunityProfile), 2)
      assertEquals(r.documentsByType(EmbeddingDocumentTypes.TopicSummary), 1)
      assertEquals(r.documentsByType(EmbeddingDocumentTypes.PaperAbstract), 2)
      assertEquals(r.documentsByIndex.values.sum, r.documentCount)
    }
  }

  test("the output directory holds the documented files and nothing is written outside it") {
    withTmp { d =>
      runInto(d)
      val out = d.resolve("out")
      List("documents.jsonl", "vectors.jsonl", "ledger.jsonl", "counts.json", "smoke.json").foreach(
        f => assert(Files.isRegularFile(out.resolve(f)), s"missing $f")
      )
      assert(Files.isDirectory(out.resolve("objects")))
      assert(!Files.exists(out.resolve("dlq.jsonl")), "no dead letters, so no dlq.jsonl")
      val top = Files.list(d)
      try assertEquals(top.iterator.asScala.map(_.getFileName.toString).toList, List("out"))
      finally top.close()
    }
  }

  test("an existing output directory is refused and left untouched") {
    withTmp { d =>
      val out = d.resolve("out")
      Files.createDirectories(out)
      Files.write(out.resolve("keep.txt"), "x".getBytes)
      val r = LocalEmbeddingPipeline.run(input, params(), out)
      assert(r.isLeft)
      assertEquals(lines(out.resolve("keep.txt")), List("x"))
    }
  }

  test("bad parameters are refused before anything is written") {
    withTmp { d =>
      assert(LocalEmbeddingPipeline.run(input, params(0), d.resolve("a")).isLeft)
      assert(LocalEmbeddingPipeline.run(input, params(5000), d.resolve("b")).isLeft)
      assert(
        LocalEmbeddingPipeline.run(input, PipelineParams("has space", 8), d.resolve("c")).isLeft
      )
      assert(
        LocalEmbeddingPipeline
          .run(input.copy(inputRunId = "run:short"), params(), d.resolve("e"))
          .isLeft
      )
      assert(
        !Files.exists(d.resolve("a")) && !Files.exists(d.resolve("b")) && !Files.exists(
          d.resolve("c")
        )
      )
    }
  }

  // ---- determinism ----------------------------------------------------------------------------------

  test("two runs into two directories are byte-identical, including the text objects") {
    withTmp { d =>
      val a = d.resolve("A")
      val b = d.resolve("B")
      Files.createDirectories(a)
      Files.createDirectories(b)
      runInto(a)
      runInto(b)
      val sa = LocalEmbeddingPipeline.snapshot(a.resolve("out"))
      val sb = LocalEmbeddingPipeline.snapshot(b.resolve("out"))
      assert(sa.size > 10, "the snapshot must include the object files")
      assertEquals(sa.map(_._1), sb.map(_._1))
      sa.zip(sb).foreach { case ((n, x), (_, y)) => assert(x == y, s"$n differs between runs") }
    }
  }

  test("the input order of the rows does not change the output") {
    withTmp { d =>
      val a = d.resolve("A")
      val b = d.resolve("B")
      Files.createDirectories(a)
      Files.createDirectories(b)
      runInto(a)
      runInto(
        b,
        input.copy(summaries = summaries.reverse, works = works.reverse, venues = venues.reverse),
      )
      val sa = LocalEmbeddingPipeline.snapshot(a.resolve("out"))
      val sb = LocalEmbeddingPipeline.snapshot(b.resolve("out"))
      sa.zip(sb).foreach { case ((n, x), (_, y)) => assert(x == y, s"$n differs") }
    }
  }

  test("no output holds a host path, a temporary directory name or a wall-clock year") {
    withTmp { d =>
      runInto(d)
      val out   = d.resolve("out")
      val files =
        List("counts.json", "smoke.json", "ledger.jsonl", "documents.jsonl", "vectors.jsonl")
      files.foreach { f =>
        val text = new String(Files.readAllBytes(out.resolve(f)), StandardCharsets.UTF_8)
        assert(!text.contains(d.toString), s"$f holds the temporary path")
        assert(!text.contains(System.getProperty("user.home")), s"$f holds the home path")
        assert(!text.contains("sc-local-embed"), s"$f holds the temp directory name")
      }
      assert(
        lines(out.resolve("ledger.jsonl"))
          .forall(_.contains(LocalEmbeddingPipeline.FixedLedgerInstant))
      )
    }
  }

  test("run IDs are deterministic, run:<64 hex>, and differ per pass, input and code version") {
    val a = LocalEmbeddingPipeline.runIdFor(inputRun, "v1", 1)
    assertEquals(a, LocalEmbeddingPipeline.runIdFor(inputRun, "v1", 1))
    assert(Ids.checkRunId(a).isRight)
    assertNotEquals(a, LocalEmbeddingPipeline.runIdFor(inputRun, "v1", 2))
    assertNotEquals(a, LocalEmbeddingPipeline.runIdFor(inputRun, "v2", 1))
    assertNotEquals(a, LocalEmbeddingPipeline.runIdFor("run:" + "c" * 64, "v1", 1))
  }

  // ---- outputs ----------------------------------------------------------------------------------------

  test("documents.jsonl holds every document once, sorted by ID, each valid under its contract") {
    withTmp { d =>
      val r  = runInto(d)
      val ds = lines(d.resolve("out/documents.jsonl")).map(l =>
        Wire.read[EmbeddingDocument](l).fold(e => fail(e.message), identity)
      )
      assertEquals(ds.size, r.documentCount)
      assertEquals(ds.map(_.documentId), ds.map(_.documentId).sorted)
      assertEquals(ds.map(_.documentId).distinct.size, ds.size)
      assert(ds.forall(x => summon[Contract[EmbeddingDocument]].validate(x).isRight))
      assert(
        ds.filter(_.documentType != EmbeddingDocumentTypes.PaperAbstract)
          .forall(_.sourceRunId == inputRun)
      )
    }
  }

  test(
    "vectors.jsonl is sorted per index, carries the model stamp and a sparse lossless embedding"
  ) {
    withTmp { d =>
      val r  = runInto(d)
      val vs = lines(d.resolve("out/vectors.jsonl")).map(ujson.read(_))
      assertEquals(vs.size, r.documentCount)
      vs.foreach { v =>
        val rec = v("record")
        assertEquals(rec("embeddingModelId").str, "local-hash-bow")
        assertEquals(rec("embeddingModelVersion").str, "v1")
        assertEquals(rec("dimension").num.toInt, 256)
        assertEquals(v("dimension").num.toInt, 256)
        assert(v("nonZero").arr.nonEmpty)
        assertEquals(v("embeddingSha256").str.length, 64)
        assertEquals(rec("runId").str, r.runIds.head, "the stored vector keeps the first pass run")
      }
      val order = VectorIndexes.all.zipWithIndex.toMap
      val keys  = vs.map(v => order(v("index").str) -> v("record")("vectorKey").str)
      assertEquals(keys, keys.sorted)
    }
  }

  test("the stored vector hits resolve to their text through the S3 pointer in the object store") {
    withTmp { d =>
      val r        = runInto(d)
      val embedder = EmbedderFactory.localTest(256)
      val resolver = VectorTextResolver(r.objects)
      val docs     = lines(d.resolve("out/documents.jsonl")).map(l =>
        Wire.read[EmbeddingDocument](l).toOption.get
      )
      docs.foreach { doc =>
        val index = EmbeddingDocumentTypes.indexFor(doc.documentType).get
        val entry = r.vectors
          .get(index, doc.documentId)
          .toOption
          .flatten
          .getOrElse(fail(s"no vector for ${doc.documentId}"))
        val text = resolver.resolve(entry).fold(e => fail(e.toString), identity)
        assertEquals(text.textHash, doc.textHash)
        assertEquals(Hashing.textHash(text.text), doc.textHash)
        // and a query with that text returns the same document as top-1, whose pointer resolves too
        val q   = embedder.embed(List(text.text)).toOption.get.head
        val hit = r.vectors.query(index, VectorQuery(q, 1)).toOption.get.head
        assertEquals(hit.vectorKey, doc.documentId)
        assertEquals(resolver.resolve(hit).map(_.text), Right(text.text))
      }
    }
  }

  // ---- long entity IDs end to end (Phase 08 repair 01, M1) -----------------------------------------------

  private val longPathEntity: String =
    "path:1000-9999:researcher:sc1:" + ("a" * 64) + ":researcher:sc1:" + ("b" * 64)

  private val pathText =
    "A collaboration path of 3 hops links two researchers through shared venues pldi sigmod vldb."

  private def inputWithPath: PipelineInput =
    input.copy(summaries =
      summaries :+ summary(
        pathText,
        longPathEntity,
        EmbeddingDocumentTypes.PathSummary,
        GraphWindow(2000, 2004),
      )
    )

  test(
    "a path_summary with a >128-char entity ID flows worker, store and SemanticContext to an item"
  ) {
    withTmp { d =>
      assert(longPathEntity.length > VectorRecord.MaxMetadataValueLength)
      val r = runInto(d, inputWithPath)
      assertEquals(r.invariantFailures, Nil)
      assertEquals(r.pass1.documentsDeadLettered, 0)
      assertEquals(r.documentsByType(EmbeddingDocumentTypes.PathSummary), 1)
      val key = EmbeddingDocument.idFor(
        EmbeddingDocumentTypes.PathSummary,
        longPathEntity,
        GraphWindow(2000, 2004),
        0,
      )
      val entry = r.vectors
        .get(VectorIndexes.CollaborationPath, key)
        .toOption
        .flatten
        .getOrElse(fail(s"no vector stored for $key"))
      // the worker keeps only the hash of the long ID in the metadata
      assertEquals(entry.record.metadata.get("entityId"), None)
      assertEquals(
        entry.record.metadata.get("entityIdHash"),
        Some(Hashing.textHash(longPathEntity)),
      )

      val embedder = EmbedderFactory.localTest(256)
      val resolver = VectorTextResolver(r.objects)
      val q        = embedder.embed(List(pathText)).toOption.get.head
      val hits     = r.vectors
        .query(VectorIndexes.CollaborationPath, VectorQuery(q, 3))
        .fold(e => fail(e.toString), identity)
      assertEquals(hits.headOption.map(_.vectorKey), Some(key))
      val ctx = SemanticContext.build(
        hits,
        k => hits.find(_.vectorKey == k).flatMap(h => resolver.resolve(h).toOption.map(_.text)),
      )
      assertEquals(ctx.skipped, Nil)
      val item = ctx.items.headOption.getOrElse(fail("no semantic context item"))
      assertEquals(item.entityId, longPathEntity)
      assertEquals(item.vectorKey, key)
      assertEquals(item.documentType, EmbeddingDocumentTypes.PathSummary)
      assertEquals(item.window, GraphWindow(2000, 2004))
      assertEquals(item.excerpt, pathText)
      assert(summon[Contract[SemanticContextItem]].validate(item).isRight)
    }
  }

  test(
    "every stored vector of the run, long entity IDs included, becomes a semantic context item"
  ) {
    withTmp { d =>
      val r        = runInto(d, inputWithPath)
      val embedder = EmbedderFactory.localTest(256)
      val resolver = VectorTextResolver(r.objects)
      val docs     = lines(d.resolve("out/documents.jsonl")).map(l =>
        Wire.read[EmbeddingDocument](l).toOption.get
      )
      docs.foreach { doc =>
        val index = EmbeddingDocumentTypes.indexFor(doc.documentType).get
        val entry = r.vectors.get(index, doc.documentId).toOption.flatten.get
        val hit   = r.vectors
          .query(index, VectorQuery(entry.embedding, 1))
          .fold(e => fail(e.toString), _.headOption.getOrElse(fail("no hit")))
        assertEquals(hit.vectorKey, doc.documentId)
        val text = resolver.resolve(entry).fold(e => fail(e.toString), _.text)
        val item = SemanticContext.item(hit, Some(text))
        assertEquals(item.map(_.entityId), Right(doc.entityId), doc.documentId)
      }
      assert(embedder.dimension == 256)
    }
  }

  test("the smoke of a run with a long path entity confirms its entities and passes") {
    withTmp { d =>
      val r = runInto(d, inputWithPath)
      assert(r.smoke.allPassed, r.smoke.outcomes.filterNot(_.passed).map(_.detail).toString)
    }
  }

  test("the run's claims are made in the harness claims table: a second pass never reclaims") {
    withTmp { d =>
      val r = runInto(d, inputWithPath)
      assertEquals(r.pass2.vectorsWritten, 0)
      assertEquals(r.pass2.documentsDeadLettered, 0)
      assertEquals(r.deadLettered, Nil)
      assertEquals(LocalEmbeddingPipeline.ClaimsTable, EmbeddingSettings.DefaultClaimsTableName)
    }
  }

  test("texts are stored under the content-addressed pointer of their hash") {
    withTmp { d =>
      val r   = runInto(d)
      val s   = summaries.head
      val loc =
        ObjectLocation(LocalEmbeddingPipeline.Bucket, s"rag/text/${Hashing.textHash(s.text)}.txt")
      assertEquals(
        r.objects.get(loc).map(o => new String(o.bytes, StandardCharsets.UTF_8)),
        Right(s.text),
      )
    }
  }

  test("ledger.jsonl records both enqueue passes and every job delivery, with the fixed clock") {
    withTmp { d =>
      val r  = runInto(d)
      val es = lines(d.resolve("out/ledger.jsonl")).map(l =>
        Wire.read[RunLedgerEntry](l).fold(e => fail(e.message), identity)
      )
      assertEquals(es.count(_.stage == EmbeddingLedger.ProducerStage), 2)
      val jobs = es.filter(_.stage == EmbeddingLedger.JobStage)
      assertEquals(jobs.size, r.pass1.jobsProcessed + r.pass2.jobsProcessed)
      assert(es.forall(e => e.status == RunStatus.Succeeded))
      assert(es.forall(_.startedAt == LocalEmbeddingPipeline.FixedLedgerInstant))
      assert(es.forall(_.codeVersion == "code-test-1"))
      assert(es.forall(e => summon[Contract[RunLedgerEntry]].validate(e).isRight))
    }
  }

  test("counts.json reports documents per index and type, both passes, dead letters and smoke") {
    withTmp { d =>
      val r = runInto(d)
      val c = ujson.read(Files.readString(d.resolve("out/counts.json")))
      assertEquals(c("documents")("total").num.toInt, r.documentCount)
      assertEquals(c("documents")("byType")("researcher_profile").num.toInt, 5)
      assertEquals(c("documents")("byIndex")(VectorIndexes.ResearcherProfile).num.toInt, 5)
      assertEquals(c("pass1")("worker")("vectorsWritten").num.toInt, r.documentCount)
      assertEquals(c("pass2")("worker")("skippedUnchanged").num.toInt, r.documentCount)
      assertEquals(c("pass2")("worker")("embedCalls").num.toInt, 0)
      assertEquals(c("deadLettered").num.toInt, 0)
      assertEquals(c("numberCheckFailures").num.toInt, 0)
      assertEquals(c("embedder")("dimension").num.toInt, 256)
      assertEquals(c("smoke")("failed").num.toInt, 0)
      assertEquals(c("invariantFailures").arr.size, 0)
      assertEquals(c("embeddingRunIds").arr.map(_.str).toList, r.runIds)
    }
  }

  test("smoke.json lists each query with its expected key, why, rank, top keys and verdict") {
    withTmp { d =>
      val r = runInto(d)
      assert(r.smoke.outcomes.nonEmpty)
      assert(r.smoke.allPassed, r.smoke.outcomes.filterNot(_.passed).map(_.detail).toString)
      val s = ujson.read(Files.readString(d.resolve("out/smoke.json")))
      assertEquals(s("queries").arr.size, r.smoke.outcomes.size)
      s("queries").arr.foreach { q =>
        assert(q("why").str.nonEmpty)
        assert(q("expectedKey").str.nonEmpty && q("expectedEntityId").str.nonEmpty)
        assertEquals(q("expectedRank").num.toInt, 1)
        assertEquals(q("topKeys").arr.head.str, q("expectedKey").str)
        assertEquals(q("passed").bool, true)
      }
    }
  }

  test("the dimension option is honoured end to end") {
    withTmp { d =>
      val r = runInto(d, p = params(64))
      assertEquals(r.invariantFailures, Nil)
      val v = ujson.read(lines(d.resolve("out/vectors.jsonl")).head)
      assertEquals(v("dimension").num.toInt, 64)
    }
  }

  test(
    "a summary with changed text under the same key between passes would be dead-lettered, never overwritten"
  ) {
    withTmp { d =>
      // pass 1 stores vectors; a second harness over the SAME vector store is not exposed, so check
      // the guarantee at the worker level through the pipeline's own stores
      val r   = runInto(d)
      val doc = lines(d.resolve("out/documents.jsonl"))
        .map(l => Wire.read[EmbeddingDocument](l).toOption.get)
        .head
      val index  = EmbeddingDocumentTypes.indexFor(doc.documentType).get
      val stored = r.vectors.get(index, doc.documentId).toOption.flatten.get
      val clash  =
        stored.copy(record = stored.record.copy(textHash = Hashing.textHash("some other text")))
      assertEquals(
        r.vectors.putBatch(index, List(clash)).map(_.head.outcome),
        Right(VectorPutOutcome.Rejected(VectorRejection.KeyCollision)),
      )
      assertEquals(
        r.vectors.get(index, doc.documentId).toOption.flatten.map(_.record.textHash),
        Some(doc.textHash),
      )
    }
  }

  test("an input that renders nothing is reported as an invariant failure, not a crash") {
    withTmp { d =>
      val r = runInto(d, input.copy(summaries = Nil, works = Nil))
      assertEquals(r.documentCount, 0)
      assert(r.invariantFailures.contains("no documents were rendered"))
    }
  }

  // ---- row conversion (Parquet rows as in-memory tables) -------------------------------------------------

  private def table(name: String, cols: List[(String, String)], rows: Vector[Vector[Cell]]) =
    TableData(name, cols.map(Column(_, _)).toVector, rows)

  private val summaryCols = List(
    "window"       -> "string",
    "windowStart"  -> "int",
    "windowEnd"    -> "int",
    "entityId"     -> "string",
    "documentType" -> "string",
    "textHash"     -> "string",
    "text"         -> "string",
    "runId"        -> "string",
  )

  private def summaryRow(
      text: String,
      hash: String,
      entity: String = researcher("Ada Lovelace"),
      ws: Int = 2000,
      we: Int = 2004,
  ) =
    Vector(
      Cell.S(s"$ws-$we"),
      Cell.I(ws),
      Cell.I(we),
      Cell.S(entity),
      Cell.S("researcher_profile"),
      Cell.S(hash),
      Cell.S(text),
      Cell.S(inputRun),
    )

  test("summary rows convert to validated core records and invalid rows are counted by reason") {
    val good    = summaryRow("Ada has 3 papers.", Hashing.textHash("Ada has 3 papers."))
    val badHash = summaryRow("SECRET ROW CONTENT 777", "0" * 64)
    val badWin  = summaryRow(
      "Window is reversed.",
      Hashing.textHash("Window is reversed."),
      ws = 2010,
      we = 2005,
    )
    val nullText = good.updated(6, Cell.Null)
    val c        = ParquetInput.summaries(
      table("semantic-summaries", summaryCols, Vector(good, badHash, badWin, nullText)),
      prov,
    )
    assertEquals(c.rows, 4)
    assertEquals(c.records.size, 1)
    assertEquals(c.records.head.window, GraphWindow(2000, 2004))
    assertEquals(c.rejects.values.sum, 3)
    assert(
      c.rejects.keys.forall(k => k.startsWith("invalid-") || k == "null-or-wrong-type"),
      c.rejects.toString,
    )
    assert(!c.rejects.toString.contains("SECRET ROW CONTENT"), "row content must never be reported")
  }

  test("a table without a required column is rejected row by row with a fixed reason") {
    val t = table("semantic-summaries", List("entityId" -> "string"), Vector(Vector(Cell.S("x"))))
    val c = ParquetInput.summaries(t, prov)
    assertEquals(c.records, Nil)
    assertEquals(c.rejects, Map("missing-column" -> 1))
  }

  test(
    "works, venues and topics rows convert; null DOI and venue become None, topic arrays are kept"
  ) {
    val wcols = List(
      "workId"   -> "string",
      "title"    -> "string",
      "year"     -> "int",
      "doi"      -> "string",
      "venueId"  -> "string",
      "topicIds" -> "array<string>",
    )
    val wt = table(
      "works",
      wcols,
      Vector(
        Vector(
          Cell.S("work:dblp:conf/x/A01"),
          Cell.S("A Title"),
          Cell.I(2001),
          Cell.Null,
          Cell.Null,
          Cell.Arr(Nil),
        ),
        Vector(
          Cell.S("work:dblp:conf/x/B02"),
          Cell.S("B Title"),
          Cell.I(2002),
          Cell.S("10.1000/abc"),
          Cell.S("venue:dblp:conf/x"),
          Cell.Arr(List(Cell.S("topic:dblp:t1"))),
        ),
        Vector(
          Cell.S("work:dblp:conf/x/C03"),
          Cell.S("   "),
          Cell.I(2003),
          Cell.Null,
          Cell.Null,
          Cell.Arr(Nil),
        ),
      ),
    )
    val w = ParquetInput.works(wt, prov)
    assertEquals(w.records.map(_.workId), List("work:dblp:conf/x/A01", "work:dblp:conf/x/B02"))
    assertEquals(w.records.map(_.doi), List(None, Some("10.1000/abc")))
    assertEquals(w.records(1).topicIds, List("topic:dblp:t1"))
    assertEquals(w.rejects.values.sum, 1)
    val v = ParquetInput.venues(
      table(
        "venues",
        List("venueId" -> "string", "name" -> "string"),
        Vector(Vector(Cell.S("venue:dblp:conf/x"), Cell.S("X Conf"))),
      ),
      prov,
    )
    assertEquals(v.records.map(_.name), List("X Conf"))
    val t = ParquetInput.topics(
      table(
        "topics",
        List("topicId" -> "string", "label" -> "string"),
        Vector(
          Vector(Cell.S("topic:dblp:t1"), Cell.S("Graphs")),
          Vector(Cell.S("bad id"), Cell.S("L")),
        ),
      ),
      prov,
    )
    assertEquals(t.records.map(_.label), List("Graphs"))
    assertEquals(t.rejects.values.sum, 1)
  }

  // ---- command line --------------------------------------------------------------------------------------

  test("CLI: required options, absolute paths, positive integers") {
    val ok = LocalEmbeddingRun.parse(
      List("--run-root", "/a/b", "--out-dir", "/c/d", "--code-version", "v1")
    )
    assertEquals(ok.map(a => (a.codeVersion, a.dimension, a.smokePerType)), Right(("v1", 1024, 5)))
    val opts = LocalEmbeddingRun.parse(
      List(
        "--run-root",
        "/a",
        "--out-dir",
        "/c",
        "--code-version",
        "v",
        "--dimension",
        "64",
        "--smoke-per-type",
        "2",
      )
    )
    assertEquals(opts.map(a => (a.dimension, a.smokePerType)), Right((64, 2)))
    val min = LocalEmbeddingRun.parse(
      List(
        "--run-root",
        "/a",
        "--out-dir",
        "/c",
        "--code-version",
        "v",
        "--smoke-min-distinctive",
        "3",
      )
    )
    assertEquals(min.map(_.smokeMinDistinctive), Right(3))
    assertEquals(ok.map(_.smokeMinDistinctive), Right(RetrievalSmoke.DefaultMinDistinctive))
    assert(LocalEmbeddingRun.parse(List("--out-dir", "/c", "--code-version", "v")).isLeft)
    assert(
      LocalEmbeddingRun
        .parse(List("--run-root", "rel", "--out-dir", "/c", "--code-version", "v"))
        .isLeft
    )
    assert(
      LocalEmbeddingRun
        .parse(List("--run-root", "/a", "--out-dir", "rel/x", "--code-version", "v"))
        .isLeft
    )
    assert(
      LocalEmbeddingRun
        .parse(List("--run-root", "/a", "--out-dir", "/c", "--code-version", "has space"))
        .isLeft
    )
    assert(
      LocalEmbeddingRun
        .parse(
          List("--run-root", "/a", "--out-dir", "/c", "--code-version", "v", "--dimension", "0")
        )
        .isLeft
    )
    assert(
      LocalEmbeddingRun
        .parse(
          List("--run-root", "/a", "--out-dir", "/c", "--code-version", "v", "--dimension", "x")
        )
        .isLeft
    )
  }

  test("the harness lives only in the services test tree (no file under services src/main)") {
    val root = StorageRoot.find.getOrElse(fail("repository root not found"))
    val main = root.resolve("modules/services/src/main")
    if Files.exists(main) then
      val s = Files.walk(main)
      try
        val scala = s.iterator.asScala
          .filter(_.toString.endsWith(".scala"))
          .map(_.getFileName.toString)
          .toList
        assert(!scala.exists(_.contains("LocalEmbedding")), scala.toString)
      finally s.close()
  }

/** Finds the repository root (the directory holding build.sbt). */
private object StorageRoot:
  def find: Option[Path] =
    Iterator
      .iterate(Option(Path.of(System.getProperty("user.dir")).toAbsolutePath))(
        _.flatMap(p => Option(p.getParent))
      )
      .takeWhile(_.isDefined)
      .flatten
      .find(p => Files.isRegularFile(p.resolve("build.sbt")))
