package zombie.tvirus.codegen
import zombie.tvirus.parser.*
import collection.mutable

def reduce_rhs_wildcard(lhs: Seq[Seq[Pat]], rhs: Seq[Expr]) = {
  (lhs.map(_.tail), rhs)
}

def reduce_rhs_var(name: String, lhs: Seq[Seq[Pat]], rhs: Seq[Expr]) = {
  val zipped = lhs
    .zip(rhs)
    .map((pats, e) =>
      pats.head match {
        case Pat.Wildcard => (pats.tail, e)
        case Pat.Var(x) =>
          (pats.tail, Expr.Let(Seq((x, Expr.InlineVar(name))), e))
      }
    )
  (zipped.map(_(0)), zipped.map(_(1)))
}

def reduce_rhs_cons(
    cons_name: String,
    cons_args: Seq[String],
    lhs: Seq[Seq[Pat]],
    rhs: Seq[Expr]
): (Seq[Seq[Pat]], Seq[Expr]) = {
  val zipped = lhs
    .zip(rhs)
    .flatMap((pats, e) =>
      pats.head match
        case Pat.Wildcard =>
          Seq((Seq.fill(cons_args.length)(Pat.Wildcard) ++ pats.tail, e))
        case Pat.Cons(name, xs) => {
          if (name == cons_name) {
            Seq((xs ++ pats.tail, e))
          } else {
            Seq()
          }
        }
        case Pat.Var(x) => {
          val let_e = Expr.Let(
            Seq((x, Expr.Cons(cons_name, cons_args.map(Expr.InlineVar)))),
            e
          )
          Seq((cons_args.map(_ => Pat.Wildcard) ++ pats.tail, let_e))
        }
    )
  (zipped.map(_(0)), zipped.map(_(1)))
}

class UnnestMatchEnv(p: Program) {
  val typename_to_scons = p.tds
    .map(x => (x.name, x.cons.map(y => SCons(y.name, y.args.length))))
    .toMap

  val consname_to_typename =
    p.tds.flatMap(x => x.cons.map(y => (y.name, x.name))).toMap
}

def pat_covered_by(x: Pat, y: Pat): Boolean = {
  y match {
    case Pat.Var(_)   => true
    case Pat.Wildcard => true
    case Pat.Cons(yname, ys) => {
      x match {
        case Pat.Var(_)   => false
        case Pat.Wildcard => false
        case Pat.Cons(xname, xs) => {
          if (yname == xname) {
            assert(ys.length == xs.length)
            xs.zip(ys).forall((xsub, ysub) => pat_covered_by(xsub, ysub))
          } else {
            false
          }
        }
      }
    }
  }

}

// check if x is redundent under y.
// doing this precisely is np complete, so we approximate.
// in particular we check if x is covered by one of the case in y.
// this mean some x might be covered but we wont find out.
def covered_by(x: Seq[Pat], y: Seq[Seq[Pat]]): Boolean = {
  y.exists(y_ =>
    assert(x.length == y_.length)
    x.zip(y_).forall((x, y) => pat_covered_by(x, y))
  )
}

def transform_program_raw(
    matched: Seq[String],
    lhs: Seq[Seq[Pat]],
    rhs: Seq[Expr],
    env: LEnv
): Expr = {
  if (lhs.length == 0) {
    // no more pattern
    Expr.Fail()
  } else if (matched.length == 0) {
    // nothing else to match on
    rhs.head
  } else {
    // all possible head pattern
    val pats = lhs.map(pats => pats(0))
    if (
      pats.forall(_ match {
        case Pat.Wildcard => true
        case _            => false
      })
    ) {
      val (l, r) = reduce_rhs_wildcard(lhs, rhs)
      transform_program(matched.tail, l, r, env)
    } else if (
      pats.forall(_ match {
        case Pat.Wildcard => true
        case Pat.Var(_)   => true
        case _            => false
      })
    ) {
      val (l, r) = reduce_rhs_var(matched.head, lhs, rhs)
      transform_program(matched.tail, l, r, env)
    } else {
      val sconss = env.env.typename_to_scons(
        env.env.consname_to_typename(
          pats
            .flatMap(_ match {
              case Pat.Cons(name, _) => Seq(name)
              case _                 => Seq()
            })
            .head
        )
      )
      Expr.Match(
        Expr.InlineVar(matched.head),
        sconss.flatMap(scons => {
          val names: Seq[String] = Seq.fill(scons.narg)({ freshName() })
          val cons = Pat.Cons(scons.name, names.map(name => Pat.Var(name)))
          val (l, r) = reduce_rhs_cons(scons.name, names, lhs, rhs)
          Seq((cons, transform_program(names ++ matched.tail, l, r, env)))
        })
      )
    }
  }
}

def always_match(pat: Pat): Boolean = {
  pat match
    case Pat.Var(_)     => true
    case Pat.Wildcard   => true
    case Pat.Cons(_, _) => false
}

def transform_program(
    matched: Seq[String],
    _lhs: Seq[Seq[Pat]],
    _rhs: Seq[Expr],
    env: LEnv
): Expr = {
  if (_lhs.forall(pats => pats.length == 0 || always_match(pats(0)))) {
    transform_program_raw(matched, _lhs, _rhs, env)
  } else {
    assert(_lhs.forall(pats => pats.length == matched.length))
    assert(_lhs.length == _rhs.length)
    val simplified = _lhs
      .zip(_rhs)
      .foldLeft(Seq[(Seq[Pat], Expr)]())((l, r) =>
        if (covered_by(r(0), l.map(_(0)))) { l }
        else { l ++ Seq(r) }
      )
      .map((l, r) => (l, Expr.Abs(l.flatMap(pat_vars), r)))
    val lhs = simplified.map(_(0))
    val rhs = simplified.map(_(1))
    assert(lhs.forall(pats => pats.length == matched.length))
    env.mem.get(lhs) match {
      case None => {
        val v = freshName()
        val funcs = rhs.map(_ => freshName())
        val applied = lhs
          .zip(funcs)
          .map((l, r) =>
            Expr.App(Expr.InlineVar(r), l.flatMap(pat_vars).map(Expr.InlineVar))
          )
        val inserted =
          Expr.Abs(
            matched ++ funcs,
            transform_program_raw(matched, lhs, applied, env)
          )
        env.mem.put(lhs, v)
        env.ll = env.ll :+ (v, inserted)
        Expr.App(Expr.Var(v), matched.map(Expr.InlineVar) ++ rhs)
      }
      case Some(name) => {
        Expr.App(Expr.Var(name), matched.map(Expr.InlineVar) ++ rhs)
      }
    }
  }
}

class LEnv(_env: UnnestMatchEnv) {
  val env = _env

  var ll = Seq[(String, Expr)]()

  val mem = mutable.Map[Seq[Seq[Pat]], String]()

  def produce(x: Expr): Expr = {
    Expr.Let(ll, x)
  }
}

def unnest_matching(
    x: String,
    cases: Seq[(Pat, Expr)],
    env: UnnestMatchEnv
): Expr = {
  val lenv = LEnv(env)
  lenv.produce(
    transform_program(
      Seq(x),
      cases.map((p, e) => (Seq(p))),
      cases.map((p, e) => e),
      lenv
    )
  )
}

def pat_vars(p: Pat): Seq[String] = {
  p match {
    case Pat.Var(n)      => Seq(n)
    case Pat.Wildcard    => Seq()
    case Pat.Cons(_, xs) => xs.flatMap(pat_vars)
  }
}

def unnest_match_expr(x: Expr, env: UnnestMatchEnv): Expr = {
  val recurse = x => unnest_match_expr(x, env)
  x match {
    case Expr.Var(v)    => Expr.Var(v)
    case Expr.Abs(x, b) => Expr.Abs(x, recurse(b))
    case Expr.Match(x, cases) => {
      val x_bind = freshName()
      Expr.Let(
        Seq((x_bind, recurse(x))),
        unnest_matching(x_bind, cases, env)
      )
    }
    case Expr.App(f, x) =>
      Expr.App(recurse(f), x.map(recurse))
    case Expr.Cons(name, x)  => Expr.Cons(name, x.map(recurse))
    case Expr.LitInt(x)      => Expr.LitInt(x)
    case Expr.LitBool(inner) => x
    case Expr.If(i, t, e)    => Expr.If(recurse(i), recurse(t), recurse(e))
    case Expr.Prim(l, op, r) => Expr.Prim(recurse(l), op, recurse(r))
    case Expr.Let(xs, body) =>
      Expr.Let(
        xs.map((name, expr) => (name, recurse(expr))),
        recurse(body)
      )
  }
}

def unnest_match(p: Program): Program = {
  val env = UnnestMatchEnv(p)
  val transformed = Program(
    p.tds,
    p.vds.map(vd => ValueDecl(vd.x, unnest_match_expr(vd.b, env)))
  )
  refresh(transformed)
}
