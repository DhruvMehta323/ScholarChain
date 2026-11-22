package scholarchain.storage

import java.nio.charset.StandardCharsets

/** DynamoDB item files (JSONL) for the Phase 06 analytics tables listed in the dynamodb manifest.
  *
  * One line per item, byte-stable: keys of the JSON objects are in ascending order, rows are in the
  * (deterministic) table order, text is UTF-8 with LF line ends.
  * {{{
  * {"attributes":{...},"payloadHash":"<sha256>","pk":"...","sk":"...","table":"<manifest table>"}
  * }}}
  *   - `pk` / `sk` come from the manifest `partitionKey` / `sortKey` columns. Several sort columns
  *     are joined with `|`; inside a component `%` becomes `%25` and `|` becomes `%7C` (so the
  *     encoding is unambiguous). `sk` is omitted when the manifest has no sort key.
  *   - `attributes` holds every other column (key columns are repeated so an item is
  *     self-describing). The row-level `provenance` string is replaced by `provenanceDigest` (the
  *     SHA-256 of that text) and `provenanceRunId`; the full provenance texts are listed once in
  *     the load manifest under their digest.
  *   - `payloadHash` is the SHA-256 of the compact JSON of `attributes`: a reload of identical data
  *     produces identical items, and a changed row under the same key is detectable.
  */
object AnalyticsItems:
  val MaxItemBytes: Int = 409600 // DynamoDB item size limit, 400 KB (see STORAGE-CONTRACTS.md)
  val MaxPartitionKeyBytes: Int = 2048
  val MaxSortKeyBytes: Int      = 1024

  /** One analytics item in the form `AnalyticsRepository` stores: the encoded partition and sort
    * key, the canonical compact JSON text of `attributes` (typed JSON, kept as ONE string so
    * nothing is lost: ints, doubles, nulls, arrays and objects survive byte for byte) and its
    * SHA-256 (`payloadHash`, the same value as in the JSONL line).
    */
  final case class AnalyticsItem(
      table: String,
      pk: String,
      sk: Option[String],
      attributesJson: String,
      payloadHash: String,
  )

  final case class ItemFile(
      table: String,
      rows: Long,
      bytes: Array[Byte],
      items: Vector[AnalyticsItem] = Vector.empty,
  ):
    def sha256: String = Cells.sha256(bytes)
    def path: String   = s"items/$table.jsonl"

  def encodeComponent(s: String): String = s.replace("%", "%25").replace("|", "%7C")

  private def keyPart(table: TableData, row: Vector[Cell], column: String): Either[String, String] =
    table.cell(row, column).flatMap {
      case Cell.Null => Left(s"${table.name}: null key column '$column'")
      case c         => Right(encodeComponent(c.str.getOrElse(c.render)))
    }

  def build(spec: DynamoTableSpec, table: TableData): Either[String, ItemFile] =
    val keyCols = spec.partitionKey :: spec.sortKey
    val missing = keyCols.filterNot(table.has)
    if missing.nonEmpty then
      Left(s"${table.name}: manifest key column(s) missing: ${missing.mkString(",")}")
    else
      val sb      = new StringBuilder
      val itemsB  = Vector.newBuilder[AnalyticsItem]
      val seen    = scala.collection.mutable.HashSet.empty[(String, String)]
      val it      = table.rows.iterator
      var err     = Option.empty[String]
      val provIdx = table.columns.indexWhere(_.name == "provenance")
      while err.isEmpty && it.hasNext do
        val row   = it.next()
        val built = for
          pk  <- keyPart(table, row, spec.partitionKey)
          sks <- spec.sortKey.foldRight[Either[String, List[String]]](Right(Nil))((c, acc) =>
            for rest <- acc; p <- keyPart(table, row, c) yield p :: rest
          )
        yield (pk, sks.mkString("|"))
        built match
          case Left(e)         => err = Some(e)
          case Right((pk, sk)) =>
            if pk.getBytes(StandardCharsets.UTF_8).length > MaxPartitionKeyBytes ||
              sk.getBytes(StandardCharsets.UTF_8).length > MaxSortKeyBytes ||
              pk.isEmpty || (spec.sortKey.nonEmpty && sk.isEmpty)
            then err = Some(s"${table.name}: key is empty or exceeds the DynamoDB key size limit")
            else if !seen.add((pk, sk)) then err = Some(s"${table.name}: duplicate item key")
            else
              val attrs = table.columns.zipWithIndex
                .filter((_, i) => i != provIdx)
                .map((c, i) => c.name -> row(i).toJson)
                .sortBy(_._1)
              val provAttrs =
                if provIdx < 0 then Nil
                else
                  row(provIdx).str match
                    case Some(text) =>
                      val runId = ujson.read(text).obj.get("runId").flatMap(_.strOpt).getOrElse("")
                      List(
                        "provenanceDigest" -> ujson.Str(Cells.sha256(text)),
                        "provenanceRunId"  -> ujson.Str(runId),
                      )
                    case None => Nil
              val all      = (attrs ++ provAttrs).sortBy(_._1)
              val attrObj  = ujson.Obj.from(all)
              val attrText = ujson.write(attrObj)
              val fields   = List[(String, ujson.Value)](
                "attributes"  -> attrObj,
                "payloadHash" -> ujson.Str(Cells.sha256(attrText)),
                "pk"          -> ujson.Str(pk),
              ) ++ (if spec.sortKey.nonEmpty then List("sk" -> ujson.Str(sk)) else Nil) ++
                List("table" -> ujson.Str(spec.ref.table))
              val line = ujson.write(ujson.Obj.from(fields)) + "\n"
              if line.getBytes(StandardCharsets.UTF_8).length > MaxItemBytes then
                err = Some(s"${table.name}: item exceeds the DynamoDB item size limit")
              else
                sb.append(line)
                itemsB += AnalyticsItem(
                  spec.ref.table,
                  pk,
                  if spec.sortKey.nonEmpty then Some(sk) else None,
                  attrText,
                  Cells.sha256(attrText),
                )
      err.toLeft(
        ItemFile(
          spec.ref.table,
          table.rows.size.toLong,
          sb.toString.getBytes(StandardCharsets.UTF_8),
          itemsB.result(),
        )
      )

  /** Distinct provenance texts of a table by digest (for the load manifest). */
  def provenanceTexts(table: TableData): Map[String, String] =
    table.columns.indexWhere(_.name == "provenance") match
      case -1 => Map.empty
      case i  => table.rows.flatMap(_(i).str).distinct.map(t => Cells.sha256(t) -> t).toMap
