package hwacha

import Chisel._
import Node._
import Constants._
import uncore.constants.MemoryOpConstants._

class MemIF(implicit conf: HwachaConfiguration) extends Module
{
  val io = new Bundle {
    val vaq = Decoupled(new io_vpaq_bundle()).flip
    val vsdq = Decoupled(Bits(width = 65)).flip
    val vldq = Valid(new VLDQEnqBundle(66, 4, log2Up(conf.nvldq)))
    val vldq_rtag = Decoupled(Bits(width = log2Up(conf.nvldq))).flip

    val mem_req = new io_dmem_req()
    val mem_resp = new io_dmem_resp().flip

    val store_ack = Bool(OUTPUT)

    val prec = Bits(INPUT, SZ_PREC)
  }

  val memtag = Module(new MemTag())

  val ex_pf_cmd = is_mcmd_pf(io.vaq.bits.cmd)
  val ex_load_cmd = is_mcmd_load(io.vaq.bits.cmd)
  val ex_store_cmd = is_mcmd_store(io.vaq.bits.cmd)
  val ex_amo_cmd = is_mcmd_amo(io.vaq.bits.cmd)

  val ex_pf_val = ex_pf_cmd && io.vaq.valid
  val ex_load_val = ex_load_cmd && memtag.io.out.valid
  val ex_store_val = ex_store_cmd && io.vaq.valid && io.vsdq.valid
  val ex_amo_val = ex_amo_cmd && io.vaq.valid && io.vsdq.valid && io.vldq_rtag.valid

  memtag.io.prec := io.prec
  memtag.io.typ.valid := io.vaq.valid && (ex_load_cmd || ex_amo_cmd)
  memtag.io.typ.bits.size := io.vaq.bits.typ
  memtag.io.typ.bits.float := io.vaq.bits.typ_float
  memtag.io.tag.valid := io.vldq_rtag.valid
  memtag.io.tag.bits := io.vldq_rtag.bits

  memtag.io.out.ready := io.mem_req.ready

  io.mem_req.valid := ex_pf_val || ex_load_val || ex_store_val || ex_amo_val
  io.mem_req.bits.cmd := io.vaq.bits.cmd
  io.mem_req.bits.typ := io.vaq.bits.typ
  io.mem_req.bits.addr := io.vaq.bits.addr.toUInt
  io.mem_req.bits.data := io.vsdq.bits // delayed one cycle in cpu
  io.mem_req.bits.tag := Cat(memtag.io.out.bits.major, memtag.io.out.bits.minor, io.vaq.bits.typ_float) // delayed one cycle in cpu
  io.mem_req.bits.phys := Bool(true)

  io.vaq.ready :=
    io.mem_req.ready && (
      ex_pf_cmd ||
      ex_load_cmd && memtag.io.typ.ready ||
      ex_store_cmd && io.vsdq.valid ||
      ex_amo_cmd && io.vsdq.valid && io.vldq_rtag.valid
    )

  io.vsdq.ready :=
    io.mem_req.ready && (
      ex_store_cmd && io.vaq.valid ||
      ex_amo_cmd && io.vaq.valid && io.vldq_rtag.valid
    )

  io.vldq_rtag.ready :=
    io.mem_req.ready && (
      ex_load_cmd && memtag.io.tag.ready ||
      ex_amo_cmd && io.vaq.valid && io.vsdq.valid
    )

  io.store_ack := io.mem_resp.valid && is_mcmd_store(io.mem_resp.bits.cmd)

  // load data conversion
  val reg_mem_resp = Reg(next=io.mem_resp)
    
  val ldq_sp_bits = Bits(width=33)
  val ldq_dp_bits = Bits(width=65)
  val ldq_hp_bits = Bits(width=16) // no recoding
  
  val load_fp = reg_mem_resp.bits.tag(0)
  val load_fp_d = load_fp && reg_mem_resp.bits.typ === MT_D 
  val load_fp_w = load_fp && reg_mem_resp.bits.typ === MT_W
  val load_fp_h = load_fp && reg_mem_resp.bits.typ === MT_H

  val recode_sp = Module(new hardfloat.float32ToRecodedFloat32)
  recode_sp.io.in := reg_mem_resp.bits.data_subword(31,0)
  ldq_sp_bits := recode_sp.io.out
  
  val recode_dp = Module(new hardfloat.float64ToRecodedFloat64)
  recode_dp.io.in := reg_mem_resp.bits.data_subword
  ldq_dp_bits := recode_dp.io.out

  ldq_hp_bits := reg_mem_resp.bits.data_subword(15,0)

  val load_shift = reg_mem_resp.bits.tag(2,1)
  val load_data_shift = MuxLookup(
    load_shift, UInt(0),
    Array(
      Bits("b01") -> UInt(16),
      Bits("b10") -> UInt(33),
      Bits("b11") -> UInt(49)
  ))

  io.vldq.valid := reg_mem_resp.valid && reg_mem_resp.bits.has_data
  io.vldq.bits.data := MuxCase(
    Cat(Bits(0,1),reg_mem_resp.bits.data_subword(63,0)), 
    Array(
      (load_fp_d) -> ldq_dp_bits,
      (load_fp_w) -> Cat(Bits("hFFFFFFFF",32), ldq_sp_bits),
      (load_fp_h) -> Cat(Bits("h1FFFFFFFFFFFF",49), ldq_hp_bits)
  )) << load_data_shift
  io.vldq.bits.mask := MuxCase(
    Bits("b1111"),
    Array(
      (load_fp && (io.prec === PREC_SINGLE)) -> Bits("b0011"),
      (load_fp && (io.prec === PREC_HALF))   -> Bits("b0001")
  )) << load_shift
  io.vldq.bits.rtag := reg_mem_resp.bits.tag.toUInt >> UInt(3)
}