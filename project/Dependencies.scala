import sbt._

object Dependencies {

  object versions {
    val play = "2.5.10"
  }

  val playJson =
    Seq(
      "com.typesafe.play" %% "play-json" % versions.play
    )

  val typesafe =
    Seq(
      "com.typesafe" % "config" % "1.3.1"
    )

  val test =
    Seq(
      "org.scalatest" %% "scalatest" % "3.0.1" % "test"
    )

  val logging =
    Seq(
      "ch.qos.logback" % "logback-classic" % "1.1.7",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0"
    )

  val storage =
    Seq(
      "com.seancheatham" %% "storage-firebase" % "0.1.1"
    )

  val akka = {
    val version = "2.4.16"
    Seq(
      "com.typesafe.akka" %% "akka-persistence" % version,
      "com.typesafe.akka" %% "akka-persistence-tck" % version % "test"
    )
  }

}
