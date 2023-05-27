ThisBuild / scalaVersion     := "2.13.10"
ThisBuild / version          := "0.1.3"
ThisBuild / organization     := "br.com.inbot"
ThisBuild / organizationName := "Inbot"
ThisBuild / versionScheme := Some("early-semver")

val scalacheckEffectVersion = "1.0.4"

DefaultOptions.addCredentials

resolvers += "Artifactory" at "https://inbot.jfrog.io/artifactory/inbot-sbt-release/"
publishTo := Some("Artifactory Realm" at "https://inbot.jfrog.io/artifactory/inbot-sbt-release")


Compile / doc / scalacOptions ++= Seq("-groups", "-implicits")

Compile / doc / scalacOptions ++= { CrossVersion.partialVersion(scalaVersion.value) match {
            case Some((2, n)) => Nil
            case _ => List("-source:future", "-rewrite", "-source", "3.0-migration")
        }
    }

lazy val root = (project in file("."))
  .settings(
    name := "process-resource",
    crossScalaVersions := Seq("2.13.10", "2.12.17", "3.2.1"),
    autoAPIMappings := true,
    libraryDependencies ++= Seq(
        "org.typelevel" %% "cats-effect" % "3.4.11",
        "org.typelevel" %% "cats-effect-kernel" % "3.4.11",
      "co.fs2" %% "fs2-core" % "3.6.1",
        "co.fs2" %% "fs2-io" % "3.6.1",
        "org.typelevel" %% "cats-effect-testing-specs2" % "1.5.0" % Test,
        "org.typelevel" %% "munit-cats-effect-3" % "1.0.7" % Test,
        "org.typelevel" %% "scalacheck-effect-munit" % scalacheckEffectVersion % Test
    ),
    libraryDependencies ++= {
        CrossVersion.partialVersion(scalaVersion.value) match {
            case Some((2, n)) if n <= 12 =>
                List(compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"))
            case _                       => Nil
      }
    }
  )
