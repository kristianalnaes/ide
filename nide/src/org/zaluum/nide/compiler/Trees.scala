package org.zaluum.nide.compiler

import scala.collection.immutable.Stack
import scala.collection.mutable.Buffer
import java.io.StringWriter

trait SelectionSubject
abstract class Tree extends Product with SelectionSubject {
  //var pos : Position = NoPosition
  var tpe: Type = NoSymbol
  var symbol: Symbol = NoSymbol
  var line : Int = 0
  def hasSymbol = false
  def isDef = false
  def isEmpty = false
  private def findPath0(l:Int) : (Option[Tree],Int) = {
    if (l<=0) (None,0)
    else if (l==1) (Some(this),1)
    else {
      var visited = 1
      for (c<-children) {
        val (t, visits) = c.findPath0(l-visited)
        visited += visits
        if (t.isDefined) {
          return (t,visited)
        }
      }
      (None,visited)
    }
  }
  def findPath(l: Int): Option[Tree] = findPath0(l)._1
  def assignLine(l:Int) : Int = {
    this.line = l
    var x = l+1
    for (c<-children) {
      x = c.assignLine(x)
    }
    x
  }
  def children: List[Tree] = {
    def subtrees(x: Any): List[Tree] = x match {
      case t: Tree ⇒ List(t)
      case o : Option[_] => o.toList flatMap subtrees
      case xs: List[_] ⇒ xs flatMap subtrees
      case _ ⇒ List()
    }
    productIterator.toList flatMap subtrees
  }
  private[zaluum] def copyAttrs(tree: Tree): this.type = {
    //pos = tree.pos
    tpe = tree.tpe
    symbol = tree.symbol
    this
  }

  override def hashCode(): Int = super.hashCode()

  override def equals(that: Any): Boolean = that match {
    case t: Tree ⇒ this eq t
    case _ ⇒ false
  }
  def print(depth : Int) : String = {
    def print(a:Any) : String =  {
      a match {
        case t:Tree => t.print(depth+1)
        case l:List[_] => l.map {print(_)}.mkString("\n")
        case _ => (" "*(depth+1)) + a.toString
      }
    }
    val prods = productIterator.toList
    (" "*depth) + this.productPrefix + "(" +
    (if (prods.isEmpty) ")"
    else {
      "\n" + (for (e<-prods) yield {
        print(e)
      }).mkString("\n") + 
      "\n"+(" "*depth)+")"
    })
  }
}

trait SymTree extends Tree {
  override def hasSymbol = true
  symbol = NoSymbol
}
/*
    case EmptyTree ⇒
    case BoxDef(name, defs, vals, ports, connections) ⇒
    case PortDef(name, typeName, in, inPos, extPos) ⇒
    case ValDef(name, typeName,pos,guiSize) ⇒
    case ConnectionDef(a, b) ⇒
    case PortRef(name, from) =>
    case BoxRef(name) =>
   */
// Transformer
abstract class EditTransformer extends CopyTransformer with MapTransformer

trait MapTransformer extends Transformer {
  var map = Map[SelectionSubject, SelectionSubject]()
  abstract override protected def transform[A<:Tree](tree: A): A = {
    val transformed = super.transform(tree)
    map += (tree -> transformed)
    transformed
  }
}
trait CopySymbolTransformer extends Transformer {
  abstract override protected def transform[A<:Tree](tree: A): A =
    super.transform(tree).copyAttrs(tree)
}
trait CopyTransformer extends Transformer {
  val defaultTransform: PartialFunction[Tree, Tree] = {
    case b:BoxDef⇒
      atOwner(b.symbol) {
      b.copy(template = transform(b.template))
      }
    case t:Template=> 
      t.copy (blocks = transformTrees(t.blocks),
          ports = transformTrees(t.ports))
    case b:Block => 
      b.copy (
          junctions = transformTrees(b.junctions),
          connections = transformTrees(b.connections),
          parameters = transformTrees(b.parameters),
          valDefs = transformTrees(b.valDefs)
          )
    case PortDef(name, typeName, dir, inPos, extPos) ⇒
      PortDef(name, typeName, dir, inPos, extPos)
    case v:ValDef ⇒
      atOwner(v.symbol) {
        v.copy(params = transformTrees(v.params))
      }
    case p: Param ⇒ p.copy()
    case c@ConnectionDef(a, b, wp) ⇒
      atOwner(c.symbol) {
        ConnectionDef(transformOption(a), transformOption(b), wp)
      }
    case PortRef(from, name, in) ⇒
      PortRef(transform(from), name, in)
    case ValRef(name) ⇒ ValRef(name)
    case j: JunctionRef ⇒ j.copy()
    case j: Junction ⇒ j.copy()
    case t: ThisRef ⇒ ThisRef()
  }
}
abstract class Transformer extends OwnerHelper[Tree] {
  protected val defaultTransform: PartialFunction[Tree, Tree]
  protected val trans: PartialFunction[Tree, Tree]
  protected lazy val finalTrans = trans.orElse(defaultTransform)
  def apply(tree: Tree, initOwner: Symbol = NoSymbol): Tree = {
    currentOwner = initOwner
    transform(tree)
  }
  protected def transform[A<:Tree](tree: A): A = finalTrans.apply(tree).asInstanceOf[A]
  protected def transformOption[A<:Tree](tree: Option[A]) : Option[A] = 
    tree map (transform(_))
  protected def transformTrees[A<:Tree](trees: List[A]): List[A] =
    trees mapConserve (transform(_))
}
// Traverser
abstract class Traverser(initSymbol: Symbol) extends OwnerHelper[Unit] {
  currentOwner = initSymbol
  def traverse(tree: Tree): Unit = {
    tree match {
      case b:BoxDef⇒
        atOwner(tree.symbol) {
          traverse(b.template)
        }
      case t:Template => 
        atOwner(tree.symbol) {
          traverseTrees(t.ports)
          traverseTrees(t.blocks)
        }
      case b:Block =>
        atOwner(tree.symbol) {
	      	traverseTrees(b.valDefs)
	        traverseTrees(b.junctions)
	        traverseTrees(b.connections)
	        traverseTrees(b.parameters)
        }
      case v: ValDef ⇒
        atOwner(tree.symbol) {
          traverseTrees(v.params)
        }
      case p: Param ⇒
      case ConnectionDef(a, b, waypoints) ⇒
        traverseOption(a)
        traverseOption(b)
      case p: PortDef ⇒
      case PortRef(tree, _, _) ⇒
        traverse(tree)
      case j: Junction ⇒
      case j: JunctionRef ⇒
      case ValRef(_) ⇒
      case ThisRef() ⇒
    }
  }
  def traverseTrees(trees: List[Tree]) {
    trees foreach traverse
  }
  def traverseOption(o : Option[Tree]) {
    o foreach traverse
  }
}
object PrettyPrinter {
  def print(str: String, deep: Int) {
    println(new String(Array.fill(deep) { ' ' }) + str)
  }
  def print(trees: List[Tree], deep: Int) {
    trees.foreach { print(_, deep) }
  }
  def sym(tree: Tree) = " sym= " + tree.symbol + " tpe= " + tree.tpe
  def print(tree: Tree, deep: Int): Unit = tree match {
    case b:BoxDef ⇒
      print("BoxDef(" + b.pkg + " " + b.name + " extends " + b.superName + ", " + b.image, deep)
      print(b.guiSize.toString, deep + 1)
      print(b.template, deep +1)
      print(")" + sym(b), deep)
    case t:Template =>
      print("Template( ",deep )
      print(t.blocks, deep+1)
      print(t.ports, deep+1)
      print(")", deep)
    case b: Block =>
      print("Block( ", deep)
      print(b.valDefs, deep +1)
      print(b.connections, deep +1)
      print(b.junctions, deep +1)
      print(b.parameters, deep +1)
      print(")", deep)
    case v: ValDef ⇒
      print("ValDef(" + List(v.name, v.pos, v.size, v.typeName, v.guiPos, v.guiSize).mkString(","), deep)
      print("params: " + v.params, deep + 1)
      print("constructors:" + v.constructorParams.mkString(","), deep +1)
      print("constructorTypes:("+ v.constructorTypes.mkString(",") +")" , deep +1)
      print(")" + sym(v), deep)
    case p: Param ⇒
      print(p.toString + sym(p), deep)
    case c@ConnectionDef(a, b, wp) ⇒
      print("ConnectionDef(", deep)
      a foreach { print(_, deep + 1) } 
      b foreach { print(_, deep + 1) }
      for (p ← wp) {
        print(p.toString, deep + 1)
      }
      print(")" + sym(c), deep)
    case p@PortDef(_, _, _, _, _) ⇒
      print(p.toString + sym(p), deep)
    case p@PortRef(tree, a, b) ⇒
      print("PortRef(", deep)
      print(tree, deep + 1)
      print(a + ", " + b, deep + 1)
      print(")" + sym(p), deep)
    case _ ⇒ print(tree.toString + sym(tree), deep)
  }
}
abstract class OwnerHelper[A] {
  protected var currentOwner: Symbol = null

  def atOwner(owner: Symbol)(traverse: ⇒ A): A = {
    val prevOwner = currentOwner
    currentOwner = owner
    val result = traverse
    currentOwner = prevOwner
    result
  }
}
// ----- tree node alternatives --------------------------------------

/** The empty tree */
/*case object EmptyTree extends Tree {
  override def isEmpty = true
}*/

/* Definition */
case class BoxDef(name: Name, // simple name
  pkg:Name, superName: Option[Name],
  guiSize: Option[Dimension],
  image: Option[String],
  template : Template) extends Tree {
  def sym = symbol.asInstanceOf[BoxTypeSymbol]
}
case class Template(blocks : List[Block], ports: List[PortDef]) extends Tree {
  def sym :TemplateSymbol = symbol.asInstanceOf[TemplateSymbol]
}
case class Block(
    junctions : List[Junction],
    connections : List[ConnectionDef],
    parameters : List[Param],
    valDefs : List[ValDef]
    ) extends Tree {
  def sym = symbol.asInstanceOf[BlockSymbol]
}
object PortDir {
  def fromStr(str:String) = str match {
    case In.str => In
    case Out.str => Out
    case Shift.str => Shift
  }
}
sealed abstract class PortDir(val str:String, val desc:String) 
case object In extends PortDir("<in>", "Port In") 
case object Out extends PortDir("<out>", "Port Out")
case object Shift extends PortDir("<shift>", "Port Shift")
case class PortDef(name: Name, typeName: Name, dir: PortDir, inPos: Point, extPos: Point) extends Tree with Positionable {
  def pos = inPos
}
case class ValRef(name: Name) extends Tree
case class ThisRef() extends SymTree
trait ConnectionEnd extends Tree
case class PortRef(fromRef: Tree, name: Name, in: Boolean) extends ConnectionEnd
case class Param(key: Name, value: String) extends Tree
case class LabelDesc(description:String, pos:Vector2)
case class ValDef(
  name: Name,
  typeName: Name,
  pos: Point,
  size: Option[Dimension],
  guiPos: Option[Point],
  guiSize: Option[Dimension],
  params: List[Tree], 
  constructorParams:List[String],
  constructorTypes:List[Name],
  label : Option[LabelDesc],
  labelGui : Option[LabelDesc]
  ) extends Tree with Positionable {
  /*def localTypeDecl = tpe match {
    case NoSymbol ⇒ None
    case b: BoxTypeSymbol ⇒ if (b.isLocal) Some(b.decl.asInstanceOf[BoxDef]) else None
    case _ => None
  }*/
  def sym = symbol.asInstanceOf[ValSymbol]
}
//case class SizeDef(pos: Point, size: Dimension) extends Tree
case class ConnectionDef(a: Option[ConnectionEnd], b: Option[ConnectionEnd], points: List[Point]) extends SymTree {
 def headPoint = points.headOption.getOrElse(Point(0, 0))
 def lastPoint = points.lastOption.getOrElse(Point(0, 0))

}
case class Junction(name: Name, p: Point) extends Tree
case class JunctionRef(name: Name) extends ConnectionEnd
