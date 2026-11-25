package scholarchain.hadoop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.conf.Configuration

/** Runs HJ2 (SourceNormalizeJob) alone over synthetic envelope directories (LocalJobRunner) and
  * captures every output byte. Uses only APIs that exist before and after the packed-input change,
  * so the same code produced the golden files at commit f5cc5f133415. Synthetic data only.
  */
object Hj2RunSupport:
  import ItSupport.*

  final case class Run(setup: Setup, outcome: JobOutcome, work: JPath)

  def conf1(): Configuration = conf("scholarchain.hadoop.reducers.normalize" -> "1")

  /** Prepares the run setup over the given source directories (DBLP and DTD are the it fixtures).
    */
  def prepare(
      root: JPath,
      crossref: Option[JPath],
      openalex: Option[JPath],
      orcid: Option[JPath],
  ): Setup =
    val srcs = List("--crossref" -> crossref, "--openalex" -> openalex, "--orcid" -> orcid)
      .flatMap((f, p) => p.toList.flatMap(d => List(f, d.toString)))
    val a = List(
      "--dblp",
      fixture("dblp-synth.xml").toString,
      "--dtd",
      fixture("dblp-synth.dtd").toString,
    ) ++ srcs ++ List(
      "--output",
      root.toString,
      "--snapshot",
      "synthetic",
      "--code-version",
      CodeVersion,
    )
    val args = PipelineArgs.parse(a.toArray).fold(m => throw new AssertionError(m), identity)
    RunSetup.prepare(conf1(), args).fold(m => throw new AssertionError(m), identity)

  def run(
      label: String,
      crossref: Option[JPath] = None,
      openalex: Option[JPath] = None,
      orcid: Option[JPath] = None,
  ): Run =
    val root = newRoot(label)
    val s    = prepare(root, crossref, openalex, orcid)
    val out  = SourceNormalizeJob.submit(conf1(), s).getOrElse(throw new AssertionError("no job"))
    Run(s, out, root.resolve("work").resolve("hj2"))

  /** The standard fixture run (the it fixtures: single-file envelopes, `errors.jsonl` included). */
  def fixtureRun(label: String): Run =
    run(
      label,
      Some(fixture("crossref")),
      Some(fixture("openalex")),
      Some(fixture("orcid")),
    )

  private def kv(m: Map[String, Long]): String =
    m.toList.sortBy(_._1).map((k, v) => s"$k=$v").mkString("\n")

  /** Every observable HJ2 byte: each output file under work/hj2 (relative path, exact bytes; Hadoop
    * `.crc` and `_SUCCESS` files left out), the counters, and the input checksums of the run.
    */
  def capture(r: Run): Map[String, Array[Byte]] =
    val files =
      if !Files.exists(r.work) then Nil
      else
        Files
          .walk(r.work)
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .filter(p =>
            !p.getFileName.toString.startsWith(".") && !p.getFileName.toString.startsWith("_")
          )
          .toList
    val tables =
      files.map(p => ("tables/" + r.work.relativize(p).toString) -> Files.readAllBytes(p))
    val txt    = (n: String, s: String) => n -> s.getBytes(StandardCharsets.UTF_8)
    val inputs = r.setup.run.inputs.toList
      .filter((k, _) => k != "dblp" && k != "dtd")
      .sortBy(_._1)
      .flatMap((src, is) =>
        is.map(i => s"$src ${i.inputId} ${i.version} ${i.checksum.getOrElse("-")}")
      )
      .mkString("\n")
    (tables ++ List(
      txt(
        "counters.txt",
        s"success=${r.outcome.success}\n[counters]\n${kv(r.outcome.counters)}\n[rows]\n${kv(r.outcome.rows)}\n[rejects]\n${kv(r.outcome.rejects)}\n",
      ),
      txt("input-checksums.txt", inputs + "\n"),
    )).toMap

  def sha256Hex(b: Array[Byte]): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(b)
      .map(x => f"${x & 0xff}%02x")
      .mkString

  /** A fresh directory holding copies of the named files (relative to a fixture dir). */
  def copyInto(dir: JPath, from: JPath, names: List[String]): JPath =
    Files.createDirectories(dir)
    names.foreach(n => Files.copy(from.resolve(n), dir.resolve(n)))
    dir

  /** One compact envelope per line, sorted by file name (a file that is not JSON keeps its text,
    * newlines turned into spaces, so it stays one bad line).
    */
  def packLines(from: JPath, names: List[String]): List[String] =
    names.sorted.map { n =>
      val t = Files.readString(from.resolve(n))
      try ujson.write(ujson.read(t))
      catch case _: Exception => t.replace('\n', ' ')
    }

  def write(p: JPath, s: String): JPath =
    Files.createDirectories(p.getParent)
    Files.write(p, s.getBytes(StandardCharsets.UTF_8))
    p
end Hj2RunSupport
