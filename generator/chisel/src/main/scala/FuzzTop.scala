package freechips.rocketchip.system

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage._

object FuzzMain {
  def main(args: Array[String]): Unit = {
    val (target, chiselArgs) = if (args.contains("--fir-only")) {
      (CIRCTTarget.FIRRTL, args.filterNot(arg => arg == "--fir-only" || arg == "--split-verilog"))
    } else {
      (CIRCTTarget.SystemVerilog, args)
    }
    val generator = Seq(ChiselGeneratorAnnotation(() => {
      freechips.rocketchip.diplomacy.DisableMonitors(p => new SimTop()(p))(new FuzzConfig)
    }))
    (new ChiselStage).execute(chiselArgs, generator
      :+ CIRCTTargetAnnotation(target)
      :+ FirtoolOption("--disable-annotation-unknown")
    )
  }
}
