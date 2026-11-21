package scholarchain.spark

import org.apache.spark.sql.functions as F

import scholarchain.core.*

/** Human decision (repair 02): row-level `provenance` is a JSON STRING column. Contract test: every
  * provenance string of every table that has a `provenance` column parses into core `Provenance`
  * through `Wire.read` and validates, on the extended fixture.
  */
class ProvenanceContractSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")

  private val withProvenance: List[Tables.Spec] =
    Tables.all.filter(_.columns.contains("provenance"))

  test("the tables with a provenance column are the declared record tables") {
    assert(withProvenance.nonEmpty)
    assert(withProvenance.map(_.name).contains("coauthor-edges"))
    assert(withProvenance.map(_.name).contains("researcher-metrics"))
    assert(withProvenance.map(_.name).contains("resolves-to"))
    withProvenance.foreach { t =>
      assertEquals(t.schema("provenance").dataType.catalogString, "string", t.name)
    }
  }

  test("EVERY provenance string of EVERY table parses through Wire.read and validates") {
    var total    = 0L
    var expected = 0L
    withProvenance.foreach { t =>
      val df = ExtRun.computed.tables(t.name)
      assert(df.count() > 0L, s"${t.name} is empty: the check would be vacuous")
      expected += df.count()
      val nulls = df.where(F.col("provenance").isNull).count()
      assertEquals(nulls, 0L, s"${t.name}: null provenance")
      // every row, not only the distinct strings, so a row-dependent value cannot slip through
      df.select("provenance").collect().foreach { r =>
        total += 1
        val text = r.getString(0)
        val p    = Wire.read[Provenance](text) match
          case Right(p)  => p
          case Left(err) => fail(s"${t.name}: provenance does not parse: $err; text=$text")
        assertEquals(summon[Contract[Provenance]].validate(p), Right(p), t.name)
        assertEquals(p.sourceType, SourceType.ScholarChain, t.name)
        assertEquals(p.runId, Some(ExtRun.runId), t.name)
        assert(p.inputs.nonEmpty, t.name)
        assert(p.inputs.forall(_.checksum.exists(_.nonEmpty)), s"${t.name}: input without checksum")
        assert(p.parameters.nonEmpty, t.name)
        // round trip: re-serializing the parsed record gives the same JSON tree (key order aside)
        assertEquals(Wire.write(p).map(ujson.read(_)), Right(ujson.read(text)), t.name)
      }
    }
    assertEquals(total, expected) // every row of every table was checked
    assert(total > 50L, total.toString)
  }

  test("a tampered provenance string (not JSON, unknown source type) is rejected by Wire.read") {
    assert(Wire.read[Provenance]("not json").isLeft)
    val good = ExtRun.computed.tables("resolves-to").select("provenance").head().getString(0)
    assert(Wire.read[Provenance](good).isRight)
    assert(Wire.read[Provenance](good.replace("scholarchain", "no-such-source")).isLeft)
  }
end ProvenanceContractSuite
