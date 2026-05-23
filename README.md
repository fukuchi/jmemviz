# jmemviz

An educational tool for observing the **actual representation of objects on the JVM heap** using [Java Object Layout (JOL)](https://github.com/openjdk/jol) and `sun.misc.Unsafe`.

It has two modes of use:

1. **CLI mode** — prints static explanations of the layouts of `int`, `double`, `Integer`, `Double`, and related values to the console. Intended for lectures.
2. **Trace recording + browser playback** — embed `Jmemviz.track / snap` in arbitrary code, run it, and record the byte sequence at each `snap` point as JSON. The bundled local server and browser viewer show **step-by-step differences** with pink highlighting.

## Requirements

- JDK 21
- Maven 3.8+
- Linux/macOS (Windows has not been tested)

## Quick start

```bash
mvn package

# 1. CLI mode (static layout explanation)
java -jar target/jmemviz-0.1.0-SNAPSHOT.jar demo

# 2. record → serve → browser (default)
java -jar target/jmemviz-0.1.0-SNAPSHOT.jar
#   = record-and-serve trace.json 8765
# → opens http://127.0.0.1:8765/ in your browser
```

Subcommands:

```
jmemviz demo                              # Print CLI explanations
jmemviz record [out.json]                 # Write only a trace
jmemviz serve  [trace.json] [port]        # Serve an existing trace
jmemviz record-and-serve [out] [port]     # Do everything at once
```

## Recording API

```java
import static org.fukuchi.jmemviz.Jmemviz.*;

record("trace.json", () -> {
    int[] xs = {257, 258, 259, 260};
    track("xs", xs);
    snap("initial");

    xs[0] = 0x99999999;
    snap("after xs[0] = 0x99999999");
});
```

- `track(name, obj)` — registers an object to be tracked. Calling it again with the same name replaces the tracked object, which is useful when a variable is reassigned. Internally, this calls `System.identityHashCode(obj)` to stabilize the mark word. This avoids a Heisenberg-like effect in which the act of observation itself changes the mark word.
- `snap(label)` — reads the byte sequence of every currently tracked object, for the size reported by JOL's `sizeOf`, using `Unsafe.getByte`, and appends it as a snapshot with `label`.

`RecordDemo` currently contains the following scenarios, for a total of 11 steps:

1. Updating an element of an `int[]` → only the corresponding 4 bytes after the array header are highlighted.
2. Boxing an `Integer` and then reassigning it → the value becomes a different instance, so the entire byte sequence is refreshed.
3. Updating fields in `Point { int x; double y; }` → only the corresponding offsets change.
4. **`Rectangle { Point topLeft, bottomRight; }`** → the `Rectangle` object itself does not contain the values of the `Point` objects. It only contains **two 4-byte oop references**. Mutating the contents of a `Point` leaves the `Rectangle` body unchanged. The bytes of the reference field inside `Rectangle` change only when `r.bottomRight = newPoint` is executed.

## What the demo shows

### (A) A primitive value by itself is not a heap object

`int` and `double` exist as stack values, or inline inside class fields. They have no object header, no GC identity, and no object identity. Since JOL accepts only `Object`, a primitive value cannot be inspected by itself.

### (A') Once placed in a container, however, primitives appear as raw bytes on the heap

```
int[] {257, 258, 259, 260}
raw bytes (32B):
  01 00 00 00 00 00 00 00   ← mark word
  08 27 00 00               ← klass ptr
  04 00 00 00               ← array length = 4
  01 01 00 00 02 01 00 00   ← 257, 258
  03 01 00 00 04 01 00 00   ← 259, 260
```

Immediately after the 16-byte array header, **4-byte values are laid out consecutively**. There is no per-element header as there would be with `Integer`. The same holds for `double[]`: IEEE 754 double-precision values are laid out directly in 8-byte chunks (`1.0` = `00 00 00 00 00 00 f0 3f`).

```
An instance of class Point { int x; double y; } (24B):
  01 00 00 00 00 00 00 00   ← mark word
  b0 fc 01 01               ← klass ptr
  11 11 11 11               ← Point.x  (= 0x11111111)
  1f 85 eb 51 b8 1e 09 40   ← Point.y  (= 3.14, IEEE 754)
```

The same principle applies to class fields. Primitive values are not put into separate **boxes**; they are folded inline into the instance body.

### (B) `Integer` is a 16-byte box

```
OFF  SZ   TYPE DESCRIPTION               VALUE
  0   8        (object header: mark)     0x0000000000000001
  8   4        (object header: class)    0x00025fd8
 12   4    int Integer.value             257
Instance size: 16 bytes
```

- 8-byte **mark word** for hash, lock state, GC age, and related metadata
- 4-byte **klass pointer** when compressed oops are enabled
- 4-byte **value** field
- 16 bytes in total, just to carry a 4-byte integer

When the 16 bytes are read with `Unsafe.getByte`, the last four bytes appear as `01 01 00 00` in little-endian order, confirming the value 257 = `0x101`.

### (C) `Double` is 24 bytes, including padding

```
OFF  SZ     TYPE DESCRIPTION               VALUE
  0   8          (object header: mark)
  8   4          (object header: class)
 12   4          (alignment/padding gap)
 16   8   double Double.value              3.14
Instance size: 24 bytes
```

To align the value on an 8-byte boundary, 4 bytes of padding are inserted before it. This shows how Java's **header + value** model grows under alignment requirements.

### (D) The `Integer` cache

`Integer.valueOf(int)` returns values in the range `-128..127` from a static cache. Therefore:

```
valueOf(100) == valueOf(100) ?  true   ← inside the cache
valueOf(200) == valueOf(200) ?  false  ← outside the cache; a new object each time
```

Autoboxing such as `Integer x = 100;` internally calls `valueOf`, so it has the same behavior. Note that this is not comparing addresses directly; `==` checks reference identity.

### (E) `int[N]` vs `Integer[N]`: the aggregate cost of boxing

For an array of 1000 elements:

```
int[1000]      reachable bytes:   4016
Integer[1000]  reachable bytes:  20016   (× 5.0)
  └─ array itself   :   4016 bytes
  └─ 1000 Integers  :  16000 bytes  (= 1000 × 16B)
```

- `int[]`: one contiguous block containing a 16-byte header plus 4-byte values × N.
- `Integer[]`: the array contains only **4-byte references**. The value objects themselves, 16 bytes each, are scattered elsewhere on the heap. This costs **5×** the memory, plus pointer chasing and additional GC pressure.

## Troubleshooting

JOL may print warnings like these at startup:

```
# WARNING: Unable to get Instrumentation. Dynamic Attach failed.
# WARNING | Compressed references base/shifts are guessed by the experiment!
```

This happens when JOL cannot attach to the Serviceability Agent. Address display via `addressOf` then becomes **estimated**, but the layout information itself remains accurate. If exact addresses are required, run:

```bash
java -Djol.tryWithSudo=true -jar target/jmemviz-0.1.0-SNAPSHOT.jar
# or
echo 0 | sudo tee /proc/sys/kernel/yama/ptrace_scope
```

Alternatively, start the program with `-javaagent:path/to/jol-core-0.17.jar`.

## Design notes / pitfalls

- **Varargs trap**: `GraphLayout.parseInstance` takes `Object... roots`. If an `Integer[]` is passed directly, it is expanded as “1000 roots,” and the array object itself is omitted from the count. Cast it as `(Object) arr` to force it to be treated as “one array root.” `int[]` is a primitive array, so it is not expanded by varargs and does not have this problem.
- `sun.misc.Unsafe` is obtained via `Field.setAccessible(true)`. In JDK 21, the `jdk.unsupported` module is resolved automatically, so no extra option is required.
- To force `Integer` objects to be created outside the cache, the demo deliberately uses the deprecated `new Integer(int)` constructor with `@SuppressWarnings`. Production code should use `Integer.valueOf(...)` instead.

## Architecture

```
[ Demo code ]
   Jmemviz.record(out, () -> { ... track(), snap() ... })
       │
       │   ・Builds field annotations with JOL ClassLayout
       │   ・Reads raw bytes with Unsafe.getByte(obj, offset) (GC-safe)
       ▼
[ trace.json ]   <- self-contained JSON schema
       │
       ▼
[ JmemvizServer ]   com.sun.net.httpserver.HttpServer (bundled with the JDK; no external dependency)
       │   GET /            → classpath:/viewer/index.html
       │   GET /trace.json  → written JSON trace
       │   Opens the browser with Desktop.browse(URI)
       ▼
[ browser viewer ]   diff calculation happens on the JS side (prevBytes comparison)
```

Key design decisions:

- **The UI is a web browser**. A small vanilla JS viewer (no framework, no build step) renders the trace. Compared with Swing or JavaFX, this also has the advantage that traces remain portable JSON files.
- **Snapshots are triggered explicitly with `snap(label)`**. Java has no lightweight hook equivalent to `sys.settrace`; JVMTI/JDI would be heavy. For educational use, recording only the moments one wants to teach keeps the cognitive load low.
- **GC safety**: `Unsafe.getByte(Object, long)` re-resolves the oop handle inside HotSpot, making it GC-safe. Address display via `addressOf` is saved on a best-effort basis, but object identification uses `name`.
- **Stabilizing the mark word**: `track()` calls `System.identityHashCode(obj)` and burns the hash into the mark word. Without this, our own `safeRepr()` would generate a hash code through `toString()`, changing the header during observation and making bytes appear to change spontaneously between steps 0 and 1.

See `~/.claude/plans/visualization-architecture-java-synchronous-cherny.md` for details.

## Future extensions

- **Automatic recording with a Java Agent (ASM)**: capture a snapshot at every line, so users do not need to insert `snap()` calls manually. This can be added later because the JSON format remains compatible with v1.
- **Trace diff mode**: display two `trace.json` files side by side in the viewer.
- **Teaching JDK internals**: expand `String`'s `coder`/`hash`/`value` and `ArrayList`'s `elementData` as child regions.
- **Support for Compact Object Headers (Lilliput, JDK 24+)**: replace hard-coded header-size assumptions, such as `8 + 4 = 12B` in `buildFields`, with values obtained dynamically from JOL.

## Files

| File | Role |
|---|---|
| `pom.xml` | Maven configuration (JDK 21, JOL 0.17, shade plugin for a fat jar) |
| `src/main/java/org/fukuchi/jmemviz/Main.java` | CLI dispatcher (`demo` / `record` / `serve` / `record-and-serve`) |
| `src/main/java/org/fukuchi/jmemviz/Jmemviz.java` | Public API (`record/track/snap`) + Snapshotter |
| `src/main/java/org/fukuchi/jmemviz/TraceWriter.java` | JSON writer (handwritten, no dependency) |
| `src/main/java/org/fukuchi/jmemviz/JmemvizServer.java` | HttpServer + browser launch |
| `src/main/java/org/fukuchi/jmemviz/RecordDemo.java` | Recording demo using `snap()` |
| `src/main/java/org/fukuchi/jmemviz/JmemvizDemo.java` | Existing CLI explanation demo |
| `src/main/resources/viewer/index.html` | Browser viewer (vanilla JS, no dependencies) |
