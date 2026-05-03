package common

import chisel3._

/** Shared parameters across all accelerator designs. */
object Params {
  val DataWidth = 8 // INT8
  val AccWidth = 32 // INT32 accumulator
}
