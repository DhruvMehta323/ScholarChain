package scholarchain.storage

import scholarchain.core.*

import EmbeddingFx.*

/** Phase 11 repair-01 F-TEST-1: a title-only paper text (no abstract, topic, venue or DOI line, the
  * shape EmbeddingDocumentRenderer emits) is embedded, stored in the paper_abstract index and
  * resolves back byte-for-byte; nothing is added to it on the way.
  */
class MissingMetadataVectorSuite extends munit.FunSuite:

  private val Idx = VectorIndexes.PaperAbstract

  private val titleOnly = "Paper: work:dblp:conf/x/Z03\nTitle: Lost Links\nYear: 2003"

  private def embedded() =
    val rig = Rig()
    val s   = source(titleOnly, "work:dblp:conf/x/Z03", EmbeddingDocumentTypes.PaperAbstract)
    rig.enqueue(runFor("m"), s)
    (rig, s, rig.drain())

  test("a title-only paper document is stored in the paper_abstract index, no failure") {
    val (rig, s, r) = embedded()
    assertEquals(r.stoppedBy, "empty")
    assert(rig.stored(Idx, s.document.documentId).isDefined, "vector must be stored")
  }

  test("its text resolves unchanged through the pointer: no abstract or topic is invented") {
    val (rig, s, _) = embedded()
    val hits = rig.vectorsRaw.query(Idx, VectorQuery(rig.embedder.vecOf(titleOnly), 1)).toOption.get
    val t = VectorTextResolver(rig.objects).resolve(hits.head).fold(e => fail(e.toString), identity)
    assertEquals(t.text, titleOnly)
    assertEquals(t.textHash, s.document.textHash)
    assert(!t.text.toLowerCase.contains("abstract"))
    assert(!t.text.contains("Topics:") && !t.text.contains("Venue:"))
  }

  test("hit metadata of a title-only paper holds only the pointer and hash, no text fields") {
    val (rig, _, _) = embedded()
    val hit         =
      rig.vectorsRaw.query(Idx, VectorQuery(rig.embedder.vecOf(titleOnly), 1)).toOption.get.head
    assert(hit.metadata.values.forall(v => !v.contains("Lost Links")), hit.metadata.toString)
  }
