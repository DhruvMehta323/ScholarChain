package scholarchain.storage

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{
  FileAlreadyExistsException,
  Files,
  NoSuchFileException,
  Path,
  StandardCopyOption,
  StandardOpenOption,
}
import java.util.Base64

import scala.jdk.CollectionConverters.*

import scholarchain.core.*

import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  HeadObjectRequest,
  ListObjectsV2Request,
  NoSuchKeyException,
  PutObjectRequest,
  S3Exception,
}

/** Object key layout and helpers shared by the S3 adapter and its fake. */
object ObjectStores:
  /** Longest path segment (UTF-8 bytes) a key may have. Local file systems limit a file name to 255
    * bytes; the fake stores the sidecar as `<segment>.json`, so the limit is 255 - 5 - margin. It
    * is part of the shared key contract (S3 itself would accept longer segments), so a key the fake
    * cannot store is rejected up front as `InvalidRequest` by both adapters.
    */
  val MaxSegmentBytes: Int = 240

  /** Valid object names: non-empty, at most 1024 UTF-8 bytes, no leading `/`, no `\`, no control
    * characters, no `.` or `..` or empty path segments (so a key can never leave the fake's root)
    * and no segment longer than `MaxSegmentBytes` UTF-8 bytes.
    */
  def validKey(key: String): Boolean =
    key.nonEmpty && key.getBytes(StandardCharsets.UTF_8).length <= 1024 &&
      !key.startsWith("/") && !key.contains('\\') && !key.exists(_.isControl) &&
      !key
        .split("/", -1)
        .exists(s =>
          s == "." || s == ".." || s.isEmpty ||
            s.getBytes(StandardCharsets.UTF_8).length > MaxSegmentBytes
        )

  def validBucket(b: String): Boolean = b.matches("^[a-z0-9][a-z0-9.\\-]{1,61}[a-z0-9]$")

  def checkLocation(l: ObjectLocation): Either[StorageError, ObjectLocation] =
    if validBucket(l.bucket) && validKey(l.objectKey) then Right(l)
    else Left(StorageError.InvalidRequest("invalid bucket or object key"))

  /** Versioned key: `<prefix><logicalName>/run=<runId hex>/<fileName>`. The run ID (a core run ID,
    * `run:<64 hex>`) is part of the key, so two runs never overwrite each other and the key of an
    * artifact names the run that produced it.
    */
  def versionedKey(
      prefix: String,
      logicalName: String,
      runId: String,
      fileName: String,
  ): Either[StorageError, String] =
    val hex = runId.stripPrefix("run:")
    if Ids.checkRunId(runId).isLeft then Left(StorageError.InvalidRequest("invalid run ID"))
    else
      val key = s"$prefix$logicalName/run=$hex/$fileName"
      if validKey(key) then Right(key) else Left(StorageError.InvalidRequest("invalid object key"))

  /** Idempotent write: `putIfAbsent`; when the object exists with the SAME SHA-256 the call is a
    * no-op returning the stored metadata; with a different hash it is `IntegrityMismatch` (a
    * conflict, never an overwrite).
    */
  def putIdempotent(
      store: ObjectStore,
      location: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String] = None,
  ): StorageResult[ObjectMeta] =
    store.putIfAbsent(location, bytes, contentType) match
      case Left(StorageError.AlreadyExists(_)) =>
        store.head(location).flatMap {
          case Some(m) if m.contentHash == Hashing.sha256Hex(bytes) => Right(m)
          case Some(_)                                              =>
            Left(StorageError.IntegrityMismatch("object exists with different content"))
          case None => Left(StorageError.Failed("object vanished during an idempotent write"))
        }
      case other => other

/** One artifact of a run manifest: where it is, what it hashes to, how big it is. */
final case class ManifestArtifact(key: String, sha256: String, sizeBytes: Long)

/** Run manifest of an object-store load: the inputs (with versions and checksums), the code
  * version, the parameters and the produced artifacts. Serialized with sorted keys, no timestamps.
  */
final case class ObjectRunManifest(
    runId: String,
    stage: String,
    codeVersion: String,
    parameters: Map[String, String],
    inputs: List[InputRef],
    artifacts: List[ManifestArtifact],
):
  def toJson: String =
    val o = ujson.Obj()
    o("artifacts") = ujson.Arr.from(
      artifacts
        .sortBy(_.key)
        .map(a =>
          ujson.Obj("key" -> a.key, "sha256" -> a.sha256, "sizeBytes" -> a.sizeBytes.toDouble)
        )
    )
    o("codeVersion") = codeVersion
    o("inputs") = ujson.Arr.from(
      inputs
        .sortBy(i => (i.inputId, i.version))
        .map(i =>
          ujson.Obj(
            "checksum" -> i.checksum.map(ujson.Str(_)).getOrElse(ujson.Null),
            "inputId"  -> i.inputId,
            "version"  -> i.version,
          )
        )
    )
    o("parameters") =
      ujson.Obj.from(parameters.toList.sortBy(_._1).map((k, v) => k -> ujson.Str(v)))
    o("runId") = runId
    o("stage") = stage
    ujson.write(o, indent = 2) + "\n"

object ObjectRunManifest:
  /** Writes artifacts and then the manifest (`manifest.json` next to them) idempotently. Returns
    * the manifest location. Identical data is a no-op; changed data under the same run-versioned
    * key is `IntegrityMismatch`.
    */
  def publish(
      store: ObjectStore,
      bucket: String,
      prefix: String,
      logicalName: String,
      manifest: ObjectRunManifestInput,
  ): StorageResult[ObjectLocation] =
    for
      artifacts <- sequenceResults(manifest.files.map { (name, bytes) =>
        for
          key  <- ObjectStores.versionedKey(prefix, logicalName, manifest.runId, name)
          meta <- ObjectStores.putIdempotent(store, ObjectLocation(bucket, key), bytes)
        yield ManifestArtifact(key, meta.contentHash, meta.sizeBytes)
      })
      m = ObjectRunManifest(
        manifest.runId,
        manifest.stage,
        manifest.codeVersion,
        manifest.parameters,
        manifest.inputs,
        artifacts,
      )
      key <- ObjectStores.versionedKey(prefix, logicalName, manifest.runId, "manifest.json")
      loc = ObjectLocation(bucket, key)
      _ <- ObjectStores.putIdempotent(
        store,
        loc,
        m.toJson.getBytes(StandardCharsets.UTF_8),
        Some("application/json"),
      )
    yield loc

/** Input of `ObjectRunManifest.publish`: files in publish order. */
final case class ObjectRunManifestInput(
    runId: String,
    stage: String,
    codeVersion: String,
    parameters: Map[String, String],
    inputs: List[InputRef],
    files: List[(String, Array[Byte])],
)

/** Filesystem-backed fake of the ordinary S3 store. Objects live under `<root>/data/<bucket>/<key>`
  * and their metadata (hash, type) under `<root>/meta/<bucket>/<key>.json`. Writes go through a
  * temporary file and an atomic move; `putIfAbsent` uses `CREATE_NEW` so it is atomic. There is no
  * delete operation. Versioning is not modelled (`versionId` is always `None`): the versioned key
  * layout carries the run instead.
  */
final class FileObjectStore(root: Path) extends ObjectStore:
  private val data = root.resolve("data")
  private val meta = root.resolve("meta")

  private def dataPath(l: ObjectLocation): Path = data.resolve(l.bucket).resolve(l.objectKey)
  private def metaPath(l: ObjectLocation): Path =
    meta.resolve(l.bucket).resolve(l.objectKey + ".json")

  /** Local I/O failures are not transient (a missing file is `NotFound`, anything else is a
    * non-retryable `Failed`): retrying the same call would fail the same way.
    */
  private def io[A](what: String)(op: => A): StorageResult[A] =
    try Right(op)
    catch
      case _: NoSuchFileException => Left(StorageError.NotFound(what))
      case _: IOException         => Left(StorageError.Failed(s"$what: I/O failure"))

  private def check(
      l: ObjectLocation,
      bytes: Array[Byte],
      expected: Option[String],
  ): StorageResult[String] =
    ObjectStores.checkLocation(l).flatMap { _ =>
      val h = Hashing.sha256Hex(bytes)
      if expected.exists(_ != h) then
        Left(StorageError.IntegrityMismatch("content hash differs from the expected hash"))
      else Right(h)
    }

  private def metaJson(h: String, size: Long, ct: Option[String]): Array[Byte] =
    ujson
      .write(
        ujson.Obj(
          "contentHash" -> h,
          "contentType" -> ct.map(ujson.Str(_)).getOrElse(ujson.Null),
          "sizeBytes"   -> size.toDouble,
        )
      )
      .getBytes(StandardCharsets.UTF_8)

  /** True when the object path cannot exist next to the stored objects: it is a directory, or an
    * ancestor under the data root is a regular file (an S3 key may be a prefix of another key, a
    * file system path cannot).
    */
  private def pathConflict(p: Path, bucketRoot: Path): Boolean =
    Files.isDirectory(p) || {
      var a        = p.getParent
      var conflict = false
      while !conflict && a != null && a.startsWith(bucketRoot) && a != bucketRoot do
        if Files.isRegularFile(a) then conflict = true
        a = a.getParent
      conflict
    }

  /** Writes data and sidecar so that a failure leaves no partial result: both are first written to
    * temporary files; `ifAbsent` claims the data path atomically with a hard link (`AlreadyExists`
    * when taken); the sidecar is then moved into place, and when that fails the data file this call
    * created (or the previous sidecar) is restored. Temporary files are always removed.
    */
  private def commit(
      l: ObjectLocation,
      bytes: Array[Byte],
      h: String,
      ct: Option[String],
      ifAbsent: Boolean,
  ): StorageResult[ObjectMeta] =
    val p  = dataPath(l)
    val mp = metaPath(l)
    if pathConflict(p, data.resolve(l.bucket)) || pathConflict(mp, meta.resolve(l.bucket)) then
      Left(StorageError.InvalidRequest("object key conflicts with another stored key"))
    else
      var tmpData = Option.empty[Path]
      var tmpMeta = Option.empty[Path]
      try
        Files.createDirectories(p.getParent)
        Files.createDirectories(mp.getParent)
        val td = Files.createTempFile(p.getParent, ".tmp-", ".part")
        tmpData = Some(td)
        Files.write(td, bytes)
        val tm = Files.createTempFile(mp.getParent, ".tmp-", ".part")
        tmpMeta = Some(tm)
        Files.write(tm, metaJson(h, bytes.length.toLong, ct))
        val hadData = Files.exists(p)
        val oldMeta = if Files.isRegularFile(mp) then Some(Files.readAllBytes(mp)) else None
        if ifAbsent then Files.createLink(p, td)
        else Files.move(td, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        try Files.move(tm, mp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        catch
          case e: IOException =>
            // Roll back what this call changed: a new data file is removed, a replaced sidecar is
            // restored; an overwritten data file cannot be restored (put replaces by contract).
            if !hadData then Files.deleteIfExists(p)
            oldMeta.foreach(Files.write(mp, _))
            throw e
        Right(ObjectMeta(l, bytes.length.toLong, h, ct, None, None))
      catch
        case _: FileAlreadyExistsException => Left(StorageError.AlreadyExists("object"))
        case _: IOException                => Left(StorageError.Failed("write: I/O failure"))
      finally
        tmpData.foreach(Files.deleteIfExists)
        tmpMeta.foreach(Files.deleteIfExists)

  def put(
      location: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String],
      expectedHash: Option[String],
  ): StorageResult[ObjectMeta] =
    check(location, bytes, expectedHash).flatMap(h =>
      commit(location, bytes, h, contentType, ifAbsent = false)
    )

  def putIfAbsent(
      location: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String],
      expectedHash: Option[String],
  ): StorageResult[ObjectMeta] =
    check(location, bytes, expectedHash).flatMap(h =>
      commit(location, bytes, h, contentType, ifAbsent = true)
    )

  def head(location: ObjectLocation): StorageResult[Option[ObjectMeta]] =
    ObjectStores.checkLocation(location).flatMap { _ =>
      val p = dataPath(location)
      if !Files.isRegularFile(p) then Right(None)
      else
        io("head") {
          val bytes = Files.readAllBytes(p)
          val ct    = scala.util
            .Try(ujson.read(Files.readAllBytes(metaPath(location))))
            .toOption
            .flatMap(_.obj.get("contentType"))
            .flatMap(_.strOpt)
          // The hash is recomputed from the bytes, never trusted from the sidecar.
          Some(ObjectMeta(location, bytes.length.toLong, Hashing.sha256Hex(bytes), ct, None, None))
        }
    }

  def get(location: ObjectLocation): StorageResult[ObjectData] =
    ObjectStores.checkLocation(location).flatMap { _ =>
      io("get") {
        val bytes = Files.readAllBytes(dataPath(location))
        val ct    = scala.util
          .Try(ujson.read(Files.readAllBytes(metaPath(location))))
          .toOption
          .flatMap(_.obj.get("contentType"))
          .flatMap(_.strOpt)
        ObjectData(
          ObjectMeta(location, bytes.length.toLong, Hashing.sha256Hex(bytes), ct, None, None),
          bytes,
        )
      }
    }

  def list(
      bucket: String,
      prefix: String,
      limit: Int,
      continuationToken: Option[String],
  ): StorageResult[ObjectPage] =
    if limit < 1 then Left(StorageError.InvalidRequest("limit must be >= 1"))
    else if !ObjectStores.validBucket(bucket) || prefix.contains("..") || prefix.startsWith("/")
    then Left(StorageError.InvalidRequest("invalid bucket or prefix"))
    else
      val base = data.resolve(bucket)
      if !Files.isDirectory(base) then Right(ObjectPage(Nil, None))
      else
        io("list") {
          val s    = Files.walk(base)
          val keys =
            try
              s.iterator.asScala
                .filter(Files.isRegularFile(_))
                .map(p => base.relativize(p).toString.replace('\\', '/'))
                .filter(k => k.startsWith(prefix) && !k.split("/").last.startsWith(".tmp-"))
                .toList
                .sortBy(identity)(using DynamoKeys.StringOrder)
            finally s.close()
          val after = continuationToken.map(t =>
            new String(Base64.getUrlDecoder.decode(t), StandardCharsets.UTF_8)
          )
          val rest  = after.fold(keys)(a => keys.filter(k => DynamoKeys.StringOrder.gt(k, a)))
          val page  = rest.take(limit)
          val metas = page.map { k =>
            val l     = ObjectLocation(bucket, k)
            val bytes = Files.readAllBytes(dataPath(l))
            ObjectMeta(l, bytes.length.toLong, Hashing.sha256Hex(bytes), None, None, None)
          }
          val next = if rest.size > limit then page.lastOption.map(DynamoKeys.encodeToken) else None
          ObjectPage(metas, next)
        }

/** S3 adapter (sync client). Content integrity: every put sends the SHA-256 checksum (base64) so S3
  * rejects a corrupted upload, and stores the hex SHA-256 as object metadata `sha256`.
  * `putIfAbsent` uses the conditional write `If-None-Match: *`. Objects are never deleted.
  */
final class S3ObjectStore(client: S3Client) extends ObjectStore:
  private val HashMeta = "sha256"

  private def b64(hex: String): String =
    Base64.getEncoder.encodeToString(hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)

  private def request(
      l: ObjectLocation,
      h: String,
      ct: Option[String],
      ifAbsent: Boolean,
  ): PutObjectRequest =
    val b = PutObjectRequest
      .builder()
      .bucket(l.bucket)
      .key(l.objectKey)
      .checksumSHA256(b64(h))
      .metadata(Map(HashMeta -> h).asJava)
    ct.foreach(b.contentType)
    if ifAbsent then b.ifNoneMatch("*")
    b.build()

  private def doPut(
      l: ObjectLocation,
      bytes: Array[Byte],
      ct: Option[String],
      expected: Option[String],
      ifAbsent: Boolean,
  ): StorageResult[ObjectMeta] =
    ObjectStores.checkLocation(l).flatMap { _ =>
      val h = Hashing.sha256Hex(bytes)
      if expected.exists(_ != h) then
        Left(StorageError.IntegrityMismatch("content hash differs from the expected hash"))
      else
        try
          val r = client.putObject(request(l, h, ct, ifAbsent), RequestBody.fromBytes(bytes))
          Right(ObjectMeta(l, bytes.length.toLong, h, ct, Option(r.versionId()), None))
        catch
          case s: S3Exception if ifAbsent && s.statusCode() == 412 =>
            Left(StorageError.AlreadyExists("object"))
          case s: S3Exception if s.statusCode() == 409 =>
            Left(StorageError.Unavailable("s3 put: conditional request conflict"))
          case scala.util.control.NonFatal(e) => Left(AwsErrors.translate(e, "s3 put"))
    }

  def put(
      location: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String],
      expectedHash: Option[String],
  ): StorageResult[ObjectMeta] = doPut(location, bytes, contentType, expectedHash, ifAbsent = false)

  def putIfAbsent(
      location: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String],
      expectedHash: Option[String],
  ): StorageResult[ObjectMeta] = doPut(location, bytes, contentType, expectedHash, ifAbsent = true)

  def get(location: ObjectLocation): StorageResult[ObjectData] =
    ObjectStores.checkLocation(location).flatMap { _ =>
      try
        val r = client.getObjectAsBytes(
          GetObjectRequest.builder().bucket(location.bucket).key(location.objectKey).build()
        )
        val bytes  = r.asByteArray()
        val h      = Hashing.sha256Hex(bytes)
        val stored = Option(r.response().metadata().get(HashMeta))
        if stored.exists(_ != h) then
          Left(StorageError.IntegrityMismatch("object bytes differ from the stored hash"))
        else
          Right(
            ObjectData(
              ObjectMeta(
                location,
                bytes.length.toLong,
                h,
                Option(r.response().contentType()),
                Option(r.response().versionId()),
                Option(r.response().lastModified()).map(_.toString),
              ),
              bytes,
            )
          )
      catch
        case _: NoSuchKeyException          => Left(StorageError.NotFound("object"))
        case scala.util.control.NonFatal(e) => Left(AwsErrors.translate(e, "s3 get"))
    }

  def head(location: ObjectLocation): StorageResult[Option[ObjectMeta]] =
    ObjectStores.checkLocation(location).flatMap { _ =>
      try
        val r = client.headObject(
          HeadObjectRequest.builder().bucket(location.bucket).key(location.objectKey).build()
        )
        Option(r.metadata().get(HashMeta)) match
          case Some(h) =>
            Right(
              Some(
                ObjectMeta(
                  location,
                  r.contentLength().longValue,
                  h,
                  Option(r.contentType()),
                  Option(r.versionId()),
                  Option(r.lastModified()).map(_.toString),
                )
              )
            )
          // An object without our hash metadata (not written by this adapter): read it to hash it.
          case None => get(location).map(d => Some(d.meta))
      catch
        case s: S3Exception if s.statusCode() == 404 => Right(None)
        case scala.util.control.NonFatal(e)          => Left(AwsErrors.translate(e, "s3 head"))
    }

  def list(
      bucket: String,
      prefix: String,
      limit: Int,
      continuationToken: Option[String],
  ): StorageResult[ObjectPage] =
    if limit < 1 || limit > 1000 then Left(StorageError.InvalidRequest("limit must be 1..1000"))
    else if !ObjectStores.validBucket(bucket) then
      Left(StorageError.InvalidRequest("invalid bucket"))
    else
      val b = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).maxKeys(limit)
      continuationToken.foreach(b.continuationToken)
      AwsErrors.guard("s3 list")(client.listObjectsV2(b.build())).map { r =>
        val metas = r.contents().asScala.toList.map { o =>
          // ETag is not a SHA-256; the hash of listed objects is unknown until read.
          ObjectMeta(
            ObjectLocation(bucket, o.key()),
            o.size().longValue,
            "",
            None,
            None,
            Option(o.lastModified()).map(_.toString),
          )
        }
        ObjectPage(metas, if r.isTruncated then Option(r.nextContinuationToken()) else None)
      }
