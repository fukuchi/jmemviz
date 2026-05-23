package org.fukuchi.jmemviz;

import static org.fukuchi.jmemviz.Jmemviz.*;

/**
 * Demo for record→serve→replay visualization. Unlike CLI-only {@link JmemvizDemo},
 * it places educational, diff-visible mutations between snap() calls.
 */
public final class RecordDemo {

    public static void run(String outPath) {
        record(outPath, () -> {

            // ─── (1) int[] element updates show clear diffs ──────────
            int[] xs = {257, 258, 259, 260};
            track("xs", xs);
            snap("int[] xs = {257, 258, 259, 260}");

            xs[0] = 0x99999999;
            snap("xs[0] = 0x99999999  (offset 16-19 が変化)");

            xs[3] = 0x12345678;
            snap("xs[3] = 0x12345678  (offset 28-31 が変化)");

            // ─── (2) Integer boxing and reassignment ─────────────────
            Integer boxed = newInteger(257);
            track("boxed", boxed);
            snap("Integer boxed = new Integer(257)");

            boxed = newInteger(258);   // New object -> addr/bytes are refreshed.
            track("boxed", boxed);
            snap("boxed = new Integer(258)  (別の Integer インスタンス)");

            // ─── (3) Point: inline updates of int/int fields
            Point p = new Point(0x11111111, 314);
            track("p", p);
            snap("Point p = new Point(0x11111111, 314)");

            p.x = 0x22222222;
            snap("p.x = 0x22222222  (offset 12-15 だけ変わる)");

            p.y = 1;
            snap("p.y = 1  (offset 16-19 だけ変わる)");

            // ─── (4) Rectangle: composite object with two Point refs ──
            // Rectangle itself does not contain Point values directly.
            // With compressed oops, only two 4B refs follow a 12B header
            // (20B total, aligned to 24B on an 8B boundary).
            Point tl = new Point(0x0a0a0a0a, 15);
            Point br = new Point(0xb0b0b0b0, 95);
            Rectangle r = new Rectangle(tl, br);
            track("r", r);
            track("r.topLeft", tl);
            track("r.bottomRight", br);
            snap("Rectangle r = new Rectangle(tl, br)  (3 オブジェクト)");

            tl.x = 0x0c0c0c0c;
            snap("tl.x を書き換え → tl のバイトだけ変わる。r 本体は不変");

            // Create a new Point and replace bottomRight.
            // Bytes of the second oop field inside r will change.
            Point newBr = new Point(0xde000000, 42);
            r.bottomRight = newBr;
            track("r.bottomRight", newBr);   // Replace tracked target as well.
            snap("r.bottomRight = new Point(...)  (r 内の参照フィールドが変化)");
        });
    }

    static final class Point {
        int x;
        int y;
        Point(int x, int y) { this.x = x; this.y = y; }
        @Override public String toString() {
            return "Point{x=0x" + Integer.toHexString(x) + ", y=" + y + "}";
        }
    }

    static final class Rectangle {
        Point topLeft;
        Point bottomRight;
        Rectangle(Point tl, Point br) { this.topLeft = tl; this.bottomRight = br; }
        @Override public String toString() {
            return "Rectangle{tl=" + topLeft + ", br=" + bottomRight + "}";
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static Integer newInteger(int v) {
        // Intentionally use old API to reliably bypass IntegerCache,
        // so learners can observe "new on every call".
        return new Integer(v);
    }
}
