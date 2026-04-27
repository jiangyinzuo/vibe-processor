"""Integration test: load program, preload data, run to HALT, check results."""
import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge
import numpy as np
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', 'tools'))
from asm import assemble

N = 4
DW = 8
AW = 32
WORD_BITS = N * AW  # 128 bits per UB word


def pack_row_as_ub_word(row_int8):
    """Pack a row of N int8 values into a 128-bit UB word.
    Each element occupies ACC_WIDTH (32) bits, sign-extended."""
    val = 0
    for j in range(N):
        x = int(row_int8[j]) & ((1 << AW) - 1)
        val |= x << (j * AW)
    return val


def unpack_ub_word(val):
    """Unpack a 128-bit UB word into N int32 values."""
    mask = (1 << AW) - 1
    result = np.zeros(N, dtype=np.int32)
    for j in range(N):
        raw = (val >> (j * AW)) & mask
        if raw >= (1 << (AW - 1)):
            raw -= (1 << AW)
        result[j] = raw
    return result


async def reset(dut):
    dut.rst_n.value = 0
    dut.start.value = 0
    dut.imem_load_en.value = 0
    dut.ub_ext_en.value = 0
    dut.ub_ext_we.value = 0
    for _ in range(3):
        await RisingEdge(dut.clk)
    dut.rst_n.value = 1
    await RisingEdge(dut.clk)


async def load_program(dut, instructions):
    """Load instructions into instruction memory."""
    for i, instr in enumerate(instructions):
        dut.imem_load_en.value = 1
        dut.imem_load_addr.value = i
        dut.imem_load_data.value = instr
        await RisingEdge(dut.clk)
    dut.imem_load_en.value = 0
    await RisingEdge(dut.clk)


async def write_ub(dut, addr, data):
    """Write a 128-bit word to UB via external port."""
    dut.ub_ext_en.value = 1
    dut.ub_ext_we.value = 1
    dut.ub_ext_addr.value = addr
    dut.ub_ext_wdata.value = data
    await RisingEdge(dut.clk)
    dut.ub_ext_en.value = 0
    dut.ub_ext_we.value = 0
    await RisingEdge(dut.clk)


async def read_ub(dut, addr):
    """Read a 128-bit word from UB via external port."""
    dut.ub_ext_en.value = 1
    dut.ub_ext_we.value = 0
    dut.ub_ext_addr.value = addr
    await RisingEdge(dut.clk)
    dut.ub_ext_en.value = 0
    await RisingEdge(dut.clk)
    return dut.ub_ext_rdata.value.integer


@cocotb.test()
async def test_matmul_program(dut):
    """Full integration: LOAD A, LOAD W, MATMUL, STORE, check C = A*W"""
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    await reset(dut)

    # Test matrices
    A = np.array([[1, 2, 3, 4],
                  [5, 6, 7, 8],
                  [2, 3, 1, 4],
                  [7, 1, 5, 3]], dtype=np.int8)
    W = np.array([[1, 0, 2, 1],
                  [3, 1, 0, 2],
                  [2, 4, 1, 3],
                  [0, 2, 3, 1]], dtype=np.int8)
    expected = A.astype(np.int32) @ W.astype(np.int32)

    # Assemble program
    program_src = """\
LOAD  L0_B, 0, 0
LOAD  L0_A, 0, 4
MATMUL
STORE L0_C, 0, 8
HALT
"""
    instructions = assemble(program_src)
    dut._log.info(f"Program: {len(instructions)} instructions")
    for i, instr in enumerate(instructions):
        dut._log.info(f"  [{i}] 0x{instr:08X}")

    # Load program
    await load_program(dut, instructions)

    # Preload A into UB[0..3]
    for i in range(N):
        await write_ub(dut, i, pack_row_as_ub_word(A[i]))

    # Preload W into UB[4..7]
    for i in range(N):
        await write_ub(dut, 4 + i, pack_row_as_ub_word(W[i]))

    # Start execution
    dut.start.value = 1
    await RisingEdge(dut.clk)
    dut.start.value = 0

    # Wait for HALT
    for cycle in range(500):
        await RisingEdge(dut.clk)
        if dut.halted.value == 1:
            dut._log.info(f"Halted after {cycle+1} cycles")
            break
    else:
        assert False, "Did not halt within 500 cycles"

    # Read results from UB[8..11]
    C = np.zeros((N, N), dtype=np.int32)
    for i in range(N):
        raw = await read_ub(dut, 8 + i)
        C[i] = unpack_ub_word(raw)

    dut._log.info(f"A:\n{A}")
    dut._log.info(f"W:\n{W}")
    dut._log.info(f"Result C:\n{C}")
    dut._log.info(f"Expected:\n{expected}")

    assert np.array_equal(C, expected), \
        f"Matrix multiply result mismatch!\nGot:\n{C}\nExpected:\n{expected}"


@cocotb.test()
async def test_nop_halt(dut):
    """Simplest program: NOP then HALT"""
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    await reset(dut)

    instructions = assemble("NOP\nHALT\n")
    await load_program(dut, instructions)

    dut.start.value = 1
    await RisingEdge(dut.clk)
    dut.start.value = 0

    for cycle in range(50):
        await RisingEdge(dut.clk)
        if dut.halted.value == 1:
            dut._log.info(f"NOP+HALT: halted after {cycle+1} cycles")
            break
    else:
        assert False, "Did not halt within 50 cycles"
