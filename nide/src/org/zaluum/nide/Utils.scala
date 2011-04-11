package org.zaluum.nide
import org.eclipse.jface.viewers.CellEditor
import org.eclipse.swt.widgets.{ Text, Display }
import org.eclipse.jface.resource.ImageRegistry
import org.eclipse.draw2d.Figure
import org.eclipse.jface.fieldassist.IContentProposalProvider
import org.eclipse.jface.fieldassist.IContentProposal
import scala.collection.JavaConversions._
import scala.collection.mutable._
object Utils {

  def loadIcons(ir: ImageRegistry, base: Class[_], keys: String*) = {
    keys.foreach(k ⇒ { loadImage(ir, k + "_16", base); loadImage(ir, k + "_32", base) })
  }

  def loadImage(ir: ImageRegistry, key: String, base: Class[_]) = {
    import org.eclipse.jface.resource.ImageDescriptor
    if (ir.getDescriptor(key) == null) {
      ir.put(key, ImageDescriptor.createFromURL(base.getResource(key + ".png")));
    }
  }
  implicit def asRunnable(func: () ⇒ Unit): Runnable = {
    new Runnable() {
      def run() {
        func()
      }
    }
  }
  def inSWT(toRun: ⇒ Unit)(implicit display: Display) {
    if (Display.getCurrent != null)
      toRun
    else {
      (if (display == null)
        Display.getDefault
      else display).asyncExec(new Runnable { override def run { toRun } })
    }
  }
}

class EditCPP(val c: Array[String]) extends IContentProposalProvider() {
  override def getProposals(contentsProposal: String, position: Int) =
    Array.tabulate(c.size)(i ⇒ new ContentProposal(i, c(i)))
}

class ContentProposal(index: Int, text: String) extends IContentProposal {
  override def getContent(): String = { text }
  override def getCursorPosition(): Int = { index }
  override def getLabel(): String = { text }
  override def getDescription(): String = {
    if (text.startsWith("@")) "Connect to outer port " + text.substring(1) else "Connect to neighbor port " + text
  }
}

class RichCast(val a: AnyRef) {
  def castOption[A](implicit m: Manifest[A]) =
    if (Manifest.singleType(a) <:< m) Some(a.asInstanceOf[A]) else None
}
object RichCast {
  implicit def optionCast(a: AnyRef) = new RichCast(a)
}
trait Subject {
  private var observers = List[Observer]()
  def addObserver(observer: Observer) = observers ::= observer
  def removeObserver(observer: Observer) = observers filterNot (_ == observer)
  def notifyObservers = observers foreach (_.receiveUpdate(this))
}
trait Observer {
  def receiveUpdate(subject: Subject)
}