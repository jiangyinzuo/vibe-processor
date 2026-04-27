import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge


async def reset(dut):
    dut.rst_n.value = 0
    dut.weight_load.value = 0
    dut.weight_in.value = 0
    dut.data_in.value = 0
    dut.psum_in.value = 0
    for _ in range(2):
        await RisingEdge(dut.clk)
    dut.rst_n.value = 1
    await RisingEdge(dut.clk)


@cocotb.test()
async def test_pe_mac(dut):
    """Test basic MAC operation: psum_out = psum_in + weight * data"""
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    await reset(dut)

    # Load weight = 3
    dut.weight_load.value = 1
    dut.weight_in.value = 3
    await RisingEdge(dut.clk)
    dut.weight_load.value = 0

    # Feed data_in = 5, psum_in = 10
    dut.data_in.value = 5
    dut.psum_in.value = 10
    await RisingEdge(dut.clk)

    # Clear inputs
    dut.data_in.value = 0
    dut.psum_in.value = 0
    await RisingEdge(dut.clk)

    # Check: psum_out should be 10 + 3*5 = 25
    assert dut.psum_out.value.signed_integer == 25, \
        f"Expected 25, got {dut.psum_out.value.signed_integer}"
    # data_out should be 5 (delayed by 1 cycle)
    assert dut.data_out.value.signed_integer == 5, \
        f"Expected data_out=5, got {dut.data_out.value.signed_integer}"


@cocotb.test()
async def test_pe_data_passthrough(dut):
    """Test that data flows through with 1-cycle delay"""
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    await reset(dut)

    # Drive values on consecutive cycles, sample output 1 cycle later
    test_values = [10, 20, 30, 40]
    for i, v in enumerate(test_values):
        dut.data_in.value = v
        await RisingEdge(dut.clk)
        # After this edge, the PE registers the input from the previous cycle
        if i > 0:
            got = dut.data_out.value.signed_integer
            expected = test_values[i - 1]
            assert got == expected, \
                f"Cycle {i}: expected data_out={expected}, got {got}"

    # One more cycle to check the last value
    dut.data_in.value = 0
    await RisingEdge(dut.clk)
    got = dut.data_out.value.signed_integer
    assert got == test_values[-1], \
        f"Final: expected data_out={test_values[-1]}, got {got}"


@cocotb.test()
async def test_pe_accumulate_sequence(dut):
    """Test accumulation over multiple cycles"""
    cocotb.start_soon(Clock(dut.clk, 10, units="ns").start())
    await reset(dut)

    # Load weight = 2
    dut.weight_load.value = 1
    dut.weight_in.value = 2
    await RisingEdge(dut.clk)
    dut.weight_load.value = 0

    # Feed sequence: data=1,2,3 with psum chaining
    expected_psums = []
    psum = 0
    for d in [1, 2, 3]:
        dut.data_in.value = d
        dut.psum_in.value = psum
        await RisingEdge(dut.clk)
        psum = psum + 2 * d
        expected_psums.append(psum)

    dut.data_in.value = 0
    dut.psum_in.value = 0
    await RisingEdge(dut.clk)

    # After feeding 3 values with weight=2: 0+2*1=2, 2+2*2=6, 6+2*3=12
    assert dut.psum_out.value.signed_integer == expected_psums[-1], \
        f"Expected {expected_psums[-1]}, got {dut.psum_out.value.signed_integer}"
