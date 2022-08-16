package uk.co.odinconsultants.mss

opaque type ImageName     = String
opaque type ConnectionURL = String
opaque type ContainerId   = String
opaque type Command       = String
type Environment          = List[String]

object ConnectionURL:
  def apply(x: String): ConnectionURL = x
object ImageName:
  def apply(x: String): ImageName = x
object ContainerId:
  def apply(x: String): ContainerId = x
object Command:
  def apply(x: String): Command = x

sealed abstract class ManagerRequest[A]

case class StartRequest[A](image: ImageName, command: Command, env: Environment)
    extends ManagerRequest[A]

case class StopRequest[A](image: ContainerId) extends ManagerRequest[A]

object Domain {}
