package org.zaluum.nide.zge

import org.eclipse.ui.IEditorSite
import org.eclipse.ui.IViewSite
import org.eclipse.ui.PlatformUI
import org.zaluum.nide.compiler.{Tree,SelectionSubject}
import draw2dConversions._
import org.eclipse.draw2d.{FigureCanvas, FreeformViewport, LightweightSystem}
import org.eclipse.swt.graphics.Cursor
import org.eclipse.swt.widgets.Composite
import scala.collection.JavaConversions._
import org.zaluum.nide.eclipse.ZaluumProject
import org.eclipse.swt.events.FocusListener
import org.eclipse.swt.events.FocusEvent
import org.eclipse.swt.events.ControlListener
import org.eclipse.swt.events.ControlEvent

abstract class Viewer(parent: Composite, val controller: Controller) extends FreeformViewport {
  def shell = parent.getShell
  def zproject : ZaluumProject
  lazy val display = shell.getDisplay
  val light = new LightweightSystem()
  val canvas = new FigureCanvas(parent, light)
  canvas.addControlListener(new ControlListener() {
    def controlResized(e : ControlEvent) { onResize() }
    def controlMoved(e:ControlEvent) {}
  }); 
  canvas.addFocusListener(new FocusListener {
    def focusGained(e:FocusEvent) { onFocus()}
    def focusLost(e:FocusEvent) { onFocusLost() }
  })
  def onResize() :Unit
  def onFocus(): Unit
  def onFocusLost() : Unit = {}
  def focus = parent.setFocus
  def tool: Tool
  def refresh()
  def remapSelection(map :  PartialFunction[SelectionSubject,SelectionSubject]) // move to item?
  def blink(s:SelectionSubject)
  def dispose() {
    canvas.dispose()
    controller.unregisterViewer(this)
  }
  def setStatusMessage(s:String) {
    val wb = PlatformUI.getWorkbench();
    val win = wb.getActiveWorkbenchWindow();
    val page = win.getActivePage();
    val part = page.getActivePart();
    val site = part.getSite();
    val vSite =  site.asInstanceOf[IEditorSite];
    val actionBars =  vSite.getActionBars();
    if( actionBars == null ) return ;
    val statusLineManager = actionBars.getStatusLineManager();
    if( statusLineManager == null ) return ;
    statusLineManager.setMessage( s);
  }
}
