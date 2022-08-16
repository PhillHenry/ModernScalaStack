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
  opaque type Port       = Int

  def initializeClient(dockerHost: String, apiVersion: ApiVersion): IO[DockerClient] = IO {
    val config: DefaultDockerClientConfig  = buildConfig(dockerHost, apiVersion)
    val httpClient: ApacheDockerHttpClient = buildClient(config)
    DockerClientImpl.getInstance(config, httpClient)
  }

  def stopContainer(client: DockerClient, containerId: String): IO[Unit] = IO {
    stopContainerWithId(client, containerId)
  }

  val client: IO[DockerClient] = {
    val host       = "unix:///var/run/docker.sock"
    val apiVersion = "1.41"
    initializeClient(host, apiVersion).handleErrorWith { (t: Throwable) =>
      IO.println(s"Could not connect to host $host using API version $apiVersion") *>
        IO.raiseError(t)
    }
  }

  def run: IO[Unit] =
    for {
      client <- client
      _      <- interpret(client, buildFree)
    } yield println("Started and stopped")

  def interpret(client: DockerClient, tree: Free[ManagerRequest, Unit]): IO[Unit] =
    val requestToIO: FunctionK[ManagerRequest, IO] = new FunctionK[ManagerRequest, IO] {
      def apply[A](l: ManagerRequest[A]): IO[A] = interpreter[A](client)(l)
    }
    tree.foldMap(requestToIO)

  val freeZookeeper: Free[ManagerRequest, ContainerId] = Free.liftF(
    StartRequest[Port](
      ImageName("docker.io/bitnami/zookeeper:3.8"),
      Command("/bin/bash -c /entrypoint.sh /opt/bitnami/scripts/zookeeper/run.sh"),
      List("ALLOW_ANONYMOUS_LOGIN=yes"),
      List(2181 -> 2181),
    )
  )

  def buildFree: Free[ManagerRequest, Unit] = for {
    zookeeper <- freeZookeeper
    stop      <- Free.liftF(StopRequest(zookeeper))
  } yield {}

  def interpreter[A](client: DockerClient): ManagerRequest[A] => IO[A] = {
    case StartRequest(image, cmd, env, maps) =>
      start(client, image, cmd, env, maps.asInstanceOf[Mapping[Port]])
    case StopRequest(containerId)            => IO(stopContainerWithId(client, containerId.toString))
  }

  def start(
      dockerClient: DockerClient,
      image: ImageName,
      command: Command,
      environment: Environment,
      portMappings: Mapping[Port],
  ): IO[ContainerId] = IO {
    import scala.jdk.CollectionConverters.*

    val config: CreateContainerCmd = dockerClient
      .createContainerCmd(image.toString)
      .withAttachStdin(false)
      .withAttachStdout(true)
      .withAttachStderr(false)
      .withEnv(environment.asJava)
      .withCmd(command.toString.split(" ").toList.asJava)

    val portBindings                       = new Ports
    val exposedPorts                       = for {
      (container, host) <- portMappings
    } yield {
      val exposed: ExposedPort = ExposedPort.tcp(container)
      portBindings.bind(exposed, Ports.Binding.bindPort(host))
      exposed
    }
    val container: CreateContainerResponse = config
      .withExposedPorts(exposedPorts.asJava)
      .withHostConfig(config.getHostConfig.withPortBindings(portBindings))
      .exec

    // start the container
    dockerClient.startContainerCmd(container.getId).exec
    ContainerId(container.getId)
  }
}
