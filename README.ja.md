# jmemviz

[Java Object Layout (JOL)](https://github.com/openjdk/jol) と
`sun.misc.Unsafe` を使って **JVM ヒープ上のオブジェクトの実体** を観察する
教育用ツール。

任意のコード片に `Jmemviz.track / snap` を埋め込んで実行し、各 snap 時点の
バイト列を JSON に記録。付属のローカルサーバ + ブラウザビューアで
**ステップごとの差分** をピンクハイライト表示。

## Requirements

- JDK 21
- Maven 3.8+
- Linux/macOS (Windows は未確認)

## Quick start

```bash
mvn package

# PointDemo を前処理・コンパイル・実行してビューアを開く:
./run_point_demo.sh
# → http://127.0.0.1:8765/ がブラウザで開く
```

`examples/` ディレクトリのサンプルを直接コンパイル・実行することもできる:

```bash
cd examples
JAR=../target/jmemviz-0.1.0-SNAPSHOT.jar
javac -cp $JAR RecordDemo.java
java  -cp .:$JAR RecordDemo              # trace.json を書き出す
../jmemviz serve trace.json
```

サブコマンド一覧:

```
jmemviz serve  [trace.json] [port]              # トレースをブラウザで配信
jmemviz preprocess <input.java> [output.java]   # @jmemviz マーカーを展開
```

## ソースプリプロセッサ

`jmemviz preprocess` を使うと、通常の Java ソースに `// @jmemviz` コメントを
書くだけで `track/snap` 呼び出しを自動注入できる。

### マーカー一覧

| マーカー | 場所 | 生成されるコード |
|---|---|---|
| `// @jmemviz record "path"` | 独立行 | `record("path", () -> {` |
| `// @jmemviz end` | 独立行 | `});` |
| `// @jmemviz snap "ラベル"` | 独立行 | `snap("ラベル");` |
| `// @jmemviz snap` | 独立行 | `snap("step N");` (連番) |
| `decl; // @jmemviz track` | 宣言行の末尾 | `track("変数名", 変数名);` を次行に注入 |
| `decl; // @jmemviz track 名前` | 宣言行の末尾 | `track("名前", 名前);` を次行に注入 |

`import static org.fukuchi.jmemviz.Jmemviz.*;` が未記載の場合は自動付与される。

### 例

```java
// PointDemo.java (マーカー付き — そのままコンパイル可, 録画は行わない)
public class PointDemo {
    public static void main(String[] args) {
        // @jmemviz record "trace.json"

        int[] xs = {257, 258, 259}; // @jmemviz track
        // @jmemviz snap "int[] xs = {257, 258, 259}"

        xs[0] = 0x99999999;
        // @jmemviz snap "xs[0] = 0x99999999"

        // @jmemviz end
    }
}
```

```bash
java -jar jmemviz.jar preprocess PointDemo.java PointDemo_out.java
```

生成された `PointDemo_out.java` の差分:

```java
import static org.fukuchi.jmemviz.Jmemviz.*;  // ← 自動付与
public class PointDemo {
    public static void main(String[] args) {
        record("trace.json", () -> {           // ← record() ラッパー

        int[] xs = {257, 258, 259};
        track("xs", xs);                       // ← track() 注入
        snap("int[] xs = {257, 258, 259}");    // ← snap() 展開

        xs[0] = 0x99999999;
        snap("xs[0] = 0x99999999");

        });                                    // ← end 展開
    }
}
```

> **Note**: マーカーの数を減らして「自動推論」する機能 (全ローカル変数を自動
> `track`、全 mutation 後に自動 `snap` など) は今後の拡張として検討中。

## 録画 API

```java
import static org.fukuchi.jmemviz.Jmemviz.*;

record("trace.json", () -> {
    int[] xs = {257, 258, 259, 260};
    track("xs", xs);
    snap("initial");

    xs[0] = 0x99999999;
    snap("after xs[0] = 0x99999999");
});
```

- `track(name, obj)` — 追跡対象を登録。同名で呼ぶと差し替え (再代入時用)。
  内部で `System.identityHashCode(obj)` を呼んで mark word を安定化する
  (観測自身が mark word を書換えてしまう Heisenberg 現象の回避)
- `snap(label)` — 現在追跡中の全オブジェクトのバイト列を JOL の `sizeOf` 分
  だけ `Unsafe.getByte` で読み、`label` 付きで snapshot に追加

`examples/RecordDemo.java` には現在以下のシナリオが入っている (全 11 ステップ):

1. `int[]` の要素書き換え → 配列ヘッダ後ろの該当 4B だけハイライト
2. `Integer` のボクシング + 再代入 → 別インスタンスになりバイト全体が一新
3. `Point { int x; int y; }` のフィールド書換 → 該当オフセットだけ変化
4. **`Rectangle { Point topLeft, bottomRight; }`** → Rectangle 本体には Point の
   値は入らず **4B の oop 参照が 2 本** 並ぶだけ。Point の中身を書き換えても
   Rectangle 本体は不変。`r.bottomRight = newPoint` で初めて Rectangle 内の
   参照フィールドのバイトが変化する

## デモが何を見せるか

### (A) プリミティブ単体はヒープ上のオブジェクトではない

`int` や `double` はスタック上の値 (またはクラスフィールドにインライン)
として存在し、ヘッダも GC も同一性もない。JOL は `Object` しか受け取ら
ないので、プリミティブを単独で見ることはできない。

### (A') ただし容器に入った瞬間、ヒープに raw バイトで並ぶ

```
int[] {257, 258, 259, 260}
raw bytes (32B):
  01 00 00 00 00 00 00 00   ← mark word
  08 27 00 00               ← klass ptr
  04 00 00 00               ← array length = 4
  01 01 00 00 02 01 00 00   ← 257, 258
  03 01 00 00 04 01 00 00   ← 259, 260
```

16B 配列ヘッダの直後に **4B 値が連続して並ぶ**。`Integer` のような
per-element ヘッダは無い。`double[]` も同様で、IEEE 754 倍精度が
8B ずつそのまま並ぶ (`1.0` = `00 00 00 00 00 00 f0 3f`)。

```
class Point { int x; double y; } のインスタンス (24B):
  01 00 00 00 00 00 00 00   ← mark word
  b0 fc 01 01               ← klass ptr
  11 11 11 11               ← Point.x  (= 0x11111111)
  1f 85 eb 51 b8 1e 09 40   ← Point.y  (= 3.14, IEEE 754)
```

クラスのフィールドとしても同じ。プリミティブは **「箱」** にならず
インスタンスの本体にインラインで畳み込まれる。

### (B) Integer は 16 バイトの「箱」

```
OFF  SZ   TYPE DESCRIPTION               VALUE
  0   8        (object header: mark)     0x0000000000000001
  8   4        (object header: class)    0x00025fd8
 12   4    int Integer.value             257
Instance size: 16 bytes
```

- 8 バイトの **mark word** (ハッシュ・ロック・GC age 等)
- 4 バイトの **klass pointer** (圧縮 oops 有効時)
- 4 バイトの **value** フィールド
- 合計 16 バイト (4 バイト整数を運ぶためだけに!)

raw バイト列を `Unsafe.getByte` で 16 バイト分読むと、末尾 4 バイトが
リトルエンディアンで `01 01 00 00` = 257 = `0x101` として確認できる。

### (C) Double は 24 バイト (padding 付き)

```
OFF  SZ     TYPE DESCRIPTION               VALUE
  0   8          (object header: mark)
  8   4          (object header: class)
 12   4          (alignment/padding gap)
 16   8   double Double.value              3.14
Instance size: 24 bytes
```

8 バイト境界に揃えるため、value の手前に 4 バイトの padding が
入る。Java の **ヘッダ + 値** モデルが整列要件で膨らむことを示す。

### (D) Integer キャッシュ

`Integer.valueOf(int)` は `-128..127` の範囲を静的キャッシュから返す。
そのため:

```
valueOf(100) == valueOf(100) ?  true   ← キャッシュ内
valueOf(200) == valueOf(200) ?  false  ← キャッシュ外、毎回 new
```

`Integer x = 100;` というオートボクシングも内部的には `valueOf` を呼ぶ
ので同じ挙動。アドレスを比較しているのではなく `==` で参照同一性を見て
いる点に注意。

### (E) int[N] vs Integer[N]: ボクシングの一括コスト

1000 要素の配列で比較すると:

```
int[1000]      reachable bytes:   4016
Integer[1000]  reachable bytes:  20016   (× 5.0)
  └─ array itself   :   4016 bytes
  └─ 1000 Integers  :  16000 bytes  (= 1000 × 16B)
```

- `int[]`: ヘッダ 16B + 値 4B × N が一塊。
- `Integer[]`: 配列に並ぶのは 4B の **参照だけ**。値本体 (16B/個) は
  ヒープのどこか別の場所にバラまかれる。**5倍** のメモリ + ポインタ
  追跡 + GC 圧力がかかる。

## トラブルシューティング

JOL が起動時にこんな warning を出す:

```
# WARNING: Unable to get Instrumentation. Dynamic Attach failed.
# WARNING | Compressed references base/shifts are guessed by the experiment!
```

これは Serviceability Agent にアタッチできないため。アドレス表示
(`addressOf`) は **推測値** になるが、レイアウト情報そのものは正確。
正確なアドレスが必要なら:

```bash
java -Djol.tryWithSudo=true -jar target/jmemviz-0.1.0-SNAPSHOT.jar
# または
echo 0 | sudo tee /proc/sys/kernel/yama/ptrace_scope
```

または `-javaagent:path/to/jol-core-0.17.jar` で起動する。

## 設計メモ / 落とし穴

- **varargs trap**: `GraphLayout.parseInstance` は `Object... roots` を
  取るため、`Integer[]` を素で渡すと「1000 個のルート」として展開
  されてしまい、配列自身がカウントから漏れる。`(Object) arr` キャストで
  「1個の配列ルート」に矯正する必要がある。`int[]` はプリミティブ配列
  なので varargs に展開されず問題なし。
- `sun.misc.Unsafe` を `Field.setAccessible(true)` 経由で取得している。
  JDK 21 では `jdk.unsupported` モジュールが自動解決されるので追加
  オプション不要。
- `Integer` を強制的にキャッシュ外から作るために、敢えて非推奨の
  `new Integer(int)` を使っている (`@SuppressWarnings`)。本番コードでは
  `Integer.valueOf(...)` を使うべき。

## アーキテクチャ

```
[ サンプルコード (例: examples/RecordDemo.java) ]
   Jmemviz.record(out, () -> { ... track(), snap() ... })
       │
       │   ・JOL ClassLayout で field 注釈を構築
       │   ・Unsafe.getByte(obj, offset) で raw バイト読出 (GC セーフ)
       ▼
[ trace.json ]   <- 自己完結した JSON スキーマ
       │
       ▼
[ JmemvizServer ]   com.sun.net.httpserver.HttpServer (JDK 同梱, 外部依存なし)
       │   GET /            → classpath:/viewer/index.html
       │   GET /trace.json  → 書き出した JSON
       │   Desktop.browse(URI) でブラウザ自動起動
       ▼
[ browser viewer ]   差分計算は JS 側 (prevBytes 比較)
```

設計判断の要点:

- **UI は Web ブラウザ**。小さな vanilla JS ビューア (フレームワーク・
  ビルド工程なし) で trace を描画する。Swing/JavaFX に比べてトレースが
  可搬な JSON として残るのも利点。
- **スナップショット起動は明示的 `snap(label)`**。Java には `sys.settrace`
  に相当する軽量フックが無い (JVMTI/JDI は重い)。教材用途では「教えたい
  瞬間だけ撮る」方が認知負荷が低い。
- **GC 対策**: `Unsafe.getByte(Object, long)` は HotSpot 内部で oop ハンドル
  を再解決するため GC 安全。アドレス表示 (`addressOf`) は best-effort で
  保存するが、オブジェクト同定には `name` を使う。
- **mark word の安定化**: `track()` 時に `System.identityHashCode(obj)` を
  呼んで hash を mark word に焼き込む。これをしないと、我々自身の
  `safeRepr()` (= `toString()` 経由で hashCode を生成) が観測中にヘッダを
  書換えてしまい、ステップ 0 → 1 で勝手にバイトが変わって見える。

## 今後の拡張余地

- **Java Agent (ASM) で自動撮影**: ユーザが `snap()` を挿入しなくても
  毎行スナップショットを撮れるようにする。v1 と JSON 互換なので後付け可能
- **トレースの diff モード**: 2 つの trace.json をビューアで並べる
- **JDK 内部構造の教材化**: `String` の `coder`/`hash` の展開を強化
  (`value` の byte[] は子 region として表示済み)、`ArrayList` の `elementData` も子 region として展開
- **Compact Object Headers (Lilliput, JDK 24+) 対応**: ヘッダサイズを
  ハードコードしている箇所 (`buildFields` の 8 + 4 = 12B) を JOL から
  動的取得するように

## ファイル

| ファイル | 役割 |
|---|---|
| `pom.xml` | Maven 設定 (JDK 21, JOL 0.17, shade plugin で fat jar) |
| `src/main/java/org/fukuchi/jmemviz/Main.java` | CLI dispatcher (`serve` / `preprocess`) |
| `src/main/java/org/fukuchi/jmemviz/Jmemviz.java` | 公開 API (`record/track/snap`) + Snapshotter |
| `src/main/java/org/fukuchi/jmemviz/Preprocessor.java` | ソースプリプロセッサ (`// @jmemviz` マーカー展開) |
| `src/main/java/org/fukuchi/jmemviz/TraceWriter.java` | JSON 書き出し (手書き、依存なし) |
| `src/main/java/org/fukuchi/jmemviz/JmemvizServer.java` | HttpServer + ブラウザ起動 |
| `src/main/resources/viewer/index.html` | ブラウザビューア (vanilla JS, 依存なし) |
| `examples/RecordDemo.java` | snap() を使った録画用デモ (jar 外、スタンドアロン) |
| `examples/PointDemo.java` | `// @jmemviz` マーカー付きサンプル入力 |
| `examples/LayoutDemo.java` | JOL を使ったコンソール向けレイアウト確認ツール |
