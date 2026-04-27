import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge
import numpy as np

N = 4
AW = 32


def pack_vec(v):
    val = 0
    for i in range(N):
        x = int(v[i]) & ((1 << AW) - 1)
        val |= x << (i * AW)
    return val


def unpack_vec(val):
    mask = (1 << AW) - 1
    result = np.zeros(N, dtype=np.int32)
    for i in range(N):
        raw = (val >> (i * AW)) & mask
        if raw >= (1 << (AW - 1)):
            raw -= (1 << AW)
        result[i] = raw
    return result


async def reset(dut):
    dut.rst_n.value = 0
    dut.start.value = 0
    dut.op.value = 0
    dut.src1_flat.value = 0
    dut.src2_flat.value = 0
    for _ in range(2):
        await RisingEdge(dut.clk)
    dut.rst_n.value = 1
    await RisingEdge(dut.clk)


@cocotb.test()
async def test_vecadd(dut):
    """Test vector addition"""
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    await reset(dut)

    a = np.array([10, 20, -5, 100], dtype=np.int32)
    b = np.array([3, -7, 15, -50], dtype=np.int32)

    dut.src1_flat.value = pack_vec(a)
    dut.src2_flat.value = pack_vec(b)
    dut.op.value = 0  # VECADD
    dut.start.value = 1
    await RisingEdge(dut.clk)
    dut.start.value = 0
    await RisingEdge(dut.clk)

    assert dut.done.value == 1
    result = unpack_vec(dut.dst_flat.value.integer)
    expected = a + b

    dut._log.info(f"VECADD: {a} + {b} = {result} (expected {expected})")
    assert np.array_equal(result, expected)


@cocotb.test()
async def test_relu_positive(dut):
    """Test ReLU with mixed positive/negative values"""
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    await reset(dut)

    a = np.array([10, -20, 0, -1], dtype=np.int32)

    dut.src1_flat.value = pack_vec(a)
    dut.src2_flat.value = 0
    dut.op.value = 1  # RELU
    dut.start.value = 1
    await RisingEdge(dut.clk)
    dut.start.value = 0
    await RisingEdge(dut.clk)

    assert dut.done.value == 1
    result = unpack_vec(dut.dst_flat.value.integer)
    expected = np.maximum(a, 0)

    dut._log.info(f"RELU({a}) = {result} (expected {expected})")
    assert np.array_equal(result, expected)


@cocotb.test()
async def test_relu_all_negative(dut):
    """Test ReLU with all negative values"""
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    await reset(dut)

    a = np.array([-1, -100, -50, -3], dtype=np.int32)

    dut.src1_flat.value = pack_vec(a)
    dut.src2_flat.value = 0
    dut.op.value = 1  # RELU
    dut.start.value = 1
    await RisingEdge(dut.clk)
    dut.start.value = 0
    await RisingEdge(dut.clk)

    assert dut.done.value == 1
    result = unpack_vec(dut.dst_flat.value.integer)
    expected = np.zeros(N, dtype=np.int32)

    dut._log.info(f"RELU({a}) = {result} (expected {expected})")
    assert np.array_equal(result, expected)
