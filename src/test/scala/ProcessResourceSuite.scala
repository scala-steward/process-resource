
import br.com.inbot.os.ProcessResource
import br.com.inbot.os.ProcessResource.mkProcess
import cats.effect.{IO, Resource}
import fs2.{Stream => FStream}
import munit.CatsEffectSuite

import java.io.File
import scala.concurrent.duration.DurationInt

class ProcessResourceSuite extends CatsEffectSuite {

    test("mkProcess should run echo") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO, Byte]] = mkProcess[IO](Seq("/bin/echo", "hello"), Map.empty, None)
        val program = procR.use { proc =>
            val stdout: fs2.Stream[IO, String] = proc.stdout.through(fs2.text.utf8.decode)
            val t: IO[Unit] = stdout.compile.toList.map(output => assertEquals(output.mkString, "hello\n"))
            t
        }
        program
    }
    test("mkProcess runs cat") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO, Byte]] = mkProcess[IO](Seq("/bin/cat"), Map.empty, None)
        val program = procR.use { proc =>
            val stdinStream: FStream[IO, Byte] = FStream.range(0, 100).map(_ => ".\n")
                .through(fs2.text.utf8.encode)
                .through(proc.stdin)
            for {
                _ <- stdinStream.compile.drain
                output <- proc.stdout.through(fs2.text.utf8.decode).compile.toList.map(_.mkString)
                // _ <- IO.println(s"length is ${output.length}")
            } yield
                assertEquals(output.length, 200)
        }
        program
    }

    test("Flag error when cmd does not exist") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO, String]] = ProcessResource[IO](Seq("/non/existent"))
        procR.use { proc =>
            proc.waitFor
        }.attempt map { p =>
            assert(p.isLeft)
        }

    }
    test("apply runs cat") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO, String]] = ProcessResource[IO](Seq("/bin/cat"))
        val program = procR.use { (proc: ProcessResource.FullProcess[IO, String]) =>
            val stdinStream: FStream[IO, Byte] =
                FStream.constant(".\n").take(100)
                    .through(proc.stdin)
            for {
                _ <- stdinStream.compile.drain
                output <- proc.stdout.compile.string
                terminated <- proc.isTerminated
            } yield {
                assertEquals(output.length, 200)
                assert(terminated)
            }
        }
        program
    }
    test("mkProcess stops input before reading everything") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO, Byte]] = mkProcess[IO](Seq("/bin/cat"), Map.empty, None)
        val program = procR.use { proc =>
            val stdinStream: FStream[IO, Byte] = FStream.range(0, 100).map(_ => ".\n")
                .through(fs2.text.utf8.encode)
                .through(proc.stdin)
            for {
                _ <- stdinStream.compile.drain
                output <- IO.sleep(1.second) *> proc.stdout.through(fs2.text.utf8.decode).compile.toList.map(_.mkString)
            } yield
                assertEquals(output.length, 200)
        }
        program
    }
    test("mkProcess causes error") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO, Byte]] = mkProcess[IO](Seq("/bin/bash", "-c", "sleep 1; exit 2"), Map.empty, None)
        val program = procR.use { proc =>
            val stdinStream: FStream[IO, Byte] = FStream.range(0, 100).map(_ => ".\n")
                .through(fs2.text.utf8.encode)
                .covary[IO]
                .spaced(100.millis)
                .through(proc.stdin)
            for {
                _ <- stdinStream.compile.drain.attempt.start
                _ <- proc.stdout.through(fs2.text.utf8.decode).evalTap(IO.println).compile.toList.map(_.mkString).attempt
                _ <- proc.waitFor
            } yield {
                assertEquals(proc.proc.exitValue(),2)
            }
        }
        program
    }
    test("mkProcess 1000 in parallel") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO, String]] = ProcessResource[IO](Seq("/bin/ls", "/"))
        val program: IO[Unit] = procR.use { proc =>
            for {
                output <- proc.stdout.compile.toList.map(_.mkString).attempt
                _ <- proc.waitFor
            } yield {
                assert(output.isRight)
            }
        }
        for {
            _ <- FStream.range(0, 1000)
                .covary[IO]
                .parEvalMap(100)(_ => program)
                .compile
                .drain
        } yield ()
    }

    test("can change directory") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO, String]] =
            ProcessResource[IO](Seq("/bin/bash", "-c", "pwd"), Map.empty[String, String], Some(new File("/")))
        val program = procR.use { proc =>
            for {
                outFiber <- proc.stdout.compile.toList.map(_.mkString).attempt.start
                result <- proc.waitFor
                out <- outFiber.join
            } yield {
                assert(out.isSuccess)
                assertEquals(result.exitValue(), 0)
                out.fold(
                    fail("output canceled"),
                    err => fail(s"error:${err}"),
                    i_e_pwd => i_e_pwd map { e_pwd =>
                        assert(e_pwd.isRight)
                        assertEquals(e_pwd.getOrElse("FAIL"), "/")
                    }
                )
            }
        }
        program
    }

    test("can set environment variables") {
        val procR: Resource[IO, ProcessResource.FullProcess[IO, String]] =
            ProcessResource[IO](Seq("/bin/bash", "-c", """echo $TESTVAR"""), Map("TESTVAR" -> "TESTVALUE"), None)
        val program: IO[Unit] = procR.use { proc =>
            for {
                outFiber <- proc.stdout.compile.toList.map(_.mkString).attempt.start
                result <- proc.waitFor
                out <- outFiber.join
                _ <- out.fold(
                    fail("output cancelled"),
                    err => fail(s"error:${err}"),
                    i_e_pwd => i_e_pwd map { e_pwd =>
                        assert(e_pwd.isRight)
                        assertEquals(e_pwd.getOrElse("FAIL"), "TESTVALUE\n")
                    }
                )
            } yield {
                assert(out.isSuccess)
                assertEquals(result.exitValue(), 0)
            }
        }
        program
    }

    test("simpleRun can read and write output") {
        val txt = "line 1\nline 2"
        for {
            runResult <- ProcessResource.simpleRun[IO](Seq("cat"), txt)
        } yield {
            runResult match {
                case Right(result) =>
                    assertEquals(result.exitCode, 0)
                    assertEquals(result.out, txt)
                    assertEquals(result.err, "")
                case Left(err) =>
                    fail(s"simple Run failed with error ${err}")
            }
        }
    }

    test("simpleRun reports exitcode != 0") {
        val txt = "line 1\nline 2"
        for {
            runResult <- ProcessResource.simpleRun[IO](Seq("/bin/bash", "-c", "echo 1; exit 2"), txt)
        } yield {
            runResult match {
                case Right(result) =>
                    assertEquals(result.exitCode, 2)
                    assertEquals(result.out, "1\n")
                    assertEquals(result.err, "")
                case Left(err) =>
                    fail(s"simple Run failed with error ${err}")
            }
        }
    }

}

