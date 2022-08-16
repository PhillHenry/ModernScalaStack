package uk.co.odinconsultants.mss
import cats.effect.{ExitCode, IO, IOApp}
import cats.free.Free
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.{CreateContainerCmd, CreateContainerResponse}
import com.github.dockerjava.api.model.{ExposedPort, Ports}
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientImpl}
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import uk.co.odinconsultants.mss.DockerMain.*
import cats.arrow.FunctionK

object Docker extends IOApp.Simple {

  opaque type ApiVersion = String

  def initializeClient(dockerHost: String, apiVersion: ApiVersion): IO[DockerClient] = IO {
    val config: DefaultDockerClientConfig  = buildConfig(dockerHost, apiVersion)
    val httpClient: ApacheDockerHttpClient = buildClient(config)
    DockerClientImpl.getInstance(config, httpClient)
  }

  def stopContainer(client: DockerClient, containerId: String): IO[Unit] = IO {
    stopContainerWithId(client, containerId)
  }

  val host       = "unix:///var/run/docker.sock"
  val apiVersion = "1.41"

  def run: IO[Unit] =
//    buildFree.foldMap(interpret())
    initializeClient(host, apiVersion).handleErrorWith { (t: Throwable) =>
      IO.println(s"Could not connect to host $host using API version $apiVersion") *>
        IO.raiseError(t)
    } >> IO.unit

  def interpret(client: DockerClient, tree: Free[ManagerRequest, Unit]) =
    val dockerInterpreter                          = interpreter(client)
    val requestToIO: FunctionK[ManagerRequest, IO] = new FunctionK[ManagerRequest, IO] {
      def apply[A](l: ManagerRequest[A]): IO[A] = l match {
        case StartRequest(image, cmd, env) => start(client, image, cmd, env)
        case StopRequest(containerId)      => IO(stopContainerWithId(client, containerId.toString))
      }
    }
    tree.foldMap(requestToIO)

  def buildFree: Free[ManagerRequest, Unit] = for {
    container <- Free.liftF(
                   StartRequest(
                     ImageName(DockerMain.ZOOKEEPER_NAME),
                     Command("/bin/bash -c /entrypoint.sh /opt/bitnami/scripts/zookeeper/run.sh"),
                     List("ALLOW_ANONYMOUS_LOGIN=yes"),
                   )
                 )
    stop      <- Free.liftF(StopRequest(container))
  } yield {}

  def interpreter[A](client: DockerClient): ManagerRequest[A] => IO[A] = _ match {
    case StartRequest(image, cmd, env) => start(client, image, cmd, env)
    case StopRequest(containerId)      => IO(stopContainerWithId(client, containerId.toString))
  }

  def start(
      dockerClient: DockerClient,
      image: ImageName,
      command: Command,
      environment: Environment,
  ): IO[ContainerId] = IO {
    import scala.jdk.CollectionConverters.*
    val tcp2181: ExposedPort = ExposedPort.tcp(2181)
    val portBindings         = new Ports
    portBindings.bind(tcp2181, Ports.Binding.bindPort(2181))

    val config: CreateContainerCmd = dockerClient
      .createContainerCmd(image.toString)
      .withAttachStdin(false)
      .withAttachStdout(true)
      .withAttachStderr(false)
      .withEnv(environment.asJava)
      .withCmd(command.toString.split(" ").toList.asJava)

    val container: CreateContainerResponse = config
      .withExposedPorts(tcp2181)
      .withHostConfig(config.getHostConfig.withPortBindings(portBindings))
      .exec

    // start the container
    dockerClient.startContainerCmd(container.getId).exec
    ContainerId(container.getId)
  }
}
