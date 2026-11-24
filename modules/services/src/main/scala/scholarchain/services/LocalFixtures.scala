package scholarchain.services

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import scholarchain.core.*
import scholarchain.rag.EmbedderFactory
import scholarchain.rag.runtime.*
import scholarchain.storage.*

/** LOCAL MODE ONLY. Fixtures are plain files (DynamoDB JSONL, Neptune CSV, vector JSONL, text
  * objects) produced by earlier phases; reading them needs no Spark and no Parquet. Nothing here
  * constructs an AWS client. Production code does not depend on test code: the readers below are
  * the service's own.
  */
final case class LocalFixturePaths(l4: Path, embeddings: Path, auditDir: Path)

object LocalFixturePaths:
  def repoRoot: Path =
    val start = Paths.get(System.getProperty("user.dir")).toAbsolutePath
    Iterator
      .iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("build.sbt")))
      .getOrElse(
        throw new IllegalStateException(s"repository root (build.sbt) not found above $start")
      )

  def default(auditDir: Path): LocalFixturePaths =
    val root = repoRoot
    LocalFixturePaths(
      root.resolve("modules/storage/target/loads/L4"),
      root.resolve("modules/services/target/embedding-runs/L4"),
      auditDir,
    )

  /** The inputs the local mode needs; a missing one is a startup error, never a silent skip. */
  def missing(p: LocalFixturePaths): List[String] =
    List(
      p.l4.resolve("dynamodb/items/researcher-metrics.jsonl"),
      p.l4.resolve("dynamodb/items/identity-clusters.jsonl"),
      p.l4.resolve("neptune/edges/COAUTHORED_WITH.csv"),
      p.l4.resolve("neptune/edges/IN_COMPONENT.csv"),
      p.l4.resolve("neptune/edges/MEMBER_OF.csv"),
      p.l4.resolve("neptune/vertices/Component.csv"),
      p.l4.resolve("neptune/vertices/Community.csv"),
      p.embeddings.resolve("vectors.jsonl"),
      p.embeddings.resolve("objects/data/scholarchain-local-text"),
    ).filterNot(Files.exists(_)).map(_.getFileName.toString)

/** Minimal RFC 4180 reader for the Neptune bulk-load CSVs (quotes, doubled quotes, no newlines in
  * fields).
  */
object LocalCsv:
  def parseLine(line: String): Vector[String] =
    val out = Vector.newBuilder[String]
    val sb  = new StringBuilder
    var q   = false
    var i   = 0
    while i < line.length do
      val c = line.charAt(i)
      if q then
        if c == '"' then
          if i + 1 < line.length && line.charAt(i + 1) == '"' then
            sb.append('"')
            i += 1
          else q = false
        else sb.append(c)
      else if c == '"' then q = true
      else if c == ',' then
        out += sb.toString
        sb.clear()
      else sb.append(c)
      i += 1
    out += sb.toString
    out.result()

  /** Header-keyed rows of a CSV file. */
  def read(path: Path): List[Map[String, String]] =
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList
    val head  = parseLine(lines.head)
    lines.tail.filter(_.nonEmpty).map(l => head.zip(parseLine(l)).toMap)

/** A `GraphStore` that serves the five read templates from the Neptune CSVs. `COAUTHORED_WITH` is
  * undirected; every template filters on the `window` label; bounds are honored (rows are cut at
  * `maxResults`, a path longer than `maxHops` is not returned, `maxHops > maxDepth` is rejected).
  * Ties in shortest-path are broken by researcher ID, so a run is deterministic. Every query is
  * recorded.
  */
final class LocalGraphStore(neptune: Path) extends GraphStore:
  private final case class E(
      id: String,
      a: String,
      b: String,
      window: String,
      count: Long,
      first: Long,
      last: Long,
      digest: String,
  )

  private val edges: List[E] =
    LocalCsv.read(neptune.resolve("edges/COAUTHORED_WITH.csv")).map { r =>
      E(
        r(":ID"),
        r(":START_ID"),
        r(":END_ID"),
        r("window:String"),
        r("jointPublicationCount:Int").toLong,
        r("firstCollaborationYear:Int").toLong,
        r("lastCollaborationYear:Int").toLong,
        r("supportingWorksDigest:String"),
      )
    }

  private val byNode: Map[(String, String), List[(String, E)]] =
    edges
      .flatMap(e => List((e.window, e.a) -> (e.b -> e), (e.window, e.b) -> (e.a -> e)))
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .toMap

  private val componentSize: Map[String, Long] =
    LocalCsv
      .read(neptune.resolve("vertices/Component.csv"))
      .map(r => r(":ID") -> r("size:Long").toLong)
      .toMap

  private val inComponent: Map[(String, String), String] =
    LocalCsv
      .read(neptune.resolve("edges/IN_COMPONENT.csv"))
      .map(r => (r(":START_ID"), r("window:String")) -> r(":END_ID"))
      .toMap

  private val communities: Map[String, Map[String, String]] =
    LocalCsv.read(neptune.resolve("vertices/Community.csv")).map(r => r(":ID") -> r).toMap

  private val memberOf: Map[(String, String), String] =
    LocalCsv
      .read(neptune.resolve("edges/MEMBER_OF.csv"))
      .map(r => (r(":START_ID"), r("window:String")) -> r(":END_ID"))
      .toMap

  private val log = mutable.ListBuffer.empty[GraphQuery]

  def queries: List[GraphQuery] = synchronized(log.toList)

  private def str(q: GraphQuery, k: String): Option[String] =
    q.parameters.get(k).collect { case GraphValue.Str(s) => s }

  private def edgeColumns(e: E): Map[String, GraphValue] = Map(
    "edgeId"                 -> GraphValue.Str(e.id),
    "window"                 -> GraphValue.Str(e.window),
    "jointPublicationCount"  -> GraphValue.Num(e.count),
    "firstCollaborationYear" -> GraphValue.Num(e.first),
    "lastCollaborationYear"  -> GraphValue.Num(e.last),
    "supportingWorksDigest"  -> GraphValue.Str(e.digest),
  )

  private def shortest(from: String, to: String, window: String, maxHops: Int): Option[List[E]] =
    if from == to then None
    else
      val parent = mutable.Map.empty[String, (String, E)]
      var level  = List(from)
      val seen   = mutable.Set(from)
      var depth  = 0
      var found  = false
      while !found && level.nonEmpty && depth < maxHops do
        depth += 1
        val next = mutable.ListBuffer.empty[String]
        for node <- level.sorted if !found do
          val ns = byNode.getOrElse((window, node), Nil).sortBy(_._1)
          for (other, e) <- ns if !seen.contains(other) do
            seen += other
            parent(other) = (node, e)
            next += other
            if other == to then found = true
        level = next.toList
      if !found then None
      else
        var cur = to
        var acc = List.empty[E]
        while cur != from do
          val (p, e) = parent(cur)
          acc = e :: acc
          cur = p
        Some(acc)

  def query(q: GraphQuery): StorageResult[GraphResult] =
    synchronized { log += q }
    if !GraphTemplates.all.contains(q.template) then Left(StorageError.InvalidRequest("template"))
    else
      QueryBounds.validate(q.bounds) match
        case Left(_)  => Left(StorageError.InvalidRequest("bounds"))
        case Right(b) =>
          val window                                                = str(q, "window").getOrElse("")
          def cut(rows: List[GraphRow]): StorageResult[GraphResult] =
            Right(GraphResult(rows.take(b.maxResults), rows.size > b.maxResults))
          q.template match
            case GraphTemplates.Neighbors =>
              val me = str(q, "researcherId").getOrElse("")
              cut(
                byNode
                  .getOrElse((window, me), Nil)
                  .sortBy((o, e) => (-e.count, o))
                  .map((o, e) => GraphRow(edgeColumns(e) + ("researcherId" -> GraphValue.Str(o))))
              )
            case GraphTemplates.CoauthorEdge =>
              val a = str(q, "researcherA").getOrElse("")
              val c = str(q, "researcherB").getOrElse("")
              cut(
                byNode
                  .getOrElse((window, a), Nil)
                  .filter(_._1 == c)
                  .map(_._2)
                  .sortBy(_.id)
                  .map(e => GraphRow(edgeColumns(e)))
              )
            case GraphTemplates.ShortestPath =>
              val max  = q.parameters.get("maxHops").collect { case GraphValue.Num(n) => n.toInt }
              val hops = max.getOrElse(b.maxDepth)
              if hops > b.maxDepth then Left(StorageError.InvalidRequest("depth"))
              else
                val from = str(q, "from").getOrElse("")
                val to   = str(q, "to").getOrElse("")
                cut(shortest(from, to, window, hops).toList.map { p =>
                  val nodes = p.foldLeft(List(from)) { (acc, e) =>
                    acc :+ (if e.a == acc.last then e.b else e.a)
                  }
                  GraphRow(
                    Map(
                      "hops"    -> GraphValue.Lst(nodes.map(GraphValue.Str(_))),
                      "edgeIds" -> GraphValue.Lst(p.map(e => GraphValue.Str(e.id))),
                      "length"  -> GraphValue.Num(p.size.toLong),
                    )
                  )
                })
            case GraphTemplates.Component =>
              val me = str(q, "researcherId").getOrElse("")
              cut(inComponent.get((me, window)).toList.map { c =>
                GraphRow(
                  Map(
                    "componentId" -> GraphValue.Str(c),
                    "size"        -> GraphValue.Num(componentSize.getOrElse(c, 0L)),
                    "window"      -> GraphValue.Str(window),
                  )
                )
              })
            case GraphTemplates.Community =>
              val me = str(q, "researcherId").getOrElse("")
              cut(
                memberOf.get((me, window)).flatMap(c => communities.get(c).map(c -> _)).toList.map {
                  (c, r) =>
                    GraphRow(
                      Map(
                        "communityId"       -> GraphValue.Str(c),
                        "size"              -> GraphValue.Num(r("size:Long").toLong),
                        "method"            -> GraphValue.Str(r("method:String")),
                        "window"            -> GraphValue.Str(window),
                        "internalEdgeCount" -> GraphValue.Num(r("internalEdgeCount:Long").toLong),
                        "crossEdgeCount"    -> GraphValue.Num(r("crossEdgeCount:Long").toLong),
                      )
                    )
                }
              )
            case _ => Right(GraphResult(Nil, false))

  def submitBulkLoad(r: BulkLoadRequest): StorageResult[BulkLoadHandle] =
    Left(StorageError.InvalidRequest("read-only"))

  def bulkLoadStatus(h: BulkLoadHandle): StorageResult[BulkLoadStatus] =
    Left(StorageError.InvalidRequest("read-only"))

/** Reads the text bucket from the Phase 08 objects (read-only); the audit bucket goes to its own
  * store. Writes outside the audit bucket are refused and recorded.
  */
final class LocalRoutedObjects(text: ObjectStore, audit: ObjectStore, auditBucket: String)
    extends ObjectStore:
  private val writes   = mutable.ListBuffer.empty[String]
  private val refusals = mutable.ListBuffer.empty[String]

  def auditWrites: List[String] = synchronized(writes.toList)
  def refused: List[String]     = synchronized(refusals.toList)

  private def target(l: ObjectLocation): ObjectStore =
    if l.bucket == auditBucket then audit else text

  def put(
      l: ObjectLocation,
      b: Array[Byte],
      ct: Option[String],
      h: Option[String],
  ): StorageResult[ObjectMeta] =
    if l.bucket != auditBucket then
      synchronized { refusals += l.uri }
      Left(StorageError.InvalidRequest("read-only bucket"))
    else
      synchronized { writes += l.uri }
      audit.put(l, b, ct, h)

  def putIfAbsent(
      l: ObjectLocation,
      b: Array[Byte],
      ct: Option[String],
      h: Option[String],
  ): StorageResult[ObjectMeta] =
    if l.bucket != auditBucket then
      synchronized { refusals += l.uri }
      Left(StorageError.InvalidRequest("read-only bucket"))
    else
      synchronized { writes += l.uri }
      audit.putIfAbsent(l, b, ct, h)

  def get(l: ObjectLocation): StorageResult[ObjectData]          = target(l).get(l)
  def head(l: ObjectLocation): StorageResult[Option[ObjectMeta]] = target(l).head(l)
  def list(b: String, p: String, n: Int, t: Option[String]): StorageResult[ObjectPage] =
    (if b == auditBucket then audit else text).list(b, p, n, t)

/** Loaded local stores. */
final class LocalStores(paths: LocalFixturePaths, tables: RuntimeTables):
  val graph: GraphStore = new LocalGraphStore(paths.l4.resolve("neptune"))

  private val schemas: Map[String, KvTableSchema] =
    AnalyticsRepository.HasSortKey.map((logical, sort) =>
      tables.analytics(logical) -> KvTableSchema(sort)
    ) ++ Map(
      tables.querySession   -> KvTableSchema(false),
      tables.evidenceBundle -> KvTableSchema(true),
    )

  val kv: KeyValueStore =
    val store = new InMemoryKeyValueStore(schemas)
    AnalyticsRepository.HasSortKey.keys.toList.sorted.foreach { logical =>
      val path = paths.l4.resolve(s"dynamodb/items/$logical.jsonl")
      if Files.exists(path) then
        Files.lines(path, StandardCharsets.UTF_8).iterator.asScala.filter(_.nonEmpty).foreach {
          line =>
            val j       = ujson.read(line)
            val payload = ujson.write(j("attributes"))
            val item    = KvItem(
              KvKey(j("pk").str, j.obj.get("sk").flatMap(_.strOpt)),
              Map("payload" -> payload, "payloadHash" -> Hashing.textHash(payload)),
            )
            store.put(tables.analytics(logical), item, PutCondition.Always) match
              case Right(_) => ()
              case Left(e) => throw new IllegalStateException(s"loading $logical failed: ${e.code}")
        }
    }
    store

  val vectors: VectorStore =
    val store = new InMemoryVectorStore(VectorIndexes.all.map(_ -> 1024).toMap)
    val lines = Files
      .readAllLines(paths.embeddings.resolve("vectors.jsonl"), StandardCharsets.UTF_8)
      .asScala
      .toList
      .filter(_.nonEmpty)
    val entries = lines.map { line =>
      val j      = ujson.read(line)
      val record = Wire
        .read[VectorRecord](ujson.write(j("record")))
        .fold(e => throw new IllegalStateException(s"vector record: ${e.code}"), identity)
      val dense = Array.fill(j("dimension").num.toInt)(0f)
      j("nonZero").arr.foreach(p => dense(p(0).num.toInt) = p(1).num.toFloat)
      j("index").str -> VectorEntry(record, dense.toVector)
    }
    entries.groupBy(_._1).toList.sortBy(_._1).foreach { (index, es) =>
      es.map(_._2).grouped(400).foreach { batch =>
        store.putBatch(index, batch) match
          case Right(rs) =>
            rs.foreach(r =>
              if r.outcome != VectorPutOutcome.Accepted then
                throw new IllegalStateException(s"vector ${r.vectorKey} rejected")
            )
          case Left(e) => throw new IllegalStateException(s"vector load failed: ${e.code}")
      }
    }
    store

  val objects: ObjectStore = new LocalRoutedObjects(
    new FileObjectStore(paths.embeddings.resolve("objects")),
    new FileObjectStore(paths.auditDir),
    tables.auditBucket,
  )

  val embedder: Embedder = EmbedderFactory.localTest(1024)
