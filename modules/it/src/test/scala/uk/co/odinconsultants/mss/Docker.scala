package uk.co.odinconsultants.mss
import cats.effect.{ExitCode, IO, IOApp}
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientImpl}
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient

object Docker extends IOApp.Simple {

  opaque type DockerHost = String
  opaque type ApiVersion = String

  def client(dockerHost: DockerHost, apiVersion: ApiVersion): IO[DockerClient] = IO {
    val config: DefaultDockerClientConfig  = DockerMain.buildConfig(dockerHost, apiVersion)
    val httpClient: ApacheDockerHttpClient = DockerMain.buildClient(config)
    DockerClientImpl.getInstance(config, httpClient)
  }

  def run: IO[Unit] = client("unix:///var/run/docker.sock", "1.41") >> IO.unit
}
