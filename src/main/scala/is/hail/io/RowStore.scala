package is.hail.io

import is.hail.annotations._
import is.hail.expr.JSONAnnotationImpex
import is.hail.expr.types._
import is.hail.io.compress.LZ4Utils
import is.hail.rvd.{OrderedRVDPartitioner, OrderedRVDSpec, RVDSpec, UnpartitionedRVDSpec}
import is.hail.utils._
import org.apache.spark.rdd.RDD
import org.json4s.{Extraction, JValue}
import org.json4s.jackson.JsonMethods
import java.io.{Closeable, InputStream, OutputStream}

trait BufferSpec extends Serializable {
  def buildInputBuffer(in: InputStream): InputBuffer

  def buildOutputBuffer(out: OutputStream): OutputBuffer
}

final class LEB128BufferSpec(child: BufferSpec) extends BufferSpec {
  def buildInputBuffer(in: InputStream): InputBuffer = new LEB128InputBuffer(child.buildInputBuffer(in))

  def buildOutputBuffer(out: OutputStream): OutputBuffer = new LEB128OutputBuffer(child.buildOutputBuffer(out))
}

final class BlockingBufferSpec(blockSize: Int, child: BlockBufferSpec) extends BufferSpec {
  def buildInputBuffer(in: InputStream): InputBuffer = new BlockingInputBuffer(blockSize, child.buildInputBuffer(in))

  def buildOutputBuffer(out: OutputStream): OutputBuffer = new BlockingOutputBuffer(blockSize, child.buildOutputBuffer(out))
}

trait BlockBufferSpec extends Serializable {
  def buildInputBuffer(in: InputStream): InputBlockBuffer

  def buildOutputBuffer(out: OutputStream): OutputBlockBuffer
}

final class LZ4BlockBufferSpec(blockSize: Int, child: BlockBufferSpec) extends BlockBufferSpec {
  def buildInputBuffer(in: InputStream): InputBlockBuffer = new LZ4InputBlockBuffer(blockSize, child.buildInputBuffer(in))

  def buildOutputBuffer(out: OutputStream): OutputBlockBuffer = new LZ4OutputBlockBuffer(blockSize, child.buildOutputBuffer(out))
}

object StreamBlockBufferSpec {
  def extract(jv: JValue): StreamBlockBufferSpec = new StreamBlockBufferSpec
}

final class StreamBlockBufferSpec extends BlockBufferSpec {
  def buildInputBuffer(in: InputStream): InputBlockBuffer = new StreamBlockInputBuffer(in)

  def buildOutputBuffer(out: OutputStream): OutputBlockBuffer = new StreamBlockOutputBuffer(out)
}

object CodecSpec {
  val default: CodecSpec = new PackCodecSpec(
    new LEB128BufferSpec(
      new BlockingBufferSpec(32 * 1024,
        new LZ4BlockBufferSpec(32 * 1024,
          new StreamBlockBufferSpec))))

  val blockSpecs:  Array[BufferSpec] = Array(
    new BlockingBufferSpec(64 * 1024,
      new StreamBlockBufferSpec),
    new BlockingBufferSpec(32 * 1024,
      new LZ4BlockBufferSpec(32 * 1024,
        new StreamBlockBufferSpec)))

  val bufferSpecs: Array[BufferSpec] = blockSpecs.flatMap { blockSpec =>
    Array(blockSpec,
      new LEB128BufferSpec(blockSpec))
  }

  val codecSpecs: Array[CodecSpec] = bufferSpecs.flatMap { bufferSpec =>
    Array(new PackCodecSpec(bufferSpec))
  }

  val supportedCodecSpecs: Array[CodecSpec] = bufferSpecs.flatMap { bufferSpec =>
    Array(new PackCodecSpec(bufferSpec))
  }
}

trait CodecSpec extends Serializable {
  def buildEncoder(out: OutputStream): Encoder

  def buildDecoder(in: InputStream): Decoder

  override def toString: String = {
    implicit val formats = RVDSpec.formats
    val jv = Extraction.decompose(this)
    JsonMethods.compact(JsonMethods.render(jv))
  }
}

final class PackCodecSpec(child: BufferSpec) extends CodecSpec {
  def buildEncoder(out: OutputStream): Encoder = new PackEncoder(child.buildOutputBuffer(out))

  def buildDecoder(in: InputStream): Decoder = new PackDecoder(child.buildInputBuffer(in))
}

trait OutputBlockBuffer extends Closeable {
  def writeBlock(buf: Array[Byte], len: Int): Unit
}

trait InputBlockBuffer extends Closeable {
  def close(): Unit

  def readBlock(buf: Array[Byte]): Int
}

final class StreamBlockOutputBuffer(out: OutputStream) extends OutputBlockBuffer {
  private val lenBuf = new Array[Byte](4)

  def close() {
    out.close()
  }

  def writeBlock(buf: Array[Byte], len: Int): Unit = {
    Memory.storeInt(lenBuf, 0, len)
    out.write(lenBuf, 0, 4)
    out.write(buf, 0, len)
  }
}

final class StreamBlockInputBuffer(in: InputStream) extends InputBlockBuffer {
  private val lenBuf = new Array[Byte](4)

  def close() {
    in.close()
  }

  def readBlock(buf: Array[Byte]): Int = {
    in.readFully(lenBuf, 0, 4)
    val len = Memory.loadInt(lenBuf, 0)
    in.readFully(buf, 0, len)
    len
  }
}

trait OutputBuffer extends Closeable {
  def flush(): Unit

  def close(): Unit

  def writeByte(b: Byte): Unit

  def writeInt(i: Int): Unit

  def writeLong(l: Long): Unit

  def writeFloat(f: Float): Unit

  def writeDouble(d: Double): Unit

  def writeBytes(region: Region, off: Long, n: Int): Unit

  def writeDoubles(from: Array[Double], fromOff: Int, n: Int): Unit

  def writeDoubles(from: Array[Double]): Unit = writeDoubles(from, 0, from.length)

  def writeBoolean(b: Boolean) {
    writeByte(b.toByte)
  }
}

final class LEB128OutputBuffer(out: OutputBuffer) extends OutputBuffer {
  def flush(): Unit = out.flush()

  def close() {
    out.close()
  }

  def writeByte(b: Byte): Unit = out.writeByte(b)

  def writeInt(i: Int): Unit = {
    var j = i
    do {
      var b = j & 0x7f
      j >>>= 7
      if (j != 0)
        b |= 0x80
      out.writeByte(b.toByte)
    } while (j != 0)
  }

  def writeLong(l: Long): Unit = {
    var j = l
    do {
      var b = j & 0x7f
      j >>>= 7
      if (j != 0)
        b |= 0x80
      out.writeByte(b.toByte)
    } while (j != 0)
  }

  def writeFloat(f: Float): Unit = out.writeFloat(f)

  def writeDouble(d: Double): Unit = out.writeDouble(d)

  def writeBytes(region: Region, off: Long, n: Int): Unit = out.writeBytes(region, off, n)

  def writeDoubles(from: Array[Double], fromOff: Int, n: Int): Unit = out.writeDoubles(from, fromOff, n)
}

final class LZ4OutputBlockBuffer(blockSize: Int, out: OutputBlockBuffer) extends OutputBlockBuffer {
  private val comp = new Array[Byte](4 + LZ4Utils.maxCompressedLength(blockSize))

  def close() {
    out.close()
  }

  def writeBlock(buf: Array[Byte], decompLen: Int): Unit = {
    val compLen = LZ4Utils.compress(comp, 4, buf, decompLen)
    Memory.storeInt(comp, 0, decompLen) // decompLen
    out.writeBlock(comp, compLen + 4)
  }
}

final class BlockingOutputBuffer(blockSize: Int, out: OutputBlockBuffer) extends OutputBuffer {
  private val buf: Array[Byte] = new Array[Byte](blockSize)
  private var off: Int = 0

  private def writeBlock() {
    out.writeBlock(buf, off)
    off = 0
  }

  def flush() {
    writeBlock()
  }

  def close() {
    flush()
    out.close()
  }

  def writeByte(b: Byte) {
    if (off + 1 > buf.length)
      writeBlock()
    Memory.storeByte(buf, off, b)
    off += 1
  }

  def writeInt(i: Int) {
    if (off + 4 > buf.length)
      writeBlock()
    Memory.storeInt(buf, off, i)
    off += 4
  }

  def writeLong(l: Long) {
    if (off + 8 > buf.length)
      writeBlock()
    Memory.storeLong(buf, off, l)
    off += 8
  }

  def writeFloat(f: Float) {
    if (off + 4 > buf.length)
      writeBlock()
    Memory.storeFloat(buf, off, f)
    off += 4
  }

  def writeDouble(d: Double) {
    if (off + 8 > buf.length)
      writeBlock()
    Memory.storeDouble(buf, off, d)
    off += 8
  }

  def writeBytes(fromRegion: Region, fromOff0: Long, n0: Int) {
    assert(n0 >= 0)
    var fromOff = fromOff0
    var n = n0

    while (off + n > buf.length) {
      val p = buf.length - off
      fromRegion.loadBytes(fromOff, buf, off, p)
      off += p
      fromOff += p
      n -= p
      assert(off == buf.length)
      writeBlock()
    }
    fromRegion.loadBytes(fromOff, buf, off, n)
    off += n
  }

  def writeDoubles(from: Array[Double], fromOff0: Int, n0: Int) {
    assert(n0 >= 0)
    assert(fromOff0 >= 0)
    assert(fromOff0 <= from.length - n0)
    var fromOff = fromOff0
    var n = n0

    while (off + (n << 3) > buf.length) {
      val p = (buf.length - off) >>> 3
      Memory.memcpy(buf, off, from, fromOff, p)
      off += (p << 3)
      fromOff += p
      n -= p
      writeBlock()
    }
    Memory.memcpy(buf, off, from, fromOff, n)
    off += (n << 3)
  }
}

trait InputBuffer extends Closeable {
  def close(): Unit

  def readByte(): Byte

  def readInt(): Int

  def readLong(): Long

  def readFloat(): Float

  def readDouble(): Double

  def readBytes(toRegion: Region, toOff: Long, n: Int): Unit
  
  def skipBytes(n: Int): Unit

  def readDoubles(to: Array[Double], off: Int, n: Int): Unit

  def readDoubles(to: Array[Double]): Unit = readDoubles(to, 0, to.length)

  def readBoolean(): Boolean = readByte() != 0
}

final class LEB128InputBuffer(in: InputBuffer) extends InputBuffer {
  def close() {
    in.close()
  }

  def readByte(): Byte = in.readByte()

  def readInt(): Int = {
    var b: Byte = readByte()
    var x: Int = b & 0x7f
    var shift: Int = 7
    while ((b & 0x80) != 0) {
      b = readByte()
      x |= ((b & 0x7f) << shift)
      shift += 7
    }
    x
  }

  def readLong(): Long = {
    var b: Byte = readByte()
    var x: Long = b & 0x7fL
    var shift: Int = 7
    while ((b & 0x80) != 0) {
      b = readByte()
      x |= ((b & 0x7fL) << shift)
      shift += 7
    }
    x
  }

  def readFloat(): Float = in.readFloat()

  def readDouble(): Double = in.readDouble()

  def readBytes(toRegion: Region, toOff: Long, n: Int): Unit = in.readBytes(toRegion, toOff, n)

  def skipBytes(n: Int): Unit = in.skipBytes(n)
  
  def readDoubles(to: Array[Double], toOff: Int, n: Int): Unit = in.readDoubles(to, toOff, n)
}

final class LZ4InputBlockBuffer(blockSize: Int, in: InputBlockBuffer) extends InputBlockBuffer {
  private val comp = new Array[Byte](4 + LZ4Utils.maxCompressedLength(blockSize))

  def close() {
    in.close()
  }

  def readBlock(buf: Array[Byte]): Int = {
    val blockLen = in.readBlock(comp)
    val compLen = blockLen - 4
    val decompLen = Memory.loadInt(comp, 0)

    LZ4Utils.decompress(buf, 0, decompLen, comp, 4, compLen)

    decompLen
  }
}

final class BlockingInputBuffer(blockSize: Int, in: InputBlockBuffer) extends InputBuffer {
  private val buf = new Array[Byte](blockSize)
  private var end: Int = 0
  private var off: Int = 0

  private def readBlock() {
    assert(off == end)
    end = in.readBlock(buf)
    off = 0
  }

  private def ensure(n: Int) {
    if (off == end)
      readBlock()
    assert(off + n <= end)
  }

  def close() {
    in.close()
  }

  def readByte(): Byte = {
    ensure(1)
    val b = Memory.loadByte(buf, off)
    off += 1
    b
  }

  def readInt(): Int = {
    ensure(4)
    val i = Memory.loadInt(buf, off)
    off += 4
    i
  }
  def readLong(): Long = {
    ensure(8)
    val l  = Memory.loadLong(buf, off)
    off += 8
    l
  }

  def readFloat(): Float = {
    ensure(4)
    val f = Memory.loadFloat(buf, off)
    off += 4
    f
  }

  def readDouble(): Double = {
    ensure(8)
    val d = Memory.loadDouble(buf, off)
    off += 8
    d
  }

  def readBytes(toRegion: Region, toOff0: Long, n0: Int) {
    assert(n0 >= 0)
    var toOff = toOff0
    var n = n0

    while (n > 0) {
      if (end == off)
        readBlock()
      val p = math.min(end - off, n)
      assert(p > 0)
      toRegion.storeBytes(toOff, buf, off, p)
      toOff += p
      n -= p
      off += p
    }
  }
  
  def skipBytes(n0: Int) {
    var n = n0
    while (n > 0) {
      if (end == off)
        readBlock()
      val p = math.min(end - off, n)
      n -= p
      off += p
    }
  }

  def readDoubles(to: Array[Double], toOff0: Int, n0: Int) {
    assert(toOff0 >= 0)
    assert(n0 >= 0)
    assert(toOff0 <= to.length - n0)
    var toOff = toOff0
    var n = n0

    while (n > 0) {
      if (end == off)
        readBlock()
      val p = math.min(end - off, n << 3) >>> 3
      assert(p > 0)
      Memory.memcpy(to, toOff, buf, off, p)
      toOff += p
      n -= p
      off += (p << 3)
    }
  }
}

trait Decoder extends Closeable {
  def close()

  def readRegionValue(t: Type, region: Region): Long

  def readByte(): Byte
}

final class PackDecoder(in: InputBuffer) extends Decoder {
  def close() {
    in.close()
  }

  def readByte(): Byte = in.readByte()

  def readBinary(region: Region, off: Long) {
    val length = in.readInt()
    val boff = region.allocate(4, 4 + length)
    region.storeAddress(off, boff)
    region.storeInt(boff, length)
    in.readBytes(region, boff + 4, length)
  }

  def readArray(t: TArray, region: Region): Long = {
    val length = in.readInt()

    val contentSize = t.contentsByteSize(length)
    val aoff = region.allocate(t.contentsAlignment, contentSize)

    region.storeInt(aoff, length)
    if (!t.elementType.required) {
      val nMissingBytes = (length + 7) >>> 3
      in.readBytes(region, aoff + 4, nMissingBytes)
    }

    val elemsOff = aoff + t.elementsOffset(length)
    val elemSize = t.elementByteSize

    if (t.elementType.isInstanceOf[TInt32]) { // fast path
      var i = 0
      while (i < length) {
        if (t.isElementDefined(region, aoff, i)) {
          val off = elemsOff + i * elemSize
          region.storeInt(off, in.readInt())
        }
        i += 1
      }
    } else {
      var i = 0
      while (i < length) {
        if (t.isElementDefined(region, aoff, i)) {
          val off = elemsOff + i * elemSize
          t.elementType match {
            case t2: TBaseStruct => readBaseStruct(t2, region, off)
            case t2: TArray =>
              val aoff = readArray(t2, region)
              region.storeAddress(off, aoff)
            case _: TBoolean => region.storeByte(off, in.readBoolean().toByte)
            case _: TInt64 => region.storeLong(off, in.readLong())
            case _: TFloat32 => region.storeFloat(off, in.readFloat())
            case _: TFloat64 => region.storeDouble(off, in.readDouble())
            case _: TBinary => readBinary(region, off)
          }
        }
        i += 1
      }
    }

    aoff
  }

  def readBaseStruct(t: TBaseStruct, region: Region, offset: Long) {
    val nMissingBytes = t.nMissingBytes
    in.readBytes(region, offset, nMissingBytes)

    var i = 0
    while (i < t.size) {
      if (t.isFieldDefined(region, offset, i)) {
        val off = offset + t.byteOffsets(i)
        t.types(i) match {
          case t2: TBaseStruct => readBaseStruct(t2, region, off)
          case t2: TArray =>
            val aoff = readArray(t2, region)
            region.storeAddress(off, aoff)
          case _: TBoolean => region.storeByte(off, in.readBoolean().toByte)
          case _: TInt32 => region.storeInt(off, in.readInt())
          case _: TInt64 => region.storeLong(off, in.readLong())
          case _: TFloat32 => region.storeFloat(off, in.readFloat())
          case _: TFloat64 => region.storeDouble(off, in.readDouble())
          case _: TBinary => readBinary(region, off)
        }
      }
      i += 1
    }
  }

  def readRegionValue(t: Type, region: Region): Long = {
    val f = t.fundamentalType
    f match {
      case t: TBaseStruct =>
        val start = region.allocate(t.alignment, t.byteSize)
        readBaseStruct(t, region, start)
        start

      case t: TArray =>
        readArray(t, region)
    }
  }
}

trait Encoder extends Closeable {
  def flush(): Unit

  def close(): Unit

  def writeRegionValue(t: Type, region: Region, offset: Long): Unit

  def writeByte(b: Byte): Unit
}

final class PackEncoder(out: OutputBuffer) extends Encoder {
  def flush() {
    out.flush()
  }

  def close() {
    out.close()
  }

  def writeByte(b: Byte): Unit = out.writeByte(b)

  def writeBinary(region: Region, offset: Long) {
    val boff = region.loadAddress(offset)
    val length = region.loadInt(boff)
    out.writeInt(length)
    out.writeBytes(region, boff + 4, length)
  }

  def writeArray(t: TArray, region: Region, aoff: Long) {
    val length = region.loadInt(aoff)

    out.writeInt(length)
    if (!t.elementType.required) {
      val nMissingBytes = (length + 7) >>> 3
      out.writeBytes(region, aoff + 4, nMissingBytes)
    }

    val elemsOff = aoff + t.elementsOffset(length)
    val elemSize = t.elementByteSize
    if (t.elementType.isInstanceOf[TInt32]) { // fast case
      var i = 0
      while (i < length) {
        if (t.isElementDefined(region, aoff, i)) {
          val off = elemsOff + i * elemSize
          out.writeInt(region.loadInt(off))
        }
        i += 1
      }
    } else {
      var i = 0
      while (i < length) {
        if (t.isElementDefined(region, aoff, i)) {
          val off = elemsOff + i * elemSize
          t.elementType match {
            case t2: TBaseStruct => writeBaseStruct(t2, region, off)
            case t2: TArray => writeArray(t2, region, region.loadAddress(off))
            case _: TBoolean => out.writeBoolean(region.loadByte(off) != 0)
            case _: TInt64 => out.writeLong(region.loadLong(off))
            case _: TFloat32 => out.writeFloat(region.loadFloat(off))
            case _: TFloat64 => out.writeDouble(region.loadDouble(off))
            case _: TBinary => writeBinary(region, off)
          }
        }

        i += 1
      }
    }
  }

  def writeBaseStruct(t: TBaseStruct, region: Region, offset: Long) {
    val nMissingBytes = t.nMissingBytes
    out.writeBytes(region, offset, nMissingBytes)

    var i = 0
    while (i < t.size) {
      if (t.isFieldDefined(region, offset, i)) {
        val off = offset + t.byteOffsets(i)
        t.types(i) match {
          case t2: TBaseStruct => writeBaseStruct(t2, region, off)
          case t2: TArray => writeArray(t2, region, region.loadAddress(off))
          case _: TBoolean => out.writeBoolean(region.loadByte(off) != 0)
          case _: TInt32 => out.writeInt(region.loadInt(off))
          case _: TInt64 => out.writeLong(region.loadLong(off))
          case _: TFloat32 => out.writeFloat(region.loadFloat(off))
          case _: TFloat64 => out.writeDouble(region.loadDouble(off))
          case _: TBinary => writeBinary(region, off)
        }
      }

      i += 1
    }
  }

  def writeRegionValue(t: Type, region: Region, offset: Long) {
    val f = t.fundamentalType
    (f: @unchecked) match {
      case t: TBaseStruct =>
        writeBaseStruct(t, region, offset)
      case t: TArray =>
        writeArray(t, region, offset)
    }
  }
}

object RichRDDRegionValue {
  def writeRowsPartition(t: TStruct, codecSpec: CodecSpec)(i: Int, it: Iterator[RegionValue], os: OutputStream): Long = {
    val en = codecSpec.buildEncoder(os)
    var rowCount = 0L

    it.foreach { rv =>
      en.writeByte(1)
      en.writeRegionValue(t, rv.region, rv.offset)
      rowCount += 1
    }

    en.writeByte(0) // end
    en.flush()
    os.close()

    rowCount
  }
}

class RichRDDRegionValue(val rdd: RDD[RegionValue]) extends AnyVal {
  def writeRows(path: String, t: TStruct, codecSpec: CodecSpec): (Array[String], Array[Long]) = {
    rdd.writePartitions(path, RichRDDRegionValue.writeRowsPartition(t, codecSpec))
  }

  def writeRowsSplit(path: String, t: MatrixType, codecSpec: CodecSpec, partitioner: OrderedRVDPartitioner): Array[Long] = {
    val sc = rdd.sparkContext
    val hConf = sc.hadoopConfiguration

    hConf.mkDir(path + "/rows/rows/parts")
    hConf.mkDir(path + "/entries/rows/parts")

    val sHConfBc = sc.broadcast(new SerializableHadoopConfiguration(hConf))

    val nPartitions = rdd.getNumPartitions
    val d = digitsNeeded(nPartitions)

    val partFiles = Array.tabulate[String](nPartitions) { i => partFile(d, i) }

    val fullRowType = t.rvRowType
    val rowsRVType = t.rowType
    val localEntriesIndex = t.entriesIdx

    val entriesRVType = TStruct(
      MatrixType.entriesIdentifier -> TArray(t.entryType))

    val partitionCounts = rdd.mapPartitionsWithIndex { case (i, it) =>
      val hConf = sHConfBc.value.value

      val f = partFile(d, i)

      val rowsPartPath = path + "/rows/rows/parts/" + f
      hConf.writeFile(rowsPartPath) { rowsOS =>
        using(codecSpec.buildEncoder(rowsOS)) { rowsEN =>

          val entriesPartPath = path + "/entries/rows/parts/" + f
          hConf.writeFile(entriesPartPath) { entriesOS =>
            using(codecSpec.buildEncoder(entriesOS)) { entriesEN =>

              var rowCount = 0L

              val rvb = new RegionValueBuilder()
              val fullRow = new UnsafeRow(fullRowType)

              it.foreach { rv =>
                fullRow.set(rv)
                val row = fullRow.deleteField(localEntriesIndex)

                val region = rv.region
                rvb.set(region)
                rvb.start(rowsRVType)
                rvb.addAnnotation(rowsRVType, row)

                rowsEN.writeByte(1)
                rowsEN.writeRegionValue(rowsRVType, region, rvb.end())

                rvb.start(entriesRVType)
                rvb.startStruct()
                rvb.addField(fullRowType, rv, localEntriesIndex)
                rvb.endStruct()

                entriesEN.writeByte(1)
                entriesEN.writeRegionValue(entriesRVType, region, rvb.end())

                rowCount += 1
              }

              rowsEN.writeByte(0) // end
              entriesEN.writeByte(0)

              Iterator.single(rowCount)
            }
          }
        }
      }
    }
      .collect()

    val rowsSpec = OrderedRVDSpec(t.rowORVDType,
      codecSpec,
      partFiles,
      JSONAnnotationImpex.exportAnnotation(partitioner.rangeBounds, partitioner.rangeBoundsType))
    rowsSpec.write(hConf, path + "/rows/rows")

    val entriesSpec = UnpartitionedRVDSpec(entriesRVType, codecSpec, partFiles)
    entriesSpec.write(hConf, path + "/entries/rows")

    info(s"wrote ${ partitionCounts.sum } items in $nPartitions partitions")

    partitionCounts
  }
}
