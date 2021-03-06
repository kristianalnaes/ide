package org.zaluum.nide.zge

import org.eclipse.jface.resource.DeviceResourceDescriptor
import org.eclipse.swt.graphics.Color
import org.eclipse.draw2d.Ellipse
import org.eclipse.jface.viewers.ICellEditorListener
import org.eclipse.swt.widgets.Text
import org.eclipse.jface.viewers.TextCellEditor
import org.eclipse.draw2d.text.TextFlow
import org.eclipse.draw2d.text.FlowPage
import org.eclipse.draw2d.RectangleFigure
import org.eclipse.draw2d.{ ColorConstants, Figure, ImageFigure, Graphics }
import org.eclipse.draw2d.geometry.{ Rectangle, Point ⇒ EPoint, Dimension ⇒ EDimension }
import org.eclipse.swt.SWT
import org.eclipse.swt.graphics.{ Image, Font, GC }
import org.zaluum.nide.compiler._
import org.eclipse.swt.widgets.{ Composite, Display, Shell, Listener, Event }
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.{ Graphics ⇒ AG }
import java.awt.image.BufferedImage
import org.eclipse.draw2d.FigureUtilities
import org.zaluum.nide.Activator
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding
import org.zaluum.nide.utils.MethodBindingUtils
import org.eclipse.jdt.internal.core.JavaProject
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding
import org.eclipse.ui.views.properties.TextPropertyDescriptor
import org.eclipse.ui.views.properties.IPropertyDescriptor
import org.eclipse.ui.views.properties.IPropertySource2
import org.zaluum.nide.utils.SwingSWTUtils._
import org.eclipse.ui.views.properties.ColorPropertyDescriptor
import org.eclipse.swt.graphics.RGB
import org.eclipse.ui.views.properties.PropertyDescriptor
import org.eclipse.jface.viewers.LabelProvider
import org.eclipse.swt.graphics.FontData
import org.eclipse.jface.viewers.DialogCellEditor
import org.eclipse.swt.widgets.FontDialog
import org.eclipse.swt.widgets.Control
import org.zaluum.nide.compiler.InvalidValueType
import org.zaluum.nide.utils.SWTScala
import org.zaluum.nide.utils.SwingSWTUtils
import org.zaluum.nide.compiler.BoxExprType
import org.zaluum.nide.utils.ReflectionUtilsWB
import org.zaluum.basic.BoxConfigurer
import javax.script.ScriptEngineManager
import java.security.AccessController
import java.security.PrivilegedAction
import org.zaluum.nide.images.ImageKey
// TREE SPECIFIC FIGURES
trait ValDefItem extends Item with PropertySource {
  var valDef: ValDef = _
  def valSym = valDef.sym
  def pos = valDef.pos
  def viewPortPos = this.translateToViewport(pos)
  override def selectionSubject = Some(valDef)
  def updateValDef(t: ValDef) {
    valDef = t
    updateMe()
    updateSize()
    updateValPorts()
    properties = calcProperties()
  }
  def updateMe()
  def updateValPorts()
  import scala.util.control.Exception._
  def openConfigurer(cloader: ClassLoader): Option[List[Param]] = {
    if (valSym == null) { throw new RuntimeException("BUG: null") }
      def open(cl: Class[_]): Option[List[Param]] = {
        try {
          import scala.collection.JavaConverters._
          import scala.collection.JavaConversions._
          val conf = cl.newInstance()
          // Shell and BoxConfigurer are specially loaded in projectclassloader
          val configurer = conf.asInstanceOf[BoxConfigurer]
          val pars = new java.util.HashMap[String, java.util.List[String]]()
          valDef.params.foreach { p ⇒
            pars.put(p.key.str, p.values.asJava)
          }
          //open
          val newpars = configurer.configure(container.viewer.shell, pars)
          if (newpars == null) None
          else {
            Some(newpars.view.map {
              case (k, l) ⇒ Param(Name(k), l.toList)
            }.toList)
          }
        } catch { case e: Exception ⇒ e.printStackTrace; None }
      }
    for (c ← valSym.configurer; cl ← c.loadClass(cloader); res ← open(cl)) yield res
  }
  def controller = container.viewer.controller
  def calcProperties(): List[Property] = {
    val tpe = new ValDefTypeProperty(valDef, controller)
    val nme = new NameProperty(valDef, controller)
    val lbl = new LabelProperty(valDef, controller, false)
    val lblGui = new LabelProperty(valDef, controller, true)
    val props: List[ParamProperty] = valSym.tpe match {
      case Some(p: PropertySourceType) ⇒ p.properties(controller, valDef)
      case _                           ⇒ List()
    }
    val missing = valDef.params filter {
      case p: Param ⇒
        if (valDef.sym.tpe == Some(BoxExprType) &&
          p.key == BoxExprType.constructorParamsDecl.fqName ||
          p.key == BoxExprType.constructorTypesDecl.fqName)
          false
        else
          !props.exists { _.key == p.key }

    } map { case p: Param ⇒ new MissingParamProperty(controller, p, valDef) }
    val consl = if (valDef.sym.tpe == Some(BoxExprType)) List(new ConstructorSelectProperty(valDef, controller))
    else List()
    consl ::: nme :: tpe :: lbl :: lblGui :: missing ::: props
  }
  def display = container.viewer.display
}

trait ValFigure extends ValDefItem with OverlappedEffect with HasPorts {
  def sym = valDef.sym
  def myLayer = container.layer
  var ins = List[PortSide]()
  var outs = List[PortSide]()
  var minYSize = 0
  val separation = 6
  def updateMe() {
    val bports = sym.portSides;
    val (unsortedins, unsortedouts) = bports.partition { _.inPort } // SHIFT?
    ins = unsortedins.toList.sortBy(_.name.str);
    outs = unsortedouts.toList.sortBy(_.name.str);
    val max = math.max(ins.size, outs.size)
    val correctedMax = if (max == 2) 3 else max
    // spread 2 ports like X _ X
    minYSize = correctedMax * separation
  }
  def updateValPorts() {
    val center = size.h / 2
    val insStartY = center - separation * (ins.size / 2)
    val outStartY = center - separation * (outs.size / 2)
      def createPort(s: PortSide, i: Int) {
        val p = new PortFigure(container)
        val x = if (s.inPort) 0 else size.w
        val sourceList = if (s.inPort) ins else outs
        val skipCenter = if (sourceList.size == 2 && i == 1) 1 else 0 // skip 1 position 
        val starty = if (s.inPort) insStartY else outStartY
        val point = Point(x, +starty + ((i + skipCenter) * separation))
        p.update(point + Vector2(getBounds.x, getBounds.y), s)
        ports += p
      }
    for (p ← ports) p.destroy()
    ports.clear
    for ((p, i) ← ins.zipWithIndex) createPort(p, i)
    for ((p, i) ← outs.zipWithIndex) createPort(p, i)

  }
}
class LabelItem(val container: ContainerItem, gui: Boolean = false) extends TextEditFigure with ValDefItem with RectFeedback {
  setForegroundColor(Colorizer.color(None))
  def blink(b: Boolean) {}
  override val textPos = new EPoint(0, 0)
  def size = preferredTextSize
  def baseVector = Vector2(0, -size.h)
  def lbl = if (gui) valDef.labelGui else valDef.label
  def text = {
    val fromTree = lbl.map(_.description).getOrElse("XXX")
    if (fromTree == "") valDef.name.str else fromTree
  }
  def basePos = if (gui) valSym.bounds.map { r ⇒ Point(r.x, r.y) }.getOrElse(Point(0, 0)) else valDef.pos
  override def pos = basePos + baseVector + (lbl.map { _.pos } getOrElse { Vector2(0, 0) })
  def myLayer = container.layer
  def updateMe() {
    updateText()
  }
  def updateValPorts() {}
}
class ThisOpValFigure(container: ContainerItem) extends ImageValFigure(container) {
  private def jproject = container.viewer.zproject.jProject.asInstanceOf[JavaProject]
  override def img = {
    sym.typeSpecificInfo match {
      case Some(m: MethodBinding) ⇒
        val txt =
          if (m.isConstructor())
            "new " + m.declaringClass.compoundName.last.mkString
          else {
            (if (m.isStatic()) m.declaringClass.compoundName.last.mkString else "") +
              "." + m.selector.mkString + "()"
          }
        sym.portInstances
        imageFactory.iconMethod(m, minYSize, txt)
      case Some(f: FieldBinding) ⇒
        val prefix = if (f.isStatic()) f.declaringClass.compoundName.last.mkString else ""
        imageFactory.textIcon(prefix + "." + f.name.mkString, minYSize)
      case _ ⇒
        val str = sym.tpe match {
          case Some(NewArrayExprType) ⇒
            val pi = NewArrayExprType.thisPort(sym)
            pi.tpe match {
              case Some(tpe: ArrayType) ⇒ Some("new " + tpe.of.name.str.split('.').last + "[]" * tpe.dim)
              case _                    ⇒ None
            }
          case Some(ArrayComposeExprType) ⇒ Some("new[]")
          case _                          ⇒ None
        }
        str match {
          case Some(str) ⇒ imageFactory.textIcon(str, minYSize)
          case None      ⇒ imageFactory.textIconError("<error>", minYSize)
        }
    }
  }
}
class ImageValFigure(val container: ContainerItem) extends ImageFigure with ValFigure with RectFeedback {
  def size = Dimension(getImage.getBounds.width, getImage.getBounds.height)
  def imageFactory = container.viewer.imageFactory
  def img = valDef.sym.tpe match {
    case Some(BoxExprType) ⇒
      val className = Option(valDef.sym.classinfo).map(_.name.str).getOrElse("<no type>")
      BoxExprType.methodOf(valDef.sym) match {
        case Some(m) ⇒ imageFactory.iconMethod(m, minYSize, className)
        case None    ⇒ imageFactory.textIconError("<no type>", minYSize)
      }
    case o ⇒ imageFactory.iconTpe(o, minYSize)
  }

  override def updateMe() {
    super.updateMe()
    setImage(img)
  }
  override def paintFigure(gc: Graphics) {
    gc.setAlpha(if (blinkOn) 100 else 255);
    super.paintFigure(gc)
  }
  var blinkOn = false
  def blink(b: Boolean) {
    blinkOn = b
    repaint()
  }
}
class LiteralFigure(val container: ContainerItem) extends RectangleFigure with TextEditFigure with ValFigure with RectFeedback {
  def size = preferredSize
  def param = valDef.params.headOption
  def text = param.map { _.valueStr }.getOrElse { "0" }
  setFill(false)
  override val textPos = new EPoint(2, 2)
  override def updateMe {
    super.updateMe()
    updateText()
    val tpe = LiteralExprType.outPort(valDef.sym).tpe
    setForegroundColor(Colorizer.color(tpe))
  }
  def blink(c: Boolean) {
    this.setXOR(c)
  }
}
trait TextEditFigure extends Item {
  def text: String;
  def preferredSize =
    preferredTextSize.ensureMin(Dimension(baseSpace * 3, baseSpace * 3)) + Vector2(baseSpace, 0)
  def preferredTextSize = pg.getPreferredSize()
  val textPos = new EPoint(2, 2)
  setFont(Activator.getDefault.directEditFont) // https://bugs.eclipse.org/bugs/show_bug.cgi?id=308964
  private val pg = new FlowPage()
  setOpaque(false)
  pg.setForegroundColor(ColorConstants.black)
  private val fl = new TextFlow()
  pg.add(fl)
  add(pg)
  def updateText() {
    fl.setText(text)
    pg.setBounds(new Rectangle(textPos, preferredTextSize))
  }
  var textCellEditor: TextCellEditor = null
  def edit(onComplete: (String) ⇒ Unit, onCancel: () ⇒ Unit) = {
    if (textCellEditor == null) {
      textCellEditor = new TextCellEditor(container.viewer.canvas)
      val textC = textCellEditor.getControl().asInstanceOf[Text]
      textC.setFont(Activator.getDefault.directEditFont)
      textC.setText(text)
      textCellEditor.activate()
      textCellEditor.addListener(new ICellEditorListener() {
        def applyEditorValue() { onComplete(textC.getText) }
        def cancelEditor() { onCancel() }
        def editorValueChanged(oldValid: Boolean, newValid: Boolean) {}
      })
      val b = getClientArea.getCopy
      this.translateToAbsolute(b) // !Its absolute and not viewports because it's attached to the canvas
      textC.setBounds(b.x + 1, b.y + 1, math.max(b.width - 1, baseSpace * 8), b.height - 2)
      textC.setBackground(ColorConstants.white)
      textC.setVisible(true)
      textC.selectAll()
      textC.setFocus
    }
  }
  override def destroy() {
    destroyEdit()
    super.destroy()
  }
  def destroyEdit() = {
    if (textCellEditor != null) {
      textCellEditor.dispose()
      textCellEditor = null
    }
  }
}
class SwingFigure(val treeViewer: TreeViewer, val container: ContainerItem, val cloader: ClassLoader) extends ValDefItem with ResizableFeedback {
  setOpaque(true)
  def size =
    valSym.bounds map { r ⇒ Dimension(r.width, r.height) } getOrElse {
      Dimension(baseSpace * 5, baseSpace * 5)
    }
  var position = Point(0, 0)
  override def pos = position
  def myLayer = container.layer
  var component: Option[java.awt.Component] = None

  def updateMe() {
    position = valSym.bounds map { r ⇒ Point(r.x, r.y) } getOrElse {
      treeViewer.findFigureOf(valDef) match {
        case Some(i) ⇒ i.viewPortPos
        case None    ⇒ Point(0, 0)
      }
    }
      def instance(cl: Class[_]) = try {
        valSym.constructor match {
          case Some(cons) ⇒
            MethodBindingUtils.getConstructor(cons, cloader) map { constructor ⇒
              val pars = valSym.constructorParams.flatMap { p ⇒
                if (p.valid) Some(p.parse.asInstanceOf[AnyRef])
                else None
              }
              constructor.newInstance(pars.toArray: _*).asInstanceOf[java.awt.Component]
            }
          case None ⇒
            Some(cl.newInstance().asInstanceOf[java.awt.Component])
        }
      } catch {
        case e: Exception ⇒ None
      }

    component = valDef.sym.tpe match {
      case Some(BoxExprType) ⇒
        val cjt = valDef.sym.classinfo.asInstanceOf[ClassJavaType]
        for (
          cl ← cjt.loadClass(cloader);
          i ← instance(cl)
        ) yield i
      case _ ⇒ None
    }
    component foreach { c ⇒
      valSym.allValues.foreach {
        case (param: BeanParamDecl, v) ⇒
          val classParam = param.tpe.flatMap(_.loadClass(cloader))
          c.getClass().getMethods() find { m ⇒
            m.getName == param.setter.selector.mkString &&
              m.getParameterTypes.size == 1 &&
              Some(m.getParameterTypes()(0)) == classParam
          } foreach { m ⇒
            try {
              if (v.valid) {
                val parsed = v match {
                  case z: ZaluumParseValue ⇒
                    z.parse(container.viewer.controller.zproject).asInstanceOf[AnyRef]
                  case _ ⇒ v.parse.asInstanceOf[AnyRef]
                }
                m.invoke(c, parsed)
              }
            } catch { case e ⇒ e.printStackTrace() }
          }
        case _ ⇒
      }
    }
    component foreach { c ⇒
      valSym.getStr(BoxExprType.scriptDecl) foreach { script ⇒
        val oldLoader = Thread.currentThread().getContextClassLoader()
        try {
          Thread.currentThread().setContextClassLoader(cloader)
          val manager = new ScriptEngineManager();
          val engine = manager.getEngineByName("JavaScript");
          engine.put("c", c)
          engine.eval(script)
        } catch {
          case ex: Exception                    ⇒ ex.printStackTrace()
          case err: ExceptionInInitializerError ⇒ err.printStackTrace() // jrebel
          case err: NoClassDefFoundError        ⇒ err.printStackTrace() // jrebel

        } finally { Thread.currentThread().setContextClassLoader(oldLoader) }
      }
    }
  }
  override def updateValPorts() {}
  override def selectionSubject = Some(valDef)
  var blinkOn = false
  def blink(b: Boolean) {
    blinkOn = b
    // raise the figure
    val p = getParent
    p.remove(this)
    p.add(this)
    repaint()
  }
  override def paintFigure(g: Graphics) {
    try {
      val rect = getClientArea()
      g.setXORMode(blinkOn)
      component match {
        case Some(c) ⇒
          val aimage = new BufferedImage(rect.width, rect.height, BufferedImage.TYPE_INT_RGB)
          val ag = aimage.createGraphics
          c.setBounds(0, 0, rect.width, rect.height);
          c.doLayout
          c.paint(ag)
          val imageData = SwingSWTUtils.convertAWTImageToSWT(aimage)
          val image = new org.eclipse.swt.graphics.Image(Display.getCurrent(), imageData)
          g.drawImage(image, rect.x, rect.y)
          image.dispose()
          ag.dispose();
        case None ⇒
          g.setForegroundColor(ColorConstants.lightGray)
          g.fillRectangle(rect)
          g.setForegroundColor(ColorConstants.gray)
          val data = g.getFont.getFontData
          for (d ← data) {
            d.setHeight(rect.height / 2)
          }
          val font = new Font(Display.getCurrent, data)
          g.setFont(font)
          val dim = FigureUtilities.getStringExtents("?", font)
          g.drawText("?", rect.getCenter.x - dim.width / 2, rect.getCenter.y - dim.height / 2)
          font.dispose()
      }
    } catch {
      case e ⇒ e.printStackTrace
    }
    //g.setForegroundColor(ColorConstants.lightGray)
    //g.drawRectangle(rect.getCopy.expand(-1, -1))
  }
}