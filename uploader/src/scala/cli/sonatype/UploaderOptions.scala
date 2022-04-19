package scala.cli.sonatype

import caseapp._

// format: off
final case class UploaderOptions(
  endpoint: Option[String] = None,
  repositoryId: Option[String] = None,
  user: Option[String] = None,
  password: Option[String] = None,
  bundle: Option[String] = None
)
// format: on

object UploaderOptions {
  implicit lazy val parser: Parser[UploaderOptions] = Parser.derive
  implicit lazy val help: Help[UploaderOptions]     = Help.derive
}
