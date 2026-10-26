#!/usr/bin/env bash
# run_record_demo.sh — end-to-end demo of the jmemviz recording API.
#
# Steps:
#   1. Build the fat jar if it does not exist yet.
#   2. Compile examples/RecordDemo.java.
#   3. Run it                              → writes trace.json.
#   4. Serve the trace and open the viewer in your browser.
#
# Usage:
#   ./run_record_demo.sh [port]
#   port defaults to 8765.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JMEMVIZ="$SCRIPT_DIR/jmemviz"
EXAMPLES_DIR="$SCRIPT_DIR/examples"
PORT="${1:-8765}"

# ── Helper: find the shaded fat jar ─────────────────────────────────────
find_jar() {
    find "$SCRIPT_DIR/target" -maxdepth 1 -name 'jmemviz-*.jar' \
         ! -name 'original-*' 2>/dev/null | head -1
}

# ── 1. Build jar if needed ───────────────────────────────────────────────
if [ -z "$(find_jar)" ]; then
    echo "[run_record_demo] jar not found — building (this may take a minute)…"
    (cd "$SCRIPT_DIR" && mvn package -q -DskipTests)
fi

JAR="$(find_jar)"
if [ -z "$JAR" ]; then
    echo "[run_record_demo] Build failed: no jar found in $SCRIPT_DIR/target/" >&2
    exit 1
fi

# ── 2. Set up a temporary working directory ─────────────────────────────
WORK_DIR="$(mktemp -d)"
cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

# ── 3. Compile ───────────────────────────────────────────────────────────
echo "[run_record_demo] Compiling RecordDemo.java…"
mkdir -p "$WORK_DIR/classes"
javac -cp "$JAR" -d "$WORK_DIR/classes" "$EXAMPLES_DIR/RecordDemo.java"

# ── 4. Run  (RecordDemo reads its source from CWD for the source view) ──
echo "[run_record_demo] Running RecordDemo (recording trace)…"
(cd "$EXAMPLES_DIR" && java -cp "$WORK_DIR/classes:$JAR" RecordDemo "$WORK_DIR/trace.json")
echo "[run_record_demo] trace written to $WORK_DIR/trace.json"

# ── 5. Serve ─────────────────────────────────────────────────────────────
echo "[run_record_demo] Serving at http://127.0.0.1:$PORT  (Ctrl+C to quit)"
"$JMEMVIZ" serve "$WORK_DIR/trace.json" "$PORT"
