package top

import circt.stage.ChiselStage

/** Generate Verilog for all Top modules. */
object Elaborate extends App {
  val firtoolBase = Array("-disable-all-randomization", "-strip-debug-info")
  val firtoolYosys =
    firtoolBase ++ Array("--lowering-options=disallowLocalVariables,disallowPackedArrays")

  // Ascend NPU
  ChiselStage.emitSystemVerilogFile(
    new ascend.ToyAscendTop,
    firtoolOpts = firtoolBase,
    args = Array("--target-dir", "generated/ascend")
  )
  ChiselStage.emitSystemVerilogFile(
    new ascend.ToyAscendTop,
    firtoolOpts = firtoolYosys,
    args = Array("--target-dir", "generated/ascend/yosys")
  )
  println("Ascend NPU Verilog -> generated/ascend/")

  // GPU
  ChiselStage.emitSystemVerilogFile(
    new gpu.ToyGpuTop,
    firtoolOpts = firtoolBase,
    args = Array("--target-dir", "generated/gpu")
  )
  ChiselStage.emitSystemVerilogFile(
    new gpu.ToyGpuTop,
    firtoolOpts = firtoolYosys,
    args = Array("--target-dir", "generated/gpu/yosys")
  )
  println("GPU Verilog -> generated/gpu/")
}
