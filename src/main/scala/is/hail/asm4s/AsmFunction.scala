package is.hail.asm4s

trait AsmFunction0[R] { def apply(): R }
trait AsmFunction1[A,R] { def apply(a: A): R }
trait AsmFunction2[A,B,R] { def apply(a: A, b: B): R }
trait AsmFunction3[A,B,C,R] { def apply(a: A, b: B, c: C): R }
trait AsmFunction4[A,B,C,D,R] { def apply(a: A, b: B, c: C, d: D): R }
trait AsmFunction5[A,B,C,D,E,R] { def apply(a: A, b: B, c: C, d: D, e: E): R }
trait AsmFunction6[A,B,C,D,E,F,R] { def apply(a: A, b: B, c: C, d: D, e: E, f: F): R }
trait AsmFunction7[A,B,C,D,E,F,G,R] { def apply(a: A, b: B, c: C, d: D, e: E, f: F, g: G): R }
trait AsmFunction13[T1,T2,T3,T4,T5,T6,T7,T8,T9,T10,T11,T12,T13,R] {
  def apply(t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10, t11: T11, t12: T12, t13: T13): R
}
