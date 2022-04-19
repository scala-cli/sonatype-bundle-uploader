package scala.cli.sonatype

import caseapp.core.RemainingArgs
import caseapp.core.app.CaseApp
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.impl.client.BasicCredentialsProvider
import org.sonatype.spice.zapper

import java.io.File

object Uploader extends CaseApp[UploaderOptions] {
  def run(options: UploaderOptions, args: RemainingArgs): Unit = {

    // originally adapted from https://github.com/xerial/sbt-sonatype/blob/6a4245f74904a4dfddb4c5f7b67ab86a39088e74/src/main/scala/xerial/sbt/sonatype/SonatypeClient.scala#L270-L293

    val parameters = zapper.ParametersBuilder.defaults().build()
    val endpoint = options.endpoint.filter(_.trim.nonEmpty).getOrElse {
      val repoId = options.repositoryId.filter(_.trim.nonEmpty).getOrElse {
        sys.error("Error: either --endpoint or --repository-id need to be specified.")
      }
      s"https://s01.oss.sonatype.org/service/local/staging/deployByRepositoryId/$repoId/"
    }
    val clientBuilder = new zapper.client.hc4.Hc4ClientBuilder(parameters, endpoint)

    for (password <- options.password) {
      val user                        = options.user.getOrElse("")
      val credentialProvider          = new BasicCredentialsProvider
      val usernamePasswordCredentials = new UsernamePasswordCredentials(user, password)
      credentialProvider.setCredentials(AuthScope.ANY, usernamePasswordCredentials)
      clientBuilder.withPreemptiveRealm(credentialProvider)
    }

    val bundlePath = options.bundle.filter(_.trim.nonEmpty).map(new File(_)).getOrElse {
      sys.error("Error: --bundle is required.")
    }
    val source = new zapper.fs.DirectoryIOSource(bundlePath)

    val client = clientBuilder.build()

    try {
      System.err.println(s"Uploading $bundlePath to $endpoint")
      client.upload(source)
      System.err.println(s"Finished uploading $bundlePath")
    }
    finally
      client.close()
  }
}
