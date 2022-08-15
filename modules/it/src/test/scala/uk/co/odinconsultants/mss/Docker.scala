package uk.co.odinconsultants.mss
import cats.effect.{ExitCode, IO, IOApp}
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientImpl}
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import cats.free.Free
import com.github.dockerjava.api.command.CreateContainerResponse
import uk.co.odinconsultants.mss.Domain.{ConnectionURL, ImageName, ConnectRequest, StartRequest}

object Docker extends IOApp.Simple {

  opaque type ApiVersion = String

  def client(dockerHost: String, apiVersion: ApiVersion): IO[DockerClient] = IO {
    val config: DefaultDockerClientConfig  = DockerMain.buildConfig(dockerHost, apiVersion)
    val httpClient: ApacheDockerHttpClient = DockerMain.buildClient(config)
    DockerClientImpl.getInstance(config, httpClient)
  }

  def run: IO[Unit] = {
    val host       = "unix:///var/run/docker.sock"
    val apiVersion = "1.41"

    val connect =
      Free.liftF(ConnectRequest[DockerClient](ConnectionURL(host)))
    val start   =
      Free.liftF(StartRequest[CreateContainerResponse](ImageName(DockerMain.ZOOKEEPER_NAME)))

    client(host, apiVersion).handleErrorWith { (t: Throwable) =>
      IO.println(s"Could not connect to host $host using API version $apiVersion") *>
        IO.raiseError(t)
    } >> IO.unit
  }
}
