package scholarchain.storage

import java.net.URI

import scholarchain.core.*

/** Live: one read-only template query (`neighbors`) with the configured bounds. Needs
  * `SCHOLARCHAIN_IT_NEPTUNE_ENDPOINT` (for example `https://<cluster-endpoint>:8182`) and
  * `SCHOLARCHAIN_IT_RESEARCHER_ID`. An empty result is acceptable: the suite proves the call, the
  * signing and the parameter passing, not the data. Run only with SCHOLARCHAIN_AWS_IT=1.
  */
class NeptuneReadAwsItSuite extends munit.FunSuite:
  override def munitIgnore: Boolean = !AwsItSupport.enabled

  test("neighbors template returns a result within bounds") {
    val cfg   = AwsItSupport.config.value
    val uri   = URI.create(AwsItSupport.env("SCHOLARCHAIN_IT_NEPTUNE_ENDPOINT"))
    val store =
      AwsItSupport.clients(Some(uri)).graphStore(cfg).fold(e => fail(e.toString), identity)
    val result = store.query(
      GraphQuery(
        GraphTemplates.Neighbors,
        Map(
          "researcherId" -> GraphValue.Str(AwsItSupport.env("SCHOLARCHAIN_IT_RESEARCHER_ID")),
          "window"       -> GraphValue.Str("1000-9999"),
        ),
        cfg.queryBounds,
      )
    )
    assert(result.isRight, result.toString)
    assert(result.toOption.get.rows.size <= cfg.queryBounds.maxResults)
  }
