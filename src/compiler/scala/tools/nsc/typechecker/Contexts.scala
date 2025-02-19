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
package typechecker

import scala.annotation.tailrec
import scala.collection.{immutable, mutable}
import scala.reflect.internal.util.{ReusableInstance, shortClassOfInstance, SomeOfNil}
import scala.tools.nsc.Reporting.WarningCategory

/**
 *  @author  Martin Odersky
 *  @version 1.0
 */
trait Contexts { self: Analyzer =>
  import global._
  import definitions.{ JavaLangPackage, ScalaPackage, PredefModule, ScalaXmlTopScope, ScalaXmlPackage }

  import ContextMode._

  protected def onTreeCheckerError(pos: Position, msg: String): Unit = ()

  object NoContext
    extends Context(EmptyTree, NoSymbol, EmptyScope, NoCompilationUnit,
      // We can't pass the uninitialized `this`. Instead, we treat null specially in `Context#outer`
                    null, 0) {
    enclClass  = this
    enclMethod = this

    override def nextEnclosing(p: Context => Boolean): Context = this
    override def enclosingContextChain: List[Context] = Nil
    override def implicitss: List[List[ImplicitInfo]] = Nil
    override def imports: List[ImportInfo] = Nil
    override def firstImport: Option[ImportInfo] = None
    override def toString = "NoContext"
  }
  private object RootImports {
    // Possible lists of root imports
    val javaList         = JavaLangPackage :: Nil
    val javaAndScalaList = JavaLangPackage :: ScalaPackage :: Nil
    val completeList     = JavaLangPackage :: ScalaPackage :: PredefModule :: Nil
  }
  private lazy val NoJavaMemberFound = (NoType, NoSymbol)

  def ambiguousImports(imp1: ImportInfo, imp2: ImportInfo) =
    LookupAmbiguous(s"it is imported twice in the same scope by\n$imp1\nand $imp2")
  def ambiguousDefnAndImport(owner: Symbol, imp: ImportInfo) =
    LookupAmbiguous(s"it is both defined in $owner and imported subsequently by \n$imp")
  def ambiguousWithEnclosing(outer: Symbol, inherited: Symbol, currentClass: Symbol) =
    if (!currentRun.isScala213 || !outer.exists || inherited.isImplicit) None else {
      val outer1 = outer.alternatives.head
      val inherited1 = inherited.alternatives.head
      val classDesc = if (currentClass.isAnonymousClass) "anonymous class" else currentClass.toString
      val parent = currentClass.parentSymbols.find(_.isNonBottomSubClass(inherited1.owner)).getOrElse(NoSymbol)
      val inherit = if (parent.exists && parent != inherited1.owner) s", inherited through parent $parent" else ""
      val message =
        s"""it is both defined in the enclosing ${outer1.owner} and inherited in the enclosing $classDesc as $inherited1 (defined in ${inherited1.ownsString}$inherit)
           |In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
           |Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.${outer1.name}`.""".stripMargin
      if (currentRun.isScala3)
        Some(LookupAmbiguous(message))
      else {
        // passing the message to `typedIdent` as attachment, we don't have the position here to report the warning
        inherited.updateAttachment(
          LookupAmbiguityWarning(
          s"""reference to ${outer1.name} is ambiguous;
             |$message
             |Or use `-Wconf:msg=legacy-binding:s` to silence this warning.""".stripMargin))
        None
      }
    }
  private lazy val startContext = NoContext.make(
    Template(List(), noSelfType, List()) setSymbol global.NoSymbol setType global.NoType,
    rootMirror.RootClass,
    rootMirror.RootClass.info.decls
  )

  private lazy val allUsedSelectors =
    mutable.Map[ImportInfo, Set[ImportSelector]]() withDefaultValue Set()
  private lazy val allImportInfos =
    mutable.Map[CompilationUnit, List[(ImportInfo, Symbol)]]() withDefaultValue Nil

  def warnUnusedImports(unit: CompilationUnit) = if (!unit.isJava) {
    for (imps <- allImportInfos.remove(unit)) {
      for ((imp, owner) <- imps.distinct.reverse) {
        val used = allUsedSelectors(imp)
        for (sel <- imp.tree.selectors if !isMaskImport(sel) && !used(sel))
          runReporting.warning(imp.posOf(sel), "Unused import", WarningCategory.UnusedImports, site = owner)
      }
      allUsedSelectors --= imps.iterator.map(_._1)
    }
  }

  def isMaskImport(s: ImportSelector): Boolean = s.name != nme.WILDCARD && s.rename == nme.WILDCARD
  def isIndividualImport(s: ImportSelector): Boolean = s.name != nme.WILDCARD && s.rename != nme.WILDCARD
  def isWildcardImport(s: ImportSelector): Boolean = s.name == nme.WILDCARD

  var lastAccessCheckDetails: String = ""

  /** List of symbols to import from in a root context.  Typically that
   *  is `java.lang`, `scala`, and [[scala.Predef]], in that order.  Exceptions:
   *
   *  - if option `-Yno-imports` is given, nothing is imported
   *  - if the unit is java defined, only `java.lang` is imported
   *  - if option `-Yno-predef` is given, if the unit body has an import of Predef
   *    among its leading imports, or if the tree is [[scala.Predef]], `Predef` is not imported.
   */
  protected def rootImports(unit: CompilationUnit): List[Symbol] = {
    assert(definitions.isDefinitionsInitialized, "definitions uninitialized")

    if (settings.noimports) Nil
    else if (unit.isJava) RootImports.javaList
    else if (settings.nopredef || treeInfo.noPredefImportForUnit(unit.body)) {
      // scala/bug#8258 Needed for the presentation compiler using -sourcepath, otherwise cycles can occur. See the commit
      //         message for this ticket for an example.
      debuglog("Omitted import of Predef._ for " + unit)
      RootImports.javaAndScalaList
    }
    else RootImports.completeList
  }


  def rootContext(unit: CompilationUnit, tree: Tree = EmptyTree, throwing: Boolean = false, checking: Boolean = false): Context = {
    val rootImportsContext = (startContext /: rootImports(unit))((c, sym) => c.make(gen.mkWildcardImport(sym)))

    // there must be a scala.xml package when xml literals were parsed in this unit
    if (unit.hasXml && ScalaXmlPackage == NoSymbol)
      reporter.error(unit.firstXmlPos, "To compile XML syntax, the scala.xml package must be on the classpath.\nPlease see https://github.com/scala/scala-xml for details.")

    // scala-xml needs `scala.xml.TopScope` to be in scope globally as `$scope`
    // We detect `scala-xml` by looking for `scala.xml.TopScope` and
    // inject the equivalent of `import scala.xml.{TopScope => $scope}`
    val contextWithXML =
      if (!unit.hasXml || ScalaXmlTopScope == NoSymbol) rootImportsContext
      else rootImportsContext.make(gen.mkImport(ScalaXmlPackage, nme.TopScope, nme.dollarScope))

    val c = contextWithXML.make(tree, unit = unit)

    c.initRootContext(throwing, checking)
    c
  }

  def rootContextPostTyper(unit: CompilationUnit, tree: Tree = EmptyTree): Context =
    rootContext(unit, tree, throwing = true)

  def resetContexts() {
    startContext.enclosingContextChain foreach { context =>
      context.tree match {
        case Import(qual, _) => qual setType singleType(qual.symbol.owner.thisType, qual.symbol)
        case _               =>
      }
      context.reporter.clearAll()
    }
  }

  /**
   * A motley collection of the state and loosely associated behaviour of the type checker.
   * Each `Typer` has an associated context, and as it descends into the tree new `(Typer, Context)`
   * pairs are spawned.
   *
   * Meet the crew; first the state:
   *
   *   - A tree, symbol, and scope representing the focus of the typechecker
   *   - An enclosing context, `outer`.
   *   - The current compilation unit.
   *   - A variety of bits that track the current error reporting policy (more on this later);
   *     whether or not implicits/macros are enabled, whether we are in a self or super call or
   *     in a constructor suffix. These are represented as bits in the mask `contextMode`.
   *   - Some odds and ends: undetermined type parameters of the current line of type inference;
   *     contextual augmentation for error messages, tracking of the nesting depth.
   *
   * And behaviour:
   *
   *   - The central point for issuing errors and warnings from the typechecker, with a means
   *     to buffer these for use in 'silent' type checking, when some recovery might be possible.
   *  -  `Context` is something of a Zipper for the tree were are typechecking: it `enclosingContextChain`
   *     is the path back to the root. This is exactly what we need to resolve names (`lookupSymbol`)
   *     and to collect in-scope implicit definitions (`implicitss`)
   *     Supporting these are `imports`, which represents all `Import` trees in in the enclosing context chain.
   *  -  In a similar vein, we can assess accessibility (`isAccessible`.)
   *
   * More on error buffering:
   *     When are type errors recoverable? In quite a few places, it turns out. Some examples:
   *     trying to type an application with/without the expected type, or with/without implicit views
   *     enabled. This is usually mediated by `Typer.silent`, `Inferencer#tryTwice`.
   *
   *     Initially, starting from the `typer` phase, the contexts either buffer or report errors;
   *     afterwards errors are thrown. This is configured in `rootContext`. Additionally, more
   *     fine grained control is needed based on the kind of error; ambiguity errors are often
   *     suppressed during exploratory typing, such as determining whether `a == b` in an argument
   *     position is an assignment or a named argument, when `Inferencer#isApplicableSafe` type checks
   *     applications with and without an expected type, or when `Typer#tryTypedApply` tries to fit arguments to
   *     a function type with/without implicit views.
   *
   *     When the error policies entail error/warning buffering, the mutable [[ReportBuffer]] records
   *     everything that is issued. It is important to note, that child Contexts created with `make`
   *     "inherit" the very same `ReportBuffer` instance, whereas children spawned through `makeSilent`
   *     receive a separate, fresh buffer.
   *
   * @param tree  Tree associated with this context
   * @param owner The current owner
   * @param scope The current scope
   * @param _outer The next outer context.
   */
  class Context private[typechecker](val tree: Tree, val owner: Symbol, val scope: Scope,
                                     val unit: CompilationUnit, _outer: Context, val depth: Int,
                                     private[this] var _reporter: ContextReporter = new ThrowingReporter) {
    private def outerIsNoContext = _outer eq null
    final def outer: Context = if (outerIsNoContext) NoContext else _outer

    /** The next outer context whose tree is a template or package definition */
    var enclClass: Context = _

    @inline private def savingEnclClass[A](c: Context)(a: => A): A = {
      val saved = enclClass
      enclClass = c
      try a finally enclClass = saved
    }

    /** A bitmask containing all the boolean flags in a context, e.g. are implicit views enabled */
    var contextMode: ContextMode = ContextMode.DefaultMode

    /** Update all modes in `mask` to `value` */
    def update(mask: ContextMode, value: Boolean) {
      contextMode = contextMode.set(value, mask)
    }

    /** Set all modes in the mask `enable` to true, and all in `disable` to false. */
    def set(enable: ContextMode = NOmode, disable: ContextMode = NOmode): this.type = {
      contextMode = contextMode.set(true, enable).set(false, disable)
      this
    }

    /** Is this context in all modes in the given `mask`? */
    def apply(mask: ContextMode): Boolean = contextMode.inAll(mask)

    /** The next (logical) outer context whose tree is a method.
      *
      * NOTE: this is the "logical" enclosing method, which may not be the actual enclosing method when we
      * synthesize a nested method, such as for lazy val getters (scala/bug#8245) or the methods that
      * implement a PartialFunction literal (scala/bug#10291).
      */
    var enclMethod: Context = _

    /** Variance relative to enclosing class */
    var variance: Variance = Variance.Invariant

    private var _undetparams: List[Symbol] = List()

    protected def outerDepth = if (outerIsNoContext) 0 else outer.depth

    /** The currently visible imports, from innermost to outermost. */
    def imports: List[ImportInfo] = outer.imports
    /** Equivalent to `imports.headOption`, but more efficient */
    def firstImport: Option[ImportInfo] = outer.firstImport
    protected[Contexts] def importOrNull: ImportInfo = null
    /** A root import is never unused and always bumps context depth. (e.g scala._ / Predef._ and magic REPL imports) */
    def isRootImport: Boolean = false

    /** Types for which implicit arguments are currently searched */
    var openImplicits: List[OpenImplicit] = List()
    final def isSearchingForImplicitParam: Boolean = {
      openImplicits.nonEmpty && openImplicits.exists(x => !x.isView)
    }

    var prefix: Type = NoPrefix

    def inSuperInit_=(value: Boolean)         = this(SuperInit) = value
    def inSuperInit                           = this(SuperInit)
    def inConstructorSuffix_=(value: Boolean) = this(ConstructorSuffix) = value
    def inConstructorSuffix                   = this(ConstructorSuffix)
    def inPatAlternative_=(value: Boolean)    = this(PatternAlternative) = value
    def inPatAlternative                      = this(PatternAlternative)
    def starPatterns_=(value: Boolean)        = this(StarPatterns) = value
    def starPatterns                          = this(StarPatterns)
    def returnsSeen_=(value: Boolean)         = this(ReturnsSeen) = value
    def returnsSeen                           = this(ReturnsSeen)
    def inSelfSuperCall_=(value: Boolean)     = this(SelfSuperCall) = value
    def inSelfSuperCall                       = this(SelfSuperCall)
    def implicitsEnabled_=(value: Boolean)    = this(ImplicitsEnabled) = value
    def implicitsEnabled                      = this(ImplicitsEnabled)
    def macrosEnabled_=(value: Boolean)       = this(MacrosEnabled) = value
    def macrosEnabled                         = this(MacrosEnabled)
    def enrichmentEnabled_=(value: Boolean)   = this(EnrichmentEnabled) = value
    def enrichmentEnabled                     = this(EnrichmentEnabled)
    def retyping_=(value: Boolean)            = this(ReTyping) = value
    def retyping                              = this(ReTyping)
    def inSecondTry                           = this(SecondTry)
    def inSecondTry_=(value: Boolean)         = this(SecondTry) = value
    def inReturnExpr                          = this(ReturnExpr)
    def inTypeConstructorAllowed              = this(TypeConstructorAllowed)

    def defaultModeForTyped: Mode = if (inTypeConstructorAllowed) Mode.NOmode else Mode.EXPRmode

    /** To enrich error messages involving default arguments.
        When extending the notion, group diagnostics in an object. */
    var diagUsedDefaults: Boolean = false

    /** Saved type bounds for type parameters which are narrowed in a GADT. */
    var savedTypeBounds: List[(Symbol, Type)] = List()

    /** The next enclosing context (potentially `this`) that is owned by a class or method */
    def enclClassOrMethod: Context =
      if (!owner.exists || owner.isClass || owner.isMethod) this
      else outer.enclClassOrMethod

    /** The next enclosing context (potentially `this`) that has a `CaseDef` as a tree */
    def enclosingCaseDef = nextEnclosing(_.tree.isInstanceOf[CaseDef])

    /** ...or an Apply. */
    def enclosingApply = nextEnclosing(_.tree.isInstanceOf[Apply])

    @tailrec
    final def enclosingImport: Context = this match {
      case _: ImportContext => this
      case NoContext => this
      case _ => outer.enclosingImport
    }

    def siteString = {
      def what_s  = if (owner.isConstructor) "" else owner.kindString
      def where_s = if (owner.isClass) "" else "in " + enclClass.owner.decodedName
      List(what_s, owner.decodedName, where_s) filterNot (_ == "") mkString " "
    }
    //
    // Tracking undetermined type parameters for type argument inference.
    //
    def undetparamsString =
      if (undetparams.isEmpty) ""
      else undetparams.mkString("undetparams=", ", ", "")
    /** Undetermined type parameters. See `Infer#{inferExprInstance, adjustTypeArgs}`. Not inherited to child contexts */
    def undetparams: List[Symbol] = _undetparams
    def undetparams_=(ps: List[Symbol]) = { _undetparams = ps }

    /** Return and clear the undetermined type parameters */
    def extractUndetparams(): List[Symbol] = {
      val tparams = undetparams
      undetparams = List()
      tparams
    }

    /** Run `body` with this context with no undetermined type parameters, restore the original
     *  the original list afterwards.
     *  @param reportAmbiguous Should ambiguous errors be reported during evaluation of `body`?
     */
    def savingUndeterminedTypeParams[A](reportAmbiguous: Boolean = ambiguousErrors)(body: => A): A = {
      withMode() {
        setAmbiguousErrors(reportAmbiguous)
        val saved = extractUndetparams()
        try body
        finally undetparams = saved
      }
    }

    //
    // Error reporting policies and buffer.
    //

    // the reporter for this context
    def reporter: ContextReporter = _reporter

    // if set, errors will not be reporter/thrown
    def bufferErrors = reporter.isBuffering
    def reportErrors = !(bufferErrors || reporter.isThrowing)

    // whether to *report* (which is separate from buffering/throwing) ambiguity errors
    def ambiguousErrors = this(AmbiguousErrors)

    private def setAmbiguousErrors(report: Boolean): Unit = this(AmbiguousErrors) = report

    /**
     * Try inference twice: once without views and once with views,
     *  unless views are already disabled.
     */
    abstract class TryTwice {
      def tryOnce(isLastTry: Boolean): Unit

      final def apply(): Unit = {
        val doLastTry =
          // do first try if implicits are enabled
          if (implicitsEnabled) {
            // We create a new BufferingReporter to
            // distinguish errors that occurred before entering tryTwice
            // and our first attempt in 'withImplicitsDisabled'. If the
            // first attempt fails, we try with implicits on
            // and the original reporter.
            // immediate reporting of ambiguous errors is suppressed, so that they are buffered
            inSilentMode {
              try {
                set(disable = ImplicitsEnabled | EnrichmentEnabled) // restored by inSilentMode
                tryOnce(false)
                reporter.hasErrors
              } catch {
                case ex: CyclicReference => throw ex
                case ex: TypeError => true // recoverable cyclic references?
              }
            }
          } else true

        // do last try if try with implicits enabled failed
        // (or if it was not attempted because they were disabled)
        if (doLastTry)
          tryOnce(true)
      }
    }

    //
    // Temporary mode adjustment
    //

    @inline final def withMode[T](enabled: ContextMode = NOmode, disabled: ContextMode = NOmode)(op: => T): T = {
      val saved = contextMode
      set(enabled, disabled)
      try op
      finally contextMode = saved
    }

    @inline final def withImplicitsEnabled[T](op: => T): T                 = withMode(enabled = ImplicitsEnabled)(op)
    @inline final def withImplicitsDisabled[T](op: => T): T                = withMode(disabled = ImplicitsEnabled | EnrichmentEnabled)(op)
    @inline final def withImplicitsDisabledAllowEnrichment[T](op: => T): T = withMode(enabled = EnrichmentEnabled, disabled = ImplicitsEnabled)(op)
    @inline final def withImplicits[T](enabled: Boolean)(op: => T): T      = if (enabled) withImplicitsEnabled(op) else withImplicitsDisabled(op)
    @inline final def withMacrosEnabled[T](op: => T): T                    = withMode(enabled = MacrosEnabled)(op)
    @inline final def withMacrosDisabled[T](op: => T): T                   = withMode(disabled = MacrosEnabled)(op)
    @inline final def withMacros[T](enabled: Boolean)(op: => T): T         = if (enabled) withMacrosEnabled(op) else withMacrosDisabled(op)
    @inline final def withinStarPatterns[T](op: => T): T                   = withMode(enabled = StarPatterns)(op)
    @inline final def withinSuperInit[T](op: => T): T                      = withMode(enabled = SuperInit)(op)
    @inline final def withinSecondTry[T](op: => T): T                      = withMode(enabled = SecondTry)(op)
    @inline final def withinPatAlternative[T](op: => T): T                 = withMode(enabled = PatternAlternative)(op)

    @inline final def withSuppressDeadArgWarning[T](suppress: Boolean)(op: => T): T =
      if (suppress) withMode(enabled = SuppressDeadArgWarning)(op) else withMode(disabled = SuppressDeadArgWarning)(op)

    /** TypeConstructorAllowed is enabled when we are typing a higher-kinded type.
     *  adapt should then check kind-arity based on the prototypical type's kind
     *  arity. Type arguments should not be inferred.
     */
    @inline final def withinTypeConstructorAllowed[T](op: => T): T = withMode(enabled = TypeConstructorAllowed)(op)

    /* TODO - consolidate returnsSeen (which seems only to be used by checkDead)
     * and ReturnExpr.
     */
    @inline final def withinReturnExpr[T](op: => T): T = {
      enclMethod.returnsSeen = true
      withMode(enabled = ReturnExpr)(op)
    }

    // See comment on FormerNonStickyModes.
    @inline final def withOnlyStickyModes[T](op: => T): T = withMode(disabled = FormerNonStickyModes)(op)

    // inliner note: this has to be a simple method for inlining to work -- moved the `&& !reporter.hasErrors` out
    @inline final def inSilentMode(expr: => Boolean): Boolean = {
      val savedContextMode = contextMode
      val savedReporter    = reporter

      setAmbiguousErrors(false)
      _reporter = new BufferingReporter

      try expr
      finally {
        contextMode = savedContextMode
        _reporter   = savedReporter
      }
    }

    //
    // Child Context Creation
    //

    /**
     * Construct a child context. The parent and child will share the report buffer.
     * Compare with `makeSilent`, in which the child has a fresh report buffer.
     *
     * If `tree` is an `Import`, that import will be available at the head of
     * `Context#imports`.
     */
    def make(tree: Tree = tree, owner: Symbol = owner,
             scope: Scope = scope, unit: CompilationUnit = unit,
             reporter: ContextReporter = this.reporter): Context = {
      val isTemplateOrPackage = tree match {
        case _: Template | _: PackageDef => true
        case _                           => false
      }
      val isDefDef = tree match {
        case _: DefDef => true
        case _         => false
      }
      val isImport = tree match {
        // The guard is for scala/bug#8403. It prevents adding imports again in the context created by
        // `Namer#createInnerNamer`
        case _: Import if tree != this.tree => true
        case _                              => false
      }
      val sameOwner = owner == this.owner
      val prefixInChild =
        if (isTemplateOrPackage) owner.thisType
        else if (!sameOwner && owner.isTerm) NoPrefix
        else prefix

      def innerDepth(isRootImport: Boolean) = {
        val increasesDepth = isRootImport || (this == NoContext) || (this.scope != scope)
        depth + (if (increasesDepth) 1 else 0)
      }

      // The blank canvas
      val c = if (isImport) {
        val isRootImport = !tree.pos.isDefined || isReplImportWrapperImport(tree)
        new ImportContext(tree, owner, scope, unit, this, isRootImport, innerDepth(isRootImport), reporter)
      } else
        new Context(tree, owner, scope, unit, this, innerDepth(isRootImport = false), reporter)

      // Fields that are directly propagated
      c.variance           = variance
      c.diagUsedDefaults   = diagUsedDefaults
      c.openImplicits      = openImplicits
      c.contextMode        = contextMode // note: ConstructorSuffix, a bit within `mode`, is conditionally overwritten below.

      // Fields that may take on a different value in the child
      c.prefix             = prefixInChild
      c.enclClass          = if (isTemplateOrPackage) c else enclClass
      c(ConstructorSuffix) = !isTemplateOrPackage && c(ConstructorSuffix)

      // scala/bug#8245 `isLazy` need to skip lazy getters to ensure `return` binds to the right place
      c.enclMethod         = if (isDefDef && !owner.isLazy) c else enclMethod

      if (tree != outer.tree)
        c(TypeConstructorAllowed) = false

      registerContext(c.asInstanceOf[analyzer.Context])
      debuglog("[context] ++ " + c.unit + " / " + (if (tree == null) "" else tree.summaryString))
      c
    }

    /** Use reporter (possibly buffered) for errors/warnings and enable implicit conversion **/
    def initRootContext(throwing: Boolean = false, checking: Boolean = false): Unit = {
      _reporter =
        if (checking) new CheckingReporter
        else if (throwing) new ThrowingReporter
        else new ImmediateReporter

      setAmbiguousErrors(!throwing)
      this(EnrichmentEnabled | ImplicitsEnabled) = !throwing
    }

    def make(tree: Tree, owner: Symbol, scope: Scope): Context =
      // TODO scala/bug#7345 Moving this optimization into the main overload of `make` causes all tests to fail.
      //              even if it is extended to check that `unit == this.unit`. Why is this?
      if (tree == this.tree && owner == this.owner && scope == this.scope) this
      else make(tree, owner, scope, unit)

    /** Make a child context that represents a new nested scope */
    def makeNewScope(tree: Tree, owner: Symbol, reporter: ContextReporter = this.reporter): Context =
      make(tree, owner, newNestedScope(scope), reporter = reporter)

    /** Make a child context that buffers errors and warnings into a fresh report buffer. */
    def makeSilent(reportAmbiguousErrors: Boolean = ambiguousErrors, newtree: Tree = tree): Context = {
      // A fresh buffer so as not to leak errors/warnings into `this`.
      val c = make(newtree, reporter = new BufferingReporter)
      c.setAmbiguousErrors(reportAmbiguousErrors)
      c
    }

    def makeNonSilent(newtree: Tree): Context = {
      val c = make(newtree, reporter = reporter.makeImmediate)
      c.setAmbiguousErrors(true)
      c
    }

    /** Make a silent child context does not allow implicits. Used to prevent chaining of implicit views. */
    def makeImplicit(reportAmbiguousErrors: Boolean) = {
      val c = makeSilent(reportAmbiguousErrors)
      c(ImplicitsEnabled | EnrichmentEnabled) = false
      c
    }

    /**
     * A context for typing constructor parameter ValDefs, super or self invocation arguments and default getters
     * of constructors. These expressions need to be type checked in a scope outside the class, cf. spec 5.3.1.
     *
     * This method is called by namer / typer where `this` is the context for the constructor DefDef. The
     * owner of the resulting (new) context is the outer context for the Template, i.e. the context for the
     * ClassDef. This means that class type parameters will be in scope. The value parameters of the current
     * constructor are also entered into the new constructor scope. Members of the class however will not be
     * accessible.
     */
    def makeConstructorContext = {
      val baseContext = enclClass.outer.nextEnclosing(!_.tree.isInstanceOf[Template])
      // must propagate reporter!
      // (caught by neg/t3649 when refactoring reporting to be specified only by this.reporter and not also by this.contextMode)
      val argContext = baseContext.makeNewScope(tree, owner, reporter = this.reporter)
      argContext.contextMode = contextMode
      argContext.inSelfSuperCall = true
      def enterElems(c: Context) {
        def enterLocalElems(e: ScopeEntry) {
          if (e != null && e.owner == c.scope) {
            enterLocalElems(e.next)
            argContext.scope enter e.sym
          }
        }
        if (c.owner.isTerm && !c.owner.isLocalDummy) {
          enterElems(c.outer)
          enterLocalElems(c.scope.elems)
        }
      }
      // Enter the scope elements of this (the scope for the constructor DefDef) into the new constructor scope.
      // Concretely, this will enter the value parameters of constructor.
      enterElems(this)
      argContext
    }

    //
    // Error and warning issuance
    //

    /** Issue/buffer/throw the given type error according to the current mode for error reporting. */
    private[typechecker] def issue(err: AbsTypeError)                        = reporter.issue(err)(this)
    /** Issue/buffer/throw the given implicit ambiguity error according to the current mode for error reporting. */
    private[typechecker] def issueAmbiguousError(err: AbsAmbiguousTypeError) = reporter.issueAmbiguousError(err)(this)
    /** Issue/throw the given error message according to the current mode for error reporting. */
    def error(pos: Position, msg: String)                                    = reporter.error(fixPosition(pos), msg)
    /** Issue/throw the given error message according to the current mode for error reporting. */
    def warning(pos: Position, msg: String, category: WarningCategory)       = reporter.warning(fixPosition(pos), msg, category, owner)
    def warning(pos: Position, msg: String, category: WarningCategory, site: Symbol) = reporter.warning(fixPosition(pos), msg, category, site)
    def echo(pos: Position, msg: String)                                     = reporter.echo(fixPosition(pos), msg)
    def fixPosition(pos: Position): Position = pos match {
      case NoPosition => nextEnclosing(_.tree.pos != NoPosition).tree.pos
      case _ => pos
    }


    // TODO: buffer deprecations under silent (route through ContextReporter, store in BufferingReporter)
    def deprecationWarning(pos: Position, sym: Symbol, msg: String, since: String): Unit =
      runReporting.deprecationWarning(fixPosition(pos), sym, owner, msg, since)

    def deprecationWarning(pos: Position, sym: Symbol): Unit =
      runReporting.deprecationWarning(fixPosition(pos), sym, owner)

    def featureWarning(pos: Position, featureName: String, featureDesc: String, featureTrait: Symbol, construct: => String = "", required: Boolean): Unit =
      runReporting.featureWarning(fixPosition(pos), featureName, featureDesc, featureTrait, construct, required, owner)


    // nextOuter determines which context is searched next for implicits
    // (after `this`, which contributes `newImplicits` below.) In
    // most cases, it is simply the outer context: if we're owned by
    // a constructor, the actual current context and the conceptual
    // context are different when it comes to scoping. The current
    // conceptual scope is the context enclosing the blocks which
    // represent the constructor body (TODO: why is there more than one
    // such block in the outer chain?)
    private def nextOuter = {
      // Drop the constructor body blocks, which come in varying numbers.
      // -- If the first statement is in the constructor, scopingCtx == (constructor definition)
      // -- Otherwise, scopingCtx == (the class which contains the constructor)
      val scopingCtx =
        if (owner.isConstructor) nextEnclosing(c => !c.tree.isInstanceOf[Block])
        else this

      scopingCtx.outer
    }

    def nextEnclosing(p: Context => Boolean): Context =
      if (p(this)) this else outer.nextEnclosing(p)

    final def outermostContextAtCurrentPos: Context = {
      var pos = tree.pos
      var encl = this
      while (pos == NoPosition && encl != NoContext) {
        encl = encl.outer
        pos = encl.tree.pos
      }
      while (encl.outer.tree.pos == pos && encl != NoContext)
        encl = encl.outer
      encl
    }

    def enclosingContextChain: List[Context] = this :: outer.enclosingContextChain

    private def treeTruncated       = tree.toString.replaceAll("\\s+", " ").linesIterator.mkString("\\n").take(70)
    private def treeIdString        = if (settings.uniqid.value) "#" + System.identityHashCode(tree).toString.takeRight(3) else ""
    private def treeString          = tree match {
      case x: Import => "" + x
      case Template(parents, `noSelfType`, body) =>
        val pstr = if ((parents eq null) || parents.isEmpty) "Nil" else parents mkString " "
        val bstr = if (body eq null) "" else body.length + " stats"
        s"""Template($pstr, _, $bstr)"""
      case x => s"${tree.shortClass}${treeIdString}:${treeTruncated}"
    }

    override def toString =
      sm"""|Context($unit) {
           |   owner       = $owner
           |   tree        = $treeString
           |   scope       = ${scope.size} decls
           |   contextMode = $contextMode
           |   outer.owner = ${outer.owner}
           |}"""

    //
    // Accessibility checking
    //

    /** True iff...
      * - `sub` is a subclass of `base`
      * - `sub` is the module class of a companion of a subclass of `base`
      * - `base` is a Java-defined module class (containing static members),
      *   and `sub` is a subclass of its companion class. (see scala/bug#6394)
      */
    private def isSubClassOrCompanion(sub: Symbol, base: Symbol) =
      sub.isNonBottomSubClass(base) ||
        (sub.isModuleClass && sub.linkedClassOfClass.isNonBottomSubClass(base)) ||
        (base.isJavaDefined && base.isModuleClass && (
          sub.isNonBottomSubClass(base.linkedClassOfClass) ||
            sub.isModuleClass && sub.linkedClassOfClass.isNonBottomSubClass(base.linkedClassOfClass)))

    /** Return the closest enclosing context that defines a subclass of `clazz`
     *  or a companion object thereof, or `NoContext` if no such context exists.
     */
    def enclosingSubClassContext(clazz: Symbol): Context = {
      var c = this.enclClass
      while (c != NoContext && !isSubClassOrCompanion(c.owner, clazz))
        c = c.outer.enclClass
      c
    }

    def enclosingNonImportContext: Context = {
      var c = this
      while (c != NoContext && c.tree.isInstanceOf[Import])
        c = c.outer
      c
    }

    /** Is `sym` accessible as a member of `pre` in current context? */
    def isAccessible(sym: Symbol, pre: Type, superAccess: Boolean = false): Boolean = {
      lastAccessCheckDetails = ""
      // Console.println("isAccessible(%s, %s, %s)".format(sym, pre, superAccess))

      // don't have access if there is no linked class (so exclude linkedClass=NoSymbol)
      def accessWithinLinked(ab: Symbol) = {
        val linked = linkedClassOfClassOf(ab, this)
        linked.fold(false)(accessWithin)
      }

      /* Are we inside definition of `ab`? */
      def accessWithin(ab: Symbol) = {
        // #3663: we must disregard package nesting if sym isJavaDefined
        if (sym.isJavaDefined) {
          // is `o` or one of its transitive owners equal to `ab`?
          // stops at first package, since further owners can only be surrounding packages
          @tailrec def abEnclosesStopAtPkg(o: Symbol): Boolean =
            (o eq ab) || (!o.isPackageClass && (o ne NoSymbol) && abEnclosesStopAtPkg(o.owner))
          abEnclosesStopAtPkg(owner)
        } else (owner hasTransOwner ab)
      }

      def isSubThisType(pre: Type, clazz: Symbol): Boolean = pre match {
        case ThisType(pclazz) => pclazz isNonBottomSubClass clazz
        case _ => false
      }

      /* Is protected access to target symbol permitted */
      def isProtectedAccessOK(target: Symbol) = {
        val c = enclosingSubClassContext(sym.owner)
        val preSym = pre.widen.typeSymbol
        if (c == NoContext)
          lastAccessCheckDetails =
            sm"""
                | Access to protected $target not permitted because
                | enclosing ${enclClass.owner.fullLocationString} is not a subclass of
                | ${sym.owner.fullLocationString} where target is defined"""
        c != NoContext &&
        {
          target.isType || { // allow accesses to types from arbitrary subclasses fixes scala/bug#4737
            val res =
              isSubClassOrCompanion(preSym, c.owner) ||
                (c.owner.isModuleClass
                  && isSubClassOrCompanion(preSym, c.owner.linkedClassOfClass)) ||
                (preSym.isJava
                  && preSym.isModuleClass) // java static members don't care about prefix for accessibility
            if (!res)
              lastAccessCheckDetails =
                sm"""
                    | Access to protected $target not permitted because
                    | prefix type ${pre.widen} does not conform to
                    | ${c.owner.fullLocationString} where the access takes place"""
              res
          }
        }
      }

      (pre == NoPrefix) || {
        val ab = sym.accessBoundary(sym.owner)

        (  (ab.isTerm || ab == rootMirror.RootClass)
        || (accessWithin(ab) || accessWithinLinked(ab)) &&
             (  !sym.isLocalToThis
             || sym.isProtected && isSubThisType(pre, sym.owner)
             || pre =:= sym.owner.thisType
             )
        || sym.isProtected &&
             (  superAccess
             || pre.isInstanceOf[ThisType]
             || phase.erasedTypes // (*)
             || (sym.overrideChain exists isProtectedAccessOK)
                // that last condition makes protected access via self types work.
             )
        )
        // (*) in t780.scala: class B extends A { protected val x }; trait A { self: B => x }
        // Before erasure, the `pre` is a `ThisType`, so the access is allowed. Erasure introduces
        // a cast to access `x` (this.$asInstanceOf[B].x), then `pre` is no longer a `ThisType`
        // but a `TypeRef` to `B`.
        // Note that `isProtectedAccessOK` is false, it checks if access is OK in the current
        // context's owner (trait `A`), not in the `pre` type.
        // This implementation makes `isAccessible` return false positives. Maybe the idea is to
        // represent VM-level information, as we don't emit protected? If so, it's wrong for
        // Java-defined symbols, which can be protected in bytecode. History:
        //   - Phase check added in 8243b2dd2d
        //   - Removed in 1536b1c67e, but moved to `accessBoundary`
        //   - Re-added in 42744ffda0 (and left in `accessBoundary`)
      }
    }

    //
    // Type bound management
    //

    def pushTypeBounds(sym: Symbol) {
      sym.info match {
        case tb: TypeBounds => if (!tb.isEmptyBounds) log(s"Saving $sym info=$tb")
        case info           => devWarning(s"Something other than a TypeBounds seen in pushTypeBounds: $info is a ${shortClassOfInstance(info)}")
      }
      savedTypeBounds ::= ((sym, sym.info))
    }

    def restoreTypeBounds(tp: Type): Type = {
      def restore(): Type = savedTypeBounds.foldLeft(tp) { case (current, (sym, savedInfo)) =>
        def bounds_s(tb: TypeBounds) = if (tb.isEmptyBounds) "<empty bounds>" else s"TypeBounds(lo=${tb.lo}, hi=${tb.hi})"
        //@M TODO: when higher-kinded types are inferred, probably need a case PolyType(_, TypeBounds(...)) if ... =>
        val TypeBounds(lo, hi) = sym.info.bounds
        val isUnique           = lo <:< hi && hi <:< lo
        val isPresent          = current contains sym
        def saved_s            = bounds_s(savedInfo.bounds)
        def current_s          = bounds_s(sym.info.bounds)

        if (isUnique && isPresent)
          devWarningResult(s"Preserving inference: ${sym.nameString}=$hi in $current (based on $current_s) before restoring $sym to saved $saved_s")(
            current.instantiateTypeParams(List(sym), List(hi))
          )
        else if (isPresent)
          devWarningResult(s"Discarding inferred $current_s because it does not uniquely determine $sym in")(current)
        else
          logResult(s"Discarding inferred $current_s because $sym does not appear in")(current)
      }
      try restore()
      finally {
        for ((sym, savedInfo) <- savedTypeBounds)
          sym setInfo debuglogResult(s"Discarding inferred $sym=${sym.info}, restoring saved info")(savedInfo)

        savedTypeBounds = Nil
      }
    }

    //
    // Implicit collection
    //

    private var implicitsCache: List[ImplicitInfo] = null
    private var implicitsRunId = NoRunId

    def resetCache() {
      implicitsRunId = NoRunId
      implicitsCache = null
      if (outer != null && outer != this) outer.resetCache()
    }

    /** A symbol `sym` qualifies as an implicit if it has the IMPLICIT flag set,
     *  it is accessible, and if it is imported there is not already a local symbol
     *  with the same names. Local symbols override imported ones. This fixes #2866.
     */
    private def isQualifyingImplicit(name: Name, sym: Symbol, pre: Type, imported: Boolean) =
      sym.isImplicit &&
      isAccessible(sym, pre) &&
      !(imported && {
        val e = scope.lookupEntry(name)
        (e ne null) && (e.owner == scope) && (!currentRun.isScala212 || e.sym.exists)
      })

    /** Do something with the symbols with name `name` imported via the import in `imp`,
     *  if any such symbol is accessible from this context and is a qualifying implicit.
     */
    private def withQualifyingImplicitAlternatives(imp: ImportInfo, name: Name, pre: Type)(f: Symbol => Unit) = for {
      sym <- importedAccessibleSymbol(imp, name, requireExplicit = false, record = false).alternatives
      if isQualifyingImplicit(name, sym, pre, imported = true)
    } f(sym)

    private def collectImplicits(syms: Scope, pre: Type, imported: Boolean = false): List[ImplicitInfo] =
      for (sym <- syms.toList if isQualifyingImplicit(sym.name, sym, pre, imported)) yield
        new ImplicitInfo(sym.name, pre, sym)

    private def collectImplicitImports(imp: ImportInfo): List[ImplicitInfo] = {
      val qual = imp.qual

      val pre = qual.tpe
      def collect(sels: List[ImportSelector]): List[ImplicitInfo] = sels match {
        case List() =>
          List()
        case List(ImportSelector(nme.WILDCARD, _, _, _)) =>
          // Using pre.implicitMembers seems to exposes a problem with out-dated symbols in the IDE,
          // see the example in https://www.assembla.com/spaces/scala-ide/tickets/1002552#/activity/ticket
          // I haven't been able to boil that down the an automated test yet.
          // Looking up implicit members in the package, rather than package object, here is at least
          // consistent with what is done just below for named imports.
          collectImplicits(qual.tpe.implicitMembers, pre, imported = true)
        case ImportSelector(from, _, to, _) :: sels1 =>
          var impls = collect(sels1) filter (info => info.name != from)
          if (to != nme.WILDCARD) {
            withQualifyingImplicitAlternatives(imp, to, pre) { sym =>
              impls = new ImplicitInfo(to, pre, sym) :: impls
            }
          }
          impls
      }
      //debuglog("collect implicit imports " + imp + "=" + collect(imp.tree.selectors))//DEBUG
      collect(imp.tree.selectors)
    }

    /* scala/bug#5892 / scala/bug#4270: `implicitss` can return results which are not accessible at the
     * point where implicit search is triggered. Example: implicits in (annotations of)
     * class type parameters (scala/bug#5892). The `context.owner` is the class symbol, therefore
     * `implicitss` will return implicit conversions defined inside the class. These are
     * filtered out later by `eligibleInfos` (scala/bug#4270 / 9129cfe9), as they don't type-check.
     */
    def implicitss: List[List[ImplicitInfo]] = {
      val nextOuter = this.nextOuter
      def withOuter(is: List[ImplicitInfo]): List[List[ImplicitInfo]] =
        is match {
          case Nil => nextOuter.implicitss
          case _   => is :: nextOuter.implicitss
        }

      val CycleMarker = NoRunId - 1
      if (implicitsRunId == CycleMarker) {
        debuglog(s"cycle while collecting implicits at owner ${owner}, probably due to an implicit without an explicit return type. Continuing with implicits from enclosing contexts.")
        withOuter(Nil)
      } else if (implicitsRunId != currentRunId) {
        implicitsRunId = CycleMarker
        implicits(nextOuter) match {
          case None =>
            implicitsRunId = NoRunId
            withOuter(Nil)
          case Some(is) =>
            implicitsRunId = currentRunId
            implicitsCache = is
            withOuter(is)
        }
      }
      else withOuter(implicitsCache)
    }

    /** @return None if a cycle is detected, or Some(infos) containing the in-scope implicits at this context */
    private def implicits(nextOuter: Context): Option[List[ImplicitInfo]] = {
      val firstImport = this.firstImport
      if (unit.isJava) SomeOfNil
      else if (owner != nextOuter.owner && owner.isClass && !owner.isPackageClass && !inSelfSuperCall) {
        if (!owner.isInitialized) None
        else savingEnclClass(this) {
          // !!! In the body of `class C(implicit a: A) { }`, `implicitss` returns `List(List(a), List(a), List(<predef..)))`
          //     it handled correctly by implicit search, which considers the second `a` to be shadowed, but should be
          //     remedied nonetheless.
          Some(collectImplicits(owner.thisType.implicitMembers, owner.thisType))
        }
      } else if (scope != nextOuter.scope && !owner.isPackageClass) {
        debuglog("collect local implicits " + scope.toList)//DEBUG
        Some(collectImplicits(scope, NoPrefix))
      } else if (firstImport != nextOuter.firstImport) {
        if (isDeveloper)
          assert(imports.tail.headOption == nextOuter.firstImport, (imports, nextOuter.imports))
        Some(collectImplicitImports(firstImport.get))
      } else if (owner.isPackageClass) {
        // the corresponding package object may contain implicit members.
        val pre = owner.packageObject.typeOfThis
        Some(collectImplicits(pre.implicitMembers, pre))
      } else SomeOfNil
    }

    //
    // Imports and symbol lookup
    //

    /** It's possible that seemingly conflicting identifiers are
     *  identifiably the same after type normalization.  In such cases,
     *  allow compilation to proceed.  A typical example is:
     *    package object foo { type InputStream = java.io.InputStream }
     *    import foo._, java.io._
     */
    private[Contexts] def resolveAmbiguousImport(name: Name, imp1: ImportInfo, imp2: ImportInfo): Option[ImportInfo] = {
      val imp1Explicit = imp1 isExplicitImport name
      val imp2Explicit = imp2 isExplicitImport name
      val ambiguous    = if (imp1.depth == imp2.depth) imp1Explicit == imp2Explicit else !imp1Explicit && imp2Explicit
      val imp1Symbol   = (imp1 importedSymbol name).initialize filter (s => isAccessible(s, imp1.qual.tpe, superAccess = false))
      val imp2Symbol   = (imp2 importedSymbol name).initialize filter (s => isAccessible(s, imp2.qual.tpe, superAccess = false))

      // The types of the qualifiers from which the ambiguous imports come.
      // If the ambiguous name is a value, these must be the same.
      def t1 = imp1.qual.tpe
      def t2 = imp2.qual.tpe
      // The types of the ambiguous symbols, seen as members of their qualifiers.
      // If the ambiguous name is a monomorphic type, we can relax this far.
      def mt1 = t1 memberType imp1Symbol
      def mt2 = t2 memberType imp2Symbol

      def characterize = List(
        s"types:  $t1 =:= $t2  ${t1 =:= t2}  members: ${mt1 =:= mt2}",
        s"member type 1: $mt1",
        s"member type 2: $mt2"
      ).mkString("\n  ")

      if (!ambiguous || !imp2Symbol.exists) Some(imp1)
      else if (!imp1Symbol.exists) Some(imp2)
      else (
        // The symbol names are checked rather than the symbols themselves because
        // each time an overloaded member is looked up it receives a new symbol.
        // So foo.member("x") != foo.member("x") if x is overloaded.  This seems
        // likely to be the cause of other bugs too...
        if (t1 =:= t2 && imp1Symbol.name == imp2Symbol.name) {
          log(s"Suppressing ambiguous import: $t1 =:= $t2 && $imp1Symbol == $imp2Symbol")
          Some(imp1)
        }
        // Monomorphism restriction on types is in part because type aliases could have the
        // same target type but attach different variance to the parameters. Maybe it can be
        // relaxed, but doesn't seem worth it at present.
        else if (mt1 =:= mt2 && name.isTypeName && imp1Symbol.isMonomorphicType && imp2Symbol.isMonomorphicType) {
          log(s"Suppressing ambiguous import: $mt1 =:= $mt2 && $imp1Symbol and $imp2Symbol are equivalent")
          Some(imp1)
        }
        else {
          log(s"Import is genuinely ambiguous:\n  " + characterize)
          None
        }
      )
    }

    /** The symbol with name `name` imported via the import in `imp`,
     *  if any such symbol is accessible from this context.
     */
    private[Contexts] def importedAccessibleSymbol(imp: ImportInfo, name: Name, requireExplicit: Boolean, record: Boolean): Symbol =
      imp.importedSymbol(name, requireExplicit, record) filter (s => isAccessible(s, imp.qual.tpe, superAccess = false))

    private[Contexts] def requiresQualifier(s: Symbol): Boolean = (
          s.owner.isClass
      && !s.owner.isPackageClass
      && !s.isTypeParameterOrSkolem
      && !s.isExistentiallyBound
    )

    /** Must `sym` defined in package object of package `pkg`, if
     *  it selected from a prefix with `pkg` as its type symbol?
     */
    def isInPackageObject(sym: Symbol, pkg: Symbol): Boolean = {
      if (sym.isOverloaded) sym.alternatives.exists(alt => isInPackageObject(alt, pkg))
      else pkg.hasPackageFlag && sym.owner != pkg && requiresQualifier(sym)
    }

    def isNameInScope(name: Name) = lookupSymbol(name, _ => true).isSuccess

    def lookupSymbol(name: Name, qualifies: Symbol => Boolean): NameLookup =
      symbolLookupCache.using(_(this, name)(qualifies))

    final def lookupCompanionInIncompleteOwner(original: Symbol): Symbol = {
      // Must have both a class and module symbol, so that `{ class C; def C }` or `{ type T; object T }` are not companions.
      def isCompanion(sym: Symbol): Boolean =
        (original.isModule && sym.isClass || sym.isModule && original.isClass) && sym.isCoDefinedWith(original)
      lookupSibling(original, original.name.companionName).filter(isCompanion)
    }

    final def lookupSibling(original: Symbol, name: Name): Symbol = {
      /* Search scopes in current and enclosing contexts for the definition of `symbol` */
      def lookupScopeEntry(symbol: Symbol): ScopeEntry = {
        var res: ScopeEntry = null
        var ctx = this
        while (res == null && ctx.outer != ctx) {
          val s = ctx.scope lookupSymbolEntry symbol
          if (s != null)
            res = s
          else
            ctx = ctx.outer
        }
        res
      }

      // Must be owned by the same Scope, to ensure that in
      // `{ class C; { ...; object C } }`, the class is not seen as a companion of the object.
      lookupScopeEntry(original) match {
        case null => NoSymbol
        case entry =>
          entry.owner.lookupNameInSameScopeAs(original, name)
      }
    }

    final def javaFindMember(pre: Type, name: Name, qualifies: Symbol => Boolean): (Type, Symbol) = {
      val sym = pre.member(name).filter(qualifies)
      val preSym = pre.typeSymbol
      if (sym.exists || preSym.isPackageClass || !preSym.isClass) (pre, sym)
      else {
        // In Java code, static innner classes, which we model as members of the companion object,
        // can be referenced from an ident in a subclass or by a selection prefixed by the subclass.
        val toSearch = if (preSym.isModuleClass) companionSymbolOf(pre.typeSymbol.sourceModule, this).baseClasses else preSym.baseClasses
        toSearch.iterator.map { bc =>
          val pre1 = bc.typeOfThis
          val found = pre1.decl(name)
          found.filter(qualifies) match {
            case NoSymbol =>
              val pre2 = companionSymbolOf(pre1.typeSymbol, this).typeOfThis
              val found = pre2.decl(name).filter(qualifies)
              found match {
                case NoSymbol => NoJavaMemberFound
                case sym => (pre2, sym)
              }
            case sym => (pre1, sym)
          }
        }.find(_._2 ne NoSymbol).getOrElse(NoJavaMemberFound)
      }
    }


    private def isReplImportWrapperImport(tree: Tree): Boolean = {
      tree match {
        case Import(expr, selector :: Nil) =>
          // Just a syntactic check to avoid forcing typechecking of imports
          selector.name.string_==(nme.INTERPRETER_IMPORT_LEVEL_UP) && owner.enclosingTopLevelClass.isInterpreterWrapper
        case _ => false
      }
    }

  } //class Context

  /** Find the symbol of a simple name starting from this context.
   *  All names are filtered through the "qualifies" predicate,
   *  the search continuing as long as no qualifying name is found.
   */
  // OPT: moved this into a (cached) object to avoid costly and non-eliminated {Object,Int}Ref allocations
  private[Contexts] final val symbolLookupCache = ReusableInstance[SymbolLookup](new SymbolLookup, enabled = isCompilerUniverse)
  private[Contexts] final class SymbolLookup {
    private[this] var lookupError: NameLookup  = _ // set to non-null if a definite error is encountered
    private[this] var inaccessible: NameLookup = _ // records inaccessible symbol for error reporting in case none is found
    private[this] var defSym: Symbol           = _ // the directly found symbol
    private[this] var pre: Type                = _ // the prefix type of defSym, if a class member
    private[this] var cx: Context              = _ // the context under consideration
    private[this] var symbolDepth: Int         = _ // the depth of the directly found symbol

    def apply(thisContext: Context, name: Name)(qualifies: Symbol => Boolean): NameLookup = {
      lookupError  = null
      inaccessible = null
      defSym       = NoSymbol
      pre          = NoPrefix
      cx           = thisContext
      symbolDepth  = -1

      def finish(qual: Tree, sym: Symbol): NameLookup = (
        if (lookupError ne null) lookupError
        else sym match {
          case NoSymbol if inaccessible ne null => inaccessible
          case NoSymbol                         => LookupNotFound
          case _                                => LookupSucceeded(qual, sym)
        }
      )
      def finishDefSym(sym: Symbol, pre0: Type): NameLookup =
        if (!thisContext.unit.isJava && thisContext.requiresQualifier(sym))
          finish(gen.mkAttributedQualifier(pre0), sym)
        else
          finish(EmptyTree, sym)

      def isPackageOwnedInDifferentUnit(s: Symbol) = (
        s.isDefinedInPackage && (
             !currentRun.compiles(s)
          || thisContext.unit.exists && s.sourceFile != thisContext.unit.source.file
        )
      )
      def lookupInPrefix(name: Name) = {
        if (thisContext.unit.isJava) {
          thisContext.javaFindMember(pre, name, qualifies) match {
            case (_, NoSymbol) =>
              NoSymbol
            case (pre1, sym) =>
              pre = pre1
              sym
          }
        } else {
          pre.member(name).filter(qualifies)
        }
      }

      def accessibleInPrefix(s: Symbol) =
        thisContext.isAccessible(s, pre, superAccess = false)

      def searchPrefix = {
        cx = cx.enclClass
        val found0 = lookupInPrefix(name)
        val found1 = found0 filter accessibleInPrefix
        if (found0.exists && !found1.exists && inaccessible == null)
          inaccessible = LookupInaccessible(found0, analyzer.lastAccessCheckDetails)

        found1
      }

      def lookupInScope(owner: Symbol, pre: Type, scope: Scope): Symbol = {
        var e = scope.lookupEntry(name)
        while (e != null && !qualifies(e.sym)) {
          e = scope.lookupNextEntry(e)
        }
        if (e == null) {
          NoSymbol
        } else {
          val e1 = e
          val e1Sym = e.sym
          var syms: mutable.ListBuffer[Symbol] = null
          e = scope.lookupNextEntry(e)
          while (e ne null) {
            if (e.depth == e1.depth && e.sym != e1Sym && qualifies(e.sym)) {
              if (syms eq null) {
                syms = new mutable.ListBuffer[Symbol]
                syms += e1Sym
              }
              syms += e.sym
            }
            e = scope.lookupNextEntry(e)
          }
          // we have a winner: record the symbol depth
          symbolDepth = (cx.depth - cx.scope.nestingLevel) + e1.depth

          if (syms eq null) e1Sym
          else owner.newOverloaded(pre, syms.toList)
        }
      }

      // Constructor lookup should only look in the decls of the enclosing class
      // not in the self-type, nor in the enclosing context, nor in imports (scala/bug#4460, scala/bug#6745)
      if (name == nme.CONSTRUCTOR) {
        val enclClassSym = cx.enclClass.owner
        val scope = cx.enclClass.prefix.baseType(enclClassSym).decls
        val constructorSym = lookupInScope(enclClassSym, cx.enclClass.prefix, scope)
        return finishDefSym(constructorSym, cx.enclClass.prefix)
      }

      var foundInSuper: Boolean = false
      var outerDefSym: Symbol = NoSymbol

      // cx.scope eq null arises during FixInvalidSyms in Duplicators
      while (defSym == NoSymbol && (cx ne NoContext) && (cx.scope ne null)) {
        pre    = cx.enclClass.prefix
        defSym = lookupInScope(cx.owner, cx.enclClass.prefix, cx.scope) match {
          case NoSymbol =>
            val prefixSym = searchPrefix
            if (currentRun.isScala213 && prefixSym.exists && prefixSym.alternatives.forall(_.owner != cx.owner))
              foundInSuper = true
            prefixSym
          case found =>
            found
        }
        if (!defSym.exists)
          cx = cx.outer // push further outward
      }

      if (symbolDepth < 0)
        symbolDepth = cx.depth

      def checkAmbiguousWithEnclosing(): Unit = if (foundInSuper && !thisContext.unit.isJava) {
        val defPre = pre
        val defCx = cx
        val defDepth = symbolDepth

        while ((cx ne NoContext) && (cx.owner == defCx.owner || cx.depth >= symbolDepth)) cx = cx.outer

        while ((cx ne NoContext) && (cx.scope ne null)) {
          pre = cx.enclClass.prefix
          val next = lookupInScope(cx.owner, cx.enclClass.prefix, cx.scope).orElse(searchPrefix).filter(_.owner == cx.owner)
          if (next.exists && thisContext.unit.exists && next.sourceFile == thisContext.unit.source.file) {
            outerDefSym = next
            cx = NoContext
          } else
            cx = cx.outer
        }
        if (outerDefSym.exists) {
          if (outerDefSym == defSym)
            outerDefSym = NoSymbol
          else if ((defSym.isAliasType || outerDefSym.isAliasType) && defPre.memberType(defSym) =:= pre.memberType(outerDefSym))
            outerDefSym = NoSymbol
          else if (defSym.isStable && outerDefSym.isStable &&
            (pre.memberType(outerDefSym).termSymbol == defSym || defPre.memberType(defSym).termSymbol == outerDefSym))
            outerDefSym = NoSymbol
        }

        pre = defPre
        cx = defCx
        symbolDepth = defDepth
      }
      checkAmbiguousWithEnclosing()

      var impSym: Symbol = NoSymbol
      val importCursor = new ImportCursor(thisContext, name)
      import importCursor.{imp1, imp2}

      def lookupImport(imp: ImportInfo, requireExplicit: Boolean) =
        thisContext.importedAccessibleSymbol(imp, name, requireExplicit, record = true) filter qualifies

      var importLookupFor213MigrationWarning = false
      def depthOk213 = {
        currentRun.isScala213 && !thisContext.unit.isJava && !cx(ContextMode.InPackageClauseName) && defSym.exists && isPackageOwnedInDifferentUnit(defSym) && {
          importLookupFor213MigrationWarning = true
          true
        }
      }

      // Java: A single-type-import declaration d in a compilation unit c of package p
      // that imports a type named n shadows, throughout c, the declarations of:
      //
      //  1) any top level type named n declared in another compilation unit of p
      //
      // A type-import-on-demand declaration never causes any other declaration to be shadowed.
      //
      // Scala: Bindings of different kinds have a precedence defined on them:
      //
      //  1) Definitions and declarations that are local, inherited, or made available by a
      //     package clause in the same compilation unit where the definition occurs have
      //     highest precedence.
      //  2) Explicit imports have next highest precedence.
      def depthOk(imp: ImportInfo) = {
        imp.depth > symbolDepth ||
          (thisContext.unit.isJava && imp.isExplicitImport(name) && imp.depth == symbolDepth) ||
          depthOk213
      }

      while (!impSym.exists && importCursor.imp1Exists && depthOk(importCursor.imp1)) {
        impSym = lookupImport(imp1, requireExplicit = false)
        if (!impSym.exists)
          importCursor.advanceImp1Imp2()
      }

      if (impSym.exists && importLookupFor213MigrationWarning) {
        if (impSym != defSym && imp1.depth >= symbolDepth && !imp1.isExplicitImport(name)) {
          val msg =
            s"""This wildcard import imports ${impSym.fullName}, which is shadowed by ${defSym.fullName}.
               |This is not according to the language specification and has changed in Scala 2.13, where ${impSym.fullName} takes precedence.
               |To keep the same meaning in 2.12 and 2.13, un-import ${name} by adding `$name => _` to the import list.""".stripMargin
          runReporting.warning(imp1.pos, msg, WarningCategory.Other, "")
        }
        impSym = NoSymbol
      }

      if (defSym.exists && impSym.exists) {
        // imported symbols take precedence over package-owned symbols in different compilation units.
        if (isPackageOwnedInDifferentUnit(defSym))
          defSym = NoSymbol
        // Defined symbols take precedence over erroneous imports.
        else if (impSym.isError || impSym.name == nme.CONSTRUCTOR)
          impSym = NoSymbol
        // Otherwise they are irreconcilably ambiguous
        else
          return ambiguousDefnAndImport(defSym.alternatives.head.owner, imp1)
      }

      // At this point only one or the other of defSym and impSym might be set.
      if (defSym.exists) {
        val ambiguity = ambiguousWithEnclosing(outerDefSym, defSym, cx.enclClass.owner)
        ambiguity.getOrElse(finishDefSym(defSym, pre))
      } else if (impSym.exists) {
        // If we find a competitor imp2 which imports the same name, possible outcomes are:
        //
        //  - same depth, imp1 wild, imp2 explicit:        imp2 wins, drop imp1
        //  - same depth, imp1 wild, imp2 wild:            ambiguity check
        //  - same depth, imp1 explicit, imp2 explicit:    ambiguity check
        //  - differing depth, imp1 wild, imp2 explicit:   ambiguity check
        //  - all others:                                  imp1 wins, drop imp2
        //
        // The ambiguity check is: if we can verify that both imports refer to the same
        // symbol (e.g. import foo.X followed by import foo._) then we discard imp2
        // and proceed. If we cannot, issue an ambiguity error.
        while (lookupError == null && importCursor.keepLooking) {
          // If not at the same depth, limit the lookup to explicit imports.
          // This is desirable from a performance standpoint (compare to
          // filtering after the fact) but also necessary to keep the unused
          // import check from being misled by symbol lookups which are not
          // actually used.
          val other = lookupImport(imp2, requireExplicit = !importCursor.sameDepth)

          @inline def imp1wins() { importCursor.advanceImp2() }
          @inline def imp2wins() { impSym = other; importCursor.advanceImp1Imp2() }
          if (!other.exists) // imp1 wins; drop imp2 and continue.
            imp1wins()
          else if (importCursor.imp2Wins) // imp2 wins; drop imp1 and continue.
            imp2wins()
          else thisContext.resolveAmbiguousImport(name, imp1, imp2) match {
            case Some(imp) => if (imp eq imp1) imp1wins() else imp2wins()
            case _         => lookupError = ambiguousImports(imp1, imp2)
          }
        }
        // optimization: don't write out package prefixes
        finish(duplicateAndResetPos.transform(imp1.qual), impSym)
      }
      else finish(EmptyTree, NoSymbol)
    }
  }

  /** A `Context` focussed on an `Import` tree */
  final class ImportContext(tree: Tree, owner: Symbol, scope: Scope,
                            unit: CompilationUnit, outer: Context,
                            override val isRootImport: Boolean, depth: Int,
                            reporter: ContextReporter) extends Context(tree, owner, scope, unit, outer, depth, reporter) {
    private[this] val impInfo: ImportInfo = {
      val info = new ImportInfo(tree.asInstanceOf[Import], outerDepth)
      if (settings.warnUnusedImport && openMacros.isEmpty && !isRootImport) // excludes java.lang/scala/Predef imports
        allImportInfos(unit) ::= (info, owner)
      info
    }
    override final def imports      = impInfo :: super.imports
    override final def firstImport  = Some(impInfo)
    override final def importOrNull = impInfo

    override final def toString     = s"${super.toString} with ImportContext { $impInfo; outer.owner = ${outer.owner} }"
  }

  /** A reporter for use during type checking. It has multiple modes for handling errors.
   *
   *  The default (immediate mode) is to send the error to the global reporter.
   *  When switched into buffering mode via makeBuffering, errors and warnings are buffered and not be reported
   *  (there's a special case for ambiguity errors for some reason: those are force to the reporter when context.ambiguousErrors,
   *   or else they are buffered -- TODO: can we simplify this?)
   *
   *  When using the type checker after typers, an error results in a TypeError being thrown. TODO: get rid of this mode.
   *
   *  To handle nested contexts, reporters share buffers. TODO: only buffer in BufferingReporter, emit immediately in ImmediateReporter
   */
  abstract class ContextReporter(private[this] var _errorBuffer: mutable.LinkedHashSet[AbsTypeError] = null, private[this] var _warningBuffer: mutable.LinkedHashSet[(Position, String, WarningCategory, Symbol)] = null) {
    type Error = AbsTypeError
    type Warning = (Position, String, WarningCategory, Symbol)

    def issue(err: AbsTypeError)(implicit context: Context): Unit = error(context.fixPosition(err.errPos), addDiagString(err.errMsg))

    def echo(msg: String): Unit                = echo(NoPosition, msg)
    def echo(pos: Position, msg: String): Unit = reporter.echo(pos, msg)

    def warning(pos: Position, msg: String, category: WarningCategory, site: Symbol): Unit =
      runReporting.warning(pos, msg, category, site)

    def error(pos: Position, msg: String): Unit

    protected def handleSuppressedAmbiguous(err: AbsAmbiguousTypeError): Unit = ()

    def makeImmediate: ContextReporter = this
    def makeBuffering: ContextReporter = this
    def isBuffering: Boolean           = false
    def isThrowing: Boolean            = false

    /** Emit an ambiguous error according to context.ambiguousErrors
     *
     *  - when true, use global.reporter regardless of whether we're buffering (TODO: can we change this?)
     *  - else, let this context reporter decide
     */
    final def issueAmbiguousError(err: AbsAmbiguousTypeError)(implicit context: Context): Unit =
      if (context.ambiguousErrors) reporter.error(context.fixPosition(err.errPos), addDiagString(err.errMsg)) // force reporting... see TODO above
      else handleSuppressedAmbiguous(err)

    @inline final def withFreshErrorBuffer[T](expr: => T): T = {
      val previousBuffer = _errorBuffer
      _errorBuffer = null
      val res = expr // expr will read _errorBuffer
      _errorBuffer = previousBuffer
      res
    }

    final def propagateErrorsTo[T](target: ContextReporter): Unit = {
      if (this ne target) {  // `this eq target` in e.g., test/files/neg/divergent-implicit.scala
        if (hasErrors) {
          // assert(target.errorBuffer ne _errorBuffer)
          if (target.isBuffering) {
            target ++= errors
          } else {
            errors.foreach(e => target.error(e.errPos, e.errMsg))
          }
          // TODO: is clearAllErrors necessary? (no tests failed when dropping it)
          // NOTE: even though `this ne target`, it may still be that `target.errorBuffer eq _errorBuffer`,
          // so don't clear the buffer, but null out the reference so that a new one will be created when necessary (should be never??)
          // (we should refactor error buffering to avoid mutation on shared buffers)
          clearAllErrors()
        }
        // TODO propagate warnings if no errors, like `silent` does?
      }
    }

    final def hasErrors: Boolean = _errorBuffer != null && errorBuffer.nonEmpty

    // TODO: everything below should be pushed down to BufferingReporter (related to buffering)
    // Implicit relies on this most heavily, but there you know reporter.isInstanceOf[BufferingReporter]
    // can we encode this statically?

    // have to pass in context because multiple contexts may share the same ReportBuffer
    def reportFirstDivergentError(fun: Tree, param: Symbol, paramTp: Type)(implicit context: Context): Unit =
      errors.collectFirst {
        case dte: DivergentImplicitTypeError => dte
      } match {
        case Some(divergent) =>
          // DivergentImplicit error has higher priority than "no implicit found"
          // no need to issue the problem again if we are still in silent mode
          if (context.reportErrors) {
            context.issue(divergent.withPt(paramTp))
            errorBuffer.retain {
              case dte: DivergentImplicitTypeError => false
              case _ => true
            }
          }
        case _ =>
          NoImplicitFoundError(fun, param)(context)
      }

    def retainDivergentErrorsExcept(saved: DivergentImplicitTypeError) =
      errorBuffer.retain {
        case err: DivergentImplicitTypeError => err ne saved
        case _ => false
      }

    def propagateImplicitTypeErrorsTo(target: ContextReporter) = {
      errors foreach {
        case err@(_: DivergentImplicitTypeError | _: AmbiguousImplicitTypeError) =>
          target.errorBuffer += err
        case _ =>
      }
      // debuglog("propagateImplicitTypeErrorsTo: " + errors)
    }

    protected def addDiagString(msg: String)(implicit context: Context): String = {
      val diagUsedDefaultsMsg = "Error occurred in an application involving default arguments."
      if (context.diagUsedDefaults && !(msg endsWith diagUsedDefaultsMsg)) msg + "\n" + diagUsedDefaultsMsg
      else msg
    }

    final def emitWarnings() = if (_warningBuffer != null) {
      _warningBuffer foreach {
        case (pos, msg, category, site) => runReporting.warning(pos, msg, category, site)
      }
      _warningBuffer = null
    }

    // [JZ] Contexts, pre- the scala/bug#7345 refactor, avoided allocating the buffers until needed. This
    // is replicated here out of conservatism.
    private def newBuffer[A]    = mutable.LinkedHashSet.empty[A] // Important to use LinkedHS for stable results.
    final protected def errorBuffer   = { if (_errorBuffer == null) _errorBuffer = newBuffer; _errorBuffer }
    final protected def warningBuffer = { if (_warningBuffer == null) _warningBuffer = newBuffer; _warningBuffer }

    final def errors: immutable.Seq[Error]     = errorBuffer.toVector
    final def warnings: immutable.Seq[Warning] = warningBuffer.toVector
    final def firstError: Option[AbsTypeError] = errorBuffer.headOption

    // TODO: remove ++= and clearAll* entirely in favor of more high-level combinators like withFreshErrorBuffer
    final private[typechecker] def ++=(errors: Traversable[AbsTypeError]): Unit = errorBuffer ++= errors

    // null references to buffers instead of clearing them,
    // as the buffers may be shared between different reporters
    final def clearAll(): Unit       = { _errorBuffer = null; _warningBuffer = null }
    final def clearAllErrors(): Unit = { _errorBuffer = null }
  }

  private[typechecker] class ImmediateReporter(_errorBuffer: mutable.LinkedHashSet[AbsTypeError] = null, _warningBuffer: mutable.LinkedHashSet[(Position, String, WarningCategory, Symbol)] = null) extends ContextReporter(_errorBuffer, _warningBuffer) {
    override def makeBuffering: ContextReporter = new BufferingReporter(errorBuffer, warningBuffer)
    def error(pos: Position, msg: String): Unit = reporter.error(pos, msg)
 }

  private[typechecker] class BufferingReporter(_errorBuffer: mutable.LinkedHashSet[AbsTypeError] = null, _warningBuffer: mutable.LinkedHashSet[(Position, String, WarningCategory, Symbol)] = null) extends ContextReporter(_errorBuffer, _warningBuffer) {
    override def isBuffering = true

    override def issue(err: AbsTypeError)(implicit context: Context): Unit             = errorBuffer += err

    // this used to throw new TypeError(pos, msg) -- buffering lets us report more errors (test/files/neg/macro-basic-mamdmi)
    // the old throwing behavior was relied on by diagnostics in manifestOfType
    def error(pos: Position, msg: String): Unit = errorBuffer += TypeErrorWrapper(new TypeError(pos, msg))

    override def warning(pos: Position, msg: String, category: WarningCategory, site: Symbol): Unit =
      warningBuffer += ((pos, msg, category, site))

    override protected def handleSuppressedAmbiguous(err: AbsAmbiguousTypeError): Unit = errorBuffer += err

    // TODO: emit all buffered errors, warnings
    override def makeImmediate: ContextReporter = new ImmediateReporter(errorBuffer, warningBuffer)
  }

  /** Used after typer (specialization relies on TypeError being thrown, among other post-typer phases).
   *
   * TODO: get rid of it, use ImmediateReporter and a check for reporter.hasErrors where necessary
   */
  private[typechecker] class ThrowingReporter extends ContextReporter {
    override def isThrowing = true
    def error(pos: Position, msg: String): Unit = throw new TypeError(pos, msg)
  }

  /** Used during a run of [[scala.tools.nsc.typechecker.TreeCheckers]]? */
  private[typechecker] class CheckingReporter extends ContextReporter {
    def error(pos: Position, msg: String): Unit = onTreeCheckerError(pos, msg)
  }

  class ImportInfo(val tree: Import, val depth: Int) {
    def pos = tree.pos
    def posOf(sel: ImportSelector) =
      if (sel.namePos >= 0) tree.pos withPoint sel.namePos else tree.pos

    /** The prefix expression */
    def qual: Tree = tree.symbol.info match {
      case ImportType(expr) => expr
      case ErrorType        => tree setType NoType // fix for #2870
      case _                => throw new FatalError("symbol " + tree.symbol + " has bad type: " + tree.symbol.info) //debug
    }

    /** Is name imported explicitly, not via wildcard? */
    def isExplicitImport(name: Name): Boolean =
      tree.selectors exists (_.rename == name.toTermName)

    /** The symbol with name `name` imported from import clause `tree`. */
    def importedSymbol(name: Name): Symbol = importedSymbol(name, requireExplicit = false, record = true)

    private def recordUsage(sel: ImportSelector, result: Symbol): Unit = {
      debuglog(s"In $this at ${ pos.source.file.name }:${ posOf(sel).line }, selector '${ selectorString(sel)
        }' resolved to ${
          if (tree.symbol.hasCompleteInfo) s"(qual=$qual, $result)"
          else s"(expr=${tree.expr}, ${result.fullLocationString})"
        }")
      allUsedSelectors(this) += sel
    }

    /** If requireExplicit is true, wildcard imports are not considered. */
    def importedSymbol(name: Name, requireExplicit: Boolean, record: Boolean): Symbol = {
      var result: Symbol = NoSymbol
      var renamed = false
      var selectors = tree.selectors
      @inline def current = selectors.head
      while ((selectors ne Nil) && result == NoSymbol) {
        def sameName(name: Name, other: Name) = {
          (name eq other) || (name ne null) && name.start == other.start && name.length == other.length
        }
        def lookup(target: Name): Symbol = {
          if (pos.source.isJava) {
            val (_, sym) = NoContext.javaFindMember(qual.tpe, target, _ => true)
            // We don't need to propagate the new prefix back out to the result of `Context.lookupSymbol`
            // because typechecking .java sources doesn't need it.
            sym
          }
          else qual.tpe nonLocalMember target
        }
        if (sameName(current.rename, name)) {
          val target = current.name asTypeOf name
          result = lookup(target)
        } else if (sameName(current.name, name))
          renamed = true
        else if (current.name == nme.WILDCARD && !renamed && !requireExplicit)
          result = lookup(name)

        if (result == NoSymbol)
          selectors = selectors.tail
      }
      if (record && settings.warnUnusedImport && selectors.nonEmpty && result != NoSymbol && pos != NoPosition)
        recordUsage(current, result)

      // Harden against the fallout from bugs like scala/bug#6745
      //
      // [JZ] I considered issuing a devWarning and moving the
      //      check inside the above loop, as I believe that
      //      this always represents a mistake on the part of
      //      the caller.
      if (definitions isImportable result) result
      else NoSymbol
    }
    private def selectorString(s: ImportSelector): String = {
      if (s.name == nme.WILDCARD && s.rename == null) "_"
      else if (s.name == s.rename) "" + s.name
      else s.name + " => " + s.rename
    }

    def allImportedSymbols: Iterable[Symbol] =
      importableMembers(qual.tpe) flatMap (transformImport(tree.selectors, _))

    private def transformImport(selectors: List[ImportSelector], sym: Symbol): List[Symbol] = selectors match {
      case List() => List()
      case List(ImportSelector(nme.WILDCARD, _, _, _)) => List(sym)
      case ImportSelector(from, _, to, _) :: _ if from == (if (from.isTermName) sym.name.toTermName else sym.name.toTypeName) =>
        if (to == nme.WILDCARD) List()
        else List(sym.cloneSymbol(sym.owner, sym.rawflags, to))
      case _ :: rest => transformImport(rest, sym)
    }

    override def hashCode = tree.##
    override def equals(other: Any) = other match {
      case that: ImportInfo => (tree == that.tree)
      case _                => false
    }
    override def toString = tree.toString
  }

  type ImportType = global.ImportType
  val ImportType = global.ImportType

  /** Walks a pair of references (`imp1` and `imp2`) up the context chain to ImportContexts */
  private final class ImportCursor(var ctx: Context, name: Name) {
    private var imp1Ctx = ctx.enclosingImport
    private var imp2Ctx = imp1Ctx.outer.enclosingImport

    def advanceImp1Imp2(): Unit = {
      imp1Ctx = imp2Ctx; imp2Ctx = imp1Ctx.outer.enclosingImport
    }
    def advanceImp2(): Unit = {
      imp2Ctx = imp2Ctx.outer.enclosingImport
    }
    def imp1Exists: Boolean = imp1Ctx.importOrNull != null
    def imp1: ImportInfo = imp1Ctx.importOrNull
    def imp2: ImportInfo = imp2Ctx.importOrNull

    // We continue walking down the imports as long as the tail is non-empty, which gives us:
    //   imports  ==  imp1 :: imp2 :: _
    // And at least one of the following is true:
    //   - imp1 and imp2 are at the same depth
    //   - imp1 is a wildcard import, so all explicit imports from outer scopes must be checked
    def keepLooking: Boolean = imp2Exists && (sameDepth || !imp1Explicit)
    def imp2Wins: Boolean = sameDepth && !imp1Explicit && imp2Explicit
    def sameDepth: Boolean = imp1.depth == imp2.depth

    private def imp2Exists = imp2Ctx.importOrNull != null
    private def imp1Explicit = imp1 isExplicitImport name
    private def imp2Explicit = imp2 isExplicitImport name
  }
}

object ContextMode {
  import scala.language.implicitConversions
  private implicit def liftIntBitsToContextState(bits: Int): ContextMode = apply(bits)
  def apply(bits: Int): ContextMode = new ContextMode(bits)
  final val NOmode: ContextMode                   = 0

  final val AmbiguousErrors: ContextMode          = 1 << 2

  /** Are we in a secondary constructor after the this constructor call? */
  final val ConstructorSuffix: ContextMode        = 1 << 3

  /** For method context: were returns encountered? */
  final val ReturnsSeen: ContextMode              = 1 << 4

  /** Is this context (enclosed in) a constructor call?
    * (the call to the super or self constructor in the first line of a constructor.)
    * In such a context, the object's fields should not be in scope
    */
  final val SelfSuperCall: ContextMode            = 1 << 5

  // TODO harvest documentation for this
  final val ImplicitsEnabled: ContextMode         = 1 << 6

  final val MacrosEnabled: ContextMode            = 1 << 7

  /** To selectively allow enrichment in patterns, where other kinds of implicit conversions are not allowed */
  final val EnrichmentEnabled: ContextMode        = 1 << 8


  /** Are we retypechecking arguments independently from the function applied to them? See `Typer.tryTypedApply`
   *  TODO - iron out distinction/overlap with SecondTry.
   */
  final val ReTyping: ContextMode                 = 1 << 10

  /** Are we typechecking pattern alternatives. Formerly ALTmode. */
  final val PatternAlternative: ContextMode       = 1 << 11

  /** Are star patterns allowed. Formerly STARmode. */
  final val StarPatterns: ContextMode             = 1 << 12

  /** Are we typing the "super" in a superclass constructor call super.<init>. Formerly SUPERCONSTRmode. */
  final val SuperInit: ContextMode                = 1 << 13

  /*  Is this the second attempt to type this tree? In that case functions
   *  may no longer be coerced with implicit views. Formerly SNDTRYmode.
   */
  final val SecondTry: ContextMode                = 1 << 14

  /** Are we in return position? Formerly RETmode. */
  final val ReturnExpr: ContextMode               = 1 << 15

  /** Are unapplied type constructors allowed here? Formerly HKmode. */
  final val TypeConstructorAllowed: ContextMode   = 1 << 16

  /** Should a dead code warning be issued for a Nothing-typed argument to the current application. */
  final val SuppressDeadArgWarning: ContextMode   = 1 << 17

  /** Were default arguments used? */
  final val DiagUsedDefaults: ContextMode         = 1 << 18

  final val InPackageClauseName: ContextMode      = 1 << 19

  /** TODO: The "sticky modes" are EXPRmode, PATTERNmode, TYPEmode.
   *  To mimic the sticky mode behavior, when captain stickyfingers
   *  comes around we need to propagate those modes but forget the other
   *  context modes which were once mode bits; those being so far the
   *  ones listed here.
   */
  final val FormerNonStickyModes: ContextMode = (
    PatternAlternative | StarPatterns | SuperInit | SecondTry | ReturnExpr | TypeConstructorAllowed
  )

  final val DefaultMode: ContextMode = MacrosEnabled

  private val contextModeNameMap = Map(
    AmbiguousErrors        -> "AmbiguousErrors",
    ConstructorSuffix      -> "ConstructorSuffix",
    SelfSuperCall          -> "SelfSuperCall",
    ImplicitsEnabled       -> "ImplicitsEnabled",
    MacrosEnabled          -> "MacrosEnabled",
    ReTyping               -> "ReTyping",
    PatternAlternative     -> "PatternAlternative",
    StarPatterns           -> "StarPatterns",
    SuperInit              -> "SuperInit",
    SecondTry              -> "SecondTry",
    TypeConstructorAllowed -> "TypeConstructorAllowed",
    SuppressDeadArgWarning -> "SuppressDeadArgWarning",
    DiagUsedDefaults       -> "DiagUsedDefaults"
  )
}

/**
 * A value class to carry the boolean flags of a context, such as whether errors should
 * be buffered or reported.
 */
final class ContextMode private (val bits: Int) extends AnyVal {
  import ContextMode._

  def &(other: ContextMode): ContextMode  = new ContextMode(bits & other.bits)
  def |(other: ContextMode): ContextMode  = new ContextMode(bits | other.bits)
  def &~(other: ContextMode): ContextMode = new ContextMode(bits & ~(other.bits))
  def set(value: Boolean, mask: ContextMode) = if (value) |(mask) else &~(mask)

  def inAll(required: ContextMode)        = (this & required) == required
  def inAny(required: ContextMode)        = (this & required) != NOmode
  def inNone(prohibited: ContextMode)     = (this & prohibited) == NOmode

  override def toString =
    if (bits == 0) "NOmode"
    else (contextModeNameMap filterKeys inAll).values.toList.sorted mkString " "
}
