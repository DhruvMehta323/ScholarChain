package scholarchain.hadoop

import java.io.{File, FileInputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath, Paths}
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.util.ToolRunner

/** Helpers shared by the Hadoop integration suites. All fixtures are SYNTHETIC (see
  * `src/it/resources/fixtures`); all output goes under `modules/hadoop/target/it-runs`.
  */
object ItSupport:
  /** `modules/hadoop/target` (found by walking up from this class to the directory with build.sbt).
    */
  lazy val targetDir: JPath =
    var p = Paths.get(getClass.getProtectionDomain.getCodeSource.getLocation.toURI).toAbsolutePath
    while p != null && !Files.exists(p.resolve("build.sbt")) do p = p.getParent
    require(p != null, "repository root not found")
    p.resolve("modules/hadoop/target")

  private val FixtureFiles = List(
    "dblp-synth.xml",
    "dblp-synth.dtd",
    "dblp-edge-synth.xml",
    "dblp-unclosed-synth.xml",
    "crossref/c1.json",
    "crossref/c1-refetch.json",
    "crossref/c2.json",
    "crossref/c3-bad.json",
    "crossref/c4-missing.json",
    "crossref/errors.jsonl",
    "openalex/o1.json",
    "openalex/o2-notitle.json",
    "orcid/p1.json",
    "orcid/p2.json",
    "orcid/p3-bad.json",
    "orcid/p4-shared-doi.json",
  )

  /** The classpath fixtures copied to a fresh directory (classpath resources may live in a jar). */
  private lazy val fixtureDir: JPath =
    val d = targetDir.resolve("it-runs").resolve(s"fixtures-${System.nanoTime()}")
    FixtureFiles.foreach { f =>
      val dest = d.resolve(f)
      Files.createDirectories(dest.getParent)
      val in = getClass.getResourceAsStream(s"/fixtures/$f")
      require(in != null, s"missing fixture $f")
      try Files.copy(in, dest)
      finally in.close()
    }
    d

  def fixture(name: String): JPath = fixtureDir.resolve(name)

  /** A path that does not exist yet (a rerun always uses a new root). */
  def newRoot(label: String): JPath =
    val d = targetDir.resolve("it-runs").resolve(s"$label-${System.nanoTime()}")
    Files.createDirectories(d.getParent)
    d

  /** The code version of the integration runs (required by the driver, F4). */
  val CodeVersion: String = "it-test+uncommitted"

  def baseArgs(root: JPath): List[String] = List(
    "--dblp",
    fixture("dblp-synth.xml").toString,
    "--dtd",
    fixture("dblp-synth.dtd").toString,
    "--crossref",
    fixture("crossref").toString,
    "--openalex",
    fixture("openalex").toString,
    "--orcid",
    fixture("orcid").toString,
    "--output",
    root.toString,
    "--snapshot",
    "synthetic",
    "--code-version",
    CodeVersion,
  )

  def conf(overrides: (String, String)*): Configuration =
    val c = new Configuration()
    overrides.foreach((k, v) => c.set(k, v))
    c

  /** Runs the pipeline driver in-process (LocalJobRunner). */
  def runDriver(root: JPath, overrides: (String, String)*): Int =
    ToolRunner.run(conf(overrides*), new PipelineDriver, baseArgs(root).toArray)

  private def open(f: File): InputStream =
    val in = new FileInputStream(f)
    if f.getName.endsWith(".gz") then new GZIPInputStream(in) else in

  def files(dir: JPath): List[File] =
    if !Files.exists(dir) then Nil
    else
      Files
        .walk(dir)
        .iterator()
        .asScala
        .map(_.toFile)
        .filter(f => f.isFile)
        .toList
        .sortBy(_.getPath)

  def partFiles(dir: JPath): List[File] =
    files(dir).filter(_.getName.startsWith("part-"))

  def lines(f: File): List[String] =
    val in = open(f)
    try new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n").toList.filter(_.nonEmpty)
    finally in.close()

  def curated(root: JPath): JPath = root.resolve("curated")

  /** All JSON rows of a curated table. */
  def table(root: JPath, name: String): List[ujson.Value] =
    partFiles(curated(root).resolve(name)).flatMap(lines).map(ujson.read(_))

  /** Raw (unparsed) lines of a curated table, with the part file's path relative to the table. */
  def tableLines(root: JPath, name: String): List[(String, String)] =
    val base = curated(root).resolve(name)
    partFiles(base).flatMap(f => lines(f).map(l => (base.relativize(f.toPath).toString, l)))

  def runDir(root: JPath): JPath =
    files(root.resolve("run")).map(_.toPath.getParent).head

  def readJson(p: JPath): ujson.Value = ujson.read(Files.readString(p))

  def sha256(p: JPath): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(p))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  def sorted(xs: Iterable[String]): List[String] = xs.toList.sorted
end ItSupport
