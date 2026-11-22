package scholarchain.storage

import java.nio.file.{Files, Path}

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.*

import scala.util.control.NonFatal

/** Reads Phase 06 Parquet tables as FILES. Uses the Spark SQL library (Provided) and no
  * `modules/spark` code. Only the local loader (a `Test`-classpath main) touches this object; the
  * service runtime never does.
  *
  * The read schema is explicit: it is the `schema` recorded in the table's `_provenance.json`
  * (Spark catalog string), not an inferred one, and the observed row count must equal both the
  * manifest `rowCount` and the provenance `rowCount`.
  */
object ParquetTables:
  def newSession(): SparkSession =
    SparkSession
      .builder()
      .master("local[2]")
      .appName("scholarchain-storage-loader")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "2")
      .config("spark.sql.session.timeZone", "UTC")
      .config("spark.sql.sources.partitionColumnTypeInference.enabled", "false")
      .getOrCreate()

  def tableDir(runRoot: Path, ref: TableRef): Path = runRoot.resolve(ref.path)

  def provenancePath(runRoot: Path, ref: TableRef): Path =
    tableDir(runRoot, ref).resolve("_provenance.json")

  /** Declared schema and row count from `_provenance.json`. */
  def declared(runRoot: Path, ref: TableRef): Either[String, (StructType, Long)] =
    for
      json <- Manifests.readJson(provenancePath(runRoot, ref))
      o    <- json.objOpt.map(_.value).toRight(s"${ref.table}: _provenance.json is not an object")
      s    <- o
        .get("schema")
        .flatMap(_.strOpt)
        .toRight(s"${ref.table}: _provenance.json has no schema")
      n <- o
        .get("rowCount")
        .flatMap(_.numOpt)
        .map(_.toLong)
        .toRight(s"${ref.table}: _provenance.json has no rowCount")
      st <- scala.util
        .Try(DataType.fromDDL(s))
        .toEither
        .left
        .map(_ => s"${ref.table}: unreadable schema in _provenance.json")
        .flatMap {
          case t: StructType => Right(t)
          case _             => Left(s"${ref.table}: schema in _provenance.json is not a struct")
        }
    yield (st, n)

  def read(spark: SparkSession, runRoot: Path, ref: TableRef): Either[String, TableData] =
    declared(runRoot, ref).flatMap { (schema, provRows) =>
      if provRows != ref.rowCount then
        Left(s"${ref.table}: manifest rowCount ${ref.rowCount} != provenance rowCount $provRows")
      else
        try
          val dir = tableDir(runRoot, ref)
          if !Files.isDirectory(dir) then Left(s"${ref.table}: table directory is missing")
          else
            val rows = spark.read
              .schema(schema)
              .parquet(dir.toString)
              .select(schema.fieldNames.toSeq.map(org.apache.spark.sql.functions.col)*)
              .collect()
              .toVector
            if rows.size.toLong != ref.rowCount then
              Left(s"${ref.table}: read ${rows.size} rows, manifest says ${ref.rowCount}")
            else
              val cols  = schema.fields.toVector.map(f => Column(f.name, f.dataType.catalogString))
              val cells = rows.map(r =>
                schema.fields.toVector.zipWithIndex.map((f, i) => toCell(r.get(i), f.dataType))
              )
              Right(TableData(ref.table, cols, cells.sortBy(Cells.rowKey)))
        catch
          case NonFatal(e) =>
            Left(
              s"${ref.table}: Parquet read failed (${e.getClass.getSimpleName}: ${Option(e.getMessage).getOrElse("").take(300)})"
            )
    }

  private[storage] def toCell(v: Any, dt: DataType): Cell =
    if v == null then Cell.Null
    else
      (dt, v) match
        case (StringType, s)                                => Cell.S(s.toString)
        case (IntegerType, i: Int)                          => Cell.I(i)
        case (LongType, l: Long)                            => Cell.L(l)
        case (DoubleType, d: Double)                        => Cell.D(d)
        case (BooleanType, b: Boolean)                      => Cell.B(b)
        case (ArrayType(et, _), s: scala.collection.Seq[?]) => Cell.Arr(s.toList.map(toCell(_, et)))
        case (MapType(_, vt, _), m: scala.collection.Map[?, ?]) =>
          Cell.Obj(m.toList.map((k, x) => k.toString -> toCell(x, vt)).sortBy(_._1))
        case (st: StructType, r: Row) =>
          Cell.Obj(
            st.fields.toList.zipWithIndex
              .map((f, i) => f.name -> toCell(r.get(i), f.dataType))
              .sortBy(_._1)
          )
        case _ =>
          throw new IllegalStateException(
            s"unsupported cell type ${dt.catalogString} for ${v.getClass.getName}"
          )
