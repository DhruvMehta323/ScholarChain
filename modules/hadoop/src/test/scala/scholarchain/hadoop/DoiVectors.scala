package scholarchain.hadoop

import java.nio.file.Files

/** Reader for the shared DOI vector file. The hadoop Test classpath does NOT include core's test
  * resources (build.sbt has `dependsOn(core)` without `test->test`), so the file is read by its
  * repository-relative path `modules/core/src/test/resources/doi-normalization-vectors.tsv`. Escape
  * rules mirror core's DoiVectorsContractSuite: `\\`, `\uXXXX`, `\UXXXXXXXX`, `\e`.
  */
object DoiVectors:
  final case class Row(
      line: Int,
      rawInput: String,
      rawExpected: String,
      input: String,
      expected: Option[String],
  )

  val Path = "modules/core/src/test/resources/doi-normalization-vectors.tsv"

  def unescape(cell: String): String =
    if cell == "\\e" then ""
    else
      val sb = new StringBuilder
      var i  = 0
      while i < cell.length do
        val c = cell.charAt(i)
        if c != '\\' then
          sb.append(c)
          i += 1
        else
          cell.charAt(i + 1) match
            case '\\' =>
              sb.append('\\')
              i += 2
            case 'u' =>
              sb.append(Integer.parseInt(cell.substring(i + 2, i + 6), 16).toChar)
              i += 6
            case 'U' =>
              sb.appendAll(Character.toChars(Integer.parseInt(cell.substring(i + 2, i + 10), 16)))
              i += 10
            case o => throw new IllegalStateException(s"unknown escape \\$o")
      sb.toString

  def load(): List[Row] =
    val text = new String(Files.readAllBytes(UnitSupport.repoRoot.resolve(Path)), "UTF-8")
    text.split("\n", -1).toList.zipWithIndex.flatMap { (line, idx) =>
      if line.isEmpty || line.startsWith("#") then None
      else
        val cells = line.split("\t", -1)
        if cells(0) == "input" && cells(1) == "expected" then None
        else
          Some(
            Row(
              idx + 1,
              cells(0),
              cells(1),
              unescape(cells(0)),
              if cells(1) == "REJECT" then None else Some(unescape(cells(1))),
            )
          )
    }
