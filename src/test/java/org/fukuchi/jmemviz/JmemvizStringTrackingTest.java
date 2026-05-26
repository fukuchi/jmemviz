package org.fukuchi.jmemviz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JmemvizStringTrackingTest {

    @TempDir
    Path tempDir;

    @Test
    void snap_includesStringValueArrayRegion() throws IOException {
        Path out = tempDir.resolve("trace.json");
        Jmemviz.record(out.toString(), () -> {
            String s = "Hello";
            Jmemviz.track("s", s);
            Jmemviz.snap("string");
        });

        String json = Files.readString(out);
        assertTrue(json.contains("\"name\": \"s.value\""), json);

        Matcher m = Pattern.compile("\"name\": \"s\\.value\"[\\s\\S]*?\"bytes\": \"([0-9a-f]+)\"")
                .matcher(json);
        assertTrue(m.find(), json);
        assertTrue(m.group(1).contains("48656c6c6f"), m.group(1));
    }
}
