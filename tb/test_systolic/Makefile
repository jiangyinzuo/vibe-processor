SIM ?= verilator
TOPLEVEL_LANG ?= verilog

VERILOG_SOURCES = \
    $(PWD)/../../rtl/pkg/ascend_pkg.sv \
    $(PWD)/../../rtl/cube/pe.sv \
    $(PWD)/../../rtl/cube/systolic_array.sv

TOPLEVEL = systolic_array
MODULE = test_systolic

EXTRA_ARGS += --trace --trace-structs
EXTRA_ARGS += -Wno-WIDTHEXPAND -Wno-WIDTHTRUNC -Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM

include $(shell cocotb-config --makefiles)/Makefile.sim
