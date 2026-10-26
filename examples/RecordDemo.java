import static org.fukuchi.jmemviz.Jmemviz.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal example of calling the recording API directly, without the
 * preprocessor. It records {@code Integer} boxing and reassignment and writes
 * a {@code trace.json} file for browser playback.
 *
 * <p>The source text and the line of each {@code snap()} call are passed to
 * the API, so the viewer shows this file in its left pane. The source is read
 * from {@code RecordDemo.java} in the current directory; if it is missing,
 * the viewer falls back to the label list.
 *
 * <pre>
 *   JAR=../target/jmemviz-0.1.0-SNAPSHOT.jar
 *   javac -cp $JAR RecordDemo.java
 *   java  -cp .:$JAR RecordDemo [trace.json]   # default: trace.json
 *   ../jmemviz serve trace.json
 * </pre>
 */
public final class RecordDemo {

    public static void main(String[] args) {
        String outPath = args.length > 0 ? args[0] : "trace.json";
        run(outPath);
    }

    public static void run(String outPath) {
        int startLine = here();
        record(outPath, () -> {
            // ─── (1) Integer: a boxed int is a heap object ───────────
            Integer boxed = newInteger(257);
            track("boxed", boxed);
            snap("The int value follows the 12B header (257 → 01 01 00 00)", here());

            // ─── (2) Reassignment points to a different object ───────
            // The old object is not modified; boxed now refers to a new one.
            // track() again with the same name to follow the new object.
            boxed = newInteger(258);
            track("boxed", boxed);
            snap("A new object at a different address, not an in-place update", here());
        }, "RecordDemo.java", startLine, readSource("RecordDemo.java"));
    }

    /** Returns the line number of the caller, to tell the viewer where each snap() is. */
    private static int here() {
        return StackWalker.getInstance()
                .walk(frames -> frames.skip(1).findFirst())
                .map(StackWalker.StackFrame::getLineNumber)
                .orElse(-1);
    }

    private static String readSource(String file) {
        try {
            return Files.readString(Path.of(file));
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Integer newInteger(int v) {
        // Intentionally use old API to reliably bypass IntegerCache,
        // so learners can observe "new on every call".
        return new Integer(v);
    }
}
