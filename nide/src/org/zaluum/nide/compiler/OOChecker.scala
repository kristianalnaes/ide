package org.zaluum.nide.compiler
import org.eclipse.jdt.internal.compiler.lookup.ProblemMethodBinding
import org.eclipse.jdt.internal.compiler.lookup.FieldBinding
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding
import org.eclipse.jdt.internal.compiler.lookup.ProblemFieldBinding
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding

class OOChecker(val c: CheckConnections) extends CheckerPart {
  /*
     * helpers for methods and fields
     */
  def processField(vs: ValSymbol, f: Option[FieldBinding])(body: (ValSymbol, FieldBinding) ⇒ Unit) = f match {
    case Some(p: ProblemFieldBinding) ⇒ error("problem field " + p + p.problemId(), vs.decl)
    case Some(f: FieldBinding)        ⇒ body(vs, f); vs.info = f
    case None                         ⇒ error("field not found", vs.decl)
  }
  def processGet(vs: ValSymbol, f: Option[FieldBinding]) {
    processField(vs, f) { (vs, f) ⇒
      val out = vs.tpe.asInstanceOf[ResultExprType].outPort(vs)
      out.finalTpe = cud.zaluumScope.getJavaType(f.`type`)
      if (out.finalTpe == NoSymbol) error("field type not found", vs.decl)
    }
  }
  def processPut(vs: ValSymbol, f: Option[FieldBinding]) {
    processField(vs, f) { (vs, f) ⇒
      val a = vs.tpe.asInstanceOf[OneParameter].aPort(vs)
      a.finalTpe = cud.zaluumScope.getJavaType(f.`type`)
      if (a.finalTpe == NoSymbol) error("field type not found", vs.decl)
    }
  }
  def processMethod(vs: ValSymbol, m: Option[MethodBinding])(body: MethodBinding ⇒ Unit) = m match {
    case Some(p: ProblemMethodBinding) ⇒
      error("problem method " + p + p.problemId(), vs.decl)
    case Some(m) ⇒
      if (m.returnType != null && m.returnType != TypeBinding.VOID) {
        val out = vs.portInstances find { _.name == Name("return") } getOrElse { vs.createOutsideOut(Name("return")).pi }
        out.missing = false
        out.finalTpe = cud.zaluumScope.getJavaType(m.returnType)
        if (out.finalTpe == NoSymbol) error("return type not found", vs.decl)
      }
      for ((p, i) ← m.parameters.zipWithIndex) {
        val name = Name("p" + i)
        val in = vs.portInstances find { _.name == name } getOrElse { vs.createOutsideIn(Name("p" + i)).pi }
        in.missing = false
        in.finalTpe = cud.zaluumScope.getJavaType(p);
      }
      vs.info = m
      body(m)
    case None ⇒
      error("method not found", vs.decl)
  }
  /*
     *  statics
     */
  def checkStaticExprType(vs: ValSymbol) {
    val tpe = vs.tpe.asInstanceOf[StaticExprType]
    vs.params.get(tpe.classSymbol) match {
      case Some(className: String) ⇒
        cud.zaluumScope.getJavaType(Name(className)) match {
          case Some(c: ClassJavaType) ⇒
            vs.classinfo = c
            tpe match {
              case NewExprType            ⇒ checkNew(vs, c)
              case InvokeStaticExprType   ⇒ invokeStatic(vs, c)
              case GetStaticFieldExprType ⇒ processStaticField(vs, c)(processGet)
              case PutStaticFieldExprType ⇒ processStaticField(vs, c)(processPut)
            }
          case _ ⇒ error("Class " + className + " not found", vs.decl)
        }
      case None ⇒ error("no class specified", vs.decl)
    }
  }
  def checkNew(vs: ValSymbol, c: ClassJavaType) {
    if (!c.binding.canBeInstantiated()) {
      error("Class " + c.name.str + " cannot be instantiated", vs.decl);
    }
    vs.params.get(NewExprType.signatureSymbol) match {
      case Some(NewExprType.Sig(name, signature)) ⇒
        val cons = ZaluumCompletionEngineScala.findConstructor(cud, scope(vs), c.binding, signature)
        processMethod(vs, cons) { m ⇒
          NewExprType.thisPort(vs).finalTpe = cud.zaluumScope.getJavaType(m.declaringClass)
        }
      case _ ⇒ error("No constructor specified", vs.decl) // XXdefault?
    }
  }
  def invokeStatic(vs: ValSymbol, c: ClassJavaType) {
    vs.params.get(InvokeStaticExprType.signatureSymbol) match {
      case Some(InvokeStaticExprType.Sig(selector, signature)) ⇒
        val m = ZaluumCompletionEngineScala.findBySignature(cud, scope(vs), c, selector, signature, true)
        processMethod(vs, m) { _ ⇒ }
      case _ ⇒ error("Static method not specified", vs.decl)
    }
  }
  def processStaticField(vs: ValSymbol, c: ClassJavaType)(body: (ValSymbol, Option[FieldBinding]) ⇒ Unit) {
    val tpe = vs.tpe.asInstanceOf[SignatureExprType]
    vs.params.get(tpe.signatureSymbol) match {
      case Some(fieldName: String) ⇒
        val f = ZaluumCompletionEngineScala.findField(cud, scope(vs), c.binding, fieldName, true)
        withSigField(vs, c)(body)
      case _ ⇒ error("Static field not specified", vs.decl)
    }
  }

  /*
     * this
     */
  def checkThisExprType(vs: ValSymbol) {
    val tpe = vs.tpe.asInstanceOf[ThisExprType]
    val thiz = tpe.thisPort(vs)
    val thizOut = tpe.thisOutPort(vs)
    InvokeExprType.signatureSymbol.tpe = cud.zaluumScope.getZJavaLangString // XXX ugly
    connectedFrom(thiz) match {
      case Some((from, blame)) ⇒
        from.finalTpe match {
          case a: ArrayType => 
            thiz.finalTpe = a
            thizOut.finalTpe = a
            tpe match {
              case ArrayExprType => array(vs,a)
              case _=> error("Type must be a class", vs.decl)
            }
          case c: ClassJavaType ⇒
            thiz.finalTpe = c
            thizOut.finalTpe = c
            tpe match {
              case InvokeExprType ⇒ invoke(vs, c)
              case GetFieldExprType ⇒ withSigField(vs, c)(processGet)
              case PutFieldExprType ⇒ withSigField(vs, c)(processPut)
              case ArrayExprType => error("Type must be array",vs.decl)
            }
          case _ ⇒
            error("bad type", blame)
        }
      case None ⇒ // not connected
    }
  }
  def array(vs:ValSymbol, a:ArrayType) {
    val index = ArrayExprType.indexPort(vs)
    val thisPort = ArrayExprType.thisPort(vs)
    val thisOutPort =ArrayExprType.thisOutPort(vs)
    val aPort = ArrayExprType.aPort(vs)
    val oPort = ArrayExprType.outPort(vs)
    index.finalTpe = primitives.Int
    val tpe = a.dim match {
      case 1 => a.of
      case i if (i>1) => cud.zaluumScope.getArrayType(a.of,i-1)
    }
    aPort.finalTpe = tpe
    oPort.finalTpe = tpe
    thisPort
  }
  def withSigField(vs: ValSymbol, c: ClassJavaType)(body: (ValSymbol, Option[FieldBinding]) ⇒ Unit) {
    val tpe = vs.tpe.asInstanceOf[SignatureExprType]
    vs.params.get(tpe.signatureSymbol) match {
      case Some(fieldName: String) ⇒
        val f = ZaluumCompletionEngineScala.findField(cud, scope(vs), c.binding, fieldName, false)
        body(vs, f)
      case _ ⇒ error("no field specified", vs.decl)
    }
  }
  def invoke(vs: ValSymbol, c: ClassJavaType) {
    vs.params.get(InvokeExprType.signatureSymbol) match {
      case Some(InvokeExprType.Sig(selector, signature)) ⇒
        val m = ZaluumCompletionEngineScala.findBySignature(cud, scope(vs), c, selector, signature, false)
        processMethod(vs, m) { _ ⇒ }
      case _ ⇒ error("signature missing", vs.decl)
    }
  }
  /*
     * expressions with templates
     */
  def checkTemplateExprType(vs: ValSymbol) = { // FIXME share code with While
    val t = vs.tpe.asInstanceOf[TemplateExprType]
    vs.tdecl.template match {
      case Some(template) ⇒
        if (template.blocks.size != t.requiredBlocks)
          error(t.name.classNameWithoutPackage + " must have " + t.requiredBlocks + " blocks", vs.decl) // FIXME tolerate
        else {
          for (pi ← vs.portInstances; ps ← pi.portSymbol) {
            pi.finalTpe = ps.tpe
          }
          t.ports.values foreach { ps ⇒
            val pi = vs.findPortInstance(ps).get
            pi.finalTpe = primitives.Boolean
          }
          template.blocks.foreach { b ⇒
            new CheckConnections(b, false, c.analyzer).run()
          }
        }
      case None ⇒
        error("Fatal no template for template expression", vs.decl)
    }
    c.checkPortConnectionsTypes(vs)
  }
}