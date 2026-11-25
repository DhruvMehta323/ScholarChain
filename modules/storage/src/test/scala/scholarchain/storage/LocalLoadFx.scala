package scholarchain.storage

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import scholarchain.core.*

/** Shared real `LocalLoad` runs for the Phase 14 Part A suites. Each run is executed once per test
  * JVM (lazy vals) over the existing Phase 06 run `modules/spark/target/runs/sparkS1r2`, which is
  * only read: variants (without `s3-vectors.json`, with a table missing) are built in a temp
  * directory from a copy of its `load-manifests` and a symlink to its table directories.
  */
object LocalLoadFx:
  val CodeVersion: String = "test+m13-2"

  private val tmp: Path =
    val d = Files.createTempDirectory("scholarchain-m13-2-")
    sys.addShutdownHook(StorageFx.deleteTree(d))
    d

  def scratch(name: String): Path = tmp.resolve(name)

  def sourceRun: Option[Path] =
    StorageFx.repoRoot
      .map(_.resolve("modules/spark/target/runs/sparkS1r2"))
      .filter(p => Files.isRegularFile(p.resolve("load-manifests/neptune.json")))

  final case class Out(result: Either[List[String], String], dir: Path)

  def runNew(root: Path, name: String): Out =
    val dir = scratch(name)
    Out(LocalLoad.run(LocalLoad.Args(root, dir, CodeVersion)), dir)

  def runBaseline(root: Path, name: String): Out =
    val dir = scratch(name)
    Out(LocalLoadBaseline.run(LocalLoadBaseline.Args(root, dir, CodeVersion)), dir)

  /** A run root that shares the table directories of `src` and has its own `load-manifests`. */
  def variantRoot(
      name: String,
      src: Path,
      dropVectors: Boolean = false,
      editVectors: ujson.Value => Unit = _ => (),
      editS3: ujson.Value => Unit = _ => (),
      dropS3: Boolean = false,
  ): Path =
    val root = scratch(name + "-root")
    val lm   = root.resolve("load-manifests")
    Files.createDirectories(lm)
    Files.createSymbolicLink(root.resolve("scholarchain-graph"), src.resolve("scholarchain-graph"))
    val s = Files.list(src.resolve("load-manifests"))
    try
      s.iterator.asScala.toList.foreach { f =>
        val n = f.getFileName.toString
        if n == "s3-vectors.json" then
          if !dropVectors then
            val j = ujson.read(Files.readAllBytes(f))
            editVectors(j)
            Files.write(
              lm.resolve(n),
              (ujson.write(j, indent = 2) + "\n").getBytes(StandardCharsets.UTF_8),
            )
        else if n == "s3.json" then
          if !dropS3 then
            val j = ujson.read(Files.readAllBytes(f))
            editS3(j)
            Files.write(
              lm.resolve(n),
              (ujson.write(j, indent = 2) + "\n").getBytes(StandardCharsets.UTF_8),
            )
        else Files.copy(f, lm.resolve(n))
      }
    finally s.close()
    root

  /** Relative path (with `/`) to bytes, for every regular file under `dir`. */
  def tree(dir: Path): Map[String, Array[Byte]] =
    val s = Files.walk(dir)
    try
      s.iterator.asScala
        .filter(Files.isRegularFile(_))
        .map(p => dir.relativize(p).toString.replace('\\', '/') -> Files.readAllBytes(p))
        .toMap
    finally s.close()

  def shaTree(dir: Path): Map[String, String] = tree(dir).view.mapValues(Cells.sha256(_)).toMap

  def text(b: Array[Byte]): String = new String(b, StandardCharsets.UTF_8)

  /** The `TableRef`s a run root declares (documents of `s3-vectors.json` and tables of `s3.json`).
    */
  def refs(root: Path): Map[String, TableRef] =
    val dir  = root.resolve("load-manifests")
    val docs = ujson.read(Files.readAllBytes(dir.resolve("s3-vectors.json")))("documents").arr
    val tabs = ujson.read(Files.readAllBytes(dir.resolve("s3.json")))("tables").arr
    (docs.toList ++ tabs.toList)
      .map { o =>
        TableRef(
          o("table").str,
          o("path").str,
          o("format").str,
          o("partitionColumns").arr.toList.map(_.str),
          o("rowCount").num.toLong,
        )
      }
      .map(r => r.table -> r)
      .toMap

  /** The four embedding tables read through `ParquetTables` (the path `LocalEmbeddingRun` uses). */
  def parquetTables(root: Path): Map[String, TableData] =
    val r     = refs(root)
    val spark = ParquetTables.newSession()
    try
      LocalLoad.EmbeddingTables.map { t =>
        t -> ParquetTables.read(spark, root, r(t)).fold(e => throw AssertionError(e), identity)
      }.toMap
    finally spark.stop()

  lazy val new1: Out = runNew(sourceRun.get, "new-1")
  lazy val new2: Out = runNew(sourceRun.get, "new-2")
  lazy val base: Out = runBaseline(sourceRun.get, "base")

  lazy val noVecRoot: Path = variantRoot("novec", sourceRun.get, dropVectors = true)
  lazy val newNoVec: Out   = runNew(noVecRoot, "new-novec")
  lazy val baseNoVec: Out  = runBaseline(noVecRoot, "base-novec")
