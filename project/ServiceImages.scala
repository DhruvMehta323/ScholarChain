/** One deployable service image built from the single `services` sbt module. */
final case class ServiceImage(name: String, mainClass: String, port: Option[Int])
