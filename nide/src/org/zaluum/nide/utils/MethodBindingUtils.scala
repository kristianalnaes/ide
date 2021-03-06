package org.zaluum.nide.utils
import org.eclipse.jdt.core.IJavaProject
import org.eclipse.jdt.core.JavaModelException
import org.eclipse.jdt.internal.compiler.ast.ASTNode
import org.eclipse.jdt.internal.compiler.env.INameEnvironment
import org.eclipse.jdt.internal.compiler.lookup.Binding
import org.eclipse.jdt.internal.compiler.lookup.ExtraCompilerModifiers
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding
import org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding
import org.eclipse.jdt.internal.core.SourceMethod
import org.eclipse.jdt.internal.core.SourceMethodElementInfo
import org.eclipse.jdt.internal.core.SourceTypeElementInfo
import org.objectweb.asm.util.ASMifiable
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor
import scala.collection.mutable.Buffer
import org.zaluum.nide.compiler.primitives
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding
import org.eclipse.jdt.internal.compiler.lookup.ArrayBinding
import org.eclipse.jdt.internal.compiler.lookup.BaseTypeBinding
import java.lang.reflect.Constructor

object MethodBindingUtils {
  def typeToClass(t: TypeBinding, cl: ClassLoader): Option[Class[_]] = {
    if (t.isValidBinding) {
      try {
        t.erasure match {
          case a: ArrayBinding ⇒
            Some(Class.forName(a.constantPoolName.mkString.replace("/", "."), false, cl))
          case b: BaseTypeBinding ⇒
            primitives.fromChar(b.constantPoolName()(0)).loadClass(cl)
          case o: ReferenceBinding ⇒
            val name = o.compoundName.map(_.mkString).mkString(".")
            Some(Class.forName(name, false, cl))
        }
      } catch { case e: ClassNotFoundException ⇒ e.printStackTrace; None }
    } else None
  }
  def getConstructor(cons: MethodBinding, cl: ClassLoader): Option[Constructor[_]] = {
    val paramClasses = cons.parameters.map(typeToClass(_, cl))
    if (paramClasses.forall(_.isDefined)) {
      typeToClass(cons.declaringClass, cl) flatMap { clazz ⇒
        val arr = paramClasses.map(_.get).toArray
        try {
          Some(clazz.getConstructor(arr: _*))
        } catch { case e: NoSuchMethodException ⇒ None }
      }
    } else None
  }

  def findMethodParamNames(m: MethodBinding, javaProject: IJavaProject) = {
    val e = m.declaringClass.erasure()
    val tpeName = e.qualifiedPackageName.mkString + "." + e.qualifiedSourceName().mkString
    try {
      val tpe = javaProject.findType(tpeName)
      tpe.getMethods() find { im ⇒
        (if (m.isConstructor())
          im.isConstructor()
        else im.getElementName == m.selector.mkString) && im.getSignature == m.signature.mkString
      } map { meth ⇒
        meth.getParameterNames()
      }
    } catch { case j: JavaModelException ⇒ None }
  }

  def toMethodStr(m: MethodBinding, paramNames: List[String]) = {
    val output = new StringBuffer(10);
    if ((m.modifiers & ExtraCompilerModifiers.AccUnresolved) != 0) {
      output.append("[unresolved] "); //$NON-NLS-1$
    }
    ASTNode.printModifiers(m.modifiers, output);
    output.append(if (m.returnType != null) m.returnType.debugName() else "<no type>");
    output.append(" "); //$NON-NLS-1$
    output.append(if (m.selector != null) new String(m.selector) else "<no selector>");
    output.append("("); //$NON-NLS-1$
    if (m.parameters != null) {
      if (m.parameters != Binding.NO_PARAMETERS) {
        val padded = paramNames.padTo(m.parameters.length, "?")
        val zip = padded.zip(m.parameters)
        val str = zip.map {
          case (name, p) ⇒
            if (p != null) p.debugName() + " " + name else "<no argument type>"
        } mkString (", ")
        output.append(str); //$NON-NLS-1$
      }
    } else {
      output.append("<no argument types>"); //$NON-NLS-1$
    }
    output.append(") "); //$NON-NLS-1$

    if (m.thrownExceptions != null) {
      if (m.thrownExceptions != Binding.NO_EXCEPTIONS) {
        output.append("throws "); //$NON-NLS-1$
        val s = m.thrownExceptions.map { t ⇒
          if (t != null) t.debugName() else "<no exception type>"
        }.mkString(", ")
        output.append(s)
      }
    } else {
      output.append("<no exception types>"); //$NON-NLS-1$
    }
    output.toString();
  }
  private def findMethodParameterNamesSource(m: MethodBinding, sourceType: SourceTypeBinding): Option[Array[String]] = {
    if (sourceType.scope != null) {
      val parsedType = sourceType.scope.referenceContext
      if (parsedType != null) {
        val methodDecl = parsedType.declarationOf(m.original());
        if (methodDecl != null) {
          val arguments = methodDecl.arguments;
          if (arguments != null) {
            val names = for (a ← arguments) yield { a.name.mkString }
            if (names.size == m.parameters.size)
              return Some(names)
          }
        }
      }
    }
    None
  }

  private def findMethodParameterNamesBinaryEnv(m: MethodBinding,
                                                rb: ReferenceBinding, nameEnvironment: INameEnvironment): Option[Array[String]] = {
    val tentative = nameEnvironment.findType(rb.compoundName) match {
      case null ⇒ None
      case answer if answer.isSourceType && answer.getSourceTypes()(0) != null ⇒
        val sourceType = answer.getSourceTypes()(0);
        val typeHandle = sourceType.asInstanceOf[SourceTypeElementInfo].getHandle();
        val signature = for (e ← m.parameters) yield {
          e.signature.mkString
        }
        val searchedMethod = typeHandle.getMethod(String.valueOf(m.selector), signature);
        val foundMethods = typeHandle.findMethods(searchedMethod);
        if (foundMethods != null && foundMethods.length == 1) {
          try {
            val names = foundMethods(0).asInstanceOf[SourceMethod]
              .getElementInfo.asInstanceOf[SourceMethodElementInfo]
              .getArgumentNames().map { _.mkString }
            if (names.size == m.parameters.size) Some(names) else None
          } catch { case e: JavaModelException ⇒ None }
        } else None
      case answer if answer.isBinaryType ⇒
        answer.getBinaryType.getMethods.find { candidate ⇒
          candidate.getSelector.mkString == m.selector.mkString &&
            candidate.getMethodDescriptor.mkString == m.signature.mkString
        } map { foundM ⇒ foundM.getArgumentNames map { _.mkString } }
    }
    tentative match {
      case Some(res) ⇒ if (res.size == m.parameters.size) tentative else None
      case _         ⇒ tentative
    }
  }

  private def findMethodParams(m: MethodBinding, binFunc: ReferenceBinding ⇒ Option[Array[String]]): Option[Array[String]] = {
    val erasure = m.declaringClass.erasure();
    erasure match {
      case sourceType: SourceTypeBinding ⇒
        findMethodParameterNamesSource(m, sourceType)
      case rb: ReferenceBinding ⇒
        binFunc(rb)
      case _ ⇒ None
    }
  }

  def findMethodParameterNamesEnv(m: MethodBinding, nameEnvironment: INameEnvironment): Option[Array[String]] =
    findMethodParams(m, findMethodParameterNamesBinaryEnv(m, _, nameEnvironment))

}