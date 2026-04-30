package top

import java.io.File

/** Render all d2 diagram sources in docs/diagrams/ to SVG.
  * Run: sbt "runMain top.Visualize"
  */
object Visualize extends App {

  val dir = "docs/diagrams"
  val d2Files = new File(dir).listFiles().filter(_.getName.endsWith(".d2"))

  if (d2Files.isEmpty) {
    println(s"No .d2 files found in $dir/")
    sys.exit(1)
  }

  println(s"Rendering ${d2Files.length} d2 diagrams...")
  d2Files.foreach { f =>
    val svg = f.getAbsolutePath.replace(".d2", ".svg")
    val ret = sys.process.Process(Seq("d2", f.getAbsolutePath, svg)).!
    if (ret == 0) println(s"  ${f.getName} -> ${f.getName.replace(".d2", ".svg")}")
    else System.err.println(s"  Failed: ${f.getName}")
  }
  println("Done!")
}
