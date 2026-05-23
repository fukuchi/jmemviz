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
 * jmemviz 公開 API。
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
     * JOL の HotspotUnsafe から compressed oops のパラメータを引き抜く。
     * これがあれば viewer 側で 4B narrow oop を実アドレスに復元できる。
     * 取得失敗時は空 Map を返す (viewer 側で fallback)。
     */
    private static Map<String, Object> compressedOopsInfo() {
        Map<String, Object> m = new LinkedHashMap<>();
        Object vm = VM.current();
        try {
            Class<?> c = vm.getClass();
            // org.openjdk.jol.vm.HotspotUnsafe の private field を覗く
            m.put("compressed_oops", readPrivate(c, vm, "compressedOopsEnabled"));
            m.put("narrow_oop_base", readPrivate(c, vm, "narrowOopBase"));
            m.put("narrow_oop_shift", readPrivate(c, vm, "narrowOopShift"));
            m.put("object_alignment", VM.current().objectAlignment());
        } catch (ReflectiveOperationException ignored) {
            // JOL の内部実装が変わった/別 JVM だった場合は viewer 側で
            // shift=0,base=0 として扱う (= 4B 値をそのままアドレスに見せる)
        }
        return m;
    }

    private static Object readPrivate(Class<?> c, Object obj, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    /** トラッキング対象を登録 (強参照保持)。同名で呼べば差し替え。 */
    public static void track(String name, Object obj) {
        // identityHashCode を事前に mark word に焼き込んで、スナップショット
        // ごとに「ヘッダだけ勝手に変わる」現象 (我々自身の repr 呼出が原因) を避ける
        System.identityHashCode(obj);
        require().tracked.put(name, obj);
    }

    /** 現在追跡中の全オブジェクトのバイト列を撮る。 */
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

    // ─── snapshot 構築 ───────────────────────────────────────────

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
        // 短いループでまとめて読み、GC 中の不整合を最小化する
        for (int i = 0; i < size; i++) {
            out[i] = u.getByte(obj, (long) i);
        }
        return out;
    }

    private static List<FieldInfo> buildFields(Object obj, long size) {
        List<FieldInfo> out = new ArrayList<>();
        // ヘッダ (JDK 21 + compressed oops: mark 8B + klass 4B = 12B)
        out.add(new FieldInfo("(header: mark)",  0, 8, "header",  ""));
        out.add(new FieldInfo("(header: class)", 8, 4, "header",  ""));

        if (obj.getClass().isArray()) {
            // 配列: length (4B) と elements
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
        // JOL の name() は "MyClass.fieldName" 形式
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

    // ─── 内部データ ──────────────────────────────────────────────

    static final class Recorder {
        final LinkedHashMap<String, Object> tracked = new LinkedHashMap<>();
        final List<Snapshot> snapshots = new ArrayList<>();
    }

    record Snapshot(int step, String label, List<Region> regions) {}

    record Region(String name, String type, String repr,
                  long addr, long size, byte[] bytes, List<FieldInfo> fields) {}

    record FieldInfo(String name, long offset, int size, String kind, String type) {}
}
