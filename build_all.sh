#!/usr/bin/env bash

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAVA_MODULES=(
  "MiniJava_Type_Checker:P2"
  "MiniJava_to_MiniIR:P3"
  "MiniIR_to_MicroIR:P4"
  "MicroIR_to_MiniRA:P5"
  "MiniRA_to_MIPS:P6"
)

echo "== Building MacroJava_To_MiniJava =="
P1_DIR="$ROOT/MacroJava_to_MiniJava"
(
  cd "$P1_DIR"
  bison -d -o P1.tab.c P1.y        
  flex  -o lex.yy.c P1.l           
  g++ -std=c++17 -O2 -o MacroJavaToMiniJava lex.yy.c P1.tab.c
)
echo "   -> $P1_DIR/MacroJavaToMiniJava"
echo

for entry in "${JAVA_MODULES[@]}"; do
  dir="${entry%%:*}"
  mainclass="${entry##*:}"
  mod_path="$ROOT/$dir"
  bin_path="$mod_path/bin"

  echo "== Building $dir =="
  rm -rf "$bin_path"
  mkdir -p "$bin_path"

  sources=()
  while IFS= read -r -d '' f; do
    sources+=("$f")
  done < <(find "$mod_path" -maxdepth 3 -name "*.java" -print0)
  javac -d "$bin_path" -cp "$mod_path" "${sources[@]}"

  echo "   -> $bin_path (run with: java -cp $bin_path $mainclass)"
  echo
done

echo "All modules built successfully."