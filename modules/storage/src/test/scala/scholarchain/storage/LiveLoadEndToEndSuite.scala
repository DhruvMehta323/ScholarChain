package scholarchain.storage

import java.nio.file.Path

import scala.concurrent.duration.*

import scholarchain.core.*

import LiveLoadFx.{FakeLoader, RecordingKv, RecordingSleeper, FakeClock, Rig}
import LocalLoadFx.*

/** Phase 14 Part A (M13-2): the real `LocalLoad` output over the Phase 06 run, uploaded through a
  * `FileObjectStore` in the layout the operator uses, is accepted by `LiveLoad.run` with fakes.
  *   - neptune source: the `neptune/vertices/` and `neptune/edges/` files with `neptune/` stripped;
  *   - items source: `load-manifest.json`, `checksums.sha256`, `dynamodb/items/`,
  *     `embedding-inputs/`;
  *   - both prefixes end in `/<runId>/`.
  */
class LiveLoadEndToEndSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 20.minutes

  private val E2eRun   = "run-e2e-1"
  private val Bucket   = LiveLoadFx.Bucket
  private val NepSrc   = s"s3://$Bucket/graph/neptune-load/$E2eRun/"
  private val ItemsSrc = s"s3://$Bucket/graph/local-load/$E2eRun/"

  private def out: Path =
    assume(
      sourceRun.isDefined,
      "modules/spark/target/runs/sparkS1r2 is not present: run Phase 06 first",
    )
    assert(new1.result.isRight, new1.result.toString)
    new1.dir

  private def params(dry: Boolean = false, max: Long = LiveLoad.HardMaxItems) =
    LiveLoadParams(NepSrc, ItemsSrc, E2eRun, "0.14.0-test", max, 120L, 5L, dry)

  /** Uploads the agreed layout; nothing else of the output tree (no object-store/) is uploaded. */
  private def uploadTo(store: ObjectStore, d: Path): Unit =
    val agreed = (p: String) =>
      p == "load-manifest.json" || p == "checksums.sha256" || p.startsWith("neptune/vertices/") ||
        p.startsWith("neptune/edges/") || p.startsWith("dynamodb/items/") ||
        p.startsWith("embedding-inputs/")
    LiveLoadFx.upload(store, tree(d).filter((p, _) => agreed(p)), NepSrc, ItemsSrc)

  private def rig(
      store: ObjectStore,
      kv: InMemoryKeyValueStore,
      loader: FakeLoader,
  ): (LiveLoadPorts, RecordingKv, FakeLoader) =
    val clock = new FakeClock
    val rkv   = new RecordingKv(kv)
    (LiveLoadPorts(store, loader, rkv, new RecordingSleeper(clock), clock.clock), rkv, loader)

  private def fileStore(name: String): FileObjectStore = new FileObjectStore(scratch(name))

  test("19. the LocalLoad output uploaded in the agreed layout is accepted by LiveLoad.run") {
    val d     = out
    val cfg   = LiveLoadFx.cfg
    val store = fileStore("e2e-store")
    uploadTo(store, d)
    val kv              = LiveLoadFx.freshKv()
    val (ports, rkv, l) = rig(store, kv, FakeLoader.completing(1234L))
    val rep             = LiveLoad.run(ports, cfg, LiveLoadFx.Region, params())
    assertEquals(rep.status, LiveLoadStatus.Completed, rep.detail)
    assertEquals(rep.exitCode, 0)
    assertEquals(l.starts.size, 1)
    assertEquals(l.starts.head.source, NepSrc)
    assertEquals(l.starts.head.format, "opencypher")

    val man   = ujson.read(java.nio.file.Files.readAllBytes(d.resolve("load-manifest.json")))
    val files = man("dynamodb")("files").arr
    files.foreach { f =>
      val phys = cfg.tables.analyticsTable(f("table").str).get
      assertEquals(kv.snapshot(phys).size.toLong, f("rows").num.toLong, f("table").str)
    }
    val total = files.map(_("rows").num.toLong).sum
    assertEquals(rkv.puts.toLong, total)

    val p = ujson.read(rep.provenanceJson)
    assertEquals(p("inputs")("loadRunId").str, man("loadRunId").str)
    assertEquals(p("inputs")("sparkRunId").str, man("input")("sparkRunId").str)
    val expectedFiles =
      2 + man("neptune")("files").arr.size + files.size + man("embeddingInputs")("files").arr.size
    assertEquals(p("inputs")("files").arr.size, expectedFiles)
    assertEquals(p("items")("total").num.toLong, total)
    assertEquals(
      p("neptune")("rowsPlanned").num.toLong,
      man("neptune")("files").arr.map(_("rows").num.toLong).sum,
    )
    List("/Users", "/tmp", "/var/folders", cfg.neptune.roleArn).foreach(s =>
      assert(!rep.provenanceJson.contains(s), s)
    )
  }

  test("19. a second run over the same stores is idempotent (written=0)") {
    val d     = out
    val cfg   = LiveLoadFx.cfg
    val store = fileStore("e2e-store-2")
    uploadTo(store, d)
    val kv         = LiveLoadFx.freshKv()
    val (p1, _, _) = rig(store, kv, FakeLoader.completing())
    assertEquals(
      LiveLoad.run(p1, cfg, LiveLoadFx.Region, params()).status,
      LiveLoadStatus.Completed,
    )
    val (p2, _, _) = rig(store, kv, FakeLoader.completing())
    val rep        = LiveLoad.run(p2, cfg, LiveLoadFx.Region, params())
    assertEquals(rep.status, LiveLoadStatus.Completed, rep.detail)
    val p = ujson.read(rep.provenanceJson)
    assertEquals(p("items")("written").obj.values.map(_("written").num.toLong).sum, 0L)
    assertEquals(p("items")("written").obj.values.map(_("conflicts").num.toLong).sum, 0L)
  }

  test("19. dry run on the real output verifies it and makes no call") {
    val d     = out
    val store = fileStore("e2e-store-3")
    uploadTo(store, d)
    val (ports, rkv, l) = rig(store, LiveLoadFx.freshKv(), FakeLoader.completing())
    val rep             = LiveLoad.run(ports, LiveLoadFx.cfg, LiveLoadFx.Region, params(dry = true))
    assertEquals(rep.status, LiveLoadStatus.DryRunVerified, rep.detail)
    assertEquals(l.calls, 0)
    assertEquals(rkv.calls, 0)
  }

  test("19. verifyEmbeddingInputs accepts the real embedding-inputs and matches the local files") {
    val d     = out
    val store = fileStore("e2e-store-4")
    uploadTo(store, d)
    val (ports, _, _) = rig(store, LiveLoadFx.freshKv(), FakeLoader.completing())
    val got           = LiveLoad
      .verifyEmbeddingInputs(ports, LiveLoadFx.cfg, ItemsSrc)
      .fold(e => fail(e.toString), identity)
    assertEquals(got.map(_.table).sorted, LocalLoad.EmbeddingTables.sorted)
    got.foreach { f =>
      val local = java.nio.file.Files.readAllBytes(d.resolve(s"embedding-inputs/${f.table}.jsonl"))
      assert(java.util.Arrays.equals(f.bytes, local), f.table)
    }
  }

  test("19. a byte flipped in the uploaded embedding input or CSV is refused before any call") {
    val d     = out
    val store = fileStore("e2e-store-5")
    uploadTo(store, d)
    val loc  = ObjectLocation(Bucket, s"graph/local-load/$E2eRun/embedding-inputs/works.jsonl")
    val good = store.get(loc).fold(e => fail(e.toString), _.bytes)
    store.put(loc, LiveLoadFx.flip(good)).fold(e => fail(e.toString), identity)
    val (ports, rkv, l) = rig(store, LiveLoadFx.freshKv(), FakeLoader.completing())
    val rep             = LiveLoad.run(ports, LiveLoadFx.cfg, LiveLoadFx.Region, params())
    assertEquals(rep.status, LiveLoadStatus.ChecksumMismatch, rep.detail)
    assertEquals((l.calls, rkv.calls), (0, 0))
  }
end LiveLoadEndToEndSuite
