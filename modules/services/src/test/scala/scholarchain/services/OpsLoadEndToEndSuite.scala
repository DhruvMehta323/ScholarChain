package scholarchain.services

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import scholarchain.core.*
import scholarchain.rag.{EmbeddingDocumentRenderer, TextUris}
import scholarchain.storage.*

import OpsFx.*

/** End to end over the real Phase 06 run `modules/spark/target/runs/sparkS1r2`: the real
  * `LocalLoad` output (Spark, Test classpath) uploaded in the agreed layout into an in-memory
  * store, then `OpsLoad.run` with fakes. The documents it enqueues are compared with the reference
  * path (`ParquetInput.read` on the Parquet tables, then the same renderer).
  *
  * The suite FAILS (never skips) when the Phase 06 run is absent, so it cannot pass vacuously.
  */
class OpsLoadEndToEndSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 20.minutes

  private val E2eRun   = "run-e2e-1"
  private val NepSrc   = s"s3://$Bucket/graph/neptune-load/$E2eRun/"
  private val ItemsSrc = s"s3://$Bucket/graph/local-load/$E2eRun/"

  private def repoRoot: Path =
    val start = Paths.get(System.getProperty("user.dir")).toAbsolutePath
    Iterator
      .iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(throw new AssertionError(s"repository root (build.sbt) not found above $start"))

  private lazy val runRoot: Path =
    val p = repoRoot.resolve("modules/spark/target/runs/sparkS1r2")
    assert(
      Files.isRegularFile(p.resolve("load-manifests/neptune.json")),
      s"the Phase 06 run is absent ($p): run Phase 06 first; this suite does not skip",
    )
    p

  private def tree(dir: Path): Map[String, Array[Byte]] =
    val s = Files.walk(dir)
    try
      s.iterator.asScala
        .filter(Files.isRegularFile(_))
        .map(p => dir.relativize(p).toString.replace('\\', '/') -> Files.readAllBytes(p))
        .toMap
    finally s.close()

  private def deleteTree(d: Path): Unit =
    if Files.exists(d) then
      val s = Files.walk(d)
      try s.iterator.asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()

  /** The real LocalLoad output, built once per test JVM. */
  private lazy val loadDir: Path =
    val root = runRoot
    val base = Files.createTempDirectory("scholarchain-ops-e2e-")
    sys.addShutdownHook(deleteTree(base))
    val dir = base.resolve("load")
    val res = LocalLoad.run(LocalLoad.Args(root, dir, "test+ops-e2e"))
    assert(res.isRight, res.toString)
    dir

  private val agreed: String => Boolean = p =>
    p == "load-manifest.json" || p == "checksums.sha256" || p.startsWith("neptune/vertices/") ||
      p.startsWith("neptune/edges/") || p.startsWith("dynamodb/items/") ||
      p.startsWith("embedding-inputs/")

  private def uploaded(): Rig =
    val r = rig()
    upload(r.store, tree(loadDir).filter((p, _) => agreed(p)), NepSrc, ItemsSrc)
    r

  private def a(dry: Boolean = false) =
    OpsFx.args(
      dry = dry,
      runId = E2eRun,
      neptuneSource = NepSrc,
      itemsSource = ItemsSrc,
      timeout = 120L,
    )

  /** Reference: the Parquet tables through `ParquetInput` and the same renderer. */
  private lazy val reference: PipelineInput =
    ParquetInput.read(runRoot).fold(e => throw AssertionError(e.toString), identity)

  private lazy val referenceDocs =
    EmbeddingDocumentRenderer.renderDetailed(
      reference.summaries,
      reference.works,
      reference.venues,
      reference.topics,
      reference.inputRunId,
      TextUris.contentAddressed(Bucket, TextPrefix),
    )

  private def manifestOf(o: OpsOutcome): ujson.Value =
    ujson.read(o.manifestJson.getOrElse(fail(s"no manifest: ${o.status} ${o.detail}")))

  test(
    "the real LocalLoad output uploaded in the agreed layout completes with exit 0 under the cap"
  ) {
    val r   = uploaded()
    val out = r.run(a())
    assertEquals(out.exitCode, 0, s"${out.status}: ${out.detail}")
    assertEquals(out.status, "completed")
    assertEquals(out.manifestStatus, "written")
    val n = r.queue.documents.size
    assert(n > 0 && n <= 10000, s"$n documents enqueued")
    assertEquals(r.store.textObjects.size, n)
    assertEquals(manifestOf(out)("documents")("total").num.toInt, n)
    assertEquals(r.loader.starts, 1)
    assert(r.kv.puts > 0)
    assert(
      r.store.writeAttempts.forall(k =>
        k.startsWith("rag/text/") || k == ManifestKey || k.endsWith("/ops-manifest.json")
      )
    )
    assert(r.store.bytesOf(s"graph/local-load/$E2eRun/ops-manifest.json").isDefined)
  }

  test("parity: the enqueued documents are the reference renderer's documents, text for text") {
    val r   = uploaded()
    val out = r.run(a())
    assertEquals(out.exitCode, 0, out.detail)
    val refDocs = referenceDocs.documents
    val d       = manifestOf(out)("documents")
    assertEquals(d("uncappedTotal").num.toInt, refDocs.size)
    if refDocs.size <= 10000 then
      assertEquals(d("paperLimit"), ujson.Null)
      assertEquals(
        r.queue.documents.map(_.documentId).sorted,
        refDocs.map(_.document.documentId).sorted,
      )
      assertEquals(
        r.queue.documents.map(x => x.documentId -> x.textHash).sorted,
        refDocs.map(x => x.document.documentId -> x.document.textHash).sorted,
      )
      assertEquals(
        r.queue.documents.map(_.textS3Uri).sorted,
        refDocs.map(_.document.textS3Uri).sorted,
      )
    else assert(r.queue.documents.size <= 10000)
    // every stored text object is a rendered text, addressed by its hash
    val byHash = refDocs.map(x => x.document.textHash -> x.text).toMap
    r.store.textObjects.foreach { k =>
      val hash = k.stripPrefix("rag/text/").stripSuffix(".txt")
      val body = new String(r.store.bytesOf(k).get, StandardCharsets.UTF_8)
      assertEquals(Some(body), byHash.get(hash), s"text object $k")
    }
  }

  test("parity: rows, converted and rejected counts equal the reference conversion") {
    val r         = uploaded()
    val rec       = manifestOf(r.run(a(dry = true)))("records")
    val ref       = reference
    val converted = Map(
      "semantic-summaries" -> ref.summaries.size,
      "works"              -> ref.works.size,
      "venues"             -> ref.venues.size,
      "topics"             -> ref.topics.size,
    )
    ref.inputRows.foreach { (t, rows) =>
      assertEquals(rec(t)("rows").num.toInt, rows, s"rows of $t")
      assertEquals(rec(t)("converted").num.toInt, converted(t), s"converted of $t")
      val rejects = rec(t)("rejected").obj.map((k, v) => k -> v.num.toInt).toMap
      assertEquals(
        rejects,
        ref.convertRejects.getOrElse(t, Map.empty[String, Int]),
        s"rejects of $t",
      )
    }
  }

  test(
    "parity: the provenance the manifest carries is the provenance ParquetInput builds (provFor)"
  ) {
    val r      = uploaded()
    val loaded = tree(loadDir)
    val man    = ujson.read(loaded("load-manifest.json"))("embeddingInputs")
    val runId  = man("sparkRunId").str
    val code   = man("sparkCodeVersion").str
    assertEquals(runId, reference.inputRunId)
    def expected(table: String): Provenance =
      val digest = man("files").arr.find(_("table").str == table).get("partFilesDigest").str
      Provenance(
        1,
        SourceType.ScholarChain,
        List(InputRef(s"scholarchain-graph/$table", runId, Some(digest))),
        code,
        Map("table" -> table),
        Some(runId),
        None,
      )
    // the reference reads the part files itself, so a stale or wrong digest in the manifest shows here
    assertEquals(reference.summaries.head.provenance, expected("semantic-summaries"))
    assertEquals(reference.works.head.provenance, expected("works"))
    assertEquals(reference.venues.head.provenance, expected("venues"))
    assertEquals(reference.topics.head.provenance, expected("topics"))
    // and the JSONL reader, given that provenance, converts what the reference converts
    val sumBytes = loaded("embedding-inputs/semantic-summaries.jsonl")
    val types    = man("files").arr
      .find(_("table").str == "semantic-summaries")
      .get("columns")
      .arr
      .map(c => c("name").str -> c("sparkType").str)
      .toMap
    val jr = EmbeddingJsonl.summaries(sumBytes, types, expected("semantic-summaries"))
    assertEquals(jr.records, reference.summaries)
    assertEquals(r.manifestStored, None)
  }

  test("parity: the JSONL reader converts every table to the reference's records") {
    val loaded = tree(loadDir)
    val man    = ujson.read(loaded("load-manifest.json"))("embeddingInputs")
    val runId  = man("sparkRunId").str
    val code   = man("sparkCodeVersion").str
    def prov(table: String, digest: String) = Provenance(
      1,
      SourceType.ScholarChain,
      List(InputRef(s"scholarchain-graph/$table", runId, Some(digest))),
      code,
      Map("table" -> table),
      Some(runId),
      None,
    )
    def meta(table: String) =
      val f = man("files").arr.find(_("table").str == table).get
      (
        loaded(f("path").str),
        f("columns").arr.map(c => c("name").str -> c("sparkType").str).toMap,
        prov(table, f("partFilesDigest").str),
      )
    val (wb, wt, wp) = meta("works")
    assertEquals(EmbeddingJsonl.works(wb, wt, wp).records, reference.works)
    val (vb, vt, vp) = meta("venues")
    assertEquals(EmbeddingJsonl.venues(vb, vt, vp).records, reference.venues)
    val (tb, tt, tp) = meta("topics")
    assertEquals(EmbeddingJsonl.topics(tb, tt, tp).records, reference.topics)
  }

  test("a rerun over the real data writes nothing new and keeps the manifest") {
    val r   = uploaded()
    val one = r.run(a())
    val man = r.store.bytesOf(s"graph/local-load/$E2eRun/ops-manifest.json").map(_.toList)
    val two = r.run(a())
    assertEquals(two.exitCode, 0, two.detail)
    assertEquals(two.manifestStatus, "exists-not-overwritten")
    assertEquals(two.producer.get.textsWritten, 0)
    assertEquals(two.producer.get.textsUnchanged, one.producer.get.documentsSeen)
    assertEquals(two.producer.get.jobIds, one.producer.get.jobIds)
    assertEquals(r.store.bytesOf(s"graph/local-load/$E2eRun/ops-manifest.json").map(_.toList), man)
  }

  test("a dry run over the real data verifies and counts but writes and sends nothing") {
    val r   = uploaded()
    val out = r.run(a(dry = true))
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(out.manifestStatus, "dry-run-not-persisted")
    assertEquals(r.queue.sends, 0)
    assertEquals(r.store.writeAttempts.toList, Nil)
    assertEquals(r.loader.calls, 0)
    assertEquals(r.kv.calls, 0)
    assertEquals(
      manifestOf(out)("documents")("uncappedTotal").num.toInt,
      referenceDocs.documents.size,
    )
  }

  test(
    "a cap below the real total is applied: summaries first, then limited papers, total within the cap"
  ) {
    val r       = uploaded()
    val summary = referenceDocs.documents.count(_.document.documentType != "paper_abstract")
    val cap     = summary + 10
    val out     = r.run(a().copy(maxVectors = cap))
    assertEquals(out.exitCode, 0, out.detail)
    val d = manifestOf(out)("documents")
    assertEquals(d("paperLimit").num.toInt, 10)
    assertEquals(d("total").num.toInt, cap)
    assertEquals(r.queue.documents.size, cap)
    val expectedPapers = reference.works.map(_.workId).sorted.take(10)
    assertEquals(
      r.queue.documents.filter(_.documentType == "paper_abstract").map(_.entityId).sorted,
      expectedPapers,
    )
  }
