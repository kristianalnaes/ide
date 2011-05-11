package org.zaluum.nide.compiler

import javax.swing.JLabel
import org.zaluum.basic.RunnableBox

case class BoxClass(name: Name, superName: Name, contents: List[Tree]) extends Tree
case class FieldDef(name: Name, typeName: Name) extends Tree
case class New(typeName: Name, param: List[Tree], signature: String) extends Tree
case class ConstructorMethod(boxCreation: List[Tree]) extends Tree
case class Method(name: Name, signature: String, stats: List[Tree]) extends Tree
case class Assign(lhs: Tree, rhs: Tree) extends Tree
case class Select(a: Tree, b: Tree) extends Tree
case object This extends Tree
case object Pop extends Tree
case object NullConst extends Tree
case class FieldRef(id: Name, typeName: Name, fromClass: Name) extends Tree
case class Invoke(obj: Tree, meth: String, param: List[Tree], fromClass: Name, descriptor: String) extends Tree
case class Const(i: Any) extends Tree
case class Return(t: Tree) extends Tree
case object True extends Tree
case object Dup extends Tree
case class ALoad(i:Int) extends Tree
case class AStore(i:Int) extends Tree
class TreeToClass(t: Tree, global: Scope) extends ConnectionHelper with ReporterAdapter {
  val reporter = new Reporter // TODO fail reporter
  def location(t: Tree) = Location(List(0))
  object orderValDefs extends CopyTransformer with CopySymbolTransformer {
    val trans: PartialFunction[Tree, Tree] = {
      case b @ BoxDef(name, superName, guiSize, image, defs, vals, ports, connections, junctions) ⇒
        val orderVals = b.symbol.asInstanceOf[BoxTypeSymbol].executionOrder map { _.decl }
        atOwner(b.symbol) {
          BoxDef(name, superName, guiSize, image,
            transformTrees(defs),
            transformTrees(orderVals),
            transformTrees(ports),
            transformTrees(connections),
            transformTrees(junctions))
        }
    }
  }
  object rewrite {
    def vClass(bd: BoxDef): Option[Name] = {
      bd.symbol.asInstanceOf[BoxTypeSymbol].visualClass
    }
    def apply(t: Tree) = t match {
      case b @ BoxDef(name, superName, guiSize, image, defs, vals, ports, connections, junctions) ⇒
        val tpe = b.tpe.asInstanceOf[BoxTypeSymbol]
        val baseFields = (vals ++ ports).map { field(_) }
        val fields = vClass(b) map { vn ⇒
          FieldDef(Name("_widget"), vn) :: baseFields
        } getOrElse { baseFields }
        val baseMethods = List(cons(b), appl(b))
        BoxClass(
          tpe.fqName,
          // TODO check super-name
          superName getOrElse { Name(classOf[RunnableBox].getName) },
          baseMethods ++ fields)
    }
    def field(t: Tree) = t match {
      case PortDef(name, typeName, dir, inPos, extPos) ⇒
        FieldDef(name, t.symbol.tpe.name)
      case v: ValDef ⇒
        val tpe = v.symbol.tpe.asInstanceOf[BoxTypeSymbol]
        FieldDef(v.name, t.symbol.tpe.asInstanceOf[BoxTypeSymbol].fqName)
    }
    def cons(b: BoxDef) = {
      val bs = b.symbol.asInstanceOf[BoxTypeSymbol]
      // boxes
      val boxCreation: List[Tree] = b.vals map {
        _ match {
          case v: ValDef ⇒
            val tpe = v.symbol.tpe.asInstanceOf[BoxTypeSymbol]
            val vs = v.symbol.asInstanceOf[ValSymbol]
            val sig = vs.constructor.get.signature
            val values = for ((v, t) ← vs.constructorParams) yield {
              Const(v)
            }
            Assign(
              Select(This, FieldRef(v.name, tpe.fqName, bs.fqName)),
              New(tpe.fqName, values, sig))
        }
      }
      // params
      val params = b.vals flatMap {
        case valDef: ValDef ⇒
          val valSym = valDef.symbol.asInstanceOf[ValSymbol]
          val valBs = valSym.tpe.asInstanceOf[BoxTypeSymbol]
          valSym.params map {
            case (param, v) ⇒
              Invoke(
                Select(This, FieldRef(valSym.name, valBs.fqName, bs.fqName)),
                param.name.str,
                List(Const(v)),
                valBs.fqName,
                "(" + param.tpe.asInstanceOf[JavaType].descriptor + ")V")
          }
      }
      // widgets
      val widgets = vClass(b) map { vn ⇒
        val widgetCreation: List[Tree] = List(
          Assign(Select(This, FieldRef(widgetName, vn, bs.fqName)),
            New(vn, List(NullConst), "(Ljava/awt/LayoutManager;)V")),
          Invoke(
            Select(This, FieldRef(widgetName, vn, bs.fqName)),
            "setSize",
            List(Const(b.guiSize.map(_.w).getOrElse(100)),
              Const(b.guiSize.map(_.h).getOrElse(100))),
            Name("javax.swing.JComponent"),
            "(II)V"))
        widgetCreation ++ createWidgets(bs, List(), b)
      }
      ConstructorMethod(boxCreation ++ params ++ widgets.toList.flatten)
    }

    val widgetName = Name("_widget")
    def fieldRef(v: ValSymbol) = {
      val tpe = v.tpe.asInstanceOf[BoxTypeSymbol]
      val ownertpe = v.owner.asInstanceOf[BoxTypeSymbol]
      FieldRef(v.name, tpe.fqName, ownertpe.fqName)
    }
    def selectPath(path: List[ValSymbol]): Tree = {
      path match {
        case Nil ⇒ This
        case v :: tail ⇒ Select(selectPath(tail), fieldRef(v))
      }
    }
    def createWidget(path: List[ValSymbol], mainBox: BoxDef): List[Tree] = {
      val vs = path.head
      val valDef = vs.decl.asInstanceOf[ValDef]
      val tpe = vs.tpe.asInstanceOf[BoxTypeSymbol]
      val mainTpe = mainBox.symbol.asInstanceOf[BoxTypeSymbol]
      tpe.visualClass map { cl ⇒
        val widgetSelect = Select(selectPath(path), FieldRef(widgetName, cl, tpe.fqName))
        List[Tree](
          Invoke(
            widgetSelect,
            "setBounds",
            List(Const(valDef.guiPos.map(_.x).getOrElse(0)),
              Const(valDef.guiPos.map(_.y).getOrElse(0)),
              Const(valDef.guiSize.map(_.w).getOrElse(50)),
              Const(valDef.guiSize.map(_.h).getOrElse(50))),
            Name("javax.swing.JComponent"),
            "(IIII)V"),
          Invoke(
            Select(This, FieldRef(widgetName, vClass(mainBox).get, mainTpe.fqName)),
            "add",
            List(widgetSelect),
            Name("javax.swing.JComponent"), "(Ljava/awt/Component;)Ljava/awt/Component;"),
          Pop) ++ createLabel(vs,mainBox)
      } getOrElse List()
    }
    def createLabel(vs: ValSymbol,mainBox:BoxDef): List[Tree] = {
      val v = vs.decl.asInstanceOf[ValDef]
      val mainTpe = mainBox.symbol.asInstanceOf[BoxTypeSymbol]
      v.labelGui match {
        case Some(lbl) ⇒
          val jlabel = new JLabel(lbl.description) // TODO better way to get size
          val jdim = jlabel.getPreferredSize
          val pos = v.guiPos.getOrElse(Point(0, 0)) + lbl.pos + Vector2(0,-jdim.height);
          List[Tree](
            New(Name("javax.swing.JLabel"), List(Const(lbl.description)), "(Ljava/lang/String;)V"),
            AStore(1),
            Invoke(
              ALoad(1), 
              "setBounds",
              List(Const(pos.x),
                Const(pos.y),
                Const(jdim.width),
                Const(jdim.height)),
              Name("javax.swing.JComponent"),
              "(IIII)V"),
            Invoke(
              Select(This, FieldRef(widgetName, vClass(mainBox).get, mainTpe.fqName)),
              "add",
              List(ALoad(1)),
              Name("javax.swing.JComponent"), "(Ljava/awt/Component;)Ljava/awt/Component;"),
            Pop)
        case None => List()
      }
    }
    def createWidgets(b: BoxTypeSymbol, path: List[ValSymbol], mainBox: BoxDef): List[Tree] = {
      b.declaredVals.values.toList flatMap {
        case v: ValSymbol ⇒
          val tpe = v.tpe.asInstanceOf[BoxTypeSymbol];
          if (tpe.isLocal)
            createWidgets(tpe, v :: path, mainBox)
          else
            createWidget(v :: path, mainBox)
      }
    }
    def appl(b: BoxDef): Method = {
      val bs = b.symbol.asInstanceOf[BoxTypeSymbol]
      // propagate initial inputs
      def execConnection(c: (PortKey, Set[PortKey])) = {
        def toRef(p: AnyRef): Tree = p match {
          case b: BoxTypeSymbol ⇒ This
          case v: ValSymbol ⇒ Select(This, FieldRef(v.name, v.tpe.asInstanceOf[BoxTypeSymbol].fqName, bs.fqName))
          case ValPortKey(from, portName, in) ⇒
            val vfrom = b.vals.view.collect { case v: ValDef ⇒ v.symbol } find { _.name == from } get
            val pfrom = vfrom.tpe.asInstanceOf[BoxTypeSymbol].lookupPort(portName).get.asInstanceOf[PortSymbol]
            Select(toRef(vfrom), FieldRef(portName, pfrom.tpe.name, vfrom.tpe.asInstanceOf[BoxTypeSymbol].fqName))
          case BoxPortKey(portName, in) ⇒
            val pfrom = bs.lookupPort(portName).get
            Select(This, FieldRef(portName, pfrom.tpe.name, bs.fqName))
        }
        val (out, ins) = c
        ins.toList map { in ⇒
          Assign(toRef(in), toRef(out))
        }
      }
      def connections = bs.connections
      def propagateInitialInputs = {
        val initialConnections = {
          connections.flow collect {
            case c @ (a: BoxPortKey, _) ⇒ c
          } toList
        }
        initialConnections flatMap { execConnection(_) }
      }
      // execute in order
      def runOne(v: ValDef) = {
        def outConnections = connections.flow collect {
          case c @ (p @ ValPortKey(name, _, in), ins) if (name == v.name) ⇒ c
        } toList
        val outs = outConnections flatMap { execConnection(_) }
        val tpe = v.tpe.asInstanceOf[BoxTypeSymbol].fqName
        val invoke = Invoke(
          Select(This, FieldRef(v.name, tpe, bs.fqName)),
          "apply",
          List(),
          tpe,
          "()V")
        invoke :: outs
      }

      Method(Name("contents"), "()V", propagateInitialInputs ++ (b.vals flatMap { case v: ValDef ⇒ runOne(v) }))

    }
  }
  def run() = {
    val owner = global.root
    val mutated = orderValDefs(t, owner)
    //PrettyPrinter.print(mutated, 0)
    rewrite(mutated)
  }
}