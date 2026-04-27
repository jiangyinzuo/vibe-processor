SIM ?= verilator
TOPLEVEL_LANG ?= verilog

VERILOG_SOURCES = \
    $(PWD)/../../rtl/pkg/ascend_pkg.sv \
    $(PWD)/../../rtl/cube/pe.sv \
    $(PWD)/../../rtl/cube/systolic_array.sv \
    $(PWD)/../../rtl/memory/l0_buffer.sv \
    $(PWD)/../../rtl/memory/unified_buffer.sv \
    $(PWD)/../../rtl/memory/instr_mem.sv \
    $(PWD)/../../rtl/core/vector_unit.sv \
    $(PWD)/../../rtl/core/cube_unit.sv \
    $(PWD)/../../rtl/core/scalar_unit.sv \
    $(PWD)/../../rtl/core/ai_core.sv \
    $(PWD)/../../rtl/top/toy_ascend_top.sv

TOPLEVEL = toy_ascend_top
MODULE = test_integration

EXTRA_ARGS += --trace --trace-structs
EXTRA_ARGS += -Wno-WIDTHEXPAND -Wno-WIDTHTRUNC -Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM

include $(shell cocotb-config --makefiles)/Makefile.sim
