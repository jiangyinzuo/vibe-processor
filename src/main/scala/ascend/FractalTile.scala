package ascend

/** Utilities for the toy Cube fractal tile layout.
  *
  * The hardware consumes one 16x16 Cube tile at a time. This helper models the software-visible
  * packing step for larger logical matrices: split a row-major matrix into 16x16 blocks, pad tail
  * elements with zero, and keep each block contiguous.
  */
object FractalTile {
  val TileSize: Int = AscendParams.FractalTileSize

  final case class Coord(tileRow: Int, tileCol: Int, innerRow: Int, innerCol: Int)

  def tileCount(dim: Int): Int =
    (dim + TileSize - 1) / TileSize

  def logicalToCoord(row: Int, col: Int): Coord =
    Coord(
      tileRow = row / TileSize,
      tileCol = col / TileSize,
      innerRow = row % TileSize,
      innerCol = col % TileSize
    )

  def flatIndex(row: Int, col: Int, cols: Int): Int = {
    val coord = logicalToCoord(row, col)
    val tilesPerRow = tileCount(cols)
    (((coord.tileRow * tilesPerRow + coord.tileCol) * TileSize + coord.innerRow) * TileSize) +
      coord.innerCol
  }

  def packedSize(rows: Int, cols: Int): Int =
    tileCount(rows) * tileCount(cols) * TileSize * TileSize

  def pack(matrix: Seq[Seq[Int]]): Vector[Int] = {
    require(matrix.nonEmpty, "matrix must not be empty")
    val rows = matrix.length
    val cols = matrix.head.length
    require(cols > 0, "matrix rows must not be empty")
    require(matrix.forall(_.length == cols), "matrix must be rectangular")

    val packed = Array.fill(packedSize(rows, cols))(0)
    for (r <- 0 until rows; c <- 0 until cols) {
      packed(flatIndex(r, c, cols)) = matrix(r)(c)
    }
    packed.toVector
  }

  def unpack(packed: Seq[Int], rows: Int, cols: Int): Vector[Vector[Int]] = {
    require(packed.length >= packedSize(rows, cols), "packed buffer is too small")
    Vector.tabulate(rows, cols) { (r, c) =>
      packed(flatIndex(r, c, cols))
    }
  }
}
