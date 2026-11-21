package scholarchain.hadoop

import ErItSupport.setup
import ItSupport.*

/** Run set-up checks that launch NO job (they stop at argument and configuration handling): N2 (the
  * recorded `export-reducers` equals the value the job uses, an invalid value is refused), the ER
  * settings in the run configuration, and N4 (retrievedAt compared as an instant).
  *
  * Job launches: 0.
  */
class RunSetupItSuite extends munit.FunSuite:
  private def prepare(overrides: (String, String)*) =
    val args = PipelineArgs.parse(baseArgs(newRoot("setup")).toArray).toOption.get
    RunSetup.prepare(conf(overrides*), args)

  test(
    "N2: the recorded export-reducers is the value HJ5 uses (the job reads the recorded value)"
  ) {
    val s = prepare("scholarchain.hadoop.export-reducers" -> "3").toOption.get
    assertEquals(s.run.config("hadoop.export-reducers"), "3")
    assertEquals(s.run.allConfig("hadoop.export-reducers"), "3")
    val d = prepare().toOption.get
    assertEquals(d.run.config("hadoop.export-reducers"), "1")
  }

  test("N2: an invalid export-reducers is refused instead of silently becoming 1") {
    List("0", "-2", "abc", "1.5", "").foreach { v =>
      val r = prepare("scholarchain.hadoop.export-reducers" -> v)
      // an empty value is not a setting; every other one is a refusal
      if v.nonEmpty then
        assert(r.isLeft && r.left.exists(_.contains("export-reducers")), s"'$v' -> $r")
    }
    val rc = org.apache.hadoop.util.ToolRunner.run(
      conf("scholarchain.hadoop.export-reducers" -> "0"),
      new PipelineDriver,
      baseArgs(newRoot("setup-refused")).toArray,
    )
    assertEquals(rc, 2)
  }

  test("the resolved ER settings are in the run configuration and hashed into the run ID") {
    val a = prepare().toOption.get
    val b = prepare("scholarchain.er.max-block-size" -> "7").toOption.get
    assertEquals(a.run.er("er.max-block-size"), "500")
    assertEquals(b.run.er("er.max-block-size"), "7")
    assertEquals(a.run.er("er.policy-version"), "er-v1")
    assertNotEquals(a.run.runId, b.run.runId)
    // the Phase 04 provenance parameters stay free of er.* keys (only HJ6 and HJ7 add them)
    assert(
      !a.run
        .prov(scholarchain.core.SourceType.Dblp, Seq("dblp"), "x")
        .parameters
        .keys
        .exists(_.startsWith("er."))
    )
    assert(a.run.provAll("x", withEr = true).parameters.contains("er.max-block-size"))
  }

  test("an invalid ER setting refuses the run") {
    assert(prepare("scholarchain.er.max-block-size" -> "1").isLeft)
    assert(prepare("scholarchain.er.researcher.threshold.reject" -> "0.99").isLeft)
  }

  test("N4: retrievedAt is compared as an instant, not as text") {
    // +02:00 offset: 09:00+02:00 is 07:00Z, EARLIER than 08:00Z, though the text sorts later
    val a = "2026-01-01T09:00:00+02:00"
    val b = "2026-01-01T08:00:00Z"
    assert(a > b, "the text order is the opposite of the instant order")
    assert(Dedupe.instantKey(Some(a)).isBefore(Dedupe.instantKey(Some(b))))
    // fractional seconds
    assert(
      Dedupe
        .instantKey(Some("2026-01-01T00:00:00.5Z"))
        .isAfter(Dedupe.instantKey(Some("2026-01-01T00:00:00Z")))
    )
    // absent and unparsable sort first
    assertEquals(Dedupe.instantKey(None), Dedupe.instantKey(Some("not a time")))
    assert(Dedupe.instantKey(None).isBefore(Dedupe.instantKey(Some(b))))
  }
end RunSetupItSuite
