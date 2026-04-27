SIM ?= verilator
TOPLEVEL_LANG ?= verilog

VERILOG_SOURCES = \
    $(PWD)/../../rtl/pkg/ascend_pkg.sv \
    $(PWD)/../../rtl/cube/pe.sv

TOPLEVEL = pe
MODULE = test_pe

EXTRA_ARGS += --trace --trace-structs
EXTRA_ARGS += -Wno-WIDTHEXPAND -Wno-WIDTHTRUNC -Wno-UNUSEDSIGNAL -Wno-UNUSEDPARAM

include $(shell cocotb-config --makefiles)/Makefile.sim
