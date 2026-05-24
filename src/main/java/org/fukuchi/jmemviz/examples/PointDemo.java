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

    public static void main(String[] args) {
        // @jmemviz record "trace.json"

        int[] xs = {257, 258, 259}; // @jmemviz track
        // @jmemviz snap "int[] xs = {257, 258, 259}"

        xs[0] = 0x99999999;
        // @jmemviz snap "xs[0] = 0x99999999"

        Point p = new Point(0x11111111, 314); // @jmemviz track
        // @jmemviz snap "Point p = new Point(0x11111111, 314)"

        p.x = 0x22222222;
        // @jmemviz snap "p.x = 0x22222222"

        // @jmemviz end
    }
}
