// Plugin versions come from the single ledger project/Versions.scala.
// plugins.sbt cannot see the sources of project/ (same meta-build level), so the ledger
// is read textually. A missing constant fails the build loudly.
def ledger(name: String): String = {
  val src  = scala.io.Source.fromFile(new java.io.File("project/Versions.scala"), "UTF-8")
  val text =
    try src.mkString
    finally src.close()
  val pattern = ("""val\s+""" + name + """\s*=\s*"([^"]+)"""").r
  pattern
    .findFirstMatchIn(text)
    .map(_.group(1))
    .getOrElse(
      sys.error(s"project/Versions.scala has no constant '$name'")
    )
}

addSbtPlugin("org.scalameta"  % "sbt-scalafmt"        % ledger("sbtScalafmt"))
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % ledger("sbtNativePackager"))
addSbtPlugin("com.eed3si9n"   % "sbt-assembly"        % ledger("sbtAssembly"))
