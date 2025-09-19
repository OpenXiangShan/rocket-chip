package freechips.rocketchip.system


import chisel3._
import difftest.DifftestModule
import freechips.rocketchip.devices.debug.{Debug, DebugModuleKey}
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.rocket.WithNBigCores
import freechips.rocketchip.subsystem._
import freechips.rocketchip.util.AsyncResetReg
import org.chipsalliance.cde.config.{Config, Parameters}

class ExampleFuzzSystem(implicit p: Parameters) extends RocketSubsystem
  with CanHaveMasterAXI4MemPort
{
  // optionally add ROM devices
  // Note that setting BootROMLocated will override the reset_vector for all tiles
  val bootROM  = p(BootROMLocated(location)).map { BootROM.attach(_, this, CBUS) }
  val maskROMs = p(MaskROMLocated(location)).map { MaskROM.attach(_, this, CBUS) }

  override lazy val module = new ExampleFuzzSystemImp(this)
}

class ExampleFuzzSystemImp[+L <: ExampleFuzzSystem](_outer: L) extends RocketSubsystemModuleImp(_outer)
  with HasRTCModuleImp

class SimTop(implicit p: Parameters) extends Module {
  val ldut = LazyModule(new ExampleFuzzSystem)
  val dut = Module(ldut.module)

  ldut.io_clocks.get.elements.values.foreach(_.clock := clock)
  // Allow the debug ndreset to reset the dut, but not until the initial reset has completed
  val dut_reset = (reset.asBool | ldut.debug.map { debug => AsyncResetReg(debug.ndreset) }.getOrElse(false.B)).asBool
  ldut.io_clocks.get.elements.values.foreach(_.reset := dut_reset)

  SimAXIMem.connectMem(ldut)
  val success = WireInit(false.B)
  Debug.connectDebug(ldut.debug, ldut.resetctrl, ldut.psd, clock, reset.asBool, success)

  ldut.module.meip.foreach(_.foreach(_ := false.B))
  ldut.module.seip.foreach(_.foreach(_ := false.B))

  val difftest = DifftestModule.finish("rocket-chip")
}

class FuzzConfig extends Config(
  new WithNBigCores(1).alter((site, _, up) => {
    case TilesLocated(InSubsystem) => up(TilesLocated(InSubsystem), site).map {
      case tp: RocketTileAttachParams => tp.copy(tileParams = tp.tileParams.copy(
        core = tp.tileParams.core.copy(
          haveNemuTrap = true,
          haveCease = false,
          nPMPs = 0,
          nBreakpoints = 0,
          chickenCSR = false,
          haveSimTimeout = false,
        )
      ))
    }
  }) ++
  new WithNExtTopInterrupts(0) ++
  new WithCoherentBusTopology ++
  new BaseConfig().alter((site, _, up) => {
    case DebugModuleKey => None
    case BootROMLocated(InSubsystem) => Seq(BootROMParams(
      address = 0x10000000,
      hang = 0x10000000,
      appendDTB = false,
    ))
    case ExtMem => Some(MemoryPortParams(MasterPortParams(
      base = x"8000_0000",
      size = x"8000_0000",
      beatBytes = site(MemoryBusKey).beatBytes,
      idBits = 4), 1))
    case ControlBusKey => up(ControlBusKey, site).copy(
      errorDevice = None,
      zeroDevice = None,
    )
  })
)
