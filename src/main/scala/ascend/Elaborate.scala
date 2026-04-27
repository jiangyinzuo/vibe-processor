package ascend

import circt.stage.ChiselStage

/** Generate Verilog for the Toy Ascend NPU. */
object Elaborate extends App {
  ChiselStage.emitSystemVerilogFile(
    new ToyAscendTop,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    args = Array("--target-dir", "generated")
  )
  println("Verilog generated in generated/")
}
