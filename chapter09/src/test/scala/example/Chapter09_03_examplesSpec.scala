package example

import org.scalatest.{FlatSpec, Matchers}
import cats.{Applicative, Bifunctor, Bitraverse, Eval, Functor, Monoid, Traverse}
import org.scalatest.{FlatSpec, Matchers}
import cats.syntax.functor._
import cats.instances._
import cats.syntax.bifunctor._
import cats.syntax.traverse._
import cats.syntax.monoid._
import cats.syntax.bitraverse._
import WuZip.WuZipSyntax
import Trav._
import cats.data.State
import io.chymyst.ch._

class Chapter09_03_examplesSpec extends FlatSpec with Matchers {

  behavior of "examples of using traversals"

  it should "convert a traversable functor to List" in {
    def toList[L[_] : Trav : Functor, C](lc: L[C]): List[C] = {
      // Define List[A] as a monoid type and a constant functor.
      type Z[B] = List[C]
      implicit val functorZ: Functor[Z] = new Functor[Z] {
        override def map[A, B](fa: Z[A])(f: A => B): Z[B] = fa
      }
      implicit val wuZipZ: WuZip[Z] = new WuZip[Z]() {
        override def wu: Z[Unit] = Nil

        override def zip[A, B](fa: Z[A], fb: Z[B]): Z[(A, B)] = fb ++ fa // Note opposite order!
      }
      lc.trav[Z, C](c ⇒ List(c))
    }

    // Check that this works.
    type L[A] = (A, A, A)
    implicit val functorL: Functor[L] = new Functor[L] {
      override def map[A, B](fa: L[A])(f: A ⇒ B): L[B] =
        (f(fa._1), f(fa._2), f(fa._3))
    }
    implicit val travL: Trav[L] = new Trav[L] {
      override def seq[F[_] : WuZip : Functor, A](lfa: L[F[A]]): F[L[A]] =
        lfa._1 zip lfa._2 zip lfa._3 map { case ((x, y), z) ⇒ (x, y, z) }
    }

    val l1: L[Int] = (1, 2, 3)
    toList(l1) shouldEqual List(3, 2, 1)
  }

  sealed trait Tree[A]

  final case class Leaf[A](x: A) extends Tree[A]

  final case class Branch[A](left: Tree[A], right: Tree[A]) extends Tree[A]

  implicit val functorTree: Functor[Tree] = new Functor[Tree] {
    override def map[A, B](fa: Tree[A])(f: A ⇒ B): Tree[B] = fa match {
      case Leaf(x) ⇒ Leaf(f(x))
      case Branch(left, right) ⇒ Branch(map(left)(f), map(right)(f))
    }
  }

  // Depth-first traversal.
  implicit val travTree: Trav[Tree] = new Trav[Tree] {
    override def seq[F[_] : WuZip : Functor, A](t: Tree[F[A]]): F[Tree[A]] = t match {
      case Leaf(fa) ⇒ fa.map(Leaf.apply)
      case Branch(left, right) ⇒
        seq[F, A](left) zip seq[F, A](right) map { case (x, y) ⇒ Branch(x, y) }
    }
  }

  val t1: Tree[Int] = Branch(Branch(Leaf(1), Leaf(2)), Leaf(3))
  val t2: Tree[String] = Branch(Branch(Leaf("a"), Branch(Leaf("b"), Leaf("c"))), Leaf("d"))
  /*             .
               /  \
              .   d
     t2 =   /  \
           a   .
             /  \ 
            b   c
   */

  it should "fold a tree to aggregate data" in {
    // Product of squares of all integers in the tree.
    implicit val monoidInt: Monoid[Int] = new Monoid[Int] {
      override def empty: Int = 1

      override def combine(x: Int, y: Int): Int = x * y
    }

    t1.foldMap(x ⇒ x * x) shouldEqual 36
  }

  // Visualize the traversal of t2: f(a) zip f(b) zip f(c) zip f(d), ⇒ F[Tree[B]]

  // Use a state monad as the applicative effect.
  type S[X] = State[Int, X]
  val makeLabel: S[Int] = for {
    s ← State.get
    _ ← State.set(s + 1)
  } yield s

  def wuzipFromState[St]: WuZip[State[St, ?]] = new WuZip[State[St, ?]] {
    override def wu: State[St, Unit] = State.pure(())

    override def zip[A, B](fa: State[St, A], fb: State[St, B]): State[St, (A, B)] = for {
      x ← fa
      y ← fb
    } yield (x, y)
  }

  implicit val wuzipState: WuZip[S] = wuzipFromState[Int]

  it should "decorate a tree with depth-first traversal order labels" in {
    val result: S[Tree[(String, Int)]] = t2.trav[S, (String, Int)](leaf ⇒ makeLabel.map((leaf, _)))
    // Run the State monad.
    result.run(1).value._2 shouldEqual Branch(Branch(Leaf(("a", 1)), Branch(Leaf(("b", 2)), Leaf(("c", 3)))), Leaf(("d", 4)))
  }

  it should "implement scanMap and scanLeft for traversable" in {
    // A method analogous to foldMap.
    def scanMap[L[_] : Trav : Functor, Z: Monoid, A](la: L[A])(f: A ⇒ Z): L[Z] = {
      // Use the State monad with the monoid Z as the state.
      type S[X] = State[Z, X]
      implicit val wuzipS: WuZip[S] = wuzipFromState[Z]

      def accumulate(z: Z): S[Z] = for {
        s ← State.get
        _ ← State.set(s |+| z)
        newS ← State.get
      } yield newS

      la.trav[S, Z](f andThen accumulate)
        .run(Monoid[Z].empty).value._2
    }

    // Use String as a standard monoid.
    import cats.instances.string._
    scanMap[Tree, String, String](t2)(identity) shouldEqual Branch(Branch(Leaf("a"), Branch(Leaf("ab"), Leaf("abc"))), Leaf("abcd"))

    def scanLeft[L[_] : Trav : Functor, A, Z](la: L[A])(init: Z)(f: (A, Z) ⇒ Z): L[Z] = {
      // Use the State monad with the type Z as the state (not necessarily a monoid).
      type S[X] = State[Z, X]
      implicit val wuzipS: WuZip[S] = wuzipFromState[Z]

      def accumulate(a: A): S[Z] = for {
        s ← State.get
        _ ← State.set(f(a, s))
        newS ← State.get
      } yield newS

      la.trav[S, Z](accumulate)
        .run(init).value._2
    }

    scanLeft(t2)(0) { (s, i) ⇒ i + s.length } shouldEqual Branch(Branch(Leaf(1), Branch(Leaf(2), Leaf(3))), Leaf(4))
  }

  it should "implement traversal for a non-monadic rigid tree" in {
    sealed trait BTree[A]

    final case class BLeaf[A](x: A) extends BTree[A]

    final case class BBranch[A](baa: BTree[(A, A)]) extends BTree[A]

    implicit val functorBTree: Functor[BTree] = new Functor[BTree] {
      override def map[A, B](fa: BTree[A])(f: A ⇒ B): BTree[B] = fa match {
        case BLeaf(x) ⇒ BLeaf(f(x))
        case BBranch(baa) ⇒ BBranch(map[(A, A), (B, B)](baa) { case (x, y) ⇒ (f(x), f(y)) })
      }
    }

    // Depth-first traversal.
    implicit val travBTree: Trav[BTree] = new Trav[BTree] {
      override def seq[F[_] : WuZip : Functor, A](t: BTree[F[A]]): F[BTree[A]] = t match {
        case BLeaf(fa) ⇒ fa.map(BLeaf.apply)
        case BBranch(bfafa) ⇒ // Have B[(F[A], F[A])], but need F[B[(A, A)]]. Use zip for F.
          seq[F, (A, A)](bfafa.map { case (fa1, fa2) ⇒ fa1 zip fa2 })
            .map(BBranch.apply)
      }
    }

    val t3: BTree[Int] = BBranch(BBranch(BLeaf(((1, 2), (3, 4)))))

    import cats.instances.int._
    t3.foldMap(identity) shouldEqual 10
  }
}
