package org.zaluum.nide
import org.eclipse.jface.resource.ImageRegistry
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.osgi.framework.BundleContext
import org.eclipse.core.runtime.Platform
import org.eclipse.core.runtime.FileLocator
import org.eclipse.core.runtime.Path
import org.osgi.framework.Bundle
import org.eclipse.core.runtime.Status
import scala.util.control.ControlThrowable
import org.eclipse.core.runtime.IStatus
import java.net.URL
import org.eclipse.core.runtime.IPath
import org.eclipse.osgi.service.resolver.BundleDescription
import scala.collection.mutable.Buffer

object Activator {
  val PLUGIN_ID: String = "org.zaluum.nide"
  var plugin: Activator = _
  def logError(s: String, a: AnyRef) {
    println("ERROR" + s + " " + a)
  }
  def getDefault(): Activator = {
    return plugin;
  }
  val NameExtractor = """.*/([^_^/^\\]+)_.*""".r
}

class Activator extends AbstractUIPlugin {
  def pluginId = Activator.PLUGIN_ID
  override def start(context: BundleContext) = {
    super.start(context);
    Activator.plugin = this;

  }

  protected def urlToPath(o: Option[URL]) = o map { x ⇒ Path.fromOSString(FileLocator.toFileURL(x).getPath) }
  def urlForBundleName(bundleName: String) = {
    val bundle = Platform.getBundle(bundleName)
    if (bundle != null) {
      if (bundle.getEntry("/bin/") != null) { // detect if it's a project dir or a jar
        // it is a directory
        Option(bundle.getEntry("/bin/")) /* XXX looks like src dir is silently unsupported Some(bundle.getEntry("/src/"))*/ // dir
      } else {
        // it's a jar.
        if (bundle.getLocation.startsWith("reference:file:")) { // try to find the .jar file
          // FIXME works in windows???
          val url = new URL(bundle.getLocation.drop("reference:".length))
          Option(url)
        } else {
          Option(bundle.getEntry("/"))
        }
      }
    } else None
  }
  def libEntries = {
    if (System.getProperty("zaluum.noEmbeddedClasspath") != null) {
      val l:List[(IPath,Option[IPath])] = 
        List(urlForBundleName("org.zaluum.runtime")) flatMap { urlToPath(_) } map { (_,None)}
      l
    }else {
      embeddedLib
    }
  }
  def embeddedLib : List[(IPath, Option[IPath])]= {
    val path = "embedded-libs/plugins"
    val paths = nideBundle.getEntryPaths(path)
    import scala.collection.JavaConversions._
    val bins = Buffer[String]()
    val srcs = Buffer[String]()
    for (p ← paths.asInstanceOf[java.util.Enumeration[String]]) {
      if (p.contains(".source")) srcs += p
      else bins += p
    }
    val result = Buffer[(IPath, Option[IPath])]()
    for (bin ← bins) {
      bin match {
        case Activator.NameExtractor(name) ⇒
          val src = srcs.find { _.contains(name + ".source") }
          def stringToPath(p: String) = urlToPath(Option(nideBundle.getEntry(p)))
          stringToPath(bin) match {
            case Some(p) => 
              result += ((p,src flatMap { s ⇒ stringToPath(s) }))
            case None =>
          }
        case _ ⇒
      }
    }
    println(result)
    result.toList
  }
  def version = "1.0.0"
  val zaluumLib = "ZALUUM_CONTAINER"
  val zaluumLibId = "org.zaluum." + zaluumLib
  val nideBundle = Platform.getBundle("org.zaluum.nide")

  override def initializeImageRegistry(reg: ImageRegistry) = {
    super.initializeImageRegistry(reg);
    /*Utils.loadIcons(reg,classOf[Icons],"zaluum","stickynote", "composed", "const", "value", "instance", "portin", "portout", "wire");
    Utils.loadImage(reg,"portInD", classOf[Icons]);
    Utils.loadImage(reg,"portInND", classOf[Icons]);
    Utils.loadImage(reg,"portOutND", classOf[Icons]);*/
  }

  override def getImageRegistry(): ImageRegistry = {
    return super.getImageRegistry();
  }
  def logError(t: Throwable): Unit = logError(t.getClass + ":" + t.getMessage, t)

  def logError(msg: String, t: Throwable): Unit = {
    val t1 = if (t != null) t else { val ex = new Exception; ex.fillInStackTrace; ex }
    val status1 = new Status(IStatus.ERROR, pluginId, IStatus.ERROR, msg, t1)
    getLog.log(status1)

    val status = t match {
      case ce: ControlThrowable ⇒
        val t2 = { val ex = new Exception; ex.fillInStackTrace; ex }
        val status2 = new Status(
          IStatus.ERROR, pluginId, IStatus.ERROR,
          "Incorrectly logged ControlThrowable: " + ce.getClass.getSimpleName + "(" + ce.getMessage + ")", t2)
        getLog.log(status2)
      case _ ⇒
    }
  }

  final def check[T](f: ⇒ T) =
    try {
      Some(f)
    } catch {
      case e: Throwable ⇒
        logError(e)
        None
    }
}
