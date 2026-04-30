package top

import ascend.AscendParams
import java.io.{File, PrintWriter}

/** Generate Graphviz dot files for architecture visualization.
  *
  * Hand-crafted diagrams that match the actual design, not auto-extracted.
  * Run: sbt "runMain ascend.Visualize"
  */
object Visualize extends App {

  val outDir = "docs/diagrams"
  new File(outDir).mkdirs()

  // --- 1. Top-level architecture diagram ---
  val arch =
    """digraph ToyAscendNPU {
      |  rankdir=TB;
      |  fontname="Helvetica"; fontsize=14;
      |  node [fontname="Helvetica", fontsize=11, shape=record, style=filled];
      |  edge [fontname="Helvetica", fontsize=9];
      |  label="Toy Ascend NPU — 顶层架构图";
      |  labelloc=t;
      |
      |  subgraph cluster_ext {
      |    label="外部接口"; style=dashed; color=gray60;
      |    ext_ctrl [label="{start|halted}", fillcolor="#E8F5E9"];
      |    ext_imem [label="{imemLoadEn|imemLoadAddr|imemLoadData}", fillcolor="#E8F5E9"];
      |    ext_ub   [label="{ubExt.en|ubExt.we|ubExt.addr\lubExt.wdata|ubExt.rdata}", fillcolor="#E8F5E9"];
      |  }
      |
      |  subgraph cluster_top {
      |    label="ToyAscendTop"; style=rounded; color="#1565C0"; penwidth=2;
      |    bgcolor="#E3F2FD";
      |
      |    imem [label="{InstrMem|256 × 32-bit\lMem (组合读)}", fillcolor="#FFF9C4"];
      |    ub   [label="{UnifiedBuffer|1024 × 128-bit\lSyncReadMem (双端口)}", fillcolor="#FFF9C4"];
      |
      |    subgraph cluster_core {
      |      label="AiCore"; style=rounded; color="#2E7D32"; penwidth=2;
      |      bgcolor="#E8F5E9";
      |
      |      scalar [label="{ScalarUnit|取指 / 译码 / 控制 FSM\l11 状态}", fillcolor="#FFCCBC"];
      |
      |      subgraph cluster_cube {
      |        label="CubeUnit"; style=rounded; color="#E65100";
      |        bgcolor="#FFF3E0";
      |
      |        sa [label="{SystolicArray|4×4 Weight-Stationary\l激活 → / 部分和 ↓}", fillcolor="#FFE0B2"];
      |
      |        subgraph cluster_pe {
      |          label="PE[4][4]"; style=dashed; color="#BF360C";
      |          bgcolor="#FFECB3";
      |          pe [label="PE\nMAC + RegNext\nweightReg", fillcolor="#FFD54F", shape=box];
      |        }
      |      }
      |
      |      vec [label="{VectorUnit|VECADD / RELU\l单周期, 4路 × 32-bit}", fillcolor="#D1C4E9"];
      |    }
      |  }
      |
      |  ext_ctrl -> scalar [label="start", color="#2E7D32"];
      |  scalar -> ext_ctrl [label="halted", color="#C62828", style=dashed];
      |
      |  ext_imem -> imem [label="preload"];
      |  imem -> scalar [label="instr (32b)", color="#1565C0"];
      |  scalar -> imem [label="PC (addr)", color="#1565C0", style=dashed];
      |
      |  ext_ub -> ub [label="Port B\n(test preload)"];
      |  scalar -> ub [label="Port A\n(en/we/addr/wdata)", color="#2E7D32"];
      |  ub -> scalar [label="rdata (128b)", color="#2E7D32", style=dashed];
      |
      |  scalar -> sa [label="cubeStart\nweightBuf\nactBuf", color="#E65100"];
      |  sa -> scalar [label="cubeDone\nresult[4][4]", color="#E65100", style=dashed];
      |
      |  scalar -> vec [label="vecStart\nsrc1/src2", color="#4A148C"];
      |  vec -> scalar [label="vecDone\ndst", color="#4A148C", style=dashed];
      |
      |  sa -> pe [label="16 PEs", style=dotted, color=gray50];
      |}""".stripMargin

  writeAndRender("architecture", arch)

  // --- 2. Systolic array detail diagram ---
  val N = AscendParams.ArraySize
  val saDot = new StringBuilder
  saDot ++=
    s"""digraph SystolicArray {
       |  rankdir=TB;
       |  fontname="Helvetica"; fontsize=14;
       |  node [fontname="Courier", fontsize=10, shape=box, style=filled, fillcolor="#FFE0B2",
       |        width=1.0, height=0.6, fixedsize=true];
       |  edge [fontname="Helvetica", fontsize=8];
       |  label="4×4 Weight-Stationary 收缩阵列\\nC[i][j] = Σ_k A[i][k] × W[k][j]";
       |  labelloc=t;
       |
       |  { rank=source;
       |""".stripMargin

  for (k <- 0 until N)
    saDot ++= s"""    act_$k [label="actIn($k)\\nA[i][$k]", shape=cds, fillcolor="#C8E6C9", width=1.2];\n"""
  saDot ++= "  }\n\n"

  for (j <- 0 until N)
    saDot ++= s"""  psum_top_$j [label="psum=0", shape=none, fontsize=8, width=0.6, height=0.3, fixedsize=true];\n"""
  saDot ++= "\n"

  for (k <- 0 until N; j <- 0 until N)
    saDot ++= s"""  pe_${k}_$j [label="PE[$k][$j]\\nW[$k][$j]"];\n"""
  saDot ++= "\n"

  for (j <- 0 until N)
    saDot ++= s"""  res_$j [label="C[i][$j]", shape=cds, fillcolor="#BBDEFB", width=1.0];\n"""
  saDot ++= "\n"

  for (k <- 0 until N)
    saDot ++= s"  { rank=same; ${(0 until N).map(j => s"pe_${k}_$j").mkString("; ")}; }\n"
  saDot ++= s"  { rank=sink; ${(0 until N).map(j => s"res_$j").mkString("; ")}; }\n\n"

  for (k <- 0 until N) {
    saDot ++= s"""  act_$k -> pe_${k}_0 [label="data", color="#2E7D32"];\n"""
    for (j <- 0 until N - 1)
      saDot ++= s"""  pe_${k}_$j -> pe_${k}_${j + 1} [label="data→", color="#2E7D32"];\n"""
  }
  saDot ++= "\n"

  for (j <- 0 until N) {
    saDot ++= s"""  psum_top_$j -> pe_0_$j [label="psum", color="#1565C0"];\n"""
    for (k <- 0 until N - 1)
      saDot ++= s"""  pe_${k}_$j -> pe_${k + 1}_$j [label="psum↓", color="#1565C0"];\n"""
    saDot ++= s"""  pe_${N - 1}_$j -> res_$j [label="result", color="#C62828"];\n"""
  }
  saDot ++= "}\n"

  writeAndRender("systolic_array", saDot.toString)

  println(s"\nDone! All diagrams in $outDir/")

  // --- Helper ---
  def writeAndRender(name: String, dotContent: String): Unit = {
    val dotFile = s"$outDir/$name.dot"
    val svgFile = s"$outDir/$name.svg"
    new PrintWriter(dotFile) { write(dotContent); close() }
    val ret = sys.process.Process(Seq("dot", "-Tsvg", "-o", svgFile, dotFile)).!
    if (ret == 0) println(s"  $name.dot -> $name.svg")
    else System.err.println(s"  Failed: $name")
  }
}
