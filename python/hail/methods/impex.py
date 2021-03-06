from hail.typecheck import *
from hail.utils.java import Env, joption, FatalError, jindexed_seq_args, jset_args
from hail.utils import wrap_to_list
from hail.utils.misc import plural
from hail.matrixtable import MatrixTable
from hail.table import Table
from hail.expr.types import *
from hail.expr.expressions import analyze, expr_any
from hail.genetics.reference_genome import reference_genome_type
from hail.methods.misc import require_biallelic, require_row_key_variant


@typecheck(table=Table,
           address=str,
           keyspace=str,
           table_name=str,
           block_size=int,
           rate=int)
def export_cassandra(table, address, keyspace, table_name, block_size=100, rate=1000):
    """Export a :class:`.Table` to Cassandra.

    Warning
    -------
    :func:`export_cassandra` is EXPERIMENTAL.
    """

    table._jkt.exportCassandra(address, keyspace, table_name, block_size, rate)


@typecheck(dataset=MatrixTable,
           output=str,
           precision=int)
def export_gen(dataset, output, precision=4):
    """Export a :class:`.MatrixTable` as GEN and SAMPLE files.

    .. include:: ../_templates/req_tvariant.rst

    .. include:: ../_templates/req_biallelic.rst

    Examples
    --------
    Import genotype probability data, filter variants based on INFO score, and
    export data to a GEN and SAMPLE file:

    >>> example_ds = hl.import_gen('data/example.gen', sample_file='data/example.sample')
    >>> example_ds = example_ds.filter_rows(agg.info_score(example_ds.GP).score >= 0.9) # doctest: +SKIP
    >>> hl.export_gen(example_ds, 'output/infoscore_filtered')

    Notes
    -----
    Writes out the dataset to a GEN and SAMPLE fileset in the
    `Oxford spec <http://www.stats.ox.ac.uk/%7Emarchini/software/gwas/file_format.html>`__.

    This method requires a `GP` (genotype probabilities) entry field of type
    ``array<float64>``. The values at indices 0, 1, and 2 are exported as the
    probabilities of homozygous reference, heterozygous, and homozygous variant,
    respectively. Missing `GP` values are exported as ``0 0 0``.

    The first six columns of the GEN file are as follows:

    - chromosome (`locus.contig`)
    - variant ID (`varid` if defined, else Contig:Position:Ref:Alt)
    - rsID (`rsid` if defined, else ``.``)
    - position (`locus.position`)
    - reference allele (`alleles[0]`)
    - alternate allele (`alleles[1]`)

    The SAMPLE file has three columns:

    - ID_1 and ID_2 are identical and set to the sample ID (`s`).
    - The third column (``missing``) is set to 0 for all samples.

    Parameters
    ----------
    dataset : :class:`.MatrixTable`
        Dataset with entry field `GP` of type ``array<float64>``.
    output : :obj:`str`
        Filename root for output GEN and SAMPLE files.
    precision : :obj:`int`
        Number of digits to write after the decimal point.
    """

    dataset = require_biallelic(dataset, 'export_gen')
    try:
        gp = dataset['GP']
        if gp.dtype != tarray(tfloat64) or gp._indices != dataset._entry_indices:
            raise KeyError
    except KeyError:
        raise FatalError("export_gen: no entry field 'GP' of type 'array<float64>'")

    dataset = require_biallelic(dataset, 'export_plink')

    Env.hail().io.gen.ExportGen.apply(dataset._jvds, output, precision)


@typecheck(dataset=MatrixTable,
           output=str,
           fam_args=expr_any)
def export_plink(dataset, output, **fam_args):
    """Export a :class:`.MatrixTable` as
    `PLINK2 <https://www.cog-genomics.org/plink2/formats>`__
    BED, BIM and FAM files.

    .. include:: ../_templates/req_tvariant.rst

    .. include:: ../_templates/req_tstring.rst

    .. include:: ../_templates/req_biallelic.rst

    Examples
    --------
    Import data from a VCF file, split multi-allelic variants, and export to
    PLINK files with the FAM file individual ID set to the sample ID:

    >>> ds = hl.split_multi_hts(dataset)
    >>> hl.export_plink(ds, 'output/example', id = ds.s)

    Notes
    -----
    `fam_args` may be used to set the fields in the output
    `FAM file <https://www.cog-genomics.org/plink2/formats#fam>`__
    via expressions with column and global fields in scope:

    - ``fam_id``: :py:data:`.tstr` for the family ID
    - ``id``: :py:data:`.tstr` for the individual (proband) ID
    - ``mat_id``: :py:data:`.tstr` for the maternal ID
    - ``pat_id``: :py:data:`.tstr` for the paternal ID
    - ``is_female``: :py:data:`.tbool` for the proband sex
    - ``is_case``: :py:data:`.tbool` or `quant_pheno`: :py:data:`.tfloat64` for the
       phenotype

    If no assignment is given, the corresponding PLINK missing value is written:
    ``0`` for IDs and sex, ``NA`` for phenotype. Only one of ``is_case`` or
    ``quant_pheno`` can be assigned. For Boolean expressions, true and false are
    output as ``2`` and ``1``, respectively (i.e., female and case are ``2``).

    The BIM file ID field has the form ``chr:pos:ref:alt`` with values given by
    `v.contig`, `v.start`, `v.ref`, and `v.alt`.

    On an imported VCF, the example above will behave similarly to the PLINK
    conversion command

    .. code-block:: text

        plink --vcf /path/to/file.vcf --make-bed --out sample --const-fid --keep-allele-order

    except that:

    - Variants that result from splitting a multi-allelic variant may be
      re-ordered relative to the BIM and BED files.
    - PLINK uses the rsID for the BIM file ID.

    Parameters
    ----------
    dataset : :class:`.MatrixTable`
        Dataset.
    output : :obj:`str`
        Filename root for output BED, BIM, and FAM files.
    fam_args : varargs of :class:`hail.expr.expressions.Expression`
        Named expressions defining FAM field values.
    """

    fam_dict = {'fam_id': tstr, 'id': tstr, 'mat_id': tstr, 'pat_id': tstr,
                'is_female': tbool, 'is_case': tbool, 'quant_pheno': tfloat64}

    exprs = []
    named_exprs = {k: v for k, v in fam_args.items()}
    if ('is_case' in named_exprs) and ('quant_pheno' in named_exprs):
        raise ValueError("At most one of 'is_case' and 'quant_pheno' may be given as fam_args. Found both.")
    for k, v in named_exprs.items():
        if k not in fam_dict:
            raise ValueError("fam_arg '{}' not recognized. Valid names: {}".format(k, ', '.join(fam_dict)))
        elif (v.dtype != fam_dict[k]):
            raise TypeError("fam_arg '{}' expression has type {}, expected type {}".format(k, v.dtype, fam_dict[k]))

        analyze('export_plink/{}'.format(k), v, dataset._col_indices)
        exprs.append('`{k}` = {v}'.format(k=k, v=v._ast.to_hql()))
    base, _ = dataset._process_joins(*named_exprs.values())
    base = require_biallelic(base, 'export_plink')

    Env.hail().io.plink.ExportPlink.apply(base._jvds, output, ','.join(exprs))


@typecheck(table=Table,
           zk_host=str,
           collection=str,
           block_size=int)
def export_solr(table, zk_host, collection, block_size=100):
    """Export a :class:`.Table` to Solr.

    Warning
    -------
    :func:`export_solr` is EXPERIMENTAL.
    """

    table._jkt.exportSolr(zk_host, collection, block_size)


@typecheck(dataset=MatrixTable,
           output=str,
           append_to_header=nullable(str),
           parallel=nullable(enumeration('separate_header', 'header_per_shard')),
           metadata=nullable(dictof(str, dictof(str, dictof(str, str)))))
def export_vcf(dataset, output, append_to_header=None, parallel=None, metadata=None):
    """Export a :class:`.MatrixTable` as a VCF file.

    .. include:: ../_templates/req_tvariant.rst

    Examples
    --------
    Export to VCF as a block-compressed file:

    >>> hl.export_vcf(dataset, 'output/example.vcf.bgz')

    Notes
    -----
    :func:`export_vcf` writes the dataset to disk in VCF format as described in the
    `VCF 4.2 spec <https://samtools.github.io/hts-specs/VCFv4.2.pdf>`__.

    Use the ``.vcf.bgz`` extension rather than ``.vcf`` in the output file name
    for `blocked GZIP <http://www.htslib.org/doc/tabix.html>`__ compression.

    Note
    ----
        We strongly recommended compressed (``.bgz`` extension) and parallel
        output (`parallel` set to ``'separate_header'`` or
        ``'header_per_shard'``) when exporting large VCFs.

    Hail exports the fields of struct `info` as INFO fields,
    the elements of ``set<str>`` `filters` as FILTERS, and the
    value of float64 `qual` as QUAL. No other row fields are exported.

    The FORMAT field is generated from the entry schema, which
    must be a :class:`.tstruct`.  There is a FORMAT
    field for each field of the Struct.

    INFO and FORMAT fields may be generated from Struct fields of type
    :py:data:`.tcall`, :py:data:`.tint32`, :py:data:`.tfloat32`,
    :py:data:`.tfloat64`, or :py:data:`.tstr`. If a field has type
    :py:data:`.tint64`, every value must be a valid ``int32``. Arrays and sets
    containing these types are also allowed but cannot be nested; for example,
    ``array<array<int32>>`` is invalid. Arrays and sets are written with the
    same comma-separated format. Fields of type :py:data:`.tbool` are also
    permitted in `info` and will generate INFO fields of VCF type Flag.

    Hail also exports the name, length, and assembly of each contig as a VCF
    header line, where the assembly is set to the :class:`.ReferenceGenome`
    name.

    Consider the workflow of importing a VCF and immediately exporting the
    dataset back to VCF. The output VCF header will contain FORMAT lines for
    each entry field and INFO lines for all fields in `info`, but these lines
    will have empty Description fields and the Number and Type fields will be
    determined from their corresponding Hail types. To output a desired
    Description, Number, and/or Type value in a FORMAT or INFO field or to
    specify FILTER lines, use the `metadata` parameter to supply a dictionary
    with the relevant information. See
    :func:`get_vcf_metadata` for how to obtain the
    dictionary corresponding to the original VCF, and for info on how this
    dictionary should be structured.

    The output VCF header will also contain CONTIG lines
    with ID, length, and assembly fields derived from the reference genome of
    the dataset.

    The output VCF header will `not` contain lines added by external tools
    (such as bcftools and GATK) unless they are explicitly inserted using the
    `append_to_header` parameter.

    Warning
    -------

    INFO fields stored at VCF import are `not` automatically modified to
    reflect filtering of samples or genotypes, which can affect the value of
    AC (allele count), AF (allele frequency), AN (allele number), etc. If a
    filtered dataset is exported to VCF without updating `info`, downstream
    tools which may produce erroneous results. The solution is to create new
    fields in `info` or overwrite existing fields. For example, in order to
    produce an accurate `AC` field, one can run :func:`variant_qc` and copy
    the `variant_qc.AC` field to `info.AC` as shown below.

    >>> ds = dataset.filter_entries(dataset.GQ >= 20)
    >>> ds = hl.variant_qc(ds)
    >>> ds = ds.annotate_rows(info = ds.info.annotate(AC=ds.variant_qc.AC)) # doctest: +SKIP
    >>> hl.export_vcf(ds, 'output/example.vcf.bgz')

    Parameters
    ----------
    dataset : :class:`.MatrixTable`
        Dataset.
    output : :obj:`str`
        Path of .vcf or .vcf.bgz file to write.
    append_to_header : :obj:`str`, optional
        Path of file to append to VCF header.
    parallel : :obj:`str`, optional
        If ``'header_per_shard'``, return a set of VCF files (one per
        partition) rather than serially concatenating these files. If
        ``'separate_header'``, return a separate VCF header file and a set of
        VCF files (one per partition) without the header. If ``None``,
        concatenate the header and all partitions into one VCF file.
    metadata : :obj:`dict[str]` or :obj:`dict[str, dict[str, str]`, optional
        Dictionary with information to fill in the VCF header. See
        :func:`get_vcf_metadata` for how this
        dictionary should be structured.

    """

    require_row_key_variant(dataset, 'export_vcf')
    typ = tdict(tstr, tdict(tstr, tdict(tstr, tstr)))
    Env.hail().io.vcf.ExportVCF.apply(dataset._jvds, output, joption(append_to_header),
                                      Env.hail().utils.ExportType.getExportType(parallel),
                                      joption(typ._convert_to_j(metadata)))


@typecheck(path=str,
           reference_genome=nullable(reference_genome_type))
def import_locus_intervals(path, reference_genome='default'):
    """Import an interval list as a :class:`.Table`.

    Examples
    --------

    >>> intervals = hl.import_locus_intervals('data/capture_intervals.txt')

    Notes
    -----

    Hail expects an interval file to contain either three or five fields per
    line in the following formats:

    - ``contig:start-end``
    - ``contig  start  end`` (tab-separated)
    - ``contig  start  end  direction  target`` (tab-separated)

    A file in either of the first two formats produces a table with one
    field:

    - **interval** (:class:`.tinterval`) - Row key. Genomic interval. If
      `reference_genome` is defined, the point type of the interval will be
      :class:`.tlocus` parameterized by the `reference_genome`. Otherwise,
      the point type is a :class:`.tstruct` with two fields: `contig` with
      type :py:data:`.tstr` and `position` with type :py:data:`.tint32`.

    A file in the third format (with a "target" column) produces a table with two
    fields:

     - **interval** (:class:`.tinterval`) - Row key. Same schema as above.
     - **target** (:py:data:`.tstr`)

    Note
    ----
    ``start`` and ``end`` match positions inclusively, e.g.
    ``start <= position <= end``. :meth:`.Interval.parse`
    is exclusive of the end position.

    Refer to :class:`.ReferenceGenome` for contig ordering and behavior.

    Warning
    -------
    The interval parser for these files does not support the full range of
    formats supported by the python parser
    :meth:`representation.Interval.parse`. 'k', 'm', 'start', and 'end' are all
    invalid motifs in the ``contig:start-end`` format here.

    Parameters
    ----------
    path : :obj:`str`
        Path to file.

    reference_genome : :obj:`str` or :class:`.ReferenceGenome`, optional
        Reference genome to use.

    Returns
    -------
    :class:`.Table`
        Interval-keyed table.
    """
    rg = reference_genome._jrep if reference_genome else None

    t = Env.hail().table.Table.importIntervalList(Env.hc()._jhc, path, joption(rg))
    return Table(t)


@typecheck(path=str,
           reference_genome=nullable(reference_genome_type))
def import_bed(path, reference_genome='default'):
    """Import a UCSC .bed file as a :class:`.Table`.

    Examples
    --------

    >>> bed = hl.import_bed('data/file1.bed')

    >>> bed = hl.import_bed('data/file2.bed')

    The file formats are

    .. code-block:: text

        $ cat data/file1.bed
        track name="BedTest"
        20    1          14000000
        20    17000000   18000000
        ...

        $ cat file2.bed
        track name="BedTest"
        20    1          14000000  cnv1
        20    17000000   18000000  cnv2
        ...


    Notes
    -----

    The table produced by this method has one of two possible structures. If
    the .bed file has only three fields (`chrom`, `chromStart`, and
    `chromEnd`), then the produced table has only one column:

        - **interval** (:class:`.tinterval`) - Row key. Genomic interval. If
          `reference_genome` is defined, the point type of the interval will be
          :class:`.tlocus` parameterized by the `reference_genome`. Otherwise,
          the point type is a :class:`.tstruct` with two fields: `contig` with
          type :py:data:`.tstr` and `position` with type :py:data:`.tint32`.

    If the .bed file has four or more columns, then Hail will store the fourth
    column as a field in the table:

        - *interval* (:class:`.tinterval`) - Row key. Genomic interval. Same schema as above.
        - *target* (:py:data:`.tstr`) - Fourth column of .bed file.

    `UCSC bed files <https://genome.ucsc.edu/FAQ/FAQformat.html#format1>`__ can
    have up to 12 fields, but Hail will only ever look at the first four. Hail
    ignores header lines in BED files.

    Warning
    -------
        UCSC BED files are 0-indexed and end-exclusive. The line "5  100  105"
        will contain locus ``5:105`` but not ``5:100``. Details
        `here <http://genome.ucsc.edu/blog/the-ucsc-genome-browser-coordinate-counting-systems/>`__.

    Parameters
    ----------
    path : :obj:`str`
        Path to .bed file.

    reference_genome : :obj:`str` or :class:`.ReferenceGenome`, optional
        Reference genome to use.

    Returns
    -------
    :class:`.Table`
        Interval-keyed table.
    """
    # FIXME: once interval join support is added, add the following examples:
    # Add the variant annotation ``va.cnvRegion: Boolean`` indicating inclusion in
    # at least one interval of the three-column BED file `file1.bed`:

    # >>> bed = hl.import_bed('data/file1.bed')
    # >>> vds_result = vds.annotate_rows(cnvRegion = bed[vds.locus])

    # Add a variant annotation **va.cnvRegion** (*String*) with value given by the
    # fourth column of ``file2.bed``:

    # >>> bed = hl.import_bed('data/file2.bed')
    # >>> vds_result = vds.annotate_rows(cnvID = bed[vds.locus])

    rg = reference_genome._jrep if reference_genome else None

    jt = Env.hail().table.Table.importBED(Env.hc()._jhc, path, joption(rg))
    return Table(jt)


@typecheck(path=str,
           quant_pheno=bool,
           delimiter=str,
           missing=str)
def import_fam(path, quant_pheno=False, delimiter=r'\\s+', missing='NA'):
    """Import a PLINK FAM file into a :class:`.Table`.

    Examples
    --------

    Import a tab-separated
    `FAM file <https://www.cog-genomics.org/plink2/formats#fam>`__
    with a case-control phenotype:

    >>> fam_kt = hl.import_fam('data/case_control_study.fam')

    Import a FAM file with a quantitative phenotype:

    >>> fam_kt = hl.import_fam('data/quantitative_study.fam', quant_pheno=True)

    Notes
    -----

    In Hail, unlike PLINK, the user must *explicitly* distinguish between
    case-control and quantitative phenotypes. Importing a quantitative
    phenotype without ``quant_pheno=True`` will return an error
    (unless all values happen to be `0`, `1`, `2`, or `-9`):

    The resulting :class:`.Table` will have fields, types, and values that are interpreted as missing.

     - *fam_id* (:py:data:`.tstr`) -- Family ID (missing = "0")
     - *id* (:py:data:`.tstr`) -- Sample ID (key column)
     - *pat_id* (:py:data:`.tstr`) -- Paternal ID (missing = "0")
     - *mat_id* (:py:data:`.tstr`) -- Maternal ID (missing = "0")
     - *is_female* (:py:data:`.tstr`) -- Sex (missing = "NA", "-9", "0")

    One of:

     - *is_case* (:py:data:`.tbool`) -- Case-control phenotype (missing = "0", "-9",
       non-numeric or the ``missing`` argument, if given.
     - *quant_pheno* (:py:data:`.tfloat64`) -- Quantitative phenotype (missing = "NA" or
       the ``missing`` argument, if given.

    Parameters
    ----------
    path : :obj:`str`
        Path to FAM file.
    quant_pheno : :obj:`bool`
        If ``True``, phenotype is interpreted as quantitative.
    delimiter : :obj:`str`
        Field delimiter regex.
    missing : :obj:`str`
        The string used to denote missing values. For case-control, 0, -9, and
        non-numeric are also treated as missing.

    Returns
    -------
    :class:`.Table`
    """

    jkt = Env.hail().table.Table.importFam(Env.hc()._jhc, path,
                                           quant_pheno, delimiter, missing)
    return Table(jkt)


@typecheck(regex=str,
           path=oneof(str, listof(str)),
           max_count=int)
def grep(regex, path, max_count=100):
    """Searches given paths for all lines containing regex matches.

        Examples
        --------

        Print all lines containing the string ``hello`` in *file.txt*:

        >>> hl.grep('hello','data/file.txt')

        Print all lines containing digits in *file1.txt* and *file2.txt*:

        >>> hl.grep('\d', ['data/file1.txt','data/file2.txt'])

        Notes
        -----
        :func:`.grep` mimics the basic functionality of Unix ``grep`` in
        parallel, printing results to the screen. This command is provided as a
        convenience to those in the statistical genetics community who often
        search enormous text files like VCFs. Hail uses `Java regular expression
        patterns
        <https://docs.oracle.com/javase/8/docs/api/java/util/regex/Pattern.html>`__.
        The `RegExr sandbox <http://regexr.com/>`__ may be helpful.

        Parameters
        ----------
        regex : :obj:`str`
            The regular expression to match.
        path : :obj:`str` or :obj:`list` of :obj:`str`
            The files to search.
        max_count : :obj:`int`
            The maximum number of matches to return
        """
    Env.hc()._jhc.grep(regex, jindexed_seq_args(path), max_count)


@typecheck(path=oneof(str, listof(str)),
           sample_file=nullable(str),
           entry_fields=listof(str),
           min_partitions=nullable(int),
           reference_genome=nullable(reference_genome_type),
           contig_recoding=nullable(dictof(str, str)),
           tolerance=numeric)
def import_bgen(path, entry_fields, sample_file=None,
                min_partitions=None, reference_genome='default',
                contig_recoding=None, tolerance=0.2):
    """Import BGEN file(s) as a :class:`.MatrixTable`.

    Examples
    --------

    Import a BGEN file as a matrix table with GT and GP entry fields,
    renaming contig name "01" to "1":

    >>> ds_result = hl.import_bgen("data/example.8bits.bgen",
    ...                            entry_fields=['GT', 'GP'],
    ...                            sample_file="data/example.8bits.sample",
    ...                            contig_recoding={"01": "1"})

    Import a BGEN file as a matrix table with genotype dosage entry field,
    renaming contig name "01" to "1":

    >>> ds_result = hl.import_bgen("data/example.8bits.bgen",
    ...                             entry_fields=['dosage'],
    ...                             sample_file="data/example.8bits.sample",
    ...                             contig_recoding={"01": "1"})

    Notes
    -----

    Hail supports importing data from v1.1 and v1.2 of the
    `BGEN file format <http://www.well.ox.ac.uk/~gav/bgen_format/bgen_format.html>`__.
    For v1.2, genotypes must be **unphased** and **diploid**, and genotype
    probability blocks must be compressed with zlib or uncompressed. If
    `entry_fields` includes ``'dosage'``, all variants must be bi-allelic.

    Each BGEN file must have a corresponding index file, which can be generated
    with :func:`.index_bgen`. To load multiple files at the same time,
    use :ref:`Hadoop Glob Patterns <sec-hadoop-glob>`.

    **Column Fields**

    - `s` (:py:data:`.tstr`) -- Column key. This is the sample ID imported
      from the first column of the sample file if given. Otherwise, the sample
      ID is taken from the sample identifying block in the first BGEN file if it
      exists; else IDs are assigned from `_0`, `_1`, to `_N`.

    **Row Fields**

    - `locus` (:class:`.tlocus` or :class:`.tstruct`) -- Row key. The chromosome
      and position. If `reference_genome` is defined, the type will be
      :class:`.tlocus` parameterized by `reference_genome`. Otherwise, the type
      will be a :class:`.tstruct` with two fields: `contig` with type
      :py:data:`.tstr` and `position` with type :py:data:`.tint32`.
    - `alleles` (:class:`.tarray` of :py:data:`.tstr`) -- Row key. An array
      containing the alleles of the variant. The reference allele (A allele in
      the v1.1 spec and first allele in the v1.2 spec) is the first element in
      the array.
    - `varid` (:py:data:`.tstr`) -- The variant identifier. The third field in
      each variant identifying block.
    - `rsid` (:py:data:`.tstr`) -- The rsID for the variant. The fifth field in
      each variant identifying block.

    **Entry Fields**

    Up to three entry fields are created, as determined by `entry_fields` which
    must be non-empty. For best performance, include precisely those fields
    required for your analysis. For BGEN v1.1 files, all entry fields are set
    to missing if the sum of the genotype probabilities is a distance greater
    than `tolerance` from 1.0.

    - `GT` (:py:data:`.tcall`) -- The hard call corresponding to the genotype with
      the greatest probability.
    - `GP` (:class:`.tarray` of :py:data:`.tfloat64`) -- Genotype probabilities
      as defined by the BGEN file spec. For bi-allelic variants, the array has
      three elements giving the probabilities of homozygous reference,
      heterozygous, and homozygous alternate genotype, in that order.
      For v1.2 files, no modifications are made to these genotype
      probabilities. For v1.1 files, the probabilities are normalized to
      sum to 1.0. For example, ``[0.98, 0.0, 0.0]`` is normalized to
      ``[1.0, 0.0, 0.0]``.
    - `dosage` (:py:data:`.tfloat64`) -- The expected value of the number of
      alternate alleles, given by the probability of heterozygous genotype plus
      twice the probability of homozygous alternate genotype. All variants must
      be bi-allelic.

    Parameters
    ----------
    path : :obj:`str` or :obj:`list` of :obj:`str`
        BGEN file(s) to read.
    entry_fields : :obj:`list` of :obj:`str`
        List of entry fields to create.
        Options: ``'GT'``, ``'GP'``, ``'dosage'``.
    sample_file : :obj:`str`, optional
        Sample file to read the sample ids from. If specified, the number of
        samples in the file must match the number in the BGEN file(s).
    min_partitions : :obj:`int`, optional
        Number of partitions.
    reference_genome : :obj:`str` or :class:`.ReferenceGenome`, optional
        Reference genome to use.
    contig_recoding : :obj:`dict` of :obj:`str` to :obj:`str`, optional
        Dict of old contig name to new contig name. The new contig name must be
        in the reference genome given by `reference_genome`.
    tolerance : :obj:`float`
        If the sum of the probabilities for an entry differ from 1.0 by more
        than the tolerance, set the entry to missing. Only applicable to v1.1.

    Returns
    -------
    :class:`.MatrixTable`
    """

    rg = reference_genome._jrep if reference_genome else None

    if not entry_fields:
        raise FatalError("import_bgen: entry_fields must be non-empty."
                         "\n    Options: 'GT', 'GP', 'dosage'.")

    entry_set = set(entry_fields)
    bad_entry_fields = list(entry_set - {'GT', 'GP', 'dosage'})

    if bad_entry_fields:
        word = plural('value', len(bad_entry_fields))
        raise FatalError("import_bgen: found invalid {} {} in entry_fields."
                         "\n    Options: 'GT', 'GP', 'dosage'.".format(word, bad_entry_fields))

    if contig_recoding:
        contig_recoding = tdict(tstr, tstr)._convert_to_j(contig_recoding)

    jmt = Env.hc()._jhc.importBgens(jindexed_seq_args(path), joption(sample_file),
                                    'GT' in entry_set, 'GP' in entry_set, 'dosage' in entry_set,
                                    joption(min_partitions), joption(rg), joption(contig_recoding), tolerance)
    return MatrixTable(jmt)


@typecheck(path=oneof(str, listof(str)),
           sample_file=nullable(str),
           tolerance=numeric,
           min_partitions=nullable(int),
           chromosome=nullable(str),
           reference_genome=nullable(reference_genome_type),
           contig_recoding=nullable(dictof(str, str)))
def import_gen(path, sample_file=None, tolerance=0.2, min_partitions=None, chromosome=None,
               reference_genome='default', contig_recoding=None):
    """
    Import GEN file(s) as a :class:`.MatrixTable`.

    Examples
    --------

    >>> ds = hl.import_gen('data/example.gen',
    ...                    sample_file='data/example.sample')

    Notes
    -----

    For more information on the GEN file format, see `here
    <http://www.stats.ox.ac.uk/%7Emarchini/software/gwas/file_format.html#mozTocId40300>`__.

    If the GEN file has only 5 columns before the start of the genotype
    probability data (chromosome field is missing), you must specify the
    chromosome using the `chromosome` parameter.

    To load multiple files at the same time, use :ref:`Hadoop Glob Patterns
    <sec-hadoop-glob>`.

    **Column Fields**

    - `s` (:py:data:`.tstr`) -- Column key. This is the sample ID imported
      from the first column of the sample file.

    **Row Fields**

    - `locus` (:class:`.tlocus` or :class:`.tstruct`) -- Row key. The genomic
      location consisting of the chromosome (1st column if present, otherwise
      given by `chromosome`) and position (3rd column if `chromosome` is not
      defined). If `reference_genome` is defined, the type will be
      :class:`.tlocus` parameterized by `reference_genome`. Otherwise, the type
      will be a :class:`.tstruct` with two fields: `contig` with type
      :py:data:`.tstr` and `position` with type :py:data:`.tint32`.
    - `alleles` (:class:`.tarray` of :py:data:`.tstr`) -- Row key. An array
      containing the alleles of the variant. The reference allele (4th column if
      `chromosome` is not defined) is the first element of the array and the
      alternate allele (5th column if `chromosome` is not defined) is the second
      element.
    - `varid` (:py:data:`.tstr`) -- The variant identifier. 2nd column of GEN
      file if chromosome present, otherwise 1st column.
    - `rsid` (:py:data:`.tstr`) -- The rsID. 3rd column of GEN file if
      chromosome present, otherwise 2nd column.

    **Entry Fields**

    - `GT` (:py:data:`.tcall`) -- The hard call corresponding to the genotype with
      the highest probability.
    - `GP` (:class:`.tarray` of :py:data:`.tfloat64`) -- Genotype probabilities
      as defined by the GEN file spec. The array is set to missing if the
      sum of the probabilities is a distance greater than the `tolerance`
      parameter from 1.0. Otherwise, the probabilities are normalized to sum to
      1.0. For example, the input ``[0.98, 0.0, 0.0]`` will be normalized to
      ``[1.0, 0.0, 0.0]``.

    Parameters
    ----------
    path : :obj:`str` or :obj:`list` of :obj:`str`
        GEN files to import.
    sample_file : :obj:`str`
        Sample file to import.
    tolerance : :obj:`float`
        If the sum of the genotype probabilities for a genotype differ from 1.0
        by more than the tolerance, set the genotype to missing.
    min_partitions : :obj:`int`, optional
        Number of partitions.
    chromosome : :obj:`str`, optional
        Chromosome if not included in the GEN file
    reference_genome : :obj:`str` or :class:`.ReferenceGenome`, optional
        Reference genome to use.
    contig_recoding : :obj:`dict` of :obj:`str` to :obj:`str`, optional
        Dict of old contig name to new contig name. The new contig name must be
        in the reference genome given by `reference_genome`.

    Returns
    -------
    :class:`.MatrixTable`
    """

    rg = reference_genome._jrep if reference_genome else None

    if contig_recoding:
        contig_recoding = tdict(tstr, tstr)._convert_to_j(contig_recoding)

    jmt = Env.hc()._jhc.importGens(jindexed_seq_args(path), sample_file, joption(chromosome), joption(min_partitions),
                                   tolerance, joption(rg), joption(contig_recoding))
    return MatrixTable(jmt)


@typecheck(paths=oneof(str, listof(str)),
           key=oneof(str, listof(str)),
           min_partitions=nullable(int),
           impute=bool,
           no_header=bool,
           comment=nullable(str),
           delimiter=str,
           missing=str,
           types=dictof(str, hail_type),
           quote=nullable(char))
def import_table(paths, key=[], min_partitions=None, impute=False, no_header=False,
                 comment=None, delimiter="\t", missing="NA", types={}, quote=None):
    """Import delimited text file (text table) as :class:`.Table`.

    The resulting :class:`.Table` will have no key fields. Use
    :meth:`.Table.key_by` to specify keys.

    Examples
    --------

    Consider this file:

    .. code-block:: text

        $ cat data/samples1.tsv
        Sample     Height  Status  Age
        PT-1234    154.1   ADHD    24
        PT-1236    160.9   Control 19
        PT-1238    NA      ADHD    89
        PT-1239    170.3   Control 55

    The field ``Height`` contains floating-point numbers and the field ``Age``
    contains integers.

    To import this table using field types:

    >>> table = hl.import_table('data/samples1.tsv',
    ...                              types={'Height': hl.tfloat64, 'Age': hl.tint32})

    Note ``Sample`` and ``Status`` need no type, because :py:data:`.tstr` is
    the default type.

    To import a table using type imputation (which causes the file to be parsed
    twice):

    >>> table = hl.import_table('data/samples1.tsv', impute=True)

    **Detailed examples**

    Let's import fields from a CSV file with missing data and special characters:

    .. code-block:: text

        $ cat data/samples2.tsv
        Batch,PT-ID
        1kg,PT-0001
        1kg,PT-0002
        study1,PT-0003
        study3,PT-0003
        .,PT-0004
        1kg,PT-0005
        .,PT-0006
        1kg,PT-0007

    In this case, we should:

    - Pass the non-default delimiter ``,``

    - Pass the non-default missing value ``.``

    >>> table = hl.import_table('data/samples2.tsv', delimiter=',', missing='.')

    Let's import a table from a file with no header and sample IDs that need to
    be transformed.  Suppose the sample IDs are of the form ``NA#####``. This
    file has no header line, and the sample ID is hidden in a field with other
    information.

    .. code-block: text

        $ cat data/samples3.tsv
        1kg_NA12345   female
        1kg_NA12346   male
        1kg_NA12348   female
        pgc_NA23415   male
        pgc_NA23418   male

    To import:

    >>> t = hl.import_table('data/samples3.tsv', no_header=True)
    >>> t = t.annotate(sample = t.f0.split("_")[1]).key_by('sample')

    Notes
    -----

    The `impute` parameter tells Hail to scan the file an extra time to gather
    information about possible field types. While this is a bit slower for large
    files because the file is parsed twice, the convenience is often worth this
    cost.

    The `delimiter` parameter is either a delimiter character (if a single
    character) or a field separator regex (2 or more characters). This regex
    follows the `Java regex standard
    <http://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html>`_.

    .. note::

        Use ``delimiter='\\s+'`` to specify whitespace delimited files.

    If set, the `comment` parameter causes Hail to skip any line that starts
    with the given string. For example, passing ``comment='#'`` will skip any
    line beginning in a pound sign.

    The `missing` parameter defines the representation of missing data in the table.

    .. note::

        The `comment` and `missing` parameters are **NOT** regexes.

    The `no_header` parameter indicates that the file has no header line. If
    this option is passed, then the field names will be `f0`, `f1`,
    ... `fN` (0-indexed).

    The `types` parameter allows the user to pass the types of fields in the
    table. It is an :obj:`dict` keyed by :obj:`str`, with :class:`.HailType` values.
    See the examples above for a standard usage. Additionally, this option can
    be used to override type imputation. For example, if the field
    ``Chromosome`` only contains the values ``1`` through ``22``, it will be
    imputed to have type :py:data:`.tint32`, whereas most Hail methods expect
    that a chromosome field will be of type :py:data:`.tstr`. Setting
    ``impute=True`` and ``types={'Chromosome': hl.tstr}`` solves this problem.

    Parameters
    ----------

    paths: :obj:`str` or :obj:`list` of :obj:`str`
        Files to import.
    key: :obj:`str` or :obj:`list` of :obj:`str`
        Key fields(s).
    min_partitions: :obj:`int` or :obj:`None`
        Minimum number of partitions.
    no_header: :obj:`bool`
        If ``True```, assume the file has no header and name the N fields `f0`,
        `f1`, ... `fN` (0-indexed).
    impute: :obj:`bool`
        If ``True``, Impute field types from the file.
    comment: :obj:`str` or :obj:`None`
        Skip lines beginning with the given string.
    delimiter: :obj:`str`
        Field delimiter regex.
    missing: :obj:`str`
        Identifier to be treated as missing.
    types: :obj:`dict` mapping :obj:`str` to :class:`.HailType`
        Dictionary defining field types.
    quote: :obj:`str` or :obj:`None`
        Quote character.

    Returns
    -------
    :class:`.Table`
    """
    key = wrap_to_list(key)
    paths = wrap_to_list(paths)
    jtypes = {k: v._jtype for k, v in types.items()}

    jt = Env.hc()._jhc.importTable(paths, key, min_partitions, jtypes, comment, delimiter, missing,
                                   no_header, impute, quote)
    return Table(jt)


@typecheck(paths=oneof(str, listof(str)),
           row_fields=dictof(str, hail_type),
           row_key=oneof(str, listof(str)),
           entry_type=enumeration(tint32, tint64, tfloat32, tfloat64, tstr),
           missing=str,
           min_partitions=nullable(int),
           no_header=bool,
           force_bgz=bool)
def import_matrix_table(paths, row_fields={}, row_key=[], entry_type=tint32, missing="NA", min_partitions=None,
                        no_header=False, force_bgz=False):
    """
    Import tab-delimited file(s) as a :class:`.MatrixTable`.

    Examples
    --------

        Consider the following file containing counts from a RNA sequencing
        dataset:

    .. code-block:: text

        $ cat data/matrix1.tsv
        Barcode Tissue  Days    GENE1   GENE2   GENE3   GENE4
        TTAGCCA brain   1.0     0       0       1       0
        ATCACTT kidney  5.5     3       0       2       0
        CTCTTCT kidney  2.5     0       0       0       1
        CTATATA brain   7.0     0       0       3       0

    The field ``Height`` contains floating-point numbers and the field ``Age``
    contains integers.

    To import this matrix:

    >>> matrix1 = hl.import_matrix_table('data/matrix1.tsv',
    ...                                  row_fields={'Barcode': hl.tstr, 'Tissue': hl.tstr, 'Days':hl.tfloat32},
    ...                                  row_key='Barcode')
    >>> matrix1.describe()
    ----------------------------------------
    Global fields:
        None
    ----------------------------------------
    Column fields:
        'col_id': str
    ----------------------------------------
    Row fields:
        'Barcode': str
        'Tissue': str
        'Days': float32
    ----------------------------------------
    Entry fields:
        'x': int32
    ----------------------------------------
    Column key:
        'col_id': str
    Row key:
        'Barcode': str
    Partition key:
        'Barcode': str
    ----------------------------------------

    In this example, the header information is missing for the row fields, but
    the column IDs are still present:

    .. code-block:: text

        $ cat data/matrix2.tsv
        GENE1   GENE2   GENE3   GENE4
        TTAGCCA brain   1.0     0       0       1       0
        ATCACTT kidney  5.5     3       0       2       0
        CTCTTCT kidney  2.5     0       0       0       1
        CTATATA brain   7.0     0       0       3       0

    The row fields get imported as `f0`, `f1`, and `f2`, so we need to do:

    >>> matrix2 = hl.import_matrix_table('data/matrix2.tsv',
    ...                                  row_fields={'f0': hl.tstr, 'f1': hl.tstr, 'f2':hl.tfloat32},
    ...                                  row_key='f0')
    >>> matrix2.rename({'f0': 'Barcode', 'f1': 'Tissue', 'f2': 'Days'})

    Sometimes, the header and row information is missing completely:

    .. code-block:: text

        $ cat data/matrix3.tsv
        0       0       1       0
        3       0       2       0
        0       0       0       1
        0       0       3       0

    >>> matrix3 = hl.import_matrix_table('data/matrix3.tsv', no_header=True)

    In this case, the file has no row fields, so we use the default
    row index as a key for the imported matrix table.

    Notes
    -----

    The resulting matrix table has the following structure:

        * The row fields are named as specified in the column header. If they
          are missing from the header or ``no_header=True``, row field names are
          set to the strings `f0`, `f1`, ... (0-indexed) in column order. The types
          of all row fields must be specified in the `row_fields` argument.
        * The row key is taken from the `row_key` argument, and must be a
          subset of row fields. If left empty, the row key will be a new row field
          `row_idx` of type :obj:`int`, whose values 0, 1, ... index the original
          rows of the matrix.
        * There is one column field, **col_id**, which is a key field of type
          :obj:str or :obj:int. By default, its values are the strings given by
          the corresponding column names in the header line. If ``no_header=True``,
          column IDs are set to integers 0, 1, ... (also 0-indexed) in column
          order.
        * There is one entry field, **x**, that contains the data from the imported
          matrix.


    All columns to be imported as row fields must be at the start of the row.

    Unlike import_table, no type imputation is done so types must be specified
    for all columns that should be imported as row fields. (The other columns are
    imported as entries in the matrix.)

    The header information for row fields is allowed to be missing, if the
    column IDs are present, but the header must then consist only of tab-delimited
    column IDs (no row field names).

    Parameters
    ----------
    paths: :obj:`str` or :obj:`list` of :obj:`str`
        Files to import.
    row_fields: :obj:`dict` of :obj:`str` to :class:`.HailType`
        Columns to take as row fields in the MatrixTable. They must be located
        before all entry columns.
    row_key: :obj:`str` or :obj:`list` of :obj:`str`
        Key fields(s). If empty, creates an index `row_id` to use as key.
    entry_type: :class:`.HailType`
        Type of entries in matrix table. Must be one of: :py:data:`.tint32`,
        :py:data:`.tint64`, :py:data:`.tfloat32`, :py:data:`.tfloat64`, or
        :py:data:`.tstr`. Default: :py:data:`.tint32`.
    missing: :obj:`str`
        Identifier to be treated as missing. Default: NA
    min_partitions: :obj:`int` or :obj:`None`
        Minimum number of partitions.
    no_header: :obj:`bool`
        If ``True``, assume the file has no header and name the row fields `f0`,
        `f1`, ... `fK` (0-indexed) and the column keys 0, 1, ... N.
    force_bgz : :obj:`bool`
        If ``True``, load **.gz** files as blocked gzip files, assuming
        that they were actually compressed using the BGZ codec.

    Returns
    -------
    :class:`.MatrixTable`
        MatrixTable constructed from imported data
    """

    paths = wrap_to_list(paths)
    jrow_fields = {k: v._jtype for k, v in row_fields.items()}
    for k, v in row_fields.items():
        if v not in {tint32, tint64, tfloat32, tfloat64, tstr}:
            raise FatalError("""import_matrix_table expects field types to be one of: 
            'int32', 'int64', 'float32', 'float64', 'str': field {} had type '{}'""".format(repr(k), v))
    row_key = wrap_to_list(row_key)
    if entry_type not in {tint32, tint64, tfloat32, tfloat64, tstr}:
        raise FatalError("""import_matrix_table expects entry types to be one of: 
        'int32', 'int64', 'float32', 'float64', 'str': found '{}'""".format(entry_type))

    jmt = Env.hc()._jhc.importMatrix(paths, jrow_fields, row_key, entry_type._jtype, missing, joption(min_partitions),
                                     no_header, force_bgz)
    return MatrixTable(jmt)


@typecheck(bed=str,
           bim=str,
           fam=str,
           min_partitions=nullable(int),
           delimiter=str,
           missing=str,
           quant_pheno=bool,
           a2_reference=bool,
           reference_genome=nullable(reference_genome_type),
           contig_recoding=nullable(dictof(str, str)))
def import_plink(bed, bim, fam,
                 min_partitions=None,
                 delimiter='\\\\s+',
                 missing='NA',
                 quant_pheno=False,
                 a2_reference=True,
                 reference_genome='default',
                 contig_recoding={'23': 'X',
                                  '24': 'Y',
                                  '25': 'X',
                                  '26': 'MT'}):
    """Import a PLINK dataset (BED, BIM, FAM) as a :class:`.MatrixTable`.

    Examples
    --------

    >>> ds = hl.import_plink(bed="data/test.bed",
    ...                      bim="data/test.bim",
    ...                      fam="data/test.fam")

    Notes
    -----

    Only binary SNP-major mode files can be read into Hail. To convert your
    file from individual-major mode to SNP-major mode, use PLINK to read in
    your fileset and use the ``--make-bed`` option.

    Hail ignores the centimorgan position (Column 3 in BIM file).

    Hail uses the individual ID (column 2 in FAM file) as the sample id (`s`).
    The individual IDs must be unique.

    The resulting :class:`.MatrixTable` has the following fields:

    * Row fields:

        * `locus` (:class:`.tlocus` or :class:`.tstruct`) -- Row key. The
          chromosome and position. If `reference_genome` is defined, the type
          will be :class:`.tlocus` parameterized by `reference_genome`.
          Otherwise, the type will be a :class:`.tstruct` with two fields:
          `contig` with type :py:data:`.tstr` and `position` with type
          :py:data:`.tint32`.
        * `alleles` (:class:`.tarray` of :py:data:`.tstr`) -- Row key. An
          array containing the alleles of the variant. The reference allele (A2
          if `a2_reference` is ``True``) is the first element in the array.
        * `rsid` (:py:data:`.tstr`) -- Column 2 in the BIM file.

    * Column fields:

        * `s` (:py:data:`.tstr`) -- Column 2 in the Fam file (key field).
        * `fam_id` (:py:data:`.tstr`) -- Column 1 in the FAM file. Set to
          missing if ID equals "0".
        * `pat_id` (:py:data:`.tstr`) -- Column 3 in the FAM file. Set to
          missing if ID equals "0".
        * `mat_id` (:py:data:`.tstr`) -- Column 4 in the FAM file. Set to
          missing if ID equals "0".
        * `is_female` (:py:data:`.tstr`) -- Column 5 in the FAM file. Set to
          missing if value equals "-9", "0", or "N/A". Set to true if value
          equals "2". Set to false if value equals "1".
        * `is_case` (:py:data:`.tstr`) -- Column 6 in the FAM file. Only
          present if `quant_pheno` equals False. Set to missing if value equals
          "-9", "0", "N/A", or the value specified by `missing`. Set to true if
          value equals "2". Set to false if value equals "1".
        * `quant_pheno` (:py:data:`.tstr`) -- Column 6 in the FAM file. Only
          present if `quant_pheno` equals True. Set to missing if value equals
          `missing`.

    * Entry fields:

        * `GT` (:py:data:`.tcall`) -- Genotype call (diploid, unphased).

    Parameters
    ----------
    bed : :obj:`str`
        PLINK BED file.

    bim : :obj:`str`
        PLINK BIM file.

    fam : :obj:`str`
        PLINK FAM file.

    min_partitions : :obj:`int`, optional
        Number of partitions.

    missing : :obj:`str`
        String used to denote missing values **only** for the phenotype field.
        This is in addition to "-9", "0", and "N/A" for case-control
        phenotypes.

    delimiter : :obj:`str`
        FAM file field delimiter regex.

    quant_pheno : :obj:`bool`
        If true, FAM phenotype is interpreted as quantitative.

    a2_reference : :obj:`bool`
        If True, A2 is treated as the reference allele. If False, A1 is treated
        as the reference allele.

    reference_genome : :obj:`str` or :class:`.ReferenceGenome`, optional
        Reference genome to use.

    contig_recoding : :obj:`dict` of :obj:`str` to :obj:`str`, optional
        Dict of old contig name to new contig name. The new contig name must be
        in the reference genome given by ``reference_genome``.

    Returns
    -------
    :class:`.MatrixTable`
    """

    rg = reference_genome._jrep if reference_genome else None

    if contig_recoding:
        contig_recoding = tdict(tstr,
                                tstr)._convert_to_j(contig_recoding)

    jmt = Env.hc()._jhc.importPlink(bed, bim, fam, joption(min_partitions),
                                    delimiter, missing, quant_pheno,
                                    a2_reference, joption(rg),
                                    joption(contig_recoding))

    return MatrixTable(jmt)


@typecheck(path=oneof(str, listof(str)),
           _drop_cols=bool,
           _drop_rows=bool)
def read_matrix_table(path, _drop_cols=False, _drop_rows=False):
    """Read in a :class:`.MatrixTable` written with written with :meth:`.MatrixTable.write`

    Parameters
    ----------
    path : :obj:`str`
        File to read.

    Returns
    -------
    :class:`.MatrixTable`
    """
    return MatrixTable(Env.hc()._jhc.read(path, _drop_cols, _drop_rows))


@typecheck(path=str)
def get_vcf_metadata(path):
    """Extract metadata from VCF header.

    Examples
    --------

    >>> metadata = hl.get_vcf_metadata('data/example2.vcf.bgz')
    {'filter': {'LowQual': {'Description': ''}, ...},
     'format': {'AD': {'Description': 'Allelic depths for the ref and alt alleles in the order listed',
                       'Number': 'R',
                       'Type': 'Integer'}, ...},
     'info': {'AC': {'Description': 'Allele count in genotypes, for each ALT allele, in the same order as listed',
                     'Number': 'A',
                     'Type': 'Integer'}, ...}}

    Notes
    -----

    This method parses the VCF header to extract the `ID`, `Number`,
    `Type`, and `Description` fields from FORMAT and INFO lines as
    well as `ID` and `Description` for FILTER lines. For example,
    given the following header lines:

    .. code-block:: text

        ##FORMAT=<ID=DP,Number=1,Type=Integer,Description="Read Depth">
        ##FILTER=<ID=LowQual,Description="Low quality">
        ##INFO=<ID=MQ,Number=1,Type=Float,Description="RMS Mapping Quality">

    The resulting Python dictionary returned would be

    .. code-block:: python

        metadata = {'filter': {'LowQual': {'Description': 'Low quality'}},
                    'format': {'DP': {'Description': 'Read Depth',
                                      'Number': '1',
                                      'Type': 'Integer'}},
                    'info': {'MQ': {'Description': 'RMS Mapping Quality',
                                    'Number': '1',
                                    'Type': 'Float'}}}

    which can be used with :func:`.export_vcf` to fill in the relevant fields in the header.

    Parameters
    ----------
    path : :obj:`str`
        VCF file(s) to read. If more than one file is given, the first
        file is used.

    Returns
    -------
    :obj:`dict` of :obj:`str` to (:obj:`dict` of :obj:`str` to (:obj:`dict` of :obj:`str` to :obj:`str`))
    """
    typ = tdict(tstr, tdict(tstr, tdict(tstr, tstr)))
    return typ._convert_to_py(Env.hc()._jhc.parseVCFMetadata(path))


@typecheck(path=oneof(str, listof(str)),
           force=bool,
           force_bgz=bool,
           header_file=nullable(str),
           min_partitions=nullable(int),
           drop_samples=bool,
           call_fields=oneof(str, listof(str)),
           reference_genome=nullable(reference_genome_type),
           contig_recoding=nullable(dictof(str, str)))
def import_vcf(path, force=False, force_bgz=False, header_file=None, min_partitions=None,
               drop_samples=False, call_fields=[], reference_genome='default', contig_recoding=None):
    """Import VCF file(s) as a :class:`.MatrixTable`.

    Examples
    --------

    >>> ds = hl.import_vcf('data/example2.vcf.bgz')

    Notes
    -----

    Hail is designed to be maximally compatible with files in the `VCF v4.2
    spec <https://samtools.github.io/hts-specs/VCFv4.2.pdf>`__.

    :func:`.import_vcf` takes a list of VCF files to load. All files must have
    the same header and the same set of samples in the same order (e.g., a
    dataset split by chromosome). Files can be specified as :ref:`Hadoop glob
    patterns <sec-hadoop-glob>`.

    Ensure that the VCF file is correctly prepared for import: VCFs should
    either be uncompressed (**.vcf**) or block compressed (**.vcf.bgz**). If you
    have a large compressed VCF that ends in **.vcf.gz**, it is likely that the
    file is actually block-compressed, and you should rename the file to
    **.vcf.bgz** accordingly. If you actually have a standard gzipped file, it
    is possible to import it to Hail using the `force` parameter. However, this
    is not recommended -- all parsing will have to take place on one node
    because gzip decompression is not parallelizable. In this case, import will
    take significantly longer.

    :func:`.import_vcf` does not perform deduplication - if the provided VCF(s)
    contain multiple records with the same chrom, pos, ref, alt, all these
    records will be imported as-is (in multiple rows) and will not be collapsed
    into a single variant.

    .. note::

        Using the **FILTER** field:

        The information in the FILTER field of a VCF is contained in the
        ``filters`` row field. This annotation is a ``set<str>`` and can be
        queried for filter membership with expressions like
        ``ds.filters.contains("VQSRTranche99.5...")``. Variants that are flagged
        as "PASS" will have no filters applied; for these variants,
        ``hl.len(ds.filters)`` is ``0``. Thus, filtering to PASS variants
        can be done with :meth:`.MatrixTable.filter_rows` as follows:

        >>> pass_ds = dataset.filter_rows(hl.len(dataset.filters) == 0)

    **Column Fields**

    - `s` (:py:data:`.tstr`) -- Column key. This is the sample ID.

    **Row Fields**

    - `locus` (:class:`.tlocus` or :class:`.tstruct`) -- Row key. The
      chromosome (CHROM field) and position (POS field). If `reference_genome`
      is defined, the type will be :class:`.tlocus` parameterized by
      `reference_genome`. Otherwise, the type will be a :class:`.tstruct` with
      two fields: `contig` with type :py:data:`.tstr` and `position` with type
      :py:data:`.tint32`.
    - `alleles` (:class:`.tarray` of :py:data:`.tstr`) -- Row key. An array
      containing the alleles of the variant. The reference allele (REF field) is
      the first element in the array and the alternate alleles (ALT field) are
      the subsequent elements.
    - `filters` (:class:`.tset` of :py:data:`.tstr`) -- Set containing all filters applied to a
      variant.
    - `rsid` (:py:data:`.tstr`) -- rsID of the variant.
    - `qual` (:py:data:`.tfloat64`) -- Floating-point number in the QUAL field.
    - `info` (:class:`.tstruct`) -- All INFO fields defined in the VCF header
      can be found in the struct `info`. Data types match the type specified
      in the VCF header, and if the declared ``Number`` is not 1, the result
      will be stored as an array.

    **Entry Fields**

    :func:`.import_vcf` generates an entry field for each FORMAT field declared
    in the VCF header. The types of these fields are generated according to the
    same rules as INFO fields, with one difference -- "GT" and other fields
    specified in `call_fields` will be read as :py:data:`.tcall`.

    Parameters
    ----------
    path : :obj:`str` or :obj:`list` of :obj:`str`
        VCF file(s) to read.
    force : :obj:`bool`
        If ``True``, load **.vcf.gz** files serially. No downstream operations
        can be parallelized, so this mode is strongly discouraged.
    force_bgz : :obj:`bool`
        If ``True``, load **.vcf.gz** files as blocked gzip files, assuming
        that they were actually compressed using the BGZ codec.
    header_file : :obj:`str`, optional
        Optional header override file. If not specified, the first file in
        `path` is used.
    min_partitions : :obj:`int`, optional
        Minimum partitions to load per file.
    drop_samples : :obj:`bool`
        If ``True``, create sites-only dataset. Don't load sample IDs or
        entries.
    call_fields : :obj:`list` of :obj:`str`
        List of FORMAT fields to load as :py:data:`.tcall`. "GT" is loaded as
        a call automatically.
    reference_genome: :obj:`str` or :class:`.ReferenceGenome`, optional
        Reference genome to use.
    contig_recoding: :obj:`dict` of (:obj:`str`, :obj:`str`)
        Mapping from contig name in VCF to contig name in loaded dataset.
        All contigs must be present in the `reference_genome`, so this is
        useful for mapping differently-formatted data onto known references.

    Returns
    -------
    :class:`.MatrixTable`
    """

    rg = reference_genome._jrep if reference_genome else None

    if contig_recoding:
        contig_recoding = tdict(tstr, tstr)._convert_to_j(contig_recoding)

    jmt = Env.hc()._jhc.importVCFs(jindexed_seq_args(path), force, force_bgz, joption(header_file),
                                   joption(min_partitions), drop_samples, jset_args(call_fields),
                                   joption(rg), joption(contig_recoding))

    return MatrixTable(jmt)


@typecheck(path=oneof(str, listof(str)))
def index_bgen(path):
    """Index BGEN files as required by :func:`.import_bgen`.

    The index file is generated in the same directory as `path` with the
    filename of `path` appended by `.idx`.

    Example
    -------

    >>> hl.index_bgen("data/example.8bits.bgen")

    Warning
    -------

    While this method parallelizes over a list of BGEN files, each file is
    indexed serially by one core. Indexing several BGEN files on a large cluster
    is a waste of resources, so indexing should generally be done once,
    separately from large analyses.

    path: :obj:`str` or :obj:`list` of :obj:`str`
        .bgen files to index.

    """
    Env.hc()._jhc.indexBgen(jindexed_seq_args(path))


@typecheck(path=str)
def read_table(path):
    """Read in a :class:`.Table` written with :meth:`.Table.write`.

    Parameters
    ----------
    path : :obj:`str`
        File to read.

    Returns
    -------
    :class:`.Table`
    """
    return Table(Env.hc()._jhc.readTable(path))
