package org.zaluum.nide.zge

import scala.collection.mutable.Buffer
import scala.collection.mutable.Stack
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.ElementChangedEvent
import org.eclipse.jdt.core.ICompilationUnit
import org.eclipse.jdt.core.IElementChangedListener
import org.eclipse.jdt.core.IJavaElement
import org.eclipse.jdt.core.IJavaElementDelta
import org.eclipse.jdt.core.IJavaProject
import org.eclipse.jdt.core.JavaCore
import org.eclipse.swt.widgets.Display
import org.eclipse.text.edits.ReplaceEdit
import org.zaluum.nide.utils.Utils.inSWT
import org.zaluum.nide.compiler._
import org.zaluum.nide.eclipse.integration.model.ZaluumASTParser
import org.zaluum.nide.eclipse.integration.model.ZaluumDomCompilationUnit
import org.zaluum.nide.eclipse.ZaluumProject
import org.zaluum.nide.utils.Timer

class Controller(val cu: ICompilationUnit, val zproject: ZaluumProject, implicit val display: Display) {
  private var nowTree: BoxDef = _

  private var viewers = Buffer[Viewer]()

  def registerViewer(viewer: Viewer) {
    viewers += viewer
  }
  def unregisterViewer(viewer: Viewer) {
    viewers -= viewer
  }
  def updateViewers(map: Map[SelectionSubject, SelectionSubject]) {
    viewers foreach { v ⇒
      inSWT {
        v.remapSelection(map);
        v.refresh();
      }
    }
  }
  def blink(s: SelectionSubject, fromViewer: Viewer) {
    viewers filterNot { _ == fromViewer } foreach { _.blink(s) }
  }
  def tree = nowTree
  val reporter = new Reporter()
  type DMap = Map[SelectionSubject, SelectionSubject]
  def refreshTools(m: DMap) { viewers foreach { _.tool.refresh(m) } }
  case class Mutation(before: BoxDef, d: DMap, now: BoxDef)
  var undoStack = Stack[Mutation]()
  var redoStack = Stack[Mutation]()
  var mark: Option[Mutation] = None
  def isDirty = undoStack.elems.headOption != mark
  def markSaved() { mark = undoStack.elems.headOption }

  def exec(c: MapTransformer) {
    val before = nowTree
    nowTree = c(tree).asInstanceOf[BoxDef]
    before.clean()
    before.deepChildrenStream foreach { t ⇒
      t.clean()
    }
    undoStack.push(Mutation(before, c.map, nowTree))
    redoStack.clear
    update(c.map)
  }
  // uses a special parser to keep the mutated tree.
  def compile(parse: Boolean) = {
    val parser = if (parse) ASTParser.newParser(AST.JLS3)
    else new ZaluumASTParser(AST.JLS3, nowTree)
    parser.setKind(ASTParser.K_COMPILATION_UNIT)
    parser.setSource(cu)
    parser.setResolveBindings(true)
    parser.setIgnoreMethodBodies(true)
    val domcu = parser.createAST(null)
    nowTree = domcu match {
      case z: ZaluumDomCompilationUnit ⇒
        z.tree
      case _ ⇒ throw new Exception()
    }
  }
  def replaceWorkingCopyContents() {
    if (isDirty) {
      if (!cu.isWorkingCopy) cu.becomeWorkingCopy(null)
      val str = Serializer.writeToIsoString(Serializer.proto(nowTree));
      cu.applyTextEdit(new ReplaceEdit(0, cu.getBuffer.getLength, str), null)
    } else {
      if (cu.isWorkingCopy) cu.discardWorkingCopy
    }
  }
  def dispose() {
    if (cu.isWorkingCopy)
      cu.discardWorkingCopy()
    JavaCore.removeElementChangedListener(coreListener)
  }
  /*def usedTypes : Seq[Type] = {
    val b = scala.collection.mutable.Set[Type]()
    val t = new Traverser(global) {
      override def traverse(tree:Tree) = {
        tree match {
          case t:ValDef if (t.tpe!=NoSymbol)=> b+=t.tpe 
          case _ =>
        }
        super.traverse(tree)
      }
    }
    t.traverse(nowTree)
    b.toSeq
  }*/
  def noChangeMap = {
    var map: DMap = Map()
    new Traverser(null) {
      override def traverse(tree: Tree) = {
        map += (tree -> tree)
      }
    }
    map
  }
  private def update(m: DMap) {
    nowTree.assignLine(1)
    //Timer.go
    replaceWorkingCopyContents()
    //Timer.stop("replaceWorking")
    recompile(m)
  }
  private def recompile(m: DMap) {
    Timer.go
    compile(false)
    Timer.stop("compilation")
    //PrettyPrinter.print(nowTree, 0)
    Timer.go
    updateViewers(m)
    notifyListeners
    refreshTools(m)
    Timer.stop("updateViewers")
    println("---")
  }
  def canUndo = !undoStack.isEmpty
  def canRedo = !redoStack.isEmpty
  def undo() {
    if (!undoStack.isEmpty) {
      val mutation = undoStack.pop
      nowTree = mutation.before
      redoStack.push(mutation)
      update(mutation.d map { _.swap })
    }
  }
  def redo() {
    if (!redoStack.isEmpty) {
      val mutation = redoStack.pop;
      undoStack.push(mutation)
      nowTree = mutation.now
      update(mutation.d)
    }
  }
  def nonDirtyTree = mark match {
    case Some(markMut) ⇒ markMut.now
    case None ⇒ undoStack.lastOption match {
      case Some(mut) ⇒ mut.before
      case None      ⇒ nowTree
    }
  }
  def fromSaveMutations = { // returns the mutations to go from saved state to nowTree
    mark match {
      case None ⇒ undoStack.toList reverse
      case Some(m) ⇒
        val dropped = undoStack.toList.reverse dropWhile (_ != m)
        if (dropped.isEmpty) List() else dropped.drop(1)
    }
  }
  def nonDirtyToNow(t: Tree): Option[Tree] = {
    var mutatedt = t
    fromSaveMutations foreach { mutation ⇒
      mutation.d.get(mutatedt) match {
        case Some(mt: Tree) ⇒
          if (mutation == undoStack.head)
            return Some(mt);
          else
            mutatedt = mt;
        case a ⇒
          return None
      }
    }
    Some(t)
  }
  def findPath(line: Int) = { // walk current mutations to map line numbers
    val l = nonDirtyTree.findPath(line)
    l flatMap { nonDirtyToNow(_) }
  }
  // listeners 
  var listeners = Set[() ⇒ Unit]()
  def addListener(action: () ⇒ Unit) {
    listeners += action
  }
  def removeListener(action: () ⇒ Unit) {
    listeners -= action
  }
  def notifyListeners() {
    inSWT {
      listeners foreach { _() }
    }
  }
  // core listener
  val coreListener = new IElementChangedListener() {
    def isDeltaElemUpdated(delta: IJavaElementDelta): Boolean = {
        def isPrimaryResource = (delta.getFlags & IJavaElementDelta.F_PRIMARY_RESOURCE) != 0
        def isJavaProject = delta.getElement.getElementType == IJavaElement.JAVA_PROJECT
        def isJavaCompilationUnit = delta.getElement.getElementType == IJavaElement.COMPILATION_UNIT
        def isAdded = delta.getKind == IJavaElementDelta.ADDED
        def isRemoved = delta.getKind == IJavaElementDelta.REMOVED
        def isChanged = delta.getKind == IJavaElementDelta.CHANGED;
        def parseChildren = { delta.getAffectedChildren exists { isDeltaElemUpdated(_) } }
      // TODO look for used types  
      if (isPrimaryResource && isJavaProject) {
        val jproj = delta.getElement.asInstanceOf[IJavaProject]
        if (isAdded) true
        else if (isRemoved) true
        else parseChildren
      } else if (isPrimaryResource && isJavaCompilationUnit) {
        if (isChanged || isRemoved) {
          val res = delta.getElement.getResource
          true
        } else {
          parseChildren
        }
      } else
        parseChildren
    }
    def elementChanged(event: ElementChangedEvent) {
      if (isDeltaElemUpdated(event.getDelta))
        recompile(noChangeMap)
    }
  }
  // init
  compile(true)
  JavaCore.addElementChangedListener(coreListener)
}

