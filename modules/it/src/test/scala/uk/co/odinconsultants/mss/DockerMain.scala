package uk.co.odinconsultants.mss

import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import java.time.Duration
import com.github.dockerjava.core.DockerClientImpl

object DockerMain {
  def main(args: Array[String]): Unit = {
    val config     = DefaultDockerClientConfig
      .createDefaultConfigBuilder()
      .withDockerHost("unix:///var/run/docker.sock")
      .build()
    val httpClient = new ApacheDockerHttpClient.Builder()
      .dockerHost(config.getDockerHost())
      .sslConfig(config.getSSLConfig())
      .maxConnections(100)
      .connectionTimeout(Duration.ofSeconds(30))
      .responseTimeout(Duration.ofSeconds(45))
      .build()

    val dockerClient = DockerClientImpl.getInstance(config, httpClient)
    println(dockerClient.pingCmd().exec())
  }
}
