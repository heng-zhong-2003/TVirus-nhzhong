package zombie.tvirus.parser

enum PrimOp:
    case ADD, MINUS, MUL, DIV, EQ, NE, GT, LT, GE, LE

enum PrimType:
    case INT

enum Type:
    case Prim(t: PrimType)
    case Defined(name: String)
    case Product(x: Type, y: Type)
    case Sum(x: Type, y: Type)
    case Function(x: Type, r: Type)

enum Scheme:
    case Mono(t: Type)
    case Poly(xs: Seq[String], t: Type)

case class TBind(name: String, t: Option[Type])
case class SBind(name: String, s: Option[Scheme])
case class CBind(name: String, args: Option[Type])

case class TypeDecl(name: String, cons: Seq[CBind])

enum Expr:
    case Prim(op: PrimOp)
    case Var(name: String)
    case LitInt(inner: Int)
    case App(f: Expr, x: Expr)
    case Abs(xs: Seq[TBind], b: Expr)
    case Let(xs: Seq[(SBind, Expr)], b: Expr)

case class ValueDecl(x: SBind, b: Expr)

case class Program(decls: Seq[TypeDecl | ValueDecl])