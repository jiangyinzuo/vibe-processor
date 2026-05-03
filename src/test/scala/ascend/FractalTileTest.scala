package ascend

import org.scalatest.funspec.AnyFunSpec

class FractalTileTest extends AnyFunSpec {

  describe("FractalTile") {

    it("maps logical coordinates into 16x16 tile coordinates") {
      val coord = FractalTile.logicalToCoord(row = 17, col = 34)

      assert(coord.tileRow == 1)
      assert(coord.tileCol == 2)
      assert(coord.innerRow == 1)
      assert(coord.innerCol == 2)
    }

    it("packs and unpacks a tail-padded matrix") {
      val rows = FractalTile.TileSize + 3
      val cols = FractalTile.TileSize + 5
      val matrix = Vector.tabulate(rows, cols)((r, c) => r * 100 + c)

      val packed = FractalTile.pack(matrix)
      val unpacked = FractalTile.unpack(packed, rows, cols)

      assert(unpacked == matrix)
      assert(packed.length == 4 * FractalTile.TileSize * FractalTile.TileSize)

      val tailPaddingIndex =
        FractalTile.flatIndex(row = FractalTile.TileSize + 2, col = cols - 1, cols = cols) + 1
      assert(packed(tailPaddingIndex) == 0)
    }
  }
}
