package uk.co.odinconsultants.mss

opaque type ImageName     = String
opaque type ConnectionURL = String

object ConnectionURL:
  def apply(x: String): ConnectionURL = x
object ImageName:
  def apply(x: String): ImageName = x

sealed abstract class ManagerRequest[A]

case class ConnectRequest[A](url: ConnectionURL) extends ManagerRequest[A]

case class StartRequest[A](image: ImageName) extends ManagerRequest[A]

object Domain {}
