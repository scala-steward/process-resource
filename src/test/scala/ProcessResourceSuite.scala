package br.com.inbot.gol

import br.com.inbot.os.ProcessResource
import br.com.inbot.os.ProcessResource.mkProcess
import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import fs2.{Stream => FStream}

import scala.concurrent.duration.DurationInt

class ProcessResourceSuite extends CatsEffectSuite {

    test("mkProcess should run echo") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO]] = mkProcess[IO](Seq("/bin/echo", "hello"), Map.empty, None)
        val program = procR.use { proc =>
            val stdout: fs2.Stream[IO, String] = proc.stdout.through(fs2.text.utf8.decode)
            val t: IO[Unit] = stdout.compile.toList.map(output => assertEquals(output.mkString, "hello\n"))
            t
        }
        program
    }
    test("mkProcess runs cat") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO]] = mkProcess[IO](Seq("/bin/cat"), Map.empty, None)
        val program = procR.use { proc =>
            val stdinStream: FStream[IO, Byte] = FStream.range(0, 100).map(_ => ".\n")
                .through(fs2.text.utf8.encode)
                .through(proc.stdin)
            for {
                _ <- stdinStream.compile.drain
                output: String <- proc.stdout.through(fs2.text.utf8.decode).compile.toList.map(_.mkString)
                // _ <- IO.println(s"length is ${output.length}")
            } yield
                assertEquals(output.length, 200)
        }
        program
    }
    test("mkProcess stops input before reading everything") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO]] = mkProcess[IO](Seq("/bin/cat"), Map.empty, None)
        val program = procR.use { proc =>
            val stdinStream: FStream[IO, Byte] = FStream.range(0, 100).map(_ => ".\n")
                .through(fs2.text.utf8.encode)
                .through(proc.stdin)
            for {
                _ <- stdinStream.compile.drain
                output: String <- IO.sleep(1.second) *> proc.stdout.through(fs2.text.utf8.decode).compile.toList.map(_.mkString)
            } yield
                assertEquals(output.length, 200)
        }
        program
    }
    test("mkProcess causes error") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO]] = mkProcess[IO](Seq("/bin/bash", "-c", "sleep 1; exit 2"), Map.empty, None)
        val program = procR.use { proc =>
            val stdinStream: FStream[IO, Byte] = FStream.range(0, 100).map(_ => ".\n")
                .through(fs2.text.utf8.encode)
                .covary[IO]
                .spaced(100.millis)
                .through(proc.stdin)
            for {
                _ <- stdinStream.compile.drain.attempt.start
                output: Either[Throwable, String] <- proc.stdout.through(fs2.text.utf8.decode).evalTap(IO.println).compile.toList.map(_.mkString).attempt
                outProc <- proc.waitFor
                // _ <- IO.println(s"result:${outProc}: ${outProc.exitValue()}: output=${output}")
            } yield
                assertEquals(proc.proc.exitValue(),2)
        }
        program
    }
    test("mkProcess 1000 em paralelo") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO]] = mkProcess[IO](Seq("/bin/ls", "/"), Map.empty, None)
        val program: IO[Unit] = procR.use { proc =>
            for {
                output <- proc.stdout.through(fs2.text.utf8.decode).compile.toList.map(_.mkString).attempt
                // buf = Array.fill[Byte](100000)(32)
                result <- proc.waitFor
                // _ <- IO.println(s"${result}: ${output}")
            } yield {
                assert(output.isRight)
                // assertEquals(output.getOrElse(""), "/\n")
            }
        }
        for {
            _ <- FStream.range(0, 1000).covary[IO].parEvalMap(100)(_ => program).compile.drain
        } yield ()
    }

    test("set account devdev13385761") {


    }

}

