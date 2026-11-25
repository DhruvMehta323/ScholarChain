package scholarchain.services

import java.nio.charset.StandardCharsets

import scholarchain.core.*
import scholarchain.storage.*

import OpsFx.*

/** `OpsLoad.run` with fakes: dry-run, the happy path, idempotent rerun, the manifest rules, the
  * plan-before-write refusals, the LiveLoad and producer outcome mapping, the manifest content and
  * what must never appear in an emitted line.
  */
class OpsLoadRunSuite extends munit.FunSuite:

  private def manifest(o: OpsOutcome): ujson.Value =
    ujson.read(o.manifestJson.getOrElse(fail(s"no manifest json: ${o.status} ${o.detail}")))

  private def utf8(b: Array[Byte]): String = new String(b, StandardCharsets.UTF_8)

  private def assertNoManifest(r: Rig, o: OpsOutcome): Unit =
    assertEquals(r.manifestStored, None, "a manifest object exists")
    assert(!r.store.writeAttempts.contains(ManifestKey), "a manifest write was attempted")
    assertEquals(o.manifestJson, None)
    assertEquals(o.manifestStatus, "not-written")

  private def assertUntouched(r: Rig): Unit =
    assertEquals(r.effects, (0, 0, 0, 0, 0))

  // ---- 6. dry-run ---------------------------------------------------------------------------------

  test("dry-run: verifies, renders and counts, but sends and writes nothing") {
    val r   = rigWith(Src())
    val out = r.run(args(dry = true))
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(out.status, "dry-run-verified")
    assertEquals(out.manifestStatus, "dry-run-not-persisted")
    assertEquals(out.producer, None)
    assertEquals(r.queue.sends, 0)
    assertEquals(r.store.writeAttempts.toList, Nil)
    assertEquals(r.store.textObjects, Nil)
    assertEquals(r.manifestStored, None)
    assertEquals(r.loader.calls, 0)
    assertEquals(r.kv.calls, 0)
    assert(r.store.gets > 0, "the plan reads the sources")
  }

  test(
    "dry-run: the manifest is one stdout line, ops.manifest.dry-run, with dryRun true and no producer"
  ) {
    val r   = rigWith(Src())
    val out = r.run(args(dry = true))
    val ev  = r.events("ops.manifest.dry-run")
    assertEquals(ev.size, 1)
    val m = ev.head("manifest")
    assertEquals(m("dryRun"), ujson.True)
    assertEquals(m("producer"), ujson.Null)
    assertEquals(m("status"), ujson.Str("dry-run-verified"))
    assertEquals(m("exitCode").num.toInt, 0)
    assertEquals(m("liveLoad")("status"), ujson.Str("dry-run-verified"))
    assertEquals(m("liveLoad")("parameters")("dryRun"), ujson.True)
    assertEquals(manifest(out), m)
    assertEquals(r.events("ops.manifest"), Nil)
    assertEquals(r.events("ops.enqueue"), Nil)
    assert(r.lines.forall(!_.contains("\n")), "every emitted line is one JSON line")
  }

  test("dry-run: the counts are the real run's counts") {
    val dry  = rigWith(sized(4, 9))
    val real = rigWith(sized(4, 9))
    val d    = manifest(dry.run(args(dry = true)))("documents")
    val m    = manifest(real.run(args()))("documents")
    assertEquals(d, m)
  }

  test("dry-run then a real run: the dry-run left no object that blocks the real manifest") {
    val r = rigWith(Src())
    r.run(args(dry = true))
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(out.manifestStatus, "written")
    assert(r.manifestStored.isDefined)
  }

  // ---- the happy path ------------------------------------------------------------------------------

  test("a real run: texts, jobs and the manifest are written, and nothing else") {
    val r   = rigWith(Src())
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(out.status, "completed")
    assertEquals(out.manifestStatus, "written")
    val p = out.producer.getOrElse(fail("no producer report"))
    assertEquals((p.documentsSeen, p.textsWritten, p.textsUnchanged), (5, 5, 0))
    assertEquals(p.rejected, Nil)
    assertEquals(p.jobsEnqueued, 2)
    assertEquals(r.queue.sends, 2)
    assertEquals(r.queue.documents.size, 5)
    assertEquals(r.loader.starts, 1)
    assertEquals(r.kv.puts.toLong, ItemTotal)
    assert(
      r.store.writeAttempts.forall(k => k.startsWith("rag/text/") || k == ManifestKey),
      r.store.writeAttempts.toList.toString,
    )
    assertEquals(r.store.textObjects.size, 5)
    r.store.textObjects.foreach { k =>
      val body = r.store.bytesOf(k).get
      assertEquals(k, s"rag/text/${Hashing.sha256Hex(body)}.txt")
    }
    val stored = r.manifestStored.map(utf8).getOrElse(fail("no manifest object"))
    assert(stored.endsWith("\n"))
    assertEquals(ujson.read(stored), manifest(out))
    assertEquals(r.events("ops.manifest").head("manifestStatus"), ujson.Str("written"))
  }

  test("the queue messages carry document references, not text") {
    val r = rigWith(Src())
    r.run(args())
    r.queue.sent.foreach { (body, dedup) =>
      assert(dedup.isDefined)
      assert(
        !body.contains("studied graphs"),
        "the text travels in the object store, not the queue",
      )
    }
    r.queue.documents.foreach { d =>
      assertEquals(d.textS3Uri, s"s3://$Bucket/$TextPrefix/${d.textHash}.txt")
      assertEquals(d.sourceRunId, SparkRunId)
    }
  }

  // ---- 7. idempotent rerun -------------------------------------------------------------------------

  test("rerun: nothing rewritten, same job ids, items unchanged, manifest kept byte for byte") {
    val r    = rigWith(Src())
    val one  = r.run(args())
    val man1 = r.manifestStored.map(_.toList).getOrElse(fail("no manifest after run 1"))
    val two  = r.run(args())
    assertEquals(two.exitCode, 0, two.detail)
    assertEquals(two.status, "completed")
    assertEquals(two.manifestStatus, "exists-not-overwritten")
    val p1 = one.producer.get
    val p2 = two.producer.get
    assertEquals(p2.textsWritten, 0)
    assertEquals(p2.textsUnchanged, p1.documentsSeen)
    assertEquals(p2.jobIds, p1.jobIds)
    assertEquals(p2.jobsEnqueued, p1.jobsEnqueued)
    assertEquals(r.manifestStored.map(_.toList), Some(man1))
    // the queue de-duplicates the resent jobs
    assertEquals(r.queue.inner.depth(embeddingSettings.queueName), p1.jobsEnqueued)
    val ll = manifest(two)("liveLoad")("items")("written")
    ll.obj.values.foreach { t =>
      assertEquals(t("written").num.toInt, 0)
      assertEquals(t("conflicts").num.toInt, 0)
    }
    assertEquals(ll.obj.values.map(_("unchanged").num.toLong).sum, ItemTotal)
    val ll1 = manifest(one)("liveLoad")("items")("written")
    assertEquals(ll1.obj.values.map(_("written").num.toLong).sum, ItemTotal)
    assertEquals(
      r.store.writeAttempts.count(_ == ManifestKey),
      2,
      "the second run attempted putIfAbsent and was refused",
    )
    assertEquals(r.store.textObjects.size, p1.documentsSeen)
    val sentBodies = r.queue.sent.toList.map(_._1)
    assertEquals(sentBodies.take(p1.jobsEnqueued), sentBodies.drop(p1.jobsEnqueued))
  }

  test("rerun: the stored manifest is the first run's, whatever the second run would say") {
    val r = rigWith(Src())
    r.run(args())
    val before = r.manifestStored.map(utf8)
    val two    = r.run(args())
    assertEquals(r.manifestStored.map(utf8), before)
    assertEquals(manifest(two)("producer")("textsWritten").num.toInt, 0)
    assertEquals(ujson.read(before.get)("producer")("textsWritten").num.toInt, 5)
  }

  // ---- 8. the manifest is never overwritten ----------------------------------------------------------

  test("an existing manifest is never overwritten and the run still completes (exit 0)") {
    val r = rigWith(Src())
    val k = ObjectLocation(Bucket, ManifestKey)
    r.store.seed(k, "PRE-EXISTING".getBytes(StandardCharsets.UTF_8))
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(out.manifestStatus, "exists-not-overwritten")
    assertEquals(r.manifestStored.map(utf8), Some("PRE-EXISTING"))
  }

  test("any other manifest write failure is manifest-failed with exit 1") {
    List[StorageError](
      StorageError.Failed("write refused"),
      StorageError.InvalidRequest("denied"),
      StorageError.Unavailable("timeout"),
      StorageError.Throttled("slow down"),
      StorageError.IntegrityMismatch("hash"),
    ).foreach { e =>
      val r = rigWith(Src())
      r.store.failWrite = l => Option.when(l.objectKey == ManifestKey)(e)
      val out = r.run(args())
      assertEquals(out.exitCode, 1, s"$e")
      assertEquals(out.status, "manifest-failed")
      assertEquals(out.manifestStatus, "not-written")
      assert(out.detail.contains("manifest write failed"), out.detail)
      assertEquals(r.manifestStored, None)
      // the enqueue happened: texts and jobs are in place, the manifest is what is missing
      assertEquals(r.queue.sends, 2)
      assertEquals(r.events("ops.manifest").head("exitCode").num.toInt, 1)
    }
  }

  test("manifest-failed is retryable: a corrected rerun writes the manifest") {
    val r = rigWith(Src())
    r.store.failWrite = l => Option.when(l.objectKey == ManifestKey)(StorageError.Failed("boom"))
    assertEquals(r.run(args()).exitCode, 1)
    r.store.failWrite = _ => None
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(out.manifestStatus, "written")
    assert(r.manifestStored.isDefined)
  }

  test("the manifest key is exactly <items-source>ops-manifest.json") {
    val items   = "s3://placeholder-scholarchain-bucket/graph/other-prefix/deeper/run-007/"
    val neptune = "s3://placeholder-scholarchain-bucket/graph/neptune-load/run-007/"
    val r       = rig()
    upload(r.store, Src().render, neptune, items)
    val out = r.run(args(runId = "run-007", neptuneSource = neptune, itemsSource = items))
    assertEquals(out.exitCode, 0, out.detail)
    val wanted = "graph/other-prefix/deeper/run-007/ops-manifest.json"
    assertEquals(r.store.keys("").filter(_.endsWith("ops-manifest.json")), List(wanted))
    assertEquals(
      r.store.writeAttempts.toList.filterNot(_.startsWith("rag/text/")),
      List(wanted),
    )
  }

  test("a manifest write is a putIfAbsent: a plain put is never used for it") {
    val r = rigWith(Src())
    r.store.failWrite = _ => None
    r.run(args())
    // MemStore.put also records attempts, so a put over an existing manifest would have replaced it
    val k = ObjectLocation(Bucket, ManifestKey)
    r.store.seed(k, "KEEP".getBytes(StandardCharsets.UTF_8))
    r.run(args())
    assertEquals(r.manifestStored.map(utf8), Some("KEEP"))
  }

  // ---- 9. plan before write --------------------------------------------------------------------------

  private def refused(
      r: Rig,
      a: OpsArgs,
      exit: Int,
      status: String,
  ): OpsOutcome =
    val out = r.run(a)
    assertEquals(out.exitCode, exit, s"${out.status}: ${out.detail}")
    assertEquals(out.status, status)
    assertUntouched(r)
    assertNoManifest(r, out)
    assertEquals(out.producer, None)
    assertEquals(r.events("ops.refused").size, 1)
    assertEquals(r.event("ops.refused")("status"), ujson.Str(status))
    assertEquals(r.event("ops.refused")("exitCode").num.toInt, exit)
    out

  test("plan: the cap refuses before any loader, items, queue or text call") {
    refused(rigWith(sized(6, 2)), args(maxVectors = 5), 3, "cap-exceeded")
  }

  test("plan: a tampered embedding file is a checksum mismatch with no effect") {
    val r = rigWith(
      Src(),
      tamper =
        f => f.updated("embedding-inputs/works.jsonl", flip(f("embedding-inputs/works.jsonl"))),
    )
    refused(r, args(), 3, "checksum-mismatch")
  }

  test("plan: every embedding file is covered by the checksum") {
    LocalLoad.EmbeddingTables.foreach { t =>
      val p = s"embedding-inputs/$t.jsonl"
      val r = rigWith(Src(), tamper = f => f.updated(p, flip(f(p))))
      refused(r, args(), 3, "checksum-mismatch")
    }
  }

  test("plan: a load manifest that differs from checksums.sha256 is a checksum mismatch") {
    val r = rigWith(
      Src(),
      tamper =
        f => f.updated("load-manifest.json", f("load-manifest.json") ++ " ".getBytes("UTF-8")),
    )
    refused(r, args(), 3, "checksum-mismatch")
  }

  test("plan: a checksums.sha256 with a malformed line is source-invalid") {
    val r = rigWith(
      Src(),
      tamper = f => f.updated("checksums.sha256", "not-a-checksum-line\n".getBytes("UTF-8")),
    )
    refused(r, args(), 3, "source-invalid")
  }

  test("plan: a bad source run id (not run:<64 hex>) is source-invalid") {
    List("run:abc", "run:" + ("AB" * 32), "sparkRun1", "run:" + ("a" * 63)).foreach { bad =>
      refused(rigWith(Src(sparkRunId = bad)), args(), 3, "source-invalid")
    }
  }

  test("plan: a rendered number absent from its source row is number-check-failed") {
    // 2500 digits without whitespace: the chunker has to cut inside the number
    val src = Src(summaries = List(summaryRow("researcher:dblp:r1", "9" * 2500)))
    val out = refused(rigWith(src), args(), 3, "number-check-failed")
    assert(out.detail.contains("number"), out.detail)
  }

  test("plan: a missing embeddingInputs section is source-invalid") {
    refused(rigWith(Src(withEmbeds = false)), args(), 3, "source-invalid")
  }

  test("plan: an embeddingInputs without sparkRunId, files or a table entry is source-invalid") {
    List[ujson.Obj => Unit](
      m => m("embeddingInputs").obj.remove("sparkRunId"),
      m => m("embeddingInputs").obj.remove("sparkCodeVersion"),
      m => m("embeddingInputs").obj.remove("files"),
      m => m("embeddingInputs")("files").arr.remove(0),
      m => m("embeddingInputs")("files")(0).obj.remove("partFilesDigest"),
    ).zipWithIndex.foreach { (edit, i) =>
      val r   = rigWith(Src(edit = edit))
      val out = r.run(args())
      assertEquals(out.exitCode, 3, s"edit $i: ${out.status} ${out.detail}")
      assert(out.status == "source-invalid", s"edit $i: ${out.status}")
      assertUntouched(r)
      assertNoManifest(r, out)
    }
  }

  test("plan: a line count that differs from the manifest rows is source-invalid") {
    val r = rigWith(Src(edit = m => m("embeddingInputs")("files")(0)("rows") = ujson.Num(99.0)))
    refused(r, args(), 3, "source-invalid")
  }

  test("plan: a missing embedding file or a missing checksums.sha256 is source-invalid") {
    val r1 = rigWith(Src(), tamper = _ - "embedding-inputs/venues.jsonl")
    refused(r1, args(), 3, "source-invalid")
    val r2 = rigWith(Src(), tamper = _ - "checksums.sha256")
    refused(r2, args(), 3, "source-invalid")
    val r3 = rigWith(Src(), tamper = _ - "load-manifest.json")
    refused(r3, args(), 3, "source-invalid")
  }

  test("plan: no document rendered is nothing-to-enqueue (exit 3)") {
    refused(rigWith(Src(summaries = Nil, works = Nil)), args(), 3, "nothing-to-enqueue")
  }

  test("plan: every row rejected is nothing-to-enqueue, and the rejects stay out of the output") {
    val bad = Src(
      summaries = Nil,
      works = List(workRow(1, Some("   ")), workRow(2, Some(""))),
    )
    refused(rigWith(bad), args(), 3, "nothing-to-enqueue")
  }

  test("plan: an items source that is not an s3 prefix URI is usage-invalid (exit 2)") {
    val r = rigWith(Src())
    refused(r, args(itemsSource = "not-an-s3-uri"), 2, "usage-invalid")
    val r2 = rigWith(Src())
    refused(
      r2,
      args(itemsSource = "s3://placeholder-scholarchain-bucket/graph/local-load/run-001"),
      2,
      "usage-invalid",
    )
  }

  test("plan: a dry-run refuses on the same grounds") {
    val r = rigWith(
      Src(),
      tamper =
        f => f.updated("embedding-inputs/topics.jsonl", flip(f("embedding-inputs/topics.jsonl"))),
    )
    refused(r, args(dry = true), 3, "checksum-mismatch")
  }

  // ---- 10. LiveLoad mapping --------------------------------------------------------------------------

  private def liveRefused(
      r: Rig,
      a: OpsArgs,
      exit: Int,
      liveStatus: String,
      ctxx: OpsContext = ctx(),
  ): OpsOutcome =
    val out = r.run(a, ctxx)
    assertEquals(out.exitCode, exit, s"${out.status}: ${out.detail}")
    assertEquals(out.status, s"liveload-$liveStatus")
    assertEquals(r.queue.sends, 0)
    assertEquals(r.store.textObjects, Nil)
    assertEquals(r.store.writeAttempts.toList, Nil)
    assertNoManifest(r, out)
    assertEquals(out.producer, None)
    out

  test("Neptune load failed: exit 1, nothing enqueued, no manifest") {
    val r = rigWith(
      Src(),
      loader = FakeLoader.scripted(List(Right(BulkLoadStatus.LoadFailed("LOAD_FAILED")))),
    )
    liveRefused(r, args(), 1, "neptune-failed")
    assertEquals(r.kv.puts, 0, "the items are not written after a failed Neptune load")
  }

  test("Neptune start failed: exit 1, nothing enqueued") {
    val r = rigWith(Src(), loader = FakeLoader.failingStart(StorageError.Failed("start")))
    liveRefused(r, args(), 1, "neptune-failed")
  }

  test("Neptune cancelled: exit 1, nothing enqueued") {
    val r = rigWith(Src(), loader = FakeLoader.scripted(List(Right(BulkLoadStatus.Cancelled))))
    liveRefused(r, args(), 1, "neptune-cancelled")
  }

  test("Neptune timed out: exit 1, nothing enqueued, the job is left running") {
    val r = rigWith(
      Src(),
      loader = FakeLoader.scripted(List(Right(BulkLoadStatus.InProgress(Some(3L))))),
    )
    val out = liveRefused(r, args(timeout = 5), 1, "neptune-timeout")
    assert(out.detail.contains("left running"), out.detail)
    assert(r.sleeper.sleeps > 0)
    assert(r.loader.statusCalls > 1)
  }

  test("a LiveLoad refusal that is not an I/O failure is exit 3 (items cap)") {
    val r = rigWith(Src())
    liveRefused(r, args(maxItems = 1L), 3, "cap-exceeded")
    assertEquals(r.loader.calls, 0)
    assertEquals(r.kv.calls, 0)
  }

  test("a LiveLoad refusal is exit 3 (a Neptune CSV differing from its checksum)") {
    val r = rigWith(
      Src(),
      tamper = f =>
        f.updated("neptune/vertices/Researcher.csv", flip(f("neptune/vertices/Researcher.csv"))),
    )
    liveRefused(r, args(), 3, "checksum-mismatch")
    assertEquals(r.loader.calls, 0)
  }

  test("a LiveLoad refusal is exit 3 (an extra object under the Neptune prefix)") {
    val r = rigWith(Src())
    r.store.seed(
      ObjectLocation(Bucket, "graph/neptune-load/run-001/vertices/Stray.csv"),
      Array[Byte](1, 2, 3),
    )
    liveRefused(r, args(), 3, "source-invalid")
    assertEquals(r.loader.calls, 0)
  }

  test("a LiveLoad refusal is exit 3 (manifest neptune invariants not all zero)") {
    val r =
      rigWith(Src(edit = m => m("neptune")("invariants")("danglingEndpoints") = ujson.Num(2.0)))
    liveRefused(r, args(), 3, "source-invalid")
    assertEquals(r.loader.calls, 0)
  }

  test("LiveLoad usage-invalid is exit 2 (the run id is not the last segment of the sources)") {
    val r = rigWith(Src())
    liveRefused(r, args(runId = "run-other"), 2, "usage-invalid")
    assertEquals(r.loader.calls, 0)
    assertEquals(r.kv.calls, 0)
  }

  test("LiveLoad usage-invalid is exit 2 (a Neptune source outside the configured load prefix)") {
    val r = rigWith(Src())
    liveRefused(
      r,
      args(neptuneSource = "s3://some-other-bucket/graph/neptune-load/run-001/"),
      2,
      "usage-invalid",
    )
    assertEquals(r.loader.calls, 0)
  }

  test("LiveLoad provenance is on stdout for a failure, as one parseable line") {
    val r = rigWith(Src(), loader = FakeLoader.scripted(List(Right(BulkLoadStatus.Cancelled))))
    r.run(args())
    val ev = r.event("ops.liveload.provenance")
    assertEquals(ev("provenance")("schema"), ujson.Str(LiveLoad.ProvenanceSchema))
    assertEquals(ev("provenance")("status"), ujson.Str("neptune-cancelled"))
    assertEquals(ev("provenance")("runId"), ujson.Str(RunId))
    assertEquals(ev("provenance")("neptune")("status"), ujson.Str("cancelled"))
    assertEquals(ev("level"), ujson.Str("error"))
    assertEquals(r.event("ops.liveload")("status"), ujson.Str("neptune-cancelled"))
    assertEquals(r.event("ops.liveload")("exitCode").num.toInt, 1)
  }

  test("LiveLoad provenance is in the manifest and on stdout for a success") {
    val r   = rigWith(Src())
    val out = r.run(args())
    val ll  = manifest(out)("liveLoad")
    assertEquals(ll("schema"), ujson.Str(LiveLoad.ProvenanceSchema))
    assertEquals(ll("status"), ujson.Str("completed"))
    assertEquals(ll("neptune")("loadId"), ujson.Str("load-0001"))
    assertEquals(ll("items")("total").num.toLong, ItemTotal)
    assertEquals(r.event("ops.manifest")("manifest")("liveLoad"), ll)
    assertEquals(r.events("ops.liveload.provenance"), Nil)
  }

  test("the LiveLoad provenance of a usage error is on stdout too") {
    val r = rigWith(Src())
    r.run(args(runId = "run-other"))
    assertEquals(
      r.event("ops.liveload.provenance")("provenance")("status"),
      ujson.Str("usage-invalid"),
    )
  }

  // ---- 11. producer outcomes -------------------------------------------------------------------------

  test("a queue send failure is enqueue-failed with exit 1 and no manifest") {
    val r = rigWith(Src())
    r.queue.failSend = Some(StorageError.Failed("queue is down"))
    val out = r.run(args())
    assertEquals(out.exitCode, 1)
    assertEquals(out.status, "enqueue-failed")
    assert(out.detail.startsWith("enqueue failed ("), out.detail)
    assertNoManifest(r, out)
    assertEquals(out.producer, None)
    assertEquals(r.queue.inner.depth(embeddingSettings.queueName), 0)
    assertEquals(r.event("ops.refused")("status"), ujson.Str("enqueue-failed"))
  }

  test(
    "a retryable queue failure is also enqueue-failed with no manifest, and a rerun can complete"
  ) {
    val r = rigWith(Src())
    r.queue.failSend = Some(StorageError.Unavailable("later"))
    assertEquals(r.run(args()).exitCode, 1)
    assertEquals(r.manifestStored, None)
    r.queue.failSend = None
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    assertEquals(out.manifestStatus, "written")
    assertEquals(out.producer.get.textsUnchanged, 5)
  }

  test(
    "a text conflict is producer-rejected with exit 3, the manifest is written, the object kept"
  ) {
    val r        = rigWith(Src())
    val text     = "Researcher number 1 studied graphs and networks."
    val conflict = s"$TextPrefix/${Hashing.textHash(text)}.txt"
    r.store.seed(
      ObjectLocation(Bucket, conflict),
      "SOMETHING ELSE".getBytes(StandardCharsets.UTF_8),
    )
    val out = r.run(args())
    assertEquals(out.exitCode, 3, out.detail)
    assertEquals(out.status, "producer-rejected")
    assertEquals(out.manifestStatus, "written")
    assert(out.detail.contains("rejected by the producer"), out.detail)
    val p = out.producer.get
    assertEquals(p.rejectedByReason, Map("text-conflict" -> 1))
    assertEquals(p.documentsSeen, 4)
    assertEquals(r.store.bytesOf(conflict).map(utf8), Some("SOMETHING ELSE"))
    assert(r.manifestStored.isDefined)
    val m = manifest(out)
    assertEquals(m("status"), ujson.Str("producer-rejected"))
    assertEquals(m("exitCode").num.toInt, 3)
    assertEquals(m("producer")("rejectedByReason")("text-conflict").num.toInt, 1)
    assertEquals(r.queue.sends, 2)
  }

  test("producer-rejected with an existing manifest is still exit 3 and the manifest is kept") {
    val r    = rigWith(Src())
    val text = "Researcher number 1 studied graphs and networks."
    r.store.seed(
      ObjectLocation(Bucket, s"$TextPrefix/${Hashing.textHash(text)}.txt"),
      Array[Byte](9),
    )
    r.store.seed(ObjectLocation(Bucket, ManifestKey), "OLD".getBytes(StandardCharsets.UTF_8))
    val out = r.run(args())
    assertEquals(out.exitCode, 3)
    assertEquals(out.status, "producer-rejected")
    assertEquals(out.manifestStatus, "exists-not-overwritten")
    assertEquals(r.manifestStored.map(utf8), Some("OLD"))
  }

  test(
    "a text object that cannot be stored is rejected (text-store-failed) without a send failure"
  ) {
    val r = rigWith(Src())
    r.store.failWrite =
      l => Option.when(l.objectKey.startsWith("rag/text/"))(StorageError.Failed("denied"))
    val out = r.run(args())
    assertEquals(out.exitCode, 3, out.detail)
    assertEquals(out.status, "producer-rejected")
    assertEquals(out.producer.get.rejectedByReason, Map("text-store-failed" -> 5))
    assertEquals(r.queue.sends, 0)
  }

  test("zero documents is nothing-to-enqueue (exit 3) in a real run") {
    val r = rigWith(Src(summaries = Nil, works = Nil))
    refused(r, args(), 3, "nothing-to-enqueue")
  }

  // ---- 12. manifest content --------------------------------------------------------------------------

  private val mixedSrc: Src =
    val dupA = summaryRow("researcher:dblp:dup", "First text of the duplicate key.")
    val dupB = summaryRow("researcher:dblp:dup", "Second, different text for the same key.")
    Src(
      summaries = List(
        summaryN(1),
        summaryN(2),
        dupA,
        dupB,
        summaryRow(
          "community:2000-2004:c1",
          "Community of 5 researchers.",
          EmbeddingDocumentTypes.CommunityProfile,
        ),
        summaryRow("researcher:dblp:bad", "ignored", ws = 2000).set("textHash", "0" * 64),
      ),
      works = List(
        workRow(1),
        workRow(2),
        workRow(3, Some("   ")),
        workRow(4, Some("")),
        workRow(5).set("year", ujson.Null),
      ),
      extraLines = Map("works" -> List("{broken line")),
    )

  test("manifest: schema, tool, run, code version, source and parameters") {
    val r = rigWith(Src())
    val m = manifest(
      r.run(args(maxVectors = 500, timeout = 77, maxItems = 99999L), ctx(Some("v1.2.3-rc1")))
    )
    assertEquals(m("schema"), ujson.Str(OpsLoad.ManifestSchema))
    assertEquals(m("tool"), ujson.Str(OpsLoad.Tool))
    assertEquals(m("status"), ujson.Str("completed"))
    assertEquals(m("exitCode").num.toInt, 0)
    assertEquals(m("dryRun"), ujson.False)
    assertEquals(m("runId"), ujson.Str(RunId))
    assertEquals(m("codeVersion"), ujson.Str(CodeVersion))
    assertEquals(m("imageTag"), ujson.Str("v1.2.3-rc1"))
    assertEquals(m("source")("sparkRunId"), ujson.Str(SparkRunId))
    assertEquals(m("source")("sparkCodeVersion"), ujson.Str(SparkCode))
    val p = m("parameters")
    assertEquals(p("itemsSource"), ujson.Str(ItemsSource))
    assertEquals(p("neptuneSource"), ujson.Str(NeptuneSource))
    assertEquals(p("maxVectors").num.toInt, 500)
    assertEquals(p("maxItems").num.toLong, 99999L)
    assertEquals(p("neptuneTimeoutSeconds").num.toLong, 77L)
    assertEquals(p("embeddingModelId"), ujson.Str(embeddingSettings.modelId))
    assertEquals(p("embeddingModelVersion"), ujson.Str(embeddingSettings.modelVersion))
    assertEquals(p("maxDocumentsPerJob").num.toInt, embeddingSettings.maxDocumentsPerJob)
    assertEquals(p("textPrefix"), ujson.Str(s"s3://$Bucket/$TextPrefix/"))
  }

  test("manifest: imageTag is null when none is set") {
    val m = manifest(rigWith(Src()).run(args()))
    assertEquals(m("imageTag"), ujson.Null)
  }

  test("manifest: input checksums and rows are those of the verified files") {
    val src = Src()
    val r   = rigWith(src)
    val m   = manifest(r.run(args()))
    val ins = m("inputs")("embeddingInputs").arr.toList
    assertEquals(ins.map(_("table").str), List("semantic-summaries", "topics", "venues", "works"))
    ins.foreach { f =>
      val t     = f("table").str
      val bytes = src.tableBytes(t)
      assertEquals(f("sha256").str, Hashing.sha256Hex(bytes), t)
      assertEquals(
        f("rows").num.toLong,
        new String(bytes, StandardCharsets.UTF_8).split("\n").count(_.nonEmpty).toLong,
        t,
      )
      assertEquals(f("bytes").num.toLong, bytes.length.toLong, t)
      assertEquals(f("uri").str, s"${ItemsSource}embedding-inputs/$t.jsonl")
    }
    val rendered = src.render
    assertEquals(
      m("inputs")("loadManifestSha256").str,
      Hashing.sha256Hex(rendered("load-manifest.json")),
    )
    assertEquals(
      m("inputs")("checksumsSha256").str,
      Hashing.sha256Hex(rendered("checksums.sha256")),
    )
  }

  test("manifest: counts by type, reasons aggregated, skipped by reason") {
    val r   = rigWith(mixedSrc)
    val out = r.run(args())
    assertEquals(out.exitCode, 0, out.detail)
    val m   = manifest(out)
    val rec = m("records")
    assertEquals(rec("semantic-summaries")("rows").num.toInt, 6)
    assertEquals(rec("semantic-summaries")("converted").num.toInt, 5)
    assertEquals(rec("works")("rows").num.toInt, 6)
    assertEquals(rec("works")("converted").num.toInt, 2)
    val wr = rec("works")("rejected").obj.map((k, v) => k -> v.num.toInt).toMap
    assertEquals(wr("malformed-line"), 1)
    assertEquals(wr("null-or-wrong-type"), 1)
    assertEquals(wr.filter(_._1.startsWith("invalid-")).values.sum, 2, wr.toString)
    assertEquals(wr.values.sum, 4)
    val sr = rec("semantic-summaries")("rejected").obj.map((k, v) => k -> v.num.toInt).toMap
    assertEquals(sr.values.sum, 1)
    assert(sr.keys.forall(_.startsWith("invalid-")), sr.toString)
    assertEquals(rec("venues")("rejected").obj.size, 0)
    val d = m("documents")
    assertEquals(d("skippedByReason")("duplicate-key-different-text").num.toInt, 1)
    assertEquals(d("byType")("researcher_profile").num.toInt, 3)
    assertEquals(d("byType")("community_profile").num.toInt, 1)
    assertEquals(d("byType")("paper_abstract").num.toInt, 2)
    assertEquals(d("entitiesByType")("researcher_profile").num.toInt, 3)
    assertEquals(d("total").num.toInt, 6)
    assertEquals(d("uncappedTotal").num.toInt, 6)
    assertEquals(d("summaryDocuments").num.toInt, 4)
    assertEquals(d("paperLimit"), ujson.Null)
    assertEquals(d("papersDroppedByCap").num.toInt, 0)
  }

  test("manifest: the producer report is complete and sorted") {
    val r   = rigWith(Src())
    val out = r.run(args())
    val p   = manifest(out)("producer")
    assertEquals(p("documentsSeen").num.toInt, 5)
    assertEquals(p("textsWritten").num.toInt, 5)
    assertEquals(p("textsUnchanged").num.toInt, 0)
    assertEquals(p("jobsEnqueued").num.toInt, 2)
    assertEquals(p("jobIds").arr.map(_.str).toList, out.producer.get.jobIds)
    assertEquals(p("rejectedByReason").obj.size, 0)
    p("jobIds").arr.foreach(j => assert(j.str.startsWith("embjob-"), j.toString))
  }

  test("manifest: keys are sorted so the stored bytes are canonical") {
    val r = rigWith(Src())
    r.run(args())
    val txt  = r.manifestStored.map(utf8).get
    val keys = ujson.read(txt).obj.keys.toList
    assertEquals(keys, keys.sorted)
  }

  // ---- 13. nothing secret, no host path, no row content in any output ----------------------------------

  private val Forbidden: List[String] = List(
    "/Users/",
    "/home/",
    "target/",
    "arn:aws:iam",
    "AKIA",
    "\tat ",
    "Exception",
    ".scala:",
    "Authorization",
    "https://sqs.",
    "neptune-sentinel",
    "SENTINEL",
  )

  private def assertClean(label: String, texts: Iterable[String], extra: List[String] = Nil): Unit =
    texts.foreach { t =>
      (Forbidden ++ extra).foreach(f =>
        assert(!t.contains(f), s"$label contains '$f': ${t.take(300)}")
      )
    }

  private val leakySrc: Src = Src(
    works = List(
      workRow(1),
      workRow(2, Some("   ")).set("doi", "SENTINEL-DOI-9931"),
      workRow(3).set("workId", "SENTINEL-WORK-ID"),
      workRow(4).set("topicIds", ujson.Arr("SENTINEL-TOPIC", 3)),
    ),
    extraLines = Map("works" -> List("""{"title":"SENTINEL-MALFORMED""")),
  )

  test("no host path, secret, role ARN or row content in any line or the manifest (success)") {
    val r   = rigWith(leakySrc)
    val out = r.run(args(), ctx(Some("v1.0")))
    assertEquals(out.exitCode, 0, out.detail)
    assertClean("line", r.lines)
    assertClean("manifest", out.manifestJson.toList ++ r.manifestStored.map(utf8).toList)
    assertClean("detail", List(out.detail, out.status))
    assert(r.lines.nonEmpty)
    r.json.foreach(j => assert(j.obj.contains("event") && j.obj.contains("level")))
  }

  test("no host path, secret or row content in refusals and failures") {
    val scenarios: List[(String, Rig, OpsArgs)] = List(
      ("cap", rigWith(leakySrc), args(maxVectors = 1)),
      (
        "checksum",
        rigWith(
          leakySrc,
          tamper =
            f => f.updated("embedding-inputs/works.jsonl", flip(f("embedding-inputs/works.jsonl"))),
        ),
        args(),
      ),
      (
        "number-check",
        rigWith(Src(summaries = List(summaryRow("researcher:dblp:r1", "9" * 2500)))),
        args(),
      ),
      (
        "cancelled",
        rigWith(leakySrc, FakeLoader.scripted(List(Right(BulkLoadStatus.Cancelled)))),
        args(),
      ),
      ("usage", rigWith(leakySrc), args(runId = "run-other")),
      ("dry-run", rigWith(leakySrc), args(dry = true)),
    )
    scenarios.foreach { (label, r, a) =>
      val out = r.run(a)
      assertClean(s"$label lines", r.lines)
      assertClean(s"$label detail", List(out.detail, out.status) ++ out.manifestJson.toList)
    }
  }

  test("a storage error's own text (host path, ARN) never reaches the output") {
    val nasty =
      "/Users/someone/.aws/credentials target/classes arn:aws:iam::123456789012:role/x AKIAXXXXXXXXXXXXXXXX"
    // manifest write failure
    val r1 = rigWith(Src())
    r1.store.failWrite = l => Option.when(l.objectKey == ManifestKey)(StorageError.Failed(nasty))
    val o1 = r1.run(args())
    assertEquals(o1.status, "manifest-failed")
    assertClean("manifest-failed lines", r1.lines)
    assertClean("manifest-failed detail", List(o1.detail))
    // queue failure
    val r2 = rigWith(Src())
    r2.queue.failSend = Some(StorageError.Failed(nasty))
    val o2 = r2.run(args())
    assertEquals(o2.status, "enqueue-failed")
    assertClean("enqueue-failed lines", r2.lines)
    assertClean("enqueue-failed detail", List(o2.detail))
    // object read failure
    val r3 = rigWith(Src())
    r3.store.data.remove((Bucket, s"graph/local-load/$RunId/embedding-inputs/works.jsonl"))
    val o3 = r3.run(args())
    assertClean("missing-object lines", r3.lines)
    assertClean("missing-object detail", List(o3.detail))
    // neptune start failure
    val r4 = rigWith(Src(), FakeLoader.failingStart(StorageError.Failed(nasty)))
    val o4 = r4.run(args())
    assertClean("neptune-failed lines", r4.lines)
    assertClean("neptune-failed detail", List(o4.detail))
  }

  test("a rejected row's content is not in the manifest, only a reason and a count") {
    val r   = rigWith(leakySrc)
    val out = r.run(args())
    val txt = out.manifestJson.get
    assert(!txt.contains("SENTINEL"))
    val rej = manifest(out)("records")("works")("rejected").obj.keys.toSet
    assert(rej.forall(k => k.matches("[a-z0-9\\-]+")), rej.toString)
  }
