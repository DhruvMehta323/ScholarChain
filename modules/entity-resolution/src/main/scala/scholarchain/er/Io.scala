package scholarchain.er

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.util.zip.GZIPInputStream

import scala.jdk.CollectionConverters.*
import scala.util.Try

import scholarchain.core.*
import scholarchain.core.WirePickle.{*, given}

/** Minimal RFC 4180 CSV: quoted fields may hold commas, quotes (doubled) and line breaks. */
object Csv:
  def parse(text: String): List[List[String]] =
    val rows             = List.newBuilder[List[String]]
    var row              = Vector.empty[String]
    val field            = StringBuilder()
    var quoted           = false
    var i                = 0
    def endField(): Unit =
      row = row :+ field.toString
      field.clear()
    def endRow(): Unit =
      endField()
      if !(row.size == 1 && row.head.isEmpty) then rows += row.toList
      row = Vector.empty
    while i < text.length do
      val c = text.charAt(i)
      if quoted then
        if c == '"' then
          if i + 1 < text.length && text.charAt(i + 1) == '"' then
            field.append('"')
            i += 1
          else quoted = false
        else field.append(c)
      else
        c match
          case '"'  => quoted = true
          case ','  => endField()
          case '\r' => ()
          case '\n' => endRow()
          case _    => field.append(c)
      i += 1
    if field.nonEmpty || row.nonEmpty then endRow()
    rows.result()

  private def cell(s: String): String =
    if s.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then
      "\"" + s.replace("\"", "\"\"") + "\""
    else s

  def write(rows: Seq[Seq[String]]): String =
    rows.map(_.map(cell).mkString(",")).mkString("", "\n", "\n")

/** Parsed command line: `--name value` pairs (repeatable) and bare `--flag`s. */
final case class Args(values: Map[String, List[String]], flags: Set[String]):
  def get(name: String): Option[String]         = values.get(name).flatMap(_.lastOption)
  def req(name: String): Either[String, String] =
    get(name).toRight(s"missing required argument --$name")
  def path(name: String): Either[String, Path]                = req(name).map(Paths.get(_))
  def long(name: String, default: Long): Either[String, Long] =
    get(name).fold(Right(default))(s => s.toLongOption.toRight(s"--$name must be an integer"))
  def overrides: Map[String, String] =
    values
      .getOrElse("override", Nil)
      .flatMap { kv =>
        kv.indexOf('=') match
          case -1 => None
          case i  => Some(kv.substring(0, i) -> kv.substring(i + 1))
      }
      .toMap

object Args:
  def parse(argv: Seq[String]): Args =
    var values = Map.empty[String, List[String]]
    var flags  = Set.empty[String]
    var i      = 0
    while i < argv.length do
      val a = argv(i)
      if a.startsWith("--") then
        val name = a.drop(2)
        if i + 1 < argv.length && !argv(i + 1).startsWith("--") then
          values = values.updated(name, values.getOrElse(name, Nil) :+ argv(i + 1))
          i += 1
        else flags += name
      i += 1
    Args(values, flags)

object Io:
  /** Regular files under `p` (or `p` itself), recursively, sorted; skips names starting with `_` or
    * `.` and `.crc` files (Hadoop markers).
    */
  def files(p: Path): List[Path] =
    if Files.isDirectory(p) then
      val s = Files.walk(p)
      try
        s.iterator.asScala
          .filter(Files.isRegularFile(_))
          .filter { f =>
            val n = f.getFileName.toString
            !n.startsWith("_") && !n.startsWith(".") && !n.endsWith(".crc")
          }
          .toList
          .sortBy(_.toString)
      finally s.close()
    else List(p)

  def lines(p: Path): List[String] =
    files(p).flatMap { f =>
      val raw = Files.newInputStream(f)
      val in  =
        if f.getFileName.toString.endsWith(".gz") then GZIPInputStream(raw) else raw
      val r = BufferedReader(InputStreamReader(in, StandardCharsets.UTF_8))
      try r.lines().iterator.asScala.filter(_.trim.nonEmpty).toList
      finally r.close()
    }

  /** SHA-256 of a file; for a directory, a digest over (relative name, file checksum) pairs. */
  def sha256(p: Path): String =
    if Files.isDirectory(p) then
      Hashing.digest(
        "scholarchain.er.dir.v1",
        files(p).flatMap(f =>
          Seq(p.relativize(f).toString, Hashing.sha256Hex(Files.readAllBytes(f)))
        ),
      )
    else Hashing.sha256Hex(Files.readAllBytes(p))

  def inputRef(id: String, version: String, p: Path): InputRef =
    InputRef(id, version, Some(sha256(p)))

  /** Writes a NEW file. An existing file is never overwritten (a rerun needs a new path). */
  def writeNew(p: Path, content: String): Either[String, Unit] =
    if Files.exists(p) then Left(s"refusing to overwrite existing file $p")
    else
      Try {
        Option(p.getParent).foreach(Files.createDirectories(_))
        Files.write(p, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW)
        ()
      }.toEither.left.map(_ => s"cannot write $p")

  /** Streams `lines` (each a line without its newline) to a new file, byte-identical to
    * `writeNew(p, lines.mkString("", "\n", if empty then "" else "\n"))`. Refuses an existing file.
    * Writes a sibling temp file and moves it into place without replacing, so a failed line leaves
    * no partial output.
    */
  def writeLinesNew(p: Path, lines: Iterator[Either[String, String]]): Either[String, Unit] =
    if Files.exists(p) then Left(s"refusing to overwrite existing file $p")
    else
      val tmp = Try {
        Option(p.getParent).foreach(Files.createDirectories(_))
        Files.createTempFile(
          Option(p.toAbsolutePath.getParent).getOrElse(Paths.get(".")),
          ".er-",
          ".tmp",
        )
      }.toEither.left.map(_ => s"cannot write $p")
      tmp.flatMap { t =>
        val result: Either[String, Unit] =
          try
            val w = java.io.BufferedWriter(
              java.io.OutputStreamWriter(Files.newOutputStream(t), StandardCharsets.UTF_8),
              1 << 16,
            )
            try
              var err: Option[String] = None
              while err.isEmpty && lines.hasNext do
                lines.next() match
                  case Left(e)  => err = Some(e)
                  case Right(l) => w.write(l); w.write('\n')
              err.toLeft(())
            finally w.close()
          catch case _: Exception => Left(s"cannot write $p")
        val moved = result.flatMap(_ =>
          Try(Files.move(t, p)).toEither.left.map(_ => s"cannot write $p").map(_ => ())
        )
        if moved.isLeft then Try(Files.deleteIfExists(t))
        moved
      }

  private def readAll[A](p: Path, label: String)(
      f: String => Either[String, A]
  ): Either[String, List[A]] =
    val parsed = Try(lines(p)).toEither.left.map(_ => s"cannot read $label at $p")
    parsed.flatMap { ls =>
      val rs = ls.map(f)
      rs.collectFirst { case Left(e) => Left(s"$label: $e") }
        .getOrElse(Right(rs.collect { case Right(a) => a }))
    }

  def readCandidates(p: Path): Either[String, List[MatchCandidate]] =
    readAll(p, "candidates")(l => Wire.read[MatchCandidate](l).left.map(_.message))

  def readViews(p: Path): Either[String, Map[String, EntityView]] =
    readAll(p, "views")(l =>
      Try(WirePickle.read[EntityView](l)).toEither.left.map(_ => "invalid entity-view line")
    ).map(_.map(v => v.id -> v).toMap)

  def errs(es: List[ValidationError]): String = es.map(_.message).mkString("; ")

/** Loads the effective configuration: versioned defaults, environment, `--override k=v` (the key
  * written as `scholarchain.er.<key>`), then, when `--policy` is given, the frozen thresholds.
  */
object Tooling:
  def config(a: Args, env: Map[String, String]): Either[String, (ErConfig, Option[FrozenPolicy])] =
    ErConfigLoader.load(env, a.overrides).left.map(Io.errs).flatMap { base =>
      a.get("policy") match
        case None    => Right((base, None))
        case Some(p) =>
          for
            text <- Try(Files.readString(Paths.get(p))).toEither.left
              .map(_ => s"cannot read policy file $p")
            frozen <- FrozenPolicy.read(text).left.map(_.message)
            drift = FrozenPolicy.envDrift(frozen, env)
            _ <- Either.cond(
              drift.isEmpty,
              (),
              s"SCHOLARCHAIN_ER_* environment differs from the frozen policy: ${drift.mkString(", ")}",
            )
            _ <- Either.cond(
              !FrozenPolicy.modelOverrideDiffers(frozen, a.overrides),
              (),
              s"--override ${FrozenPolicy.ModelOverrideKey} differs from the frozen policy",
            )
            cfg <- FrozenPolicy.applyTo(base, frozen).left.map(_.message)
          yield (cfg, Some(frozen))
    }

  /** Runs a tool body; an error is printed (never a secret) and the process exits 1. */
  def exit(r: Either[String, Unit]): Unit = r match
    case Right(_) => ()
    case Left(e)  =>
      System.err.println(s"ERROR: $e")
      sys.exit(1)

/** A candidate with its policy result under the thresholds in force. */
final case class Scored(candidate: MatchCandidate, pairId: String, result: PolicyResult)

object Resolver:
  /** The full decision: policy, then an optional validated adjudication. */
  def decide(c: MatchCandidate, cfg: ErConfig, adj: Option[AdjudicationRecord]): PolicyResult =
    Policy.applyAdjudication(
      Policy.decide(c.entityKind, c.features, cfg.thresholds(c.entityKind)),
      adj,
      cfg.llm.minConfidence,
      c,
    )

  def scored(c: MatchCandidate, cfg: ErConfig): Scored =
    Scored(c, ErIds.pairId(c.entityKind, c.leftId, c.rightId), decide(c, cfg, None))

  /** The cached adjudication of a candidate, when the views are available and the cache holds an
    * answer for exactly this input, prompt, model and policy version.
    */
  def cached(
      c: MatchCandidate,
      cfg: ErConfig,
      views: Map[String, EntityView],
      cache: AdjudicationCache,
      promptVersion: String,
  ): Option[AdjudicationRecord] =
    for
      l <- views.get(c.leftId)
      r <- views.get(c.rightId)
      ih = AdjudicationInput.inputHash(c, l, r)
      e   <- cache.get(AdjudicationCache.key(ih, promptVersion, cfg.llm.model, cfg.policyVersion))
      rec <- Some(e.record).filter(a =>
        AdjudicationRecord.validateAgainst(a, c).isRight &&
          AdjudicationCache.recordMatches(a, ih, promptVersion, cfg.llm.model, cfg.policyVersion)
      )
    yield rec
