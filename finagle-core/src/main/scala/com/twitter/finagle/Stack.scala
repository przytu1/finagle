package com.twitter.finagle

import scala.collection.mutable

/**
 * Stacks represent stackable elements of type T. It is assumed that
 * T-typed elements can be stacked in some meaningful way; examples
 * are functions (function composition) [[com.twitter.finagle.Filter
 * Filters]] (chaining), and [[com.twitter.finagle.ServiceFactory
 * ServiceFactories]] (through transformers). T-typed values are also
 * meant to compose: the stack itself materializes into a T-typed
 * value.
 *
 * Stacks are persistent, allowing for nondestructive
 * transformations; they are designed to represent 'template' stacks
 * which can be configured in various ways before materializing the
 * stack itself.
 */
private[finagle]
sealed trait Stack[T] {
  import Stack._

  /**
   * Materialize the current stack with the given parameters,
   * producing a `T`-typed value representing the current
   * configuration.
   */
  def make(params: Params): T

  /**
   * Transform one stack to another by applying `fn` on each element;
   * the map traverses on the element produced by `fn`, not the
   * original stack.
   */
  def transform(fn: Stack[T] => Stack[T]): Stack[T] =
    fn(this) match {
      case Node(id, mk, next) => Node(id, mk, next.transform(fn))
      case id@Leaf(_, _) => id
    }

  /**
   * Traverse the stack, invoking `fn` on each element.
   */
  def foreach(fn: Stack[T] => Unit) {
    fn(this)
    this match {
      case Node(_, _, next) => next.foreach(fn)
      case Leaf(_, _) =>
    }
  }

  /**
   * Enumerate each well-formed stack contained within this stack.
   */
  def tails: Iterator[Stack[T]] = {
    val buf = new mutable.ArrayBuffer[Stack[T]]
    foreach { buf += _ }
    buf.toIterator
  }

  /**
   * Produce a new stack representing the concatenation of `this`
   * with `right`. Note that this replaces the terminating element of
   * `this`.
   */
  def ++(right: Stack[T]): Stack[T] = this match {
    case Node(id, mk, left) => Node(id, mk, left++right)
    case Leaf(_, _) => right
  }

  override def toString = {
    val elems = tails map {
      case Node(id, mk, _) => "%s: Node = %s".format(id, mk)
      case Leaf(id, t) => "%s: Leaf = %s".format(id, t)
    }
    elems mkString "\n"
  }
}

object Stack {
  /**
   * Nodes materialize by transforming the underlying stack in
   * some way.
   */
  case class Node[T](id: String, mk: (Params, Stack[T]) => Stack[T], next: Stack[T]) 
    extends Stack[T] {
    def make(params: Params) = mk(params, next).make(params)
  }
  
  object Node {
    /**
     * A constructor for a 'simple' Node.
     */
    def apply[T](id: String, mk: T => T, next: Stack[T]): Node[T] =
      Node(id, (p, stk) => Leaf(id, mk(stk.make(p))), next)
  }

  /**
   * A static stack element; necessarily the last.
   */
  case class Leaf[T](id: String, t: T) extends Stack[T] {
    def make(params: Params) = t
  }

  /**
   * A typeclass representing P-typed elements, eligible as
   * parameters for stack configuration. Note that the typeclass
   * instance itself is used as the key in parameter maps; thus
   * typeclasses should be persistent:
   *
   * {{{
   * case class Multiplier(i: Int)
   * implicit object Multiplier extends Stack.Param[Multiplier] {
   *   val default = Multiplier(123)
   * }
   * }}}
   */
  trait Param[P] {
    def default: P
  }

  /**
   * A parameter map.
   */
  trait Params {
    /**
     * Get the current value of the P-typed parameter.
     */
    def apply[P: Param]: P
    
    /**
     * Produce a new parameter map, overriding any previous
     * `P`-typed value.
     */
    def +[P: Param](p: P): Params
  }

  object Params {
    private case class Prms(map: Map[Param[_], Any]) extends Params {
      def apply[P](implicit param: Param[P]): P =
        map.get(param) match {
          case Some(v) => v.asInstanceOf[P]
          case None => param.default
        }

      def +[P](p: P)(implicit param: Param[P]): Params = 
        copy(map + (param -> p))
    }
    
    /**
     * The empty parameter map.
     */
    val empty: Params = Prms(Map.empty)
  }

  /**
   * A convenient class to construct stackable modules. This variant
   * operates over stack values. Useful for building stack elements:
   *
   * {{{
   * def myNode = new Simple[Int=>Int]("myelem") {
   *   def make(params: Params, next: Int=>Int): (Int=>Int) = {
   *     val Multiplied(m) = params[Multiplier]
   *     i => next(i*m)
   *   }
   * }
   * }}}
   */
  abstract class Simple[T](val id: String) extends Stackable[T] {
    type Params = Stack.Params
    def make(params: Params, next: T): T

    def toStack(next: Stack[T]) = 
      Node(id, (params, next) => Leaf(id, make(params, next.make(params))), next)
  }
  
  /**
   * A convenience class to construct stackable modules. This variant
   * operates over stacks. Useful for building stack elements:
   *
   * {{{
   * def myNode = new Module[Int=>Int]("myelem") {
   *   def make(params: Params, next: Stack[Int=>Int]): Stack[Int=>Int] = {
   *     val Multiplier(m) = params[Multiplier]
   *     if (m == 1) next // It's a no-op, skip it.
   *     else Stack.Leaf("multiply", i => next.make(params)(i)*m)
   *   }
   * }
   * }}}
   */
  abstract class Module[T](val id: String) extends Stackable[T] {
    type Params = Stack.Params
    def make(params: Params, next: Stack[T]): Stack[T]

    def toStack(next: Stack[T]) = 
      Node(id, (params, next) => make(params, next), next)
  }
}

/**
 * Produce a stack from a `T`-typed element.
 */
trait Stackable[T] {
  def toStack(next: Stack[T]): Stack[T]
}

/**
 * A typeclass for "stackable" items. This is used by the
 * [[com.twitter.finagle.StackBuilder StackBuilder]] to provide a
 * convenient interface for constructing Stacks.
 */
@scala.annotation.implicitNotFound("${From} is not Stackable to ${To}")
trait CanStackFrom[-From, To] {
  def toStackable(id: String, el: From): Stackable[To]
}

object CanStackFrom {
  implicit def fromFun[T]: CanStackFrom[T=>T, T] =
    new CanStackFrom[T=>T, T] {
      def toStackable(id: String, fn: T => T): Stackable[T] = new Stack.Simple[T](id) {
        def make(params: Stack.Params, next: T) = fn(next)
      }
    }
}

/**
 * StackBuilders are imperative-style builders for Stacks. It
 * maintains a stack onto which new elements can be pushed (defining
 * a new stack).
 */
class StackBuilder[T](init: Stack[T]) {
  def this(id: String, end: T) = this(Stack.Leaf(id, end))

  private[this] var stack = init
  
  /**
   * Push the stack element `el` onto the stack; el must conform to
   * typeclass [[com.twitter.finagle.CanStackFrom CanStackFrom]].
   */
  def push[U](id: String, el: U)(implicit csf: CanStackFrom[U, T]): this.type = {
    stack = csf.toStackable(id, el).toStack(stack)
    this
  }

  /**
   * Push a [[com.twitter.finagle.Stackable Stackable]] module onto
   * the stack.
   */
  def push(module: Stackable[T]): this.type = {
    stack = module.toStack(stack)
    this
  }

  /**
   * Get the current stack as defined by the builder.
   */
  def result: Stack[T] = stack
  
  /**
   * Materialize the current stack: equivalent to 
   * `result.make()`.
   */
  def make(params: Stack.Params): T = result.make(params)
  
  override def toString = "Builder(%s)".format(stack)
}
