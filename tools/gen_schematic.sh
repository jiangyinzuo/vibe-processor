#!/bin/bash
# Generate RTL schematics using Yosys + netlistsvg
# Uses Yosys-compatible Verilog from generated/yosys/ (no local variables)
# Usage: bash tools/gen_schematic.sh [module_name [dep1 dep2 ...]]

set -e

GENERATED_DIR="generated/yosys"
OUTPUT_DIR="docs/schematics"
mkdir -p "$OUTPUT_DIR"

SV_FILES=$(ls "$GENERATED_DIR"/*.sv 2>/dev/null)
if [ -z "$SV_FILES" ]; then
    echo "No SV files in $GENERATED_DIR/. Run: sbt 'runMain ascend.Elaborate'"
    exit 1
fi

gen_schematic() {
    local module=$1
    shift
    local deps="$@"
    local json="$OUTPUT_DIR/${module}.json"
    local svg="$OUTPUT_DIR/${module}.svg"

    echo "=== Generating schematic for $module ==="

    local module_file="$GENERATED_DIR/${module}.sv"
    if [ ! -f "$module_file" ]; then
        echo "  $module_file not found, skipping"
        return 1
    fi

    local read_cmds="read_verilog -sv $module_file;"
    for dep in $deps; do
        local dep_file="$GENERATED_DIR/${dep}.sv"
        if [ -f "$dep_file" ]; then
            read_cmds="$read_cmds read_verilog -sv $dep_file;"
        fi
    done

    if ! yosys -q -p "$read_cmds hierarchy -top $module; proc; opt; write_json $json" 2>&1; then
        echo "  Yosys failed for $module, skipping"
        return 1
    fi

    netlistsvg "$json" -o "$svg" 2>&1
    rm -f "$json"

    if [ -f "$svg" ]; then
        echo "  Generated $svg ($(du -h "$svg" | cut -f1))"
    else
        echo "  netlistsvg failed for $module"
        return 1
    fi
}

if [ -n "$1" ]; then
    gen_schematic "$@"
else
    gen_schematic PE || true
    gen_schematic VectorUnit || true
    gen_schematic CubeUnit PE SystolicArray || true
fi

echo ""
echo "Done. Schematics in $OUTPUT_DIR/"
