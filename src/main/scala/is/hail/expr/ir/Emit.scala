package is.hail.expr.ir

import is.hail.asm4s._
import is.hail.annotations._
import is.hail.annotations.aggregators._
import is.hail.expr.ir.functions.IRFunction
import is.hail.expr.types._
import is.hail.utils._

import scala.collection.mutable
import scala.language.existentials
import scala.language.postfixOps

object Emit {
  type E = Env[(TypeInfo[_], Code[Boolean], Code[_])]

  type F = (Code[Boolean], Code[_]) => Code[Unit]

  private[ir] def toCode(ir: IR, fb: FunctionBuilder[_], nSpecialArguments: Int): (Code[Unit], Code[Boolean], Code[_]) = {
    emit(ir, fb, Env.empty, new StagedBitSet(fb), None, nSpecialArguments)
  }

  def apply(ir: IR, fb: FunctionBuilder[_]) {
    apply(ir, fb, None, 1)
  }

  def apply(ir: IR, fb: FunctionBuilder[_], nSpecialArguments: Int) {
    apply(ir, fb, None, nSpecialArguments)
  }

  def apply(ir: IR, fb: FunctionBuilder[_], nSpecialArguments: Int, tAggIn: TAggregable) {
    apply(ir, fb, Some(tAggIn), nSpecialArguments)
  }

  private def apply(ir: IR, fb: FunctionBuilder[_], tAggIn: Option[TAggregable], nSpecialArguments: Int) {
    val (dov, mv, vv) = emit(ir, fb, Env.empty, new StagedBitSet(fb), tAggIn, nSpecialArguments)
    typeToTypeInfo(ir.typ) match {
      case ti: TypeInfo[t] =>
        fb.emit(Code(dov, mv.mux(
          Code._throw(Code.newInstance[RuntimeException, String]("cannot return empty")),
          coerce[t](vv))))
    }
  }

  private def emit(
    ir: IR,
    fb: FunctionBuilder[_],
    env: E,
    mb: StagedBitSet,
    tAggIn: Option[TAggregable],
    nSpecialArguments: Int): (Code[Unit], Code[Boolean], Code[_]) = {
    new Emit(fb, mb, tAggIn, nSpecialArguments).emit(ir, env)
  }
}

case class EmitArrayTriplet(setup: Code[Unit], m: Option[Code[Boolean]], addElements: Code[Unit])

case class ArrayIteratorTriplet(calcLength: Code[Unit], length: Option[Code[Int]], arrayEmitter: Emit.F => EmitArrayTriplet) {
  def wrapContinuation(contMap: (Emit.F, Code[Boolean], Code[_]) => Code[Unit]): ArrayIteratorTriplet =
    copy(calcLength = calcLength, length = length, arrayEmitter = { cont: Emit.F => arrayEmitter(contMap(cont, _, _)) })
}

private class Emit(
  fb: FunctionBuilder[_],
  mb: StagedBitSet,
  tAggInOpt: Option[TAggregable],
  nSpecialArguments: Int) {

  val methods: mutable.Map[IRFunction, MethodBuilder] = mutable.Map()

  import Emit.E
  import Emit.F

  /**
    * Invariants of the Returned Triplet
    * ----------------------------------
    *
    * The elements of the triplet are called (precompute, missingness, value)
    *
    *  1. evaluate each returned Code[_] at most once
    *  2. evaluate precompute *on all static code-paths* leading to missingness or value
    *  3. guard the the evaluation of value by missingness
    *
    * JVM gotcha:
    *  a variable must be initialized on all static code-paths prior to its use (ergo defaultValue)
    *
    * Argument Convention
    * -------------------
    *
    * {@code In(i)} occupies two argument slots, one for the value and one for a
    * missing bit. The value for {@code  In(0)} is passed as argument
    * {@code  nSpecialArguments + 1}. The missingness bit is the subsequent
    * argument. In general, the value for {@code  In(i)} appears at
    * {@code  nSpecialArguments + 1 + 2 * i}.
    *
    * There must always be at least one special argument: a {@code  Region} in
    * which the IR can allocate memory.
    *
    * Aggregating expressions must have at least two special arguments. As with
    * all expressions, the first argument must be a {@code  Region}. The second
    * argument is the {@code  RegionValueAggregator} that implements the
    * functionality of the (unique) {@code  AggOp} in the expression. Note that
    * the special arguments do not appear in pairs, i.e., they may not be
    * missing.
    *
    * An aggregating expression additionally has an element argument and a
    * number of "scope" argmuents following the special arguments. The type of
    * the element is {@code  tAggIn.elementType}. The number and types of the
    * scope arguments are defined by the symbol table of {@code  tAggIn}. The
    * element argument and the scope arguments, unlike special arguments, appear
    * in pairs of a value and a missingness bit. Moreover, the element argument
    * must appear first.
    *
    **/
  private def emit(ir: IR, env: E): (Code[Unit], Code[Boolean], Code[_]) = {

    def emit(ir: IR, env: E = env): (Code[Unit], Code[Boolean], Code[_]) =
      this.emit(ir, env)

    def emitAgg(ir: IR, env: E = env)(k: (Code[_], Code[Boolean]) => Code[Unit]): Code[Unit] =
      this.emitAgg(ir, aggEnv)(k)

    def emitArrayIterator(ir: IR, env: E = env) = this.emitArrayIterator(ir, env)

    val region = fb.getArg[Region](1).load()
    lazy val aggregator = {
      assert(nSpecialArguments >= 2)
      fb.getArg[RegionValueAggregator](2)
    }

    ir match {
      case I32(x) =>
        present(const(x))
      case I64(x) =>
        present(const(x))
      case F32(x) =>
        present(const(x))
      case F64(x) =>
        present(const(x))
      case True() =>
        present(const(true))
      case False() =>
        present(const(false))

      case Cast(v, typ) =>
        val (dov, mv, vv) = emit(v)
        val cast = Casts.get(v.typ, typ)
        (dov, mv, cast(vv))

      case NA(typ) =>
        (Code._empty, const(true), defaultValue(typ))
      case IsNA(v) =>
        val (dov, mv, _) = emit(v)
        (dov, const(false), mv)
      case MapNA(name, value, body, typ) =>
        val vti = typeToTypeInfo(value.typ)
        val bti = typeToTypeInfo(typ)
        val mx = mb.newBit()
        val x = coerce[Any](fb.newLocal(name)(vti))
        val mout = mb.newBit()
        val out = coerce[Any](fb.newLocal(name)(bti))
        val (dovalue, mvalue, vvalue) = emit(value)
        val bodyenv = env.bind(name -> (vti, mx, x))
        val (dobody, mbody, vbody) = emit(body, env = bodyenv)
        val setup = Code(
          dovalue,
          mx := mvalue,
          mx.mux(
            Code(mout := true, out := defaultValue(typ)),
            Code(x := vvalue, dobody, mout := mbody, out := vbody)))

        (setup, mout, out)

      case If(cond, cnsq, altr, typ) =>
        val (docond, mcond, vcond) = emit(cond)
        val xvcond = mb.newBit()
        val out = coerce[Any](fb.newLocal()(typeToTypeInfo(typ)))
        val mout = mb.newBit()
        val (docnsq, mcnsq, vcnsq) = emit(cnsq)
        val (doaltr, maltr, valtr) = emit(altr)
        val setup = Code(
          docond,
          mcond.mux(
            Code(mout := true, out := defaultValue(typ)),
            Code(
              xvcond := coerce[Boolean](vcond),
              coerce[Boolean](xvcond).mux(
                Code(docnsq, mout := mcnsq, out := vcnsq),
                Code(doaltr, mout := maltr, out := valtr)))))

        (setup, mout, out)

      case Let(name, value, body, typ) =>
        val vti = typeToTypeInfo(value.typ)
        val mx = mb.newBit()
        val x = coerce[Any](fb.newLocal(name)(vti))
        val (dovalue, mvalue, vvalue) = emit(value)
        val bodyenv = env.bind(name -> (vti, mx, x))
        val (dobody, mbody, vbody) = emit(body, env = bodyenv)
        val setup = Code(
          dovalue,
          mx := mvalue,
          x := vvalue,
          dobody)

        (setup, mbody, vbody)
      case Ref(name, typ) =>
        val ti = typeToTypeInfo(typ)
        val (t, m, v) = env.lookup(name)
        assert(t == ti, s"$name type annotation, $typ, $t doesn't match typeinfo: $ti")
        (Code._empty, m, v)

      case ApplyBinaryPrimOp(op, l, r, typ) =>
        val (dol, ml, vl) = emit(l)
        val (dor, mr, vr) = emit(r)
        (Code(dol, dor),
          ml || mr,
          BinaryOp.emit(op, l.typ, r.typ, vl, vr))
      case ApplyUnaryPrimOp(op, x, typ) =>
        val (dox, mx, vx) = emit(x)
        (dox, mx, UnaryOp.emit(op, x.typ, vx))

      case MakeArray(args, typ) =>
        val srvb = new StagedRegionValueBuilder(fb, typ)
        val addElement = srvb.addIRIntermediate(typ.elementType)
        val mvargs = args.map(emit(_))
        present(Code(
          srvb.start(args.length, init = true),
          Code(mvargs.map { case (dov, m, v) =>
            Code(dov, m.mux(srvb.setMissing(), addElement(v)), srvb.advance())
          }: _*),
          srvb.offset))
      case x@MakeArrayN(len, elementType) =>
        val srvb = new StagedRegionValueBuilder(fb, x.typ)
        val (dolen, mlen, vlen) = emit(len)
        (dolen,
          mlen,
          Code(srvb.start(coerce[Int](vlen), init = true),
            srvb.offset))
      case ArrayRef(a, i, typ) =>
        val ti = typeToTypeInfo(typ)
        val tarray = coerce[TArray](a.typ)
        val ati = coerce[Long](typeToTypeInfo(tarray))
        val (doa, ma, va) = emit(a)
        val (doi, mi, vi) = emit(i)
        val xma = mb.newBit()
        val xa = fb.newLocal()(ati)
        val xi = fb.newLocal[Int]
        val len = fb.newLocal[Int]
        val xmi = mb.newBit()
        val xmv = mb.newBit()
        val setup = Code(
          doa,
          xma := ma,
          xa := coerce[Long](xma.mux(defaultValue(tarray), va)),
          doi,
          xmi := mi,
          xi := coerce[Int](xmi.mux(defaultValue(TInt32()), vi)),
          xmv := xma || xmi || !tarray.isElementDefined(region, xa, xi))

        (setup, xmv, Code(
          len := tarray.loadLength(region, xa),
          (xi < len).mux(
            region.loadIRIntermediate(typ)(tarray.elementOffset(xa, len, xi)),
            Code._fatal(
              const("array index out of bounds: ")
                .invoke[String, String]("concat", xi.load().toS)
                .invoke[String, String]("concat", " / ")
                .invoke[String, String]("concat", len.load().toS)
            ))))
      case ArrayMissingnessRef(a, i) =>
        val tarray = coerce[TArray](a.typ)
        val ati = coerce[Long](typeToTypeInfo(tarray))
        val (doa, ma, va) = emit(a)
        val (doi, mi, vi) = emit(i)
        present(Code(
          doa,
          doi,
          ma || mi || !tarray.isElementDefined(region, coerce[Long](va), coerce[Int](vi))))
      case ArrayLen(a) =>
        val (doa, ma, va) = emit(a)
        (doa, ma, TContainer.loadLength(region, coerce[Long](va)))

      case _: ArrayMap | _: ArrayFilter | _: ArrayRange | _: ArrayFlatMap =>

        val elt = coerce[TArray](ir.typ).elementType
        val srvb = new StagedRegionValueBuilder(fb, ir.typ)

        val aout = emitArrayIterator(ir)
        aout.length match {
          case Some(len) =>
            val cont = { (m: Code[Boolean], v: Code[_]) =>
              coerce[Unit](
                Code(
                  m.mux(
                    srvb.setMissing(),
                    srvb.addIRIntermediate(elt)(v)),
                  srvb.advance()))
            }
            val processAElts = aout.arrayEmitter(cont)
            (processAElts.setup, processAElts.m.getOrElse(const(false)), Code(
              aout.calcLength,
              srvb.start(len, init = true),
              processAElts.addElements,
              srvb.offset
            ))

          case None =>
            val len = fb.newLocal[Int]
            val i = fb.newLocal[Int]
            val mab = new StagedArrayBuilder(TBoolean(), fb.apply_method)
            val vab = new StagedArrayBuilder(elt, fb.apply_method)
            fb.emit(mab.create(16))
            fb.emit(vab.create(16))

            val cont = { (m: Code[Boolean], v: Code[_]) =>
              coerce[Unit](Code(mab.add(m), vab.add(v)))
            }

            val processArrayElts = aout.arrayEmitter(cont)
            (processArrayElts.setup, processArrayElts.m.getOrElse(const(false)), Code(
              mab.clear,
              vab.clear,
              aout.calcLength,
              processArrayElts.addElements,
              len := mab.size,
              srvb.start(len, init = true),
              i := 0,
              Code.whileLoop(i < len,
                coerce[Boolean](mab(i)).mux(
                  srvb.setMissing(),
                  srvb.addIRIntermediate(elt)(vab(i))),
                i := i + 1,
                srvb.advance()),
              srvb.offset
            ))
        }

      case ArrayFold(a, zero, name1, name2, body, typ) =>
        val tarray = coerce[TArray](a.typ)
        val tti = typeToTypeInfo(typ)
        val eti = typeToTypeInfo(tarray.elementType)
        val xmv = mb.newBit()
        val xvv = coerce[Any](fb.newLocal(name2)(eti))
        val xmout = mb.newBit()
        val xvout = coerce[Any](fb.newLocal(name1)(tti))
        val i = fb.newLocal[Int]("af_i")
        val len = fb.newLocal[Int]("af_len")
        val bodyenv = env.bind(
          name1 -> (tti, xmout, xvout.load()),
          name2 -> (eti, xmv, xvv.load()))

        val (dozero, mzero, vzero) = emit(zero)
        val (dobody, mbody, vbody) = emit(body, env = bodyenv)

        val aBase = emitArrayIterator(a)

        val cont = { (m: Code[Boolean], v: Code[_]) =>
          Code(
            xmv := m,
            xvv := v,
            dobody,
            xmout := mbody,
            xvout := xmout.mux(defaultValue(typ), vbody))
        }

        val processAElts = aBase.arrayEmitter(cont)
        val ma = processAElts.m.getOrElse(const(false))

        (Code(
          processAElts.setup,
          xmout := true,
          xvout := defaultValue(typ),
          xmout := ma.mux(
            ma,
            Code(
              dozero,
              xmout := mzero,
              xvout := xmout.mux(defaultValue(typ), vzero),
              aBase.calcLength,
              processAElts.addElements,
              xmout)),
          xvout := xmout.mux(defaultValue(typ), xvout)
        ), xmout, xvout)

      case x@ApplyAggOp(a, op, args, _) =>
        val agg = AggOp.get(op, x.inputType, args.map(_.typ))
        present(emitAgg(a)(agg.seqOp(aggregator, _, _)))

      case x@MakeStruct(fields, _) =>
        val initializers = fields.map { case (_, v) => (v.typ, emit(v)) }
        val srvb = new StagedRegionValueBuilder(fb, x.typ)
        present(Code(
          srvb.start(init = true),
          Code(initializers.map { case (t, (dov, mv, vv)) =>
            Code(
              dov,
              mv.mux(srvb.setMissing(), srvb.addIRIntermediate(t)(vv)),
              srvb.advance())
          }: _*),
          srvb.offset))
      case x@InsertFields(old, fields, _) =>
        old.typ match {
          case oldtype: TStruct =>
            val (doold, mold, vold) = emit(old)
            val xo = fb.newLocal[Long]
            val xmo = mb.newBit()
            val updateInit = Map(fields.filter { case (name, _) => oldtype.hasField(name) }
              .map { case (name, v) => name -> (v.typ, emit(v)) }: _*)
            val appendInit = fields.filter { case (name, _) => !oldtype.hasField(name) }
              .map { case (_, v) => (v.typ, emit(v)) }
            val initializers = fields.map { case (_, v) => (v.typ, emit(v)) }
            val srvb = new StagedRegionValueBuilder(fb, x.typ)
            present(Code(
              srvb.start(init = true),
              Code(
                doold,
                xo := coerce[Long](vold),
                xmo := mold,
                Code(oldtype.fields.map { f =>
                  updateInit.get(f.name) match {
                    case Some((t, (dov, mv, vv))) =>
                      Code(
                        dov,
                        mv.mux(srvb.setMissing(), srvb.addIRIntermediate(t)(vv)),
                        srvb.advance())
                    case None =>
                      Code(
                        (xmo || oldtype.isFieldMissing(region, xo, f.index)).mux(
                          srvb.setMissing(),
                          srvb.addIRIntermediate(f.typ)(region.loadIRIntermediate(f.typ)(oldtype.fieldOffset(xo, f.index)))
                        ),
                        srvb.advance())
                  }
                }: _*)),
              Code(appendInit.map { case (t, (dov, mv, vv)) =>
                Code(
                  dov,
                  mv.mux(srvb.setMissing(), srvb.addIRIntermediate(t)(vv)),
                  srvb.advance())
              }: _*),
              srvb.offset))
          case _ =>
            val newIR = MakeStruct(fields)
            Infer(newIR)
            emit(newIR)
        }

      case GetField(o, name, _) =>
        val t = coerce[TStruct](o.typ)
        val fieldIdx = t.fieldIdx(name)
        val (doo, mo, vo) = emit(o)
        val xmo = mb.newBit()
        val xo = fb.newLocal[Long]
        val setup = Code(
          doo,
          xmo := mo,
          xo := coerce[Long](xmo.mux(defaultValue(t), vo)))
        (setup,
          xmo || !t.isFieldDefined(region, xo, fieldIdx),
          region.loadIRIntermediate(t.types(fieldIdx))(t.fieldOffset(xo, fieldIdx)))
      case GetFieldMissingness(o, name) =>
        val t = coerce[TStruct](o.typ)
        val fieldIdx = t.fieldIdx(name)
        val (doo, mo, vo) = emit(o)
        present(Code(doo, mo || !t.isFieldDefined(region, coerce[Long](vo), fieldIdx)))

      case x@MakeTuple(types, _) =>
        val initializers = types.map { v => (v.typ, emit(v)) }
        val srvb = new StagedRegionValueBuilder(fb, x.typ)
        present(Code(
          srvb.start(init = true),
          Code(initializers.map { case (t, (dov, mv, vv)) =>
            Code(
              dov,
              mv.mux(srvb.setMissing(), srvb.addIRIntermediate(t)(vv)),
              srvb.advance())
          }: _*),
          srvb.offset))
      case GetTupleElement(o, idx, _) =>
        val t = coerce[TTuple](o.typ)
        val (doo, mo, vo) = emit(o)
        val xmo = mb.newBit()
        val xo = fb.newLocal[Long]
        val setup = Code(
          doo,
          xmo := mo,
          xo := coerce[Long](xmo.mux(defaultValue(t), vo)))
        (setup,
          xmo || !t.isFieldDefined(region, xo, idx),
          region.loadIRIntermediate(t.types(idx))(t.fieldOffset(xo, idx)))

      case _: AggIn | _: AggMap | _: AggFilter | _: AggFlatMap =>
        throw new RuntimeException(s"Aggregations must appear within an aggregation: $ir")

      case In(i, typ) =>
        (Code._empty,
          fb.getArg[Boolean](normalArgumentPosition(i) + 1),
          fb.getArg(normalArgumentPosition(i))(typeToTypeInfo(typ)))
      case InMissingness(i) =>
        present(fb.getArg[Boolean](i * 2 + 3))
      case Die(m) =>
        present(Code._throw(Code.newInstance[RuntimeException, String](m)))
      case Apply(fn, args, impl) =>
        val meth = methods.getOrElseUpdate(impl, {
          impl.argTypes.foreach(_.clear())
          (impl.argTypes, args.map(a => a.typ)).zipped.foreach(_.unify(_))
          val argTypes = impl.argTypes.map { t => typeToTypeInfo(t.subst()) }
          val methodbuilder = fb.newMethod((typeInfo[Region] +: argTypes).toArray, typeToTypeInfo(impl.returnType))
          methodbuilder.emit(impl.apply(methodbuilder, argTypes.zipWithIndex.map { case (a, i) => methodbuilder.getArg(i + 2)(a).load() }: _*))
          methodbuilder
        })
        val (s, m, v) = args.map(emit(_)).unzip3
        val vars = args.map { a => coerce[Any](fb.newLocal()(typeToTypeInfo(a.typ))) }
        val ins = vars.zip(v).map { case (l, i) => l := i }
        val setup = coerce[Unit](Code(s: _*))
        val missing = if (m.isEmpty) const(false) else m.reduce(_ || _)
        val value = Code(ins :+ meth.invoke(fb.getArg[Region](1).load() +: vars.map { a => a.load() }: _*): _*)
        (setup, missing, value)
    }
  }

  private def emitAgg(ir: IR, env: E)(continuation: (Code[_], Code[Boolean]) => Code[Unit]): Code[Unit] = {
    def emit(ir: IR, env: E = env): (Code[Unit], Code[Boolean], Code[_]) =
      this.emit(ir, env)

    def emitAgg(ir: IR)(continuation: (Code[_], Code[Boolean]) => Code[Unit]): Code[Unit] =
      this.emitAgg(ir, env)(continuation)

    assert(nSpecialArguments >= 2)

    val tAggIn = tAggInOpt.get

    val region = fb.getArg[Region](1).load()
    // aggregator is 2
    val element = fb.getArg(3)(typeToTypeInfo(tAggIn.elementType)).load()
    val melement = fb.getArg[Boolean](4).load()
    ir match {
      case AggIn(typ) =>
        assert(tAggIn == typ)
        continuation(element, melement)
      case AggMap(a, name, body, typ) =>
        val tA = coerce[TAggregable](a.typ)
        val tElement = tA.elementType
        val elementTi = typeToTypeInfo(tElement)
        val x = coerce[Any](fb.newLocal()(elementTi))
        val mx = mb.newBit
        val (dobody, mbody, vbody) = emit(body, env.bind(name, (elementTi, mx.load(), x.load())))
        emitAgg(a) { (v, mv) =>
          Code(
            mx := mv,
            x := mx.mux(defaultValue(tElement), v),
            dobody,
            continuation(vbody, mbody))
        }
      case AggFilter(a, name, body, typ) =>
        val tElement = coerce[TAggregable](a.typ).elementType
        val elementTi = typeToTypeInfo(tElement)
        val x = coerce[Any](fb.newLocal()(elementTi))
        val mx = mb.newBit
        val (dobody, mbody, vbody) = emit(body, env.bind(name, (elementTi, mx.load(), x.load())))
        emitAgg(a) { (v, mv) =>
          Code(
            mx := mv,
            x := mx.mux(defaultValue(tElement), v),
            dobody,
            // missing is false
            (!mbody && coerce[Boolean](vbody)).mux(continuation(x, mx), Code._empty))
        }
      case AggFlatMap(a, name, body, typ) =>
        val tA = coerce[TAggregable](a.typ)
        val tElement = tA.elementType
        val elementTi = typeToTypeInfo(tElement)
        val tArray = coerce[TArray](body.typ)
        val x = coerce[Any](fb.newLocal()(elementTi))
        val arr = fb.newLocal[Long]
        val len = fb.newLocal[Int]
        val i = fb.newLocal[Int]
        val mx = mb.newBit
        val (dobody, mbody, vbody) = emit(body, env.bind(name, (elementTi, mx.load(), x.load())))
        emitAgg(a) { (v, mv) =>
          Code(
            mx := mv,
            x := mx.mux(defaultValue(tElement), v),
            dobody,
            mbody.mux(
              Code._empty,
              Code(
                arr := coerce[Long](vbody),
                i := 0,
                len := tArray.loadLength(region, arr),
                Code.whileLoop(i < len,
                  continuation(
                    region.loadIRIntermediate(tArray.elementType)(tArray.loadElement(region, arr, i)),
                    tArray.isElementMissing(region, arr, i)),
                  i ++))))
        }
      case _: ApplyAggOp =>
        throw new RuntimeException(s"No nested aggregations allowed: $ir")
      case In(_, _) | InMissingness(_) =>
        throw new RuntimeException(s"No inputs may be referenced inside an aggregator: $ir")
      case _ =>
        throw new RuntimeException(s"Expected an aggregator, but found: $ir")
    }
  }

  private def emitArrayIterator(ir: IR, env: E): ArrayIteratorTriplet = {

    def emit(ir: IR, env: E = env) = this.emit(ir, env)

    def emitArrayIterator(ir: IR, env: E = env) = this.emitArrayIterator(ir, env)

    val region = fb.getArg[Region](1).load()

    ir match {
      case x@ArrayRange(startir, stopir, stepir) =>
        val (d, m, v) = Array(emit(startir), emit(stopir), emit(stepir)).unzip3
        val start = fb.newLocal[Int]("ar_start")
        val stop = fb.newLocal[Int]("ar_stop")
        val step = fb.newLocal[Int]("ar_step")

        val i = fb.newLocal[Int]("ar_i")
        val len = fb.newLocal[Int]("ar_len")
        val llen = fb.newLocal[Long]("ar_llen")

        val calcLength = Code(
          start := coerce[Int](v(0)),
          stop := coerce[Int](v(1)),
          step := coerce[Int](v(2)),
          step.ceq(0).mux(
            Code._fatal("Array range cannot have step size 0."),
            Code._empty[Unit]),
          llen := start.ceq(stop).mux(0L,
            (step < 0).mux(
              (start.toL - stop.toL - 1L) / (-step).toL + 1L,
              (stop.toL - start.toL - 1L) / step.toL + 1L)),
          (llen > const(Int.MaxValue.toLong)).mux(
            Code._fatal("Array range cannot have more than MAXINT elements."),
            len := (llen < 0L).mux(0L, llen).toI)
        )

        ArrayIteratorTriplet(calcLength, Some(len.load()), { continuation: F =>
          EmitArrayTriplet(
            coerce[Unit](Code(d: _*)),
            Some(m.reduce(_ || _)),
            Code(
              i := 0,
              Code.whileLoop(i < len,
                continuation(false, start),
                i := i + 1,
                start := start + step)))
        })
      case x@ArrayFilter(a, name, condition) =>
        val elementTypeInfoA = coerce[Any](typeToTypeInfo(x.typ.elementType))
        val xmv = mb.newBit()
        val xvv = fb.newLocal(name)(elementTypeInfoA)
        val condenv = env.bind(name -> (elementTypeInfoA, xmv, xvv))
        val (docond, mcond, vcond) = emit(condition, condenv)

        val filterCont = { (cont: F, m: Code[Boolean], v: Code[_]) =>
          Code(
            xmv := m,
            xmv.mux(
              Code._empty,
              Code(
                xvv := v,
                docond,
                (mcond || !coerce[Boolean](vcond)).mux(
                  Code._empty,
                  cont(false, xvv)))))
        }
        emitArrayIterator(a).copy(length = None).wrapContinuation(filterCont)

      case x@ArrayFlatMap(a, name, body) =>
        val elementTypeInfoA = coerce[Any](typeToTypeInfo(coerce[TArray](a.typ).elementType))
        val xmv = mb.newBit()
        val xvv = fb.newLocal(name)(elementTypeInfoA)
        val bodyenv = env.bind(name -> (elementTypeInfoA, xmv, xvv))
        val bodyIt = emitArrayIterator(body, bodyenv)

        val bodyCont = { (cont: F, m: Code[Boolean], v: Code[_]) =>
          val bodyArray = bodyIt.arrayEmitter(cont)
          Code(
            xmv := m,
            xmv.mux(
              Code._empty,
              Code(
                xvv := v,
                bodyArray.setup,
                bodyArray.m.map(_.mux(
                  Code._empty,
                  Code(bodyIt.calcLength, bodyArray.addElements))).getOrElse(Code(bodyIt.calcLength, bodyArray.addElements)))))
        }

        emitArrayIterator(a).copy(length = None).wrapContinuation(bodyCont)

      case x@ArrayMap(a, name, body, _) =>
        val elt = coerce[TArray](a.typ).elementType
        val elementTypeInfoA = coerce[Any](typeToTypeInfo(elt))
        val xmv = mb.newBit()
        val xvv = fb.newLocal(name)(elementTypeInfoA)
        val bodyenv = env.bind(name -> (elementTypeInfoA, xmv, xvv))
        val (dobody, mbody, vbody) = emit(body, bodyenv)
        val mapCont = { (continuation: F, m: Code[Boolean], v: Code[_]) =>
          Code(
            xmv := m,
            xmv.mux(
              xvv := defaultValue(elt),
              xvv := v),
            dobody,
            continuation(mbody, vbody))
        }
        emitArrayIterator(a).wrapContinuation(mapCont)

      case MakeArray(args, _) =>
        val f = { cont: F =>
          EmitArrayTriplet(Code._empty[Unit], None, coerce[Unit](Code(
            for (elt <- args) yield {
              val (doe, me, ve) = emit(elt)
              Code(doe, cont(me, ve))
            })))
        }
        ArrayIteratorTriplet(Code._empty, Some(const(args.length)), f)

      case If(cond, cnsq, altr, typ) =>
        val (docond, mcond, vcond) = emit(cond)
        val xmcond = mb.newBit()
        val xvcond = mb.newBit()
        val mout = mb.newBit()
        val aCnsq = emitArrayIterator(cnsq)
        val aAltr = emitArrayIterator(altr)

        val f = { cont: F =>
          val addCnsq = aCnsq.arrayEmitter(cont)
          val addAltr = aAltr.arrayEmitter(cont)
          val setup = Code(
            docond,
            xmcond := mcond,
            xmcond.mux(
              Code(
                mout := true,
                xvcond := coerce[Boolean](defaultValue(cond.typ))
              ),
              Code(
                mout := false,
                xvcond := coerce[Boolean](vcond),
                addCnsq.setup, addAltr.setup)))
          val missing = if (addCnsq.m.isEmpty && addAltr.m.isEmpty)
            mout
          else
            Code(
              xmcond.mux(Code._empty,
                mout := xvcond.mux(addCnsq.m.getOrElse(false), addAltr.m.getOrElse(false))),
              mout)
          val add = xvcond.mux(addCnsq.addElements, addAltr.addElements)
          EmitArrayTriplet(setup, Some(missing), add)
        }

        val lenCalc = xvcond.mux(aCnsq.calcLength, aAltr.calcLength)
        val optLen = aCnsq.length.flatMap(l1 => aAltr.length.map(xvcond.mux(l1, _)))
        ArrayIteratorTriplet(lenCalc, optLen, f)

      case _ =>
        val t: TArray = coerce[TArray](ir.typ)
        val srvb = new StagedRegionValueBuilder(fb, t)
        val i = fb.newLocal[Int]("i")
        val len = fb.newLocal[Int]("len")
        val aoff = fb.newLocal[Long]("aoff")
        val (setup, m, v) = emit(ir, env)
        val calcLength = Code(
          aoff := coerce[Long](v),
          len := t.loadLength(region, aoff))
        ArrayIteratorTriplet(calcLength, Some(len.load()), { continuation: F =>
          EmitArrayTriplet(setup, Some(m), Code(
            srvb.start(len, init = true),
            i := 0,
            Code.whileLoop(i < len,
              continuation(t.isElementMissing(region, aoff, i),
                region.loadIRIntermediate(t.elementType)(t.loadElement(region, aoff, i))),
              i := i + 1)))
        })
    }
  }

  private def present(x: Code[_]): (Code[Unit], Code[Boolean], Code[_]) =
    (Code._empty, const(false), x)

  private lazy val aggEnv: E = {
    val scopeOffset = nSpecialArguments + 2 // element and element missingness
    Env.empty.bind(tAggInOpt.get.bindings.zipWithIndex
      .map {
        case ((n, t), i) => n -> ((
          typeToTypeInfo(t),
          fb.getArg[Boolean](scopeOffset + i * 2 + 2).load(),
          fb.getArg(scopeOffset + i * 2 + 1)(typeToTypeInfo(t)).load()))
      }: _*)
  }

  private def normalArgumentPosition(idx: Int): Int = {
    val aggArgs = tAggInOpt match {
      case Some(t) => (t.symTab.size + 1) * 2 // one extra for the element itself
      case None => 0
    }
    nSpecialArguments + aggArgs + 1 + idx * 2
  }
}