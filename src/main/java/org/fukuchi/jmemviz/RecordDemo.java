package org.fukuchi.jmemviz;

import static org.fukuchi.jmemviz.Jmemviz.*;

/**
 * record→serve→replay 可視化用のデモ。CLI 専用の {@link JmemvizDemo} と違い、
 * snap() の合間に教材的に意味のある「差分が見える」ミューテーションを並べる。
 */
public final class RecordDemo {

    public static void run(String outPath) {
        record(outPath, () -> {

            // ─── (1) int[] の要素書き換えで差分が見える ──────────────
            int[] xs = {257, 258, 259, 260};
            track("xs", xs);
            snap("int[] xs = {257, 258, 259, 260}");

            xs[0] = 0x99999999;
            snap("xs[0] = 0x99999999  (offset 16-19 が変化)");

            xs[3] = 0x12345678;
            snap("xs[3] = 0x12345678  (offset 28-31 が変化)");

            // ─── (2) Integer のボクシングと再代入 ────────────────────
            Integer boxed = newInteger(257);
            track("boxed", boxed);
            snap("Integer boxed = new Integer(257)");

            boxed = newInteger(258);   // 新オブジェクト → addr / bytes が一新
            track("boxed", boxed);
            snap("boxed = new Integer(258)  (別の Integer インスタンス)");

            // ─── (3) Point: int / double フィールドのインライン書き換え
            Point p = new Point(0x11111111, 3.14);
            track("p", p);
            snap("Point p = new Point(0x11111111, 3.14)");

            p.x = 0x22222222;
            snap("p.x = 0x22222222  (offset 12-15 だけ変わる)");

            p.y = 1.0;
            snap("p.y = 1.0  (offset 16-23 が IEEE754 で 1.0 に)");

            // ─── (4) Rectangle: Point への参照を 2 本持つ複合オブジェクト ──
            // Rectangle 本体には Point 値そのものは入らない。圧縮 oops 有効時、
            // ヘッダ 12B の直後に 4B の参照が 2 本並ぶだけ (合計 20B、8B 境界で 24B)
            Point tl = new Point(0x0a0a0a0a, 1.5);
            Point br = new Point(0xb0b0b0b0, 9.5);
            Rectangle r = new Rectangle(tl, br);
            track("r", r);
            track("r.topLeft", tl);
            track("r.bottomRight", br);
            snap("Rectangle r = new Rectangle(tl, br)  (3 オブジェクト)");

            tl.x = 0x0c0c0c0c;
            snap("tl.x を書き換え → tl のバイトだけ変わる。r 本体は不変");

            // 新しい Point を作って bottomRight に差し替え。
            // r 本体の 2 本目の oop フィールドのバイトが変わる。
            Point newBr = new Point(0xde000000, 42.0);
            r.bottomRight = newBr;
            track("r.bottomRight", newBr);   // 追跡対象も差し替え
            snap("r.bottomRight = new Point(...)  (r 内の参照フィールドが変化)");
        });
    }

    static final class Point {
        int x;
        double y;
        Point(int x, double y) { this.x = x; this.y = y; }
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
        // IntegerCache を確実に避けるため敢えて旧 API。生徒に "毎回 new" を見せる。
        return new Integer(v);
    }
}
