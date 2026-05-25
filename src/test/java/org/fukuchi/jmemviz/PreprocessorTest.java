package org.fukuchi.jmemviz;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreprocessorTest {

    // ──────────────────────────────────────────────────────────────────
    // inferVarName
    // ──────────────────────────────────────────────────────────────────

    @Test
    void inferVarName_simpleInt() {
        assertEquals("xs", Preprocessor.inferVarName("        int[] xs = {257, 258, 259};"));
    }

    @Test
    void inferVarName_object() {
        assertEquals("p", Preprocessor.inferVarName("        Point p = new Point(1, 2);"));
    }

    @Test
    void inferVarName_finalModifier() {
        assertEquals("count", Preprocessor.inferVarName("        final int count = 0;"));
    }

    @Test
    void inferVarName_generic() {
        assertEquals("items", Preprocessor.inferVarName("        List<String> items = new ArrayList<>();"));
    }

    @Test
    void inferVarName_boxed() {
        assertEquals("boxed", Preprocessor.inferVarName("        Integer boxed = Integer.valueOf(257);"));
    }

    @Test
    void inferVarName_noAssignment() {
        // "int x;" — no '=' present
        assertEquals("x", Preprocessor.inferVarName("        int x;"));
    }

    // ──────────────────────────────────────────────────────────────────
    // transform – snap
    // ──────────────────────────────────────────────────────────────────

    @Test
    void transform_snap_withLabel() {
        String input = "        // @jmemviz snap \"initial state\"";
        String output = Preprocessor.transform(input);
        assertTrue(output.contains("snap(\"initial state\");"), "output: " + output);
    }

    @Test
    void transform_snap_withoutLabel_autoNumbers() {
        String input = """
                // @jmemviz snap
                // @jmemviz snap
                """;
        String output = Preprocessor.transform(input);
        assertTrue(output.contains("snap(\"step 0\");"), "output: " + output);
        assertTrue(output.contains("snap(\"step 1\");"), "output: " + output);
    }

    @Test
    void transform_snap_preservesIndent() {
        String input = "            // @jmemviz snap \"x\"";
        String output = Preprocessor.transform(input);
        assertTrue(output.contains("            snap(\"x\");"), "output: " + output);
    }

    @Test
    void transform_snap_escapedQuoteInLabel() {
        // In a .java source comment, \" is a literal backslash+quote.
        // The generated snap() call should contain a properly escaped Java string literal.
        // Comment text: // @jmemviz snap "s1 = \"Hello\""
        // → snap("s1 = \"Hello\"");  which at runtime is the string  s1 = "Hello"
        String input = "        // @jmemviz snap \"s1 = \\\"Hello\\\"\"";
        String output = Preprocessor.transform(input);
        assertTrue(output.contains("snap(\"s1 = \\\"Hello\\\"\");"), "output: " + output);
    }

    @Test
    void transform_snap_escapedBackslashInLabel() {
        // Marker comment: // @jmemviz snap "path\\to\\file"
        // In a Java source file the comment contains: path\to\file (after Java string parsing the literal)
        // We write it in this test as: "path\\\\to\\\\file" (4 backslashes = 2 literal \\ pairs)
        // The generated snap call should be:  snap("path\\to\\file");
        // which at runtime represents the string:  path\to\file
        String input = "// @jmemviz snap \"path\\\\to\\\\file\"";
        String output = Preprocessor.transform(input);
        assertTrue(output.contains("snap(\"path\\\\to\\\\file\");"), "output: " + output);
    }

    @Test
    void transform_snap_unknownEscapeKeptVerbatim() {
        // \n in a marker label is NOT a newline — it's kept as the two characters \n.
        // Marker comment: // @jmemviz snap "a\nb"   (literal backslash + n)
        // Generated code: snap("a\\nb");             (\ escaped to \\ in the Java literal)
        String input = "// @jmemviz snap \"a\\nb\"";
        String output = Preprocessor.transform(input);
        assertTrue(output.contains("snap(\"a\\\\nb\");"), "output: " + output);
    }

    // ──────────────────────────────────────────────────────────────────
    // transform – track (suffix)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void transform_track_inferredName() {
        String input = "        int[] xs = {1, 2, 3}; // @jmemviz track";
        String output = Preprocessor.transform(input);
        // Declaration line kept, marker removed
        assertTrue(output.contains("int[] xs = {1, 2, 3};"), "output: " + output);
        // track call injected
        assertTrue(output.contains("track(\"xs\", xs);"), "output: " + output);
        // marker comment gone
        assertFalse(output.contains("@jmemviz"), "output: " + output);
    }

    @Test
    void transform_track_explicitName() {
        String input = "        Point p = new Point(1, 2); // @jmemviz track myPoint";
        String output = Preprocessor.transform(input);
        assertTrue(output.contains("track(\"myPoint\", myPoint);"), "output: " + output);
    }

    @Test
    void transform_track_preservesIndent() {
        String input = "            Integer boxed = Integer.valueOf(257); // @jmemviz track";
        String output = Preprocessor.transform(input);
        // track() must share the same indentation as the declaration
        assertTrue(output.contains("            track(\"boxed\", boxed);"), "output: " + output);
    }

    // ──────────────────────────────────────────────────────────────────
    // transform – record / end
    // ──────────────────────────────────────────────────────────────────

    @Test
    void transform_recordAndEnd_withPath() {
        String input = """
                        // @jmemviz record "out.json"
                        int x = 1;
                        // @jmemviz end
                """;
        String output = Preprocessor.transform(input);
        assertTrue(output.contains("record(\"out.json\", () -> {"), "output: " + output);
        assertTrue(output.contains("});"), "output: " + output);
    }

    @Test
    void transform_recordAndEnd_defaultPath() {
        String input = "// @jmemviz record\n// @jmemviz end\n";
        String output = Preprocessor.transform(input);
        assertTrue(output.contains("record(\"trace.json\", () -> {"), "output: " + output);
    }

    // ──────────────────────────────────────────────────────────────────
    // transform – import injection
    // ──────────────────────────────────────────────────────────────────

    @Test
    void transform_injectsImport_whenAbsent() {
        String input = """
                public class Demo {
                    public static void main(String[] args) {
                        // @jmemviz record "t.json"
                        // @jmemviz end
                    }
                }
                """;
        String output = Preprocessor.transform(input);
        assertTrue(output.contains("import static org.fukuchi.jmemviz.Jmemviz.*;"),
                "output: " + output);
    }

    @Test
    void transform_doesNotDuplicateImport_whenPresent() {
        String input = """
                import static org.fukuchi.jmemviz.Jmemviz.*;
                public class Demo {
                    public static void main(String[] args) {
                        // @jmemviz snap "x"
                    }
                }
                """;
        String output = Preprocessor.transform(input);
        long count = output.lines()
                .filter(l -> l.contains("import static org.fukuchi.jmemviz.Jmemviz"))
                .count();
        assertEquals(1, count, "import duplicated in output:\n" + output);
    }

    @Test
    void transform_importPlacedAfterPackage() {
        String input = """
                package demo;
                public class Demo {}
                """;
        String output = Preprocessor.transform(input);
        int pkgIdx = output.indexOf("package demo;");
        int impIdx = output.indexOf("import static org.fukuchi.jmemviz.Jmemviz.*;");
        assertTrue(pkgIdx < impIdx, "import should come after package:\n" + output);
    }

    @Test
    void transform_importPlacedAfterExistingImports() {
        String input = """
                package demo;
                import java.util.List;
                public class Demo {}
                """;
        String output = Preprocessor.transform(input);
        int existingImpIdx = output.indexOf("import java.util.List;");
        int newImpIdx = output.indexOf("import static org.fukuchi.jmemviz.Jmemviz.*;");
        assertTrue(existingImpIdx < newImpIdx,
                "jmemviz import should come after existing import:\n" + output);
    }

    // ──────────────────────────────────────────────────────────────────
    // transform – end-to-end (full snippet)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void transform_fullSnippet() {
        String input = """
                public class MyDemo {
                    public static void main(String[] args) {
                        // @jmemviz record "trace.json"
                        int[] xs = {257, 258}; // @jmemviz track
                        // @jmemviz snap "initial"
                        xs[0] = 999;
                        // @jmemviz snap "after"
                        // @jmemviz end
                    }
                }
                """;
        String output = Preprocessor.transform(input);

        assertTrue(output.contains("import static org.fukuchi.jmemviz.Jmemviz.*;"));
        assertTrue(output.contains("record(\"trace.json\", () -> {"));
        assertTrue(output.contains("int[] xs = {257, 258};"));
        assertTrue(output.contains("track(\"xs\", xs);"));
        assertTrue(output.contains("snap(\"initial\");"));
        assertTrue(output.contains("xs[0] = 999;"));
        assertTrue(output.contains("snap(\"after\");"));
        assertTrue(output.contains("});"));
        // No marker comments should remain
        assertFalse(output.contains("@jmemviz"));
    }

    // ──────────────────────────────────────────────────────────────────
    // transform – no markers (pass-through)
    // ──────────────────────────────────────────────────────────────────

    @Test
    void transform_noMarkers_passThrough() {
        String input = "public class X { int x = 1; }";
        // No markers → output equals input except possibly an injected import.
        // The import IS injected (source has no jmemviz import).
        String output = Preprocessor.transform(input);
        assertTrue(output.contains("public class X { int x = 1; }"),
                "original code must be preserved: " + output);
    }

    @Test
    void transform_noMarkers_alreadyHasImport_noChange() {
        String input = "import static org.fukuchi.jmemviz.Jmemviz.*;\npublic class X {}";
        String output = Preprocessor.transform(input);
        // import should appear exactly once
        long count = output.lines()
                .filter(l -> l.contains("import static org.fukuchi.jmemviz.Jmemviz"))
                .count();
        assertEquals(1, count);
    }
}
