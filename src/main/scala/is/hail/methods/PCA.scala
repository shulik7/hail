package is.hail.methods

import breeze.linalg.{*, DenseMatrix, DenseVector}
import is.hail.annotations._
import is.hail.expr.types._
import is.hail.table.Table
import is.hail.utils._
import is.hail.variant.MatrixTable
import org.apache.spark.sql.Row

object PCA {
  def pcSchema(k: Int, asArray: Boolean = false): TStruct =
    TStruct((1 to k).map(i => (s"PC$i", TFloat64())): _*)

  def scoresTable(vsm: MatrixTable, asArrays: Boolean, scores: DenseMatrix[Double]): Table = {
    assert(vsm.numCols == scores.rows)
    val k = scores.cols
    val hc = vsm.hc
    val sc = hc.sc

    val rowType = TStruct(vsm.colKey.zip(vsm.colKeyTypes): _*) ++ (if (asArrays) TStruct("scores" -> TArray(TFloat64())) else pcSchema(k))
    val rowTypeBc = sc.broadcast(rowType)

    val scoresBc = sc.broadcast(scores)
    val localSSignature = vsm.colKeyTypes

    val scoresRDD = sc.parallelize(vsm.colKeys.zipWithIndex).mapPartitions[RegionValue] { it =>
      val region = Region()
      val rv = RegionValue(region)
      val rvb = new RegionValueBuilder(region)
      val localRowType = rowTypeBc.value

      it.map { case (s, i) =>
        region.clear()
        rvb.start(localRowType)
        rvb.startStruct()
        var j = 0
        val keys = s.asInstanceOf[Row]
        while (j < localSSignature.length) {
          rvb.addAnnotation(localSSignature(j), keys.get(j))
          j += 1
        }
        if (asArrays) rvb.startArray(k)
        j = 0
        while (j < k) {
          rvb.addDouble(scoresBc.value(i, j))
          j += 1
        }
        if (asArrays) rvb.endArray()
        rvb.endStruct()
        rv.setOffset(rvb.end())
        rv
      }
    }
    new Table(hc, scoresRDD, rowType, vsm.colKey)
  }

  // returns (eigenvalues, sample scores, optional variant loadings)
  def apply(vsm: MatrixTable, expr: String, k: Int, computeLoadings: Boolean, asArray: Boolean = false): (IndexedSeq[Double], DenseMatrix[Double], Option[Table]) = {
    if (k < 1)
      fatal(
        s"""requested invalid number of components: $k
           |  Expect componenents >= 1""".stripMargin)

    val sc = vsm.sparkContext
    val irm = vsm.toIndexedRowMatrix(expr)

    info(s"Running PCA with $k components...")

    val svd = irm.computeSVD(k, computeLoadings)
    if (svd.s.size < k)
      fatal(
        s"Found only ${ svd.s.size } non-zero (or nearly zero) eigenvalues, " +
          s"but user requested ${ k } principal components.")

    def collectRowKeys(): Array[Annotation] = {
      val fullRowType = vsm.rvRowType
      val localRKF = vsm.rowKeysF
      val localKeyStruct = vsm.rowKeyStruct
      
      vsm.rvd.mapPartitions { it =>
        val ur = new UnsafeRow(fullRowType)
        it.map { rv =>
          ur.set(rv)
          Annotation.copy(localKeyStruct, localRKF(ur))
        }
      }.collect()
    }

    val optionLoadings = someIf(computeLoadings, {
      val rowType = TStruct(vsm.rowKey.zip(vsm.rowKeyTypes): _*) ++ (if (asArray) TStruct("loadings" -> TArray(TFloat64())) else pcSchema(k))
      val rowTypeBc = vsm.sparkContext.broadcast(rowType)
      val rowKeysBc = vsm.sparkContext.broadcast(collectRowKeys())
      val localRowKeySignature = vsm.rowKeyTypes

      val rdd = svd.U.rows.mapPartitions[RegionValue] { it =>
        val region = Region()
        val rv = RegionValue(region)
        val rvb = new RegionValueBuilder(region)
        it.map { ir =>
          region.clear()
          rvb.start(rowTypeBc.value)
          rvb.startStruct()

          val rowKeys = rowKeysBc.value(ir.index.toInt).asInstanceOf[Row]
          var j = 0
          while (j < localRowKeySignature.length) {
            rvb.addAnnotation(localRowKeySignature(j), rowKeys.get(j))
            j += 1
          }

          if (asArray)
            rvb.startArray(k)
          var i = 0
          while (i < k) {
            rvb.addDouble(ir.vector(i))
            i += 1
          }
          if (asArray)
            rvb.endArray()
          rvb.endStruct()
          rv.setOffset(rvb.end())
          rv
        }
      }
      new Table(vsm.hc, rdd, rowType, vsm.rowKey)
    })

    val data =
      if (!svd.V.isTransposed)
        svd.V.asInstanceOf[org.apache.spark.mllib.linalg.DenseMatrix].values
      else
        svd.V.toArray
    
    val V = new DenseMatrix[Double](svd.V.numRows, svd.V.numCols, data)
    val S = DenseVector(svd.s.toArray)

    val eigenvalues = svd.s.toArray.map(math.pow(_, 2))
    val scaledEigenvectors = V(*, ::) *:* S
    
    (eigenvalues, scaledEigenvectors, optionLoadings)
  }
}
