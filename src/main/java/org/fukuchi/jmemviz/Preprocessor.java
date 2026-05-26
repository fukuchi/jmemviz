package org.fukuchi.jmemviz;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source-level preprocessor that turns {@code // @jmemviz} comment markers in a
 * Java source file into explicit {@link Jmemviz} API calls.
 *
 * <h2>Supported markers</h2>
 * <table>
 *   <tr>
 *     <th>Marker (standalone line)</th>
 *     <th>Generated code</th>
 *   </tr>
 *   <tr>
 *     <td>{@code // @jmemviz record "path/to/trace.json"}</td>
 *     <td>{@code record("path/to/trace.json", () -> \{}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code // @jmemviz end}</td>
 *     <td>{@code });}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code // @jmemviz snap "step label"}</td>
 *     <td>{@code snap("step label");}</td>
 *   </tr>
 * </table>
 *
 * <table>
 *   <tr>
 *     <th>Marker (suffix on declaration)</th>
 *     <th>Effect</th>
 *   </tr>
 *   <tr>
 *     <td>{@code Type name = ...; // @jmemviz track}</td>
 *     <td>strips marker, appends {@code track("name", name);} on next line;
 *         variable name is inferred from the declaration.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code Type name = ...; // @jmemviz track explicitName}</td>
 *     <td>same, but uses the supplied name instead of inferring.</td>
 *   </tr>
 *   <tr>
 *     <td>{@code stmt; // @jmemviz snap}</td>
 *     <td>strips marker, appends {@code snap("stmt");} on next line;
 *         label defaults to the statement text.</td>
 *   </tr>
 * </table>
 *
 * <p>If {@code import static org.fukuchi.jmemviz.Jmemviz.*;} is not already
 * present the preprocessor inserts it automatically after the last
 * {@code package}/existing {@code import} statement.
 *
 * <h2>Example</h2>
 * <pre>
 * // Input (valid Java – compiles and runs unchanged, just does no recording):
 * public class PointDemo {
 *     public static void main(String[] args) {
 *         // @jmemviz record "trace.json"
 *         int[] xs = {257, 258, 259}; // @jmemviz track
 *         // @jmemviz snap "initial"
 *         xs[0] = 999;
 *         // @jmemviz snap "after xs[0] = 999"
 *         // @jmemviz end
 *     }
 * }
 *
 * // Output (after preprocessing):
 * import static org.fukuchi.jmemviz.Jmemviz.*;
 * public class PointDemo {
 *     public static void main(String[] args) {
 *         record("trace.json", () -> {
 *         int[] xs = {257, 258, 259};
 *         track("xs", xs);
 *         snap("initial");
 *         xs[0] = 999;
 *         snap("after xs[0] = 999");
 *         });
 *     }
 * }
 * </pre>
 */
public final class Preprocessor {
    private static final String QUOTED_MARKER_STRING = "\"((?:[^\"\\\\]|\\\\.)*)\"";

    // Standalone: // @jmemviz record ["path"]
    private static final Pattern RECORD =
            Pattern.compile("^(\\s*)//\\s*@jmemviz\\s+record(?:\\s+\"((?:[^\"\\\\]|\\\\.)*)\")?\\s*$");

    // Standalone: // @jmemviz end
    private static final Pattern END =
            Pattern.compile("^(\\s*)//\\s*@jmemviz\\s+end\\s*$");

    // Standalone: // @jmemviz snap ["label"]
    private static final Pattern SNAP =
            Pattern.compile("^(\\s*)//\\s*@jmemviz\\s+snap(?:\\s+\"((?:[^\"\\\\]|\\\\.)*)\")?\\s*$");

    // Suffix on any statement: ... // @jmemviz track [name]
    private static final Pattern TRACK_SUFFIX =
            Pattern.compile("//\\s*@jmemviz\\s+track(?:\\s+(\\w+))?\\s*$");

    // Suffix on any statement: ... // @jmemviz track [name] snap ["label"]
    //   group(1): track variable name (optional)
    //   group(2): snap label inside quotes (optional)
    private static final Pattern TRACK_SNAP_SUFFIX =
            Pattern.compile("//\\s*@jmemviz\\s+track(?:\\s+([a-zA-Z_$][a-zA-Z0-9_$]*))?\\s+snap(?:\\s+" + QUOTED_MARKER_STRING + ")?\\s*$");

    // Suffix on any statement: ... // @jmemviz snap ["label"]
    private static final Pattern SNAP_SUFFIX =
            Pattern.compile("//\\s*@jmemviz\\s+snap(?:\\s+" + QUOTED_MARKER_STRING + ")?\\s*$");

    private static final String STATIC_IMPORT =
            "import static org.fukuchi.jmemviz.Jmemviz.*;";

    private Preprocessor() {}

    /**
     * Reads {@code inputPath}, applies the marker transformation, and writes
     * the result to {@code outputPath} (which may be the same file).
     */
    public static void process(Path inputPath, Path outputPath) {
        try {
            String source = Files.readString(inputPath, StandardCharsets.UTF_8);
            String result = transform(source);
            Files.writeString(outputPath, result, StandardCharsets.UTF_8);
            System.out.printf("[jmemviz] preprocessed %s → %s%n", inputPath, outputPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Transforms source text that contains {@code @jmemviz} markers.
     * Visible for testing.
     */
    static String transform(String source) {
        boolean needsImport =
                !source.contains("import static org.fukuchi.jmemviz.Jmemviz");

        String[] lines = source.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length + 16);
        int snapCounter = 0;

        for (String line : lines) {
            Matcher m;

            // Standalone: // @jmemviz record "path"
            m = RECORD.matcher(line);
            if (m.matches()) {
                String indent = m.group(1);
                String path = m.group(2) != null ? unescapeMarkerString(m.group(2)) : "trace.json";
                out.add(indent + "record(\"" + escapeJava(path) + "\", () -> {");
                continue;
            }

            // Standalone: // @jmemviz end
            m = END.matcher(line);
            if (m.matches()) {
                String indent = m.group(1);
                out.add(indent + "});");
                continue;
            }

            // Standalone: // @jmemviz snap ["label"]
            m = SNAP.matcher(line);
            if (m.matches()) {
                String indent = m.group(1);
                String label = m.group(2) != null ? unescapeMarkerString(m.group(2)) : null;
                if (label == null) label = "step " + snapCounter;
                snapCounter++;
                out.add(indent + "snap(\"" + escapeJava(label) + "\");");
                continue;
            }

            // Suffix: stmt; // @jmemviz track [name] snap ["label"]
            m = TRACK_SNAP_SUFFIX.matcher(line);
            if (m.find()) {
                String codePart = line.substring(0, m.start()).stripTrailing();
                String varName = m.group(1);
                if (varName == null) {
                    varName = inferVarName(codePart);
                }
                out.add(codePart);
                String indent = leadingSpaces(codePart);
                if (varName != null) {
                    out.add(indent + "track(\"" + varName + "\", " + varName + ");");
                }
                appendSnap(out, codePart, m.group(2));
                continue;
            }

            // Suffix: stmt; // @jmemviz track [name]
            m = TRACK_SUFFIX.matcher(line);
            if (m.find()) {
                String codePart = line.substring(0, m.start()).stripTrailing();
                String varName = m.group(1);
                if (varName == null) {
                    varName = inferVarName(codePart);
                }
                out.add(codePart);
                if (varName != null) {
                    String indent = leadingSpaces(codePart);
                    out.add(indent + "track(\"" + varName + "\", " + varName + ");");
                }
                continue;
            }

            // Suffix: stmt; // @jmemviz snap ["label"]
            m = SNAP_SUFFIX.matcher(line);
            if (m.find()) {
                String codePart = line.substring(0, m.start()).stripTrailing();
                out.add(codePart);
                appendSnap(out, codePart, m.group(1));
                continue;
            }

            out.add(line);
        }

        String transformed = String.join("\n", out);

        // Inject import if needed (second pass for clean placement)
        if (needsImport) {
            transformed = injectImport(transformed, STATIC_IMPORT);
        }

        return transformed;
    }

    /**
     * Injects {@code importDecl} after the last {@code package} or existing
     * {@code import} statement, or at the very beginning of the file if
     * neither is present.
     */
    private static String injectImport(String source, String importDecl) {
        String[] lines = source.split("\n", -1);
        int insertAfter = -1;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                insertAfter = i;
            }
        }

        List<String> out = new ArrayList<>(lines.length + 1);
        for (int i = 0; i < lines.length; i++) {
            out.add(lines[i]);
            if (i == insertAfter) {
                out.add(importDecl);
            }
        }
        if (insertAfter < 0) {
            out.add(0, importDecl);
        }
        return String.join("\n", out);
    }

    /**
     * Infers the variable name from a local-variable declaration string.
     *
     * <p>Strategy: take the text to the left of the first {@code =}, strip
     * array brackets and generic type parameters, then return the last
     * whitespace-delimited token — which is the declared name.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code int[] xs = {257}} → {@code xs}</li>
     *   <li>{@code Point p = new Point(1, 2)} → {@code p}</li>
     *   <li>{@code final List<String> items = new ArrayList<>()} → {@code items}</li>
     * </ul>
     */
    static String inferVarName(String declaration) {
        String s = declaration.stripTrailing();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1).stripTrailing();

        // Use only the left-hand side (before the first '=')
        int eq = s.indexOf('=');
        String lhs = eq >= 0 ? s.substring(0, eq).stripTrailing() : s;

        // Remove array-bracket pairs and generic type parameters
        lhs = lhs.replaceAll("[\\[\\]<>]", " ").stripTrailing();

        // The variable name is the last whitespace-delimited token
        String[] tokens = lhs.trim().split("\\s+");
        if (tokens.length == 0) return null;
        String name = tokens[tokens.length - 1];

        return name.matches("[a-zA-Z_$][a-zA-Z0-9_$]*") ? name : null;
    }

    private static String defaultSnapLabel(String codePart) {
        String label = codePart.stripTrailing();
        if (label.endsWith(";")) {
            label = label.substring(0, label.length() - 1).stripTrailing();
        }
        return label;
    }

    private static void appendSnap(List<String> out, String codePart, String markerLabel) {
        String label = markerLabel != null
                ? unescapeMarkerString(markerLabel)
                : defaultSnapLabel(codePart);
        out.add(leadingSpaces(codePart) + "snap(\"" + escapeJava(label) + "\");");
    }

    private static String leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    private static String escapeJava(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Unescapes a string captured from between the outer quotes of a marker.
     * Only {@code \"} → {@code "} and {@code \\} → {@code \} are processed;
     * any other {@code \X} sequence is kept verbatim (both characters).
     */
    private static String unescapeMarkerString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    sb.append(next);
                    i += 2;
                } else {
                    sb.append(c);
                    sb.append(next);
                    i += 2;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
