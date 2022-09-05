package ucesoft.cbm.vic20

import ucesoft.cbm.{CBMComponentType, ChipID}
import ucesoft.cbm.ChipID.ID
import ucesoft.cbm.cpu.{RAMComponent, ROM}
import ucesoft.cbm.misc.TestCart
import ucesoft.cbm.peripheral.drive.VIA
import ucesoft.cbm.peripheral.vic.VIC_I

import java.io.{ObjectInputStream, ObjectOutputStream}

object VIC20MMU {
  val KERNAL_ROM = new ROM(null, "Kernal", 0, 8192, ROM.VIC20_KERNAL_ROM_PROP)
  val BASIC_ROM = new ROM(null, "Basic", 0, 8192, ROM.VIC20_BASIC_ROM_PROP)
  val CHAR_ROM = new ROM(null, "Char", 0, 4096, ROM.VIC20_CHAR_ROM_PROP)

  object VICExpansion extends Enumeration {
    val _NO = Value(0)
    val _3K = Value(1)
    val _8K = Value(2)
    val _16K = Value(2 + 4)
    val _24K = Value(2 + 4 + 8)
    val _ALL = Value(1 + 2 + 4 + 8 + 16)
  }
}

class VIC20MMU extends RAMComponent {
  import VIC20MMU.VICExpansion

  override val isRom = false
  override val length: Int = 0x10000
  override val startAddress = 0
  override val name = "VIC20MMU"
  override def isActive = true
  override val componentID: String = "VIC20MMU"
  override val componentType = CBMComponentType.MEMORY

  private trait RW {
    def read(address:Int,chipID: ID): Int
    def write(address: Int, value: Int): Unit
  }

  private val memRW = Array.ofDim[RW](0x10000)
  private val ram = Array.ofDim[Int](0x10000)
  private var basicROM : Array[Int] = _
  private var kernelROM : Array[Int] = _
  private var charROM : Array[Int] = _
  /*
    Index   Memory
    0       0400 - 0FFF
    1       2000 - 3FFF
    2       4000 - 5FFF
    3       6000 - 7FFF
    4       A000 - BFFF
   */
  private val expansionBlocks = Array(false,false,false,false,false) // BLOCK 0 - BLOCK 4
  private var lastByteOnBUS = 0
  private var dontUpdateLastByteOnBUS = false

  private var via1,via2 : VIA = _
  private var vic : VIC_I = _

  // Constructor
  setExpansion(VICExpansion._NO)

  def setBasicROM(rom:Array[Int]): Unit = basicROM = rom
  def setKernelROM(rom:Array[Int]): Unit = kernelROM = rom
  def setCharROM(rom:Array[Int]): Unit = charROM = rom

  def setExpansion(e:VICExpansion.Value): Unit = {
    val exp = e.id
    var b = 0
    while (b < 5) {
      val enabled = (exp & (1 << b)) > 0
      expansionBlocks(b) = enabled
      b += 1
    }
  }

  def setIO(via1:VIA,via2:VIA,vic:VIC_I): Unit = {
    this.via1 = via1
    this.via2 = via2
    this.vic = vic
  }

  private object BASICROM_RW extends RW {
    override def read(address: Int, chipID: ID): Int = basicROM(address & 0x1FFF)
    override def write(address: Int, value: Int): Unit = {}
  }
  private object KERNELROM_RW extends RW {
    override def read(address: Int, chipID: ID): Int = kernelROM(address & 0x1FFF)
    override def write(address: Int, value: Int): Unit = {}
  }
  private object CHARROM_RW extends RW {
    override def read(address: Int, chipID: ID): Int = charROM(address & 0xFFF)
    override def write(address: Int, value: Int): Unit = {}
  }

  private object RAM_RW extends RW {
    override def read(address: Int, chipID: ID): Int = ram(address & 0xFFFF)
    override def write(address: Int, value: Int): Unit = ram(address & 0xFFFF) = value
  }
  private object COLOR_RW extends RW {
    override def read(address: Int, chipID: ID): Int = {
      val color = ram(address & 0xFFFF) & 0xF
      if (chipID == ChipID.VIC) {
        dontUpdateLastByteOnBUS = true
        color
      }
      else lastByteOnBUS & 0xF0 | color
    }
    override def write(address: Int, value: Int): Unit = ram(address & 0xFFFF) = value & 0xF
  }
  private class EXPRAM_BLOCK_RW(block:Int) extends RW {
    override def read(address: Int, chipID: ID): Int = {
      if (chipID == ChipID.VIC)
        lastByteOnBUS
      else {
        if (expansionBlocks(block)) ram(address & 0xFFFF)
        else lastByteOnBUS
      }
    }
    override def write(address: Int, value: Int): Unit = if (expansionBlocks(block)) ram(address & 0xFFFF) = value
  }
  private class IOBLOCK_RW(block:Int) extends RW {
    override def read(address: Int, chipID: ID): Int = lastByteOnBUS
    override def write(address: Int, value: Int): Unit = {}
  }
  private object VIC_RW extends RW {
    override def read(address: Int, chipID: ID): Int = vic.read(address)
    override def write(address: Int, value: Int): Unit = vic.write(address,value)
  }
  private object VIA1_VIA2_RW extends RW {
    override def read(address: Int, chipID: ID): Int = {
      if ((address & 0x30) == 0) lastByteOnBUS
      else {
        var tmp = 0xFF
        if ((address & 0x10) > 0) tmp = via1.read(address)
        if ((address & 0x20) > 0) tmp = via2.read(address)
        tmp
      }
    }
    override def write(address: Int, value: Int): Unit = {
      if ((address & 0x10) > 0) via1.write(address,value)
      if ((address & 0x20) > 0) via2.write(address,value)
    }
  }

  override def init(): Unit = {
    val exp0 = new EXPRAM_BLOCK_RW(0)
    val exp1 = new EXPRAM_BLOCK_RW(1)
    val exp2 = new EXPRAM_BLOCK_RW(2)
    val exp3 = new EXPRAM_BLOCK_RW(3)
    val exp4 = new EXPRAM_BLOCK_RW(4)
    val io1 = new IOBLOCK_RW(1)
    val io2 = new IOBLOCK_RW(2)

    for(r <- 0 until 0x10000) {
      memRW(r) =
        if (r < 0x400) RAM_RW
        else if (r < 0x1000) exp0
        else if (r < 0x2000) RAM_RW
        else if (r < 0x4000) exp1
        else if (r < 0x6000) exp2
        else if (r < 0x8000) exp3
        else if (r < 0x9000) CHARROM_RW
        else if (r < 0x90FF) VIC_RW
        else if (r < 0x93FF) VIA1_VIA2_RW
        else if (r < 0x9800) COLOR_RW
        else if (r < 0x9C00) io1
        else if (r < 0xA000) io2
        else if (r < 0xC000) exp4
        else if (r < 0xE000) BASICROM_RW
        else KERNELROM_RW
    }
  }
  override def reset(): Unit = {}

  final override def read(address: Int, chipID: ID): Int = {
    val read = memRW(address).read(address, chipID)
    if (!dontUpdateLastByteOnBUS) lastByteOnBUS = read
    dontUpdateLastByteOnBUS = false
    read
  }
  final override def write(address: Int, value: Int, chipID: ID): Unit = {
    memRW(address).write(address, value)
    TestCart.write(address,value)
    lastByteOnBUS = value
  }

  override protected def saveState(out: ObjectOutputStream): Unit = ???
  override protected def loadState(in: ObjectInputStream): Unit = ???
  override protected def allowsStateRestoring: Boolean = true
}
