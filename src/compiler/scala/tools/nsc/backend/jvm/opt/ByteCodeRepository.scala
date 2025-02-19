/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc
package backend.jvm
package opt


import scala.collection.JavaConverters._
import scala.collection.{concurrent, mutable}
import scala.reflect.internal.util.NoPosition
import scala.tools.asm
import scala.tools.asm.{Attribute, Type}
import scala.tools.asm.tree._
import scala.tools.nsc.backend.jvm.BTypes.InternalName
import scala.tools.nsc.backend.jvm.BackendReporting._
import scala.tools.nsc.backend.jvm.opt.BytecodeUtils._

/**
 * The ByteCodeRepository provides utilities to read the bytecode of classfiles from the compilation
 * classpath. Parsed classes are cached in the `classes` map.
 */
abstract class ByteCodeRepository extends PerRunInit {
  val postProcessor: PostProcessor

  import postProcessor.{bTypes, bTypesFromClassfile, callGraph}
  import bTypes._
  import frontendAccess.{backendReporting, backendClassPath, recordPerRunCache}

  /**
   * Contains ClassNodes and the canonical path of the source file path of classes being compiled in
   * the current compilation run.
   */
  val compilingClasses: concurrent.Map[InternalName, (ClassNode, String)] = recordPerRunCache(concurrent.TrieMap.empty)

  /**
  * Prevent the code repository from growing too large. Profiling reveals that the average size
  * of a ClassNode is about 30 kb. I observed having 17k+ classes in the cache, i.e., 500 mb.
  */
  private val maxCacheSize = 1500

  /**
   * Cache for parsed ClassNodes, an LRU or size maxCacheSize
   * For Java classes in mixed compilation, the map contains an error message: no ClassNode is
   * generated by the backend and also no classfile that could be parsed.
   * Note - although this is typed a mutable.Map, individual simple get and put operations are threadsafe as the
   * underlying data structure is synchronized.
   */
  val parsedClasses: mutable.Map[InternalName, Either[ClassNotFound, ClassNode]] = recordPerRunCache(LruMap[InternalName, Either[ClassNotFound, ClassNode]](maxCacheSize, true))

  /**
   * Contains the internal names of all classes that are defined in Java source files of the current
   * compilation run (mixed compilation). Used for more detailed error reporting.
   */
  private lazy val javaDefinedClasses = perRunLazy(this)(frontendAccess.javaDefinedClasses)

  def add(classNode: ClassNode, sourceFilePath: Option[String]) = sourceFilePath match {
    case Some(path) if path != "<no file>" => compilingClasses(classNode.name) = (classNode, path)
    case _                                 => parsedClasses(classNode.name) = Right(classNode)
  }

  private def parsedClassNode(internalName: InternalName): Either[ClassNotFound, ClassNode] = {
    parsedClasses.getOrElseUpdate(internalName, parseClass(internalName))
  }

  /**
   * The class node and source file path (if the class is being compiled) for an internal name. If
   * the class node is not yet available, it is parsed from the classfile on the compile classpath.
   */
  def classNodeAndSourceFilePath(internalName: InternalName): Either[ClassNotFound, (ClassNode, Option[String])] = {
    compilingClasses.get(internalName) match {
      case Some((c, p)) => Right((c, Some(p)))
      case _            => parsedClassNode(internalName).map((_, None))
    }
  }

  /**
   * The class node for an internal name. If the class node is not yet available, it is parsed from
   * the classfile on the compile classpath.
   */
  def classNode(internalName: InternalName): Either[ClassNotFound, ClassNode] = {
    compilingClasses.get(internalName) match {
      case Some((c, _)) => Right(c)
      case None         => parsedClassNode(internalName)
    }
  }

  /**
   * The field node for a field matching `name` and `descriptor`, accessed in class `classInternalName`.
   * The declaration of the field may be in one of the superclasses.
   *
   * @return The [[FieldNode]] of the requested field and the [[InternalName]] of its declaring
   *         class, or an error message if the field could not be found
   */
  def fieldNode(classInternalName: InternalName, name: String, descriptor: String): Either[FieldNotFound, (FieldNode, InternalName)] = {
    def fieldNodeImpl(parent: InternalName): Either[FieldNotFound, (FieldNode, InternalName)] = {
      classNode(parent) match {
        case Left(e)  => Left(FieldNotFound(name, descriptor, classInternalName, Some(e)))
        case Right(c) =>
          c.fields.asScala.find(f => f.name == name && f.desc == descriptor) match {
            case Some(f) => Right((f, parent))
            case None    =>
              if (c.superName == null) Left(FieldNotFound(name, descriptor, classInternalName, None))
              else fieldNode(c.superName, name, descriptor)
          }
      }
    }
    fieldNodeImpl(classInternalName)
  }

  /**
   * The method node for a method matching `name` and `descriptor`, accessed in class `ownerInternalNameOrArrayDescriptor`.
   * The declaration of the method may be in one of the parents.
   *
   * Note that the JVM spec performs method lookup in two steps: resolution and selection.
   *
   * Method resolution, defined in jvms-5.4.3.3 and jvms-5.4.3.4, is the first step and is identical
   * for all invocation styles (virtual, interface, special, static). If C is the receiver class
   * in the invocation instruction:
   *   1 find a matching method (name and descriptor) in C
   *   2 then in C's superclasses
   *   3 then find the maximally-specific matching superinterface methods, succeed if there's a
   *     single non-abstract one. static and private methods in superinterfaces are not considered.
   *   4 then pick a random non-static, non-private superinterface method.
   *   5 then fail.
   *
   * Note that for an `invokestatic` instruction, a method reference `B.m` may resolve to `A.m`, if
   * class `B` doesn't specify a matching method `m`, but the parent `A` does.
   *
   * Selection depends on the invocation style and is defined in jvms-6.5.
   *   - invokestatic: invokes the resolved method
   *   - invokevirtual / invokeinterface: searches for an override of the resolved method starting
   *     at the dynamic receiver type. the search procedure is basically the same as in resolution,
   *     but it fails at 4 instead of picking a superinterface method at random.
   *   - invokespecial: if C is the receiver in the invocation instruction, searches for an override
   *     of the resolved method starting at
   *       - the superclass of the current class, if C is a superclass of the current class
   *       - C otherwise
   *     again, the search procedure is the same.
   *
   * In the method here we implement method *resolution*. Whether or not the returned method is
   * actually invoked at runtime depends on the invocation instruction and the class hierarchy, so
   * the users (e.g. the inliner) have to be aware of method selection.
   *
   * Note that the returned method may be abstract (ACC_ABSTRACT), native (ACC_NATIVE) or signature
   * polymorphic (methods `invoke` and `invokeExact` in class `MethodHandles`).
   *
   * @return The [[MethodNode]] of the requested method and the [[InternalName]] of its declaring
   *         class, or an error message if the method could not be found. An error message is also
   *         returned if method resolution results in multiple default methods.
   */
  def methodNode(ownerInternalNameOrArrayDescriptor: String, name: String, descriptor: String): Either[MethodNotFound, (MethodNode, InternalName)] = {
    def findMethod(c: ClassNode): Option[MethodNode] = c.methods.asScala.find(m => m.name == name && m.desc == descriptor)

    // https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-2.html#jvms-2.9.3
    def findSignaturePolymorphic(owner: ClassNode): Option[MethodNode] = {
      def hasObjectArrayParam(m: MethodNode) = Type.getArgumentTypes(m.desc) match {
        case Array(pt) => pt.getDimensions == 1 && pt.getElementType.getInternalName == coreBTypes.ObjectRef.internalName
        case _ => false
      }
      // Don't try to build a BType for `VarHandle`, it doesn't exist on JDK 8
      if (owner.name == coreBTypes.jliMethodHandleRef.internalName || owner.name == "java/lang/invoke/VarHandle")
        owner.methods.asScala.find(m =>
          m.name == name &&
            isNativeMethod(m) &&
            isVarargsMethod(m) &&
            hasObjectArrayParam(m))
      else None
    }

    // Note: if `owner` is an interface, in the first iteration we search for a matching member in the interface itself.
    // If that fails, the recursive invocation checks in the superclass (which is Object) with `publicInstanceOnly == true`.
    // This is specified in jvms-5.4.3.4: interface method resolution only returns public, non-static methods of Object.
    def findInSuperClasses(owner: ClassNode, publicInstanceOnly: Boolean = false): Either[ClassNotFound, Option[(MethodNode, InternalName)]] = {
      findMethod(owner) match {
        case Some(m) if !publicInstanceOnly || (isPublicMethod(m) && !isStaticMethod(m)) => Right(Some((m, owner.name)))
        case _ =>
          findSignaturePolymorphic(owner) match {
            case Some(m) => Right(Some((m, owner.name)))
            case _ =>
              if (owner.superName == null) Right(None)
              else classNode(owner.superName).flatMap(findInSuperClasses(_, isInterface(owner)))
          }
      }
    }

    def findInInterfaces(initialOwner: ClassNode): Either[ClassNotFound, Option[(MethodNode, InternalName)]] = {
      val visited = mutable.Set.empty[InternalName]
      val found = mutable.ListBuffer.empty[(MethodNode, ClassNode)]

      def findIn(owner: ClassNode): Option[ClassNotFound] = {
        for (i <- owner.interfaces.asScala if !visited(i)) classNode(i) match {
          case Left(e) => return Some(e)
          case Right(c) =>
            visited += i
            // private and static methods are excluded, see jvms-5.4.3.3
            for (m <- findMethod(c) if !isPrivateMethod(m) && !isStaticMethod(m)) found += ((m, c))
            val recursionResult = findIn(c)
            if (recursionResult.isDefined) return recursionResult
        }
        None
      }

      def findSpecific = {
        val result =
          if (found.size <= 1) found.headOption
          else {
            val maxSpecific = found.filterNot({
              case (method, owner) =>
                val ownerTp = bTypesFromClassfile.classBTypeFromClassNode(owner)
                found exists {
                  case (other, otherOwner) =>
                    (other ne method) && {
                      val otherTp = bTypesFromClassfile.classBTypeFromClassNode(otherOwner)
                      otherTp.isSubtypeOf(ownerTp).get
                    }
                }
            })
            // (*) note that if there's no single, non-abstract, maximally-specific method, the jvm
            // method resolution (jvms-5.4.3.3) returns any of the non-private, non-static parent
            // methods at random (abstract or concrete).
            // we chose not to do this here, to prevent the inliner from potentially inlining the
            // wrong method. in other words, we guarantee that a concrete method is only returned if
            // it resolves deterministically.
            // however, there may be multiple abstract methods inherited. in this case we *do* want
            // to return a result to allow performing accessibility checks in the inliner. note that
            // for accessibility it does not matter which of these methods is return, as they are all
            // non-private (i.e., public, protected is not possible, jvms-4.1).
            // the remaining case (when there's no max-specific method, but some non-abstract one)
            // does not occur in bytecode generated by scalac or javac. we return no result in this
            // case. this may at worst prevent some optimizations from happening.
            val nonAbs = maxSpecific.filterNot(p => isAbstractMethod(p._1))
            if (nonAbs.lengthCompare(1) == 0) nonAbs.headOption
            else {
              val foundNonAbs = found.filterNot(p => isAbstractMethod(p._1))
              if (foundNonAbs.lengthCompare(1) == 0) foundNonAbs.headOption
              else if (foundNonAbs.isEmpty) found.headOption // (*)
              else None
            }
          }
        // end result
        Right(result.map(p => (p._1, p._2.name)))
      }

      findIn(initialOwner) match {
        case Some(cnf) => Left(cnf)
        case _ => findSpecific
      }
    }

    // In a MethodInsnNode, the `owner` field may be an array descriptor, for example when invoking `clone`. We don't have a method node to return in this case.
    if (ownerInternalNameOrArrayDescriptor.charAt(0) == '[') {
      Left(MethodNotFound(name, descriptor, ownerInternalNameOrArrayDescriptor, None))
    } else {
      def notFound(cnf: Option[ClassNotFound]) = Left(MethodNotFound(name, descriptor, ownerInternalNameOrArrayDescriptor, cnf))
      val res: Either[ClassNotFound, Option[(MethodNode, InternalName)]] = classNode(ownerInternalNameOrArrayDescriptor).flatMap(c =>
        findInSuperClasses(c) flatMap {
          case None => findInInterfaces(c)
          case res => Right(res)
        }
      )
      res match {
        case Left(e) => notFound(Some(e))
        case Right(None) => notFound(None)
        case Right(Some(res)) => Right(res)
      }
    }
  }

  private def removeLineNumbersAndAddLMFImplMethods(classNode: ClassNode): Unit = {
    for (m <- classNode.methods.asScala) {
      val iter = m.instructions.iterator
      while (iter.hasNext) {
        val insn = iter.next()
        insn.getType match {
          case AbstractInsnNode.LINE =>
            iter.remove()
          case AbstractInsnNode.INVOKE_DYNAMIC_INSN => insn match {
            case callGraph.LambdaMetaFactoryCall(_, _, implMethod, _) =>
              postProcessor.backendUtils.addIndyLambdaImplMethod(classNode.name, implMethod)
            case _ =>
          }
          case _ =>
        }
      }

    }
  }


  private def parseClass(internalName: InternalName): Either[ClassNotFound, ClassNode] = {
    val fullName = internalName.replace('/', '.')
    backendClassPath.findClassFile(fullName).flatMap { classFile =>
      val classNode = new ClassNode1
      val classReader = new asm.ClassReader(classFile.toByteArray)

      try {
        // Passing the InlineInfoAttributePrototype makes the ClassReader invoke the specific `read`
        // method of the InlineInfoAttribute class, instead of putting the byte array into a generic
        // Attribute.
        // We don't need frames when inlining, but we want to keep the local variable table, so we
        // don't use SKIP_DEBUG.
        classReader.accept(classNode, Array[Attribute](InlineInfoAttributePrototype), asm.ClassReader.SKIP_FRAMES)
        // SKIP_FRAMES leaves line number nodes. Remove them because they are not correct after
        // inlining.
        // TODO: we need to remove them also for classes that are not parsed from classfiles, why not simplify and do it once when inlining?
        // OR: instead of skipping line numbers for inlined code, use write a SourceDebugExtension
        // attribute that contains JSR-45 data that encodes debugging info.
        //   http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.11
        //   https://jcp.org/aboutJava/communityprocess/final/jsr045/index.html
        removeLineNumbersAndAddLMFImplMethods(classNode)
        Some(classNode)
      } catch {
        case ex: Exception =>
          if (frontendAccess.compilerSettings.debug) ex.printStackTrace()
          backendReporting.warning(NoPosition, s"Error while reading InlineInfoAttribute from ${fullName}\n${ex.getMessage}")
          None
      }
    } match {
      case Some(node) => Right(node)
      case None       => Left(ClassNotFound(internalName, javaDefinedClasses.get(internalName)))
    }
  }
}
