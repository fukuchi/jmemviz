package org.fukuchi.jmemviz;

import org.openjdk.jol.vm.VM;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Writes snapshot rows to JSON. Keeps dependencies minimal (no Jackson, etc.). */
final class TraceWriter {

    private TraceWriter() {}

    static void write(Path out, List<Jmemviz.Snapshot> snapshots, Map<String, Object> compressedOops) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("{\n");
        sb.append("  \"meta\": {\n");
        sb.append("    \"language\": \"java\",\n");
        sb.append("    \"jvm\": ").append(quote(jvmDetails())).append(",\n");
        sb.append("    \"n_steps\": ").append(snapshots.size());
        for (var e : compressedOops.entrySet()) {
            sb.append(",\n    ").append(quote(e.getKey())).append(": ").append(jsonScalar(e.getValue()));
        }
        sb.append("\n  },\n");
        sb.append("  \"snapshots\": [");
        for (int i = 0; i < snapshots.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("\n    ");
            appendSnapshot(sb, snapshots.get(i));
        }
        sb.append("\n  ]\n");
        sb.append("}\n");
        try {
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void appendSnapshot(StringBuilder sb, Jmemviz.Snapshot s) {
        sb.append("{\"step\": ").append(s.step());
        sb.append(", \"label\": ").append(quote(s.label()));
        sb.append(", \"regions\": [");
        for (int i = 0; i < s.regions().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("\n      ");
            appendRegion(sb, s.regions().get(i));
        }
        sb.append(s.regions().isEmpty() ? "]" : "\n    ]");
        sb.append("}");
    }

    private static void appendRegion(StringBuilder sb, Jmemviz.Region r) {
        sb.append("{\"name\": ").append(quote(r.name()));
        sb.append(", \"type\": ").append(quote(r.type()));
        sb.append(", \"repr\": ").append(quote(r.repr()));
        sb.append(", \"addr\": ").append(r.addr());
        sb.append(", \"size\": ").append(r.size());
        sb.append(", \"bytes\": ").append(quote(toHex(r.bytes())));
        sb.append(", \"fields\": [");
        for (int i = 0; i < r.fields().size(); i++) {
            if (i > 0) sb.append(", ");
            appendField(sb, r.fields().get(i));
        }
        sb.append("]}");
    }

    private static void appendField(StringBuilder sb, Jmemviz.FieldInfo f) {
        sb.append("{\"name\": ").append(quote(f.name()));
        sb.append(", \"offset\": ").append(f.offset());
        sb.append(", \"size\": ").append(f.size());
        sb.append(", \"kind\": ").append(quote(f.kind()));
        sb.append(", \"type\": ").append(quote(f.type()));
        sb.append("}");
    }

    private static String jvmDetails() {
        return System.getProperty("java.vendor") + " "
                + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.version")
                + " / object alignment " + VM.current().objectAlignment() + "B";
    }

    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            chars[i * 2]     = HEX[v >>> 4];
            chars[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(chars);
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String jsonScalar(Object v) {
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Number n)  return n.toString();
        return quote(String.valueOf(v));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
