package is.hail.rvd

import is.hail.annotations._
import is.hail.expr.types._
import is.hail.utils._
import org.apache.spark.sql.Row
import org.apache.spark.{Partitioner, SparkContext}
import org.apache.spark.broadcast.Broadcast

class OrderedRVDPartitioner(
  val partitionKey: Array[String], val kType: TStruct,
  // rangeBounds: Array[Interval[pkType]]
  // rangeBounds is interval containing all partition keys within a partition
  val rangeBounds: UnsafeIndexedSeq) extends Partitioner {
  val numPartitions: Int = rangeBounds.length

  val (pkType, _) = kType.select(partitionKey)
  val pkIntervalType = TInterval(pkType)
  val rangeBoundsType = TArray(pkIntervalType)

  assert(rangeBoundsType.typeCheck(rangeBounds))

  require(rangeBounds.isEmpty || (rangeBounds.zip(rangeBounds.tail).forall { case (left: Interval, right: Interval) =>
    !left.mayOverlap(pkType.ordering, right) && pkType.ordering.lteq(left.start, right.start)
  } && rangeBounds.forall { case i: Interval =>
    pkType.ordering.lteq(i.start, i.end) && !i.definitelyEmpty(pkType.ordering)
  }))

  require(rangeBounds.isEmpty || rangeBounds.zip(rangeBounds.tail).forall { case (left: Interval, right: Interval) =>
    pkType.ordering.equiv(left.end, right.start) && (left.includesEnd || right.includesStart) } )

  val rangeTree: IntervalTree[Int] = IntervalTree.fromSorted(pkType.ordering,
    Array.tabulate[(Interval, Int)](numPartitions) { i =>
      (rangeBounds(i).asInstanceOf[Interval], i)
    })

  def region: Region = rangeBounds.region

  def loadElement(i: Int): Long = rangeBoundsType.loadElement(region, rangeBounds.aoff, rangeBounds.length, i)

  def loadStart(i: Int): Long = pkIntervalType.loadStart(region, loadElement(i))

  def loadEnd(i: Int): Long = pkIntervalType.loadStart(region, loadElement(i))

  def range: Interval = rangeTree.root.get.range

  /**
    * Find the partition containing the given Row.
    *
    * If pkType is a prefix of the type of row, the prefix of row is used to
    * find the partition.
    *
    * If row falls outside the bounds of the partitioner, return the min or max
    * partition.
    */
  def getPartitionPK(row: Any): Int = {
    val part = rangeTree.queryValues(pkType.ordering, row)
    part match {
      case Array() =>
        if (range.isAbovePosition(pkType.ordering, row))
          0
        else {
          assert(range.isBelowPosition(pkType.ordering, row))
          numPartitions - 1
        }

      case Array(x) => x
    }
  }

  // Return the sequence of partition IDs overlapping the given interval of
  // partition keys.
  def getPartitionRange(query: Any): Seq[Int] = {
    query match {
      case row: Row =>
        rangeTree.queryValues(pkType.ordering, row)
      case interval: Interval =>
        if (!rangeTree.probablyOverlaps(pkType.ordering, interval))
          Seq.empty[Int]
        else {
          val startRange = getPartitionRange(interval.start)
          val start = if (startRange.nonEmpty)
            startRange.min
          else
            0
          val endRange =  getPartitionRange(interval.end)
          val end = if (endRange.nonEmpty)
            endRange.max
          else
            numPartitions - 1
          start to end
        }
    }
  }

  // return the partition containing key
  // if outside bounds, return min or max depending on location
  // key: RegionValue[kType]
  def getPartition(key: Any): Int =
    getPartitionPK(new UnsafeRow(kType, key.asInstanceOf[RegionValue]))


  def withKType(newPartitionKey: Array[String], newKType: TStruct): OrderedRVDPartitioner = {
    val (newPKType, _) = newKType.select(newPartitionKey)
    val newRangeBounds = new UnsafeIndexedSeq(TArray(TInterval(newPKType)), rangeBounds.region, rangeBounds.aoff)
    val newPart = new OrderedRVDPartitioner(newPartitionKey, newKType, newRangeBounds)
    assert(newPart.pkType.types.sameElements(pkType.types))
    newPart
  }

  def copy(numPartitions: Int = numPartitions, partitionKey: Array[String] = partitionKey,
    kType: TStruct = kType, rangeBounds: UnsafeIndexedSeq = rangeBounds): OrderedRVDPartitioner = {
    new OrderedRVDPartitioner(partitionKey, kType, rangeBounds)
  }

  // FIXME Make work if newRange has different point type than pkType
  def enlargeToRange(newRange: Interval): OrderedRVDPartitioner = {
    val newStart = pkType.ordering.min(range.start, newRange.start)
    val newEnd = pkType.ordering.max(range.end, newRange.end)
    val newRangeBounds = rangeBounds.toArray
    newRangeBounds(0) = newRangeBounds(0).asInstanceOf[Interval]
      .copy(start = newStart, includesStart = true)
    newRangeBounds(newRangeBounds.length - 1) = newRangeBounds(newRangeBounds.length - 1)
      .asInstanceOf[Interval].copy(end = newEnd, includesEnd = true)
    copy(rangeBounds = UnsafeIndexedSeq(rangeBoundsType, newRangeBounds))
  }

  def coalesceRangeBounds(newPartEnd: Array[Int]): OrderedRVDPartitioner = {
    val newRangeBounds = UnsafeIndexedSeq(
      rangeBoundsType,
      (-1 +: newPartEnd.init).zip(newPartEnd).map { case (s, e) =>
        val i1 = rangeBounds(s + 1).asInstanceOf[Interval]
        val i2 = rangeBounds(e).asInstanceOf[Interval]
        Interval(i1.start, i2.end, i1.includesStart, i2.includesEnd)
      })
    copy(numPartitions = newPartEnd.length, rangeBounds = newRangeBounds)
  }

  @transient
  @volatile var partitionerBc: Broadcast[OrderedRVDPartitioner] = _

  def broadcast(sc: SparkContext): Broadcast[OrderedRVDPartitioner] = {
    if (partitionerBc == null) {
      synchronized {
        if (partitionerBc == null)
          partitionerBc = sc.broadcast(this)
      }
    }
    partitionerBc
  }

  def sparkPartitioner(sc: SparkContext): Partitioner = {
    val selfBc = broadcast(sc)

    new Partitioner {
      def numPartitions: Int = selfBc.value.numPartitions

      def getPartition(key: Any): Int = selfBc.value.getPartition(key)
    }
  }
}

object OrderedRVDPartitioner {
  def empty(typ: OrderedRVDType): OrderedRVDPartitioner = {
    new OrderedRVDPartitioner(typ.partitionKey, typ.kType, UnsafeIndexedSeq.empty(TArray(TInterval(typ.pkType))))
  }

  // takes npartitions + 1 points and returns npartitions intervals: [a,b], (b,c], (c,d], ... (i, j]
  def makeRangeBoundIntervals(pType: Type, rangeBounds: Array[RegionValue]): UnsafeIndexedSeq = {
    val uisRangeBounds = UnsafeIndexedSeq(TArray(pType), rangeBounds)
    var includesStart = true
    val rangeBoundIntervals = uisRangeBounds.zip(uisRangeBounds.tail).map { case (s, e) =>
        val i = Interval(s, e, includesStart, true)
        includesStart = false
        i
    }
    UnsafeIndexedSeq(TArray(TInterval(pType)), rangeBoundIntervals)
  }
}
