lazy val commonSettings =
  Seq(
    organization := "com.seancheatham",
    scalaVersion := "2.11.8",
    libraryDependencies ++=
      Dependencies.typesafe ++
        Dependencies.test ++
        Dependencies.logging
  ) ++ Publish.settings

lazy val firebasePersistence =
  project
    .in(file("."))
    .settings(commonSettings: _*)
    .settings(
      name := "firebase-persistence",
      libraryDependencies ++=
        Dependencies.akka ++
        Dependencies.playJson ++
        Dependencies.storage,
      parallelExecution in Test := false
    )