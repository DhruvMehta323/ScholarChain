package scholarchain.storage

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.Try

/** A table reference of a Phase 06 load manifest. */
final case class TableRef(
    table: String,
    path: String,
    format: String,
    partitionColumns: List[String],
    rowCount: Long,
)

final case class VertexSpec(
    ref: TableRef,
    label: String,
    idColumn: String,
    windowed: Boolean,
    propertyColumns: List[String],
)

final case class EdgeSpec(
    ref: TableRef,
    label: String,
    idColumn: Option[String],
    keyColumns: List[String],
    fromColumn: String,
    toColumn: String,
    directed: Boolean,
    windowed: Boolean,
    propertyColumns: List[String],
)

final case class DynamoTableSpec(
    ref: TableRef,
    partitionKey: String,
    sortKey: List[String],
)

final case class ManifestHeader(
    target: String,
    runId: String,
    codeVersion: String,
    root: String,
)

final case class NeptuneManifest(
    header: ManifestHeader,
    vertices: List[VertexSpec],
    edges: List[EdgeSpec],
)

final case class DynamoManifest(header: ManifestHeader, tables: List[DynamoTableSpec])

/** Parses the files under `<run-root>/load-manifests/` (Phase 06 contract, manifestVersion 1).
  * Total: every problem is a `Left` with a short message that never contains file contents.
  */
object Manifests:
  val SupportedManifestVersion = 1

  def readJson(path: Path): Either[String, ujson.Value] =
    Try(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).toEither.left
      .map(_ => s"cannot read ${path.getFileName}")
      .flatMap(s => Try(ujson.read(s)).toEither.left.map(_ => s"${path.getFileName} is not JSON"))

  private def obj(
      v: ujson.Value,
      what: String,
  ): Either[String, collection.Map[String, ujson.Value]] =
    v.objOpt.map(_.value).toRight(s"$what must be a JSON object")

  private def field(
      o: collection.Map[String, ujson.Value],
      name: String,
      what: String,
  ): Either[String, ujson.Value] =
    o.get(name).toRight(s"$what: missing '$name'")

  private def str(o: collection.Map[String, ujson.Value], name: String, what: String) =
    field(o, name, what).flatMap(_.strOpt.toRight(s"$what: '$name' must be a string"))

  private def optStr(o: collection.Map[String, ujson.Value], name: String, what: String) =
    o.get(name) match
      case None | Some(ujson.Null) => Right(None)
      case Some(ujson.Str(s))      => Right(Some(s))
      case Some(_)                 => Left(s"$what: '$name' must be a string or null")

  private def bool(o: collection.Map[String, ujson.Value], name: String, what: String) =
    field(o, name, what).flatMap(_.boolOpt.toRight(s"$what: '$name' must be a boolean"))

  private def strList(o: collection.Map[String, ujson.Value], name: String, what: String) =
    field(o, name, what).flatMap(v =>
      v.arrOpt
        .toRight(s"$what: '$name' must be an array")
        .flatMap(a => sequence(a.toList.map(_.strOpt.toRight(s"$what: '$name' must hold strings"))))
    )

  private def sequence[A](xs: List[Either[String, A]]): Either[String, List[A]] =
    xs.foldRight[Either[String, List[A]]](Right(Nil))((e, acc) =>
      for x <- e; rest <- acc yield x :: rest
    )

  private def header(o: collection.Map[String, ujson.Value], expectedTarget: String) =
    for
      version <- field(o, "manifestVersion", "manifest").flatMap(
        _.numOpt.map(_.toInt).toRight("manifest: 'manifestVersion' must be a number")
      )
      _ <-
        if version == SupportedManifestVersion then Right(())
        else Left(s"manifest: unsupported manifestVersion $version")
      target <- str(o, "target", "manifest")
      _      <-
        if target == expectedTarget then Right(())
        else Left(s"manifest: expected target '$expectedTarget'")
      runId <- str(o, "runId", "manifest")
      code  <- str(o, "codeVersion", "manifest")
      root  <- str(o, "root", "manifest")
    yield ManifestHeader(target, runId, code, root)

  private def tableRef(o: collection.Map[String, ujson.Value], what: String) =
    for
      table  <- str(o, "table", what)
      path   <- str(o, "path", what)
      format <- str(o, "format", what)
      parts  <- strList(o, "partitionColumns", what)
      rows   <- field(o, "rowCount", what).flatMap(
        _.numOpt.filter(d => d.isWhole && d >= 0).map(_.toLong).toRight(s"$what: bad 'rowCount'")
      )
      _ <-
        if format == "parquet" then Right(())
        else Left(s"$what: unsupported format '$format'")
    yield TableRef(table, path, format, parts, rows)

  private def items(o: collection.Map[String, ujson.Value], name: String, what: String) =
    field(o, name, what).flatMap(
      _.arrOpt.toRight(s"$what: '$name' must be an array").map(_.toList)
    )

  def neptune(path: Path): Either[String, NeptuneManifest] =
    for
      json <- readJson(path)
      o    <- obj(json, "neptune manifest")
      h    <- header(o, "neptune")
      vs   <- items(o, "vertices", "neptune manifest").flatMap(l =>
        sequence(l.map(v => obj(v, "vertex entry").flatMap(vertex)))
      )
      es <- items(o, "edges", "neptune manifest").flatMap(l =>
        sequence(l.map(v => obj(v, "edge entry").flatMap(edge)))
      )
    yield NeptuneManifest(h, vs, es)

  private def vertex(o: collection.Map[String, ujson.Value]) =
    for
      ref      <- tableRef(o, "vertex entry")
      label    <- str(o, "label", s"vertex ${ref.table}")
      id       <- str(o, "idColumn", s"vertex ${ref.table}")
      windowed <- bool(o, "windowed", s"vertex ${ref.table}")
      props    <- strList(o, "propertyColumns", s"vertex ${ref.table}")
    yield VertexSpec(ref, label, id, windowed, props)

  private def edge(o: collection.Map[String, ujson.Value]) =
    for
      ref <- tableRef(o, "edge entry")
      w = s"edge ${ref.table}"
      label    <- str(o, "label", w)
      id       <- optStr(o, "idColumn", w)
      keys     <- strList(o, "keyColumns", w)
      from     <- str(o, "fromColumn", w)
      to       <- str(o, "toColumn", w)
      directed <- bool(o, "directed", w)
      windowed <- bool(o, "windowed", w)
      props    <- strList(o, "propertyColumns", w)
    yield EdgeSpec(ref, label, id, keys, from, to, directed, windowed, props)

  def dynamo(path: Path): Either[String, DynamoManifest] =
    for
      json <- readJson(path)
      o    <- obj(json, "dynamodb manifest")
      h    <- header(o, "dynamodb")
      ts   <- items(o, "tables", "dynamodb manifest").flatMap(l =>
        sequence(
          l.map(v =>
            obj(v, "table entry").flatMap(t =>
              for
                ref <- tableRef(t, "table entry")
                w = s"dynamodb table ${ref.table}"
                pk <- str(t, "partitionKey", w)
                sk <- strList(t, "sortKey", w)
              yield DynamoTableSpec(ref, pk, sk)
            )
          )
        )
      )
    yield DynamoManifest(h, ts)
