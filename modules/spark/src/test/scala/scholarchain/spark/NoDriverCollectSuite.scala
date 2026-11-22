package scholarchain.spark

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.*

/** `DriverCollect` is the only place that may bring data to the driver. This suite scans the
  * production sources for driver-collection calls outside it, proves the scanner would flag one,
  * and checks the runtime bounds of `DriverCollect` itself.
  */
class NoDriverCollectSuite extends munit.FunSuite:

  /** The allow-list: the only production file permitted to collect. */
  private val Allowed = "DriverCollect.scala"

  /** Calls that bring rows (not counts) to the driver. */
  private val Forbidden =
    """\.(collect|collectAsList|toLocalIterator|take|takeAsList|head|first|tail)\s*\(""".r

  /** Lines that are comments (line comments and scaladoc/blockdoc continuation lines). */
  private def isComment(line: String): Boolean =
    val t = line.trim
    t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")

  /** (line number, matched call) of every driver-collection call in `text`. */
  private def scan(text: String): List[(Int, String)] =
    text.linesIterator.zipWithIndex.toList.flatMap { (line, i) =>
      if isComment(line) then Nil else Forbidden.findAllIn(line).toList.map(m => (i + 1, m))
    }

  private lazy val sourceDir: Path =
    List(Paths.get("src/main/scala"), Paths.get("modules/spark/src/main/scala"))
      .find(p => Files.isDirectory(p.resolve("scholarchain/spark")))
      .getOrElse(
        fail("cannot locate modules/spark/src/main/scala from " + Paths.get("").toAbsolutePath)
      )

  private lazy val sources: List[(String, String)] =
    val s = Files.walk(sourceDir.resolve("scholarchain/spark"))
    try
      s.iterator()
        .asScala
        .filter(_.toString.endsWith(".scala"))
        .toList
        .sortBy(_.toString)
        .map(p => p.getFileName.toString -> Files.readString(p, StandardCharsets.UTF_8))
    finally s.close()

  // ---- the scan -------------------------------------------------------------------------------

  test(
    "no production source outside DriverCollect.scala collects, takes, heads or firsts a frame"
  ) {
    val offenders = sources.filterNot(_._1 == Allowed).flatMap { (name, text) =>
      scan(text).map((line, m) => s"$name:$line `$m`")
    }
    assertEquals(offenders, Nil)
  }

  test(
    "the scan is not vacuous: it covers the whole module and does see DriverCollect's own collect"
  ) {
    assert(sources.size >= 20, sources.map(_._1).toString)
    val names = sources.map(_._1).toSet
    assert(
      Set(
        "Pipeline.scala",
        "Identity.scala",
        "Graph.scala",
        "Components.scala",
        "DriverCollect.scala",
      ).subsetOf(names)
    )
    val own = sources.find(_._1 == Allowed).map(s => scan(s._2)).getOrElse(Nil)
    assert(own.exists(_._2.startsWith(".collect")), own.toString)
  }

  test("the scanner flags each forbidden call on a synthetic source line") {
    List(
      "val x = df.collect()",
      "val x = df.collectAsList()",
      "df.toLocalIterator()",
      "val r = df.take(5)",
      "val r = df.head(3)",
      "val r = df.first()",
      "val r = df.limit(5).collect()",
      "df .select(\"a\").collect ()",
    ).foreach { src =>
      assert(scan(src).nonEmpty, s"scanner missed: $src")
    }
    assertEquals(
      scan("val a = df.limit(3)\nval b = DriverCollect.count(df)\nval c = xs.headOption").size,
      0,
    )
    // the line number is reported
    assertEquals(scan("val a = 1\nval b = df.collect()").map(_._1), List(2))
  }

  test("the scanner ignores comments and scaladoc lines that merely mention a call") {
    assertEquals(scan("// never df.collect() here"), Nil)
    assertEquals(scan("/** Uses .collect() in prose */"), Nil)
    assertEquals(scan("  * the .take(5) of a list"), Nil)
  }

  test("a source that adds a collect on a full frame would be reported with its file and line") {
    val injected = sources.map((n, t) =>
      if n == "Graph.scala" then
        n -> (t + "\nobject Leak:\n  def all(df: org.apache.spark.sql.DataFrame) = df.collect()\n")
      else n -> t
    )
    val offenders =
      injected.filterNot(_._1 == Allowed).flatMap((n, t) => scan(t).map((l, m) => s"$n:$l $m"))
    assertEquals(offenders.size, 1)
    assert(offenders.head.startsWith("Graph.scala:"), offenders.toString)
  }

  // ---- runtime bounds of DriverCollect --------------------------------------------------------

  private def big(n: Int) = TestSpark.spark.range(n.toLong).toDF("id")

  test("DriverCollect.rows is bounded: it returns at most maxRows rows of a large frame") {
    assertEquals(DriverCollect.MaxRows, 1000)
    assertEquals(DriverCollect.rows(big(5000), 10).length, 10)
    assertEquals(DriverCollect.rows(big(5000), 1000).length, 1000)
    assertEquals(DriverCollect.rows(big(3), 1000).length, 3)
  }

  test("DriverCollect.rows refuses a bound above the maximum or below zero") {
    intercept[IllegalArgumentException](DriverCollect.rows(big(5), 1001))
    intercept[IllegalArgumentException](DriverCollect.rows(big(5), -1))
  }

  test("DriverCollect: counts, emptiness and single values") {
    assertEquals(DriverCollect.count(big(42)), 42L)
    assert(DriverCollect.isEmpty(big(0)))
    assert(!DriverCollect.isEmpty(big(1)))
    assertEquals(DriverCollect.longValue(big(7).groupBy().count()), 7L)
    // a frame with several rows is not a single value
    intercept[IllegalArgumentException](DriverCollect.longValue(big(5)))
  }
