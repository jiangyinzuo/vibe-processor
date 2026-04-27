import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge
import numpy as np

N = 4
DW = 8
AW = 32


def pack_matrix_8(M):
    val = 0
    for i in range(N):
        for j in range(N):
            val |= (int(M[i][j]) & 0xFF) << ((i * N + j) * DW)
    return val


def unpack_matrix_32(val):
    C = np.zeros((N, N), dtype=np.int32)
    mask = (1 << AW) - 1
    for i in range(N):
        for j in range(N):
            raw = (val >> ((i * N + j) * AW)) & mask
            if raw >= (1 << (AW - 1)):
                raw -= (1 << AW)
            C[i][j] = raw
    return C


async def reset(dut):
    dut.rst_n.value = 0
    dut.start.value = 0
    dut.weight_flat.value = 0
    dut.act_flat.value = 0
    for _ in range(2):
        await RisingEdge(dut.clk)
    dut.rst_n.value = 1
    await RisingEdge(dut.clk)


async def run_cube_matmul(dut, A, W):
    """Run C = A * W through the cube unit."""
    dut.weight_flat.value = pack_matrix_8(W)
    dut.act_flat.value = pack_matrix_8(A)

    dut.start.value = 1
    await RisingEdge(dut.clk)
    dut.start.value = 0

    # Wait for done
    for _ in range(30):
        await RisingEdge(dut.clk)
        if dut.done.value == 1:
            break

    assert dut.done.value == 1, "Cube unit did not signal done"
    return unpack_matrix_32(dut.result_flat.value.integer)


@cocotb.test()
async def test_cube_identity(dut):
    """C = A * I"""
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    await reset(dut)

    A = np.array([[1, 2, 3, 4],
                  [5, 6, 7, 8],
                  [9, 10, 11, 12],
                  [13, 14, 15, 16]], dtype=np.int8)
    W = np.eye(N, dtype=np.int8)

    C = await run_cube_matmul(dut, A, W)
    expected = A.astype(np.int32) @ W.astype(np.int32)

    dut._log.info(f"Result:\n{C}")
    dut._log.info(f"Expected:\n{expected}")
    assert np.array_equal(C, expected), f"Mismatch!\n{C}\nvs\n{expected}"


@cocotb.test()
async def test_cube_general(dut):
    """General matmul through cube unit"""
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    await reset(dut)

    A = np.array([[1, 2, 3, 4],
                  [5, 6, 7, 8],
                  [2, 3, 1, 4],
                  [7, 1, 5, 3]], dtype=np.int8)
    W = np.array([[1, 0, 2, 1],
                  [3, 1, 0, 2],
                  [2, 4, 1, 3],
                  [0, 2, 3, 1]], dtype=np.int8)

    C = await run_cube_matmul(dut, A, W)
    expected = A.astype(np.int32) @ W.astype(np.int32)

    dut._log.info(f"Result:\n{C}")
    dut._log.info(f"Expected:\n{expected}")
    assert np.array_equal(C, expected), f"Mismatch!\n{C}\nvs\n{expected}"
