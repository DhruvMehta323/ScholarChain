package scholarchain.storage

import java.time.Duration

import scala.jdk.CollectionConverters.*

import scholarchain.core.*

import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.services.neptunedata.NeptunedataClient
import software.amazon.awssdk.services.neptunedata.model.{
  ExecuteOpenCypherQueryRequest,
  GetLoaderJobStatusRequest,
  StartLoaderJobRequest,
}

/** Neptune adapters over the `neptunedata` data-plane API (sync client). The client must be built
  * with the cluster endpoint (see `AwsClients`). Nothing here builds a client.
  */
object NeptuneAws:
  def executor(client: NeptunedataClient): CypherExecutor =
    (cypher, parametersJson, timeoutMillis) =>
      AwsErrors
        .guard("neptune query") {
          val cfg = AwsRequestOverrideConfiguration
            .builder()
            .apiCallTimeout(Duration.ofMillis(timeoutMillis + 1000L))
            .build()
          client.executeOpenCypherQuery(
            ExecuteOpenCypherQueryRequest
              .builder()
              .openCypherQuery(cypher)
              .parameters(parametersJson)
              .overrideConfiguration(cfg)
              .build()
          )
        }
        .map(r => rows(r.results()))

  def loader(client: NeptunedataClient): BulkLoader = new BulkLoader:
    def start(request: NeptuneLoaderRequest): StorageResult[BulkLoadHandle] =
      AwsErrors
        .guard("neptune bulk load start")(
          client.startLoaderJob(
            StartLoaderJobRequest
              .builder()
              .source(request.source)
              .format(request.format)
              .iamRoleArn(request.iamRoleArn)
              .s3BucketRegion(request.s3BucketRegion)
              .mode(request.mode)
              .failOnError(request.failOnError)
              .parallelism(request.parallelism)
              .updateSingleCardinalityProperties(request.updateSingleCardinalityProperties)
              .queueRequest(request.queueRequest)
              .build()
          )
        )
        .flatMap(r =>
          Option(r.payload())
            .flatMap(m => Option(m.get("loadId")))
            .map(id => BulkLoadHandle(id))
            .toRight(StorageError.Failed("neptune bulk load: no loadId in the response"))
        )

    def status(handle: BulkLoadHandle): StorageResult[BulkLoadStatus] =
      AwsErrors
        .guard("neptune bulk load status")(
          client.getLoaderJobStatus(
            GetLoaderJobStatusRequest.builder().loadId(handle.loadId).build()
          )
        )
        .map { r =>
          val overall =
            Option(r.payload()).filter(_.isMap).flatMap(p => Option(p.asMap().get("overallStatus")))
          val st = overall
            .filter(_.isMap)
            .flatMap(o => Option(o.asMap().get("status")))
            .filter(_.isString)
            .map(_.asString())
          val total = overall
            .filter(_.isMap)
            .flatMap(o => Option(o.asMap().get("totalRecords")))
            .filter(_.isNumber)
            .map(_.asNumber().longValue())
          mapStatus(st.getOrElse(Option(r.status()).getOrElse("")), total)
        }

  /** Maps a Neptune loader status string (for example `LOAD_COMPLETED`). */
  def mapStatus(s: String, total: Option[Long]): BulkLoadStatus = s match
    case "LOAD_NOT_STARTED" | "LOAD_IN_QUEUE" => BulkLoadStatus.Submitted
    case "LOAD_IN_PROGRESS"                   => BulkLoadStatus.InProgress(total)
    case "LOAD_COMPLETED"                     => BulkLoadStatus.Completed(total)
    case c if c.startsWith("LOAD_CANCELLED")  => BulkLoadStatus.Cancelled
    case other                                => BulkLoadStatus.LoadFailed(other.take(80))

  private def payloadString(d: Document, field: String): Option[String] =
    Option(d)
      .filter(_.isMap)
      .flatMap(m => Option(m.asMap().get(field)))
      .filter(_.isString)
      .map(_.asString())

  private def rows(d: Document): List[GraphRow] =
    if d == null || !d.isList then Nil
    else
      d.asList().asScala.toList.map { r =>
        GraphRow(
          if r.isMap then r.asMap().asScala.toMap.map((k, v) => k -> value(v)) else Map.empty
        )
      }

  private def value(d: Document): GraphValue =
    if d == null || d.isNull then GraphValue.Null
    else if d.isString then GraphValue.Str(d.asString())
    else if d.isBoolean then GraphValue.Bool(d.asBoolean())
    else if d.isNumber then
      val n = d.asNumber()
      val s = n.stringValue()
      s.toLongOption.map(GraphValue.Num(_)).getOrElse(GraphValue.Dbl(n.doubleValue()))
    else if d.isList then GraphValue.Lst(d.asList().asScala.toList.map(value))
    else if d.isMap then GraphValue.Obj(d.asMap().asScala.toMap.map((k, v) => k -> value(v)))
    else GraphValue.Null
