package scholarchain.storage

import scholarchain.core.*

/** Live: one bounded S3 put and get (64 bytes) under the configured logs prefix of
  * `SCHOLARCHAIN_IT_S3_BUCKET`. Run only with SCHOLARCHAIN_AWS_IT=1 in an approved environment.
  */
class S3ObjectStoreAwsItSuite extends munit.FunSuite:
  override def munitIgnore: Boolean = !AwsItSupport.enabled

  test("put, idempotent re-put and get of a 64-byte object") {
    val clients = AwsItSupport.clients()
    val store   = clients.objectStore
    val prefix  = AwsItSupport.config.value.s3.logs
    val bucket  = AwsItSupport.env("SCHOLARCHAIN_IT_S3_BUCKET")
    val bytes   = ("scholarchain-it-probe-" + "x" * 42).getBytes("UTF-8")
    val key     = s"${prefix}it-probe/${Hashing.sha256Hex(bytes)}.txt"
    val loc     = ObjectLocation(bucket, key)
    val first   = ObjectStores.putIdempotent(store, loc, bytes)
    assert(first.isRight, first.toString)
    val again = ObjectStores.putIdempotent(store, loc, bytes)
    assertEquals(again.map(_.contentHash), first.map(_.contentHash))
    val got = store.get(loc)
    assert(got.isRight, got.toString)
    assertEquals(got.toOption.get.meta.contentHash, Hashing.sha256Hex(bytes))
    assert(java.util.Arrays.equals(got.toOption.get.bytes, bytes))
  }
