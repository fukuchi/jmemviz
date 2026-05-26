package org.fukuchi.jmemviz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        int nameIndex = json.indexOf("\"name\": \"s.value\"");
        assertTrue(nameIndex >= 0, json);
        int bytesKeyIndex = json.indexOf("\"bytes\": \"", nameIndex);
        assertTrue(bytesKeyIndex >= 0, json);
        int bytesValueStart = bytesKeyIndex + "\"bytes\": \"".length();
        int bytesValueEnd = json.indexOf('"', bytesValueStart);
        assertTrue(bytesValueEnd > bytesValueStart, json);
        String bytesHex = json.substring(bytesValueStart, bytesValueEnd);
        assertTrue(bytesHex.contains("48656c6c6f"), bytesHex);
    }
}
