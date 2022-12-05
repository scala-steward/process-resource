package br.com.inbot.os

import cats._
import cats.effect._
import cats.syntax.all._
import fs2.io.{readInputStream, writeOutputStream}
import fs2.{Chunk, Pipe, Stream => FStream}

import java.io.{File, InputStream, OutputStream}



object ProcessResource {

    /**
     * Represents a created process with all the extra information
     * @tparam F efeito que está sendo usado
     * @param proc Process do java
     * @param stdin Um pipe no qual se pode escrever para mandar dados para o processo criado
     * @param stdout Uma stream com a saída do processo
     * @param stderr Uma stream com a entrada do processo
     * @param sync$F$0 instância de Sync para o efeito que está sendo usado
     * @param async$F$1 instância de Async para o efeito que está sendo usado
     */
    case class FullProcess[F[_]: Sync: Async](proc: Process, stdin: Pipe[F, Byte, Nothing], stdout: FStream[F, Byte], stderr: FStream[F, Byte]) {
        /**
         * Espera pelo final do processo
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
     * Notice that reading and writing should be done in different fibers, então é bom tomar cuidado
     *
     * Exemplo:
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
     * @param cmd Command line to exemplo
     * @param env Environment variables
     * @param dir Work directory
     * @tparam F Effect type (IO, Try, Task, etc)
     * @return Um Resource that when used will create the process providing a [[FullProcess]] instance
     */
    def mkProcess[F[_]: Sync: Async](cmd: Seq[String], env: Map[String, String], dir: Option[File]): Resource[F, FullProcess[F]] = {
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
        val releaseProc = { proc: Process =>
            Sync[F].blocking {
                proc.destroy()
            } // *> Sync[F].blocking(println("Releasing Process"))
        }
        val procResource = Resource.make(acquireProc)(releaseProc)

        val fullProcR: Resource[F, FullProcess[F]] = for {
            proc <- procResource
            stdinR <- Resource.make(Sync[F].blocking(proc.getOutputStream)){(os: OutputStream) =>
                // Sync[F].delay(println("Closing stdin")) *>
                Sync[F].blocking(os.close()).attempt.as(()) // captura erro
            }
            stdinSink <- Resource.make(Sync[F].blocking(writeOutputStream(Sync[F].delay(stdinR), true)))(_ => Sync[F].unit) // Sync[F].delay(println("Closing stdin sink")))
            stdoutR <- Resource.make(Sync[F].blocking(proc.getInputStream)){(is: InputStream) =>
                // Sync[F].delay(println("Closing stdout")) *>
                Sync[F].blocking(is.close()).attempt.as(()) // captura erro em caso de problemas na hora de fechar o arquivo
            }
            stdoutSource: FStream[F, Byte] <- Resource.make(Sync[F].blocking(readInputStream(Sync[F].delay(stdoutR), 10240, true)))(_ => Sync[F].unit) // delay(println("Closing stdout source")))
            stderrR <- Resource.make(Sync[F].blocking(proc.getErrorStream)){(is: InputStream) =>
                // Sync[F].delay(println("closing stderr")) *>
                Sync[F].blocking(is.close()).attempt.as(()) // captura erro
            }
            stderrSource <- Resource.make(Sync[F].blocking(readInputStream(Sync[F].delay(stderrR), 10240, true)))(_ => Sync[F].unit) // Sync[F].delay(println("Closing stderr source")))
        } yield (FullProcess(proc, stdinSink, stdoutSource, stderrSource))
        fullProcR
    }

    /**
     * Safely executes an external process (this is the same as mkProcess)
     *
     * @see [[mkProcess]]
     *
     * @param cmd cmdline parameters
     * @param env environment variables
     * @param dir working directory for the process being used
     * @tparam F effect being used
     * @return a resource containing a FullProcess instance which, when '''use'''d, will start a process, then reclaim its resources after done
     */
    def apply[F[_]: Sync: Async](cmd: Seq[String], env: Map[String, String], dir: Option[File]): Resource[F, FullProcess[F]] =
        mkProcess(cmd, env, dir)

    /**
     * Simple version of [[mkProcess]] that takes only the cmdline parameters
     *
     * @see mkProcess
     *
     * @param cmd cmdline parameters
     * @tparam F effect
     * @return a resource containing a FullProcess instance which, when '''use''' will start a process, then reclaim its resources after done
     */
    def apply[F[_]: Sync: Async](cmd: Seq[String]): Resource[F, FullProcess[F]] =
        mkProcess(cmd, Map.empty, None)

}
