# process-resource

Runs external command in a safe and (relatively) simple way.

It makes a good effort to treat execution errors, and not to leak process handles and file descriptors.

## simpleRun
The simplest way to run is to use the [simpleRun] command either as 
```scala
simpleRun(cmdLine: Seq[String]*): F[Either[exceptions.ProcessError, SimpleRunResult]]
```
or with the environment and directory parameters, as
```scala
simpleRun(cmdLine: Seq[String], env: Map[String, String], cwd: Option[File]): F[Either[exceptions.ProcessError, SimpleRunResult]]
```

```scala
    for {
        runResult <- ProcessResource.simpleRun[IO](Seq("/bin/bash", "-c", "echo 1; exit 2"), (txt))
    } yield {
        runResult match {
            case Right(result) =>
                // Success! 
            case Left(err) =>
                // It failed with error err
    }
}


```

## Running as a Resource

When you call the apply method, a Resource is created. You can "use" that resource, during which a FullProcess object is provided, containing the java Process object, stdin, stdout and stderr.

stdout and stderr are exposed as FS2 Streams of Strings.

stdin is a Pipe[F, String] and it's possible to send data from a Stream to it using the through(stdin)

While inside the "use" block, it's possible to read and write under the context of the given effect. 
For that reason, processing is usually done inside of a for block.







