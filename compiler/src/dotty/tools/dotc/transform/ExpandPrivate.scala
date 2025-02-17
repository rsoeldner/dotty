package dotty.tools.dotc
package transform

import core._
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.DenotTransformers.{IdentityDenotTransformer, SymTransformer}
import Contexts.Context
import Symbols._
import Scopes._
import Flags._
import StdNames._
import SymDenotations._
import Types._

import collection.mutable
import TreeTransforms._
import Decorators._
import ast.Trees._
import TreeTransforms._
import java.io.File.separatorChar

import ValueClasses._
import dotty.tools.dotc.core.Phases.Phase

/** Make private term members that are accessed from another class
 *  non-private by resetting the Private flag and expanding their name.
 *
 *  Make private accessor in value class not-private. Ihis is necessary to unbox
 *  the value class when accessing it from separate compilation units
 *
 *  Also, make non-private any private parameter forwarders that forward to an inherited
 *  public or protected parameter accessor with the same name as the forwarder.
 *  This is necessary since private methods are not allowed to have the same name
 *  as inherited public ones.
 *
 *  See discussion in https://github.com/lampepfl/dotty/pull/784
 *  and https://github.com/lampepfl/dotty/issues/783
 */
class ExpandPrivate extends MiniPhaseTransform with IdentityDenotTransformer { thisTransform =>
  import ast.tpd._

  override def phaseName: String = "expandPrivate"

  // This phase moves methods around (in infotransform) so it may need to make other methods public
  override def runsAfter: Set[Class[_ <: Phase]] = Set(classOf[MoveStatics])

  override def changesMembers = true // the phase introduces new members with mangled names

  override def checkPostCondition(tree: Tree)(implicit ctx: Context): Unit = {
    tree match {
      case t: DefDef =>
        val sym = t.symbol
        def hasWeakerAccess(other: Symbol) = {
          // public > protected > /* default */ > private
          if (sym.is(Private)) other.is(Private)
          else if (sym.is(Protected)) other.is(Protected | Private)
          else true // sym is public
        }
        val fail = sym.allOverriddenSymbols.findSymbol(x => !hasWeakerAccess(x))
        if (fail.exists) {
          assert(false, i"${sym.showFullName}: ${sym.info} has weaker access than superclass method ${fail.showFullName}: ${fail.info}")
        }
      case _ =>
    }
  }

  private def isVCPrivateParamAccessor(d: SymDenotation)(implicit ctx: Context) =
    d.isTerm && d.is(PrivateParamAccessor) && isDerivedValueClass(d.owner)

  /** Make private terms accessed from different classes non-private.
   *  Note: this happens also for accesses between class and linked module class.
   *  If we change the scheme at one point to make static module class computations
   *  static members of the companion class, we should tighten the condition below.
   */
  private def ensurePrivateAccessible(d: SymDenotation)(implicit ctx: Context) =
    if (isVCPrivateParamAccessor(d))
      d.ensureNotPrivate.installAfter(thisTransform)
    else if (d.is(PrivateTerm) && !d.owner.is(Package) && d.owner != ctx.owner.enclosingClass) {
      // Paths `p1` and `p2` are similar if they have a common suffix that follows
      // possibly different directory paths. That is, their common suffix extends
      // in both cases either to the start of the path or to a file separator character.
      def isSimilar(p1: String, p2: String): Boolean = {
        var i = p1.length - 1
        var j = p2.length - 1
        while (i >= 0 && j >= 0 && p1(i) == p2(j) && p1(i) != separatorChar) {
          i -= 1
          j -= 1
        }
        (i < 0 || p1(i) == separatorChar) &&
        (j < 0 || p1(j) == separatorChar)
      }

      assert(d.symbol.sourceFile != null &&
             isSimilar(d.symbol.sourceFile.path, ctx.source.file.path),
          s"private ${d.symbol.showLocated} in ${d.symbol.sourceFile} accessed from ${ctx.owner.showLocated} in ${ctx.source.file}")
      d.ensureNotPrivate.installAfter(thisTransform)
    }

  override def transformIdent(tree: Ident)(implicit ctx: Context, info: TransformerInfo) = {
    ensurePrivateAccessible(tree.symbol)
    tree
  }

  override def transformSelect(tree: Select)(implicit ctx: Context, info: TransformerInfo) = {
    ensurePrivateAccessible(tree.symbol)
    tree
  }

  override def transformDefDef(tree: DefDef)(implicit ctx: Context, info: TransformerInfo) = {
    val sym = tree.symbol
    tree.rhs match {
      case Apply(sel @ Select(_: Super, _), _)
      if sym.is(PrivateParamAccessor) && sel.symbol.is(ParamAccessor) && sym.name == sel.symbol.name =>
        sym.ensureNotPrivate.installAfter(thisTransform)
      case _ =>
        if (isVCPrivateParamAccessor(sym))
          sym.ensureNotPrivate.installAfter(thisTransform)
    }
    tree
  }
}
