import mill._
import mill.scalalib._
import mill.scalalib.publish._
import common.HasChisel
import coursier.maven.MavenRepository
import $file.dependencies.hardfloat.common
import $file.dependencies.cde.common
import $file.dependencies.diplomacy.common
import $file.common

object v {
  val scala = "2.13.16"
  val chisel = (ivy"org.chipsalliance::chisel:7.0.0", ivy"org.chipsalliance:::chisel-plugin:7.0.0")
  val mainargs = ivy"com.lihaoyi::mainargs:0.5.0"
  val json4sJackson = ivy"org.json4s::json4s-jackson:4.0.5"
  val scalaReflect = ivy"org.scala-lang:scala-reflect:${scala}"
  val sourcecode = ivy"com.lihaoyi::sourcecode:0.3.1"
  val sonatypesSnapshots = Seq(
    MavenRepository("https://s01.oss.sonatype.org/content/repositories/snapshots")
  )
}

object macros extends Macros

trait Macros
  extends $file.common.MacrosModule
    with RocketChipPublishModule
    with SbtModule {

  def scalaVersion: T[String] = T(v.scala)

  def scalaReflectIvy = v.scalaReflect
}

object hardfloat extends Hardfloat

trait Hardfloat
  extends $file.dependencies.hardfloat.common.HardfloatModule
    with RocketChipPublishModule {

  def scalaVersion: T[String] = T(v.scala)

  override def millSourcePath = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "dependencies" / "hardfloat" / "hardfloat"

  def chiselModule = None

  def chiselPluginJar = None

  def chiselIvy = Some(v.chisel._1)

  def chiselPluginIvy = Some(v.chisel._2)
}

object cde extends CDE

trait CDE
  extends $file.dependencies.cde.common.CDEModule
    with RocketChipPublishModule
    with ScalaModule {

  def scalaVersion: T[String] = T(v.scala)

  override def millSourcePath = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "dependencies" / "cde" / "cde"
}

object diplomacy extends Diplomacy

trait Diplomacy
    extends $file.dependencies.diplomacy.common.DiplomacyModule
    with RocketChipPublishModule {

  override def scalaVersion: T[String] = T(v.scala)

  override def millSourcePath = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "dependencies" / "diplomacy" / "diplomacy"

  def chiselModule = None
  def chiselPluginJar = None

  // use chisel from ivy
  def chiselIvy = Some(v.chisel._1)
  def chiselPluginIvy = Some(v.chisel._2)

  // use CDE from source until published to sonatype
  def cdeModule = cde

  def sourcecodeIvy = v.sourcecode
}

trait Difftest
  extends SbtModule
    with HasChisel
    with RocketChipPublishModule {

  def scalaVersion: T[String] = T(v.scala)

  def millSourcePath = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "difftest"

  def chiselModule: Option[ScalaModule] = None

  def chiselPluginJar: T[Option[PathRef]] = None

  def chiselIvy = Some(v.chisel._1)

  def chiselPluginIvy = Some(v.chisel._2)
}

object difftest extends Difftest

object rocketchip extends RocketChip

trait RocketChip
  extends $file.common.RocketChipModule
    with RocketChipPublishModule
    with SbtModule {
  def scalaVersion: T[String] = T(v.scala)

  override def millSourcePath = super.millSourcePath / os.up

  def chiselIvy = Some(v.chisel._1)

  def chiselPluginIvy = Some(v.chisel._2)

  def macrosModule = macros

  def hardfloatModule = hardfloat

  def cdeModule = cde

  def difftestModule = difftest

  def diplomacyModule = diplomacy

  def diplomacyIvy = None

  def mainargsIvy = v.mainargs

  def json4sJacksonIvy = v.json4sJackson

  def repositoriesTask = T.task(super.repositoriesTask() ++ v.sonatypesSnapshots)
}

trait RocketChipPublishModule
  extends PublishModule {
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "org.chipsalliance",
    url = "http://github.com/chipsalliance/rocket-chip",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github("chipsalliance", "rocket-chip"),
    developers = Seq(
      Developer("aswaterman", "Andrew Waterman", "https://aspire.eecs.berkeley.edu/author/waterman/")
    )
  )

  override def publishVersion: T[String] = T("1.6-SNAPSHOT")
}

// Tests
trait Emulator extends Cross.Module2[String, String] {
  val top: String = crossValue
  val config: String = crossValue2

  object generator extends Module {
    def elaborate = T {
      os.proc(
        mill.util.Jvm.javaExe,
        "-jar",
        rocketchip.assembly().path,
        "--dir", T.dest.toString,
        "--top", top,
        config.split('_').flatMap(c => Seq("--config", c)),
      ).call()
      PathRef(T.dest)
    }

    def chiselAnno = T {
      os.walk(elaborate().path).collectFirst { case p if p.last.endsWith("anno.json") => p }.map(PathRef(_)).get
    }

    def chirrtl = T {
      os.walk(elaborate().path).collectFirst { case p if p.last.endsWith("fir") => p }.map(PathRef(_)).get
    }
  }

  object litexgenerate extends Module {
    def compile = T {
      os.proc("firtool",
        generator.chirrtl().path,
        s"--annotation-file=${generator.chiselAnno().path}",
        "--disable-annotation-unknown",
        "-dedup",
        "-O=debug",
        "--split-verilog",
        "--preserve-values=named",
        "--output-annotation-file=mfc.anno.json",
        "--lowering-options=disallowLocalVariables",
        s"-o=${T.dest}"
      ).call(T.dest)
      PathRef(T.dest)
    }

    def rtls = T {
      os.read(compile().path / "filelist.f").split("\n").map(str =>
        try {
          os.Path(str)
        } catch {
          case e: IllegalArgumentException if e.getMessage.contains("is not an absolute path") =>
            compile().path / str.stripPrefix("./")
        }
      ).filter(p => p.ext == "v" || p.ext == "sv").map(PathRef(_)).toSeq
    }
  }

  object mfccompiler extends Module {
    def compile = T {
      os.proc("firtool",
        generator.chirrtl().path,
        s"--annotation-file=${generator.chiselAnno().path}",
        "--disable-annotation-unknown",
        "-dedup",
        "-O=debug",
        "--split-verilog",
        "--preserve-values=named",
        "--output-annotation-file=mfc.anno.json",
        s"-o=${T.dest}"
      ).call(T.dest)
      PathRef(T.dest)
    }

    def rtls = T {
      os.read(compile().path / "filelist.f").split("\n").map(str =>
        try {
          os.Path(str)
        } catch {
          case e: IllegalArgumentException if e.getMessage.contains("is not an absolute path") =>
            compile().path / str.stripPrefix("./")
        }
      ).filter(p => p.ext == "v" || p.ext == "sv").map(PathRef(_)).toSeq
    }
  }
}

/** object to elaborate verilated emulators. */
object emulator extends Cross[Emulator](
  // RocketSuiteA
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultConfig"),
  // RocketSuiteB
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultBufferlessConfig"),
  // RocketSuiteC
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.TinyConfig"),

  // Unittest
  ("freechips.rocketchip.unittest.TestHarness", "freechips.rocketchip.unittest.AMBAUnitTestConfig"),
  ("freechips.rocketchip.unittest.TestHarness", "freechips.rocketchip.unittest.TLSimpleUnitTestConfig"),
  ("freechips.rocketchip.unittest.TestHarness", "freechips.rocketchip.unittest.TLWidthUnitTestConfig"),
  // DTM
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.WithJtagDTMSystem_freechips.rocketchip.system.WithDebugSBASystem_freechips.rocketchip.system.DefaultConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.WithJtagDTMSystem_freechips.rocketchip.system.WithDebugSBASystem_freechips.rocketchip.system.DefaultRV32Config"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.WithJtagDTMSystem_freechips.rocketchip.system.DefaultConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.WithJtagDTMSystem_freechips.rocketchip.system.DefaultRV32Config"),
  // Miscellaneous
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultSmallConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DualBankConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DualChannelConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DualChannelDualBankConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.RoccExampleConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.Edge128BitConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.Edge32BitConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.QuadChannelBenchmarkConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.EightChannelConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DualCoreConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.MemPortOnlyConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.MMIOPortOnlyConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.CloneTileConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.HypervisorConfig"),
  //
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultRV32Config"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultFP16Config"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultBConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.DefaultRV32BConfig"),

  // Litex
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall1x1"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall1x2"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall1x4"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall1x8"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall2x1"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall2x2"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall2x4"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall2x8"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall4x1"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall4x2"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall4x4"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall4x8"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall8x1"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall8x2"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall8x4"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigSmall8x8"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig1x1"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig1x2"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig1x4"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig1x8"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig2x1"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig2x2"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig2x4"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig2x8"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig4x1"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig4x2"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig4x4"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig4x8"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig8x1"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig8x2"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig8x4"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.LitexConfigBig8x8"),
)
