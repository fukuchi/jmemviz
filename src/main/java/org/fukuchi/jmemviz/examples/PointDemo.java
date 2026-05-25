package org.fukuchi.jmemviz.examples;

/**
 * プリプロセッサのサンプル入力ファイル。
 *
 * <p>このファイルはそのままコンパイル・実行できる (Jmemviz 呼び出しは何も行わない)。
 * {@code jmemviz preprocess} を実行すると、{@code // @jmemviz} マーカーが
 * 実際の API 呼び出しに展開された別ファイルが生成される。
 *
 * <pre>
 *   java -jar jmemviz.jar preprocess src/.../PointDemo.java /tmp/PointDemo_out.java
 * </pre>
 */
public class PointDemo {

    static class Point {
        int x;
        int y;
        Point(int x, int y) { this.x = x; this.y = y; }
        @Override public String toString() {
            return "Point{x=0x" + Integer.toHexString(x) + ", y=" + y + "}";
        }
    }

    static class Rectangle {
        Point topLeft;
        Point bottomRight;
        Rectangle(Point tl, Point br) { this.topLeft = tl; this.bottomRight = br; }
        @Override public String toString() {
            // topLeft/bottomRight の toString() を明示的に呼ぶ。
            // 文字列リテラル同士の "+" はコンパイル時定数畳み込みの対象だが、
            // ここはインスタンスメソッド呼び出しなので実行時に String が生成される。
            return "Rectangle{tl=" + topLeft.toString() + ", br=" + bottomRight.toString() + "}";
        }
    }

    public static void main(String[] args) {
        // @jmemviz record "trace.json"

        // ─── (1) int[] 要素の書き換え ─────────────────────────────
        int[] xs = {257, 258, 259}; // @jmemviz track
        // @jmemviz snap "int[] xs = {257, 258, 259}"

        xs[0] = 0x99999999;
        // @jmemviz snap "xs[0] = 0x99999999"

        // ─── (2) Point: インラインな int フィールドの書き換え ─────
        Point p = new Point(0x11111111, 314); // @jmemviz track
        // @jmemviz snap "Point p = new Point(0x11111111, 314)"

        p.x = 0x22222222;
        // @jmemviz snap "p.x = 0x22222222"

        // ─── (3) Rectangle: oop 参照フィールドの観察 ─────────────
        // Rectangle 本体には Point の値は入らず 4B の oop 参照が 2 本並ぶだけ。
        // topLeft の中身を書き換えても Rectangle 本体は変化しない。
        // r.bottomRight = newBr で初めて Rectangle 内の参照フィールドが変わる。
        Point tl = new Point(0x0a0a0a0a, 15); // @jmemviz track
        Point br = new Point(0xb0b0b0b0, 95); // @jmemviz track
        Rectangle r = new Rectangle(tl, br);  // @jmemviz track
        // @jmemviz snap "Rectangle r = new Rectangle(tl, br)"

        tl.x = 0x0c0c0c0c;
        // @jmemviz snap "tl.x 書き換え: tl のバイトが変わる、r 本体は不変"

        Point newBr = new Point(0xde000000, 42); // @jmemviz track
        r.bottomRight = newBr;
        // @jmemviz snap "r.bottomRight = newBr: r 内の参照フィールドが変化"

        // ─── (4) String: 連結すると新しいオブジェクトが生成される ─
        // s1 + ", World!" はコンパイル時定数畳み込みの対象にならない
        // (s1 がランタイム変数のため)。実行時に新しい String オブジェクトが
        // 作られ、s2 は s1 と異なるアドレスを持つ。
        String s1 = "Hello";          // @jmemviz track
        // @jmemviz snap "String s1 = \"Hello\""

        String s2 = s1 + ", World!";  // @jmemviz track
        // @jmemviz snap "s2 = s1 + \", World!\": 新しい String オブジェクト"

        // @jmemviz end
    }
}
