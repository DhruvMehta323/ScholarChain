package scholarchain.storage

import scholarchain.core.*

/** Live: put and query of 10 vectors in the researcher-profile index. Needs
  * `SCHOLARCHAIN_IT_VECTOR_DIMENSION` (the index dimension) and `SCHOLARCHAIN_IT_S3_BUCKET` (for
  * the `textS3Uri` of the probe records). Run only with SCHOLARCHAIN_AWS_IT=1 in an approved
  * environment. Vector keys are `it-probe-<n>`; their text hash is fixed, so a rerun is idempotent.
  */
class S3VectorsAwsItSuite extends munit.FunSuite:
  override def munitIgnore: Boolean = !AwsItSupport.enabled

  test("put 10 vectors and query the nearest ones") {
    val cfg    = AwsItSupport.config.value
    val index  = cfg.vectors.researcherProfileIndex
    val dim    = AwsItSupport.env("SCHOLARCHAIN_IT_VECTOR_DIMENSION").toInt
    val bucket = AwsItSupport.env("SCHOLARCHAIN_IT_S3_BUCKET")
    val store  = AwsItSupport.clients().vectorStore(cfg.vectors.bucketName, Map(index -> dim))
    val runId  = Ids
      .runId("it-probe", "it", 1, Map.empty, List(InputRef("it-probe", "1", None)))
      .fold(e => sys.error(e.message), identity)
    def vec(i: Int): Vector[Float] = Vector.tabulate(dim)(j => if j == i % dim then 1f else 0.01f)
    val entries                    = (0 until 10).toList.map { i =>
      val text = s"it-probe-$i"
      VectorEntry(
        VectorRecord(
          1,
          s"it-probe-$i",
          index,
          dim,
          "it-model",
          "1",
          Hashing.textHash(text),
          s"s3://$bucket/it-probe/$i.txt",
          Map("source" -> "it-probe"),
          runId,
        ),
        vec(i),
      )
    }
    val loader = new VectorLoader(store, cfg.retry, Sleeper.System, new InMemoryRejectionSink)
    val report = loader.load(index, entries)
    assert(report.isRight, report.toString)
    assertEquals(report.toOption.get.accepted, 10)
    val hits =
      store.query(index, VectorQuery(vec(0), 3, Some(MetadataFilter.Equals("source", "it-probe"))))
    assert(hits.isRight, hits.toString)
    assert(hits.toOption.get.nonEmpty)
    assert(hits.toOption.get.size <= 3)
  }
