import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.1.4`
import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.19`
import $ivy.`io.github.alexarchambault.mill::mill-native-image-upload:0.1.19`

import de.tobiasroeser.mill.vcs.version._
import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill._
import mill.scalalib._

import scala.concurrent.duration.Duration

def graalVmVersion = "22.0.0"
def graalVmJvmId = s"graalvm-java17:$graalVmVersion"

trait UploaderNativeImage extends ScalaModule with NativeImage {
  def scalaVersion = "2.13.8"

  def mainClass = Some("scala.cli.sonatype.Uploader")

  def nativeImageClassPath = T {
    runClasspath()
  }
  def nativeImageOptions = T {
    super.nativeImageOptions() ++ Seq(
      "--no-fallback",
      "--enable-url-protocols=https"
    )
  }
  def nativeImagePersist      = System.getenv("CI") != null
  def nativeImageGraalVmJvmId = graalVmJvmId
  def nativeImageName         = "sonatype-bundle-upload"
  def ivyDeps = super.ivyDeps() ++ Seq(
    ivy"com.github.alexarchambault:case-app_2.13:2.1.0-M13",
    ivy"org.sonatype.spice.zapper:spice-zapper:1.3",
    ivy"org.slf4j:slf4j-nop:2.0.0-alpha7"
  )
  def nativeImageMainClass = mainClass().getOrElse {
    sys.error("No main class")
  }

  def nameSuffix = ""
  def copyToArtifacts(directory: String = "artifacts/") = T.command {
    val _ = Upload.copyLauncher(
      nativeImage().path,
      directory,
      "sonatype-bundle-upload",
      compress = true,
      suffix = nameSuffix
    )
  }
}

object uploader extends UploaderNativeImage

def csDockerVersion = "2.1.0-M5-18-gfebf9838c"

object `uploader-static` extends UploaderNativeImage {
  def moduleDeps = Seq(uploader)
  def nameSuffix = "-static"
  def buildHelperImage = T {
    os.proc("docker", "build", "-t", "scala-cli-base-musl:latest", ".")
      .call(cwd = os.pwd / "musl-image", stdout = os.Inherit)
    ()
  }
  def nativeImageDockerParams = T {
    buildHelperImage()
    Some(
      NativeImage.linuxStaticParams(
        "scala-cli-base-musl:latest",
        s"https://github.com/coursier/coursier/releases/download/v$csDockerVersion/cs-x86_64-pc-linux.gz"
      )
    )
  }
  def writeNativeImageScript(scriptDest: String, imageDest: String = "") = T.command {
    buildHelperImage()
    super.writeNativeImageScript(scriptDest, imageDest)()
  }
}

object `uploader-mostly-static` extends UploaderNativeImage {
  def moduleDeps = Seq(uploader)
  def nameSuffix = "-mostly-static"
  def nativeImageDockerParams = Some(
    NativeImage.linuxMostlyStaticParams(
      "ubuntu:18.04", // TODO Pin that?
      s"https://github.com/coursier/coursier/releases/download/v$csDockerVersion/cs-x86_64-pc-linux.gz"
    )
  )
}

def publishVersion = T {
  val state = VcsVersion.vcsState()
  if (state.commitsSinceLastTag > 0) {
    val versionOrEmpty = state.lastTag
      .filter(_ != "nightly")
      .map(_.stripPrefix("v"))
      .flatMap { tag =>
        val idx = tag.lastIndexOf(".")
        if (idx >= 0) Some(tag.take(idx + 1) + (tag.drop(idx + 1).toInt + 1).toString + "-SNAPSHOT")
        else None
      }
      .getOrElse("0.0.1-SNAPSHOT")
    Some(versionOrEmpty)
      .filter(_.nonEmpty)
      .getOrElse(state.format())
  }
  else
    state
      .lastTag
      .getOrElse(state.format())
      .stripPrefix("v")
}

def upload(directory: String = "artifacts/") = T.command {
  val version = publishVersion()

  val path = os.Path(directory, os.pwd)
  val launchers = os.list(path).filter(os.isFile(_)).map { path =>
    path.toNIO -> path.last
  }
  val ghToken = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
    sys.error("UPLOAD_GH_TOKEN not set")
  }
  val (tag, overwriteAssets) =
    if (version.endsWith("-SNAPSHOT")) ("nightly", true)
    else ("v" + version, false)

  Upload.upload(
    "scala-cli",
    "sonatype-bundle-uploader",
    ghToken,
    tag,
    dryRun = false,
    overwrite = overwriteAssets
  )(launchers: _*)
}

def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]) =
  T.command {
    import scala.concurrent.duration.DurationInt
    val timeout     = 10.minutes
    val credentials = sys.env("SONATYPE_USERNAME") + ":" + sys.env("SONATYPE_PASSWORD")
    val pgpPassword = sys.env("PGP_PASSWORD")
    val data        = T.sequence(tasks.value)()

    doPublishSonatype(
      credentials = credentials,
      pgpPassword = pgpPassword,
      data = data,
      timeout = timeout,
      log = T.ctx().log
    )
  }

def doPublishSonatype(
  credentials: String,
  pgpPassword: String,
  data: Seq[PublishModule.PublishData],
  timeout: Duration,
  log: mill.api.Logger
): Unit = {

  val artifacts = data.map {
    case PublishModule.PublishData(a, s) =>
      (s.map { case (p, f) => (p.path, f) }, a)
  }

  val isRelease = {
    val versions = artifacts.map(_._2.version).toSet
    val set      = versions.map(!_.endsWith("-SNAPSHOT"))
    assert(
      set.size == 1,
      s"Found both snapshot and non-snapshot versions: ${versions.toVector.sorted.mkString(", ")}"
    )
    set.head
  }
  val publisher = new publish.SonatypePublisher(
    uri = "https://s01.oss.sonatype.org/service/local",
    snapshotUri = "https://s01.oss.sonatype.org/content/repositories/snapshots",
    credentials = credentials,
    signed = true,
    gpgArgs = Seq(
      "--detach-sign",
      "--batch=true",
      "--yes",
      "--pinentry-mode",
      "loopback",
      "--passphrase",
      pgpPassword,
      "--armor",
      "--use-agent"
    ),
    readTimeout = timeout.toMillis.toInt,
    connectTimeout = timeout.toMillis.toInt,
    log = log,
    awaitTimeout = timeout.toMillis.toInt,
    stagingRelease = isRelease
  )

  publisher.publishAll(isRelease, artifacts: _*)
}

object ci extends Module {
  def copyJvm(jvm: String = graalVmJvmId, dest: String = "jvm") = T.command {
    import sys.process._
    val command = Seq(
      "cs",
      "java-home",
      "--jvm",
      jvm,
      "--update",
      "--ttl",
      "0"
    )
    val baseJavaHome = os.Path(command.!!.trim, os.pwd)
    System.err.println(s"Initial Java home $baseJavaHome")
    val destJavaHome = os.Path(dest, os.pwd)
    os.copy(baseJavaHome, destJavaHome, createFolders = true)
    System.err.println(s"New Java home $destJavaHome")
    destJavaHome
  }

}
