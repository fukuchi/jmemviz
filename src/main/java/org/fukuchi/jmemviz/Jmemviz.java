package org.fukuchi.jmemviz;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.FieldLayout;
import org.openjdk.jol.vm.VM;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public API for jmemviz.
 *
 * <pre>
 *   Jmemviz.record("trace.json", () -> {
 *       int[] xs = {257, 258};
 *       Jmemviz.track("xs", xs);
 *       Jmemviz.snap("initial");
 *       xs[0] = 9999;
 *       Jmemviz.snap("after xs[0] = 9999");
 *   });
 * </pre>
 */
public final class Jmemviz {

    private static Recorder current;

    private Jmemviz() {}

    public static void record(String outPath, Runnable body) {
        if (current != null) throw new IllegalStateException("Jmemviz.record is already active");
        Recorder rec = new Recorder();
        current = rec;
        try {
            body.run();
        } finally {
            current = null;
        }
        TraceWriter.write(Path.of(outPath), rec.snapshots, compressedOopsInfo());
        System.out.printf("[jmemviz] wrote %d snapshots to %s%n", rec.snapshots.size(), outPath);
    }

    /**
     * Extracts compressed-oops parameters from JOL's HotspotUnsafe.
     * With these, the viewer can reconstruct a 4B narrow oop into a real address.
     * Returns an empty map if extraction fails (viewer side fallback).
     */
    private static Map<String, Object> compressedOopsInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        Object vm = VM.current();
        try {
            Class<?> c = vm.getClass();
            // Access private fields of org.openjdk.jol.vm.HotspotUnsafe.
            m.put("compressed_oops", readPrivate(c, vm, "compressedOopsEnabled"));
            m.put("narrow_oop_base", readPrivate(c, vm, "narrowOopBase"));
            m.put("narrow_oop_shift", readPrivate(c, vm, "narrowOopShift"));
            m.put("object_alignment", VM.current().objectAlignment());
        } catch (ReflectiveOperationException ignored) {
            // If JOL internals changed or this is a different JVM, let the viewer
            // treat shift=0, base=0 (i.e., show the 4B value directly as an address).
        }
        return m;
    }

    private static Object readPrivate(Class<?> c, Object obj, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    /** Registers a tracked object (keeps a strong reference). Replaces on same name. */
    public static void track(String name, Object obj) {
        // Burn identityHashCode into the mark word in advance to avoid snapshots
        // where only the header changes unexpectedly (caused by our own repr call).
        System.identityHashCode(obj);
        require().tracked.put(name, obj);
    }

    /** Captures byte sequences of all currently tracked objects. */
    public static void snap(String label) {
        Recorder r = require();
        List<Region> regions = new ArrayList<>(r.tracked.size());
        for (var e : r.tracked.entrySet()) {
            regions.add(captureRegion(e.getKey(), e.getValue()));
        }
        r.snapshots.add(new Snapshot(r.snapshots.size(), label, regions));
    }

    private static Recorder require() {
        if (current == null) throw new IllegalStateException("Jmemviz.record(...) is not active");
        return current;
    }

    // ─── snapshot construction ───────────────────────────────────

    private static Region captureRegion(String name, Object obj) {
        long size = VM.current().sizeOf(obj);
        long addr = VM.current().addressOf(obj);
        byte[] bytes = readBytes(obj, (int) size);
        List<FieldInfo> fields = buildFields(obj, size);
        return new Region(name, obj.getClass().getName(), safeRepr(obj), addr, size, bytes, fields);
    }

    private static byte[] readBytes(Object obj, int size) {
        Unsafe u = unsafe();
        byte[] out = new byte[size];
        // Read in one short loop to minimize inconsistency during GC.
        for (int i = 0; i < size; i++) {
            out[i] = u.getByte(obj, (long) i);
        }
        return out;
    }

    private static List<FieldInfo> buildFields(Object obj, long size) {
        List<FieldInfo> out = new ArrayList<>();
        // Header (JDK 21 + compressed oops: mark 8B + klass 4B = 12B)
        out.add(new FieldInfo("(header: mark)",  0, 8, "header",  ""));
        out.add(new FieldInfo("(header: class)", 8, 4, "header",  ""));

        if (obj.getClass().isArray()) {
            // Arrays: length (4B) and elements.
            out.add(new FieldInfo("(array length)", 12, 4, "header", "int"));
            int base = unsafe().arrayBaseOffset(obj.getClass());
            int elemLen = (int) size - base;
            if (elemLen > 0) {
                String comp = obj.getClass().getComponentType().getSimpleName();
                out.add(new FieldInfo("[i] elements", base, elemLen, "primitive", comp));
            }
        } else {
            for (FieldLayout fl : ClassLayout.parseInstance(obj).fields()) {
                String type = fl.typeClass();
                String kind = isReferenceType(type) ? "ref" : "primitive";
                out.add(new FieldInfo(simpleFieldName(fl.name()), fl.offset(), (int) fl.size(), kind, type));
            }
        }
        return out;
    }

    private static String simpleFieldName(String fullName) {
        // JOL's name() uses the format "MyClass.fieldName".
        int dot = fullName.lastIndexOf('.');
        return dot < 0 ? fullName : fullName.substring(dot + 1);
    }

    private static boolean isReferenceType(String type) {
        return !switch (type) {
            case "boolean", "byte", "char", "short", "int", "long", "float", "double" -> true;
            default -> false;
        };
    }

    private static String safeRepr(Object obj) {
        try {
            String s = String.valueOf(obj);
            return s.length() > 60 ? s.substring(0, 57) + "..." : s;
        } catch (Throwable t) {
            return "<toString failed>";
        }
    }

    private static Unsafe unsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ─── internal data ───────────────────────────────────────────

    static final class Recorder {
        final LinkedHashMap<String, Object> tracked = new LinkedHashMap<>();
        final List<Snapshot> snapshots = new ArrayList<>();
    }

    record Snapshot(int step, String label, List<Region> regions) {}

    record Region(String name, String type, String repr,
                  long addr, long size, byte[] bytes, List<FieldInfo> fields) {}

    record FieldInfo(String name, long offset, int size, String kind, String type) {}
}
