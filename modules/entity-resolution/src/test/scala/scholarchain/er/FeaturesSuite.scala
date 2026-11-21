package scholarchain.er

import scholarchain.core.*

import ErFx.*

class FeaturesSuite extends munit.FunSuite:
  private val A = person(
    "mention:a",
    SourceType.Dblp,
    "Wei Wang 0001",
    position = 1,
    year = 2020,
    authors = List("Li Zhang", "Jun Chen", "Ana Diaz"),
    doi = Some("10.1000/x"),
  )
  private val B = person(
    "mention:b",
    SourceType.Crossref,
    "W. Wang",
    position = 1,
    year = 2021,
    authors = List("Zhang, Li", "Chen, J.", "Xu Tan"),
    doi = Some("https://doi.org/10.1000/X"),
  )

  private lazy val f = Features.researcher(A, B)

  test("researcher: name_sim 1.0 (same family, initial 'w' matches 'wei')") {
    assertEquals(f("name_sim"), 1.0)
    assertEquals(f("family_exact"), 1.0)
    assertEquals(f("initials_compat"), 1.0)
  }

  test("researcher: position_agreement needs the same normalized DOI and the same position") {
    assertEquals(f("position_agreement"), 1.0)
    val moved = Features.researcher(A, B.copy(position = Some(2)))
    assertEquals(moved("position_agreement"), 0.0)
    val otherDoi = Features.researcher(A, B.copy(doi = Some("10.1000/y")))
    assert(!otherDoi.contains("position_agreement"), "different DOIs: the feature is absent")
    val noDoi = Features.researcher(A, B.copy(doi = None))
    assert(!noDoi.contains("position_agreement"))
  }

  test("researcher: coauthor_overlap is |shared| / min(|A|,|B|) = 2/3 over family|initial keys") {
    assert(close(f("coauthor_overlap"), 2.0 / 3.0))
  }

  test("researcher: coauthor_overlap is absent when a side has no coauthors") {
    assert(!Features.researcher(A.copy(authors = Nil), B).contains("coauthor_overlap"))
  }

  test("researcher: title, year and source features") {
    assertEquals(f("work_title_sim"), 1.0)
    assertEquals(f("year_distance"), 1.0)
    assertEquals(f("source_reliability"), 0.85) // min(dblp .90, crossref .85)
  }

  test("researcher: unavailable features are ABSENT, never faked") {
    val absent = List("affiliation_compat", "venue_compat", "orcid_agreement", "exact_id_conflict")
    absent.foreach(n => assert(!f.contains(n), s"$n must be absent"))
    val bare = Features.researcher(
      EntityView("mention:a", EntityKind.Researcher, SourceType.Dblp, name = Some("Ann Lee")),
      EntityView("mention:b", EntityKind.Researcher, SourceType.OpenAlex, name = Some("Ann Lee")),
    )
    assertEquals(
      bare.keySet,
      Set(
        "name_sim",
        "family_exact",
        "family_jw",
        "homonym_suffix_present",
        "homonym_suffix_conflict",
        "name_mixed_script",
        "initials_compat",
        "source_reliability",
      ),
    )
    assertEquals(bare("source_reliability"), 0.75)
  }

  test("researcher: orcid_agreement is 1.0 equal, 0.0 different, absent when one side lacks it") {
    val o1 = "0000-0002-1825-0097"
    val o2 = "0000-0001-5109-3700"
    assertEquals(
      Features.researcher(A.copy(orcid = Some(o1)), B.copy(orcid = Some(o1)))("orcid_agreement"),
      1.0,
    )
    assertEquals(
      Features.researcher(A.copy(orcid = Some(o1)), B.copy(orcid = Some(o2)))("orcid_agreement"),
      0.0,
    )
    assert(!Features.researcher(A.copy(orcid = Some(o1)), B).contains("orcid_agreement"))
  }

  test("researcher: affiliation_compat is token Jaccard (2 of 3 tokens)") {
    val x = Features.researcher(
      A.copy(affiliation = Some("University of Oxford")),
      B.copy(affiliation = Some("Oxford University")),
    )
    assert(close(x("affiliation_compat"), 2.0 / 3.0))
  }

  test("venue_compat: 1.0 when equal after normalization, Jaccard otherwise") {
    val eq = Features.researcher(A.copy(venue = Some("VLDB")), B.copy(venue = Some("vldb")))
    assertEquals(eq("venue_compat"), 1.0)
    val part =
      Features.researcher(A.copy(venue = Some("ACM SIGMOD")), B.copy(venue = Some("SIGMOD")))
    assertEquals(part("venue_compat"), 0.5)
  }

  test("hj3Conflict flag sets exact_id_conflict = 1.0 for both kinds, and only then") {
    assertEquals(Features.researcher(A, B, hj3Conflict = true)("exact_id_conflict"), 1.0)
    val w1 = paper("work:a", SourceType.Dblp, "Deep Learning")
    val w2 = paper("work:b", SourceType.Crossref, "Deep Learning")
    assertEquals(Features.work(w1, w2, hj3Conflict = true)("exact_id_conflict"), 1.0)
    assert(!Features.work(w1, w2).contains("exact_id_conflict"))
  }

  test("work: title_exact, title_sim, year, author overlap and first-author agreement") {
    val w1 = paper("work:a", SourceType.Dblp, "Deep Learning!", 2020, List("Li Zhang", "Jun Chen"))
    val w2 =
      paper("work:b", SourceType.Crossref, "deep learning", 2020, List("Zhang, L.", "Wang, Q."))
    val x = Features.work(w1, w2)
    assertEquals(x("title_exact"), 1.0)
    assertEquals(x("title_sim"), 1.0)
    assertEquals(x("year_distance"), 0.0)
    assertEquals(x("author_overlap"), 0.5)
    assertEquals(x("first_author_agree"), 1.0)
    assertEquals(x("source_reliability"), 0.85)
  }

  test("work: different titles give title_exact 0.0 and a Dice similarity below 1") {
    val x = Features.work(
      paper("work:a", SourceType.Dblp, "night"),
      paper("work:b", SourceType.Crossref, "nacht"),
    )
    assertEquals(x("title_exact"), 0.0)
    assert(close(x("title_sim"), 1.0 / 3.0))
  }

  test("work: identifier agreement features use core normalization") {
    val d1 = paper("work:a", SourceType.Dblp, "T", doi = Some("10.1000/ABC"))
    val d2 = paper("work:b", SourceType.Crossref, "T", doi = Some("https://doi.org/10.1000/abc"))
    assertEquals(Features.work(d1, d2)("doi_agreement"), 1.0)
    assertEquals(Features.work(d1, d2.copy(doi = Some("10.1000/other")))("doi_agreement"), 0.0)
    val a1 = d1.copy(arxiv = Some("arXiv:2101.00001"))
    val a2 = d2.copy(arxiv = Some("2101.00001"))
    assertEquals(Features.work(a1, a2)("arxiv_agreement"), 1.0)
    assertEquals(Features.work(a1, a2.copy(arxiv = Some("2101.00002")))("arxiv_agreement"), 0.0)
    assert(!Features.work(d1.copy(doi = None), d2).contains("doi_agreement"))
  }

  test("work: a mixed-script author name sets author_mixed_script") {
    val w1 = paper("work:a", SourceType.Dblp, "T", authors = List("Jоhn Smith")) // Cyrillic o
    val w2 = paper("work:b", SourceType.Crossref, "T", authors = List("John Smith"))
    assertEquals(Features.work(w1, w2)("author_mixed_script"), 1.0)
    assert(!Features.work(w2, w2.copy(id = "work:c")).contains("author_mixed_script"))
  }

  test("source_reliability is the minimum over the pair") {
    assertEquals(Features.sourceReliability(SourceType.Orcid, SourceType.Dblp), 0.90)
    assertEquals(Features.sourceReliability(SourceType.OpenAlex, SourceType.ScholarChain), 0.50)
    assertEquals(Features.sourceReliability(SourceType.Crossref, SourceType.OpenAlex), 0.75)
  }

  test("compute: views of different kinds are refused") {
    val r = Features.compute(A, paper("work:a", SourceType.Crossref, "T"))
    assert(r.isLeft, s"expected Left, got $r")
  }

  test("compute dispatches on the entity kind") {
    assertEquals(Features.compute(A, B), Right(f))
    val w = paper("work:a", SourceType.Dblp, "T")
    assert(
      Features
        .compute(w, w.copy(id = "work:b", source = SourceType.Crossref))
        .exists(_.contains("title_sim"))
    )
  }

  // ---- L2: doi_preprint_version_pair ----------------------------------------------------------------

  private val Title    = "Inductive Representation Learning on Large Graphs"
  private val Arxiv    = "10.48550/arXiv.1706.02216"
  private val Arxiv2   = "10.48550/arXiv.1706.02217"
  private val Journal  = "10.1145/3292500.3330701"
  private val Journal2 = "10.1016/j.artint.2020.103300"

  private def wk(id: String, doi: Option[String], title: String = Title) =
    paper(id, SourceType.Crossref, title, doi = doi)

  test("L2 work: an arXiv DOI against a journal DOI with the same title emits the feature (1.0)") {
    val w = Features.work(wk("work:a", Some(Arxiv)), wk("work:b", Some(Journal)))
    assertEquals(w("doi_preprint_version_pair"), 1.0)
    assertEquals(w("doi_agreement"), 0.0)
    assertEquals(w("title_sim"), 1.0)
    // symmetric: the preprint may sit on either side
    val s = Features.work(wk("work:a", Some(Journal)), wk("work:b", Some(Arxiv)))
    assertEquals(s("doi_preprint_version_pair"), 1.0)
  }

  test("F4: a bioRxiv-shaped DOI (10.1101/) is no longer a preprint prefix: feature absent") {
    val w =
      Features.work(wk("work:a", Some("10.1101/2020.01.01.123456")), wk("work:b", Some(Journal)))
    assertEquals(w("doi_agreement"), 0.0)
    assert(!w.contains("doi_preprint_version_pair"), w.toString)
    assert(
      !Features.preprintVersionPair(
        wk("work:a", Some("10.1101/2020.01.01.123456")),
        wk("work:b", Some(Journal)),
      )
    )
  }

  test("F4: a Genome Research DOI (10.1101/gr.*) against a journal DOI is not a version pair") {
    val w = Features.work(wk("work:a", Some("10.1101/gr.123456.120")), wk("work:b", Some(Journal)))
    assertEquals(w("doi_agreement"), 0.0)
    assert(!w.contains("doi_preprint_version_pair"), w.toString)
    // so the policy reason stays plain doi_disagree, never doi_disagree_possible_version
    val r = Policy.decide(EntityKind.Work, w, Thr)
    assertEquals(r.outcome, ResolutionOutcome.Conflict)
    assertEquals(r.reasonCodes, List("doi_disagree"))
    assert(!r.reasonCodes.contains("doi_disagree_possible_version"))
  }

  test("F4 control: arXiv 10.48550/arxiv.* (lower case) against a journal DOI still pairs") {
    val w = Features.work(
      wk("work:a", Some("10.48550/arxiv.1706.02216")),
      wk("work:b", Some(Journal)),
    )
    assertEquals(w("doi_preprint_version_pair"), 1.0)
    val r = Policy.decide(EntityKind.Work, w, Thr)
    assertEquals(r.reasonCodes, List("doi_disagree_possible_version"))
  }

  test("L2 work: two non-preprint DOIs do not emit the feature") {
    val w = Features.work(wk("work:a", Some(Journal)), wk("work:b", Some(Journal2)))
    assertEquals(w("doi_agreement"), 0.0)
    assert(!w.contains("doi_preprint_version_pair"), w.toString)
  }

  test("L2 work: two preprint DOIs do not emit the feature") {
    val w = Features.work(wk("work:a", Some(Arxiv)), wk("work:b", Some(Arxiv2)))
    assertEquals(w("doi_agreement"), 0.0)
    assert(!w.contains("doi_preprint_version_pair"), w.toString)
  }

  test("L2 work: dissimilar titles do not emit the feature even for an arXiv / journal pair") {
    val w = Features.work(
      wk("work:a", Some(Arxiv)),
      wk("work:b", Some(Journal), title = "Protein Folding With Deep Transformers"),
    )
    assert(w("title_sim") < 0.9, w("title_sim").toString)
    assertEquals(w("doi_agreement"), 0.0)
    assert(!w.contains("doi_preprint_version_pair"), w.toString)
  }

  test("L2 work: the same DOI, a missing DOI or a missing title do not emit the feature") {
    assert(
      !Features
        .work(wk("work:a", Some(Arxiv)), wk("work:b", Some(Arxiv)))
        .contains("doi_preprint_version_pair")
    )
    assert(
      !Features
        .work(wk("work:a", Some(Arxiv)), wk("work:b", None))
        .contains("doi_preprint_version_pair")
    )
    val noTitle = wk("work:b", Some(Journal)).copy(title = None)
    assert(!Features.work(wk("work:a", Some(Arxiv)), noTitle).contains("doi_preprint_version_pair"))
  }

  test(
    "L2 end to end: the preprint/published pair is CONFLICT with doi_disagree_possible_version"
  ) {
    val w = Features.work(wk("work:a", Some(Arxiv)), wk("work:b", Some(Journal)))
    val r = Policy.decide(EntityKind.Work, w, Thr)
    assertEquals(r.outcome, ResolutionOutcome.Conflict)
    assertEquals(r.reasonCodes, List("doi_disagree_possible_version"))
    // two journal DOIs keep the plain reason
    val plain = Features.work(wk("work:a", Some(Journal)), wk("work:b", Some(Journal2)))
    assertEquals(Policy.decide(EntityKind.Work, plain, Thr).reasonCodes, List("doi_disagree"))
  }
