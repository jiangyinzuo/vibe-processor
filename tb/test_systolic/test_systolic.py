import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge
import numpy as np

N = 4
DW = 8   # DATA_WIDTH
AW = 32  # ACC_WIDTH


def pack_weights(W):
    """Pack NxN int8 weight matrix into flat integer, row-major, LSB-first."""
    val = 0
    for k in range(N):
        for j in range(N):
            w = int(W[k][j]) & 0xFF
            val |= w << ((k * N + j) * DW)
    return val


def pack_act(act_row):
    """Pack N int8 activations into flat integer."""
    val = 0
    for k in range(N):
        a = int(act_row[k]) & 0xFF
        val |= a << (k * DW)
    return val


def unpack_results(val):
    """Unpack flat result into NxN int32 matrix."""
    C = np.zeros((N, N), dtype=np.int32)
    mask = (1 << AW) - 1
    for i in range(N):
        for j in range(N):
            raw = (val >> ((i * N + j) * AW)) & mask
            # Sign extend from 32 bits
            if raw >= (1 << (AW - 1)):
                raw -= (1 << AW)
            C[i][j] = raw
    return C


async def reset(dut):
    dut.rst_n.value = 0
    dut.start.value = 0
    dut.act_valid.value = 0
    dut.weight_flat.value = 0
    dut.act_flat.value = 0
    for _ in range(2):
        await RisingEdge(dut.clk)
    dut.rst_n.value = 1
    await RisingEdge(dut.clk)


async def run_matmul(dut, A, W):
    """Run C = A * W through the systolic array."""
    # Set weights
    dut.weight_flat.value = pack_weights(W)

    # Start
    dut.start.value = 1
    await RisingEdge(dut.clk)
    dut.start.value = 0

    # Wait for S_LOAD_WEIGHT -> S_COMPUTE
    await RisingEdge(dut.clk)

    # Feed skewed activations for 2N-1 cycles
    # At cycle t, act_in[k] = A[t-k][k] if 0 <= t-k < N, else 0
    feed_cycles = 2 * N - 1
    for t in range(feed_cycles):
        act = np.zeros(N, dtype=np.int8)
        for k in range(N):
            i = t - k
            if 0 <= i < N:
                act[k] = A[i][k]
        dut.act_flat.value = pack_act(act)
        dut.act_valid.value = 1
        await RisingEdge(dut.clk)

    dut.act_valid.value = 0
    dut.act_flat.value = 0

    # Wait for done
    for _ in range(N + 5):
        await RisingEdge(dut.clk)
        if dut.done.value == 1:
            break

    assert dut.done.value == 1, "Systolic array did not signal done"

    # Read results
    raw = dut.result_flat.value.integer
    return unpack_results(raw)


@cocotb.test()
async def test_identity_matmul(dut):
    """C = A * I should equal A"""
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    await reset(dut)

    A = np.array([[1, 2, 3, 4],
                  [5, 6, 7, 8],
                  [9, 10, 11, 12],
                  [13, 14, 15, 16]], dtype=np.int8)
    W = np.eye(N, dtype=np.int8)

    C = await run_matmul(dut, A, W)
    expected = A.astype(np.int32) @ W.astype(np.int32)

    dut._log.info(f"Result:\n{C}")
    dut._log.info(f"Expected:\n{expected}")
    assert np.array_equal(C, expected), f"Mismatch!\nGot:\n{C}\nExpected:\n{expected}"


@cocotb.test()
async def test_simple_matmul(dut):
    """I * W = W"""
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    await reset(dut)

    A = np.eye(N, dtype=np.int8)
    W = np.array([[2, 3, 4, 5],
                  [6, 7, 8, 9],
                  [10, 11, 12, 13],
                  [14, 15, 16, 17]], dtype=np.int8)

    C = await run_matmul(dut, A, W)
    expected = A.astype(np.int32) @ W.astype(np.int32)

    dut._log.info(f"Result:\n{C}")
    dut._log.info(f"Expected:\n{expected}")
    assert np.array_equal(C, expected), f"Mismatch!\nGot:\n{C}\nExpected:\n{expected}"


@cocotb.test()
async def test_general_matmul(dut):
    """General matrix multiplication"""
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

    C = await run_matmul(dut, A, W)
    expected = A.astype(np.int32) @ W.astype(np.int32)

    dut._log.info(f"Result:\n{C}")
    dut._log.info(f"Expected:\n{expected}")
    assert np.array_equal(C, expected), f"Mismatch!\nGot:\n{C}\nExpected:\n{expected}"
