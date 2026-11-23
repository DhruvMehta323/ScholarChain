package scholarchain.rag.runtime

import java.nio.charset.StandardCharsets

import scala.collection.mutable

import scholarchain.core.*
import scholarchain.rag.EmbedderFactory

/** Test doubles for the core ports and a small synthetic world (no real data, key or endpoint). The
  * rag module may not depend on storage, so these are minimal local fakes of the core ports.
  */
final class MemKv extends KeyValueStore:
  val data: mutable.LinkedHashMap[(String, String, String), KvItem] =
    mutable.LinkedHashMap.empty

  private def slot(t: String, k: KvKey) = (t, k.partition, k.sort.getOrElse(""))

  def put(table: String, item: KvItem, condition: PutCondition): StorageResult[PutOutcome] =
    synchronized {
      val existing = data.get(slot(table, item.key))
      val allowed  = condition match
        case PutCondition.Always                => true
        case PutCondition.NotExists             => existing.isEmpty
        case PutCondition.AttributeEquals(n, v) => existing.exists(_.attributes.get(n).contains(v))
      if allowed then
        data(slot(table, item.key)) = item
        Right(PutOutcome.Written)
      else Right(PutOutcome.ConditionFailed(existing))
    }

  def get(table: String, key: KvKey): StorageResult[Option[KvItem]] =
    synchronized(Right(data.get(slot(table, key))))

  def query(
      table: String,
      partition: String,
      sort: Option[SortCondition],
      limit: Int,
      ascending: Boolean,
      continuationToken: Option[String],
  ): StorageResult[KvPage] =
    synchronized {
      def ok(sk: String): Boolean = sort match
        case None                                  => true
        case Some(SortCondition.Equals(v))         => sk == v
        case Some(SortCondition.BeginsWith(p))     => sk.startsWith(p)
        case Some(SortCondition.Between(l, h))     => sk >= l && sk <= h
        case Some(SortCondition.LessThan(v))       => sk < v
        case Some(SortCondition.LessOrEqual(v))    => sk <= v
        case Some(SortCondition.GreaterThan(v))    => sk > v
        case Some(SortCondition.GreaterOrEqual(v)) => sk >= v
      val items = data
        .collect {
          case ((t, pk, sk), i) if t == table && pk == partition && ok(sk) => sk -> i
        }
        .toList
        .sortBy(_._1)
        .map(_._2)
      Right(KvPage((if ascending then items else items.reverse).take(limit), None))
    }

  def tableItems(table: String): List[KvItem] =
    synchronized(data.collect { case ((t, _, _), i) if t == table => i }.toList)

final class MemObjects extends ObjectStore:
  val data: mutable.LinkedHashMap[ObjectLocation, Array[Byte]] = mutable.LinkedHashMap.empty

  private def meta(l: ObjectLocation, b: Array[Byte]) =
    ObjectMeta(l, b.length.toLong, Hashing.sha256Hex(b), None, None, None)

  def put(
      l: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String],
      expectedHash: Option[String],
  ): StorageResult[ObjectMeta] = synchronized {
    data(l) = bytes
    Right(meta(l, bytes))
  }

  def putIfAbsent(
      l: ObjectLocation,
      bytes: Array[Byte],
      contentType: Option[String],
      expectedHash: Option[String],
  ): StorageResult[ObjectMeta] = synchronized {
    if data.contains(l) then Left(StorageError.AlreadyExists("object"))
    else
      data(l) = bytes
      Right(meta(l, bytes))
  }

  def get(l: ObjectLocation): StorageResult[ObjectData] = synchronized {
    data.get(l).map(b => ObjectData(meta(l, b), b)).toRight(StorageError.NotFound("object"))
  }

  def head(l: ObjectLocation): StorageResult[Option[ObjectMeta]] =
    synchronized(Right(data.get(l).map(meta(l, _))))

  def list(
      bucket: String,
      prefix: String,
      limit: Int,
      continuationToken: Option[String],
  ): StorageResult[ObjectPage] = synchronized {
    Right(
      ObjectPage(
        data
          .collect {
            case (l, b) if l.bucket == bucket && l.objectKey.startsWith(prefix) => meta(l, b)
          }
          .toList
          .sortBy(_.location.objectKey)
          .take(limit),
        None,
      )
    )
  }

final class MemVectors extends VectorStore:
  private val data = mutable.LinkedHashMap.empty[(String, String), VectorEntry]

  def add(index: String, e: VectorEntry): Unit = synchronized {
    data((index, e.record.vectorKey)) = e
  }

  /** The stored metadata is the record's plus the text hash and pointer, as in storage. */
  private def stored(e: VectorEntry): Map[String, String] =
    e.record.metadata ++ Map("textHash" -> e.record.textHash, "textS3Uri" -> e.record.textS3Uri)

  def putBatch(index: String, vs: List[VectorEntry]): StorageResult[List[VectorPutResult]] =
    synchronized {
      vs.foreach(add(index, _))
      Right(vs.map(v => VectorPutResult(v.record.vectorKey, VectorPutOutcome.Accepted)))
    }

  private def matches(f: MetadataFilter, m: Map[String, String]): Boolean = f match
    case MetadataFilter.Equals(n, v) => m.get(n).contains(v)
    case MetadataFilter.In(n, vs)    => m.get(n).exists(vs.contains)
    case MetadataFilter.And(fs)      => fs.forall(matches(_, m))

  private def cosine(a: Vector[Float], b: Vector[Float]): Double =
    val dot = a.zip(b).map((x, y) => x.toDouble * y.toDouble).sum
    val na  = math.sqrt(a.map(x => x.toDouble * x).sum)
    val nb  = math.sqrt(b.map(x => x.toDouble * x).sum)
    if na == 0 || nb == 0 then 0.0 else dot / (na * nb)

  def query(index: String, q: VectorQuery): StorageResult[List[VectorHit]] = synchronized {
    val hits = data
      .collect {
        case ((i, _), e) if i == index && q.filter.forall(matches(_, stored(e))) =>
          VectorHit(e.record.vectorKey, cosine(q.embedding, e.embedding), stored(e))
      }
      .toList
      .sortBy(h => (-h.score, h.vectorKey))
    Right(hits.take(q.topK))
  }

  def get(index: String, key: String): StorageResult[Option[VectorEntry]] =
    synchronized(Right(data.get((index, key))))

/** One undirected coauthor edge of the fake graph. */
final case class FEdge(
    a: String,
    b: String,
    id: String,
    count: Long,
    first: Long,
    last: Long,
    window: String,
)

/** A `GraphStore` over a tiny edge list that honors the template contracts of storage's catalog:
  * parameter names, `window` filter, `maxHops` and `bounds`. Every query is recorded.
  */
final class FakeGraph(
    edges: List[FEdge],
    components: Map[(String, String), (String, Long)] = Map.empty,
    communities: Map[(String, String), ujson.Obj] = Map.empty,
    val shortestPathOverride: Option[GraphRow] = None,
) extends GraphStore:
  val queries: mutable.ListBuffer[GraphQuery] = mutable.ListBuffer.empty

  private def str(q: GraphQuery, k: String): Option[String] =
    q.parameters.get(k).collect { case GraphValue.Str(s) => s }

  private def edgeRow(e: FEdge): Map[String, GraphValue] = Map(
    "edgeId"                 -> GraphValue.Str(e.id),
    "window"                 -> GraphValue.Str(e.window),
    "jointPublicationCount"  -> GraphValue.Num(e.count),
    "firstCollaborationYear" -> GraphValue.Num(e.first),
    "lastCollaborationYear"  -> GraphValue.Num(e.last),
    "supportingWorksDigest"  -> GraphValue.Str(Hashing.textHash(e.id)),
  )

  private def adj(window: String, x: String): List[(String, FEdge)] =
    edges.filter(e => e.window == window && (e.a == x || e.b == x)).map { e =>
      (if e.a == x then e.b else e.a) -> e
    }

  private def bfs(from: String, to: String, window: String, maxHops: Int): Option[List[FEdge]] =
    var frontier = List(from -> List.empty[FEdge])
    val seen     = mutable.Set(from)
    var depth    = 0
    while frontier.nonEmpty && depth < maxHops do
      depth += 1
      val next = frontier.flatMap { (node, path) =>
        adj(window, node).sortBy(_._1).filterNot(n => seen.contains(n._1)).map { (n, e) =>
          (n, path :+ e)
        }
      }
      next.find(_._1 == to) match
        case Some((_, p)) => return Some(p)
        case None         =>
      next.foreach(n => seen += n._1)
      frontier = next.distinctBy(_._1)
    None

  def query(q: GraphQuery): StorageResult[GraphResult] = synchronized {
    queries += q
    if !GraphTemplates.all.contains(q.template) then Left(StorageError.InvalidRequest("template"))
    else
      val window = str(q, "window").getOrElse("")
      q.template match
        case GraphTemplates.Neighbors =>
          val me   = str(q, "researcherId").getOrElse("")
          val rows = adj(window, me).sortBy((o, e) => (-e.count, o))
          val cut  = rows.take(q.bounds.maxResults)
          Right(
            GraphResult(
              cut.map((o, e) => GraphRow(edgeRow(e) + ("researcherId" -> GraphValue.Str(o)))),
              rows.size > cut.size,
            )
          )
        case GraphTemplates.CoauthorEdge =>
          val a = str(q, "researcherA").getOrElse("")
          val b = str(q, "researcherB").getOrElse("")
          Right(
            GraphResult(
              edges
                .filter(e => e.window == window && Set(e.a, e.b) == Set(a, b))
                .map(e => GraphRow(edgeRow(e)))
                .take(q.bounds.maxResults),
              false,
            )
          )
        case GraphTemplates.ShortestPath =>
          shortestPathOverride match
            case Some(row) => Right(GraphResult(List(row), false))
            case None      =>
              val from = str(q, "from").getOrElse("")
              val to   = str(q, "to").getOrElse("")
              val hops = q.parameters.get("maxHops").collect { case GraphValue.Num(n) => n.toInt }
              val max  = hops.getOrElse(q.bounds.maxDepth)
              if max > q.bounds.maxDepth then Left(StorageError.InvalidRequest("depth bound"))
              else
                Right(
                  GraphResult(
                    bfs(from, to, window, max).toList.map { p =>
                      val nodes = from :: p.foldLeft(List.empty[String]) { (acc, e) =>
                        val prev = acc.lastOption.getOrElse(from)
                        acc :+ (if e.a == prev then e.b else e.a)
                      }
                      GraphRow(
                        Map(
                          "hops"    -> GraphValue.Lst(nodes.map(GraphValue.Str(_))),
                          "edgeIds" -> GraphValue.Lst(p.map(e => GraphValue.Str(e.id))),
                          "length"  -> GraphValue.Num(p.size.toLong),
                        )
                      )
                    },
                    false,
                  )
                )
        case GraphTemplates.Component =>
          val me = str(q, "researcherId").getOrElse("")
          Right(
            GraphResult(
              components
                .get((me, window))
                .toList
                .map((c, n) =>
                  GraphRow(
                    Map(
                      "componentId" -> GraphValue.Str(c),
                      "size"        -> GraphValue.Num(n),
                      "window"      -> GraphValue.Str(window),
                    )
                  )
                ),
              false,
            )
          )
        case GraphTemplates.Community =>
          val me = str(q, "researcherId").getOrElse("")
          Right(
            GraphResult(
              communities.get((me, window)).toList.map { o =>
                GraphRow(
                  o.value.map { (k, v) =>
                    k -> (v match
                      case ujson.Str(s) => GraphValue.Str(s)
                      case ujson.Num(d) => GraphValue.Num(d.toLong)
                      case _            => GraphValue.Null)
                  }.toMap
                )
              },
              false,
            )
          )
        case _ => Right(GraphResult(Nil, false))
  }

  def submitBulkLoad(r: BulkLoadRequest): StorageResult[BulkLoadHandle] =
    Left(StorageError.InvalidRequest("read-only fake"))

  def bulkLoadStatus(h: BulkLoadHandle): StorageResult[BulkLoadStatus] =
    Left(StorageError.InvalidRequest("read-only fake"))

object FakeGraph:
  def noGraph: FakeGraph = new FakeGraph(Nil)

/** The synthetic world used by the unit suites. */
object World:
  val W: String   = "2000-2004"
  val W2: String  = "2005-2009"
  val All: String = "1000-9999"

  def ok[A](e: Either[ValidationError, A]): A =
    e.fold(err => throw new AssertionError(err.message), identity)

  val run: String = ok(
    Ids.runId("spark-graph", "0.1.0", 1, Map("k" -> "v"), List(InputRef("t", "v", Some("a" * 64))))
  )

  def rid(name: String): String = ok(Ids.researcherIdDblpPerson(name))

  val ada: String   = rid("Ada Lovelace")
  val alan: String  = rid("Alan Turing")
  val grace: String = rid("Grace Hopper")
  val hedy: String  = rid("Hedy Lamarr")
  val lone: String  = rid("Lone Wolf")
  val yu1: String   = rid("Yu Liu 0001")
  val yu2: String   = rid("Yu Liu 0002")
  val wei1: String  = rid("Wei Chen 0001")
  val sam1: String  = rid("Sam Rivera#1")
  val sam2: String  = rid("Sam Rivera#2")
  val gwen: String  = rid("Gwen Offgraph")
  val tam: String   = rid("Tam Per")
  val cal: String   = rid("Cal Link")

  def mention(name: String): String = "mention:" + Hashing.textHash("m:" + name)
  def edgeId(n: String): String     = "edge:coauthor:" + Hashing.textHash(n)

  val eAdaAlan: FEdge   = FEdge(ada, alan, edgeId("ada-alan"), 2, 2001, 2003, W)
  val eAlanGrace: FEdge = FEdge(alan, grace, edgeId("alan-grace"), 3, 2000, 2004, W)
  val eGraceHedy: FEdge = FEdge(grace, hedy, edgeId("grace-hedy"), 1, 2002, 2002, W)

  val works: Map[String, List[String]] = Map(
    eAdaAlan.id   -> List("work:dblp:conf/x/AB1", "work:dblp:conf/x/AB2"),
    eAlanGrace.id -> List("work:dblp:conf/x/BG1", "work:dblp:conf/x/BG2", "work:dblp:conf/x/BG3"),
    eGraceHedy.id -> List("work:dblp:conf/x/GH1"),
  )

  def communityOf(id: String, w: String): String = s"community:$w:$id"
  def componentOf(id: String, w: String): String = s"component:$w:$id"

  def graph: FakeGraph = new FakeGraph(
    List(eAdaAlan, eAlanGrace, eGraceHedy),
    components = Map(
      (ada, W)  -> (componentOf(ada, W), 4L),
      (alan, W) -> (componentOf(ada, W), 4L),
    ),
    communities = Map(
      (ada, W) -> ujson.Obj(
        "communityId"       -> communityOf(ada, W),
        "size"              -> 3,
        "method"            -> "label_propagation.v1",
        "window"            -> W,
        "internalEdgeCount" -> 2,
        "crossEdgeCount"    -> 1,
      ),
      (grace, W) -> ujson.Obj(
        "communityId"       -> communityOf(grace, W),
        "size"              -> 2,
        "method"            -> "label_propagation.v1",
        "window"            -> W,
        "internalEdgeCount" -> 1,
        "crossEdgeCount"    -> 3,
      ),
    ),
  )

  def metricsAttrs(
      id: String,
      w: String,
      pub: Int,
      co: Int,
      wd: Int,
      cross: Int,
      avgTeam: Int = 3,
      maxTeam: Int = 6,
  ): ujson.Obj = ujson.Obj(
    "publicationCount"         -> pub,
    "coauthorCount"            -> co,
    "teamSizeTotal"            -> (avgTeam * pub),
    "avgTeamSize"              -> avgTeam,
    "maxTeamSize"              -> maxTeam,
    "weightedDegree"           -> wd,
    "maxTeamWorkCount"         -> 1,
    "maxTeamWorkIds"           -> ujson.Arr("work:dblp:conf/x/Big"),
    "communityId"              -> communityOf(id, w),
    "componentId"              -> componentOf(id, w),
    "coreness"                 -> 2,
    "crossCommunityDegree"     -> cross,
    "participationCoefficient" -> 0.5,
    "communitiesTouched"       -> 2,
    "policyVersion"            -> "coauthor-graph.v1",
    "provenanceRunId"          -> run,
    "researcherId"             -> id,
    "window"                   -> w,
  )

  def tableOf(logical: String): String = RuntimeTables.defaults.analytics(logical)

  def putAnalytics(
      kv: MemKv,
      logical: String,
      pk: String,
      sk: Option[String],
      attrs: ujson.Obj,
      tamper: Boolean = false,
  ): Unit =
    val payload = ujson.write(attrs)
    val hash    = if tamper then Hashing.textHash("tampered") else Hashing.textHash(payload)
    kv.put(
      tableOf(logical),
      KvItem(
        KvKey(
          AnalyticsReader.encodeComponent(pk),
          sk.map(s => AnalyticsReader.encodeSort(List(s))),
        ),
        Map("payload" -> payload, "payloadHash" -> hash),
      ),
      PutCondition.Always,
    )

  private def cluster(
      kv: MemKv,
      id: String,
      display: String,
      keys: List[String],
      rule: String = "dblp_person_exact",
      cannotLink: String = "clear",
      tamper: Boolean = false,
  ): Unit =
    val m = mention(id)
    putAnalytics(
      kv,
      "identity-clusters",
      id,
      None,
      ujson.Obj(
        "baseIdentityIds"  -> ujson.Arr(id),
        "cannotLinkStatus" -> cannotLink,
        "dblpPersonKeys"   -> ujson.Arr.from(keys.map(ujson.Str(_))),
        "displayName"      -> display,
        "memberCount"      -> 1,
        "memberMentionIds" -> ujson.Arr(m),
        "orcids"           -> ujson.Arr(),
        "policyVersion"    -> "er-v1",
        "researcherId"     -> id,
        "ruleFired"        -> rule,
      ),
      tamper,
    )
    putAnalytics(
      kv,
      "mention-researcher",
      m,
      None,
      ujson.Obj("baseIdentityId" -> id, "mentionId" -> m, "researcherId" -> id),
    )

  def kv: MemKv =
    val kv = new MemKv
    cluster(kv, ada, "Ada Lovelace", List("Ada Lovelace"))
    cluster(kv, alan, "Alan Turing", List("Alan Turing"))
    cluster(kv, grace, "Grace Hopper", List("Grace Hopper"))
    cluster(kv, hedy, "Hedy Lamarr", List("Hedy Lamarr"))
    cluster(kv, lone, "Lone Wolf", List("Lone Wolf"))
    cluster(kv, yu1, "Yu Liu 0001", List("Yu Liu 0001"))
    cluster(kv, yu2, "Yu Liu 0002", List("Yu Liu 0002"))
    cluster(kv, wei1, "Wei Chen 0001", List("Wei Chen 0001"))
    cluster(kv, sam1, "Sam Rivera", List("Sam Rivera"))
    cluster(kv, sam2, "Sam Rivera", List("Sam Rivera"))
    cluster(kv, gwen, "Gwen Offgraph", List("Gwen Offgraph"))
    cluster(kv, tam, "Tam Per", List("Tam Per"), tamper = true)
    cluster(kv, cal, "Cal Link", List("Cal Link"), cannotLink = "violated")
    // all-time metrics: every researcher except Gwen is in the graph
    List(ada, alan, grace, hedy, lone, yu1, yu2, wei1, sam1, sam2, tam, cal).foreach { id =>
      putAnalytics(
        kv,
        "researcher-metrics",
        id,
        Some(All),
        metricsAttrs(id, All, 9, 4, 9, 1),
        false,
      )
    }
    putAnalytics(kv, "researcher-metrics", ada, Some(W), metricsAttrs(ada, W, 7, 1, 2, 1))
    putAnalytics(kv, "researcher-metrics", alan, Some(W), metricsAttrs(alan, W, 5, 2, 5, 0))
    putAnalytics(kv, "researcher-metrics", grace, Some(W), metricsAttrs(grace, W, 6, 8, 4, 6))
    putAnalytics(kv, "researcher-metrics", hedy, Some(W), metricsAttrs(hedy, W, 2, 6, 1, 0))
    putAnalytics(kv, "researcher-metrics", ada, Some(W2), metricsAttrs(ada, W2, 4, 5, 7, 1))
    putAnalytics(
      kv,
      "researcher-metrics",
      lone,
      Some(W),
      metricsAttrs(lone, W, 1, 1, 1, 0),
      tamper = true,
    )
    putAnalytics(
      kv,
      "window-summary",
      W,
      None,
      ujson.Obj(
        "communityCount"           -> 12,
        "componentCount"           -> 11,
        "edgeCount"                -> 80,
        "largestComponentFraction" -> 0.25,
        "largestComponentSize"     -> 40,
        "maxCoreness"              -> 5,
        "maxTeamSize"              -> 6,
        "maxTeamWorkId"            -> "work:dblp:conf/x/Big",
        "nodeCount"                -> 100,
        "singletonCount"           -> 0,
        "window"                   -> W,
        "workCount"                -> 50,
      ),
    )
    putAnalytics(
      kv,
      "temporal-changes",
      ada,
      Some(W2),
      ujson.Obj(
        "status"            -> "continuing",
        "prevWindow"        -> W,
        "coauthorCount"     -> 5,
        "prevCoauthorCount" -> 1,
        "newCoauthors"      -> 4,
        "lostCoauthors"     -> 0,
        "retainedCoauthors" -> 1,
        "window"            -> W2,
      ),
    )
    works.foreach { (eid, ws) =>
      ws.foreach { w =>
        putAnalytics(
          kv,
          "coauthor-edge-works",
          eid,
          Some(w),
          ujson.Obj("edgeId" -> eid, "window" -> W, "workId" -> w),
        )
      }
    }
    kv

  val embedder: Embedder = EmbedderFactory.localTest(64)

  private def vec(text: String): Vector[Float] = embedder.embed(List(text)).toOption.get.head

  def vectorEntry(
      index: String,
      docType: String,
      entity: String,
      window: GraphWindow,
      text: String,
      objects: Option[MemObjects] = None,
      storeHash: Option[String] = None,
      store: Boolean = true,
  ): VectorEntry =
    val hash = Hashing.textHash(text)
    val uri  = s"s3://scholarchain-local-text/rag/text/$hash.txt"
    if store then
      objects.foreach(
        _.put(
          ObjectLocation("scholarchain-local-text", s"rag/text/$hash.txt"),
          text.getBytes(StandardCharsets.UTF_8),
          None,
          None,
        )
      )
    VectorEntry(
      VectorRecord(
        1,
        EmbeddingDocument.idFor(docType, entity, window, 0),
        index,
        64,
        embedder.modelId,
        embedder.modelVersion,
        storeHash.getOrElse(hash),
        uri,
        Map(
          "entityId"     -> entity,
          "window"       -> GraphWindow.label(window),
          "documentType" -> docType,
          "chunkCount"   -> "1",
          "chunkOrdinal" -> "0",
        ),
        run,
      ),
      vec(text),
    )

  val allTime: GraphWindow = GraphWindow(1000, 9999)

  def vectors(objects: MemObjects): MemVectors =
    val v                                    = new MemVectors
    def prof(id: String, name: String): Unit =
      v.add(
        VectorIndexes.ResearcherProfile,
        vectorEntry(
          VectorIndexes.ResearcherProfile,
          EmbeddingDocumentTypes.ResearcherProfile,
          id,
          allTime,
          s"$name researcher profile",
          Some(objects),
        ),
      )
    prof(ada, "Ada Lovelace")
    prof(alan, "Alan Turing")
    prof(grace, "Grace Hopper")
    prof(hedy, "Hedy Lamarr")
    prof(lone, "Lone Wolf")
    prof(yu1, "Yu Liu 0001")
    prof(yu2, "Yu Liu 0002")
    prof(wei1, "Wei Chen 0001")
    prof(sam1, "Sam Rivera")
    prof(sam2, "Sam Rivera")
    prof(gwen, "Gwen Offgraph")
    prof(tam, "Tam Per")
    prof(cal, "Cal Link")
    v

  lazy val prompts: PromptRepository =
    PromptRepository.locate().fold(e => throw new AssertionError(e.message), identity)

  lazy val settings: RuntimeSettings =
    RuntimeConfigLoader
      .load(Map.empty, Map(ModuleConfig.overrideName(RuntimeConfigKeys.Provider) -> "fake"))
      .fold(es => throw new AssertionError(es.toString), _.settings)

  val clock: RuntimeClock = RuntimeClock.Fixed(1700000000000L)

  final case class Env(
      kv: MemKv,
      graph: FakeGraph,
      objects: MemObjects,
      vectors: MemVectors,
      model: FakeModelClient,
      workflow: QueryWorkflow,
  )

  def env(
      model: FakeModelClient = new FakeModelClient(responder = Some(BenchmarkResponders.responder)),
      graph: FakeGraph = World.graph,
      settings: RuntimeSettings = World.settings,
      llm: Boolean = false,
      extraVectors: (MemVectors, MemObjects) => Unit = (_, _) => (),
  ): Env =
    val k = World.kv
    val o = new MemObjects
    val v = vectors(o)
    extraVectors(v, o)
    Env(
      k,
      graph,
      o,
      v,
      model,
      QueryWorkflow.create(
        settings,
        model,
        k,
        graph,
        v,
        o,
        embedder,
        prompts,
        RuntimeTables.defaults,
        clock,
        llm,
      ),
    )

  def reader(kv: MemKv): AnalyticsReader = new AnalyticsReader(kv, RuntimeTables.defaults)

  def caller(model: ModelClient, s: RuntimeSettings = settings): StructuredModelCaller =
    new StructuredModelCaller(model, s, clock)

  def planOf(
      question: String = "q",
      names: List[String] = Nil,
      ops: List[QueryOperationV2] = List(QueryOperationV2.SemanticExplanation),
      ids: List[String] = Nil,
      window: Option[GraphWindow] = None,
      metric: Option[CollaborationMetric] = None,
  ): QueryPlanV2 =
    QueryPlannerAgent
      .ruleBased(
        "query:" + Hashing.textHash(question),
        question,
        StructuredConstraints(
          operations = ops,
          researcherNames = names,
          subjectIds = ids,
          window = window,
          metric = metric,
        ),
        settings,
      )
      .fold(e => throw new AssertionError(e.message), identity)

  /** An evidence set built from stage drafts (deterministic first). */
  def evidence(det: List[EvidenceDraft], sem: List[EvidenceDraft] = Nil): EvidenceSet =
    new EvidenceBuilderAgent(settings)
      .build("query:" + Hashing.textHash("fixture"), det, sem)
      .fold(e => throw new AssertionError(e.message), identity)

  def fact(set: EvidenceSet, kind: String, subject: String): (EvidenceItem, Fact) =
    set.bundle.items
      .flatMap(i => i.text.flatMap(Fact.parse).map(i -> _))
      .find((_, f) => f.kind == kind && f.subject == subject)
      .getOrElse(throw new AssertionError(s"no $kind fact for $subject"))
