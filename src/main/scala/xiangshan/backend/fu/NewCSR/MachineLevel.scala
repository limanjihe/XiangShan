package xiangshan.backend.fu.NewCSR

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.rocket.CSRs
import utility.SignExt
import utils.PerfEvent
import xiangshan.backend.fu.NewCSR.CSRBundles._
import xiangshan.backend.fu.NewCSR.CSRDefines._
import xiangshan.backend.fu.NewCSR.CSRDefines.{CSRROField => RO, CSRRWField => RW, _}
import xiangshan.backend.fu.NewCSR.CSREvents._
import xiangshan.backend.fu.NewCSR.CSREnumTypeImplicitCast._
import xiangshan.backend.fu.NewCSR.ChiselRecordForField._
import xiangshan.backend.fu.PerfCounterIO
import xiangshan.backend.fu.NewCSR.CSRConfig._

import scala.collection.immutable.SeqMap

trait MachineLevel { self: NewCSR =>
  val mstatus = Module(new MstatusModule)
    .setAddr(CSRs.mstatus)

  val misa = Module(new CSRModule("Misa", new MisaBundle))
    .setAddr(CSRs.misa)

  println(s"[CSR] supported isa ext: ${misa.bundle.getISAString}")

  val medeleg = Module(new CSRModule("Medeleg", new MedelegBundle))
    .setAddr(CSRs.medeleg)

  val mideleg = Module(new CSRModule("Mideleg", new MidelegBundle))
    .setAddr(CSRs.mideleg)

  val mie = Module(new CSRModule("Mie", new MieBundle) with HasIpIeBundle {
    val fromHie  = IO(Flipped(new HieToMie))
    val fromSie  = IO(Flipped(new SieToMie))
    val fromVSie = IO(Flipped(new VSieToMie))

    // bit 1 SSIE
    when (fromSie.SSIE.valid) {
      reg.SSIE := fromSie.SSIE.bits
    }

    // bit 2 VSSIE
    when (fromHie.VSSIE.valid || fromVSie.VSSIE.valid) {
      reg.VSSIE := Mux1H(Seq(
        fromHie .VSSIE.valid -> fromHie .VSSIE.bits,
        fromVSie.VSSIE.valid -> fromVSie.VSSIE.bits,
      ))
    }

    // bit 5 STIE
    when(fromSie.STIE.valid) {
      reg.STIE := fromSie.STIE.bits
    }

    // bit 6 VSTIE
    when(fromHie.VSTIE.valid || fromVSie.VSTIE.valid) {
      reg.VSTIE := Mux1H(Seq(
        fromHie .VSTIE.valid -> fromHie .VSTIE.bits,
        fromVSie.VSTIE.valid -> fromVSie.VSTIE.bits,
      ))
    }

    // bit 9 SEIE
    when(fromSie.SEIE.valid) {
      reg.SEIE := fromSie.SEIE.bits
    }

    // bit 10 VSEIE
    when(fromHie.VSEIE.valid || fromVSie.VSEIE.valid) {
      reg.VSEIE := Mux1H(Seq(
        fromHie .VSEIE.valid -> fromHie .VSEIE.bits,
        fromVSie.VSEIE.valid -> fromVSie.VSEIE.bits,
      ))
    }

    // bit 12 SGEIE
    when(fromHie.SGEIE.valid) {
      reg.SGEIE := fromHie.SGEIE.bits
    }

    // bit 13~63 LCIP
    reg.getLocal lazyZip fromSie.getLocal lazyZip fromVSie.getLocal foreach { case (rLCIE, sieLCIE, vsieLCIE) =>
      when (sieLCIE.valid || vsieLCIE.valid) {
        rLCIE := Mux1H(Seq(
          sieLCIE .valid -> sieLCIE .bits,
          vsieLCIE.valid -> vsieLCIE.bits,
        ))
      }
    }

    // 14~63 read only 0
    regOut.getLocal.filterNot(_.lsb == InterruptNO.COI).foreach(_ := 0.U)
  }).setAddr(CSRs.mie)

  val mtvec = Module(new CSRModule("Mtvec", new XtvecBundle))
    .setAddr(CSRs.mtvec)

  // Todo: support "Stimecmp/Vstimecmp" Extension, Version 1.0.0
  // Todo: support Sscounterenw Extension
  val mcounteren = Module(new CSRModule("Mcounteren", new Counteren))
    .setAddr(CSRs.mcounteren)

  val mvien = Module(new CSRModule("Mvien", new MvienBundle))
    .setAddr(CSRs.mvien)

  val mvip = Module(new CSRModule("Mvip", new MvipBundle)
    with HasIpIeBundle
    with HasMachineEnvBundle
  {
    val toMip = IO(new MvipToMip).connectZeroNonRW
    val fromMip = IO(Flipped(new MipToMvip))
    val fromSip = IO(Flipped(new SipToMvip))
    val fromVSip = IO(Flipped(new VSipToMvip))

    // When bit 1 of mvien is zero, bit 1(SSIP) of mvip is an alias of the same bit (SSIP) of mip.
    // But when bit 1 of mvien is one, bit 1(SSIP) of mvip is a separate writable bit independent of mip.SSIP.
    // When the value of bit 1 of mvien is changed from zero to one, the value of bit 1 of mvip becomes UNSPECIFIED.
    // XiangShan will keep the value in mvip.SSIP when mvien.SSIE is changed from zero to one
    reg.SSIP := Mux(wen && mvien.SSIE.asBool, wdata.SSIP, reg.SSIP)
    regOut.SSIP := Mux(mvien.SSIE.asBool, reg.SSIP, mip.SSIP)
    toMip.SSIP.valid := wen && !mvien.SSIE.asBool
    toMip.SSIP.bits := wdata.SSIP

    // Bit 5 of mvip is an alias of the same bit (STIP) in mip when that bit is writable in mip.
    // When STIP is not writable in mip (such as when menvcfg.STCE = 1), bit 5 of mvip is read-only zero.
    // Todo: check mip writable when menvcfg.STCE = 1
    regOut.STIP := Mux(this.menvcfg.STCE.asBool, 0.U, mip.STIP.asBool)
    // Don't update mip.STIP when menvcfg.STCE is 1
    toMip.STIP.valid := wen && !this.menvcfg.STCE.asBool
    toMip.STIP.bits := wdata.STIP

    // When bit 9 of mvien is zero, bit 9 of mvip is an alias of the software-writable bit 9 of mip (SEIP).
    // But when bit 9 of mvien is one, bit 9 of mvip is a writable bit independent of mip.SEIP.
    // Unlike for bit 1, changing the value of bit 9 of mvien does not affect the value of bit 9 of mvip.
    toMip.SEIP.valid := wen && !mvien.SEIE.asUInt.asBool
    toMip.SEIP.bits := wdata.SEIP
    when (fromMip.SEIP.valid) {
      reg.SEIP := fromMip.SEIP.bits
    }

    // write from sip
    when (fromSip.SSIP.valid) {
      reg.SSIP := fromSip.SSIP.bits
    }

    reg.getLocal lazyZip fromSip.getLocal lazyZip fromVSip.getLocal foreach { case (rLCIP, sipLCIP, vsipLCIP) =>
      // sip should assert valid when mideleg=0 && mvien=1
      when (sipLCIP.valid || vsipLCIP.valid) {
        rLCIP := Mux1H(Seq(
          sipLCIP .valid -> sipLCIP .bits,
          vsipLCIP.valid -> vsipLCIP.bits,
        ))
      }
    }
  }).setAddr(CSRs.mvip)

  val menvcfg = Module(new CSRModule("Menvcfg", new MEnvCfg))
    .setAddr(CSRs.menvcfg)

  val mcountinhibit = Module(new CSRModule("Mcountinhibit", new McountinhibitBundle))
    .setAddr(CSRs.mcountinhibit)

  val mhpmevents: Seq[CSRModule[_]] = (3 to 0x1F).map(num =>
    Module(new CSRModule(s"Mhpmevent$num") with HasPerfEventBundle {
      regOut := perfEvents(num - 3)
    })
      .setAddr(CSRs.mhpmevent3 - 3 + num)
  )

  val mscratch = Module(new CSRModule("Mscratch"))
    .setAddr(CSRs.mscratch)

  val mepc = Module(new CSRModule("Mepc", new Epc) with TrapEntryMEventSinkBundle {
    rdata := SignExt(Cat(reg.epc.asUInt, 0.U(1.W)), XLEN)
  })
    .setAddr(CSRs.mepc)

  val mcause = Module(new CSRModule("Mcause", new CauseBundle) with TrapEntryMEventSinkBundle)
    .setAddr(CSRs.mcause)

  val mtval = Module(new CSRModule("Mtval") with TrapEntryMEventSinkBundle)
    .setAddr(CSRs.mtval)

  val mip = Module(new CSRModule("Mip", new MipBundle)
    with HasIpIeBundle
    with HasExternalInterruptBundle
    with HasMachineEnvBundle
  {
    // Alias write in
    val fromMvip = IO(Flipped(new MvipToMip))
    val fromSip  = IO(Flipped(new SipToMip))
    val fromVSip = IO(Flipped(new VSipToMip))
    // Alias write out
    val toMvip   = IO(new MipToMvip).connectZeroNonRW
    val toHvip   = IO(new MipToHvip).connectZeroNonRW

    // bit 1 SSIP
    when (fromMvip.SSIP.valid || fromSip.SSIP.valid) {
      reg.SSIP := Mux1H(Seq(
        fromMvip.SSIP.valid -> fromMvip.SSIP.bits,
        fromSip .SSIP.valid -> fromSip .SSIP.bits,
      ))
    }

    // bit 2 VSSIP reg in hvip
    // alias of hvip.VSSIP
    toHvip.VSSIP.valid := wen
    toHvip.VSSIP.bits  := wdata.VSSIP
    regOut.VSSIP := hvip.VSSIP

    // bit 3 MSIP is read-only in mip, and is written by accesses to memory-mapped control registers,
    // which are used by remote harts to provide machine-level interprocessor interrupts.
    regOut.MSIP := platformIRP.MSIP

    // bit 5 STIP
    // If the stimecmp (supervisor-mode timer compare) register is implemented(menvcfg.STCE=1), STIP is read-only in mip.
    regOut.STIP := Mux(this.menvcfg.STCE.asBool, platformIRP.STIP, reg.STIP.asBool)
    when ((wen || fromMvip.STIP.valid) && !this.menvcfg.STCE) {
      reg.STIP := Mux1H(Seq(
        wen -> wdata.STIP,
        fromMvip.STIP.valid -> fromMvip.STIP.bits,
      ))
    }

    // bit 6 VSTIP
    regOut.VSTIP := hvip.VSTIP || platformIRP.VSTIP

    // bit 7 MTIP is read-only in mip, and is cleared by writing to the memory-mapped machine-mode timer compare register
    regOut.MTIP := platformIRP.MTIP

    // bit 9 SEIP
    // When bit 9 of mvien is zero, the value of bit 9 of mvip is logically ORed into the readable value of mip.SEIP.
    // when bit 9 of mvien is one, bit SEIP in mip is read-only and does not include the value of bit 9 of mvip.
    //
    // As explained in this issue(https://github.com/riscv/riscv-aia/issues/64),
    // when mvien[9]=0, mip.SEIP is a software-writable bit and is special in its read value, which is the logical-OR of
    // mip.SEIP reg and other source from the interrupt controller.
    // mvip.SEIP is alias of mip.SEIP's reg part, and is independent of the other source from the interrupt controller.
    //
    // mip.SEIP is implemented as the alias of mvip.SEIP when mvien=0
    // the read valid of SEIP is ORed by mvip.SEIP and the other source from the interrupt controller.

    toMvip.SEIP.valid := wen && !mvien.SSIE
    toMvip.SEIP.bits := wdata.SEIP
    // When mvien.SEIE = 0, mip.SEIP is alias of mvip.SEIP.
    // Otherwise, mip.SEIP is read only 0
    regOut.SEIP := Mux(!mvien.SEIE, mvip.SEIP.asUInt, 0.U)
    rdataFields.SEIP := regOut.SEIP || platformIRP.SEIP

    // bit 10 VSEIP
    regOut.VSEIP := hvip.VSEIP || platformIRP.VSEIP || hgeip.ip.asUInt(hstatusVGEIN.asUInt)

    // bit 11 MEIP is read-only in mip, and is set and cleared by a platform-specific interrupt controller.
    regOut.MEIP := platformIRP.MEIP

    // bit 12 SGEIP
    regOut.SGEIP := Cat(hgeip.asUInt & hgeie.asUInt).orR

    // bit 13 LCOFIP
    when (fromSip.LCOFIP.valid || fromVSip.LCOFIP.valid) {
      reg.LCOFIP := Mux1H(Seq(
        fromSip.LCOFIP.valid -> fromSip.LCOFIP.bits,
        fromVSip.LCOFIP.valid -> fromVSip.LCOFIP.bits,
      ))
    }
  }).setAddr(CSRs.mip)

  val mtinst = Module(new CSRModule("Mtinst") with TrapEntryMEventSinkBundle)
    .setAddr(CSRs.mtinst)

  val mtval2 = Module(new CSRModule("Mtval2") with TrapEntryMEventSinkBundle)
    .setAddr(CSRs.mtval2)

  val mseccfg = Module(new CSRModule("Mseccfg", new CSRBundle {
    val PMM   = RO(33, 32)
    val SSEED = RO(     9)
    val USEED = RO(     8)
    val RLB   = RO(     2)
    val MMWP  = RO(     1)
    val MML   = RO(     0)
  })).setAddr(CSRs.mseccfg)

  val mcycle = Module(new CSRModule("Mcycle") with HasMachineCounterControlBundle {
    reg.ALL := Mux(!mcountinhibit.CY.asUInt.asBool, reg.ALL.asUInt + 1.U, reg.ALL.asUInt)
  }).setAddr(CSRs.mcycle)


  val minstret = Module(new CSRModule("Minstret") with HasMachineCounterControlBundle with HasRobCommitBundle {
    reg.ALL := Mux(!mcountinhibit.IR.asUInt.asBool && robCommit.instNum.valid, reg.ALL.asUInt + robCommit.instNum.bits, reg.ALL.asUInt)
  }).setAddr(CSRs.minstret)

  // Todo: guarded by mcountinhibit
  val mhpmcounters: Seq[CSRModule[_]] = (3 to 0x1F).map(num =>
    Module(new CSRModule(s"Mhpmcounter$num") with HasMachineCounterControlBundle with HasPerfCounterBundle {
      reg.ALL := Mux(mcountinhibit.asUInt(num) | perfEventscounten(num - 3), reg.ALL.asUInt, reg.ALL.asUInt + perf(num - 3).value)
    }).setAddr(CSRs.mhpmcounter3 - 3 + num)
  )

  val mvendorid = Module(new CSRModule("Mvendorid") { rdata := 0.U })
    .setAddr(CSRs.mvendorid)

  // architecture id for XiangShan is 25
  // see https://github.com/riscv/riscv-isa-manual/blob/master/marchid.md
  val marchid = Module(new CSRModule("Marchid", new CSRBundle {
    val ALL = MarchidField(63, 0).withReset(MarchidField.XSArchid)
  })).setAddr(CSRs.marchid)

  val mimpid = Module(new CSRModule("Mimpid", new CSRBundle {
    val ALL = RO(0).withReset(0.U)
  }))
    .setAddr(CSRs.mimpid)

  val mhartid = Module(new CSRModule("Mhartid", new CSRBundle {
    val ALL = RO(7, 0)
  }) {
    val hartid = IO(Input(UInt(hartIdLen.W)))
    this.reg.ALL := utils.HackedAPI.HackedRegEnable(hartid, reset.asBool)
  })
    .setAddr(CSRs.mhartid)

  val mconfigptr = Module(new CSRModule("Mconfigptr"))
    .setAddr(CSRs.mconfigptr)

  val machineLevelCSRMods: Seq[CSRModule[_]] = Seq(
    mstatus,
    misa,
    medeleg,
    mideleg,
    mie,
    mtvec,
    mcounteren,
    mvien,
    mvip,
    menvcfg,
    mcountinhibit,
    mscratch,
    mepc,
    mcause,
    mtval,
    mip,
    mtinst,
    mtval2,
    mseccfg,
    mcycle,
    minstret,
    mvendorid,
    marchid,
    mimpid,
    mhartid,
    mconfigptr,
  ) ++ mhpmevents ++ mhpmcounters

  val machineLevelCSRMap: SeqMap[Int, (CSRAddrWriteBundle[_], Data)] = SeqMap.from(
    machineLevelCSRMods.map(csr => (csr.addr -> (csr.w -> csr.rdata))).iterator
  )

  val machineLevelCSROutMap: SeqMap[Int, UInt] = SeqMap.from(
    machineLevelCSRMods.map(csr => (csr.addr -> csr.regOut.asInstanceOf[CSRBundle].asUInt)).iterator
  )

  // perf tmp
  val perfEvents = List.fill(8)(RegInit("h0000000000".U(XLEN.W))) ++
    List.fill(8)(RegInit("h4010040100".U(XLEN.W))) ++
    List.fill(8)(RegInit("h8020080200".U(XLEN.W))) ++
    List.fill(5)(RegInit("hc0300c0300".U(XLEN.W)))

  mhpmevents.foreach { mod =>
    mod match {
      case m: HasPerfEventBundle =>
        m.perfEvents := perfEvents
      case _ =>
    }
  }

}

class MstatusBundle extends CSRBundle {

  val SIE  = CSRRWField     (1).withReset(0.U)
  val MIE  = CSRRWField     (3).withReset(0.U)
  val SPIE = CSRRWField     (5).withReset(0.U)
  val UBE  = CSRROField     (6).withReset(0.U)
  val MPIE = CSRRWField     (7).withReset(0.U)
  val SPP  = CSRRWField     (8).withReset(0.U)
  val VS   = ContextStatus  (10,  9).withReset(ContextStatus.Off)
  val MPP  = PrivMode       (12, 11).withReset(PrivMode.U)
  val FS   = ContextStatus  (14, 13).withReset(ContextStatus.Off)
  val XS   = ContextStatusRO(16, 15).withReset(0.U)
  val MPRV = CSRRWField     (17).withReset(0.U)
  val SUM  = CSRRWField     (18).withReset(0.U)
  val MXR  = CSRRWField     (19).withReset(0.U)
  val TVM  = CSRRWField     (20).withReset(0.U)
  val TW   = CSRRWField     (21).withReset(0.U)
  val TSR  = CSRRWField     (22).withReset(0.U)
  val UXL  = XLENField      (33, 32).withReset(XLENField.XLEN64)
  val SXL  = XLENField      (35, 34).withReset(XLENField.XLEN64)
  val SBE  = CSRROField     (36).withReset(0.U)
  val MBE  = CSRROField     (37).withReset(0.U)
  val GVA  = CSRRWField     (38).withReset(0.U)
  val MPV  = VirtMode       (39).withReset(0.U)
  val SD   = CSRROField     (63,
    (_, _) => FS === ContextStatus.Dirty || VS === ContextStatus.Dirty
  )
}

class MstatusModule(implicit override val p: Parameters) extends CSRModule("MStatus", new MstatusBundle)
  with TrapEntryMEventSinkBundle
  with TrapEntryHSEventSinkBundle
  with DretEventSinkBundle
  with MretEventSinkBundle
  with SretEventSinkBundle
  with HasRobCommitBundle
{
  val mstatus = IO(Output(bundle))
  val sstatus = IO(Output(new SstatusBundle))

  val wAliasSstatus = IO(Input(new CSRAddrWriteBundle(new SstatusBundle)))

  // write connection
  this.wfn(reg)(Seq(wAliasSstatus))

  when (robCommit.fsDirty) {
    assert(reg.FS =/= ContextStatus.Off, "The [m|s]status.FS should not be Off when set dirty, please check decode")
    reg.FS := ContextStatus.Dirty
  }

  when (robCommit.vsDirty) {
    assert(reg.VS =/= ContextStatus.Off, "The [m|s]status.VS should not be Off when set dirty, please check decode")
    reg.VS := ContextStatus.Dirty
  }

  // read connection
  mstatus :|= reg
  sstatus := mstatus
  rdata := mstatus.asUInt
}

class MisaBundle extends CSRBundle {
  // Todo: reset with ISA string
  val A = RO( 0).withReset(1.U) // Atomic extension
  val B = RO( 1).withReset(0.U) // Reserved
  val C = RO( 2).withReset(1.U) // Compressed extension
  val D = RO( 3).withReset(1.U) // Double-precision floating-point extension
  val E = RO( 4).withReset(0.U) // RV32E/64E base ISA
  val F = RO( 5).withReset(1.U) // Single-precision floating-point extension
  val G = RO( 6).withReset(0.U) // Reserved
  val H = RO( 7).withReset(1.U) // Hypervisor extension
  val I = RO( 8).withReset(1.U) // RV32I/64I/128I base ISA
  val J = RO( 9).withReset(0.U) // Reserved
  val K = RO(10).withReset(0.U) // Reserved
  val L = RO(11).withReset(0.U) // Reserved
  val M = RO(12).withReset(1.U) // Integer Multiply/Divide extensi
  val N = RO(13).withReset(0.U) // Tentatively reserved for User-Level Interrupts extension
  val O = RO(14).withReset(0.U) // Reserved
  val P = RO(15).withReset(0.U) // Tentatively reserved for Packed-SIMD extension
  val Q = RO(16).withReset(0.U) // Quad-precision floating-point extension
  val R = RO(17).withReset(0.U) // Reserved
  val S = RO(18).withReset(1.U) // Supervisor mode implemented
  val T = RO(19).withReset(0.U) // Reserved
  val U = RO(20).withReset(1.U) // User mode implemented
  val V = RO(21).withReset(1.U) // Vector extension
  val W = RO(22).withReset(0.U) // Reserved
  val X = RO(23).withReset(0.U) // Non-standard extensions present
  val Y = RO(24).withReset(0.U) // Reserved
  val Z = RO(25).withReset(0.U) // Reserved
  val MXL = XLENField(63, 62).withReset(XLENField.XLEN64)

  def getISAString = this.getFields.filter(x => x != MXL && x.init.litValue == 1).sortBy(_.lsb).map(x => ('A' + x.msb).toChar).mkString
}

class MedelegBundle extends ExceptionBundle {
  this.getALL.foreach(_.setRW().withReset(0.U))
  this.EX_MCALL.setRO().withReset(0.U) // never delegate machine level ecall
  this.EX_BP.setRO().withReset(0.U)    // Parter 5.4 in debug spec. tcontrol is implemented. medeleg [3] is hard-wired to 0.
}

class MidelegBundle extends InterruptBundle {
  this.getALL.foreach(_.setRW().withReset(0.U))
  // Don't delegate Machine level interrupts
  this.getM.foreach(_.setRO().withReset(0.U))
  // Ref: 13.4.2. Machine Interrupt Delegation Register (mideleg)
  // When the hypervisor extension is implemented, bits 10, 6, and 2 of mideleg (corresponding to the standard VS-level
  // interrupts) are each read-only one.
  this.getVS.foreach(_.setRO().withReset(1.U))
  // bit 12 of mideleg (corresponding to supervisor-level guest external interrupts) is also read-only one.
  // VS-level interrupts and guest external interrupts are always delegated past M-mode to HS-mode.
  this.SGEI.setRO().withReset(1.U)
}

class MieBundle extends InterruptEnableBundle {
  this.getNonLocal.foreach(_.setRW().withReset(0.U))
}

class MipBundle extends InterruptPendingBundle {
  // Ref: riscv privileged spec - 18.4.3. Machine Interrupt (mip and mie) Registers
  // Bits SGEIP, VSEIP, VSTIP, and VSSIP in mip are aliases for the same bits in hypervisor CSR hip
  //
  // We implement SGEIP, VSEIP, VSTIP, and VSSIP in mip are registers,
  // while these bits in hip are aliases for the same bits in mip.
  //
  // Ref: riscv interrupt spec - 2.1 Machine-level CSRs
  // Existing CSRs mie, mip, and mideleg are widended to 64 bits to support a total of 64 interrupt causes.
  this.getHS.foreach(_.setRW().withReset(0.U))
  this.getVS.foreach(_.setRW().withReset(0.U))
  this.LCOFIP.setRW().withReset(0.U)
}

class MvienBundle extends InterruptEnableBundle {
  // Ref: riscv interrupt spec - 5.3 Interrupt filtering and virtual interrupts for supervisor level
  // It is strongly recommended that bit 9 of mvien be writable.
  // It is strongly recommended that bit 1 of mvien also be writable.
  // A bit in mvien can be set to 1 only for major interrupts 1, 9, and 13–63.
  this.SSIE.setRW().withReset(0.U)
  this.SEIE.setRW().withReset(0.U)
  this.getLocal.foreach(_.setRW().withReset(0.U))
}

class MvipBundle extends InterruptPendingBundle {
  this.getHS.foreach(_.setRW().withReset(0.U))
  this.getLocal.foreach(_.setRW().withReset(0.U))
}

class Epc extends CSRBundle {
  import CSRConfig._

  val epc = RW(VaddrMaxWidth - 1, 1)
}

class McountinhibitBundle extends CSRBundle {
  val CY = RW(0)
  val IR = RW(2)
  val HPM3 = RW(31, 3)
}

class MEnvCfg extends EnvCfg {
  if (CSRConfig.EXT_SSTC) {
    this.STCE.setRW().withReset(1.U)
  }
}

object MarchidField extends CSREnum with ROApply {
  val XSArchid = Value(25.U)
}

class MieToHie extends Bundle {
  val VSSIE = ValidIO(RW(0))
  val VSTIE = ValidIO(RW(0))
  val VSEIE = ValidIO(RW(0))
  val SGEIE = ValidIO(RW(0))
}

class MvipToMip extends IpValidBundle {
  this.getHS.foreach(_.bits.setRW())
}

class HipToMip extends IpValidBundle {
  // Only hip.VSSIP is writable
  this.VSSIP.bits.setRW()
}

class VSipToMip extends IpValidBundle {
  this.LCOFIP.bits.setRW()
}

class MipToHvip extends IpValidBundle {
  this.VSSIP.bits.setRW()
}

class MipToMvip extends IpValidBundle {
  this.SEIP.bits.setRW()
}

trait HasMipToAlias { self: CSRModule[_] =>
  val mipAlias = Output(new MipBundle)
}

trait HasMachineDelegBundle { self: CSRModule[_] =>
  val mideleg = IO(Input(new MidelegBundle))
  val medeleg = IO(Input(new MedelegBundle))
}

trait HasExternalInterruptBundle {
  val platformIRP = IO(new Bundle {
    val MEIP  = Input(Bool())
    val MTIP  = Input(Bool())
    val MSIP  = Input(Bool())
    val SEIP  = Input(Bool())
    val STIP  = Input(Bool())
    val VSEIP = Input(Bool())
    val VSTIP = Input(Bool())
    // debug interrupt from debug module
    val debugIP = Input(Bool())
  })
}

trait HasMachineCounterControlBundle { self: CSRModule[_] =>
  val mcountinhibit = IO(Input(new McountinhibitBundle))
}

trait HasRobCommitBundle { self: CSRModule[_] =>
  val robCommit = IO(Input(new RobCommitCSR))
}

trait HasMachineEnvBundle { self: CSRModule[_] =>
  val menvcfg = IO(Input(new MEnvCfg))
}

trait HasPerfCounterBundle { self: CSRModule[_] =>
  val perfEventscounten = IO(Input(Vec(perfCntNum, Bool())))
  val perf = IO(Input(Vec(perfCntNum, new PerfEvent)))
}

trait HasPerfEventBundle { self: CSRModule[_] =>
  val perfEvents = IO(Input(Vec(perfCntNum, UInt(XLEN.W))))
}