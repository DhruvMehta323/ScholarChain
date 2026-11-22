package scholarchain.spark

import org.apache.spark.sql.Row
import org.apache.spark.sql.functions as F

import scholarchain.core.*

/** Repair 02 (R-M1): the output invariant `identity_no_distinct_dblp_homonyms_in_cluster` over ALL
  * DBLP names of a base identity, on handcrafted violating mention-researcher mappings.
  */
class HomonymInvariantSuite extends munit.FunSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(20, "min")

  private val Name = "identity_no_distinct_dblp_homonyms_in_cluster"

  private def verdict(c: Computed, inputs: Inputs.LoadedInputs): Invariant =
    Invariants.check(c, ExtRun.cfg, inputs).find(_.name == Name).get

  private def base(name: String): String = Ok.get(Ids.researcherIdDblpPerson(name))

  private def replaced(c: Computed, table: String, df: org.apache.spark.sql.DataFrame) =
    c.copy(tables = c.tables.updated(table, df))

  test("untampered extended fixture: the invariant holds (Hom 0008 and Hom 0009 are separate)") {
    val v = verdict(ExtRun.computed, ExtRun.inputs)
    assert(v.ok, v.detail)
  }

  test("a mention-researcher mapping that puts Hom 0008 and Hom 0009 in one researcher fails") {
    val h8  = Fixture.rid("Hom 0008"); val h9 = Fixture.rid("Hom 0009")
    val mr  = ExtRun.computed.tables("mention-researcher")
    val bad = mr.withColumn(
      "researcherId",
      F.when(F.col("baseIdentityId") === h9, h8).otherwise(F.col("researcherId")),
    )
    // sanity: the tamper changed something
    assert(bad.where(F.col("baseIdentityId") === h9 && F.col("researcherId") === h8).count() > 0L)
    val v = verdict(replaced(ExtRun.computed, "mention-researcher", bad), ExtRun.inputs)
    assert(!v.ok, v.detail)
    assert(v.detail.contains("= 1"), v.detail) // one researcher, one stem
  }

  // ---- alias-group case ----------------------------------------------------------------------

  /** Mentions "Z. Wang" and "Zhen Wang 0009" only (NO mention of "Zhen Wang 0008"), the alias
    * record ["Zhen Wang 0008", "Z. Wang"] (one base identity keyed "Z. Wang"): the conflicting name
    * "Zhen Wang 0008" is an ALIAS member name, never a mention name.
    */
  private def aliasInputs: Inputs.LoadedInputs =
    val a = Ok.get(Ids.mentionId("work:dblp:t/AL1", 0))
    val b = Ok.get(Ids.mentionId("work:dblp:t/AL2", 0))
    val i = ExtRun.inputs
    i.copy(
      mentions = i.mentions.union(
        TestSpark.frame(
          InputSchemas.Mentions,
          List(
            Row(a, "work:dblp:t/AL1", 0, "Z. Wang", null, "dblp"),
            Row(b, "work:dblp:t/AL2", 0, "Zhen Wang 0009", null, "dblp"),
          ),
        )
      ),
      aliases = i.aliases.union(
        TestSpark.frame(
          InputSchemas.Aliases,
          List(Row("key-wang", Seq("Zhen Wang 0008", "Z. Wang"), Seq.empty[String])),
        )
      ),
    )

  private def mappingRows(
      researcherOfZhen9: String
  ): org.apache.spark.sql.DataFrame =
    val a = Ok.get(Ids.mentionId("work:dblp:t/AL1", 0))
    val b = Ok.get(Ids.mentionId("work:dblp:t/AL2", 0))
    ExtRun.computed
      .tables("mention-researcher")
      .union(
        TestSpark.frame(
          Tables.MentionResearcher.schema,
          List(
            Row(a, base("Z. Wang"), "dblp", base("Z. Wang")),
            Row(b, researcherOfZhen9, "dblp", base("Zhen Wang 0009")),
          ),
        )
      )

  test("alias-group case: an alias member name conflicts with another base in one researcher") {
    val bad = mappingRows(base("Z. Wang")) // both bases under one researcher
    val v   = verdict(replaced(ExtRun.computed, "mention-researcher", bad), aliasInputs)
    assert(!v.ok, v.detail)
  }

  test("alias-group negative control: the same two bases in SEPARATE researchers pass") {
    val good = mappingRows(base("Zhen Wang 0009"))
    val v    = verdict(replaced(ExtRun.computed, "mention-researcher", good), aliasInputs)
    assert(v.ok, v.detail)
  }

  test("alias-group negative control: one alias group alone (one base identity) passes") {
    val a    = Ok.get(Ids.mentionId("work:dblp:t/AL1", 0))
    val i    = aliasInputs
    val only = ExtRun.computed
      .tables("mention-researcher")
      .union(
        TestSpark.frame(
          Tables.MentionResearcher.schema,
          List(Row(a, base("Z. Wang"), "dblp", base("Z. Wang"))),
        )
      )
    val v = verdict(replaced(ExtRun.computed, "mention-researcher", only), i)
    assert(v.ok, v.detail)
  }
end HomonymInvariantSuite
