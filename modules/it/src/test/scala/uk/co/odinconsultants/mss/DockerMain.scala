package uk.co.odinconsultants.mss

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.{CreateContainerCmd, CreateContainerResponse}
import com.github.dockerjava.api.model.{Container, Link}
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient

import java.time.Duration
import com.github.dockerjava.core.DockerClientImpl

import java.io.Closeable

object DockerMain {

  /** Delete everything with:
    * <pre>
    * docker stop $(docker ps -a -q) ; docker rm $(docker ps -a -q)
    * </pre>
    */
  def main(args: Array[String]): Unit = {
    val config: DefaultDockerClientConfig = buildConfig("unix:///var/run/docker.sock", "1.41")
    val dockerClient: DockerClient        = DockerClientImpl.getInstance(config, buildClient(config))
    println(
      dockerClient.pingCmd().exec()
    ) // bizarrely, returns null if successful - see PingCmdExec.exec()
    import scala.jdk.CollectionConverters.*
    for {
      image <- dockerClient.listImagesCmd().exec().toArray()
    } yield println(s"Image: $image")

    val zookeeperResponse: CreateContainerResponse = startZookeeper(dockerClient)
    val kafkaResponse: CreateContainerResponse     = startKafka(dockerClient)
    log(dockerClient, kafkaResponse.getId)

    stopContainerWithId(dockerClient, zookeeperResponse.getId)
    stopContainerWithId(dockerClient, kafkaResponse.getId)
    deleteContainerWithId(dockerClient, zookeeperResponse.getId)
    deleteContainerWithId(dockerClient, kafkaResponse.getId)

    dockerClient.close()
  }

  def stopContainer(dockerClient: DockerClient, container: Container): Unit = {
    val id: String = container.getId
    stopContainerWithId(dockerClient, id)
  }

  def stopContainerWithId(dockerClient: DockerClient, id: String): Unit =
    println(s"Stopping container with ID $id")
    dockerClient.stopContainerCmd(id).exec()

  def deleteContainerWithId(dockerClient: DockerClient, id: String): Unit =
    println(s"Stopping container with ID $id")
    dockerClient.removeContainerCmd(id).exec()

  def buildClient(config: DefaultDockerClientConfig): ApacheDockerHttpClient =
    new ApacheDockerHttpClient.Builder()
      .dockerHost(config.getDockerHost)
      .sslConfig(config.getSSLConfig)
      .maxConnections(100)
      .connectionTimeout(Duration.ofSeconds(30))
      .responseTimeout(Duration.ofSeconds(45))
      .build()

  def buildConfig(
      dockerHost: String,
      apiVersion: String,
  ): DefaultDockerClientConfig = DefaultDockerClientConfig
    .createDefaultConfigBuilder()
    .withDockerHost(dockerHost)
    .withApiVersion(apiVersion)
    .build()

  private def listContainers(dockerClient: DockerClient): List[Container] =
    val containers: Array[Container] = for {
      container <- dockerClient
                     .listContainersCmd()
                     .exec()
                     .toArray()
                     .map(_.asInstanceOf[Container])
    } yield {
      println(s"id = ${container.getId}, image = ${container.getImage}")
      container
    }
    containers.toList

  private def log(dockerClient: DockerClient, id: String) = {
    import com.github.dockerjava.api.async.ResultCallback
    import com.github.dockerjava.api.model.Frame
    dockerClient
      .logContainerCmd(id)
      .withStdOut(true)
      .exec(new ResultCallback[Frame] {
        override def onError(throwable: Throwable): Unit = throwable.printStackTrace()
        override def onNext(x: Frame): Unit              = println(s"onNext: $x")
        override def onStart(closeable: Closeable): Unit = println(s"closeable = $closeable")
        override def onComplete(): Unit                  = println("Complete")
        override def close(): Unit                       = println("close")
      })
  }
  import com.github.dockerjava.api.command.CreateContainerResponse
  import com.github.dockerjava.api.model.ExposedPort
  import com.github.dockerjava.api.model.Ports

  /** From https://stackoverflow.com/questions/43135374/how-to-create-and-start-docker-container-with-specific-port-detached-mode-using
    */
  def startKafka(dockerClient: DockerClient): CreateContainerResponse = {
    val image                = "bitnami/kafka:latest"
    val tcp9092: ExposedPort = ExposedPort.tcp(9092)
    val portBindings         = new Ports
    portBindings.bind(tcp9092, Ports.Binding.bindPort(9092))

    val config: CreateContainerCmd = dockerClient
      .createContainerCmd(image)
      .withAttachStdin(false)
      .withAttachStdout(true)
      .withAttachStderr(false)
      .withEnv("KAFKA_CFG_ZOOKEEPER_CONNECT=zookeeper:2181", "ALLOW_PLAINTEXT_LISTENER=yes")
      .withCmd("/bin/bash", "-c", "/opt/bitnami/scripts/kafka/entrypoint.sh /run.sh")

    config.getHostConfig.setLinks(new Link(ZOOKEEPER_NAME, "zookeeper"))
// create container from image
    val container: CreateContainerResponse = config
      .withExposedPorts(tcp9092)
      .withHostConfig(config.getHostConfig.withPortBindings(portBindings))
      .exec

    // start the container
    dockerClient.startContainerCmd(container.getId).exec
    container
  }

  val ZOOKEEPER_NAME = "my_zookeeper3"

  def startZookeeper(dockerClient: DockerClient): CreateContainerResponse = {
    val image                = "docker.io/bitnami/zookeeper:3.8"
    val tcp2181: ExposedPort = ExposedPort.tcp(2181)
    val portBindings         = new Ports
    portBindings.bind(tcp2181, Ports.Binding.bindPort(2181))

    val config: CreateContainerCmd = dockerClient
      .createContainerCmd(image)
      .withName(ZOOKEEPER_NAME)
      .withAttachStdin(false)
      .withAttachStdout(true)
      .withAttachStderr(false)
      .withEnv("ALLOW_ANONYMOUS_LOGIN=yes")
      .withCmd("/bin/bash", "-c", "/entrypoint.sh /opt/bitnami/scripts/zookeeper/run.sh")

    val container: CreateContainerResponse = config
      .withExposedPorts(tcp2181)
      .withHostConfig(config.getHostConfig.withPortBindings(portBindings))
      .exec

    // start the container
    dockerClient.startContainerCmd(container.getId).exec
    container
  }

}
