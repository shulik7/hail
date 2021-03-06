package is.hail.rvd

import is.hail.annotations.{KeyedRow, RegionValue, UnsafeRow}
import is.hail.expr.types.TStruct
import is.hail.io.CodecSpec
import is.hail.utils._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

object UnpartitionedRVD {
  def empty(sc: SparkContext, rowType: TStruct): UnpartitionedRVD = new UnpartitionedRVD(rowType, sc.emptyRDD[RegionValue])
}

class UnpartitionedRVD(val rowType: TStruct, val rdd: RDD[RegionValue]) extends RVD {
  self =>

  def filter(f: (RegionValue) => Boolean): UnpartitionedRVD = new UnpartitionedRVD(rowType, rdd.filter(f))

  def persist(level: StorageLevel): UnpartitionedRVD = {
    val PersistedRVRDD(persistedRDD, iterationRDD) = persistRVRDD(level)
    new UnpartitionedRVD(rowType, iterationRDD) {
      override def storageLevel: StorageLevel = persistedRDD.getStorageLevel

      override def persist(newLevel: StorageLevel): UnpartitionedRVD = {
        if (newLevel == StorageLevel.NONE)
          unpersist()
        else {
          persistedRDD.persist(newLevel)
          this
        }
      }

      override def unpersist(): UnpartitionedRVD = {
        persistedRDD.unpersist()
        self
      }
    }
  }

  def sample(withReplacement: Boolean, p: Double, seed: Long): UnpartitionedRVD =
    new UnpartitionedRVD(rowType, rdd.sample(withReplacement, p, seed))

  def write(path: String, codecSpec: CodecSpec): Array[Long] = {
    val (partFiles, partitionCounts) = rdd.writeRows(path, rowType, codecSpec)
    val spec = UnpartitionedRVDSpec(rowType, codecSpec, partFiles)
    spec.write(sparkContext.hadoopConfiguration, path)
    partitionCounts
  }

  def coalesce(maxPartitions: Int, shuffle: Boolean): UnpartitionedRVD = new UnpartitionedRVD(rowType, rdd.coalesce(maxPartitions, shuffle = shuffle))

  def constrainToOrderedPartitioner(
    ordType: OrderedRVDType,
    newPartitioner: OrderedRVDPartitioner
  ): OrderedRVD = {

    assert(ordType.rowType == rowType)

    val localRowType = rowType
    val pkOrdering = ordType.pkType.ordering
    val rangeTree = newPartitioner.rangeTree
    val filtered = rdd.mapPartitions { it =>
      val ur = new UnsafeRow(localRowType, null, 0)
      val key = new KeyedRow(ur, ordType.pkRowFieldIdx)
      it.filter { rv =>
        ur.set(rv)
        rangeTree.contains(pkOrdering, key)
      }
    }

    OrderedRVD.shuffle(ordType, newPartitioner, filtered)
  }
}
