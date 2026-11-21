package scholarchain.spark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/** Load manifests: JSON files that tell the storage loaders (Phase 07) what to load where. They
  * list tables, paths relative to the output root, formats, key columns, partition columns and row
  * counts, and carry the run ID and code version. They hold no timestamps and no absolute paths, so
  * identical runs give identical manifests. The Spark module only describes; it loads nothing.
  *
  * Files under `<output-root>/load-manifests/`: `neptune.json`, `dynamodb.json`, `s3-vectors.json`,
  * `s3.json`. Every one has `manifestVersion` (1), `target`, `runId`, `codeVersion`, `root`
  * (`scholarchain-graph`) and its own list; see SPARK-CONTRACTS.md.
  */
object LoadManifests:
  val Version = 1
  val Dir     = "load-manifests"

  private def str(s: String): ujson.Value      = ujson.Str(s)
  private def strs(xs: Seq[String]): ujson.Arr = ujson.Arr.from(xs.map(ujson.Str(_)))

  private def base(target: String, run: RunInfo): ujson.Obj =
    ujson.Obj(
      "manifestVersion" -> Version,
      "target"          -> target,
      "runId"           -> run.runId,
      "codeVersion"     -> run.codeVersion,
      "root"            -> Export.GraphDir,
    )

  private def tableRef(w: TableWritten): ujson.Obj =
    ujson.Obj(
      "table"            -> w.spec.name,
      "path"             -> s"${Export.GraphDir}/${w.spec.name}",
      "format"           -> "parquet",
      "partitionColumns" -> strs(w.partitionColumns),
      "rowCount"         -> ujson.Num(w.rowCount.toDouble),
    )

  def build(written: List[TableWritten], run: RunInfo): Map[String, ujson.Obj] =
    val by                        = written.map(w => w.spec.name -> w).toMap
    def ref(t: String): ujson.Obj = tableRef(by(t))

    def vertex(t: String, label: String, id: String, props: Seq[String]) =
      ref(t).tap(
        _.value ++= Seq(
          "label"           -> str(label),
          "idColumn"        -> str(id),
          "windowed"        -> ujson.Bool(by(t).spec.windowed),
          "propertyColumns" -> strs(props),
        )
      )
    def edge(
        t: String,
        label: String,
        id: Option[String],
        keys: Seq[String],
        from: String,
        to: String,
        directed: Boolean,
        props: Seq[String],
    ) =
      ref(t).tap(
        _.value ++= Seq(
          "label"           -> str(label),
          "idColumn"        -> id.map(str).getOrElse(ujson.Null),
          "keyColumns"      -> strs(keys),
          "fromColumn"      -> str(from),
          "toColumn"        -> str(to),
          "directed"        -> ujson.Bool(directed),
          "windowed"        -> ujson.Bool(by(t).spec.windowed),
          "propertyColumns" -> strs(props),
        )
      )

    val neptune = base("neptune", run)
    neptune("vertices") = ujson.Arr(
      vertex(
        "researchers",
        "Researcher",
        "researcherId",
        Seq(
          "displayName",
          "orcid",
          "orcids",
          "identityRule",
          "cannotLinkStatus",
          "firstYear",
          "lastYear",
        ),
      ),
      vertex(
        "external-identities",
        "ExternalIdentity",
        "externalId",
        Seq("kind", "value"),
      ),
      vertex("works", "Work", "workId", Seq("title", "year", "doi", "venueId", "teamSize")),
      vertex("topics", "Topic", "topicId", Seq("label", "linkedWorkCount")),
      vertex("institutions", "Institution", "institutionId", Seq("name", "country")),
      vertex("venues", "Venue", "venueId", Seq("name", "workCount")),
      vertex(
        "communities",
        "Community",
        "communityId",
        Seq("window", "size", "method", "internalEdgeCount", "crossEdgeCount"),
      ),
      vertex("components", "Component", "componentId", Seq("window", "size")),
    )
    neptune("edges") = ujson.Arr(
      edge(
        "authored",
        "AUTHORED",
        None,
        Seq("researcherId", "workId"),
        "researcherId",
        "workId",
        true,
        Seq("year", "position"),
      ),
      edge(
        "coauthor-edges",
        "COAUTHORED_WITH",
        Some("edgeId"),
        Seq("edgeId"),
        "researcherA",
        "researcherB",
        false,
        Seq(
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
        ),
      ),
      edge(
        "resolves-to",
        scholarchain.core.GraphEdgeTypes.ResolvesTo,
        None,
        Seq("sourceId", "targetId"),
        "sourceId",
        "targetId",
        true,
        Seq("orcid", "evidenceMentionIds"),
      ),
      edge(
        "has-topic",
        "HAS_TOPIC",
        None,
        Seq("workId", "topicId"),
        "workId",
        "topicId",
        true,
        Seq("viaWorkId"),
      ),
      edge("published-in", "PUBLISHED_IN", None, Seq("workId"), "workId", "venueId", true, Nil),
      edge(
        "community-membership",
        "MEMBER_OF",
        None,
        Seq("window", "researcherId"),
        "researcherId",
        "communityId",
        true,
        Seq("window"),
      ),
      edge(
        "component-membership",
        "IN_COMPONENT",
        None,
        Seq("window", "researcherId"),
        "researcherId",
        "componentId",
        true,
        Seq("window"),
      ),
      edge(
        "community-cross-edges",
        "COMMUNITY_LINK",
        None,
        Seq("window", "communityA", "communityB"),
        "communityA",
        "communityB",
        false,
        Seq("window", "edgeCount"),
      ),
    )

    def ddb(t: String, pk: String, sk: Seq[String]) =
      ref(t).tap(_.value ++= Seq("partitionKey" -> str(pk), "sortKey" -> strs(sk)))
    val dynamo = base("dynamodb", run)
    dynamo("tables") = ujson.Arr(
      ddb("researcher-metrics", "researcherId", Seq("window")),
      ddb("coauthor-edge-works", "edgeId", Seq("workId")),
      ddb("bridges", "researcherId", Seq("window", "measure")),
      ddb("coreness", "researcherId", Seq("window")),
      ddb("temporal-changes", "researcherId", Seq("window")),
      ddb("paths", "startId", Seq("window", "endId")),
      ddb("path-estimates", "window", Nil),
      ddb("window-summary", "window", Nil),
      ddb("identity-clusters", "researcherId", Nil),
      ddb("mention-researcher", "mentionId", Nil),
    )

    val vectors = base("s3-vectors", run)
    vectors("documents") = ujson.Arr(
      ref("semantic-summaries").tap(
        _.value ++= Seq(
          "keyColumns"      -> strs(Seq("entityId", "window", "documentType")),
          "textColumn"      -> str("text"),
          "textHashColumn"  -> str("textHash"),
          "metadataColumns" -> strs(
            Seq("entityId", "documentType", "window", "windowStart", "windowEnd", "runId")
          ),
          "embedding" -> str(
            "not computed in Phase 06; the embedding job (Phase 08) reads textColumn"
          ),
        )
      )
    )

    val s3 = base("s3", run)
    s3("tables") = ujson.Arr.from(
      written.map(w =>
        tableRef(w).tap(
          _.value ++= Seq(
            "keyPrefix"  -> str(s"${Export.GraphDir}/${w.spec.name}/"),
            "partFiles"  -> ujson.Num(w.partFiles.toDouble),
            "provenance" -> str(s"${Export.GraphDir}/${w.spec.name}/_provenance.json"),
          )
        )
      )
    )
    s3("companionFiles") = strs(Seq("run-manifest.json", "run-summary.json", "run-metrics.json"))

    Map("neptune" -> neptune, "dynamodb" -> dynamo, "s3-vectors" -> vectors, "s3" -> s3)

  def write(manifests: Map[String, ujson.Obj], root: Path): List[Path] =
    val dir = root.resolve(Dir)
    Files.createDirectories(dir)
    manifests.toList.sortBy(_._1).map { (name, json) =>
      val p = dir.resolve(s"$name.json")
      Files.writeString(p, ujson.write(json, indent = 2) + "\n", StandardCharsets.UTF_8)
      p
    }

  extension [A](a: A) private def tap(f: A => Unit): A = { f(a); a }
end LoadManifests
