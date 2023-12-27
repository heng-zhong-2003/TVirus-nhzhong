package zombie.tvirus.parser

enum PrimOp:
  case ADD, MINUS, MUL, DIV, EQ, NE, GT, LT, GE, LE

enum PrimType:
  case INT, BOOL

enum Type:
  case Prim(t: PrimType)
  case Func(xs: Seq[Type], r: Type)
  case Var(name: String, var ty: Option[Type])
  case App(f: Type, x: Type)
  case TyCons(name: String)

enum Scheme:
  case Poly(xs: Seq[String], t: Type)
  case Mono(t: Type)

case class TBind(name: String, t: Option[Type])
case class SBind(name: String, s: Option[Scheme])
case class CBind(name: String, args: Seq[Type])

case class TypeDecl(name: String, xs: Seq[String], cons: Seq[CBind])

enum Pat:
  case Wildcard
  case Var(name: String)
  case Cons(name: String, arg: Seq[Pat])

// a simple constructor pattern. no nesting allowed
case class SCons(name: String, narg: Int)

enum Expr:
  case Prim(left: Expr, op: PrimOp, right: Expr)
  case Var(name: String)
  case LitInt(inner: Int)
  case App(f: Expr, xs: Seq[Expr])
  case Abs(xs: Seq[TBind], b: Expr)
  case Let(xs: Seq[(SBind, Expr)], b: Expr)
  case Match(x: Expr, bs: Seq[(Pat, Expr)])
  case Cons(name: String, args: Seq[Expr])
  case DeclValue(t: Type)

case class ValueDecl(x: SBind, b: Expr)

case class Program(decls: Seq[TypeDecl | ValueDecl])
