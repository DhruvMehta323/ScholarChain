package scholarchain.storage

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scholarchain.core.*

import StorageFx.*

/** The filesystem fake of the ordinary S3 store: conditional put, hashes, key safety. */
class FileObjectStoreSuite extends munit.FunSuite:

  private final class Env(val root: Path, val store: FileObjectStore)

  private val env = FunFixture[Env](
    setup = { _ =>
      val dir = newTempDir("sc-obj")
      Env(dir, FileObjectStore(dir))
    },
    teardown = e => deleteTree(e.root),
  )

  private def bytes(s: String)                     = s.getBytes(StandardCharsets.UTF_8)
  private def loc(key: String, b: String = bucket) = ObjectLocation(b, key)
  private def okv[A](r: StorageResult[A]): A       = r.fold(e => fail(e.toString), identity)

  private def fileCount(root: Path): Long =
    val s = Files.walk(root)
    try s.filter(Files.isRegularFile(_)).count()
    finally s.close()

  env.test("put then get round trips the bytes and records the SHA-256 and content type") { e =>
    val l    = loc("graph/a/run=x/file.txt")
    val meta = okv(e.store.put(l, bytes("hello"), Some("text/plain")))
    assertEquals(meta.contentHash, Hashing.textHash("hello"))
    assertEquals(meta.sizeBytes, 5L)
    val got = okv(e.store.get(l))
    assertEquals(new String(got.bytes, StandardCharsets.UTF_8), "hello")
    assertEquals(got.meta.contentHash, Hashing.textHash("hello"))
    assertEquals(got.meta.contentType, Some("text/plain"))
    assertEquals(okv(e.store.head(l)).map(_.sizeBytes), Some(5L))
  }

  env.test("putIfAbsent writes once; a second call is AlreadyExists and the original bytes stay") {
    e =>
      val l = loc("raw/x.txt")
      assert(e.store.putIfAbsent(l, bytes("one")).isRight)
      assertEquals(e.store.putIfAbsent(l, bytes("two")), Left(StorageError.AlreadyExists("object")))
      assertEquals(new String(okv(e.store.get(l)).bytes, StandardCharsets.UTF_8), "one")
  }

  env.test("a hash mismatch stores nothing, for put and for putIfAbsent") { e =>
    val l   = loc("raw/h.txt")
    val bad = Some("0" * 64)
    assert(
      e.store
        .put(l, bytes("data"), None, bad)
        .left
        .exists(_.isInstanceOf[StorageError.IntegrityMismatch])
    )
    assert(
      e.store
        .putIfAbsent(l, bytes("data"), None, bad)
        .left
        .exists(_.isInstanceOf[StorageError.IntegrityMismatch])
    )
    assertEquals(okv(e.store.head(l)), None)
    assertEquals(fileCount(e.root), 0L)
    val good = Some(Hashing.textHash("data"))
    assert(e.store.put(l, bytes("data"), None, good).isRight)
    assertEquals(okv(e.store.head(l)).map(_.contentHash), good)
  }

  env.test("a hash mismatch on an existing key does not damage the stored object") { e =>
    val l = loc("raw/keep.txt")
    okv(e.store.put(l, bytes("original")))
    assert(e.store.put(l, bytes("changed"), None, Some("1" * 64)).isLeft)
    assertEquals(new String(okv(e.store.get(l)).bytes, StandardCharsets.UTF_8), "original")
  }

  env.test("path traversal and unsafe keys are rejected and nothing is written") { e =>
    val keys = List(
      "../escape.txt",
      "a/../../escape.txt",
      "a/./b",
      "/absolute",
      "a\\b",
      "a//b",
      "a/b/",
      "",
      "..",
      "a\u0000b",
      "a\nb",
    )
    keys.foreach { k =>
      assert(
        e.store.put(loc(k), bytes("x")).left.exists(_.isInstanceOf[StorageError.InvalidRequest]),
        s"put $k",
      )
      assert(e.store.putIfAbsent(loc(k), bytes("x")).isLeft, s"putIfAbsent $k")
      assert(e.store.get(loc(k)).isLeft, s"get $k")
      assert(e.store.head(loc(k)).isLeft, s"head $k")
    }
    assertEquals(fileCount(e.root), 0L)
    assert(!Files.exists(e.root.resolveSibling("escape.txt")))
    assert(!Files.exists(e.root.getParent.resolve("escape.txt")))
  }

  env.test("an invalid bucket name and an over-long key are rejected") { e =>
    List("UPPER", "a", "-bad-", "has space", "../x", "").foreach(b =>
      assert(e.store.put(loc("k.txt", b), bytes("x")).isLeft, s"bucket '$b'")
    )
    assert(e.store.put(loc("k" * 1025), bytes("x")).isLeft)
    val segmented = List.fill(2)("k" * 200).mkString("/") // 401 bytes, short segments
    assert(e.store.put(loc(segmented), bytes("x")).isRight)
    assertEquals(ObjectStores.validKey("ok/key"), true)
    assertEquals(ObjectStores.validBucket("example-bucket"), true)
  }

  private def invalidNonRetryable(r: StorageResult[?]): Boolean = r match
    case Left(e: StorageError.InvalidRequest) => !e.retryable
    case _                                    => false

  // ---- M1: keys the fake cannot store are rejected up front -----------------------------------

  env.test(
    "a path segment over 240 UTF-8 bytes is rejected up front as a non-retryable InvalidRequest (put and putIfAbsent)"
  ) { e =>
    val k = "graph/" + "k" * 300
    assertEquals(ObjectStores.MaxSegmentBytes, 240)
    assertEquals(ObjectStores.validKey(k), false)
    val a = e.store.put(loc(k), bytes("x"))
    val b = e.store.putIfAbsent(loc(k), bytes("x"))
    assert(invalidNonRetryable(a), a.toString)
    assert(invalidNonRetryable(b), b.toString)
    assertEquals(fileCount(e.root), 0L)
  }

  env.test("segment lengths 241, 250, 252 and 255 (the old partial-write range) are all rejected") {
    e =>
      List(241, 250, 252, 255, 256).foreach { n =>
        val r = e.store.put(loc("g/" + "k" * n), bytes("x"))
        assert(invalidNonRetryable(r), s"$n: $r")
      }
      assertEquals(fileCount(e.root), 0L, "a rejected key must leave no data file and no sidecar")
  }

  env.test("a 240-byte segment is storable and round trips; the sidecar fits the file system") {
    e =>
      val k = "graph/" + "k" * 240
      assert(ObjectStores.validKey(k))
      val l = loc(k)
      okv(e.store.put(l, bytes("payload"), Some("text/plain")))
      assertEquals(new String(okv(e.store.get(l)).bytes, StandardCharsets.UTF_8), "payload")
      assertEquals(okv(e.store.get(l)).meta.contentType, Some("text/plain"))
      assertEquals(fileCount(e.root), 2L) // data file + sidecar, nothing else
  }

  env.test("the 240-byte limit counts UTF-8 bytes, not characters") { e =>
    assert(ObjectStores.validKey("a/" + "\u00e9" * 120))  // 240 bytes
    assert(!ObjectStores.validKey("a/" + "\u00e9" * 121)) // 242 bytes
    val r = e.store.put(loc("a/" + "\u00e9" * 121), bytes("x"))
    assert(invalidNonRetryable(r), r.toString)
    assert(e.store.put(loc("a/" + "\u00e9" * 120), bytes("x")).isRight)
  }

  test("checkLocation (shared by the S3 adapter) applies the same segment limit") {
    assert(ObjectStores.checkLocation(ObjectLocation(bucket, "g/" + "k" * 241)).isLeft)
    assert(ObjectStores.checkLocation(ObjectLocation(bucket, "g/" + "k" * 240)).isRight)
    assert(
      invalidNonRetryable(ObjectStores.checkLocation(ObjectLocation(bucket, "g/" + "k" * 300)))
    )
  }

  env.test("a key that is a prefix of a stored key is rejected and nothing is written") { e =>
    okv(e.store.put(loc("a/b/c"), bytes("deep")))
    val before = fileCount(e.root)
    val r1     = e.store.put(loc("a/b"), bytes("shallow"))
    val r2     = e.store.putIfAbsent(loc("a"), bytes("shallow"))
    assert(invalidNonRetryable(r1), r1.toString)
    assert(invalidNonRetryable(r2), r2.toString)
    assertEquals(fileCount(e.root), before)
    assertEquals(new String(okv(e.store.get(loc("a/b/c"))).bytes, StandardCharsets.UTF_8), "deep")
  }

  env.test("a key that extends a stored key (stored key is its prefix) is rejected too") { e =>
    okv(e.store.put(loc("x/y"), bytes("leaf")))
    val before = fileCount(e.root)
    val r      = e.store.put(loc("x/y/z"), bytes("child"))
    assert(invalidNonRetryable(r), r.toString)
    assertEquals(fileCount(e.root), before)
    assertEquals(new String(okv(e.store.get(loc("x/y"))).bytes, StandardCharsets.UTF_8), "leaf")
  }

  /** Makes the sidecar directory read-only so that the sidecar cannot be written; skipped (with a
    * message) when the process can write anyway (for example when it runs as root).
    */
  private def withReadOnlySidecarDir(e: Env, dirKey: String)(body: => Unit): Unit =
    val metaDir = e.root.resolve("meta").resolve(bucket).resolve(dirKey)
    Files.createDirectories(metaDir)
    import java.nio.file.attribute.PosixFilePermissions as perms
    try
      Files.setPosixFilePermissions(metaDir, perms.fromString("r-x------"))
      assume(!Files.isWritable(metaDir), "the sidecar directory is writable (running as root?)")
      body
    finally Files.setPosixFilePermissions(metaDir, perms.fromString("rwx------"))

  env.test(
    "a failed sidecar write leaves no data file, no sidecar and no temp file, and is not retryable"
  ) { e =>
    withReadOnlySidecarDir(e, "w") {
      val r = e.store.put(loc("w/obj.txt"), bytes("data"))
      assert(r.isLeft, r.toString)
      assert(!r.left.exists(_.retryable), s"a local I/O failure must not be retryable: $r")
      assert(!r.left.exists(_.isInstanceOf[StorageError.Unavailable]), r.toString)
      assertEquals(okv(e.store.head(loc("w/obj.txt"))), None)
    }
    val s     = Files.walk(e.root.resolve("data"))
    val files =
      try s.filter(Files.isRegularFile(_)).count()
      finally s.close()
    assertEquals(files, 0L, "no data file or temp file may remain after a failed write")
    assertEquals(fileCount(e.root.resolve("meta")), 0L, "no sidecar may remain")
  }

  env.test("a failed putIfAbsent leaves nothing behind, so the retry after the fault succeeds") {
    e =>
      withReadOnlySidecarDir(e, "w") {
        val r = e.store.putIfAbsent(loc("w/obj.txt"), bytes("data"))
        assert(r.isLeft && !r.left.exists(_.retryable), r.toString)
        assertEquals(okv(e.store.head(loc("w/obj.txt"))), None)
      }
      // the fault is gone: the same call now creates the object (it would be AlreadyExists if the
      // failed call had left the data file behind)
      assertEquals(okv(e.store.putIfAbsent(loc("w/obj.txt"), bytes("data"))).sizeBytes, 4L)
  }

  env.test("a non-NotFound IOException on read is a non-retryable Failed, not Unavailable") { e =>
    okv(e.store.put(loc("d/f"), bytes("x")))
    val r = e.store.get(loc("d")) // "d" is a directory: readAllBytes raises an IOException
    r match
      case Left(err) =>
        assert(!err.retryable, s"must not be retryable: $err")
        assert(!err.isInstanceOf[StorageError.Unavailable], err.toString)
        assert(!err.isInstanceOf[StorageError.NotFound], err.toString)
      case Right(_) => fail("reading a directory must fail")
  }

  env.test("a missing object stays NotFound (the mapping change must not swallow it)") { e =>
    assert(e.store.get(loc("nope/x")).left.exists(_.isInstanceOf[StorageError.NotFound]))
  }

  env.test(
    "putIdempotent: same bytes are a no-op, different bytes are IntegrityMismatch and never overwrite"
  ) { e =>
    val l = loc("graph/idem.txt")
    val a = okv(ObjectStores.putIdempotent(e.store, l, bytes("v1")))
    val b = okv(ObjectStores.putIdempotent(e.store, l, bytes("v1")))
    assertEquals(a.contentHash, b.contentHash)
    val c = ObjectStores.putIdempotent(e.store, l, bytes("v2"))
    assert(c.left.exists(_.isInstanceOf[StorageError.IntegrityMismatch]), c.toString)
    assertEquals(new String(okv(e.store.get(l)).bytes, StandardCharsets.UTF_8), "v1")
  }

  env.test("get on a missing object is NotFound and head on a missing object is Right(None)") { e =>
    assert(e.store.get(loc("nope/x")).left.exists(_.isInstanceOf[StorageError.NotFound]))
    assertEquals(e.store.head(loc("nope/x")), Right(None))
  }

  env.test(
    "the hash is recomputed from the bytes: an object changed behind the store's back is visible"
  ) { e =>
    val l = loc("graph/tamper.txt")
    okv(e.store.put(l, bytes("clean")))
    Files.write(e.root.resolve("data").resolve(bucket).resolve(l.objectKey), bytes("dirty"))
    assertEquals(okv(e.store.head(l)).map(_.contentHash), Some(Hashing.textHash("dirty")))
    assertEquals(okv(e.store.get(l)).meta.contentHash, Hashing.textHash("dirty"))
  }

  env.test(
    "list: ascending key order, prefix filter, pagination with a token, limit must be >= 1"
  ) { e =>
    List("p/b", "p/a", "p/c", "q/a").foreach(k => okv(e.store.put(loc(k), bytes(k))))
    val p1 = okv(e.store.list(bucket, "p/", 2))
    assertEquals(p1.objects.map(_.location.objectKey), List("p/a", "p/b"))
    val p2 = okv(e.store.list(bucket, "p/", 2, p1.nextToken))
    assertEquals((p2.objects.map(_.location.objectKey), p2.nextToken), (List("p/c"), None))
    assertEquals(okv(e.store.list(bucket, "", 10)).objects.size, 4)
    assertEquals(okv(e.store.list(bucket, "zzz/", 10)).objects, Nil)
    assertEquals(okv(e.store.list("other-bucket", "", 10)).objects, Nil)
    assert(e.store.list(bucket, "p/", 0).isLeft)
    assert(e.store.list(bucket, "../", 5).isLeft)
    assert(e.store.list(bucket, "/", 5).isLeft)
  }

  test("versionedKey puts the run ID in the key and rejects an invalid run ID or file name") {
    val hex = run1.stripPrefix("run:")
    assertEquals(
      ObjectStores.versionedKey("graph/", "neptune", run1, "a.csv"),
      Right(s"graph/neptune/run=$hex/a.csv"),
    )
    assertNotEquals(
      ObjectStores.versionedKey("graph/", "neptune", run1, "a.csv"),
      ObjectStores.versionedKey("graph/", "neptune", runIdFor(attempt = 2), "a.csv"),
    )
    assert(ObjectStores.versionedKey("graph/", "neptune", "run:bad", "a.csv").isLeft)
    assert(ObjectStores.versionedKey("graph/", "neptune", run1, "../a.csv").isLeft)
    assert(ObjectStores.versionedKey("graph/", "neptune", run1, "").isLeft)
  }

  env.test(
    "run manifest: files land under run-versioned keys, republishing is a no-op, changed bytes conflict"
  ) { e =>
    val input = ObjectRunManifestInput(
      run1,
      "storage-load",
      "0.1.0",
      Map("p" -> "1"),
      List(StorageFx.input),
      List("a.csv" -> bytes("A"), "b.csv" -> bytes("B")),
    )
    val m1 = okv(ObjectRunManifest.publish(e.store, bucket, "graph/", "neptune", input))
    assert(m1.objectKey.endsWith("/manifest.json"))
    assert(m1.objectKey.contains(s"run=${run1.stripPrefix("run:")}"))
    val before = fileCount(e.root)
    val m2     = okv(ObjectRunManifest.publish(e.store, bucket, "graph/", "neptune", input))
    assertEquals(m2, m1)
    assertEquals(fileCount(e.root), before)
    val manifest = ujson.read(new String(okv(e.store.get(m1)).bytes, StandardCharsets.UTF_8))
    assertEquals(manifest("runId").str, run1)
    assertEquals(
      manifest("artifacts").arr.map(_("sha256").str).toSet,
      Set(Hashing.textHash("A"), Hashing.textHash("B")),
    )
    assertEquals(manifest("inputs")(0)("checksum").str, hexA)
    val changed = input.copy(files = List("a.csv" -> bytes("A2"), "b.csv" -> bytes("B")))
    val out     = ObjectRunManifest.publish(e.store, bucket, "graph/", "neptune", changed)
    assert(out.left.exists(_.isInstanceOf[StorageError.IntegrityMismatch]), out.toString)
  }

  test("the store has no delete operation (the port exposes none)") {
    val names = classOf[ObjectStore].getMethods.map(_.getName).toSet
    assertEquals(
      names,
      Set("put", "putIfAbsent", "get", "head", "list") ++ names.filter(_.contains("$default$")),
    )
  }
