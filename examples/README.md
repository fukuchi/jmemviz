# examples

Standalone sample programs that demonstrate the jmemviz library.

Build the main jar first (`mvn package` in the repository root), then compile and
run any example from this directory.

```bash
JAR=../target/jmemviz-0.1.0-SNAPSHOT.jar
```

---

## RecordDemo.java

Records a multi-step heap trace to a JSON file and serves it in the browser.

Scenarios covered (11 steps total):

1. `int[]` element updates → only the corresponding 4 bytes after the array header are highlighted.
2. `Integer` boxing and reassignment → the value becomes a different instance, so the entire byte sequence is refreshed.
3. `Point { int x; int y; }` field updates → only the corresponding offsets change.
4. `Rectangle { Point topLeft, bottomRight; }` → the `Rectangle` body contains only two 4-byte oop references. Mutating a `Point` field leaves the `Rectangle` body unchanged; only `r.bottomRight = newPoint` changes the reference field bytes inside `Rectangle`.

```bash
javac -cp $JAR RecordDemo.java
java  -cp .:$JAR RecordDemo [out.json]    # default: trace.json
../jmemviz serve trace.json
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
