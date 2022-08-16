package uk.co.odinconsultants.mss
import cats.effect.{ExitCode, IO, IOApp}
import cats.free.Free
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.{CreateContainerCmd, CreateContainerResponse}
import com.github.dockerjava.api.model.{ExposedPort, Ports}
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientImpl}
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import uk.co.odinconsultants.mss.DockerMain.*

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

  def run: IO[Unit] = {

    val connect =
      Free.liftF(ConnectRequest[DockerClient](ConnectionURL(host)))
    val start   =
      Free.liftF(
        StartRequest[CreateContainerResponse](
          ImageName(DockerMain.ZOOKEEPER_NAME),
          Command("/bin/bash -c /entrypoint.sh /opt/bitnami/scripts/zookeeper/run.sh"),
          List("ALLOW_ANONYMOUS_LOGIN=yes"),
        )
      )

    initializeClient(host, apiVersion).handleErrorWith { (t: Throwable) =>
      IO.println(s"Could not connect to host $host using API version $apiVersion") *>
        IO.raiseError(t)
    } >> IO.unit
  }

  def interpret(client: DockerClient): ManagerRequest[?] => IO[?] = _ match {
    case ConnectRequest(url)           => initializeClient(url.toString, apiVersion)
    case StartRequest(image, cmd, env) => start(client, image, cmd, env)
    case StopRequest(containerId)      => IO(stopContainerWithId(client, containerId.toString))
  }

  def start(
      dockerClient: DockerClient,
      image: ImageName,
      command: Command,
      environment: Environment,
  ): IO[CreateContainerResponse] = IO {
    import scala.jdk.CollectionConverters.*
    val tcp2181: ExposedPort = ExposedPort.tcp(2181)
    val portBindings         = new Ports
    portBindings.bind(tcp2181, Ports.Binding.bindPort(2181))

    val config: CreateContainerCmd = dockerClient
      .createContainerCmd(image.toString)
      .withName(ZOOKEEPER_NAME)
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
    container
  }
}
