package org.fukuchi.jmemviz;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * jmemviz demo (v1, CLI output only).
 *
 * Observes, via JOL, the difference in memory representation between
 * primitives such as int/double and wrapper types Integer/Double.
 */
public final class JmemvizDemo {

    public static void run() {
        banner("JVM details");
        System.out.println(VM.current().details());

        sectionA_primitives();
        sectionAprime_primitiveArrays();
        sectionB_integer();
        sectionC_double();
        sectionD_integerCache();
        sectionE_intArrayVsIntegerArray();
    }

    // ─── (A) A primitive by itself is not on the heap ─────────────
    private static void sectionA_primitives() {
        banner("(A) A primitive by itself has no heap presence");
        int    primInt    = 257;
        double primDouble = 3.14;
        System.out.printf("  int    = %d   → 4 bytes、レジスタ/スタック/フィールドにインライン%n", primInt);
        System.out.printf("  double = %s → 8 bytes、同上。ヘッダも GC も同一性もない%n", primDouble);
        System.out.println();
        System.out.println("  JOL の ClassLayout.parseInstance(...) は Object しか受け取らないので、");
        System.out.println("  プリミティブを単独で「見る」ことはできない。");
        System.out.println("  ただし配列やクラスフィールドに入れた瞬間、その容器の一部として");
        System.out.println("  ヒープ上にバイト列が並ぶ。次節で見る。");
    }

    // ─── (A') Primitives do appear on heap "inside containers" ─────
    private static void sectionAprime_primitiveArrays() {
        banner("(A') Primitives DO live in memory — inside arrays / objects");

        // int[]: 4-byte values are laid out consecutively after a 16B header.
        int[] xs = {257, 258, 259, 260};
        System.out.println("  int[] {257, 258, 259, 260} (= 0x101, 0x102, 0x103, 0x104):");
        System.out.println(ClassLayout.parseInstance(xs).toPrintable());
        printRawBytes("int[4]", xs);
        System.out.println("  16B 配列ヘッダ (mark + klass + length) の後ろに、");
        System.out.println("  値が 4 バイトずつリトルエンディアンで並ぶ:");
        System.out.println("    offset 16: 01 01 00 00 = 0x00000101 = 257");
        System.out.println("    offset 20: 02 01 00 00 = 0x00000102 = 258");
        System.out.println("    offset 24: 03 01 00 00 = 0x00000103 = 259");
        System.out.println("    offset 28: 04 01 00 00 = 0x00000104 = 260");
        System.out.println("  → Integer のような per-element ヘッダは存在しない。");
        System.out.println();

        // double[]: IEEE 754 values are laid out in 8-byte chunks.
        double[] ys = {1.0, 2.0, 3.14};
        System.out.println("  double[] {1.0, 2.0, 3.14}:");
        System.out.println(ClassLayout.parseInstance(ys).toPrintable());
        printRawBytes("double[3]", ys);
        System.out.println("  各値は IEEE 754 倍精度 (8 バイト) としてそのまま並ぶ:");
        System.out.println("    1.0  = 0x3ff0000000000000 → 00 00 00 00 00 00 f0 3f");
        System.out.println("    2.0  = 0x4000000000000000 → 00 00 00 00 00 00 00 40");
        System.out.println("    3.14 = 0x40091eb851eb851f → 1f 85 eb 51 b8 1e 09 40");
        System.out.println();

        // Inline layout as class fields.
        Point p = new Point(0x11111111, 3.14);
        System.out.println("  class Point { int x; double y; } のインスタンス:");
        System.out.println(ClassLayout.parseInstance(p).toPrintable());
        printRawBytes("Point", p);
        System.out.println("  ヘッダ 12B → int x (4B) → double y (8B、8B境界に既に揃っている)。");
        System.out.println("  x も y も Integer/Double のような「箱」になっていない。");
    }

    static final class Point {
        int x;
        double y;
        Point(int x, double y) { this.x = x; this.y = y; }
    }

    // ─── (B) Integer layout ────────────────────────────────────────
    private static void sectionB_integer() {
        banner("(B) Integer: 12B header + 4B value = 16 bytes");
        Integer boxedInt = newInteger(257);   // Explicitly use new to bypass cache.
        System.out.println(ClassLayout.parseInstance(boxedInt).toPrintable());
        printRawBytes("Integer(257)", boxedInt);
        System.out.println("  末尾 4 バイトをリトルエンディアンで読むと 257 = 0x00000101。");
    }

    // ─── (C) Double layout ─────────────────────────────────────────
    private static void sectionC_double() {
        banner("(C) Double: 12B header + 4B pad + 8B value = 24 bytes");
        Double boxedDouble = newDouble(3.14);
        System.out.println(ClassLayout.parseInstance(boxedDouble).toPrintable());
        printRawBytes("Double(3.14)", boxedDouble);
        System.out.println("  8 バイト境界に揃えるため値の手前に 4 バイトの padding が入る。");
    }

    // ─── (D) Integer cache ─────────────────────────────────────────
    private static void sectionD_integerCache() {
        banner("(D) Integer.valueOf cache: -128..127 are shared");

        Integer a1 = Integer.valueOf(100);
        Integer a2 = Integer.valueOf(100);
        Integer b1 = Integer.valueOf(200);
        Integer b2 = Integer.valueOf(200);

        System.out.printf("  valueOf(100) == valueOf(100) ? %-5s  (a1@0x%x, a2@0x%x)%n",
                a1 == a2, VM.current().addressOf(a1), VM.current().addressOf(a2));
        System.out.printf("  valueOf(200) == valueOf(200) ? %-5s  (b1@0x%x, b2@0x%x)%n",
                b1 == b2, VM.current().addressOf(b1), VM.current().addressOf(b2));
        System.out.println();
        System.out.println("  100 は IntegerCache.cache[] (-128..127) から共有される。");
        System.out.println("  200 はキャッシュ外なので valueOf するたびに new される。");
        System.out.println("  `Integer x = 100;` というオートボクシングも内部的には valueOf を呼ぶ。");
    }

    // ─── (E) int[] vs Integer[] ───────────────────────────────────
    private static void sectionE_intArrayVsIntegerArray() {
        banner("(E) int[N] vs Integer[N] — the cost of boxing in bulk");

        int n = 1000;
        int[]     primArr  = new int[n];
        Integer[] boxedArr = new Integer[n];
        for (int i = 0; i < n; i++) {
            primArr[i]  = i + 1000;  // 1000..1999, outside cache range.
            boxedArr[i] = i + 1000;  // Calls Integer.valueOf per element -> new allocation.
        }

        // Note: GraphLayout.parseInstance is Object... varargs.
        // Passing Integer[] directly expands it as "1000 roots",
        // so cast to (Object) to force "one array root".
        GraphLayout primGraph  = GraphLayout.parseInstance((Object) primArr);
        GraphLayout boxedGraph = GraphLayout.parseInstance((Object) boxedArr);
        long primTotal  = primGraph.totalSize();
        long boxedTotal = boxedGraph.totalSize();
        long boxedSelf  = ClassLayout.parseInstance(boxedArr).instanceSize();
        long boxedElems = boxedTotal - boxedSelf;

        System.out.printf("  int[%d]      reachable bytes: %6d%n", n, primTotal);
        System.out.printf("  Integer[%d]  reachable bytes: %6d   (× %.1f)%n",
                n, boxedTotal, (double) boxedTotal / primTotal);
        System.out.printf("    └─ array itself   : %6d bytes%n", boxedSelf);
        System.out.printf("    └─ 1000 Integers  : %6d bytes  (= %d × 16B)%n",
                boxedElems, boxedElems / 16);
        System.out.println();
        System.out.println("  int[]     : 16B 配列ヘッダ + 4B × N の値が連続して並ぶ。");
        System.out.println("  Integer[] : 配列に並ぶのは 4B の参照だけ。値本体 (16B/個) は");
        System.out.println("              ヒープのどこか別の場所に N 個ばらまかれる。");
        System.out.println();
        System.out.println("  int[] header:");
        System.out.println(ClassLayout.parseInstance(primArr).toPrintable());
        System.out.println("  Integer[] header (要素 4 バイトは値ではなく oop ポインタ):");
        System.out.println(ClassLayout.parseInstance(boxedArr).toPrintable());
    }

    // ─── helpers ───────────────────────────────────────────────────

    private static void banner(String title) {
        String line = "=".repeat(72);
        System.out.println();
        System.out.println(line);
        System.out.println("  " + title);
        System.out.println(line);
    }

    private static void printRawBytes(String label, Object obj) {
        long size = VM.current().sizeOf(obj);
        long addr = VM.current().addressOf(obj);
        byte[] bytes = new byte[(int) size];
        Unsafe u = unsafe();
        for (int i = 0; i < size; i++) {
            bytes[i] = u.getByte(obj, (long) i);
        }
        System.out.printf("  raw bytes of %s  (addr 0x%x, %d bytes):%n", label, addr, size);
        System.out.print("    ");
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0 && i % 8 == 0) System.out.print(" ");
            System.out.printf("%02x ", bytes[i] & 0xff);
        }
        System.out.println();
        System.out.println();
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Integer newInteger(int v) {
        return new Integer(v);   // Intentionally old API to avoid shared cache.
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Double newDouble(double v) {
        return new Double(v);
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
}
