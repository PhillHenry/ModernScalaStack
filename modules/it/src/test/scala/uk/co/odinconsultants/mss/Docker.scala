package uk.co.odinconsultants.mss
import cats.effect.{ExitCode, IO, IOApp}
import cats.free.Free
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.{CreateContainerCmd, CreateContainerResponse}
import com.github.dockerjava.api.model.{ExposedPort, Link, Ports}
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

  val startZookeeper: StartRequest = StartRequest(
    ImageName("docker.io/bitnami/zookeeper:3.8"),
    Command("/bin/bash -c /entrypoint.sh /opt/bitnami/scripts/zookeeper/run.sh"),
    List("ALLOW_ANONYMOUS_LOGIN=yes"),
    List(2181 -> 2181),
    List.empty,
  )

  def buildFree: Free[ManagerRequest, Unit] = for {
    zookeeper <- Free.liftF(startZookeeper)
    names     <- Free.liftF(NamesRequest(zookeeper))
    kafka     <- Free.liftF(
                   StartRequest(
                     ImageName("bitnami/kafka:latest"),
                     Command("/bin/bash -c /opt/bitnami/scripts/kafka/entrypoint.sh /run.sh"),
                     List("KAFKA_CFG_ZOOKEEPER_CONNECT=zookeeper:2181", "ALLOW_PLAINTEXT_LISTENER=yes"),
                     List(9092 -> 9092),
                     names.map(_ -> "zookeeper"),
                   )
                 )
    _         <- Free.liftF(StopRequest(zookeeper))
    _         <- Free.liftF(StopRequest(kafka))
  } yield {}

  def interpreter[A](client: DockerClient): ManagerRequest[A] => IO[A] = {
    case StartRequest(image, cmd, env, ports, dns) =>
      start(client, image, cmd, env, ports, dns)
    case StopRequest(containerId)                  => IO(stopContainerWithId(client, containerId.toString))
    case NamesRequest(containerId)                 =>
      IO(listContainers(client).filter(_.getId == containerId.toString).flatMap(_.getNames))
  }

  def start(
      dockerClient: DockerClient,
      image: ImageName,
      command: Command,
      environment: Environment,
      portMappings: NetworkMapping[Port],
      dnsMappings: DnsMapping[String],
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
    val exposedPorts: List[ExposedPort]    = for {
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
    val links: Seq[Link]                   = dnsMappings.map { case (name, alias) => new Link(name, alias) }
    config.getHostConfig.setLinks(links.toList*)
    // start the container
    dockerClient.startContainerCmd(container.getId).exec
    ContainerId(container.getId)
  }
}
