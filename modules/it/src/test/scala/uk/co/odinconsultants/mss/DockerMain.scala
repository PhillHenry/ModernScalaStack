package uk.co.odinconsultants.mss

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.CreateContainerCmd
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient

import java.time.Duration
import com.github.dockerjava.core.DockerClientImpl

import java.io.Closeable

object DockerMain {
  def main(args: Array[String]): Unit = {
    val config     = DefaultDockerClientConfig
      .createDefaultConfigBuilder()
      .withDockerHost("unix:///var/run/docker.sock")
      .withApiVersion("1.41")
      .build()
    val httpClient = new ApacheDockerHttpClient.Builder()
      .dockerHost(config.getDockerHost())
      .sslConfig(config.getSSLConfig())
      .maxConnections(100)
      .connectionTimeout(Duration.ofSeconds(30))
      .responseTimeout(Duration.ofSeconds(45))
      .build()

    val dockerClient = DockerClientImpl.getInstance(config, httpClient)
    println(
      dockerClient.pingCmd().exec()
    ) // bizarrely, returns null if successful - see PingCmdExec.exec()
    import scala.jdk.CollectionConverters.*
    for {
      image <- dockerClient.listImagesCmd().exec().toArray()
    } yield println(s"Image: $image")

    val pulsarResponse = startContainer(dockerClient, "apachepulsar/pulsar:2.10.0")
    val id: String     = pulsarResponse.getId

    log(dockerClient, id)

    listContainers(dockerClient)

    dockerClient.close()
  }

  private def listContainers(dockerClient: DockerClient) =
    for {
      container <- dockerClient.listContainersCmd().exec().toArray()
    } yield println(s"Containers: ${container}")

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
  def startContainer(dockerClient: DockerClient, image: String): CreateContainerResponse = {

    val tcp4444: ExposedPort = ExposedPort.tcp(4444)
    val portBindings         = new Ports
    portBindings.bind(tcp4444, Ports.Binding.bindPort(4444))

// create container from image
    val container: CreateContainerResponse = dockerClient
      .createContainerCmd(image)
      .withAttachStdin(false)
      .withAttachStdout(true)
      .withAttachStderr(false)
//      .withTty(true)
      .withCmd("/bin/bash", "-c", "/pulsar/bin/pulsar standalone")
//      .withExposedPorts(tcp4444)
//      .withHostConfig(newHostConfig.withPortBindings(portBindings))
//      .withName("selenium-hub")
      .exec

    // start the container
    dockerClient.startContainerCmd(container.getId).exec
    container
  }

}
