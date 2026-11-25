package scholarchain.services

import java.net.URI

import scala.collection.mutable

import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  AwsCredentials,
  AwsCredentialsProvider,
}

import scholarchain.core.*
import scholarchain.storage.*

import OpsFx.*

/** A distinctive exception class: only its simple name may reach the output. */
final class BoomException(message: String) extends RuntimeException(message)

/** `OpsLoadMain.execute` with an injected build, and `OpsLoadMain.assemble` with a fake environment
  * and a credentials provider that counts its calls (no network, no AWS).
  */
class OpsLoadMainSuite extends munit.FunSuite:

  private val ApiKey      = "sk-test-SENTINEL-API-KEY-777"
  private val InternalKey = "sk-test-SENTINEL-INTERNAL-KEY-888"
  private val QueueUrl    = "https://sqs.us-east-1.amazonaws.com/123456789012/queue-sentinel-name"
  private val Endpoint    = "https://neptune-sentinel.example.invalid:8182"

  private val opsEnv: Map[String, String] = Map(
    "SCHOLARCHAIN_OPS_NEPTUNE_SOURCE" -> NeptuneSource,
    "SCHOLARCHAIN_OPS_ITEMS_SOURCE"   -> ItemsSource,
    "SCHOLARCHAIN_OPS_RUN_ID"         -> RunId,
    "SCHOLARCHAIN_OPS_CODE_VERSION"   -> CodeVersion,
  )

  private val cfgEnv: Map[String, String] = Map(
    "SCHOLARCHAIN_AWS_REGION"                   -> Region,
    "SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT"     -> Endpoint,
    "SCHOLARCHAIN_STORAGE_CURATED_BUCKET"       -> Bucket,
    "SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL" -> QueueUrl,
    "SCHOLARCHAIN_SERVICES_API_KEY"             -> ApiKey,
    "SCHOLARCHAIN_INTERNAL_API_KEY"             -> InternalKey,
  )

  private val allEnv: Map[String, String] = opsEnv ++ cfgEnv

  private val Secrets: List[String] =
    List(
      ApiKey,
      InternalKey,
      QueueUrl,
      Endpoint,
      "neptune-sentinel",
      "queue-sentinel",
      "123456789012",
    )

  private final class Counting extends AwsCredentialsProvider:
    var calls: Int                           = 0
    def resolveCredentials(): AwsCredentials =
      calls += 1
      AwsBasicCredentials.create("AKIAPLACEHOLDERKEY", "placeholder-secret-value")

  private def seams(p: Counting): AwsSeams =
    AwsSeams(Some(p), Some(new URI("https://neptune.placeholder.invalid:8182")))

  private val Forbidden: List[String] =
    List("/Users/", "/home/", "target/", "arn:aws:iam", "AKIA", "\tat ", ".scala:", "SECRET-EXC")

  private def assertClean(label: String, lines: Iterable[String]): Unit =
    lines.foreach { l =>
      (Forbidden ++ Secrets).foreach(f =>
        assert(!l.contains(f), s"$label contains '$f': ${l.take(300)}")
      )
    }

  private final class Run(val code: Int, val lines: List[String]):
    def json: List[ujson.Value]       = lines.map(ujson.read(_))
    def event(n: String): ujson.Value =
      json.find(_.obj.get("event").exists(_.str == n)).getOrElse(fail(s"no $n in $lines"))

  private def exec(
      argv: List[String],
      env: Map[String, String],
      build: Map[String, String] => Either[String, (OpsContext, OpsPorts)],
  ): Run =
    val out = mutable.ListBuffer.empty[String]
    val c   = OpsLoadMain.execute(argv, env, out += _, build)
    new Run(c, out.toList)

  private def injected(
      r: Rig,
      c: OpsContext = ctx(),
  ): Map[String, String] => Either[String, (OpsContext, OpsPorts)] =
    _ => Right((c, r.ports))

  private def neverBuilt
      : (mutable.ListBuffer[Int], Map[String, String] => Either[String, (OpsContext, OpsPorts)]) =
    val calls = mutable.ListBuffer.empty[Int]
    (calls, _ => { calls += 1; Left("build must not be called") })

  // ---- 14. execute -----------------------------------------------------------------------------

  test("a bad argument: exit 2, one refused line, the build is never called") {
    val (calls, b) = neverBuilt
    val r          = exec(List("--bogus", "value"), allEnv, b)
    assertEquals(r.code, 2)
    assertEquals(calls.toList, Nil)
    assertEquals(r.lines.size, 1)
    val ev = r.event("ops.usage")
    assertEquals(ev("level"), ujson.Str("error"))
    assertEquals(ev("service"), ujson.Str("ops"))
    assertEquals(ev("exitCode").num.toInt, 2)
    assert(!ev("message").str.contains("value"), ev("message").str)
    assertClean("usage", r.lines)
  }

  test("a missing required value: exit 2 naming the variable, never a value") {
    val required =
      List(
        "SCHOLARCHAIN_OPS_NEPTUNE_SOURCE",
        "SCHOLARCHAIN_OPS_ITEMS_SOURCE",
        "SCHOLARCHAIN_OPS_RUN_ID",
        "SCHOLARCHAIN_OPS_CODE_VERSION",
      )
    required.foreach { name =>
      val (calls, b) = neverBuilt
      val r          = exec(Nil, allEnv - name, b)
      assertEquals(r.code, 2, name)
      assertEquals(calls.toList, Nil)
      val msg = r.event("ops.usage")("message").str
      assert(msg.contains(name), msg)
      List(NeptuneSource, ItemsSource, CodeVersion).foreach(v => assert(!msg.contains(v), msg))
      assertClean(name, r.lines)
    }
  }

  test("max vectors 0 and 10001 are exit 2 through execute; the default and the bounds run") {
    List("0", "10001").foreach { v =>
      val (calls, b) = neverBuilt
      val r          = exec(Nil, allEnv + ("SCHOLARCHAIN_OPS_MAX_VECTORS" -> v), b)
      assertEquals(r.code, 2, v)
      assertEquals(calls.toList, Nil)
      assert(r.event("ops.usage")("message").str.contains("SCHOLARCHAIN_OPS_MAX_VECTORS"))
      val r2 = exec(List("--max-vectors", v), allEnv, b)
      assertEquals(r2.code, 2, v)
    }
    val rg = rigWith(sized(2, 2))
    assertEquals(exec(Nil, allEnv, injected(rg)).code, 0)
    assertEquals(rg.queue.documents.size, 4)
    val one = rigWith(sized(1, 2))
    assertEquals(exec(List("--max-vectors", "1"), allEnv, injected(one)).code, 0)
    assertEquals(one.queue.documents.size, 1)
  }

  test("argv beats the environment through execute") {
    val rg = rigWith(sized(2, 20))
    val r  = exec(
      List("--max-vectors", "6"),
      allEnv + ("SCHOLARCHAIN_OPS_MAX_VECTORS" -> "10000"),
      injected(rg),
    )
    assertEquals(r.code, 0)
    assertEquals(rg.queue.documents.size, 6)
    assertEquals(r.event("ops.start")("cap").num.toInt, 6)
  }

  test("build returning Left is exit 2 with the message on one error line") {
    val r = exec(Nil, allEnv, _ => Left("SCHOLARCHAIN_AWS_REGION is required and is not set"))
    assertEquals(r.code, 2)
    assertEquals(r.lines.size, 1)
    val ev = r.event("ops.config")
    assertEquals(ev("level"), ujson.Str("error"))
    assertEquals(ev("exitCode").num.toInt, 2)
    assertEquals(ev("message"), ujson.Str("SCHOLARCHAIN_AWS_REGION is required and is not set"))
  }

  test("an exception during the run is exit 1 and only the class name is printed") {
    val rg = rigWith(Src())
    rg.store.throwOnGet =
      Some(new BoomException("SECRET-EXC-MESSAGE arn:aws:iam::1:role/x /Users/me/x"))
    val r = exec(Nil, allEnv, injected(rg))
    assertEquals(r.code, 1)
    val ev = r.event("ops.failed")
    assertEquals(ev("message"), ujson.Str("unexpected failure (BoomException)"))
    assertEquals(ev("exitCode").num.toInt, 1)
    assertEquals(ev("level"), ujson.Str("error"))
    assertClean("exception", r.lines)
    assertEquals(rg.effects, (0, 0, 0, 0, 0))
  }

  test("an exception from a port in the middle of the run is exit 1 too") {
    val rg      = rigWith(Src())
    val failing = new RecordingQueueThrowing
    val rg2     = rig(queue = failing)
    upload(rg2.store, Src().render)
    val r = exec(Nil, allEnv, injected(rg2))
    assertEquals(r.code, 1)
    assertEquals(r.event("ops.failed")("message"), ujson.Str("unexpected failure (BoomException)"))
    assertClean("exception", r.lines)
    assert(rg.manifestStored.isEmpty)
    assertEquals(rg2.manifestStored, None)
  }

  test("a run through execute: exit 0 on success, with the lines of the run on stdout") {
    val rg = rigWith(Src())
    val r  = exec(Nil, allEnv, injected(rg, ctx(Some("v9.9"))))
    assertEquals(r.code, 0)
    assertEquals(r.json.map(_("event").str).take(3), List("ops.start", "ops.plan", "ops.liveload"))
    assertEquals(r.json.last("event"), ujson.Str("ops.manifest"))
    assertEquals(r.event("ops.manifest")("manifest")("imageTag"), ujson.Str("v9.9"))
    assertClean("success", r.lines)
    assert(
      r.json.forall(j => j.obj.contains("level") && j.obj.get("service").contains(ujson.Str("ops")))
    )
  }

  test(
    "execute maps the run's outcome to its exit code: 0 dry-run, 1 failure, 2 usage, 3 refusal"
  ) {
    assertEquals(exec(List("--dry-run"), allEnv, injected(rigWith(Src()))).code, 0)
    assertEquals(
      exec(Nil, allEnv + ("SCHOLARCHAIN_OPS_DRY_RUN" -> "true"), injected(rigWith(Src()))).code,
      0,
    )
    val cancelled = rigWith(Src(), FakeLoader.scripted(List(Right(BulkLoadStatus.Cancelled))))
    assertEquals(exec(Nil, allEnv, injected(cancelled)).code, 1)
    assertEquals(
      exec(Nil, allEnv + ("SCHOLARCHAIN_OPS_RUN_ID" -> "run-other"), injected(rigWith(Src()))).code,
      2,
    )
    val tampered = rigWith(
      Src(),
      tamper =
        f => f.updated("embedding-inputs/works.jsonl", flip(f("embedding-inputs/works.jsonl"))),
    )
    assertEquals(exec(Nil, allEnv, injected(tampered)).code, 3)
    assertEquals(tampered.effects, (0, 0, 0, 0, 0))
  }

  test("the environment secrets never reach a line, in any outcome") {
    val outcomes = List(
      exec(Nil, allEnv, injected(rigWith(Src()))),
      exec(List("--dry-run"), allEnv, injected(rigWith(Src()))),
      exec(
        Nil,
        allEnv,
        injected(rigWith(Src(), FakeLoader.scripted(List(Right(BulkLoadStatus.Cancelled))))),
      ),
      exec(List("--bogus"), allEnv, injected(rigWith(Src()))),
      exec(Nil, allEnv - "SCHOLARCHAIN_OPS_RUN_ID", injected(rigWith(Src()))),
    )
    outcomes.foreach(o => assertClean("outcome", o.lines))
  }

  test("the default build refuses an incomplete environment without any network call (exit 2)") {
    val r = mutable.ListBuffer.empty[String]
    val c = OpsLoadMain.execute(Nil, opsEnv, r += _)
    assertEquals(c, 2)
    val msg = ujson.read(r.head)("message").str
    assert(msg.contains("SCHOLARCHAIN_AWS_REGION"), msg)
  }

  // ---- 14. assemble ------------------------------------------------------------------------------

  private def assembled(env: Map[String, String]) =
    val p = new Counting
    (p, OpsLoadMain.assemble(env, seams(p)))

  private def left(env: Map[String, String]): String =
    assembled(env)._2.fold(identity, _ => fail("assemble accepted the environment"))

  test("assemble with a complete fake environment: a context and ports, no credential resolved") {
    val (p, res)   = assembled(cfgEnv)
    val (c, ports) = res.fold(e => fail(s"unexpected Left: $e"), identity)
    assertEquals(p.calls, 0, "assemble resolved credentials, so it may have called AWS")
    assertEquals(c.region, Region)
    assertEquals(c.textBucket, Bucket)
    assertEquals(c.textPrefix, "rag/text")
    assertEquals(c.imageTag, None)
    assert(ports.queue.isInstanceOf[SqsWorkQueue])
    assert(ports.live.objects.isInstanceOf[S3ObjectStore])
    assertEquals(c.embedding.queueName, embeddingSettings.queueName)
  }

  test("assemble: the text prefix is the configured rag prefix plus /text") {
    val (_, res) = assembled(cfgEnv + ("SCHOLARCHAIN_STORAGE_S3_RAG_PREFIX" -> "custom/ragdata/"))
    assertEquals(res.map(_._1.textPrefix), Right("custom/ragdata/text"))
  }

  test("assemble: a rag prefix that is absolute, an s3 URI or contains .. is refused") {
    List("/abs/rag/", "s3://other/rag/", "a/../b/").foreach { v =>
      val m = left(cfgEnv + ("SCHOLARCHAIN_STORAGE_S3_RAG_PREFIX" -> v))
      Secrets.foreach(s => assert(!m.contains(s), m))
    }
  }

  test(
    "assemble: a missing region, Neptune endpoint, curated bucket or queue URL is exit 2 naming the variable"
  ) {
    List(
      "SCHOLARCHAIN_AWS_REGION",
      "SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT",
      "SCHOLARCHAIN_STORAGE_CURATED_BUCKET",
      "SCHOLARCHAIN_SERVICES_EMBEDDING_QUEUE_URL",
    ).foreach { name =>
      val m = left(cfgEnv - name)
      assert(m.contains(name), s"$name: $m")
      Secrets.foreach(s => assert(!m.contains(s), s"$name leaks $s: $m"))
      val blank = left(cfgEnv + (name -> "   "))
      assert(blank.contains(name), s"$name (blank): $blank")
      // and through execute: exit 2 plus one config line
      val r = exec(Nil, allEnv + (name -> ""), e => OpsLoadMain.assemble(e, seams(new Counting)))
      assertEquals(r.code, 2, name)
      assert(r.event("ops.config")("message").str.contains(name))
      assertClean(name, r.lines)
    }
  }

  test("assemble: the AWS_REGION fallback is accepted when SCHOLARCHAIN_AWS_REGION is absent") {
    val (_, res) = assembled((cfgEnv - "SCHOLARCHAIN_AWS_REGION") + ("AWS_REGION" -> "eu-west-1"))
    assertEquals(res.map(_._1.region), Right("eu-west-1"))
  }

  test("assemble: SCHOLARCHAIN_ENVIRONMENT=local is refused (no local mode)") {
    val m = left(cfgEnv + ("SCHOLARCHAIN_ENVIRONMENT" -> "local"))
    assert(m.contains("no local mode"), m)
    val r = exec(
      Nil,
      allEnv + ("SCHOLARCHAIN_ENVIRONMENT" -> "local"),
      e => OpsLoadMain.assemble(e, seams(new Counting)),
    )
    assertEquals(r.code, 2)
    assertClean("local", r.lines)
    val (_, ok) = assembled(cfgEnv + ("SCHOLARCHAIN_ENVIRONMENT" -> "course"))
    assert(ok.isRight, ok.toString)
  }

  test("assemble: a curated bucket that is not a bare name is refused, without echoing it") {
    List("s3://secret-bucket-sentinel/x", "bucket/with/slash", "S3:bucket").foreach { v =>
      val m = left(cfgEnv + ("SCHOLARCHAIN_STORAGE_CURATED_BUCKET" -> v))
      assert(m.contains("SCHOLARCHAIN_STORAGE_CURATED_BUCKET"), m)
      assert(!m.contains(v), s"the value is echoed: $m")
    }
  }

  test("assemble: a Neptune endpoint that is not a URI is refused, without echoing it") {
    val v = "not a uri with spaces SENTINEL-ENDPOINT"
    val m = left(cfgEnv + ("SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT" -> v))
    assert(m.contains("SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT"), m)
    assert(!m.contains("SENTINEL-ENDPOINT"), m)
  }

  test(
    "assemble: a Neptune endpoint without a scheme does not crash the task (exit 2, not a stack trace)"
  ) {
    val bad  = cfgEnv + ("SCHOLARCHAIN_STORAGE_NEPTUNE_ENDPOINT" -> "just-a-host-sentinel")
    val r    = mutable.ListBuffer.empty[String]
    val p    = new Counting
    val code =
      try
        OpsLoadMain.execute(
          Nil,
          opsEnv ++ bad,
          r += _,
          e => OpsLoadMain.assemble(e, AwsSeams(Some(p))),
        )
      catch
        case e: Throwable =>
          fail(s"execute threw ${e.getClass.getName} instead of returning an exit code")
    assertEquals(code, 2)
    assertEquals(p.calls, 0)
    assertClean("scheme", r.toList)
  }

  test("assemble: the image tag is recorded only when it is a valid token") {
    List("v1.2.3", "abc_DEF-9.x", "a" * 128).foreach { t =>
      val (_, res) = assembled(cfgEnv + ("SCHOLARCHAIN_IMAGE_TAG" -> t))
      assertEquals(res.map(_._1.imageTag), Right(Some(t)))
    }
    List("bad tag", "a/b", "a" * 129, "", "tag;rm -rf", "x\ny", "ünïcode").foreach { t =>
      val (_, res) = assembled(cfgEnv + ("SCHOLARCHAIN_IMAGE_TAG" -> t))
      assertEquals(res.map(_._1.imageTag), Right(None), s"tag '$t'")
    }
  }

  test("assemble: configuration errors never echo the keys, the queue URL or the endpoint") {
    val m = left(
      cfgEnv + ("SCHOLARCHAIN_STORAGE_S3_RAG_PREFIX" -> "") + ("SCHOLARCHAIN_STORAGE_RETRY_MAX_ATTEMPTS" -> "9999")
    )
    Secrets.foreach(s => assert(!m.contains(s), s"leaks $s: $m"))
  }

  test("assemble: the queue is bound to the configured queue name and nothing is called on it") {
    val (p, res)   = assembled(cfgEnv)
    val (c, ports) = res.fold(e => fail(e), identity)
    assertEquals(p.calls, 0)
    assertEquals(c.embedding.queueName, embeddingSettings.queueName)
    assert(ports.queue.isInstanceOf[SqsWorkQueue])
  }

  private final class RecordingQueueThrowing extends RecordingQueue():
    override def send(
        queue: String,
        body: String,
        deduplicationId: Option[String],
    ): StorageResult[String] =
      throw new BoomException("SECRET-EXC-QUEUE arn:aws:iam::1:role/x")
