package scholarchain.storage

import java.nio.charset.StandardCharsets

import scholarchain.core.Hashing

/** RFC 4180 CSV as Neptune's openCypher loader reads it (UTF-8, comma-separated, header row).
  * Source:
  * https://docs.aws.amazon.com/neptune/latest/userguide/bulk-load-tutorial-format-opencypher.html
  * (accessed 2026-11-21): fields containing a comma, a double quote or a line break are surrounded
  * by double quotes and an embedded quote is written as two quotes.
  */
object CsvFormat:
  def escape(field: String): String =
    val needsQuotes = field.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') ||
      field.startsWith(" ") || field.endsWith(" ")
    if needsQuotes then "\"" + field.replace("\"", "\"\"") + "\"" else field

  /** One record, LF-terminated. */
  def line(fields: Seq[String]): String = fields.map(escape).mkString(",") + "\n"

  /** Parses RFC 4180 text (LF or CRLF line ends) into records. A trailing line end does not create
    * an empty record. `Left` on an unterminated quote or a stray quote.
    */
  def parse(text: String): Either[String, Vector[Vector[String]]] =
    val records          = Vector.newBuilder[Vector[String]]
    var fields           = Vector.newBuilder[String]
    val cur              = new StringBuilder
    var inQ              = false
    var quoted           = false
    var any              = false
    var error            = Option.empty[String]
    var i                = 0
    def endField(): Unit =
      fields += cur.toString
      cur.clear()
      quoted = false
    def endRecord(): Unit =
      endField()
      records += fields.result()
      fields = Vector.newBuilder[String]
      any = false
    while i < text.length && error.isEmpty do
      val c = text.charAt(i)
      if inQ then
        if c == '"' then
          if i + 1 < text.length && text.charAt(i + 1) == '"' then
            cur.append('"')
            i += 1
          else inQ = false
        else cur.append(c)
      else
        c match
          case '"' =>
            if cur.isEmpty && !quoted then
              inQ = true
              quoted = true
              any = true
            else error = Some(s"stray quote at offset $i")
          case ',' =>
            endField()
            any = true
          case '\r' if i + 1 < text.length && text.charAt(i + 1) == '\n' => ()
          case '\n'                                                      => endRecord()
          case other                                                     =>
            if quoted then error = Some(s"text after closing quote at offset $i")
            else
              cur.append(other)
              any = true
      i += 1
    if error.isDefined then Left(error.get)
    else if inQ then Left("unterminated quoted field")
    else
      if any || cur.nonEmpty then endRecord()
      Right(records.result())

/** Neptune openCypher property types used by the loader. `[]` array types are deliberately not
  * produced: the Neptune openCypher documentation recommends against inserting array property
  * values (the loader would store a set of multi-valued properties, not a list), see
  * https://docs.aws.amazon.com/neptune/latest/userguide/access-graph-opencypher-data-model.html
  * (accessed 2026-11-21). Arrays and maps are therefore written as one `String` property holding
  * compact JSON.
  */
enum PropType(val header: String):
  case Str  extends PropType("String")
  case Int_ extends PropType("Int")
  case Lng  extends PropType("Long")
  case Dbl  extends PropType("Double")
  case Bool extends PropType("Bool")

object PropType:
  /** Spark catalog type to (Neptune type, written as JSON text?). */
  def of(sparkType: String): Either[String, (PropType, Boolean)] = sparkType match
    case "string"  => Right((Str, false))
    case "int"     => Right((Int_, false))
    case "bigint"  => Right((Lng, false))
    case "double"  => Right((Dbl, false))
    case "boolean" => Right((Bool, false))
    case t if t.startsWith("array<") || t.startsWith("map<") || t.startsWith("struct<") =>
      Right((Str, true))
    case t => Left(s"no Neptune type for Spark type '$t'")

/** A property column: the header (`name:Type`), the source column and how to render it. */
final case class PropCol(name: String, tpe: PropType, asJson: Boolean, index: Int):
  def header: String = s"$name:${tpe.header}"

/** One written file with its content hash. `rows` excludes the header. */
final case class CsvFile(
    relativePath: String,
    kind: String,
    label: String,
    rows: Long,
    bytes: Array[Byte],
):
  def sha256: String = Hashing.sha256Hex(bytes)

/** Lineage columns of a Neptune row (M6): every vertex and edge row of a load carries the load run,
  * the Spark run that produced the table, and the digest of the provenance it rests on. The digest
  * is the SHA-256 of the row-level `provenance` JSON string where the table has that column, else
  * the SHA-256 of the table's `_provenance.json` bytes (`tableProvenanceDigest`). The full
  * provenance behind each digest is listed in `load-manifest.json`.
  */
final case class Lineage(
    loadRunId: String,
    sparkRunId: String,
    tableProvenanceDigest: TableRef => Either[String, String],
)

object Lineage:
  val LoadRunId             = "loadRunId"
  val SparkRunId            = "sparkRunId"
  val ProvenanceDigest      = "provenanceDigest"
  val Names: List[String]   = List(LoadRunId, SparkRunId, ProvenanceDigest)
  val Headers: List[String] = Names.map(_ + ":String")

object NeptuneCsv:
  private val PropName = "^[A-Za-z_][A-Za-z0-9_]*$".r

  val VertexDir = "vertices"
  val EdgeDir   = "edges"

  def vertexPath(label: String): String = s"$VertexDir/$label.csv"
  def edgePath(label: String): String   = s"$EdgeDir/$label.csv"

  /** Text of one cell for a CSV column; the empty string means "no property" (Neptune stores no
    * value for an empty field). Control characters other than tab, LF and CR are rejected.
    */
  def cellText(c: Cell, p: PropCol): Either[String, String] =
    val text = c match
      case Cell.Null         => Right("")
      case other if p.asJson => Right(ujson.write(other.toJson))
      case Cell.S(v)         => Right(v)
      case Cell.I(v)         => Right(v.toString)
      case Cell.L(v)         => Right(v.toString)
      case Cell.D(v)         => Right(doubleText(v))
      case Cell.B(v)         => Right(if v then "true" else "false")
      case _ => Left(s"property '${p.name}' holds a nested value but is not JSON-typed")
    text.flatMap(t =>
      if t.exists(ch => ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r') then
        Left(s"property '${p.name}' contains a control character")
      else Right(t)
    )

  /** Neptune accepts decimal and scientific notation, `Infinity`, `-Infinity` and `NaN`. */
  def doubleText(d: Double): String =
    if d.isNaN then "NaN"
    else if d.isPosInfinity then "Infinity"
    else if d.isNegInfinity then "-Infinity"
    else d.toString

  def propCols(table: TableData, names: List[String]): Either[String, List[PropCol]] =
    val seq = names.foldRight[Either[String, List[PropCol]]](Right(Nil)) { (n, acc) =>
      for
        rest <- acc
        i    <- table.col(n)
        _    <-
          if PropName.matches(n) then Right(())
          else Left(s"table '${table.name}': property name '$n' is not a valid column name")
        t <- table.typeOf(n).toRight(s"missing type of '$n'").flatMap(PropType.of)
      yield PropCol(n, t._1, t._2, i) :: rest
    }
    seq.flatMap(cols =>
      if cols.map(_.name).distinct.size == cols.size then Right(cols)
      else Left(s"table '${table.name}': duplicate property column")
    )

  /** `:ID,:LABEL,<props>` for a vertex table. Rows keep the table order. */
  def vertexFile(
      spec: VertexSpec,
      table: TableData,
      lineage: Option[Lineage] = None,
  ): Either[String, CsvFile] =
    for
      _     <- table.col(spec.idColumn)
      props <- propCols(table, spec.propertyColumns)
      lin   <- lineageOf(spec.ref, table, props, lineage)
      body  <- rows(table.rows)(row =>
        for
          id <- table.string(row, spec.idColumn)
          _  <- if id.nonEmpty then Right(()) else Left(s"${spec.label}: empty vertex ID")
          ps <- cellsOf(row, props)
          ls <- lin(row)
        yield CsvFormat.line(id :: spec.label :: (ps ++ ls))
      )
    yield
      val header = CsvFormat.line(
        ":ID" :: ":LABEL" :: (props
          .map(_.header) ++ lineage.map(_ => Lineage.Headers).getOrElse(Nil))
      )
      CsvFile(
        vertexPath(spec.label),
        "vertex",
        spec.label,
        table.rows.size.toLong,
        utf8(header + body),
      )

  /** Deterministic ID of an edge without a Spark ID: `nedge:` plus the SHA-256 digest (core
    * `Hashing.digest`, tag `scholarchain.storage.neptune-edge.v1`) of the label and the key columns
    * of the manifest, in manifest order. Edges with a Spark `idColumn` use that ID verbatim.
    */
  val EdgeIdTag = "scholarchain.storage.neptune-edge.v1"

  def edgeId(spec: EdgeSpec, table: TableData, row: Vector[Cell]): Either[String, String] =
    spec.idColumn match
      case Some(c) => table.string(row, c).filterOrElse(_.nonEmpty, s"${spec.label}: empty edge ID")
      case None    =>
        val parts = spec.keyColumns.foldRight[Either[String, List[String]]](Right(Nil))((k, acc) =>
          for rest <- acc; c <- table.cell(row, k) yield c.str.getOrElse(c.render) :: rest
        )
        parts.flatMap(p =>
          if p.isEmpty then Left(s"${spec.label}: no key columns to derive an edge ID")
          else Right("nedge:" + Hashing.digest(EdgeIdTag, spec.label :: p))
        )

  /** `:ID,:START_ID,:END_ID,:TYPE,<props>` for an edge table. */
  def edgeFile(
      spec: EdgeSpec,
      table: TableData,
      lineage: Option[Lineage] = None,
  ): Either[String, CsvFile] =
    for
      props <- propCols(table, spec.propertyColumns)
      _     <- table.col(spec.fromColumn)
      _     <- table.col(spec.toColumn)
      lin   <- lineageOf(spec.ref, table, props, lineage)
      body  <- rows(table.rows)(row =>
        for
          id <- edgeId(spec, table, row)
          f  <- table.string(row, spec.fromColumn)
          t  <- table.string(row, spec.toColumn)
          _  <-
            if f.nonEmpty && t.nonEmpty then Right(())
            else Left(s"${spec.label}: empty edge endpoint")
          ps <- cellsOf(row, props)
          ls <- lin(row)
        yield CsvFormat.line(id :: f :: t :: spec.label :: (ps ++ ls))
      )
    yield
      val header =
        CsvFormat.line(
          ":ID" :: ":START_ID" :: ":END_ID" :: ":TYPE" ::
            (props.map(_.header) ++ lineage.map(_ => Lineage.Headers).getOrElse(Nil))
        )
      CsvFile(edgePath(spec.label), "edge", spec.label, table.rows.size.toLong, utf8(header + body))

  /** The lineage cells of a row (`loadRunId`, `sparkRunId`, `provenanceDigest`), or none without
    * lineage. Fails when a property column already uses a lineage name, or when a row-level
    * `provenance` is not a non-null string.
    */
  private def lineageOf(
      ref: TableRef,
      table: TableData,
      props: List[PropCol],
      lineage: Option[Lineage],
  ): Either[String, Vector[Cell] => Either[String, List[String]]] =
    lineage match
      case None    => Right(_ => Right(Nil))
      case Some(l) =>
        if props.exists(p => Lineage.Names.contains(p.name)) then
          Left(s"table '${table.name}': a property column uses a reserved lineage name")
        else if table.has("provenance") then
          Right(row =>
            table
              .string(row, "provenance")
              .map(t => List(l.loadRunId, l.sparkRunId, Cells.sha256(t)))
          )
        else l.tableProvenanceDigest(ref).map(d => _ => Right(List(l.loadRunId, l.sparkRunId, d)))

  private def cellsOf(row: Vector[Cell], props: List[PropCol]): Either[String, List[String]] =
    props.foldRight[Either[String, List[String]]](Right(Nil))((p, acc) =>
      for rest <- acc; t <- cellText(row(p.index), p) yield t :: rest
    )

  private def rows(rs: Vector[Vector[Cell]])(
      f: Vector[Cell] => Either[String, String]
  ): Either[String, String] =
    val sb                  = new StringBuilder
    val it                  = rs.iterator
    var err: Option[String] = None
    while err.isEmpty && it.hasNext do
      f(it.next()) match
        case Right(s) => sb.append(s)
        case Left(e)  => err = Some(e)
    err.toLeft(sb.toString)

  private def utf8(s: String): Array[Byte] = s.getBytes(StandardCharsets.UTF_8)
