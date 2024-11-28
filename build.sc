import mill._
import mill.scalalib._
import mill.scalalib.publish._
import common.HasChisel
import coursier.maven.MavenRepository
import $file.hardfloat.common
import $file.cde.common
import $file.common

object v {
  val scala = "2.13.10"
  // the first version in this Map is the mainly supported version which will be used to run tests
  val chiselCrossVersions = Map(
    "3.6.1" -> (ivy"edu.berkeley.cs::chisel3:3.6.1", ivy"edu.berkeley.cs:::chisel3-plugin:3.6.1"),
    "6.6.0" -> (ivy"org.chipsalliance::chisel:6.6.0", ivy"org.chipsalliance:::chisel-plugin:6.6.0"),
  )
  val mainargs = ivy"com.lihaoyi::mainargs:0.5.0"
  val json4sJackson = ivy"org.json4s::json4s-jackson:4.0.5"
  val scalaReflect = ivy"org.scala-lang:scala-reflect:${scala}"
}

object macros extends Macros

trait Macros
  extends $file.common.MacrosModule
    with RocketChipPublishModule
    with SbtModule {

  def scalaVersion: T[String] = T(v.scala)

  def scalaReflectIvy = v.scalaReflect
}

object hardfloat extends mill.define.Cross[Hardfloat](v.chiselCrossVersions.keys.toSeq)

trait Hardfloat
  extends $file.hardfloat.common.HardfloatModule
    with RocketChipPublishModule
    with Cross.Module[String] {

  def scalaVersion: T[String] = T(v.scala)

  override def millSourcePath = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "hardfloat" / "hardfloat"

  def chiselModule = None

  def chiselPluginJar = None

  def chiselIvy = Some(v.chiselCrossVersions(crossValue)._1)

  def chiselPluginIvy = Some(v.chiselCrossVersions(crossValue)._2)
}

object cde extends CDE

trait CDE
  extends $file.cde.common.CDEModule
    with RocketChipPublishModule
    with ScalaModule {

  def scalaVersion: T[String] = T(v.scala)

  override def millSourcePath = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "cde" / "cde"
}

trait Difftest
  extends SbtModule
    with HasChisel
    with RocketChipPublishModule
    with Cross.Module[String] {

  def scalaVersion: T[String] = T(v.scala)

  def millSourcePath = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "difftest"

  def chiselModule: Option[ScalaModule] = None

  def chiselPluginJar: T[Option[PathRef]] = None

  def chiselIvy = Some(v.chiselCrossVersions(crossValue)._1)

  def chiselPluginIvy = Some(v.chiselCrossVersions(crossValue)._2)

 // override def scalacOptions = T(Seq[String]())
}

object difftest extends mill.define.Cross[Difftest](v.chiselCrossVersions.keys.toSeq)

trait CcoverModule extends SbtModule
    with HasChisel
    with Cross.Module[String] {

  def scalaVersion: T[String] = T(v.scala)

  def sourceRoot = T.sources { T.workspace / "ccover" / "instrumentation" / "src" }

  private def getSources(p: PathRef) = if (os.exists(p.path)) os.walk(p.path) else Seq()

  def allSources = T { sourceRoot().flatMap(getSources).map(PathRef(_)) }

  def chiselModule: Option[ScalaModule] = None

  def chiselPluginJar: T[Option[PathRef]] = None

  def chiselIvy = Some(v.chiselCrossVersions(crossValue)._1)

  def chiselPluginIvy = Some(v.chiselCrossVersions(crossValue)._2)

  def ivyDeps = super.ivyDeps() ++ Agg(ivy"edu.berkeley.cs::chiseltest:0.6.2")

}

object ccover extends Cross[CcoverModule](v.chiselCrossVersions.keys.toSeq)

object rocketchip extends Cross[RocketChip](v.chiselCrossVersions.keys.toSeq)

trait RocketChip
  extends $file.common.RocketChipModule
    with RocketChipPublishModule
    with SbtModule
    with Cross.Module[String] {
  def scalaVersion: T[String] = T(v.scala)

  override def millSourcePath = super.millSourcePath / os.up

  def chiselModule = None

  def chiselPluginJar = None

  def chiselIvy = Some(v.chiselCrossVersions(crossValue)._1)

  def chiselPluginIvy = Some(v.chiselCrossVersions(crossValue)._2)

  def macrosModule = macros

  def hardfloatModule = hardfloat(crossValue)

  def cdeModule = cde

  def difftestModule = difftest(crossValue)

  def mainargsIvy = v.mainargs

  def json4sJacksonIvy = v.json4sJackson
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

object generator extends Cross[Generator](v.chiselCrossVersions.keys.toSeq)

trait Generator extends SbtModule with Cross.Module[String] {

  private val isChisel3 = crossValue.startsWith("3")

  private val directory = if (isChisel3) "chisel3" else "chisel"
  override def millSourcePath = os.Path(sys.env("MILL_WORKSPACE_ROOT")) / "generator" / directory

  override def scalaVersion: T[String] = T(v.scala)

  override def ivyDeps = Agg(v.chiselCrossVersions(crossValue)._1)

  override def scalacPluginIvyDeps = Agg(v.chiselCrossVersions(crossValue)._2)

  override def scalacOptions = T(Seq[String]())

  override def moduleDeps = super.moduleDeps ++
    Some(rocketchip(crossValue)) ++
    Option.when(isChisel3)(ccover(crossValue))

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
        rocketchip(v.chiselCrossVersions.keys.head).assembly().path,
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
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.BitManipCryptoConfig"),
  ("freechips.rocketchip.system.TestHarness", "freechips.rocketchip.system.BitManipCrypto32Config"),
)
