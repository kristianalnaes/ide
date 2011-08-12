package org.zaluum.nide.eclipse.integration.model

import scala.collection.mutable.Map
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration
import org.eclipse.jdt.internal.compiler.lookup.ArrayBinding
import org.eclipse.jdt.internal.compiler.lookup.BaseTypeBinding
import org.eclipse.jdt.internal.compiler.lookup.CompilationUnitScope
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding
import org.eclipse.jdt.internal.compiler.lookup.MissingTypeBinding
import org.eclipse.jdt.internal.compiler.lookup.ProblemReferenceBinding
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding
import org.eclipse.jdt.internal.compiler.lookup.Scope
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding
import org.eclipse.jdt.internal.compiler.lookup.UnresolvedReferenceBinding
import org.zaluum.nide.compiler.ArrayType
import org.zaluum.nide.compiler.BoxTypeSymbol
import org.zaluum.nide.compiler.ClassJavaType
import org.zaluum.nide.compiler.Constructor
import org.zaluum.nide.compiler.In
import org.zaluum.nide.compiler.JavaType
import org.zaluum.nide.compiler.Name
import org.zaluum.nide.compiler.NoSymbol
import org.zaluum.nide.compiler.Out
import org.zaluum.nide.compiler.ParamSymbol
import org.zaluum.nide.compiler.Point
import org.zaluum.nide.compiler.PortSymbol
import org.zaluum.nide.compiler.PrimitiveJavaType
import org.zaluum.nide.compiler.{ Scope ⇒ ZScope }
import org.zaluum.nide.compiler.Type
import org.zaluum.nide.compiler.primitives
import JDTInternalUtils.aToString
import JDTInternalUtils.stringToA
import org.zaluum.nide.compiler.ZaluumCompletionEngineScala
import org.eclipse.jdt.internal.compiler.impl.StringConstant
import org.zaluum.nide.compiler.PortDir
import org.eclipse.jdt.internal.compiler.lookup.AnnotationBinding
class ZaluumCompilationUnitScope(cudp: ZaluumCompilationUnitDeclaration, lookupEnvironment: LookupEnvironment) extends CompilationUnitScope(cudp, lookupEnvironment) with ZScope {
  override protected def buildClassScope(parent: Scope, typeDecl: TypeDeclaration) = {
    new ZaluumClassScope(parent, typeDecl)
  }
  def getExpectedPackageName = this.referenceContext.compilationResult.compilationUnit.getPackageName();
  val cache = Map[Name, BoxTypeSymbol]()
  private val cacheJava = Map[TypeBinding, ClassJavaType]()

  def cud = referenceContext.asInstanceOf[ZaluumCompilationUnitDeclaration]
  def name = Name("root")
  def owner = null
  def getBoxedType(p: PrimitiveJavaType): JavaType =
    getJavaType(p.boxedName).get

  def getZJavaLangString = getJavaType(Name("java.lang.String")).get;
  def javaScope: ZaluumCompilationUnitScope = this
  def lookupType(name: Name): Option[Type] = getJavaType(name)
  def getArrayType(t: JavaType, dim: Int): ArrayType = {
    val bind = createArrayType(t.binding, dim)
    val a = new ArrayType(this, t, dim)
    a.binding = bind
    a
  }
  def getJavaType(name: Name): Option[JavaType] = {
    val arr = name.asArray
    if (arr.isDefined) {
      val (leafname, dim) = arr.get
      getJavaType(leafname) map { t ⇒
        getArrayType(t, dim)
      }
    } else {
      val tpe =
        if (name.str.contains(".")) {
          val compoundName = stringToA(name.str)
          getType(compoundName, compoundName.length)
        } else {
          getType(name.str.toCharArray)
        }
      Some(getJavaType(tpe)) //FIXME
    }
  }
  def getJavaType(tpe: TypeBinding): JavaType = {
    tpe match {
      case m: MissingTypeBinding         ⇒ NoSymbol
      case p: ProblemReferenceBinding    ⇒ NoSymbol
      case u: UnresolvedReferenceBinding ⇒ NoSymbol
      case r: ReferenceBinding ⇒
        val tpe = lookupEnvironment.convertToRawType(r, false).asInstanceOf[ReferenceBinding]
        cacheJava.getOrElseUpdate(tpe, {
          val jtpe = new ClassJavaType(this, Name(aToString(tpe.compoundName)))
          jtpe.binding = tpe
          jtpe
        })
      case b: BaseTypeBinding ⇒
        b.simpleName.mkString match {
          case "byte"    ⇒ primitives.Byte
          case "short"   ⇒ primitives.Short
          case "int"     ⇒ primitives.Int
          case "long"    ⇒ primitives.Long
          case "float"   ⇒ primitives.Float
          case "double"  ⇒ primitives.Double
          case "boolean" ⇒ primitives.Boolean
          case "char"    ⇒ primitives.Char
          //case _ ⇒ None
        }
      case a: ArrayBinding ⇒
        val leaf = getJavaType(a.leafComponentType)
        val t = new ArrayType(this, leaf, a.dimensions)
        t.binding = a
        t
    }
  }

  def allFieldsFor(r: ReferenceBinding): List[FieldBinding] = {
    r.fields.toList ++ { if (r.superclass != null) allFieldsFor(r.superclass) else List() }
  }
  def allMethodsFor(r: ReferenceBinding): List[MethodBinding] = {
    r.methods.toList ++ { if (r.superclass != null) allMethodsFor(r.superclass) else List() }
  }

  def lookupBoxType(name: Name): Option[BoxTypeSymbol] = {
    cache.get(name).orElse {
      val compoundName = stringToA(name.str)
      getType(compoundName, compoundName.length) match {
        case r: ReferenceBinding ⇒ generate(r)
        case a                   ⇒ None
      }
    }
  }
  protected def generate(r: ReferenceBinding) = {
    val srcName = Name(r.compoundName.last.mkString)
    val pkgName = Name(r.qualifiedPackageName.mkString)
    val bs = new BoxTypeSymbol(
      srcName, pkgName,
      None, None, r.isAbstract)
    bs.scope = this
    /*
     TODO use this. We need a ClassScope, so move this to ZaluumClassScope
     val engine = new ZaluumCompletionEngine(environment)
     ZaluumCompletionEngineScala.allMethods(engine, null, r, false)
    */
    for (m ← allMethodsFor(r)) processMethod(bs, m)
    for (f ← allFieldsFor(r); if f.isPublic && !f.isStatic) processField(bs, f)
    if (bs.constructors.isEmpty)
      bs.constructors = List(new Constructor(bs, List()))
    bs.binding = r
    cache += (name -> bs)
    Some(bs)
  }
  private def processMethod(bs: BoxTypeSymbol, m: MethodBinding) {
    val annotation = m.getAnnotations.find { a ⇒
      aToString(a.getAnnotationType.compoundName) == classOf[org.zaluum.annotation.Apply].getName
    }
    val mName = m.selector.mkString
    if (m.isConstructor && m.isPublic) {
      doConstructor(bs, m)
    } else {
      if (mName.startsWith("set") && m.parameters.size == 1 && m.returnType == TypeBinding.VOID)
        doParam(bs, m)
      else if (annotation.isDefined && !m.isStatic && !m.isAbstract && m.isPublic && !bs.hasApply)
        doApply(bs, m, annotation)
    }
  }
  private def processField(bs: BoxTypeSymbol, f: FieldBinding) {
    val fname = f.name.mkString
      def hasAnnotation(c: Class[_]) = f.getAnnotations.exists { a ⇒
        aToString(a.getAnnotationType.compoundName) == c.getName
      }
    if (hasAnnotation(classOf[org.zaluum.annotation.Out]))
      createPort(bs, Name(fname), f.`type`, Out, field = true)
    if (fname == "_widget") {
      f.`type` match {
        case r: ReferenceBinding ⇒
          bs.visualClass = Some(Name(aToString(r.compoundName)))
        case _ ⇒
      }
    }
  }
  private def createPort(bs: BoxTypeSymbol, name: Name, tpe: TypeBinding, dir: PortDir, field: Boolean = false, helperName:Option[Name] = None) {
    val port = new PortSymbol(bs, name, helperName, Point(0, 0), dir, field)
    port.tpe = getJavaType(tpe)
    bs.ports += (port.name -> port)
  }
  private def doConstructor(bs: BoxTypeSymbol, m: MethodBinding) {
    val names = numericNames(m)
    val params = for ((p, i) ← m.parameters zipWithIndex) yield {
      val ps = new ParamSymbol(bs, Name(names(i))) // helper name
      ps.tpe = getJavaType(p)
      ps
    }
    bs.constructors = new Constructor(bs, params.toList) :: bs.constructors
  }
  private def doParam(bs: BoxTypeSymbol, m: MethodBinding) {
    val mName = m.selector.mkString
    val ptpe = getJavaType(m.parameters.head)
    val p = new ParamSymbol(bs, Name(mName))
    p.tpe = ptpe
    bs.params += (p.name -> p)
  }
  private def doApply(bs: BoxTypeSymbol, m: MethodBinding, annotation: Option[AnnotationBinding]) {
    val argumentNames = annotatedParameters(bs, m, annotation)
    val helpers = helperNames(m)
    val nums = numericNames(m)
    for ((p, i) ← m.parameters zipWithIndex) {
      val (name,hName) = argumentNames match {
        case Some(l) => (l(i),None)
        case None => helpers match {
          case Some(h) => (nums(i),Some(h(i)))
          case None => (nums(i),None)
        }
      }
      createPort(bs, Name(name), p, In, helperName=hName.map{Name(_)})
    }
    m.returnType match {
      case TypeBinding.VOID ⇒ //skip return 
      case r                ⇒ createPort(bs, Name(m.selector.mkString), r, Out)
    }
  }
  private def annotatedParameters(bs: BoxTypeSymbol, m: MethodBinding, annotation: Option[AnnotationBinding]) = {
      def arrOption(a: Any) = a match {
        case a: Array[Object] ⇒ Some(a)
        case _                ⇒ None
      }
      def stringConstant(a: Object) = a match {
        case s: StringConstant ⇒ Some(s.stringValue())
      }
    bs.hasApply = true
    val arrValues = for (
      a ← annotation;
      pair ← a.getElementValuePairs.find { _.getName.mkString == "paramNames" };
      arr ← arrOption(pair.getValue)
    ) yield { arr }
    val names = arrValues.map { arr ⇒
      for (
        component ← arr.toList;
        str ← stringConstant(component)
      ) yield str
    }
    names match {
      case Some(l) if l.size == m.parameters.size ⇒ Some(l)
      case _                                      ⇒ None
    }
  }
  private def numericNames(m: MethodBinding) =
    (1 to m.parameters.length) map { i ⇒ "p" + i } toList
  private def helperNames(m: MethodBinding) =
    MethodUtils.findMethodParameterNamesEnv(m, environment.nameEnvironment).map(_.toList)
  
  
}