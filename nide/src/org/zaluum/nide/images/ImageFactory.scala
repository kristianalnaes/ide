package org.zaluum.nide.images

import org.eclipse.draw2d.ColorConstants
import org.eclipse.jface.resource.DeviceResourceDescriptor
import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.jface.resource.JFaceResources
import org.eclipse.jface.resource.LocalResourceManager
import org.eclipse.jface.resource.ResourceManager
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.Device
import org.eclipse.swt.graphics.GC
import org.eclipse.swt.graphics.Image
import org.eclipse.swt.graphics.TextLayout
import org.eclipse.swt.widgets.Control
import org.eclipse.swt.SWT
import org.zaluum.nide.compiler.In
import org.zaluum.nide.compiler.Name
import org.zaluum.nide.compiler.Out
import org.zaluum.nide.compiler.PortDir
import org.zaluum.nide.compiler.Shift
import org.zaluum.nide.compiler.JavaType
import org.zaluum.nide.icons.Icons
import org.zaluum.nide.Activator
import org.zaluum.nide.compiler.ClassJavaType
import org.zaluum.nide.compiler.ExprType
import org.zaluum.nide.compiler.Type
import org.eclipse.swt.graphics.ImageData
import org.eclipse.swt.graphics.PaletteData
import org.zaluum.nide.palette.Palette
import org.zaluum.nide.palette.PaletteEntry
import java.net.URL
import org.zaluum.nide.utils.Utils._
import org.zaluum.nide.eclipse.ZaluumProject
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding

abstract class IFactory {
  val rm: ResourceManager
  def classLoader: ClassLoader
  def destroy(d: DeviceResourceDescriptor) = rm.destroy(d)
  def destroyAll() = rm.dispose();
  def loadDesc(d: ImageDescriptor): Option[Image] = tryo { rm.createImage(d) }
  protected def resourceToDescriptor(resource: String): Option[ImageDescriptor] =
    Option(classLoader.getResource(resource)) map { urlToDescriptor }
  protected def resourceToImage(resource: String): Option[Image] =
    resourceToDescriptor(resource) flatMap loadDesc
  protected def urlToDescriptor(url: URL): ImageDescriptor =
    ImageDescriptor.createFromURL(url)
}
class ImageFactory private (val zp: ZaluumProject, val rm: ResourceManager) extends IFactory {
  def classLoader = zp.classLoader
  def this(zp: ZaluumProject) = this(zp, new LocalResourceManager(JFaceResources.getResources))
  def this(i: ImageFactory) = this(i.zp, new LocalResourceManager(i.rm))
  def this(i: ImageFactory, c: Control) = this(i.zp, new LocalResourceManager(i.rm, c))
  def imageMap = zp.imgMap
  def notFound = ImageDescriptor.createFromFile(classOf[Icons], "notFound.png")
  def portDeclIn = ImageDescriptor.createFromFile(classOf[Icons], "portDeclIn.png")
  def portDeclOut = ImageDescriptor.createFromFile(classOf[Icons], "portDeclOut.png")
  def portDeclShift = ImageDescriptor.createFromFile(classOf[Icons], "portDeclShift.png")
  def buttonIfTrue = ImageDescriptor.createFromFile(classOf[Icons], "buttonIfTrue.png")
  def buttonIfFalse = ImageDescriptor.createFromFile(classOf[Icons], "buttonIfFalse.png")

  def portImg(dir: PortDir) = dir match {
    case In    ⇒ rm.createImage(portDeclIn)
    case Out   ⇒ rm.createImage(portDeclOut)
    case Shift ⇒ rm.createImage(portDeclShift)
  }
  def iconForPalette(p: PaletteEntry): Image = {
    p match {
      case PaletteEntry.InEntry    ⇒ portImg(In)
      case PaletteEntry.OutEntry   ⇒ portImg(Out)
      case PaletteEntry.ShiftEntry ⇒ portImg(Shift)
      case _                       ⇒ loadOrText(None, p.className)
    }
  }

  def iconTpe(tpe: Option[Type], min: Int): Image = {
    tpe match {
      case Some(c: ClassJavaType) ⇒ loadOrText(None, c.fqName, min)
      case Some(t: ExprType)      ⇒ loadOrText(None, t.fqName, min)
      case _                      ⇒ imageForText("<missing>", ColorConstants.red, min)
    }
  }
  def iconMethod(m: MethodBinding, min: Int, txt: String): Image =
    loadKey(ImageKey.fromMethod(m)) getOrElse {
      imageForText(txt, ColorConstants.black, min)
    }
  def imageKey(imageKey: ImageKey, minY: Int): Image =
    loadKey(imageKey) getOrElse { textIcon(imageKey.className, minY) }
  def textIcon(str: String, min: Int) = imageForText(str, ColorConstants.blue, min)
  def textIconError(str: String, min: Int) = imageForText(str, ColorConstants.red, min)
  def folderIcon(pkg: String) =
    resourceToImage(pkg.replace('.', '/') + "/package.png") getOrElse {
      rm.create(FolderIconImageDescriptor(pkg)).asInstanceOf[Image]
    }
  private def loadKey(imageKey: ImageKey): Option[Image] = {
    for {
      url ← imageMap.findImage(imageKey);
      i ← loadDesc(urlToDescriptor(url))
    } yield { i }
  }
  private def loadImage(imageName: Option[String], name: Name): Option[Image] = {
    imageName.flatMap { imgName ⇒ resourceToImage(imgName) }.
      orElse { loadKey(ImageKey(name.str, None, None)) }
  }
  private def loadOrText(imageName: Option[String], name: Name, min: Int = 48) = {
    loadImage(imageName, name) getOrElse (
      imageForText(name.classNameWithoutPackage.str, ColorConstants.black, min))
  }
  def imageForText(txt: String, color: Color, min: Int = 12) = {
    val desc = GeneratedTextIconImageDescriptor(txt, min, color)
    rm.create(desc).asInstanceOf[Image]
  }
  // Can't figure a way to create an image with a transparency mask that can be manipulated with GC
  // images must be composed on place
  /*case class GeneratedImageIconImageDescriptor(desc: ImageDescriptor, minY: Int) extends DeviceResourceDescriptor {
    def createResource(device: Device): Object = {
      val nestedImg = image(desc)
      val nestedY = nestedImg.getBounds().height
      val nestedX = nestedImg.getBounds().width
      val y = if (nestedY >= minY) 0 else (minY - nestedY) / 2
      val height = math.max(nestedY, minY)
      val tr = rm.createImage(trans1x1)
      val img = new Image(device, tr.getImageData().scaledTo(nestedY, height), tr.getImageData().getTransparencyMask().scaledTo(nestedY, height))
      val gc = new GC(img)
      gc.drawLine(0, 0, 48, 48)
      //gc.drawImage(nestedImg, 0, y)
      /*if (nestedY < minY) {
        gc.setForeground(ColorConstants.gray)
        gc.drawRectangle(0, 0, nestedX - 1, height - 1)
      }*/
      destroy(desc)
      gc.dispose
      img
    }

    def destroyResource(previouslyCreatedObject: Object) {
      previouslyCreatedObject.asInstanceOf[Image].dispose();
    }
  }*/
  trait MyImageDeviceResourceDescriptor extends DeviceResourceDescriptor {
    protected def createTextImage(text: String, device: Device, ySize: Int)(body: (Int, GC, TextLayout) ⇒ Unit) = {
      val font = Activator.getDefault.generatedIconFont
      val t = new TextLayout(device)
      t.setText(text)
      t.setAlignment(SWT.CENTER)
      t.setFont(font)
      t.setWidth(47)
      val textY = t.getBounds.height
      val finalY = math.max(ySize, textY)
      val img = new Image(device, 48, finalY);
      val gc = new GC(img)
      body(finalY, gc, t)
      t.dispose()
      gc.dispose()
      img
    }
    def destroyResource(previouslyCreatedObject: Object) {
      previouslyCreatedObject.asInstanceOf[Image].dispose();
    }

  }
  case class FolderIconImageDescriptor(text: String) extends MyImageDeviceResourceDescriptor {
    def createResource(device: Device): Object = {
      createTextImage(text, device, 48) { (finalY, gc, t) ⇒
        gc.setForeground(ColorConstants.gray)
        gc.drawRectangle(0, 0, 47, finalY - 1)
        t.draw(gc, 0, math.max(0, (finalY - 1 - t.getBounds.height) / 2))
        gc.setAntialias(SWT.ON)
        gc.setBackground(ColorConstants.gray)
        //gc.fillPolygon(Array(40, 0, 47, 8, 40, 8))
        gc.fillPolygon(Array(41, 2, 46, 5, 41, 8))

      }
    }
  }
  case class GeneratedTextIconImageDescriptor(text: String, ySize: Int, color: Color) extends MyImageDeviceResourceDescriptor {
    def createResource(device: Device): Object = {
      createTextImage(text, device, ySize) { (finalY, gc, t) ⇒
        gc.setForeground(color)
        gc.drawRectangle(0, 0, 47, finalY - 1)
        t.draw(gc, 0, math.max(0, (finalY - 1 - t.getBounds.height) / 2))
      }
    }

  }
}
