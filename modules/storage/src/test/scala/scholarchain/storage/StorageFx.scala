package scholarchain.storage

import java.nio.file.{Files, Path}
import java.time.Instant

import scala.jdk.CollectionConverters.*

import scholarchain.core.*

/** Shared synthetic fixtures for the storage suites. Synthetic values only: no real identifier,
  * secret, account or endpoint.
  */
object StorageFx:
  def ok[A](e: Either[ValidationError, A]): A = e match
    case Right(a)  => a
    case Left(err) => throw new AssertionError(s"fixture construction failed: ${err.message}")

  val hexA: String = "a" * 64

  val input: InputRef = InputRef("scholarchain-graph/coauthor-edges", "example-1", Some(hexA))

  def runIdFor(stage: String = "spark-graph", attempt: Int = 1): String =
    ok(Ids.runId(stage, "0.1.0", attempt, Map("k" -> "v"), List(input)))

  val run1: String = runIdFor()

  def prov(run: Option[String] = None): Provenance =
    Provenance(
      1,
      SourceType.ScholarChain,
      List(input),
      "0.1.0",
      Map("window" -> "2000-2004"),
      run,
      Some("2026-11-21T10:00:00Z"),
    )

  def researcher(name: String): String = ok(Ids.researcherIdDblpPerson(name))

  val createdAt: String   = "2026-11-21T10:00:00Z"
  val createdEpoch: Long  = Instant.parse(createdAt).getEpochSecond
  val workA: String       = "work:dblp:conf/sigmod/Foo01"
  val windowLabel: String = "2000-2004"
  val bucket: String      = "example-bucket"

  // ---- records --------------------------------------------------------------------------------

  def ledger(
      stage: String = "spark-graph",
      attempt: Int = 1,
      startedAt: String = "2026-11-21T10:00:00Z",
      status: RunStatus = RunStatus.Started,
  ): RunLedgerEntry =
    val params = Map("k" -> "v")
    val id     = ok(Ids.runId(stage, "0.1.0", attempt, params, List(input)))
    val fin    = status match
      case RunStatus.Started => None
      case _                 => Some("2026-11-21T11:00:00Z")
    val why = if status == RunStatus.Failed then Some("disk full") else None
    RunLedgerEntry(
      1,
      id,
      stage,
      attempt,
      status,
      "0.1.0",
      params,
      List(input),
      startedAt,
      fin,
      None,
      why,
    )

  def artifact(
      run: String = run1,
      tpe: String = "parquet-table",
      uri: String = s"s3://$bucket/graph/coauthor-edges/part-0000.parquet",
      size: Long = 1024L,
  ): ArtifactRegistryEntry =
    ArtifactRegistryEntry(1, run, uri, tpe, hexA, size, "2026-11-21T10:05:00Z", prov(Some(run)))

  def session(
      id: String = "session-0001",
      status: QuerySessionStatus = QuerySessionStatus.Received,
      ttlSeconds: Long = 3600L,
  ): QuerySessionRecord =
    QuerySessionRecord(
      1,
      id,
      "query:q-0001",
      Hashing.textHash("who collaborates with Ada?"),
      status,
      createdAt,
      createdEpoch + ttlSeconds,
      if status == QuerySessionStatus.Answered then Some(hexA) else None,
      None,
      prov(),
    )

  def mention(position: Int = 0): String = ok(Ids.mentionId(workA, position))

  def link(
      mentionId: String = mention(),
      policy: String = "er-policy.v1",
      key: String = "k1",
      expires: Option[Long] = None,
  ): EntityLinkCacheEntry =
    EntityLinkCacheEntry(
      1,
      key,
      mentionId,
      Some(researcher("Ada Lovelace")),
      MatchOutcome.Match,
      0.9,
      policy,
      run1,
      createdAt,
      expires,
      prov(Some(run1)),
    )

  def evidenceItem(text: String): EvidenceItem =
    val h = Hashing.textHash(text)
    EvidenceItem(
      1,
      ok(Ids.evidenceId(EvidenceType.TextChunk, "work:dblp:x", "v1", h)),
      EvidenceType.TextChunk,
      "work:dblp:x",
      "v1",
      Some(text),
      h,
      None,
      prov(),
    )

  def bundle(
      queryId: String = "query:q-0001",
      texts: List[String] = List("small text"),
  ): EvidenceBundle =
    EvidenceBundle(1, queryId, texts.map(evidenceItem), prov())

  def vector(
      key: String,
      text: String,
      index: String = VectorIndexes.ResearcherProfile,
      dim: Int = 3,
      md: Map[String, String] = Map("window" -> windowLabel),
  ): VectorRecord =
    VectorRecord(
      1,
      key,
      index,
      dim,
      "example-model",
      "v1",
      Hashing.textHash(text),
      s"s3://$bucket/rag/text/${Hashing.textHash(text)}.txt",
      md,
      run1,
    )

  def entry(
      key: String,
      text: String,
      emb: Vector[Float] = Vector(1f, 0f, 0f),
      md: Map[String, String] = Map("window" -> windowLabel),
      index: String = VectorIndexes.ResearcherProfile,
  ): VectorEntry = VectorEntry(vector(key, text, index, emb.length, md), emb)

  // ---- temp dirs ------------------------------------------------------------------------------

  def newTempDir(prefix: String): Path = Files.createTempDirectory(prefix)

  def deleteTree(root: Path): Unit =
    if Files.exists(root) then
      val s = Files.walk(root)
      try s.iterator.asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))
      finally s.close()

  /** Walks up from the working directory to the repository root (the directory with build.sbt). */
  def repoRoot: Option[Path] =
    Iterator
      .iterate(Option(Path.of(System.getProperty("user.dir")).toAbsolutePath))(
        _.flatMap(p => Option(p.getParent))
      )
      .takeWhile(_.isDefined)
      .flatten
      .find(p => Files.isRegularFile(p.resolve("build.sbt")))
end StorageFx

/** Small in-memory graph tables that mirror the shape of the Phase 06 tables. */
object NeptuneFx:
  import StorageFx.*

  val rs: List[String] = List("Ada Lovelace", "Alan Turing", "Grace Hopper").map(researcher).sorted
  val r1: String       = rs(0)
  val r2: String       = rs(1)
  val r3: String       = rs(2)
  val w1: String       = "work:dblp:conf/sigmod/Foo01"
  val w2: String       = "work:dblp:conf/sigmod/Foo02"
  val win1: String     = "2000-2004"
  val win2: String     = "2005-2009"
  val c1: String       = s"community:$win1:$r1"
  val c2: String       = s"community:$win2:$r1"
  val digest: String   = "d" * 64

  val e1: String = ok(Ids.coauthorEdgeId(r1, r2, GraphWindow(2000, 2004), "coauthor.v1"))
  val e2: String = ok(Ids.coauthorEdgeId(r1, r2, GraphWindow(2005, 2009), "coauthor.v1"))

  private def S(s: String)                  = Cell.S(s)
  private def col(n: String, t: String)     = Column(n, t)
  def ref(table: String, n: Long): TableRef =
    TableRef(table, s"scholarchain-graph/$table", "parquet", Nil, n)

  val researchers: TableData = TableData(
    "researchers",
    Vector(
      col("researcherId", "string"),
      col("displayName", "string"),
      col("orcids", "array<string>"),
      col("paperCount", "int"),
      col("score", "double"),
      col("active", "boolean"),
    ),
    Vector(
      Vector(
        S(r1),
        S("""Ada, "Countess" Lovelace"""),
        Cell.Arr(List(S("0000-0002-1825-0097"))),
        Cell.I(3),
        Cell.D(1.5),
        Cell.B(true),
      ),
      Vector(S(r2), S("Alan Turing"), Cell.Arr(Nil), Cell.I(1), Cell.D(0.25), Cell.B(false)),
      Vector(S(r3), S("Grace\nHopper"), Cell.Null, Cell.I(2), Cell.Null, Cell.B(true)),
    ),
  )

  val works: TableData = TableData(
    "works",
    Vector(col("workId", "string"), col("title", "string"), col("year", "int")),
    Vector(Vector(S(w1), S("Graph Mining"), Cell.I(2001)), Vector(S(w2), S("A, B"), Cell.I(2003))),
  )

  val communities: TableData = TableData(
    "communities",
    Vector(col("communityId", "string"), col("size", "int")),
    Vector(Vector(S(c1), Cell.I(2)), Vector(S(c2), Cell.I(1))),
  )

  val authored: TableData = TableData(
    "authored",
    Vector(col("researcherId", "string"), col("workId", "string")),
    Vector(Vector(S(r1), S(w1)), Vector(S(r2), S(w1)), Vector(S(r2), S(w2))),
  )

  val coauthorEdges: TableData = TableData(
    "coauthor-edges",
    Vector(
      col("edgeId", "string"),
      col("researcherA", "string"),
      col("researcherB", "string"),
      col("window", "string"),
      col("jointPublicationCount", "int"),
      col("firstCollaborationYear", "int"),
      col("lastCollaborationYear", "int"),
      col("supportingWorksDigest", "string"),
      col("supportingWorksTruncated", "boolean"),
      col("representativeWorkIds", "array<string>"),
      col("evidenceTable", "string"),
      col("evidenceKey", "string"),
      col("evidencePartition", "string"),
    ),
    Vector(
      Vector(
        S(e1),
        S(r1),
        S(r2),
        S(win1),
        Cell.I(2),
        Cell.I(2001),
        Cell.I(2003),
        S(digest),
        Cell.B(false),
        Cell.Arr(List(S(w1), S(w2))),
        S("coauthor-edge-works"),
        S(e1),
        S(win1),
      ),
      Vector(
        S(e2),
        S(r1),
        S(r2),
        S(win2),
        Cell.I(1),
        Cell.I(2005),
        Cell.I(2005),
        S(digest),
        Cell.B(true),
        Cell.Arr(List(S(w1))),
        S("coauthor-edge-works"),
        S(e2),
        S(win2),
      ),
    ),
  )

  val membership: TableData = TableData(
    "community-membership",
    Vector(col("window", "string"), col("researcherId", "string"), col("communityId", "string")),
    Vector(
      Vector(S(win1), S(r1), S(c1)),
      Vector(S(win2), S(r1), S(c2)),
      Vector(S(win1), S(r2), S(c1)),
    ),
  )

  val coauthorProps: List[String] = List(
    "window",
    "jointPublicationCount",
    "firstCollaborationYear",
    "lastCollaborationYear",
    "supportingWorksDigest",
    "supportingWorksTruncated",
    "representativeWorkIds",
    "evidenceTable",
    "evidenceKey",
    "evidencePartition",
  )

  val researcherSpec: VertexSpec = VertexSpec(
    ref("researchers", 3),
    "Researcher",
    "researcherId",
    false,
    List("displayName", "orcids", "paperCount", "score", "active"),
  )
  val workSpec: VertexSpec =
    VertexSpec(ref("works", 2), "Work", "workId", false, List("title", "year"))
  val communitySpec: VertexSpec =
    VertexSpec(ref("communities", 2), "Community", "communityId", true, List("size"))

  val authoredSpec: EdgeSpec = EdgeSpec(
    ref("authored", 3),
    "AUTHORED",
    None,
    List("researcherId", "workId"),
    "researcherId",
    "workId",
    true,
    false,
    Nil,
  )
  val coauthorSpec: EdgeSpec = EdgeSpec(
    ref("coauthor-edges", 2),
    "COAUTHORED_WITH",
    Some("edgeId"),
    List("edgeId"),
    "researcherA",
    "researcherB",
    false,
    true,
    coauthorProps,
  )
  val memberSpec: EdgeSpec = EdgeSpec(
    ref("community-membership", 3),
    "MEMBER_OF",
    None,
    List("window", "researcherId"),
    "researcherId",
    "communityId",
    true,
    true,
    List("window"),
  )

  final case class Fixture(manifest: NeptuneManifest, tables: Map[String, TableData]):
    def source: NeptuneLoader.TableSource = r => tables.get(r.table).toRight(s"no table ${r.table}")

    /** Replaces a table and keeps the manifest row counts equal to the table sizes. */
    def withTable(t: TableData): Fixture =
      val ts = tables.updated(t.name, t)
      copy(manifest = rebase(manifest, ts), tables = ts)

    /** Replaces a table WITHOUT updating the manifest (to model a manifest/table disagreement). */
    def withTableKeepingManifest(t: TableData): Fixture = copy(tables = tables.updated(t.name, t))

    def build: Either[List[String], NeptuneBuild] = NeptuneLoader.build(manifest, source)

    def inputs: (List[NeptuneInvariants.VertexInput], List[NeptuneInvariants.EdgeInput]) =
      (
        manifest.vertices.map(v =>
          NeptuneInvariants.VertexInput(
            v,
            tables(v.ref.table),
            NeptuneCsv
              .vertexFile(v, tables(v.ref.table))
              .fold(e => throw AssertionError(e), identity),
          )
        ),
        manifest.edges.map(e =>
          NeptuneInvariants.EdgeInput(
            e,
            tables(e.ref.table),
            NeptuneCsv.edgeFile(e, tables(e.ref.table)).fold(x => throw AssertionError(x), identity),
          )
        ),
      )

  private def rebase(m: NeptuneManifest, ts: Map[String, TableData]): NeptuneManifest =
    def r(x: TableRef) = x.copy(rowCount = ts(x.table).rows.size.toLong)
    m.copy(
      vertices = m.vertices.map(v => v.copy(ref = r(v.ref))),
      edges = m.edges.map(e => e.copy(ref = r(e.ref))),
    )

  val fixture: Fixture =
    val tables = List(researchers, works, communities, authored, coauthorEdges, membership)
      .map(t => t.name -> t)
      .toMap
    val manifest = NeptuneManifest(
      ManifestHeader("neptune", run1, "test", "root"),
      List(researcherSpec, workSpec, communitySpec),
      List(authoredSpec, coauthorSpec, memberSpec),
    )
    Fixture(rebase(manifest, tables), tables)
end NeptuneFx
