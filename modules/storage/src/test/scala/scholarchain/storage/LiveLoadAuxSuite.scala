package scholarchain.storage

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

import scholarchain.core.*

import software.amazon.awssdk.auth.credentials.{AwsCredentials, AwsCredentialsProvider}
import software.amazon.awssdk.regions.Region

import LiveLoadFx.*

/** Phase 14 Part A (M13-2): `LiveLoad.verifyEmbeddingInputs`, `writeProvenance`, `awsPorts`. */
class LiveLoadAuxSuite extends munit.FunSuite:

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)

  private def ready(files: Map[String, Array[Byte]]): Rig =
    val r = rig()
    upload(r.store, files)
    r

  private def verify(r: Rig, source: String = ItemsSource) =
    LiveLoad.verifyEmbeddingInputs(r.ports, cfg, source)

  // ---- 11. verifyEmbeddingInputs ---------------------------------------------------------------

  test("11. verifyEmbeddingInputs: success returns the four verified files with their bytes") {
    val r   = ready(Fx().render)
    val out = verify(r).fold(e => fail(e.toString), identity)
    assertEquals(out.map(_.table).sorted, LocalLoad.EmbeddingTables.sorted)
    out.foreach { f =>
      assertEquals(f.sha256, Cells.sha256(embedFiles(f.table)))
      assertEquals(f.rows, 1L)
      assertEquals(f.uri, ItemsSource + s"embedding-inputs/${f.table}.jsonl")
      assert(java.util.Arrays.equals(f.bytes, embedFiles(f.table)), f.table)
    }
    assertEquals(r.loader.calls, 0)
    assertEquals(r.kv.calls, 0)
  }

  test("11. verifyEmbeddingInputs: a flipped byte is a ChecksumMismatch") {
    val files = Fx().render
    val r     = ready(
      files.updated("embedding-inputs/venues.jsonl", flip(files("embedding-inputs/venues.jsonl")))
    )
    assertEquals(verify(r).left.map(_._1), Left(LiveLoadStatus.ChecksumMismatch))
  }

  test("11. verifyEmbeddingInputs: a manifest without the embeddingInputs key is a SourceInvalid") {
    val r = ready(Fx(withEmbeds = false).render)
    assertEquals(verify(r).left.map(_._1), Left(LiveLoadStatus.SourceInvalid))
  }

  test("11. verifyEmbeddingInputs: fewer than four tables is a SourceInvalid") {
    val r = ready(Fx(embeds = embedFiles - "topics").render)
    assertEquals(verify(r).left.map(_._1), Left(LiveLoadStatus.SourceInvalid))
  }

  test("11. verifyEmbeddingInputs: an unknown fifth table is a SourceInvalid") {
    val r = ready(Fx(embeds = embedFiles + ("bogus" -> utf8("{}\n"))).render)
    assertEquals(verify(r).left.map(_._1), Left(LiveLoadStatus.SourceInvalid))
  }

  test("11. verifyEmbeddingInputs: a wrong line count is a SourceInvalid") {
    val fx = Fx().withEdit { m =>
      m("embeddingInputs")("files")(2)("rows") = 5
    }
    assertEquals(verify(ready(fx.render)).left.map(_._1), Left(LiveLoadStatus.SourceInvalid))
  }

  test("11. verifyEmbeddingInputs: a missing object is a SourceInvalid") {
    val r = ready(Fx().render - "embedding-inputs/works.jsonl")
    assertEquals(verify(r).left.map(_._1), Left(LiveLoadStatus.SourceInvalid))
  }

  test("11. verifyEmbeddingInputs: missing checksums.sha256 is a SourceInvalid") {
    val r = ready(Fx().render - "checksums.sha256")
    assertEquals(verify(r).left.map(_._1), Left(LiveLoadStatus.SourceInvalid))
  }

  test(
    "11. verifyEmbeddingInputs: a manifest that differs from checksums.sha256 is a ChecksumMismatch"
  ) {
    val files = Fx().render
    val r     = ready(files.updated("load-manifest.json", files("load-manifest.json") ++ utf8(" ")))
    assertEquals(verify(r).left.map(_._1), Left(LiveLoadStatus.ChecksumMismatch))
  }

  test("11. verifyEmbeddingInputs: an items source without the trailing '/' is a UsageInvalid") {
    val r = ready(Fx().render)
    assertEquals(
      verify(r, ItemsSource.dropRight(1)).left.map(_._1),
      Left(LiveLoadStatus.UsageInvalid),
    )
    assertEquals(verify(r, "not a uri/").left.map(_._1), Left(LiveLoadStatus.UsageInvalid))
  }

  // ---- 12. writeProvenance ---------------------------------------------------------------------

  test("12. writeProvenance writes once; a second write is AlreadyExists and changes nothing") {
    val r = ready(Fx().render)
    val a = r.run(params(dry = true))
    val b = r.run(params(dry = true, maxItems = 5L)) // different provenance text
    assertNotEquals(a.provenanceJson, b.provenanceJson)
    val loc = ObjectLocation(Bucket, "ops/provenance/run-001.json")
    assert(LiveLoad.writeProvenance(r.store, loc, a).isRight)
    val second = LiveLoad.writeProvenance(r.store, loc, b)
    assert(second.left.exists(_.isInstanceOf[StorageError.AlreadyExists]), second.toString)
    val stored = r.store.get(loc).fold(e => fail(e.toString), identity)
    assertEquals(new String(stored.bytes, StandardCharsets.UTF_8), a.provenanceJson)
    assertEquals(second.left.map(_.code), Left("already-exists"))
  }

  test("12b. writeProvenance of the same report twice is also AlreadyExists (never overwrites)") {
    val r   = ready(Fx().render)
    val a   = r.run(params(dry = true))
    val loc = ObjectLocation(Bucket, "ops/provenance/again.json")
    assert(LiveLoad.writeProvenance(r.store, loc, a).isRight)
    assert(
      LiveLoad
        .writeProvenance(r.store, loc, a)
        .left
        .exists(_.isInstanceOf[StorageError.AlreadyExists])
    )
  }

  // ---- 13. awsPorts ----------------------------------------------------------------------------

  test("13. awsPorts without a Neptune endpoint is a Left and resolves no credential") {
    val calls    = AtomicInteger(0)
    val provider = new AwsCredentialsProvider:
      def resolveCredentials(): AwsCredentials =
        calls.incrementAndGet()
        throw IllegalStateException("credentials must not be resolved offline")
    val clients = new AwsClients(provider, Region.US_EAST_1, None)
    val ports   = LiveLoad.awsPorts(clients)
    assert(ports.left.exists(_.isInstanceOf[StorageError.InvalidRequest]), ports.toString)
    assertEquals(calls.get(), 0)
  }
end LiveLoadAuxSuite
