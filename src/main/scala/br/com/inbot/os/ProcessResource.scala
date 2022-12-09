package br.com.inbot.os

import cats.effect._
import cats.effect.syntax.all._
import cats.syntax.all._
import fs2.io.{readInputStream, writeOutputStream}
import fs2.{Pipe, Stream => FStream}

import java.io.{Closeable, File, InputStream, OutputStream}



object ProcessResource {

    object exceptions {
        sealed trait ProcessError extends Product
        case class CancelledProcess(msg: String) extends ProcessError
        case class ExceptionInProcess(msg: String, err: Throwable) extends ProcessError
    }

    /**
     * Represents a created process with all the extra information
     * @tparam F the effect to use
     * @tparam T stream base type (usually Byte or Stream)
     * @param proc java Process object
     * @param stdin An FS2 Pipe one can write to and have the data be sent to the process
     * @param stdout Stream with the process standard output
     * @param stderr Stream with the process error output
     */
    case class FullProcess[F[_]: Async, T](proc: Process, stdin: Pipe[F, T, Nothing], stdout: FStream[F, T], stderr: FStream[F, T]) {
        /**
         * Waits for the process end
         * @return
         */
        def waitFor: F[Process] = {
            val fut: F[Process] = Async[F].fromCompletableFuture(Sync[F].delay(proc.onExit()))
            fut
        }

        def isTerminated: F[Boolean] =
            Sync[F].blocking { proc.exitValue() }
                .attempt
                .map(_.isRight)

    }

    /**
     * Runs a process inside an effect as a [[cats.effect.Resource]], thus taking care of
     * resource clean-up for the process along with its input and output.
     *
     * When the Resource object is *use*d, the process is created, and its stdin, stdout, and stderr
     * streams are turned into FS2 Streams (in the case of stderr and stdout), and into an FS2 Pipe
     * (in the case of stdin). After the "use" ends,
     *
     *
     * Notice that reading and writing should be done in different fibers as they may block
     *
     * Example:
     * {{{
     *     val processResource: Resource[IO, ProcessResource.FullProcess[IO]] =
     *         mkProcess[IO](Seq("curl", uri), Map.empty, None)
     *     val program = processResource.use { proc =>
     *         for {
     *            outFiber <- proc.stdout.through(fs2.text.utf8.decode).compile.toList.map(_.mkString).start
     *            errFiber <- proc.stderr.through(fs2.text.utf8.decode).compile.toList.map(_.mkString).start
     *            procResult <- proc.waitFor
     *            err <- errFiber.join
     *            out <- outFiber.join
     *            result <- if (procResult.exitValue() == 0) {
     *                    val output: IO[Either[TwitterErrorV1, Json]] = out.fold(
     *                        IO(Left(Canceled("canceled"))),
     *                        err => IO(Left[TwitterErrorV1, Json](InOutError(err.toString))),
     *                        outStr => outStr.map(out => parser.parse(out).left.map(err => ResponseError(err.toString))))
     *                    output
     *                } else {
     *                    IO(Left(ProcessError(s"exitcode: ${procResult.exitValue()}")))
     *                }
     *         } yield
     *             result
     *     }
     *
     * }}}
     *
     * @param cmd Command line to execute
     * @param env Environment variables
     * @param dir Work directory
     * @tparam F Effect type (IO, Try, Task, etc)
     * @return Um Resource that when used will create the process providing a [[FullProcess]] instance
     */
    def mkProcess[F[_]: Async](cmd: Seq[String], env: Map[String, String], dir: Option[File]): Resource[F, FullProcess[F, Byte]] = {
        val acquireProc: F[Process] = Sync[F].blocking {
            val pb = new ProcessBuilder(cmd: _*)
            if (dir.isDefined)
                pb.directory(dir.get)
            val envMap = pb.environment()
            for {
                (k, v) <- env
            } {
                envMap.put(k, v)
            }
            val proc: Process = pb.start()
            proc
        }
        val releaseProc = { (proc: Process) =>
            Sync[F].blocking {
                proc.destroy()
            }
        }
        val procResource = Resource.make(acquireProc)(releaseProc)

        def makeOutputResource(is: InputStream): Resource[F, FStream[F, Byte]] = {
            Resource.make(Sync[F].blocking(readInputStream(Sync[F].delay(is), 10240, closeAfterUse = true)))(_ => Sync[F].unit) // delay(println("Closing stdout source"))
        }

        def closeStream(cl: Closeable): F[Unit] = Sync[F].blocking(cl.close()).attempt.as(())

        val fullProcR: Resource[F, FullProcess[F, Byte]] = for {
            proc <- procResource
            stdinR <- Resource.make(Sync[F].blocking(proc.getOutputStream)){(os: OutputStream) =>
                // Sync[F].delay(println("Closing stdin")) *>
                closeStream(os)
            }
            stdinSink <- Resource.make(Sync[F].blocking(writeOutputStream(Sync[F].delay(stdinR), closeAfterUse = true)))(_ => Sync[F].unit) // Sync[F].delay(println("Closing stdin sink")))
            stdout <- Resource.make(Sync[F].blocking(proc.getInputStream)){(is: InputStream) =>
                // Sync[F].delay(println("Closing stdout")) *>
                closeStream(is)
            }
            stdoutSource <- makeOutputResource(stdout)
            stderr <- Resource.make(Sync[F].blocking(proc.getErrorStream)){(is: InputStream) =>
                // Sync[F].delay(println("closing stderr")) *>
                closeStream(is)
            }
            stderrSource <- makeOutputResource(stderr)
        } yield FullProcess(proc, stdinSink, stdoutSource, stderrSource)
        fullProcR
    }

    /**
     * Safely executes an external process (this is the same as mkProcess, but works with UTF8 Strings)
     *
     * @see [[mkProcess]]
     *
     * @param cmd cmdline parameters
     * @param env environment variables
     * @param dir working directory for the process being used
     * @tparam F effect being used
     * @return a resource containing a FullProcess instance which, when '''use'''d, will start a process, then reclaim its resources after done
     */
    def apply[F[_]: Async](cmd: Seq[String], env: Map[String, String], dir: Option[File]): Resource[F, FullProcess[F, String]] =
        convertToString(mkProcess(cmd, env, dir))

    /**
     * Simple version of [[apply]] that works with Strings and takes only the cmdline, not the environment or directory
     *
     * @see mkProcess
     *
     * @param cmd cmdline parameters
     * @tparam F effect
     * @return a resource containing a FullProcess instance which, when '''use''' will start a process, then reclaim its resources after done
     */
    def apply[F[_]: Async](cmd: Seq[String]): Resource[F, FullProcess[F, String]] =
        convertToString(mkProcess(cmd, Map.empty, None))

    /**
     * Transforms a ProcessResource object that does I/O with Bytes into one that works with utf8 Strings
     * @param resource the resource that works in bytes
     * @tparam F effect that is to be used
     * @return a converted Resource
     */
    def convertToString[F[_]: Async](resource: Resource[F, FullProcess[F, Byte]]): Resource[F, FullProcess[F, String]] = {
        resource.map { proc =>
            FullProcess[F, String](
                proc.proc,
                fs2.text.utf8.encode.andThen(proc.stdin),
                proc.stdout.through(fs2.text.utf8.decode),
                proc.stderr.through(fs2.text.utf8.decode)
            )
        }
    }

    case class SimpleRunResult(exitCode: Int, out: String, err: String)

    /**
     * Runs a command, giving it the given input. Collects the results
     * @param cmdline the command to execute and its parameters
     * @param input the text to give to the input
     * @tparam F the effect
     * @return a SimpleRunResult object
     */
    def simpleRun[F[_]: Sync: Async: Spawn](cmdline: Seq[String], input: String): F[Either[exceptions.ProcessError, SimpleRunResult]] = {
        val procR = apply[F](cmdline)
        procR.use { proc =>
            for {
                _ <- FStream.emit(input).covary[F].through (proc.stdin).compile.drain.attempt
                outFiber <- proc.stdout.compile.string.start
                errFiber <- proc.stderr.compile.string.start
                procResult <- proc.waitFor
                outOutcome <- outFiber.join
                errOutcome <- errFiber.join
                fullResult <- (outOutcome, errOutcome) match {
                    case (Outcome.Succeeded(outF), Outcome.Succeeded(errF)) =>
                        for {out <- outF; err <- errF} yield
                            Right(SimpleRunResult(procResult.exitValue(), out, err))
                    // I don't believe any of those cases will happen, but better safe than sorry
                    case (Outcome.Canceled(), Outcome.Canceled()) =>
                        Sync[F].pure(Left(exceptions.CancelledProcess("stdout and stderr cancelled")))
                    case (Outcome.Canceled(), _) =>
                        Sync[F].pure(Left(exceptions.CancelledProcess("stdout cancelled")))
                    case (_, Outcome.Canceled()) =>
                        Sync[F].pure(Left(exceptions.CancelledProcess("stderr cancelled")))
                    case (Outcome.Errored(err), _) =>
                        Sync[F].pure(Left(exceptions.ExceptionInProcess("stdout had exception", err)))
                    case (_, Outcome.Errored(err)) =>
                        Sync[F].pure(Left(exceptions.ExceptionInProcess("stderr had exception", err)))
                }
            } yield
                fullResult
        }
    }

}
