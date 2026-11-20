package scholarchain.hadoop

import java.nio.file.{Files, Path as JPath}

import scala.collection.immutable.ListMap

import scholarchain.core.*
import ItSupport.*

/** One LocalJobRunner run of the whole pipeline (HJ1 to HJ5) over the SYNTHETIC fixtures, with ONE
  * reducer per job, a team threshold of 4, a pair hard cap of 10 and a 700-byte split size (so the
  * DBLP file is read by several mappers). Every test below asserts against that one output.
  *
  * Job launches: 6 (hj1, hj2, hj3, hj4a, hj4b, hj5).
  */
class PipelineItSuite extends munit.FunSuite:
  private var root: JPath           = scala.compiletime.uninitialized
  private var exit: Int             = -1
  private var counters: ujson.Value = scala.compiletime.uninitialized
  private var manifest: ujson.Value = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    root = newRoot("pipeline")
    exit = runDriver(
      root,
      "scholarchain.hadoop.large-team-threshold"      -> "4",
      "scholarchain.hadoop.pair-expansion-hard-cap"   -> "10",
      "scholarchain.hadoop.reducers.normalize"        -> "1",
      "scholarchain.hadoop.reducers.teams"            -> "1",
      "scholarchain.hadoop.reducers.pairs"            -> "1",
      "scholarchain.hadoop.reducers.identifiers"      -> "1",
      "mapreduce.input.fileinputformat.split.maxsize" -> "700",
    )
    if exit == 0 then
      counters = readJson(runDir(root).resolve("counters.json"))
      manifest = readJson(runDir(root).resolve("manifest.json"))

  private def cnt(job: String, name: String): Long =
    counters("jobs")(job)("counters").obj.get(name).map(_.num.toLong).getOrElse(0L)
  private def rows(job: String, t: String): Long =
    counters("jobs")(job)("rows").obj.get(t).map(_.num.toLong).getOrElse(0L)
  private def rej(job: String, reason: String): Long =
    counters("jobs")(job)("rejects").obj.get(reason).map(_.num.toLong).getOrElse(0L)
  private def work(key: String)            = s"work:dblp:$key"
  private def byWork(t: String, w: String) = table(root, t).filter(_("workId").str == w)

  test("the pipeline exits 0 and writes the run directory with manifest, counters and _SUCCESS") {
    assertEquals(exit, 0)
    val d = runDir(root)
    assert(Files.exists(d.resolve("_SUCCESS")) && Files.exists(d.resolve("manifest.json")))
    assert(d.toString.contains("snapshot=synthetic/run_id="))
    assert(!d.getFileName.toString.contains(":"), "run_id directory must not contain a colon")
  }

  test(
    "curated layout: curated/<table>/snapshot=<s>/run_id=<hex>/ with year partitions for authorship and pairs"
  ) {
    val runHex = manifest("runId").str.stripPrefix("run:")
    ExportKeys.Tables.filterNot(Set("person-aliases")).foreach { t =>
      val tdir = curated(root).resolve(t)
      val any  = partFiles(tdir)
      if t != "duplicates" || any.nonEmpty then
        assert(any.nonEmpty, s"no part file for $t")
        assert(
          any.forall(f =>
            tdir.relativize(f.toPath).toString.startsWith(s"snapshot=synthetic/run_id=$runHex/")
          ),
          s"layout of $t",
        )
    }
    val years = partFiles(curated(root).resolve("authorship")).map(_.getParentFile.getName).toSet
    assertEquals(years, Set("year=2019", "year=2020", "year=2021", "year=2022", "year=2023"))
    assert(
      partFiles(curated(root).resolve("pair-candidates"))
        .forall(_.getParentFile.getName.startsWith("year="))
    )
    assert(
      partFiles(curated(root).resolve("works")).forall(!_.getParentFile.getName.startsWith("year="))
    )
  }

  test("HJ1 counters: every record read exactly once across several splits") {
    assertEquals(cnt("hj1-dblp-extract", "INPUT_RECORDS"), 15L)
    assertEquals(cnt("hj1-dblp-extract", "INPUT_DBLP"), 15L)
    assertEquals(cnt("hj1-dblp-extract", "ACCEPTED"), 8L)
    assertEquals(cnt("hj1-dblp-extract", "REJECTED"), 5L)
    assertEquals(cnt("hj1-dblp-extract", "DUPLICATE"), 1L)
    assertEquals(cnt("hj1-dblp-extract", "WWW_RECORDS"), 1L)
    assertEquals(cnt("hj1-dblp-extract", "SELF_PAIR_SKIPPED"), 1L)
  }

  test("HJ1 rejects by reason, in the reject-v1 format with the run ID and byte offsets") {
    val j = "hj1-dblp-extract"
    assertEquals(
      List(
        "missing_title",
        "missing_year",
        "missing_authors",
        "unsupported_type",
        "malformed_record",
      ).map(rej(j, _)),
      List(1L, 1L, 1L, 1L, 1L),
    )
    val dblpRejects = table(root, "rejects").filter(_("source").str == "dblp")
    assertEquals(dblpRejects.size, 5)
    dblpRejects.foreach { r =>
      assertEquals(r("schema").str, "reject-v1")
      assertEquals(r("runId").str, manifest("runId").str)
      assert(r("inputByteOffset").num >= 0)
      assert(r("rawExcerpt").str.codePointCount(0, r("rawExcerpt").str.length) <= 512)
      assert(r.obj.get("provenance").isEmpty, "RejectRecord has no provenance field by contract")
    }
    assertEquals(
      dblpRejects.map(r => r("sourceRecordId").str).toSet,
      Set(
        "journals/synth/NoTitle",
        "journals/synth/NoYear",
        "journals/synth/NoAuth",
        "data/synth/Dat1",
        "journals/synth/BadEnt",
      ),
    )
  }

  test(
    "HJ1 works: entities decoded, markup flattened, multiline title joined, DOI via core, duplicate winner by lowest offset"
  ) {
    val ws = table(root, "works").filter(_("provenance")("sourceType").str == "dblp")
    assertEquals(ws.size, 8)
    val a = ws.find(_("workId").str == work("journals/synth/Alpha20")).get
    assertEquals(a("title").str, "Deep Learning for Synthetic Data.")
    assertEquals(a("doi").str, "10.1000/synth.alpha")
    assertEquals(a("year").num.toInt, 2020)
    assertEquals(a("venueId").str, "venue:dblp:journals/synth")
    val dups = table(root, "duplicates").filter(_("source").str == "dblp")
    assertEquals(dups.size, 1)
    assertEquals(dups.head("sourceRecordId").str, "journals/synth/Alpha20")
    assert(
      dups.head("duplicate").str.stripPrefix("offset:").toLong > dups
        .head("winner")
        .str
        .stripPrefix("offset:")
        .toLong
    )
    assert(!a("title").str.contains("duplicate copy"))
  }

  test(
    "HJ1 mentions: named entities and ISO-8859-1 decoded, ORCID kept, homonym suffixes kept, editors excluded"
  ) {
    val alpha = byWork("author-mentions", work("journals/synth/Alpha20")).sortBy(_("position").num)
    assertEquals(alpha.map(_("rawName").str), List("Anna Müller", "Bob Stone", "José Ríos"))
    assertEquals(alpha.head("orcid").str, "0000-0002-1825-0097")
    assertEquals(alpha.map(_("position").num.toInt), List(0, 1, 2))
    val beta = byWork("author-mentions", work("conf/synth/Beta21")).sortBy(_("position").num)
    assertEquals(beta.map(_("rawName").str), List("Wei Wang 0001", "Wei Wang 0002", "Cy Dee"))
    assertEquals(beta.map(_("mentionId").str).distinct.size, 3)
    beta.foreach(m =>
      assertEquals(
        Ids.mentionId(m("workId").str, m("position").num.toInt).toOption.get,
        m("mentionId").str,
      )
    )
  }

  test(
    "person aliases: a www record becomes an alias with names, ORCID and URLs; it is not a work"
  ) {
    val p = table(root, "person-aliases")
    assertEquals(p.size, 1)
    assertEquals(p.head("aliasKey").str, "homepages/s/WeiWang")
    assertEquals(p.head("names").arr.map(_.str).toList, List("Wei Wang 0001", "Wei Wang"))
    assertEquals(p.head("orcids").arr.map(_.str).toList, List("0000-0002-1825-0097"))
    assert(!table(root, "works").exists(_("workId").str.contains("homepages")))
  }

  test(
    "team sizes: distinct mentions after duplicate-name handling; a repeated author yields no self-pair"
  ) {
    val sizes = table(root, "teams")
      .map(t => t("workId").str.stripPrefix("work:dblp:") -> t("teamSize").num.toInt)
      .toMap
    assertEquals(
      sizes,
      Map(
        "journals/synth/Alpha20"   -> 3,
        "conf/synth/Beta21"        -> 3,
        "journals/synth/Gamma22"   -> 2,
        "journals/synth/Delta22"   -> 5,
        "journals/synth/Epsilon23" -> 6,
        "books/synth/Book1"        -> 2,
        "journals/synth/Zeta23"    -> 2,
        "journals/synth/Eta23"     -> 2,
      ),
    )
    table(root, "teams").foreach { t =>
      assertEquals(t("mentionIds").arr.size, t("teamSize").num.toInt)
      assert(t("provenance")("inputs").arr.nonEmpty)
    }
    assertEquals(table(root, "authorship").size, sizes.values.sum)
  }

  test("pairs: exactly n(n-1)/2 per non-oversize work, canonical order, positions consistent") {
    val pairs = table(root, "pair-candidates")
    val per   = pairs.groupBy(_("workId").str).view.mapValues(_.size).toMap
    assertEquals(per(work("journals/synth/Alpha20")), 3)
    assertEquals(per(work("journals/synth/Delta22")), 10)
    assertEquals(per(work("journals/synth/Gamma22")), 1)
    assertEquals(per.get(work("journals/synth/Epsilon23")), None)
    assertEquals(pairs.size, 20)
    pairs.foreach { p =>
      assert(p("pair")("first").str.compareTo(p("pair")("second").str) < 0)
      assertEquals(
        Ids.mentionId(p("workId").str, p("firstPosition").num.toInt).toOption.get,
        p("pair")("first").str,
      )
      assertEquals(
        Ids.mentionId(p("workId").str, p("secondPosition").num.toInt).toOption.get,
        p("pair")("second").str,
      )
    }
    assertEquals(
      pairs
        .map(p => (p("workId").str, p("pair")("first").str, p("pair")("second").str))
        .distinct
        .size,
      20,
    )
  }

  test(
    "EMITTED_PAIRS equals the sum of n(n-1)/2 over non-oversize teams; large and oversize teams flagged, never truncated"
  ) {
    val t = "hj4a-team-expand"
    assertEquals(cnt(t, "EMITTED_PAIRS"), 20L)
    assertEquals(cnt(t, "EXPECTED_PAIRS"), 20L)
    assertEquals(cnt(t, "LARGE_AUTHOR_TEAM"), 2L)
    assertEquals(cnt(t, "OVERSIZE_AUTHOR_TEAM"), 1L)
    assertEquals(cnt("hj4b-pair-dedupe", "DUPLICATE_PAIR"), 0L)
    assertEquals(cnt("hj4b-pair-dedupe", "ACCEPTED"), 20L)
    val large = table(root, "large-teams")
      .map(l =>
        l("workId").str
          .stripPrefix("work:dblp:") -> (l("teamSize").num.toInt, l("pairCount").num.toLong)
      )
      .toMap
    assertEquals(
      large,
      Map("journals/synth/Delta22" -> (5, 10L), "journals/synth/Epsilon23" -> (6, 15L)),
    )
    val over = table(root, "oversize-teams")
    assertEquals(over.size, 1)
    assertEquals(over.head("workId").str, work("journals/synth/Epsilon23"))
    assertEquals(over.head("pairCount").num.toLong, 15L)
    assertEquals(over.head("hardCap").num.toInt, 10)
    // the oversize team still has its team and authorship records
    assertEquals(byWork("teams", work("journals/synth/Epsilon23")).size, 1)
    assertEquals(byWork("authorship", work("journals/synth/Epsilon23")).size, 6)
  }

  test(
    "HJ2 Crossref: refetch wins the duplicate, markup stripped, year-less record kept for identifiers, upstream 404 counted, errors.jsonl skipped"
  ) {
    val j = "hj2-source-normalize"
    assertEquals(cnt(j, "INPUT_CROSSREF"), 5L) // errors.jsonl is not read
    assertEquals(cnt(j, "UPSTREAM_MISSING"), 1L)
    assertEquals(cnt(j, "MISSING_YEAR_KEPT"), 1L)
    assertEquals(cnt(j, "DUPLICATE"), 1L)
    val crossrefWorks = table(root, "works").filter(_("provenance")("sourceType").str == "crossref")
    assertEquals(crossrefWorks.map(_("workId").str), List("work:crossref:10.1000/synth.alpha"))
    assertEquals(crossrefWorks.head("title").str, "Deep Learning and Data (refetch)")
    val dup = table(root, "duplicates").filter(_("source").str == "crossref")
    assertEquals(
      dup.map(d => (d("winner").str, d("duplicate").str)),
      List(("c1-refetch.json", "c1.json")),
    )
    // the year-less record still yields its author mention
    assert(table(root, "author-mentions").exists(_("workId").str == "work:crossref:10.1000/beta"))
    assertEquals(rej(j, "malformed_record"), 1L)
  }

  test(
    "HJ2 OpenAlex and ORCID: source IDs, institutions, topics and works preserved; bad records rejected"
  ) {
    val oa = table(root, "works").find(_("workId").str == "work:openalex:W111").get
    assertEquals(oa("year").num.toInt, 2020)
    assertEquals(oa("topicIds").arr.map(_.str).toList, List("topic:openalex:T555"))
    val inst = table(root, "institutions").filter(_("provenance")("sourceType").str == "openalex")
    assertEquals(
      inst.map(i => (i("institutionId").str, i("country").str)),
      List(("institution:openalex:I333", "DE")),
    )
    assertEquals(table(root, "topics").filter(_("topicId").str == "topic:openalex:T555").size, 1)
    val oaM = byWork("author-mentions", "work:openalex:W111").sortBy(_("position").num)
    assertEquals(oaM.map(_("rawName").str), List("Anna Mueller", "Bob Stone"))
    assertEquals(oaM.head("orcid").str, "0000-0002-1825-0097")
    val orc = table(root, "works").filter(_("provenance")("sourceType").str == "orcid")
    assertEquals(orc.size, 2)
    assert(orc.exists(_("workId").str == "work:doi:10.1000/synth.alpha"))
    assert(orc.exists(_("workId").str.startsWith("work:sc1:")))
    assertEquals(rej("hj2-source-normalize", "missing_title"), 1L)
    assertEquals(rej("hj2-source-normalize", "invalid_identifier"), 1L)
    assertEquals(cnt("hj2-source-normalize", "INPUT_OPENALEX"), 2L)
    assertEquals(cnt("hj2-source-normalize", "INPUT_ORCID"), 4L)
  }

  test(
    "HJ3 exact identifiers: EXACT_LINK, DUPLICATE and CONFLICT evidence with no score or decision"
  ) {
    val ev = table(root, "exact-identifier-evidence")
      .map(e => (e("identifierType").str, e("normalizedIdentifier").str) -> e)
      .toMap
    val alpha = ev(("doi", "10.1000/synth.alpha"))
    assertEquals(alpha("outcome").str, "exact_link")
    assertEquals(
      alpha("linkedRecordIds").arr.map(_.str).toList,
      List(
        "work:crossref:10.1000/synth.alpha",
        "work:dblp:journals/synth/Alpha20",
        "work:doi:10.1000/synth.alpha",
        "work:openalex:W111",
      ),
    )
    assertEquals(ev(("doi", "10.1000/beta"))("outcome").str, "exact_link")
    assertEquals(ev(("doi", "10.1000/shared"))("outcome").str, "conflict")
    assertEquals(
      ev(("orcid", "0000-0002-9079-593X"))("outcome").str,
      "duplicate",
    ) // two DBLP mentions only
    assertEquals(ev(("orcid", "0000-0002-1825-0097"))("outcome").str, "exact_link")
    assert(!ev.contains(("arxiv", "2001.01234")), "one record is not evidence")
    table(root, "exact-identifier-evidence").foreach { e =>
      assert(
        Set(
          "schemaVersion",
          "identifierType",
          "normalizedIdentifier",
          "linkedRecordIds",
          "outcome",
          "provenance",
        ) == e.obj.keySet.toSet
      )
      assert(e("linkedRecordIds").arr.size >= 2)
    }
    val j = "hj3-exact-identifier"
    assertEquals(cnt(j, "CONFLICT"), 1L)
    assertEquals(cnt(j, "DUPLICATE"), 1L)
    assertEquals(cnt(j, "EXACT_LINK"), ev.values.count(_("outcome").str == "exact_link").toLong)
    assertEquals(cnt(j, "MISSING_KEY"), 1L) // the DBLP book has no DOI or arXiv id
  }

  test(
    "provenance: every record with a provenance field carries inputs with checksums, the code version, parameters and the run ID"
  ) {
    val runId   = manifest("runId").str
    val dblpSha = sha256(fixture("dblp-synth.xml"))
    var checked = 0
    ExportKeys.Tables.foreach { t =>
      table(root, t).foreach { r =>
        r.obj.get("provenance").foreach { p =>
          checked += 1
          assert(p("inputs").arr.nonEmpty, t)
          p("inputs").arr.foreach(i => assert(Hashing.isSha256Hex(i("checksum").str), t))
          assertEquals(p("codeVersion").str, CodeVersion)
          assertEquals(p("runId").str, runId)
          assertEquals(p("parameters")("hadoop.large-team-threshold").str, "4")
          assertEquals(p("parameters")("hadoop.pair-expansion-hard-cap").str, "10")
          assert(p("parameters").obj.contains("job"))
        }
      }
    }
    assert(checked > 100)
    val a = table(root, "works").find(_("workId").str == work("journals/synth/Alpha20")).get
    assert(a("provenance")("inputs").arr.exists(_("checksum").str == dblpSha))
  }

  test(
    "manifest and counters.json: the manifest validates, its run ID is content-derived, counters match"
  ) {
    val m = Wire
      .read[PipelineRunManifest](Files.readString(runDir(root).resolve("manifest.json")))
      .toOption
      .get
    assertEquals(
      m.jobs.map(_.jobId),
      List(
        "hj1-dblp-extract",
        "hj2-source-normalize",
        "hj3-exact-identifier",
        "hj4a-team-expand",
        "hj4b-pair-dedupe",
        "hj5-graph-candidate-export",
      ),
    )
    assertEquals(
      m.inputs.map(_.inputId).toSet,
      Set("dblp/dblp-synth.xml", "dblp/dblp.dtd", "crossref/works", "openalex/works", "orcid/works"),
    )
    assert(m.inputs.forall(_.checksum.exists(Hashing.isSha256Hex)))
    assertEquals(m.createdAt, None) // no wall-clock value
    val expectedId = Ids.runId(m.stage, m.codeVersion, m.attempt, m.config, m.inputs).toOption.get
    assertEquals(m.runId, expectedId)
    assertEquals(m.config("hadoop.large-team-threshold"), "4")
    m.jobs.foreach { js =>
      val fromFile =
        counters("jobs")(js.jobId)("counters").obj.map((k, v) => k -> v.num.toLong).toMap
      fromFile.foreach((k, v) => assertEquals(js.counters(k), v, k))
    }
    val hj5 = m.jobs.last
    assertEquals(hj5.outputs.find(_.path.startsWith("curated/pair-candidates")).get.rowCount, 20L)
    assertEquals(rows("hj5-graph-candidate-export", "teams"), 8L)
  }

  test("every curated row count equals the sum of its source jobs' row counts") {
    val e = "hj5-graph-candidate-export"
    // F5: rows of one workId are merged in the export (WORK_MERGED rows are not written)
    assertEquals(
      rows(e, "works"),
      rows("hj1-dblp-extract", "works") + rows("hj2-source-normalize", "works") -
        cnt(e, "WORK_MERGED"),
    )
    assertEquals(
      rows(e, "author-mentions"),
      rows("hj1-dblp-extract", "author-mentions") + rows("hj2-source-normalize", "author-mentions"),
    )
    assertEquals(rows(e, "pair-candidates"), rows("hj4a-team-expand", "pair-stage"))
    assertEquals(table(root, "works").size.toLong, rows(e, "works"))
  }

  test("F5: two ORCID envelopes sharing a DOI give ONE works row; no provenance input is lost") {
    val id   = "work:doi:10.1000/synth.alpha"
    val rows = table(root, "works").filter(_("workId").str == id)
    assertEquals(rows.size, 1)
    // winner = latest retrievedAt: the p4 envelope (2026-02-01) over p1 (2026-01-01)
    assertEquals(rows.head("title").str, "Deep Learning for Synthetic Data (revised)")
    val inputs = rows.head("provenance")("inputs").arr.toList
    val names  = inputs.map(_("inputId").str)
    assertEquals(names, names.sorted)
    assert(names.contains("p1.json") && names.contains("p4-shared-doi.json"), names.toString)
    assertEquals(names.count(_ == "orcid/works"), 1)
    assertEquals(
      inputs.map(i => (i("inputId").str, i("version").str, i("checksum").str)),
      inputs.map(i => (i("inputId").str, i("version").str, i("checksum").str)).sorted,
    )
    assertEquals(cnt("hj5-graph-candidate-export", "WORK_MERGED"), 1L)
    val all = table(root, "works").map(_("workId").str)
    assertEquals(all.size, all.distinct.size)
  }

  test("F5: institutions and topics are unique by their ID too") {
    val inst = table(root, "institutions").map(_("institutionId").str)
    val top  = table(root, "topics").map(_("topicId").str)
    assertEquals(inst.size, inst.distinct.size)
    assertEquals(top.size, top.distinct.size)
  }

  test("F2: provenance.inputs of exact-identifier evidence are sorted") {
    table(root, "exact-identifier-evidence").foreach { e =>
      val ins = e("provenance")("inputs").arr.map(_("inputId").str).toList
      assertEquals(ins, ins.sorted)
    }
    val alpha = table(root, "exact-identifier-evidence")
      .find(_("normalizedIdentifier").str == "10.1000/synth.alpha")
      .get
    assertEquals(
      alpha("provenance")("inputs").arr.map(_("inputId").str).toList,
      List(
        "crossref/works",
        "dblp/dblp-synth.xml",
        "dblp/dblp.dtd",
        "openalex/works",
        "orcid/works",
      ),
    )
  }
end PipelineItSuite
