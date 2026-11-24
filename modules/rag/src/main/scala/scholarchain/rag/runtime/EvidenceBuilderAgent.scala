package scholarchain.rag.runtime

import scholarchain.core.*

/** A built bundle with the deterministic and the semantic evidence IDs kept apart.
  *
  * `graphEvidenceIds` are the IDs of graph, metric and identity evidence (facts).
  * `semanticEvidenceIds` are the IDs of vector-search context (`TextChunk` items). The bundle holds
  * both, in that order. `dropped` counts drafts left out by the item bound (deterministic evidence
  * is kept first).
  */
final case class EvidenceSet(
    bundle: EvidenceBundle,
    graphEvidenceIds: List[String],
    semanticEvidenceIds: List[String],
    dropped: Int,
):
  def itemsById: Map[String, EvidenceItem] = bundle.items.map(i => i.evidenceId -> i).toMap
  def graphItems: List[EvidenceItem]       =
    bundle.items.filter(i => graphEvidenceIds.contains(i.evidenceId))
  def semanticItems: List[EvidenceItem] =
    bundle.items.filter(i => semanticEvidenceIds.contains(i.evidenceId))

/** Stage 7: builds the bounded `EvidenceBundle`.
  *
  * Each draft becomes an `EvidenceItem` whose `text` is the canonical fact JSON (or the semantic
  * excerpt), whose `contentHash` is the SHA-256 of that text, and whose ID is the core
  * `Ids.evidenceId`. Provenance records the inputs (stored table and key, version, payload hash),
  * the run ID (the Spark run of the stored item), the code version and the parameters. Identity
  * resolution confidence is part of the identity facts. Duplicates (same ID) are merged. The bundle
  * is bounded by `rag.evidence.max-items`; deterministic evidence is kept before semantic context.
  */
final class EvidenceBuilderAgent(settings: RuntimeSettings):

  def build(
      queryId: String,
      deterministic: List[EvidenceDraft],
      semantic: List[EvidenceDraft],
  ): Either[RuntimeError, EvidenceSet] =
    val det     = deterministic.filterNot(_.semantic)
    val sem     = semantic.filter(_.semantic) ++ deterministic.filter(_.semantic)
    val max     = settings.evidenceMaxItems
    val keptDet = det.take(max)
    val keptSem = sem.take(math.max(0, max - keptDet.size))
    val dropped = (det.size - keptDet.size) + (sem.size - keptSem.size)
    for
      d <- items(keptDet)
      s <- items(keptSem)
      dd  = d.distinctBy(_.evidenceId)
      ss  = s.distinctBy(_.evidenceId).filterNot(i => dd.exists(_.evidenceId == i.evidenceId))
      all = dd ++ ss
      bundle <- bundleOf(queryId, all)
    yield EvidenceSet(bundle, dd.map(_.evidenceId), ss.map(_.evidenceId), dropped)

  private def items(drafts: List[EvidenceDraft]): Either[RuntimeError, List[EvidenceItem]] =
    drafts.foldLeft[Either[RuntimeError, List[EvidenceItem]]](Right(Nil)) { (acc, d) =>
      acc.flatMap(done => item(d).map(done :+ _))
    }

  private def item(d: EvidenceDraft): Either[RuntimeError, EvidenceItem] =
    val hash = Hashing.textHash(d.text)
    for
      id <- Ids
        .evidenceId(d.evidenceType, d.sourceEntityId, d.sourceVersion, hash)
        .left
        .map(e => RuntimeError("evidence-id-invalid", e.code))
      prov = Provenance(
        SchemaVersion.Current,
        SourceType.ScholarChain,
        if d.inputs.nonEmpty then d.inputs.distinct
        else List(InputRef("rag-runtime", "v1", Some(hash))),
        RuntimeCode.Version,
        d.parameters,
        d.runId.filter(r => Ids.checkRunId(r).isRight),
        None,
      )
      it = EvidenceItem(
        SchemaVersion.Current,
        id,
        d.evidenceType,
        d.sourceEntityId,
        d.sourceVersion,
        Some(d.text),
        hash,
        d.score.filter(s => !s.isNaN && !s.isInfinite),
        prov,
      )
      v <- summon[Contract[EvidenceItem]]
        .validate(it)
        .left
        .map(e => RuntimeError("evidence-invalid", e.code))
    yield v

  private def bundleOf(
      queryId: String,
      all: List[EvidenceItem],
  ): Either[RuntimeError, EvidenceBundle] =
    val ids = Hashing.digest("scholarchain.rag.bundle-items.v1", all.map(_.evidenceId))
    val b   = EvidenceBundle(
      SchemaVersion.Current,
      queryId,
      all,
      Provenance(
        SchemaVersion.Current,
        SourceType.ScholarChain,
        List(InputRef("evidence-items", all.size.toString, Some(ids))),
        RuntimeCode.Version,
        Map("maxItems" -> settings.evidenceMaxItems.toString),
      ),
    )
    summon[Contract[EvidenceBundle]]
      .validate(b)
      .left
      .map(e => RuntimeError("bundle-invalid", e.code))
