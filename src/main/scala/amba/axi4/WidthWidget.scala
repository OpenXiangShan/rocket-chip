// See LICENSE.SiFive for license details.

package freechips.rocketchip.amba.axi4

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba.axi4.AXI4Parameters._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.unittest._

import scala.math.max

// innerBeatBytes => the new client-facing bus width
class AXI4WidthWidget(innerBeatBytes: Int)(implicit p: Parameters) extends LazyModule
{
  require(innerBeatBytes >= 1 && isPow2(innerBeatBytes), s"AXI4WidthWidget innerBeatBytes must be a power of two, not $innerBeatBytes")

  private def noChangeRequired(slave: AXI4SlavePortParameters) = slave.beatBytes == innerBeatBytes

  val node = new AXI4AdapterNode(
    masterFn = { mp =>
      mp.masters.foreach { m =>
        require(m.aligned, s"AXI4WidthWidget requires aligned upstream masters; ${m.name} is not")
      }
      mp
    },
    slaveFn  = { sp =>
      val ratio = innerBeatBytes / sp.beatBytes
      val div = sp.beatBytes / innerBeatBytes
      def scaleTransfer(x: TransferSizes): TransferSizes = {
        if (!x) x
        else if (innerBeatBytes >= sp.beatBytes) TransferSizes(x.min * ratio, x.max * ratio)
        else {
          val min = if (x.min == 0) 0 else max(1, x.min / div)
          val maxXfer = x.max / div
          if (maxXfer == 0) TransferSizes.none else TransferSizes(min, maxXfer)
        }
      }
      sp.copy(
        beatBytes = innerBeatBytes,
        slaves = sp.slaves.map { s =>
          s.copy(
            supportsWrite = scaleTransfer(s.supportsWrite),
            supportsRead  = scaleTransfer(s.supportsRead))
        })
    }) {
    override def circuitIdentity = edges.out.map(_.slave).forall(noChangeRequired)
  }

  lazy val module = new Impl

  class WidthMeta(addrLoBits: Int) extends Bundle {
    val addrLo = UInt(addrLoBits.W)
    val outSize = UInt(sizeBits.W)
    val outLen = UInt(lenBits.W)
    val burst = UInt(burstBits.W)
    val splitShift = UInt(sizeBits.W)
  }

  class Impl extends LazyModuleImp(this) {
    private def maxFlightForId(master: AXI4MasterPortParameters, id: Int): Int =
      master.masters.find(_.id.contains(id)).flatMap(_.maxFlight).getOrElse(1)

    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val totalWriteFlight = edgeIn.master.masters.map { m => m.maxFlight.getOrElse(1) * m.id.size }.sum
      val inBytes = edgeIn.bundle.dataBits / 8
      val outBytes = edgeOut.bundle.dataBits / 8
      val lgIn = log2Ceil(inBytes)
      val lgOut = log2Ceil(outBytes)
      val addrLoBits = max(1, max(lgIn, lgOut))

      def maxFlightForId(id: Int): Int = edgeIn.master.masters.find(_.id.contains(id)).flatMap(_.maxFlight).getOrElse(1)

      if (inBytes == outBytes) {
        out <> in
      } else {
        val endId = edgeIn.master.endId

        def assertAddrAligned(valid: Bool, addr: UInt, size: UInt): Unit = {
          val lowAddr = addr(addrLoBits - 1, 0)
          val alignMask = Mux(size >= addrLoBits.U, ~0.U(addrLoBits.W), (1.U(addrLoBits.W) << size) - 1.U)
          assert(!valid || ((lowAddr & alignMask) === 0.U),
            s"AXI4WidthWidget observed unaligned request; upstream must be aligned to size")
        }
        assertAddrAligned(in.aw.valid, in.aw.bits.addr, in.aw.bits.size)
        assertAddrAligned(in.ar.valid, in.ar.bits.addr, in.ar.bits.size)

        def minSize(size: UInt): UInt = if (inBytes > outBytes) Mux(size > lgOut.U, lgOut.U, size) else size

        def splitShift(size: UInt): UInt = {
          Mux((inBytes > outBytes).B && size > lgOut.U, size - lgOut.U, 0.U(sizeBits.W))
        }

        def expandedLen(len: UInt, shift: UInt): (UInt, Bool) = {
          val beats = len +& 1.U
          val expandedBeats = (beats << shift).asUInt
          val overflow = expandedBeats > (1 << lenBits).U
          (expandedBeats(lenBits - 1, 0) - 1.U, overflow)
        }

        def byteStep(size: UInt): UInt = {
          val stepBits = max(addrLoBits, 1 << sizeBits)
          (1.U(stepBits.W) << size)(addrLoBits - 1, 0)
        }

        def nextAddr(addr: UInt, size: UInt, burst: UInt): UInt = {
          val inc = Mux(burst === BURST_FIXED, 0.U(addrLoBits.W), byteStep(size))
          (addr + inc)(addrLoBits - 1, 0)
        }

        def extract(data: UInt, wideBytes: Int, narrowBytes: Int, sel: UInt): UInt = {
          val ratio = wideBytes / narrowBytes
          val lanes = VecInit.tabulate(ratio) { i =>
            data((i + 1) * narrowBytes * 8 - 1, i * narrowBytes * 8)
          }
          lanes(sel)
        }

        def extractStrb(strb: UInt, wideBytes: Int, narrowBytes: Int, sel: UInt): UInt = {
          val ratio = wideBytes / narrowBytes
          val lanes = VecInit.tabulate(ratio) { i =>
            strb((i + 1) * narrowBytes - 1, i * narrowBytes)
          }
          lanes(sel)
        }

        def place(data: UInt, wideBytes: Int, narrowBytes: Int): UInt = {
          val ratio = wideBytes / narrowBytes
          Fill(ratio, data)
        }

        def placeStrb(strb: UInt, wideBytes: Int, narrowBytes: Int, sel: UInt): UInt = {
          val ratio = wideBytes / narrowBytes
          val lanes = VecInit.tabulate(ratio) { i =>
            ((strb << (i * narrowBytes)).asUInt)(wideBytes - 1, 0)
          }
          lanes(sel)
        }

        def insert(existing: UInt, sub: UInt, wideBytes: Int, narrowBytes: Int, sel: UInt): UInt = {
          val ratio = wideBytes / narrowBytes
          val lanes = VecInit.tabulate(ratio) { i =>
            val old = existing((i + 1) * narrowBytes * 8 - 1, i * narrowBytes * 8)
            Mux(sel === i.U, sub, old)
          }
          lanes.asUInt
        }

        def queueForId(depth: Int): QueueIO[WidthMeta] = {
          if (depth == 0) {
            val q = Wire(new QueueIO(new WidthMeta(addrLoBits), 1))
            q.enq.ready := false.B
            q.deq.valid := false.B
            q.deq.bits := DontCare
            q.count := DontCare
            q
          } else {
            Module(new Queue(new WidthMeta(addrLoBits), depth)).io
          }
        }

        def makeMeta(addr: UInt, size: UInt, len: UInt, burst: UInt): WidthMeta = {
          val meta = Wire(new WidthMeta(addrLoBits))
          val outSize = minSize(size)
          val shift = splitShift(size)
          val (outLen, overflow) = expandedLen(len, shift)
          meta.addrLo := addr(addrLoBits - 1, 0)
          meta.outSize := outSize
          meta.outLen := outLen
          meta.burst := burst
          meta.splitShift := shift
          assert(!overflow, s"AXI4WidthWidget cannot expand a burst beyond ${1 << lenBits} beats")
          when (shift =/= 0.U) {
            assert(burst =/= BURST_FIXED,
              "AXI4WidthWidget does not support splitting FIXED bursts when narrowing the bus")
          }
          meta
        }

        val awMetaQ = Module(new Queue(new WidthMeta(addrLoBits), max(1, totalWriteFlight))).io
        val awMeta = makeMeta(in.aw.bits.addr, in.aw.bits.size, in.aw.bits.len, in.aw.bits.burst)

        out.aw.valid := in.aw.valid && awMetaQ.enq.ready
        in.aw.ready := out.aw.ready && awMetaQ.enq.ready
        out.aw.bits := in.aw.bits
        out.aw.bits.size := awMeta.outSize
        out.aw.bits.len := awMeta.outLen

        awMetaQ.enq.valid := in.aw.valid && out.aw.ready
        awMetaQ.enq.bits := awMeta

        val wActive = RegInit(false.B)
        val wAddr = Reg(UInt(addrLoBits.W))
        val wOutSize = Reg(UInt(sizeBits.W))
        val wBurst = Reg(UInt(burstBits.W))
        val wSplit = Reg(UInt(sizeBits.W))
        val wRem = Reg(UInt(lenBits.W))
        val wSub = Reg(UInt(sizeBits.W))

        val wMetaValid = wActive || awMetaQ.deq.valid
        val wCurrAddr = Mux(wActive, wAddr, awMetaQ.deq.bits.addrLo)
        val wCurrOutSize = Mux(wActive, wOutSize, awMetaQ.deq.bits.outSize)
        val wCurrBurst = Mux(wActive, wBurst, awMetaQ.deq.bits.burst)
        val wCurrSplit = Mux(wActive, wSplit, awMetaQ.deq.bits.splitShift)
        val wCurrRem = Mux(wActive, wRem, awMetaQ.deq.bits.outLen)
        val wCurrSub = Mux(wActive, wSub, 0.U(sizeBits.W))
        val wFactor = (1.U((1 << sizeBits).W) << wCurrSplit)
        val wSubLast = wCurrSplit === 0.U || wCurrSub === (wFactor - 1.U)(sizeBits - 1, 0)
        val wTxnLast = wCurrRem === 0.U
        val wSlice = if (inBytes > outBytes) wCurrAddr(lgIn - 1, lgOut) else wCurrAddr(lgOut - 1, lgIn)

        out.w.valid := in.w.valid && wMetaValid
        in.w.ready := out.w.ready && wMetaValid && wSubLast
        out.w.bits.last := wTxnLast
        out.w.bits.user := in.w.bits.user

        if (inBytes > outBytes) {
          out.w.bits.data := extract(in.w.bits.data, inBytes, outBytes, wSlice)
          out.w.bits.strb := extractStrb(in.w.bits.strb, inBytes, outBytes, wSlice)
        } else {
          out.w.bits.data := place(in.w.bits.data, outBytes, inBytes)
          out.w.bits.strb := placeStrb(in.w.bits.strb, outBytes, inBytes, wSlice)
        }

        awMetaQ.deq.ready := !wActive && out.w.fire

        when (out.w.fire) {
          assert(!wTxnLast || wSubLast)
          when (!wTxnLast) {
            wActive := true.B
            wAddr := nextAddr(wCurrAddr, wCurrOutSize, wCurrBurst)
            wOutSize := wCurrOutSize
            wBurst := wCurrBurst
            wSplit := wCurrSplit
            wRem := wCurrRem - 1.U
            wSub := Mux(wSubLast, 0.U, wCurrSub + 1.U)
          }.otherwise {
            wActive := false.B
          }
        }

        in.b <> out.b

        val arMetaQs = Seq.tabulate(endId) { i => queueForId(maxFlightForId(i)) }
        val arMeta = makeMeta(in.ar.bits.addr, in.ar.bits.size, in.ar.bits.len, in.ar.bits.burst)
        val arId = in.ar.bits.id
        val arReadys = VecInit(arMetaQs.map(_.enq.ready))

        out.ar.valid := in.ar.valid && arReadys(arId)
        in.ar.ready := out.ar.ready && arReadys(arId)
        out.ar.bits := in.ar.bits
        out.ar.bits.size := arMeta.outSize
        out.ar.bits.len := arMeta.outLen

        val arSel = UIntToOH(arId, endId).asBools
        (arMetaQs zip arSel).foreach { case (q, sel) =>
          q.enq.valid := in.ar.valid && out.ar.ready && sel
          q.enq.bits := arMeta
        }

        val rActive = RegInit(VecInit.fill(endId)(false.B))
        val rAddr = Reg(Vec(endId, UInt(addrLoBits.W)))
        val rOutSize = Reg(Vec(endId, UInt(sizeBits.W)))
        val rBurst = Reg(Vec(endId, UInt(burstBits.W)))
        val rSplit = Reg(Vec(endId, UInt(sizeBits.W)))
        val rRem = Reg(Vec(endId, UInt(lenBits.W)))
        val rSub = Reg(Vec(endId, UInt(sizeBits.W)))
        val rRespAcc = RegInit(VecInit.fill(endId)(0.U(respBits.W)))
        val rDataAcc = RegInit(VecInit.fill(endId)(0.U((inBytes * 8).W)))

        val rid = out.r.bits.id
        val rMetaValids = VecInit(arMetaQs.map(_.deq.valid))
        val rHaveMeta = rMetaValids(rid)
        assert(!out.r.valid || rHaveMeta, "AXI4WidthWidget observed an R beat without matching AR metadata")

        val rMetaBits = VecInit(arMetaQs.map(_.deq.bits))
        val rCurrAddr = Mux(rActive(rid), rAddr(rid), rMetaBits(rid).addrLo)
        val rCurrOutSize = Mux(rActive(rid), rOutSize(rid), rMetaBits(rid).outSize)
        val rCurrBurst = Mux(rActive(rid), rBurst(rid), rMetaBits(rid).burst)
        val rCurrSplit = Mux(rActive(rid), rSplit(rid), rMetaBits(rid).splitShift)
        val rCurrRem = Mux(rActive(rid), rRem(rid), rMetaBits(rid).outLen)
        val rCurrSub = Mux(rActive(rid), rSub(rid), 0.U(sizeBits.W))
        val rFactor = (1.U((1 << sizeBits).W) << rCurrSplit).asUInt
        val rSubLast = rCurrSplit === 0.U || rCurrSub === (rFactor - 1.U)(sizeBits - 1, 0)
        val rTxnLast = rCurrRem === 0.U
        val rSlice = if (inBytes > outBytes) rCurrAddr(lgIn - 1, lgOut) else rCurrAddr(lgOut - 1, lgIn)
        val rMergedData = if (inBytes > outBytes) insert(rDataAcc(rid), out.r.bits.data, inBytes, outBytes, rSlice) else 0.U
        val rPackedData = if (inBytes > outBytes) place(out.r.bits.data, inBytes, outBytes) else 0.U
        val rResultData =
          if (inBytes > outBytes) Mux(rCurrSplit === 0.U, rPackedData, rMergedData)
          else extract(out.r.bits.data, outBytes, inBytes, rSlice)

        out.r.ready := rHaveMeta && Mux(rSubLast, in.r.ready, true.B)

        in.r.valid := out.r.valid && rHaveMeta && rSubLast
        in.r.bits.id := out.r.bits.id
        in.r.bits.data := rResultData
        in.r.bits.resp := out.r.bits.resp | rRespAcc(rid)
        in.r.bits.user := out.r.bits.user
        in.r.bits.echo := out.r.bits.echo
        in.r.bits.last := out.r.bits.last

        val rSel = UIntToOH(rid, endId).asBools
        (arMetaQs zip rSel).foreach { case (q, sel) =>
          q.deq.ready := out.r.fire && sel && rTxnLast
        }

        when (out.r.fire && rHaveMeta) {
          assert(!rTxnLast || rSubLast)

          if (inBytes > outBytes) {
            when (rCurrSplit =/= 0.U && !rSubLast) {
              rDataAcc(rid) := rMergedData
            }
          }

          rRespAcc(rid) := Mux(rSubLast, 0.U, rRespAcc(rid) | out.r.bits.resp)

          when (!rTxnLast) {
            rActive(rid) := true.B
            rAddr(rid) := nextAddr(rCurrAddr, rCurrOutSize, rCurrBurst)
            rOutSize(rid) := rCurrOutSize
            rBurst(rid) := rCurrBurst
            rSplit(rid) := rCurrSplit
            rRem(rid) := rCurrRem - 1.U
            rSub(rid) := Mux(rSubLast, 0.U, rCurrSub + 1.U)
          }.otherwise {
            rActive(rid) := false.B
          }
        }
      }
    }
  }
}

object AXI4WidthWidget
{
  def apply(innerBeatBytes: Int)(implicit p: Parameters): AXI4Node =
  {
    val widget = LazyModule(new AXI4WidthWidget(innerBeatBytes))
    widget.node
  }
}

class AXI4RAMWidthWidget(first: Int, second: Int, txns: Int)(implicit p: Parameters) extends LazyModule {
  val fuzz  = LazyModule(new TLFuzzer(txns))
  val model = LazyModule(new TLRAMModel("AXI4WidthWidget"))
  val ram   = LazyModule(new AXI4RAM(AddressSet(0x0, 0x3ff), beatBytes = second))

  (ram.node
    := AXI4WidthWidget(second)
    := AXI4WidthWidget(first)
    // Fragment on AXI before width narrowing to cap burst length expansion.
    := AXI4Fragmenter()
    := AXI4Deinterleaver(256)
    := TLToAXI4()
    := model.node
    := fuzz.node)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    io.finished := fuzz.module.io.finished
  }
}

class AXI4RAMWidthWidgetTest(first: Int, second: Int, txns: Int = 5000, timeout: Int = 500000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(LazyModule(new AXI4RAMWidthWidget(first, second, txns)).module)
  dut.io.start := DontCare
  io.finished := dut.io.finished
}