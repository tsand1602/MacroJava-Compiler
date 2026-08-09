# MacroJava Compiler

A compiler pipeline that translates **MacroJava programs into MIPS assembly** through a sequence of intermediate representations.

More information about intermediate representations can be found [here](https://www.cse.iitm.ac.in/~krishna/cs3300/subsets.html).

## Overview

The compiler consists of six stages:

```text
MacroJava
   ↓
MiniJava
   ↓
Type Checking
   ↓
MiniIR
   ↓
MicroIR
   ↓
MiniRA
   ↓
MIPS Assembly
```

### Compilation Stages

1. **MacroJava → MiniJava**
   
   Processes macros and translates the input MacroJava program into MiniJava.

2. **MiniJava Type Checking**
   
   Performs type checking on the generated MiniJava program. The pipeline stops if type checking fails.

3. **MiniJava → MiniIR**
   
   Converts the type-checked MiniJava program into MiniIR.

4. **MiniIR → MicroIR**
   
   Lowers MiniIR into MicroIR.

5. **MicroIR → MiniRA**
   
   Converts MicroIR into MiniRA.

6. **MiniRA → MIPS**
   
   Generates the final MIPS assembly code.

The build script compiles the MacroJava frontend using **Flex/Bison and C++17**, and compiles the remaining Java-based compiler stages using `javac`.

## Requirements

The following tools should be available:

* Bash
* `g++` with C++17 support
* Flex
* Bison
* Java JDK (`javac` and `java`)

## Running the Compiler

Clone the repository and enter the project directory:

```bash
git clone https://github.com/tsand1602/MacroJava_Compiler.git
cd MacroJava_Compiler
```

### 1. Build all compiler stages

Run:

```bash
./build_all.sh
```

This builds the MacroJava frontend and all subsequent compiler stages.

### 2. Run the complete pipeline

For example, to compile `BinarySearch.java`:

```bash
./run_pipeline.sh sample_inputs/BinarySearch.java
```

The pipeline runs all six stages automatically and stores the generated files in the `run_output/` directory by default.

The final output is:

```text
run_output/MIPS.s
```

Intermediate outputs include:

```text
run_output/minijava.java
run_output/typecheck.txt
run_output/miniIR.miniIR
run_output/microIR.microIR
run_output/miniRA.miniRA
run_output/MIPS.s
```

## Example

```bash
./build_all.sh
./run_pipeline.sh sample_inputs/BinarySearch.java
```

After successful compilation, the final MIPS assembly can be found at:

```text
run_output/MIPS.s
```

## Pipeline Options

The pipeline script also supports running only selected stages:

```bash
./run_pipeline.sh <input_file> [output_dir] [--from STAGE] [--to STAGE]
```

For example:

```bash
./run_pipeline.sh sample_inputs/minijava.java run_output --from p3 --to p6
```

By default, the pipeline performs type checking and stops if the type checker reports an error. The type-checking gate can be disabled with:

```bash
./run_pipeline.sh sample_inputs/BinarySearch.java --no-typecheck-gate
```
