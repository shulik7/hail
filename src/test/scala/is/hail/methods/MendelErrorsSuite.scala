package is.hail.methods

import is.hail.SparkSuite
import is.hail.variant.Locus
import org.apache.spark.sql.Row
import org.testng.annotations.Test

class MendelErrorsSuite extends SparkSuite {
  @Test def test() {
    val vds = hc.importVCF("src/test/resources/mendel.vcf")
    val ped = Pedigree.read("src/test/resources/mendel.fam", sc.hadoopConfiguration)
    val men = MendelErrors(vds, ped.filterTo(vds.stringSampleIdSet).completeTrios)

    val nPerFam = men.nErrorPerNuclearFamily.collectAsMap()
    val nPerIndiv = men.nErrorPerIndiv.collectAsMap()
    val nPerVariant = men.nErrorPerVariant.collectAsMap()

    val son = "Son1"
    val dtr = "Dtr1"
    val dad = "Dad1"
    val mom = "Mom1"
    val dad2 = "Dad2"
    val mom2 = "Mom2"

    val variant1 = Row(Locus("1", 1), IndexedSeq("C", "CT"))
    val variant2 = Row(Locus("1", 2), IndexedSeq("C", "T"))
    val variant3 = Row(Locus("X", 1), IndexedSeq("C", "T"))
    val variant4 = Row(Locus("X", 3), IndexedSeq("C", "T"))
    val variant5 = Row(Locus("Y", 1), IndexedSeq("C", "T"))
    val variant6 = Row(Locus("Y", 3), IndexedSeq("C", "T"))
    val variant7 = Row(Locus("20", 1), IndexedSeq("C", "T"))

    assert(nPerFam.size == 2)
    assert(nPerIndiv.size == 7)
    assert(nPerVariant.size == 28)

    assert(nPerFam((dad, mom)) == (41, 39))
    assert(nPerFam((dad2, mom2)) == (0, 0))

    assert(nPerIndiv(son) == (23, 22))
    assert(nPerIndiv(dtr) == (18, 17))
    assert(nPerIndiv(dad) == (19, 18))
    assert(nPerIndiv(mom) == (22, 21))
    assert(nPerIndiv(dad2) == (0, 0))

    assert(nPerVariant(variant1) == 2)
    assert(nPerVariant(variant2) == 1)
    assert(nPerVariant(variant3) == 2)
    assert(nPerVariant(variant4) == 1)
    assert(nPerVariant(variant5) == 1)
    assert(nPerVariant(variant6) == 1)
    assert(nPerVariant.get(variant7).isEmpty)

    val mendelBase = tmpDir.createTempFile("sample_mendel")

    men.mendelKT().typeCheck()
    men.fMendelKT().typeCheck()
    men.iMendelKT().typeCheck()
    men.lMendelKT().typeCheck()

    val ped2 = Pedigree.read("src/test/resources/mendelWithMissingSex.fam", sc.hadoopConfiguration)
    val men2 = MendelErrors(vds, ped2.filterTo(vds.stringSampleIdSet).completeTrios)

    assert(men2.mendelErrors.collect().toSet == men.mendelErrors.filter(_.trio.kid == "Dtr1").collect().toSet)
  }
}
