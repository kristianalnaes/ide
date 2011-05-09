package org.zaluum.nide.zge

import org.eclipse.draw2d.Shape
import org.eclipse.swt.graphics.Color
import org.eclipse.draw2d.Ellipse
import org.eclipse.swt.events.FocusListener
import org.eclipse.jface.viewers.ICellEditorListener
import org.eclipse.swt.widgets.Text
import org.eclipse.jface.viewers.TextCellEditor
import org.eclipse.draw2d.text.TextFlow
import org.eclipse.draw2d.text.FlowPage
import org.eclipse.draw2d.RectangleFigure
import draw2dConversions._
import org.eclipse.draw2d.{ ColorConstants, Figure, ImageFigure, Polyline, Graphics }
import org.eclipse.draw2d.geometry.{ Rectangle, Point ⇒ EPoint, Dimension ⇒ EDimension }
import org.eclipse.swt.SWT
import org.eclipse.swt.graphics.Image
import org.zaluum.nide.compiler._
import scala.collection.mutable.Buffer
import org.eclipse.swt.widgets.{ Composite, Display, Shell, Listener, Event }
import javax.swing.JButton
import javax.swing.JComponent
import java.awt.{ Graphics ⇒ AG }
import java.awt.image.BufferedImage

// TREE SPECIFIC FIGURES
trait ValDefItem extends Item {
  var valDef: ValDef = _
  def valSym = valDef.symbol.asInstanceOf[ValSymbol]
  def pos = valDef.pos
  override def selectionSubject = Some(valDef)
  def updateValDef(t: ValDef) {
    valDef = t
    updateMe()
    updateSize()
    updateValPorts()
  }
  def updateMe()
  def updateValPorts()
}

trait ValFigure extends ValDefItem with HasPorts {
  def sym = valDef.symbol.asInstanceOf[ValSymbol]
  def myLayer = container.layer
  def updateValPorts() {
    for (p ← ports) container.portsLayer.remove(p)
    ports.clear
    sym.tpe match {
      case b: BoxTypeSymbol ⇒
        val bports = b.portsWithSuper.values collect { case s: PortSymbol ⇒ s }
        def isIn(p: PortSymbol) = p.dir == In
        val (ins, outs) = bports.partition { isIn(_) } // SHIFT?
        def space(s: PortSymbol) = if (isIn(s)) size.h / (ins.size + 1) else size.h / (outs.size + 1)
        def createPort(s: PortSymbol, i: Int) {
          val p = new PortFigure(container)
          val x = if (isIn(s)) 0 else size.w
          val point = Point(x, (i + 1) * space(s))
          p.update(point + Vector2(getBounds.x, getBounds.y), s, sym, s.dir == In)
          ports += p
        }
        for ((p, i) ← ins.zipWithIndex) createPort(p, i)
        for ((p, i) ← outs.zipWithIndex) createPort(p, i)
      case _ ⇒ List()
    }

  }
}
trait AutoDisposeImageFigure extends ImageFigure {
  def disposeImage() {
    if (getImage != null) getImage.dispose
  }
}
class ImageValFigure(val container: ContainerItem) extends AutoDisposeImageFigure with ValFigure with RectFeedback {
  def size = Dimension(getImage.getBounds.width, getImage.getBounds.height)
  setOpaque(true)
  def updateMe() {
    disposeImage()
    val newImg = container.viewerResources.imageFactory(valDef.tpe)
    setImage(newImg)
  }
  override def paintFigure(gc:Graphics) {
    gc.setAlpha(if(blinkOn) 100 else 255);
  //  gc.drawImage(getImage, getClientArea.x, getClientArea.y)
    super.paintFigure(gc)
  }
  var blinkOn = false
  def blink(b:Boolean) {
    blinkOn = b
    repaint()
  }
}
class DirectValFigure(val container: ContainerItem) extends TextEditFigure with ValFigure {
  def size = Dimension(40, 20)
  def param = valDef.params.headOption.asInstanceOf[Option[Param]]
  def text = param.map {_.value}.getOrElse{"0"}
  def updateMe {
    fl.setText(text)
    setForegroundColor(Colorizer.color(param.map(_.tpe).getOrElse(NoSymbol)))
  }
  def blink(c:Boolean) {
    this.setXOR(c)
  }
}
trait TextEditFigure extends RectangleFigure with Item with RectFeedback {
  def text: String;
  val pg = new FlowPage()
  pg.setForegroundColor(ColorConstants.black)
  pg.setBounds(new Rectangle(2, 2, 40, 20))
  val fl = new TextFlow()
  pg.add(fl)
  add(pg)

  var textCellEditor: TextCellEditor = null
  def edit(onComplete: (String) ⇒ Unit, onCancel: () ⇒ Unit) = {
    if (textCellEditor == null) {
      textCellEditor = new TextCellEditor(container.viewer.canvas)
      val textC = textCellEditor.getControl().asInstanceOf[Text]
      textC.setText(text)
      textCellEditor.activate()
      textCellEditor.addListener(new ICellEditorListener() {
        def applyEditorValue() { onComplete(textC.getText) }
        def cancelEditor() { onCancel() }
        def editorValueChanged(oldValid: Boolean, newValid: Boolean) {}
      })
      val b = getClientArea.getCopy
      translateToAbsolute(b)
      textC.setBounds(b.x + 1, b.y + 1, b.width - 2, b.height - 2)
      textC.setBackground(ColorConstants.white)
      textC.setVisible(true)
      textC.selectAll()
      textC.setFocus
    }
  }
  def hideEdit() = {
    if (textCellEditor != null) {
      textCellEditor.dispose()
      textCellEditor = null
    }
  }
}
class SwingFigure(val container: ContainerItem, val cl: ClassLoader) extends Figure with ValDefItem with ResizableFeedback {
  setOpaque(true)
  def size = valDef.guiSize getOrElse { Dimension(15, 15) }
  override def pos = valDef.guiPos getOrElse { Point(0, 0) }
  def myLayer = container.layer
  var component: Option[JComponent] = None
  def forName(str: String): Option[Class[_]] = {
    try { Some(cl.loadClass(str)) }
    catch { case e: Exception ⇒ e.printStackTrace; None }
  }

  override def updateValDef(valDef: ValDef) = {
    def instance(cl: Class[_]) = {
      try {
        Some(cl.newInstance().asInstanceOf[JComponent])
      } catch { case e ⇒ e.printStackTrace; None }
    }
    component = for (
      c ← valDef.tpe.asInstanceOf[BoxTypeSymbol].visualClass;
      cl ← forName(c.str);
      i ← instance(cl)
    ) yield i
    super.updateValDef(valDef)
  }
  def updateMe() {}
  override def updateValPorts() {}
  override def selectionSubject = Some(valDef)
  var blinkOn = false
  def blink(b:Boolean) {
    blinkOn = b
    repaint()
  }
  override def paintFigure(g: Graphics) {
    val rect = getClientArea()
    val aimage = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB)
    val ag = aimage.createGraphics
    component foreach { c ⇒
      c.setBounds(0, 0, rect.width, rect.height);
      c.doLayout
      c.paint(ag)
    }
    val imageData = SWTUtils.convertAWTImageToSWT(aimage)
    val image = new org.eclipse.swt.graphics.Image(Display.getCurrent(), imageData)
    g.setXORMode(blinkOn)
    g.drawImage(image, rect.x, rect.y)
    g.setForegroundColor(ColorConstants.lightGray)
    g.drawRectangle(rect.getCopy.expand(-1,-1))
    ag.dispose();
    image.dispose()
  }
}