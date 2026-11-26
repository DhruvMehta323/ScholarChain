package scholarchain.storage

import java.nio.charset.StandardCharsets

import scholarchain.core.*

import LiveLoadFx.*

/** Phase 14 Part A (M13-2): `LiveLoad.run` against fakes only. The fixtures are the real output of
  * `NeptuneCsv` / `AnalyticsItems.build` wrapped in a `LocalLoad`-style manifest and checksum file.
  * No AWS call, no credential.
  */
class LiveLoadSuite extends munit.FunSuite:

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)
  private def text(b: Array[Byte]): String = new String(b, StandardCharsets.UTF_8)

  private val NeptuneKey: String = neptuneCsv.head._1
  private val ItemsKey: String   = "dynamodb/items/researcher-metrics.jsonl"
  private val EmbedKey: String   = "embedding-inputs/works.jsonl"

  private def ready(
      files: Map[String, Array[Byte]] = Fx().render,
      loader: FakeLoader = FakeLoader.completing(),
      kvInner: Option[InMemoryKeyValueStore] = None,
      putError: Option[StorageError] = None,
  ): Rig =
    val r = rig(loader, kvInner, putError)
    upload(r.store, files)
    r

  private def sumLines(files: Map[String, Array[Byte]]): List[String] =
    text(files("checksums.sha256")).split("\n").toList.filter(_.nonEmpty)

  private def withSums(
      files: Map[String, Array[Byte]]
  )(f: List[String] => List[String]): Map[String, Array[Byte]] =
    files.updated("checksums.sha256", utf8(f(sumLines(files)).map(_ + "\n").mkString))

  /** Replaces the content of a file and fixes its checksum line (a consistent tree). */
  private def rewrite(
      files: Map[String, Array[Byte]],
      path: String,
      bytes: Array[Byte],
  ): Map[String, Array[Byte]] =
    withSums(files.updated(path, bytes))(
      _.map(l => if l.endsWith("  " + path) then s"${Cells.sha256(bytes)}  $path" else l)
    )

  private def noExternalCalls(r: Rig)(using loc: munit.Location): Unit =
    assertEquals(r.loader.calls, 0, "no Neptune loader call expected")
    assertEquals(r.kv.calls, 0, "no DynamoDB call expected")

  private def checkNoLeaks(rep: LiveLoadReport)(using munit.Location): Unit =
    val t = rep.provenanceJson
    List(cfg.neptune.roleArn, "arn:aws", "000000000000", "/Users", "/tmp", "/var/folders").foreach(
      s => assert(!t.contains(s), s"provenance leaks '$s'")
    )
    List("secret", "password", "AKIA").foreach(s =>
      assert(!t.toLowerCase.contains(s.toLowerCase), s"provenance mentions '$s'")
    )

  // ---- 1. happy path ---------------------------------------------------------------------------

  test("1. happy path: Completed, one opencypher load, polled to done, every item written") {
    val files = Fx().render
    val r     = ready(
      files,
      FakeLoader.scripted(
        List(
          Right(BulkLoadStatus.Submitted),
          Right(BulkLoadStatus.InProgress(Some(5L))),
          Right(BulkLoadStatus.Completed(Some(42L))),
        )
      ),
    )
    val rep = r.run(params())
    assertEquals(rep.status, LiveLoadStatus.Completed, rep.detail)
    assertEquals(rep.exitCode, 0)
    assertEquals(r.loader.starts.size, 1)
    val req = r.loader.starts.head
    assertEquals(req.format, "opencypher")
    assertEquals(req.source, NeptuneSource)
    assertEquals(req.iamRoleArn, cfg.neptune.roleArn)
    assertEquals(req.s3BucketRegion, Region)
    assertEquals(req.mode, "AUTO")
    assertEquals(r.loader.statusCalls, 3, "status polled until the load is done")
    assertEquals(r.sleeper.sleeps, List(5000L, 5000L))
    allItems.foreach { i =>
      val got = r.kvInner.get(r.physical(i.table), KvKey(i.pk, i.sk))
      assertEquals(
        got.toOption.flatten.flatMap(_.attributes.get("payloadHash")),
        Some(i.payloadHash),
        s"${i.table}/${i.pk} not written",
      )
    }
    assertEquals(r.kv.puts.toLong, ItemTotal)

    val p = prov(rep)
    assertEquals(p("status").str, "completed")
    assertEquals(p("exitCode").num.toInt, 0)
    assertEquals(p("schema").str, LiveLoad.ProvenanceSchema)
    assertEquals(p("runId").str, RunId)
    assertEquals(p("codeVersion").str, CodeVersion)
    assertEquals(p("inputs")("loadRunId").str, "load:fixture-0001")
    assertEquals(p("inputs")("sparkRunId").str, "run:spark-fixture")
    val recorded = p("inputs")("files").arr.map(f => f("uri").str -> f("sha256").str).toMap
    val expected = files.map((path, b) =>
      val uri =
        if path.startsWith("neptune/") then NeptuneSource + path.stripPrefix("neptune/")
        else ItemsSource + path
      uri -> Cells.sha256(b)
    )
    assertEquals(recorded, expected, "provenance lists every input with its checksum")
    assertEquals(p("items")("total").num.toLong, ItemTotal)
    itemFiles.foreach { f =>
      assertEquals(p("items")("planned")(f.table).num.toLong, f.rows)
      assertEquals(p("items")("written")(f.table)("written").num.toLong, f.rows)
      assertEquals(p("items")("written")(f.table)("conflicts").num.toLong, 0L)
    }
    assertEquals(p("neptune")("loadId").str, FakeLoader.LoadId)
    assertEquals(p("neptune")("status").str, "completed")
    assertEquals(p("neptune")("processedRecords").num.toLong, 42L)
    assertEquals(p("neptune")("rowsPlanned").num.toLong, neptuneCsv.map(_._3).sum)
    assertEquals(p("parameters")("neptuneFormat").str, "opencypher")
    assertEquals(p("parameters")("neptuneSource").str, NeptuneSource)
    checkNoLeaks(rep)
  }

  test("1b. the provenance JSON is deterministic for identical inputs and a fixed clock") {
    val a = ready().run(params())
    val b = ready().run(params())
    assertEquals(a.provenanceJson, b.provenanceJson)
  }

  // ---- 2. idempotent rerun ---------------------------------------------------------------------

  test("2. a rerun against the same stores writes nothing: written=0, unchanged=N") {
    val first = ready()
    assertEquals(first.run(params()).status, LiveLoadStatus.Completed)
    val second = ready(kvInner = Some(first.kvInner))
    val rep    = second.run(params())
    assertEquals(rep.status, LiveLoadStatus.Completed, rep.detail)
    val p = prov(rep)
    itemFiles.foreach { f =>
      assertEquals(p("items")("written")(f.table)("written").num.toLong, 0L, f.table)
      assertEquals(p("items")("written")(f.table)("unchanged").num.toLong, f.rows, f.table)
    }
  }

  // ---- 3. dry run ------------------------------------------------------------------------------

  test("3. dry run: DryRunVerified, zero loader and key-value calls, provenance still produced") {
    val r   = ready()
    val rep = r.run(params(dry = true))
    assertEquals(rep.status, LiveLoadStatus.DryRunVerified, rep.detail)
    assertEquals(rep.exitCode, 0)
    noExternalCalls(r)
    assert(r.store.gets > 0, "a dry run still reads and verifies the sources")
    val p = prov(rep)
    assertEquals(p("status").str, "dry-run-verified")
    assertEquals(p("parameters")("dryRun").bool, true)
    assertEquals(p("items")("total").num.toLong, ItemTotal)
    assertEquals(p("items")("written").obj.size, 0)
    assertEquals(p("neptune")("status").str, "not-started")
    assert(p("inputs")("files").arr.nonEmpty)
    checkNoLeaks(rep)
  }

  test("3b. a dry run still refuses a corrupted source") {
    val files = Fx().render
    val r     = ready(files.updated(NeptuneKey, flip(files(NeptuneKey))))
    val rep   = r.run(params(dry = true))
    assertEquals(rep.status, LiveLoadStatus.ChecksumMismatch)
    noExternalCalls(r)
  }

  // ---- 4. checksum mismatch --------------------------------------------------------------------

  private def checksumMismatch(
      name: String
  )(mutate: Map[String, Array[Byte]] => Map[String, Array[Byte]]) =
    test(s"4. ChecksumMismatch (exit 3, no loader or KV call): $name") {
      val r   = ready(mutate(Fx().render))
      val rep = r.run(params())
      assertEquals(rep.status, LiveLoadStatus.ChecksumMismatch, rep.detail)
      assertEquals(rep.exitCode, 3)
      noExternalCalls(r)
      assertEquals(prov(rep)("status").str, "checksum-mismatch")
      checkNoLeaks(rep)
    }

  checksumMismatch("a CSV byte flipped")(f => f.updated(NeptuneKey, flip(f(NeptuneKey))))
  checksumMismatch("an items byte flipped")(f => f.updated(ItemsKey, flip(f(ItemsKey))))
  checksumMismatch("load-manifest.json altered")(f =>
    f.updated("load-manifest.json", f("load-manifest.json") ++ utf8(" "))
  )
  checksumMismatch("an embedding-input byte flipped")(f => f.updated(EmbedKey, flip(f(EmbedKey))))
  checksumMismatch("checksums.sha256 disagrees with the manifest for a CSV")(f =>
    withSums(f)(
      _.map(l => if l.endsWith("  " + NeptuneKey) then "0" * 64 + "  " + NeptuneKey else l)
    )
  )
  checksumMismatch("checksums.sha256 disagrees with the manifest for an items file")(f =>
    withSums(f)(_.map(l => if l.endsWith("  " + ItemsKey) then "0" * 64 + "  " + ItemsKey else l))
  )
  checksumMismatch("checksums.sha256 disagrees with the manifest for an embedding input")(f =>
    withSums(f)(_.map(l => if l.endsWith("  " + EmbedKey) then "0" * 64 + "  " + EmbedKey else l))
  )
  checksumMismatch("checksums.sha256 disagrees with the load manifest itself")(f =>
    withSums(f)(
      _.map(l =>
        if l.endsWith("  load-manifest.json") then "0" * 64 + "  load-manifest.json" else l
      )
    )
  )
  checksumMismatch("checksums.sha256 has no line for a CSV")(f =>
    withSums(f)(_.filterNot(_.endsWith("  " + NeptuneKey)))
  )
  checksumMismatch("checksums.sha256 has no line for an items file")(f =>
    withSums(f)(_.filterNot(_.endsWith("  " + ItemsKey)))
  )

  test(
    "4b. ChecksumMismatch: the manifest records a sha that differs from the file and checksums"
  ) {
    val files = Fx().withEdit { m =>
      m("neptune")("files")(0)("sha256") = "f" * 64
    }.render
    val r   = ready(files)
    val rep = r.run(params())
    assertEquals(rep.status, LiveLoadStatus.ChecksumMismatch, rep.detail)
    noExternalCalls(r)
  }

  // ---- 5. source invalid -----------------------------------------------------------------------

  private def sourceInvalid(name: String, fx: Fx = Fx())(
      mutate: Map[String, Array[Byte]] => Map[String, Array[Byte]] = identity
  ) =
    test(s"5. SourceInvalid (exit 3, no loader or KV call): $name") {
      val r   = ready(mutate(fx.render))
      val rep = r.run(params())
      assertEquals(rep.status, LiveLoadStatus.SourceInvalid, rep.detail)
      assertEquals(rep.exitCode, 3)
      noExternalCalls(r)
      assertEquals(prov(rep)("status").str, "source-invalid")
    }

  private def dropped(files: Map[String, Array[Byte]], path: String) = files - path

  sourceInvalid("checksums.sha256 is missing")(dropped(_, "checksums.sha256"))
  sourceInvalid("load-manifest.json is missing")(dropped(_, "load-manifest.json"))
  sourceInvalid("a CSV object is missing")(dropped(_, NeptuneKey))
  sourceInvalid("an items object is missing")(dropped(_, ItemsKey))
  sourceInvalid("an embedding-input object is missing")(dropped(_, EmbedKey))
  sourceInvalid("a duplicate checksum line (same sha)")(f =>
    withSums(f)(ls => ls :+ ls.find(_.endsWith("  " + NeptuneKey)).get)
  )
  sourceInvalid("a duplicate checksum line (different sha)")(f =>
    withSums(f)(ls => ls :+ ("0" * 64 + "  " + NeptuneKey))
  )
  sourceInvalid("a malformed checksum line")(f => withSums(f)(_ :+ "this is not a checksum line"))
  sourceInvalid("a checksum line with a short hash")(f => withSums(f)(_ :+ ("abc  some/file")))
  sourceInvalid("a checksum line with an uppercase hash")(f =>
    withSums(f)(_ :+ ("A" * 64 + "  some/file"))
  )
  sourceInvalid("load-manifest.json is not JSON (checksums consistent)")(f =>
    rewrite(f, "load-manifest.json", utf8("not json {"))
  )
  sourceInvalid("the loader field is not LocalLoad", Fx().withEdit(_("loader") = "other.Loader"))()
  sourceInvalid("the loader field is missing", Fx().withEdit(_.value.remove("loader")))()
  sourceInvalid(
    "an unknown table in the items list",
    Fx().withEdit { m =>
      m("dynamodb")("files")(0)("table") = "no-such-table"
    },
  )()
  sourceInvalid(
    "a bad items path in the manifest",
    Fx().withEdit { m =>
      m("dynamodb")("files")(0)("path") = "items/../x.jsonl"
    },
  )()
  sourceInvalid(
    "neptune invariants are not all zero",
    Fx().withEdit { m =>
      m("neptune")("invariants")("danglingEndpoints") = 1
    },
  )()
  sourceInvalid(
    "neptune invariants are missing",
    Fx().withEdit(_("neptune").obj.remove("invariants")),
  )()
  sourceInvalid(
    "neptune invariants are empty",
    Fx().withEdit(_("neptune")("invariants") = ujson.Obj()),
  )()
  sourceInvalid(
    "the manifest lists no neptune files",
    Fx().withEdit(_("neptune")("files") = ujson.Arr()),
  )()
  sourceInvalid(
    "a neptune path outside vertices/ and edges/",
    Fx().withEdit { m =>
      m("neptune")("files")(0)("path") = "neptune/other/X.csv"
    },
  )()
  sourceInvalid(
    "a neptune path with '..'",
    Fx().withEdit { m =>
      m("neptune")("files")(0)("path") = "neptune/vertices/../X.csv"
    },
  )()
  sourceInvalid("the manifest has no loadRunId", Fx().withEdit(_.value.remove("loadRunId")))()
  sourceInvalid(
    "a line-count mismatch (manifest rows differ from the items lines)",
    Fx().withEdit { m =>
      m("dynamodb")("files")(0)("rows") = m("dynamodb")("files")(0)("rows").num + 1
    },
  )()

  private def firstLinePayloadHash(bytes: Array[Byte]): Array[Byte] =
    val ls  = text(bytes).split("\n").toList
    val obj = ujson.read(ls.head).obj
    obj("payloadHash") = "0" * 64
    utf8((ujson.write(obj) :: ls.tail).map(_ + "\n").mkString)

  sourceInvalid(
    "a wrong payloadHash in a JSONL line (checksums consistent)",
    Fx(items =
      itemFiles
        .map(f => f.table -> f.bytes)
        .toMap
        .updated(
          "researcher-metrics",
          firstLinePayloadHash(itemFiles.head.bytes),
        )
    ),
  )()

  sourceInvalid(
    "edited attributes under a stale payloadHash (checksums consistent)",
    Fx(items =
      itemFiles
        .map(f => f.table -> f.bytes)
        .toMap
        .updated(
          "identity-clusters", {
            val f  = itemFiles.find(_.table == "identity-clusters").get
            val ls = text(f.bytes).split("\n").toList
            val o  = ujson.read(ls.head).obj
            o("attributes")("size") = 99
            utf8((ujson.write(o) :: ls.tail).map(_ + "\n").mkString)
          },
        )
    ),
  )()

  sourceInvalid(
    "an embedding-input line count differs from the manifest rows",
    Fx().withEdit { m =>
      m("embeddingInputs")("files")(0)("rows") = m("embeddingInputs")("files")(0)("rows").num + 1
    },
  )()

  sourceInvalid(
    "an embedding-input list with fewer than four tables",
    Fx(embeds = embedFiles - "venues"),
  )()

  test("5b. SourceInvalid: an extra object under the neptune prefix (Neptune would load it)") {
    val r = ready()
    r.store.put(
      ObjectLocation(Bucket, "graph/neptune-load/" + RunId + "/vertices/Extra.csv"),
      utf8(":ID,:LABEL\nx,Extra\n"),
      None,
      None,
    )
    val rep = r.run(params())
    assertEquals(rep.status, LiveLoadStatus.SourceInvalid, rep.detail)
    noExternalCalls(r)
  }

  test("5c. SourceInvalid: a stray non-CSV object directly under the neptune prefix") {
    val r = ready()
    r.store.put(
      ObjectLocation(Bucket, "graph/neptune-load/" + RunId + "/notes.txt"),
      utf8("x"),
      None,
      None,
    )
    assertEquals(r.run(params()).status, LiveLoadStatus.SourceInvalid)
    noExternalCalls(r)
  }

  test("5d. a CSV in the neptune prefix that the manifest lists but the store lacks is refused") {
    val files = Fx().render
    val r     = ready(files - NeptuneKey)
    assertEquals(r.run(params()).status, LiveLoadStatus.SourceInvalid)
    noExternalCalls(r)
  }

  // ---- 6. cap ----------------------------------------------------------------------------------

  test("6. above maxItems: CapExceeded, exit 3, zero loader and KV calls") {
    val r   = ready()
    val rep = r.run(params(maxItems = ItemTotal - 1))
    assertEquals(rep.status, LiveLoadStatus.CapExceeded, rep.detail)
    assertEquals(rep.exitCode, 3)
    noExternalCalls(r)
    assertEquals(prov(rep)("status").str, "cap-exceeded")
  }

  test("6b. the cap counts the total over all tables, not each table") {
    val biggestTable = itemFiles.map(_.rows).max
    assert(ItemTotal > biggestTable)
    val r = ready()
    assertEquals(r.run(params(maxItems = biggestTable)).status, LiveLoadStatus.CapExceeded)
    noExternalCalls(r)
  }

  test("6c. exactly at the cap is accepted and writes everything") {
    val r   = ready()
    val rep = r.run(params(maxItems = ItemTotal))
    assertEquals(rep.status, LiveLoadStatus.Completed, rep.detail)
    assertEquals(r.kv.puts.toLong, ItemTotal)
  }

  test("6d. a dry run above the cap is also refused") {
    val r = ready()
    assertEquals(r.run(params(maxItems = 1L, dry = true)).status, LiveLoadStatus.CapExceeded)
  }

  // ---- 7. usage invalid ------------------------------------------------------------------------

  private def usage(name: String, p: LiveLoadParams) =
    test(s"7. UsageInvalid (exit 2, nothing read, no calls): $name") {
      val r   = ready()
      val rep = r.run(p)
      assertEquals(rep.status, LiveLoadStatus.UsageInvalid, rep.detail)
      assertEquals(rep.exitCode, 2)
      assertEquals(r.store.gets, 0, "no object may be read for invalid usage")
      noExternalCalls(r)
      assertEquals(prov(rep)("status").str, "usage-invalid")
      checkNoLeaks(rep)
    }

  private val base = params()
  usage(
    "neptune source lacks the trailing '/'",
    base.copy(neptuneSource = NeptuneSource.dropRight(1)),
  )
  usage("items source lacks the trailing '/'", base.copy(itemsSource = ItemsSource.dropRight(1)))
  usage(
    "neptune source contains '..'",
    base.copy(neptuneSource = s"s3://$Bucket/graph/neptune-load/../$RunId/"),
  )
  usage(
    "items source contains '..'",
    base.copy(itemsSource = s"s3://$Bucket/graph/../local-load/$RunId/"),
  )
  usage(
    "neptune source last segment is not the run id",
    base.copy(neptuneSource = s"s3://$Bucket/graph/neptune-load/run-002/"),
  )
  usage(
    "items source last segment is not the run id",
    base.copy(itemsSource = s"s3://$Bucket/graph/local-load/run-002/"),
  )
  usage(
    "neptune source outside the configured prefix (path)",
    base.copy(neptuneSource = s"s3://$Bucket/other/$RunId/"),
  )
  usage(
    "neptune source outside the configured prefix (bucket)",
    base.copy(neptuneSource = s"s3://other-bucket/graph/neptune-load/$RunId/"),
  )
  usage("neptune source is not an s3 URI", base.copy(neptuneSource = "https://example.invalid/x/"))
  usage("run id with a space", base.copy(runId = "run 001"))
  usage("run id with a slash", base.copy(runId = "run/001"))
  usage("empty run id", base.copy(runId = ""))
  usage("run id longer than 128", base.copy(runId = "r" * 129))
  usage("empty code version", base.copy(codeVersion = ""))
  usage("code version with whitespace", base.copy(codeVersion = "v 1"))
  usage("code version longer than 64", base.copy(codeVersion = "v" * 65))
  usage("maxItems 0", base.copy(maxItems = 0L))
  usage("maxItems negative", base.copy(maxItems = -1L))
  usage("maxItems above the hard max", base.copy(maxItems = LiveLoad.HardMaxItems + 1))
  usage("timeout 0", base.copy(neptuneTimeoutSeconds = 0L))
  usage(
    "timeout above the hard max",
    base.copy(neptuneTimeoutSeconds = LiveLoad.HardMaxTimeoutSeconds + 1),
  )
  usage("poll interval 0", base.copy(pollIntervalSeconds = 0L))
  usage(
    "poll interval above the hard max",
    base.copy(pollIntervalSeconds = LiveLoad.HardMaxPollSeconds + 1),
  )

  test("7b. the hard maxima themselves are accepted") {
    val r = ready()
    val p = params(
      maxItems = LiveLoad.HardMaxItems,
      timeout = LiveLoad.HardMaxTimeoutSeconds,
      poll = LiveLoad.HardMaxPollSeconds,
      dry = true,
    )
    assertEquals(r.run(p).status, LiveLoadStatus.DryRunVerified)
    assertEquals(
      r.run(params(maxItems = 1L, timeout = 1L, poll = 1L, dry = true)).status,
      LiveLoadStatus.CapExceeded,
    )
  }

  // ---- 8. neptune outcomes ---------------------------------------------------------------------

  test("8. Neptune load failed: NeptuneFailed, exit 1, no DynamoDB write") {
    val r = ready(loader =
      FakeLoader.scripted(
        List(
          Right(BulkLoadStatus.InProgress(None)),
          Right(BulkLoadStatus.LoadFailed("LOAD_FAILED")),
        )
      )
    )
    val rep = r.run(params())
    assertEquals(rep.status, LiveLoadStatus.NeptuneFailed, rep.detail)
    assertEquals(rep.exitCode, 1)
    assertEquals(r.kv.calls, 0)
    assertEquals(r.loader.statusCalls, 2)
    val p = prov(rep)
    assertEquals(p("neptune")("status").str, "failed")
    assertEquals(p("neptune")("loadId").str, FakeLoader.LoadId)
  }

  test("8. Neptune start returns Left: NeptuneFailed, no polling, no DynamoDB write, not retried") {
    val loader = FakeLoader(
      Left(StorageError.Unavailable("boom")),
      List(Right(BulkLoadStatus.Completed(Some(1L)))),
    )
    val r   = ready(loader = loader)
    val rep = r.run(params())
    assertEquals(rep.status, LiveLoadStatus.NeptuneFailed, rep.detail)
    assertEquals(rep.exitCode, 1)
    assertEquals(r.loader.statusCalls, 0, "no polling after a failed start")
    assertEquals(r.loader.starts.size, 1, "a failed start must not be retried")
    assertEquals(r.kv.calls, 0)
    val p = prov(rep)
    assertEquals(p("neptune")("status").str, "start-failed")
    assert(p("neptune")("loadId").isNull)
  }

  test("8. Neptune load cancelled: NeptuneCancelled, exit 1, no DynamoDB write") {
    val r   = ready(loader = FakeLoader.scripted(List(Right(BulkLoadStatus.Cancelled))))
    val rep = r.run(params())
    assertEquals(rep.status, LiveLoadStatus.NeptuneCancelled, rep.detail)
    assertEquals(rep.exitCode, 1)
    assertEquals(r.kv.calls, 0)
    assertEquals(prov(rep)("neptune")("status").str, "cancelled")
  }

  test("8. Neptune timeout (fake clock): NeptuneTimeout, loadId in provenance, no DynamoDB write") {
    val r   = ready(loader = FakeLoader.scripted(List(Right(BulkLoadStatus.InProgress(Some(1L))))))
    val rep = r.run(params(timeout = 30L, poll = 10L))
    assertEquals(rep.status, LiveLoadStatus.NeptuneTimeout, rep.detail)
    assertEquals(rep.exitCode, 1)
    assertEquals(r.kv.calls, 0)
    assert(r.sleeper.sleeps.forall(_ == 10000L), r.sleeper.sleeps.toString)
    assert(r.sleeper.sleeps.size >= 2 && r.sleeper.sleeps.size <= 3, r.sleeper.sleeps.toString)
    assert(r.fakeClock.now - StartMillis >= 30000L, "the clock must have reached the deadline")
    assert(r.fakeClock.now - StartMillis <= 40000L, "the loop must stop at the deadline")
    val p = prov(rep)
    assertEquals(p("neptune")("loadId").str, FakeLoader.LoadId)
    assertEquals(p("neptune")("status").str, "timeout")
    assert(rep.detail.contains("left running"), rep.detail)
  }

  test("8. in progress, submitted, then completed: Completed after the right number of polls") {
    val r = ready(loader =
      FakeLoader.scripted(
        List(
          Right(BulkLoadStatus.InProgress(Some(3L))),
          Right(BulkLoadStatus.Submitted),
          Right(BulkLoadStatus.Completed(Some(99L))),
        )
      )
    )
    val rep = r.run(params(poll = 7L))
    assertEquals(rep.status, LiveLoadStatus.Completed, rep.detail)
    assertEquals(r.loader.statusCalls, 3)
    assertEquals(r.sleeper.sleeps, List(7000L, 7000L))
    assertEquals(prov(rep)("neptune")("processedRecords").num.toLong, 99L)
    assertEquals(r.kv.puts.toLong, ItemTotal)
  }

  test("8. a retryable status error then success: retried with the configured backoff, Completed") {
    val r = ready(loader =
      FakeLoader.scripted(
        List(Left(StorageError.Throttled("slow down")), Right(BulkLoadStatus.Completed(Some(5L))))
      )
    )
    val rep = r.run(params())
    assertEquals(rep.status, LiveLoadStatus.Completed, rep.detail)
    assertEquals(r.loader.statusCalls, 2)
    assertEquals(r.sleeper.sleeps, List(Retry.delayMillis(cfg.retry, 1)))
  }

  test("8. a non-retryable status error: StoreError, no retry, no DynamoDB write") {
    val r   = ready(loader = FakeLoader.scripted(List(Left(StorageError.Failed("denied")))))
    val rep = r.run(params())
    assertEquals(rep.status, LiveLoadStatus.StoreError, rep.detail)
    assertEquals(rep.exitCode, 1)
    assertEquals(r.loader.statusCalls, 1)
    assertEquals(r.kv.calls, 0)
    assertEquals(prov(rep)("neptune")("loadId").str, FakeLoader.LoadId)
  }

  test("8. retryable status errors that never stop: StoreError after the configured attempts") {
    val r   = ready(loader = FakeLoader.scripted(List(Left(StorageError.Unavailable("down")))))
    val rep = r.run(params())
    assertEquals(rep.status, LiveLoadStatus.StoreError, rep.detail)
    assertEquals(r.loader.statusCalls, cfg.retry.maxAttempts)
    assertEquals(r.kv.calls, 0)
  }

  // ---- 9. items conflict -----------------------------------------------------------------------

  test(
    "9. an existing item with different content: ItemsConflict, no overwrite, conflicts counted"
  ) {
    val kv     = freshKv()
    val repo   = AnalyticsRepository.fromConfig(kv, cfg.tables)
    val target = allItems.head
    val other  = """{"different":1}"""
    assert(
      repo.put(target.copy(attributesJson = other, payloadHash = Cells.sha256(other))).isRight
    )
    val r   = ready(kvInner = Some(kv))
    val rep = r.run(params())
    assertEquals(rep.status, LiveLoadStatus.ItemsConflict, rep.detail)
    assertEquals(rep.exitCode, 1)
    assertEquals(r.loader.starts.size, 1, "Neptune runs before the items are put")
    val stored = kv.get(r.physical(target.table), KvKey(target.pk, target.sk)).toOption.flatten.get
    assertEquals(stored.attributes("payload"), other, "the existing item was overwritten")
    val p         = prov(rep)
    val conflicts =
      p("items")("written").obj.values.map(_("conflicts").num.toLong).sum
    assertEquals(conflicts, 1L)
    assertEquals(
      p("items")("written").obj.values.map(_("written").num.toLong).sum,
      ItemTotal - 1,
      "the other items are still written",
    )
    assertEquals(p("status").str, "items-conflict")
  }

  // ---- 10. store errors ------------------------------------------------------------------------

  test("10. a non-retryable DynamoDB error: StoreError, exit 1, Neptune already completed") {
    val r   = ready(putError = Some(StorageError.Failed("denied")))
    val rep = r.run(params().copy(itemParallelism = 1))
    assertEquals(rep.status, LiveLoadStatus.StoreError, rep.detail)
    assertEquals(rep.exitCode, 1)
    assertEquals(r.kv.puts, 1, "a non-retryable error is not retried and stops the load")
    assertEquals(prov(rep)("neptune")("status").str, "completed")
    checkNoLeaks(rep)
  }

  test("10a. with parallel puts every worker may have one put in flight before the load stops") {
    val r   = ready(putError = Some(StorageError.Failed("denied")))
    val rep = r.run(params().copy(itemParallelism = 4))
    assertEquals(rep.status, LiveLoadStatus.StoreError, rep.detail)
    assert(r.kv.puts >= 1 && r.kv.puts <= 4, s"puts=${r.kv.puts}: not retried, at most 4 in flight")
  }

  test("10b. a retryable DynamoDB error that persists: StoreError after the configured attempts") {
    val r   = ready(putError = Some(StorageError.Throttled("slow")))
    val rep = r.run(params().copy(itemParallelism = 1))
    assertEquals(rep.status, LiveLoadStatus.StoreError, rep.detail)
    assertEquals(r.kv.puts, cfg.retry.maxAttempts)
  }

  test("10d. parallel puts with a persistent retryable error: each worker retries its own item") {
    val r   = ready(putError = Some(StorageError.Throttled("slow")))
    val rep = r.run(params().copy(itemParallelism = 4))
    assertEquals(rep.status, LiveLoadStatus.StoreError, rep.detail)
    assert(r.kv.puts >= cfg.retry.maxAttempts, s"puts=${r.kv.puts}")
    assert(r.kv.puts <= 4 * cfg.retry.maxAttempts, s"puts=${r.kv.puts}")
  }

  test("10c. an object-store read error is a StoreError, not a source error") {
    val failing = new MemObjectStore:
      override def get(location: ObjectLocation): StorageResult[ObjectData] =
        Left(StorageError.Failed("denied"))
    val c     = cfg
    val kv    = freshKv()
    val clock = new FakeClock
    val ports = LiveLoadPorts(
      failing,
      FakeLoader.completing(),
      new RecordingKv(kv),
      new RecordingSleeper(clock),
      clock.clock,
    )
    val rep = LiveLoad.run(ports, c, Region, params())
    assertEquals(rep.status, LiveLoadStatus.StoreError, rep.detail)
    assertEquals(rep.exitCode, 1)
  }
end LiveLoadSuite
