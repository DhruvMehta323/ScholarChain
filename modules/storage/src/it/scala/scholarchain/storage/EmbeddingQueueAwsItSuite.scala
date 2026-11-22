package scholarchain.storage

import scholarchain.core.*

/** Live: enqueue and drain AT MOST 5 embedding jobs (one document each) through SQS, S3 (text
  * objects) and S3 Vectors (the dedicated test index). Run only with SCHOLARCHAIN_AWS_IT=1 in an
  * approved environment; it is filtered out of every other run and NOT RUN in Phase 08.
  *
  * ALSO REQUIRES the claims DynamoDB table (`embedding.claims.table-name`, Phase 12).
  *
  * LIMITATION (named): Bedrock is NOT exercised here. The Bedrock embedder lives in the `rag`
  * module and `storage` may not depend on `rag`, and the storage build has no Bedrock SDK module.
  * The suite embeds with `LocalHashEmbedder` below (a deterministic hashed bag of words, model ID
  * `it-local-hash`), so it checks the SQS, S3 and S3 Vectors legs of the path only. A Bedrock leg
  * belongs to a rag live suite or to the services composition.
  *
  * Environment (read only inside the test): `SCHOLARCHAIN_IT_EMBEDDING_QUEUE_URL`,
  * `SCHOLARCHAIN_IT_EMBEDDING_DLQ_URL` (standard or FIFO queues named by `embedding.queue.*`),
  * `SCHOLARCHAIN_IT_S3_BUCKET`, `SCHOLARCHAIN_IT_VECTOR_DIMENSION` (dimension of the test index).
  * Vectors go only to the test index (`VectorTestIndex.guard`). Nothing is deleted: the queue
  * messages are deleted by the worker's own acknowledgement (that is consuming, not data removal);
  * the five vector keys and five text objects are listed in STORAGE-CONTRACTS.md for a human to
  * clean up.
  */
class EmbeddingQueueAwsItSuite extends munit.FunSuite:
  override def munitIgnore: Boolean = !AwsItSupport.enabled

  private final class LocalHashEmbedder(val dimension: Int) extends Embedder:
    val modelId: String                                                  = "it-local-hash"
    val modelVersion: String                                             = "1"
    val maxBatchSize: Int                                                = 8
    def embed(texts: List[String]): EmbeddingResult[List[Vector[Float]]] =
      Right(texts.map { t =>
        val v = Array.fill(dimension)(0.01f)
        t.split("\\s+")
          .filter(_.nonEmpty)
          .foreach(w => v(math.floorMod(w.hashCode, dimension)) += 1f)
        v.toVector
      })

  test("enqueue and drain 5 one-document jobs: SQS, S3 and the test vector index") {
    val cfg = AwsItSupport.config.value
    val emb =
      EmbeddingConfigLoader.load().fold(e => sys.error(e.map(_.message).mkString("; ")), _.settings)
    val index  = VectorTestIndex.resolve(emb, cfg).fold(e => sys.error(e.message), identity)
    val dim    = AwsItSupport.env("SCHOLARCHAIN_IT_VECTOR_DIMENSION").toInt
    val bucket = AwsItSupport.env("SCHOLARCHAIN_IT_S3_BUCKET")
    val urls   = Map(
      emb.queueName      -> AwsItSupport.env("SCHOLARCHAIN_IT_EMBEDDING_QUEUE_URL"),
      emb.deadLetterName -> AwsItSupport.env("SCHOLARCHAIN_IT_EMBEDDING_DLQ_URL"),
    )
    val clients = AwsItSupport.clients()
    val queue   = clients.workQueue(urls)
    val objects = clients.objectStore
    val vectors =
      VectorTestIndex.guard(clients.vectorStore(cfg.vectors.bucketName, Map(index -> dim)), index)
    val embedder = new LocalHashEmbedder(dim)
    val runId    = Ids
      .runId("it-embedding", "it", 1, Map.empty, List(InputRef("it-embedding", "1", None)))
      .fold(e => sys.error(e.message), identity)
    val window  = GraphWindow(2000, 2004)
    val sources = (0 until 5).toList.map { i =>
      val text   = s"it embedding probe $i collaboration"
      val entity =
        Ids.researcherIdDblpPerson(s"It Probe $i").fold(e => sys.error(e.message), identity)
      val doc = EmbeddingDocument(
        1,
        EmbeddingDocument.idFor(EmbeddingDocumentTypes.ResearcherProfile, entity, window, 0),
        EmbeddingDocumentTypes.ResearcherProfile,
        entity,
        window,
        s"s3://$bucket/${cfg.s3.logs}it-embedding/$i.txt",
        Hashing.textHash(text),
        0,
        1,
        runId,
      )
      EmbeddingSource(doc, text)
    }
    val producer = new EmbeddingProducer(
      EmbeddingProducerConfig(emb.queueName, 1, 5, embedder.modelId, embedder.modelVersion),
      queue,
      objects,
    )
    val report = producer.enqueue(runId, sources)
    assert(report.isRight, report.toString)
    assert(report.toOption.get.jobsEnqueued <= 5)
    val worker = new EmbeddingWorker(
      EmbeddingWorkerConfig(
        emb.queueName,
        emb.deadLetterName,
        emb.maxAttempts,
        60,
        1,
        Some(index),
        Some(dim),
        emb.claimsTableName,
      ),
      queue,
      objects,
      embedder,
      vectors,
      AwsItSupport.clients().keyValueStore,
    )
    val drained = worker.drain(5)
    assert(drained.counts.jobsProcessed <= 5, drained.toString)
    assertEquals(drained.counts.jobsDeadLettered, 0, drained.toString)
    assertEquals(
      drained.counts.vectorsWritten + drained.counts.skippedUnchanged,
      5,
      drained.toString,
    )
  }
