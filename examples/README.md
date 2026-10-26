# examples

Standalone sample programs that demonstrate the jmemviz library.

Build the main jar first (`mvn package` in the repository root), then compile and
run any example from this directory.

```bash
JAR=../target/jmemviz-0.1.0-SNAPSHOT.jar
```

---

## RecordDemo.java

A minimal example of calling the recording API (`record` / `track` / `snap`)
directly, without the preprocessor.

It records `Integer` boxing and reassignment (2 steps): the reassignment creates a
different instance, so the address and the entire byte sequence change, and
`track()` is called again with the same name to follow it.

It also passes its own source text and the line of each `snap()` call, so the viewer
shows the source in its left pane. The source is read from `RecordDemo.java` in the
current directory, so run it from this directory.

```bash
javac -cp $JAR RecordDemo.java
java  -cp .:$JAR RecordDemo [out.json]    # default: trace.json
../jmemviz serve trace.json
```

Or use the convenience script from the repository root:

```bash
./run_record_demo.sh [port]
```

---

## PointDemo.java

A preprocessor-annotated source file.  As-is it compiles and runs as a no-op;
use `jmemviz preprocess` to expand the `// @jmemviz` markers into real API calls.

```bash
../jmemviz preprocess PointDemo.java PointDemo_out.java
javac -cp $JAR PointDemo_out.java
java  -cp .:$JAR PointDemo               # writes trace.json
../jmemviz serve trace.json
```

Or use the convenience script from the repository root:

```bash
./run_point_demo.sh [port]
```
