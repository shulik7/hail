package is.hail.methods

import is.hail.{SparkSuite, TestUtils}
import is.hail.expr.types._
import is.hail.utils._
import is.hail.testUtils._
import org.testng.annotations.Test

import scala.io.Source

class ExportSuite extends SparkSuite {

  @Test def test() {
    var vds = hc.importVCF("src/test/resources/sample.vcf")
    vds = TestUtils.splitMultiHTS(vds)
    vds = SampleQC(vds)

    val out = tmpDir.createTempFile("out", ".tsv")
    vds.colsTable().select(Array("Sample = row.s",
    "row.qc.call_rate",
    "row.qc.n_called",
    "row.qc.n_not_called",
    "row.qc.n_hom_ref",
    "row.qc.n_het",
    "row.qc.n_hom_var",
    "row.qc.n_snp",
    "row.qc.n_insertion",
    "row.qc.n_deletion",
    "row.qc.n_singleton",
    "row.qc.n_transition",
    "row.qc.n_transversion",
    "row.qc.n_star",
    "row.qc.dp_mean",
    "row.qc.dp_stdev",
    "row.qc.gq_mean",
    "row.qc.gq_stdev",
    "row.qc.n_non_ref",
    "row.qc.r_ti_tv",
    "row.qc.r_het_hom_var",
    "row.qc.r_insertion_deletion")).export(out)

    val sb = new StringBuilder()
    sb.tsvAppend(Array(1, 2, 3, 4, 5))
    assert(sb.result() == "1,2,3,4,5")

    sb.clear()
    sb.tsvAppend(5.124)
    assert(sb.result() == "5.12400e+00")

    val readBackAnnotated = vds.annotateColsTable(hc.importTable(out, types = Map(
      "call_rate" -> TFloat64(),
      "n_called" -> TInt64(),
      "n_not_called" -> TInt64(),
      "n_hom_ref" -> TInt64(),
      "n_het" -> TInt64(),
      "n_hom_var" -> TInt64(),
      "n_snp" -> TInt64(),
      "n_insertion" -> TInt64(),
      "n_deletion" -> TInt64(),
      "n_singleton" -> TInt64(),
      "n_transition" -> TInt64(),
      "n_transversion" -> TInt64(),
      "n_star" -> TInt64(),
      "dp_mean" -> TFloat64(),
      "dp_stdev" -> TFloat64(),
      "gq_mean" -> TFloat64(),
      "gq_stdev" -> TFloat64(),
      "n_non_ref" -> TInt64(),
      "r_ti_tv" -> TFloat64(),
      "r_het_hom_var" -> TFloat64(),
      "r_insertion_deletion" -> TFloat64())).keyBy("Sample"),
      root = "readBackQC")

    val (t, qcQuerier) = readBackAnnotated.querySA("sa.qc")
    val (t2, rbQuerier) = readBackAnnotated.querySA("sa.readBackQC")
    assert(t == t2)
    readBackAnnotated.colValues.foreach { annotation =>
      t.valuesSimilar(qcQuerier(annotation), rbQuerier(annotation))
    }
  }

  @Test def testExportSamples() {
    val vds = TestUtils.splitMultiHTS(hc.importVCF("src/test/resources/sample.vcf")
      .filterColsExpr("""sa.s == "C469::HG02026""""))
    assert(vds.numCols == 1)

    // verify exports localSamples
    val f = tmpDir.createTempFile("samples", ".tsv")
    vds.colsTable().select(Array("row.s")).export(f, header = false)
    assert(sc.textFile(f).count() == 1)
  }

  @Test def testAllowedNames() {
    val f = tmpDir.createTempFile("samples", ".tsv")
    val f2 = tmpDir.createTempFile("samples", ".tsv")
    val f3 = tmpDir.createTempFile("samples", ".tsv")

    val vds = TestUtils.splitMultiHTS(hc.importVCF("src/test/resources/sample.vcf"))
    vds.colsTable().select(Array("`S.A.M.P.L.E.ID` = row.s")).export(f)
    vds.colsTable().select(Array("`$$$I_HEARD_YOU_LIKE!_WEIRD~^_CHARS****` = row.s", "ANOTHERTHING = row.s")).export(f2)
    vds.colsTable().select(Array("`I have some spaces and tabs\\there` = row.s", "`more weird stuff here` = row.s")).export(f3)
    hadoopConf.readFile(f) { reader =>
      val lines = Source.fromInputStream(reader)
        .getLines()
      assert(lines.next == "S.A.M.P.L.E.ID")
    }
    hadoopConf.readFile(f2) { reader =>
      val lines = Source.fromInputStream(reader)
        .getLines()
      assert(lines.next == "$$$I_HEARD_YOU_LIKE!_WEIRD~^_CHARS****\tANOTHERTHING")
    }
    hadoopConf.readFile(f3) { reader =>
      val lines = Source.fromInputStream(reader)
        .getLines()
      assert(lines.next == "I have some spaces and tabs\there\tmore weird stuff here")
    }
  }

  @Test def testIf() {

    // this should run without errors
    val f = tmpDir.createTempFile("samples", ".tsv")
    var vds = hc.importVCF("src/test/resources/sample.vcf")
    vds = TestUtils.splitMultiHTS(vds)
    vds = SampleQC(vds)
    vds
      .colsTable()
      .select(Array("computation = 5 * (if (row.qc.call_rate < .95) 0 else 1)"))
      .export(f)
  }
}
