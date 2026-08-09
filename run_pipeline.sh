#!/usr/bin/env bash

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

INPUT="${1:?Usage: $0 <input_file> [output_dir] [--from STAGE] [--to STAGE]}"
shift
OUTDIR="${1:-$ROOT/run_output}"
[[ $# -gt 0 && "$1" != --* ]] && shift || true

FROM="p1"
TO="p6"
GATE_ON_TYPECHECK=true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --from) FROM="$2"; shift 2 ;;
    --to)   TO="$2";   shift 2 ;;
    --no-typecheck-gate) GATE_ON_TYPECHECK=false; shift ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

mkdir -p "$OUTDIR"

P1_BIN="$ROOT/MacroJava_to_MiniJava/MacroJavaToMiniJava"
P2_CP="$ROOT/MiniJava_Type_Checker/bin"
P3_CP="$ROOT/MiniJava_to_MiniIR/bin"
P4_CP="$ROOT/MiniIR_to_MicroIR/bin"
P5_CP="$ROOT/MicroIR_to_MiniRA/bin"
P6_CP="$ROOT/MiniRA_to_MIPS/bin"

STAGES=(p1 p2 p3 p4 p5 p6)
run_stage() {
  local stage="$1"
  for s in "${STAGES[@]}"; do :; done
  local start=false skip=true
  for s in "${STAGES[@]}"; do
    [[ "$s" == "$FROM" ]] && skip=false
    if [[ "$s" == "$stage" ]]; then
      [[ "$skip" == false ]] && { echo "run"; return; }
      echo "skip"; return
    fi
    [[ "$s" == "$TO" ]] && skip=true
  done
}

cur="$INPUT"
step() {
  local name="$1" desc="$2" outfile="$3"; shift 3
  if [[ "$(run_stage "$name")" == "run" ]]; then
    echo "== $name: $desc =="
    "$@" < "$cur" > "$outfile"
    cur="$outfile"
    echo "   -> $outfile"
  fi
}

side_check() {
  local name="$1" desc="$2" outfile="$3"; shift 3
  if [[ "$(run_stage "$name")" == "run" ]]; then
    echo "== $name: $desc =="
    "$@" < "$cur" > "$outfile" || true
    echo "   -> $outfile"
    if grep -qiv "type checked successfully" "$outfile" 2>/dev/null; then
      echo "   TYPE ERROR: type checker did not report success:"
      sed 's/^/     /' "$outfile"
      if [[ "$GATE_ON_TYPECHECK" == true ]]; then
        echo "   Aborting pipeline (pass --no-typecheck-gate to continue anyway)."
        exit 1
      fi
    fi
  fi
}

step       p1 "MacroJava -> MiniJava" "$OUTDIR/minijava.java"    "$P1_BIN"
side_check p2 "MiniJava type check"   "$OUTDIR/typecheck.txt"    java -cp "$P2_CP" P2
step       p3 "MiniJava -> MiniIR"    "$OUTDIR/miniIR.miniIR"    java -cp "$P3_CP" P3
step       p4 "MiniIR -> MicroIR"     "$OUTDIR/microIR.microIR"  java -cp "$P4_CP" P4
step       p5 "MicroIR -> MiniRA"     "$OUTDIR/miniRA.miniRA"    java -cp "$P5_CP" P5
step       p6 "MiniRA -> MIPS"        "$OUTDIR/MIPS.s"           java -cp "$P6_CP" P6

echo
echo "Pipeline finished. Final artifact: $cur"