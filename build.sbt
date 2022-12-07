ThisBuild / scalaVersion     := "2.13.10"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "br.com.inbot"
ThisBuild / organizationName := "Inbot"
ThisBuild / versionScheme := Some("early-semver")

val scalacheckEffectVersion = "1.0.4"

DefaultOptions.addCredentials

resolvers += "Artifactory" at "https://inbot.jfrog.io/artifactory/inbot-sbt-release/"
publishTo := Some("Artifactory Realm" at "https://inbot.jfrog.io/artifactory/inbot-sbt-release")


Compile / doc / scalacOptions ++= Seq("-groups", "-implicits")

lazy val root = (project in file("."))
  .settings(
    name := "process-resource",
    crossScalaVersions := Seq("2.13.10", "2.12.17"),
    autoAPIMappings := true,
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % "3.4.0",
        "co.fs2" %% "fs2-io" % "3.4.0",
        "org.typelevel" %% "cats-effect" % "3.3.14",
        "org.typelevel" %% "cats-effect-kernel" % "3.3.14",
        "org.typelevel" %% "cats-effect-testing-specs2" % "1.4.0" % Test,
        "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test,
        "org.typelevel" %% "scalacheck-effect-munit" % scalacheckEffectVersion % Test,
        compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
    ),
  )
