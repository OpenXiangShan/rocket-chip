package freechips.rocketchip.system

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage._
import difftest.DifftestModule

object FuzzMain {
  def main(args: Array[String]): Unit = {
    val generator = Seq(ChiselGeneratorAnnotation(() => {
      freechips.rocketchip.diplomacy.DisableMonitors(p => new SimTop()(p))(new FuzzConfig)
    }))
    (new ChiselStage).execute(args, generator
      :+ FirtoolOption("--disable-annotation-unknown")
    )
  }
}
