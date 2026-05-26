#!/usr/bin/env bash
# run_point_demo.sh — end-to-end demo of the jmemviz preprocessor.
#
# Steps:
#   1. Build the fat jar if it does not exist yet.
#   2. Preprocess examples/PointDemo.java  → expand @jmemviz markers.
#   3. Compile the preprocessed source.
#   4. Run it                              → writes trace.json.
#   5. Serve the trace and open the viewer in your browser.
#
# Usage:
#   ./run_point_demo.sh [port]
#   port defaults to 8765.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/jmemviz-0.1.0-SNAPSHOT.jar"
JMEMVIZ="$SCRIPT_DIR/jmemviz"
POINT_DEMO_SRC="$SCRIPT_DIR/src/main/java/org/fukuchi/jmemviz/examples/PointDemo.java"
PORT="${1:-8765}"

# ── 1. Build jar if needed ───────────────────────────────────────────────
if [ ! -f "$JAR" ]; then
    echo "[run_point_demo] jar not found — building (this may take a minute)…"
    (cd "$SCRIPT_DIR" && mvn package -q -DskipTests)
fi

# ── 2. Set up a temporary working directory ─────────────────────────────
WORK_DIR="$(mktemp -d)"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

# javac requires the source file to live in the directory hierarchy that
# matches its package declaration (org.fukuchi.jmemviz.examples).
PKG_DIR="$WORK_DIR/src/org/fukuchi/jmemviz/examples"
mkdir -p "$PKG_DIR"

# ── 3. Preprocess ────────────────────────────────────────────────────────
echo "[run_point_demo] Preprocessing PointDemo.java…"
"$JMEMVIZ" preprocess "$POINT_DEMO_SRC" "$PKG_DIR/PointDemo.java"

# ── 4. Compile ───────────────────────────────────────────────────────────
echo "[run_point_demo] Compiling…"
mkdir -p "$WORK_DIR/classes"
javac -cp "$JAR" -d "$WORK_DIR/classes" "$PKG_DIR/PointDemo.java"

# ── 5. Run  (record() writes "trace.json" relative to CWD) ───────────────
echo "[run_point_demo] Running PointDemo (recording trace)…"
# classes/ must precede the jar so the preprocessed PointDemo takes priority
# over the unprocessed copy bundled inside the fat jar.
(cd "$WORK_DIR" && java -cp "$WORK_DIR/classes:$JAR" org.fukuchi.jmemviz.examples.PointDemo)
echo "[run_point_demo] trace written to $WORK_DIR/trace.json"

# ── 6. Serve ─────────────────────────────────────────────────────────────
echo "[run_point_demo] Serving at http://127.0.0.1:$PORT  (Ctrl+C to quit)"
"$JMEMVIZ" serve "$WORK_DIR/trace.json" "$PORT"
