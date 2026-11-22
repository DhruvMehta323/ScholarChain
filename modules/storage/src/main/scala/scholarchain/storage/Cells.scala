package scholarchain.storage

import java.nio.charset.StandardCharsets

import scholarchain.core.Hashing

/** A typed table cell, independent of Spark. The local loader converts Parquet rows into cells
  * once; everything else (CSV, item files, invariants) works on cells, so it is testable without a
  * Spark session.
  */
enum Cell:
  case Null
  case S(value: String)
  case I(value: Int)
  case L(value: Long)
  case D(value: Double)
  case B(value: Boolean)
  case Arr(values: List[Cell])

  /** Fields in ascending name order. */
  case Obj(fields: List[(String, Cell)])

  /** Stable text of the cell, used for sorting and hashing (never shown to users). */
  def render: String = this match
    case Null       => "~"
    case S(v)       => s"s:$v"
    case I(v)       => s"i:$v"
    case L(v)       => s"l:$v"
    case D(v)       => s"d:$v"
    case B(v)       => s"b:$v"
    case Arr(vs)    => vs.map(_.render).mkString("[", "\u0001", "]")
    case Obj(items) => items.map((k, v) => s"$k=${v.render}").mkString("{", "\u0001", "}")

  def isNull: Boolean = this == Null

  def str: Option[String] = this match
    case S(v) => Some(v)
    case _    => None

  /** Plain JSON form: arrays and objects nested, doubles as numbers. */
  def toJson: ujson.Value = this match
    case Null       => ujson.Null
    case S(v)       => ujson.Str(v)
    case I(v)       => ujson.Num(v.toDouble)
    case L(v)       => if v.abs < (1L << 53) then ujson.Num(v.toDouble) else ujson.Str(v.toString)
    case D(v)       => if v.isNaN || v.isInfinity then ujson.Str(v.toString) else ujson.Num(v)
    case B(v)       => ujson.Bool(v)
    case Arr(vs)    => ujson.Arr.from(vs.map(_.toJson))
    case Obj(items) => ujson.Obj.from(items.map((k, v) => k -> v.toJson))

/** One column: its name and the Spark catalog type string (for example `array<string>`). */
final case class Column(name: String, sparkType: String)

/** A fully read table: columns in declared order, rows in a deterministic order. */
final case class TableData(name: String, columns: Vector[Column], rows: Vector[Vector[Cell]]):
  private val index: Map[String, Int] = columns.map(_.name).zipWithIndex.toMap

  def has(column: String): Boolean = index.contains(column)

  def col(column: String): Either[String, Int] =
    index.get(column).toRight(s"table '$name' has no column '$column'")

  def typeOf(column: String): Option[String] = columns.find(_.name == column).map(_.sparkType)

  /** String value of a column in a row; `Left` when the column is missing or the cell is not a
    * string.
    */
  def string(row: Vector[Cell], column: String): Either[String, String] =
    col(column).flatMap(i =>
      row(i) match
        case Cell.S(v) => Right(v)
        case _         => Left(s"table '$name' column '$column' is not a non-null string")
    )

  def cell(row: Vector[Cell], column: String): Either[String, Cell] = col(column).map(row(_))

object Cells:
  /** Total order for deterministic output: compares the rendered cells column by column. */
  def rowKey(row: Vector[Cell]): String = row.map(_.render).mkString("\u0002")

  def sha256(bytes: Array[Byte]): String = Hashing.sha256Hex(bytes)

  def sha256(text: String): String = Hashing.sha256Hex(text.getBytes(StandardCharsets.UTF_8))
