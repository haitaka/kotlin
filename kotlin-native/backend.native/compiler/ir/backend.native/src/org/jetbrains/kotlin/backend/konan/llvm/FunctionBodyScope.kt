/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.llvm

import kotlinx.cinterop.*
import llvm.*
import org.jetbrains.kotlin.backend.common.lower.coroutines.getOrCreateFunctionWithContinuationStub
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.ir.*
import org.jetbrains.kotlin.backend.konan.lower.*
import org.jetbrains.kotlin.backend.konan.lower.ReifiedFunctionLowering.Companion.isReifiedInline
import org.jetbrains.kotlin.ir.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name

/**
 * A basic block together with an optional phi node for merging values at that block.
 * [valuePhi] is `null` when the block receives a Unit value.
 */
internal data class ContinuationBlock(val block: LLVMBasicBlockRef, val valuePhi: LLVMValueRef?)

/**
 * Owns all code-generation scope machinery for a [CodeGeneratorVisitor]: the scope stack,
 * the quick-access [currentFunctionScope] reference, and all concrete scope classes as `inner`
 * classes (so they capture the [FunctionBodyScope] instead of the visitor, decoupling the scope
 * logic from IR traversal).
 *
 * The two callbacks bridge the places where scope classes need the visitor:
 * @param evaluateExpressionAndJump emits an IR expression and branches to a continuation block.
 * @param genInstanceOf emits an `instanceof` check for a given class.
 */
internal class FunctionBodyScope(
        val generationState: NativeGenerationState,
        val codegen: CodeGenerator,
        private val evaluateExpressionAndJump: (IrExpression, ContinuationBlock) -> Unit,
        private val genInstanceOf: (LLVMValueRef, IrClass) -> LLVMValueRef,
) {
    private val context get() = generationState.context
    private val llvm get() = generationState.llvm
    private val debugInfo get() = generationState.debugInfo

    // -------------------------------------------------------------------------
    // Scope stack

    private var _current: CodeContext = TopLevelCodeContext

    /** The currently active [CodeContext]. */
    val current: CodeContext get() = _current

    /**
     * Executes [block] with [scope] substituted as [current], running its lifecycle hooks.
     */
    inline fun <R> using(scope: CodeContext?, block: () -> R): R {
        val old = _current
        if (scope != null) {
            _current = scope
            scope.onEnter()
        }
        try {
            return block()
        } catch (e: Exception) {
            throw (scope?.wrapException(e) ?: e)
        } finally {
            scope?.onExit()
            _current = old
        }
    }

    // -------------------------------------------------------------------------
    // Ambient state

    /** Quick access to the innermost [FunctionScope]; set/restored in [FunctionScope.onEnter]/[onExit]. */
    var currentFunctionScope: FunctionScope? = null

    /** The [FunctionGenerationContext] for the current function. */
    val functionGenerationContext: FunctionGenerationContext
        get() = currentFunctionScope!!.functionGenerationContext

    // =========================================================================
    // InnerScopeImpl — inner class that captures [_current] at construction time

    /** Delegates all operations to the scope that was active when this scope was constructed. */
    internal abstract inner class InnerScopeImpl : InnerScope(_current)

    // =========================================================================
    // Scope classes

    internal open inner class StackLocalsScope : InnerScopeImpl() {
        override fun onEnter() = functionGenerationContext.stackLocalsManager.enterScope()
        override fun onExit()  = functionGenerationContext.stackLocalsManager.exitScope()
    }

    internal inner class LoopScope(val loop: IrLoop) : StackLocalsScope() {
        val loopExit  = functionGenerationContext.basicBlock("loop_exit",  loop.endLocation)
        val loopCheck = functionGenerationContext.basicBlock("loop_check", loop.condition.startLocation)

        override fun genBreak(destination: IrBreak) {
            if (destination.loop == loop) functionGenerationContext.br(loopExit)
            else super.genBreak(destination)
        }

        override fun genContinue(destination: IrContinue) {
            if (destination.loop == loop) functionGenerationContext.br(loopCheck)
            else super.genContinue(destination)
        }
    }

    internal inner class VariableScope : InnerScopeImpl() {

        override fun genDeclareVariable(variable: IrVariable, value: LLVMValueRef?, variableLocation: VariableDebugLocation?): Int =
                functionGenerationContext.vars.createVariable(variable, value, variableLocation)

        override fun getDeclaredValue(value: IrValueDeclaration): Int {
            val index = functionGenerationContext.vars.indexOf(value)
            return if (index < 0) super.getDeclaredValue(value) else index
        }

        override fun genGetValue(value: IrValueDeclaration, resultSlot: LLVMValueRef?): LLVMValueRef {
            val index = functionGenerationContext.vars.indexOf(value)
            return if (index < 0) super.genGetValue(value, resultSlot)
            else functionGenerationContext.vars.load(index, resultSlot)
        }
    }

    internal open inner class ParameterScope(
            function: IrSimpleFunction?,
            private val functionGenerationContext: FunctionGenerationContext,
    ) : InnerScopeImpl() {

        val parameters = bindParameters(function)

        init {
            if (function != null) {
                parameters.forEach {
                    val parameter = it.key
                    val value = it.value
                    if (context.shouldContainDebugInfo()) {
                        val local = functionGenerationContext.vars.createParameterOnStack(
                                parameter, debugInfoIfNeeded(function, parameter))
                        functionGenerationContext.mapParameterForDebug(local, value)
                    } else {
                        functionGenerationContext.vars.createParameter(parameter, value)
                    }
                }
            }
        }

        override fun genGetValue(value: IrValueDeclaration, resultSlot: LLVMValueRef?): LLVMValueRef {
            val index = functionGenerationContext.vars.indexOf(value)
            return if (index < 0) super.genGetValue(value, resultSlot)
            else functionGenerationContext.vars.load(index, resultSlot)
        }
    }

    internal inner class FunctionScope private constructor(
            val functionGenerationContext: FunctionGenerationContext,
            val declaration: IrSimpleFunction?,
            val llvmFunction: LlvmCallable,
    ) : InnerScopeImpl() {

        constructor(declaration: IrSimpleFunction, functionGenerationContext: FunctionGenerationContext) :
                this(functionGenerationContext, declaration, codegen.llvmFunction(declaration))

        constructor(llvmFunction: LlvmCallable, functionGenerationContext: FunctionGenerationContext) :
                this(functionGenerationContext, null, llvmFunction)

        private var savedFunctionScope: FunctionScope? = null

        override fun onEnter() {
            savedFunctionScope = currentFunctionScope
            currentFunctionScope = this
        }

        override fun onExit() {
            currentFunctionScope = savedFunctionScope
        }

        override fun genReturn(target: IrSymbolOwner, value: LLVMValueRef?) {
            if (declaration == null || target == declaration) {
                if ((target as IrSimpleFunction).returnsUnit()) functionGenerationContext.ret(null)
                else functionGenerationContext.ret(value!!)
            } else {
                super.genReturn(target, value)
            }
        }

        override fun getReturnSlot(target: IrSymbolOwner): LLVMValueRef? =
                if (declaration == null || target == declaration) functionGenerationContext.returnSlot
                else super.getReturnSlot(target)

        override val exceptionHandler: ExceptionHandler
            get() = ExceptionHandler.Caller

        override fun functionScope(): CodeContext = this

        private val scope by lazy {
            if (context.shouldContainLocationDebugInfo()) declaration?.scope()
            else null
        }

        private val fileEntry = fileEntry()
        override fun location(offset: Int) = scope?.let { scope ->
            val [line, column] = fileEntry.lineAndColumn(offset)
            LocationInfo(scope, line, column)
        }

        override fun scope() = scope

        override fun wrapException(e: Exception) = NativeCodeGeneratorException.wrap(e, declaration)
    }

    internal abstract inner class CatchingScope : InnerScopeImpl() {

        private val landingpad: LLVMBasicBlockRef by lazy {
            using(outerContext) {
                functionGenerationContext.basicBlock("landingpad", endLocationInfoFromScope()) {
                    genLandingpad()
                }
            }
        }

        private val handler by lazy {
            using(outerContext) {
                continuationBlock(context.irBuiltIns.throwableClass.owner.defaultType, endLocationInfoFromScope()) {
                    genHandler(it.value)
                }
            }
        }

        private fun endLocationInfoFromScope(): LocationInfo? =
                currentFunctionScope?.declaration?.endLocation

        private fun FunctionGenerationContext.jumpToHandler(exception: LLVMValueRef) {
            jump(handler, exception)
        }

        private fun genLandingpad() {
            with(functionGenerationContext) {
                val exceptionPtr = catchKotlinException()
                jumpToHandler(exceptionPtr)
            }
        }

        override val exceptionHandler: ExceptionHandler
            get() = object : ExceptionHandler.Local() {
                override val unwind get() = landingpad

                override fun genThrow(functionGenerationContext: FunctionGenerationContext, kotlinException: LLVMValueRef) {
                    functionGenerationContext.jumpToHandler(kotlinException)
                }
            }

        protected abstract fun genHandler(exception: LLVMValueRef)
    }

    internal inner class CatchScope(
            private val catches: List<IrCatch>,
            private val success: ContinuationBlock,
    ) : CatchingScope() {

        override fun genHandler(exception: LLVMValueRef) {
            for (catch in catches) {
                fun genCatchBlock() {
                    using(VariableScope()) {
                        current.genDeclareVariable(catch.catchParameter, exception,
                            debugInfoIfNeeded(null, catch.catchParameter))
                        functionGenerationContext.generateFrameCheck()
                        evaluateExpressionAndJump(catch.result, success)
                    }
                }
                if (catch.catchParameter.type == context.irBuiltIns.throwableType) {
                    genCatchBlock()
                    return
                } else {
                    val isInstance = genInstanceOf(exception, catch.catchParameter.type.getClass()!!)
                    val body = functionGenerationContext.basicBlock("catch", catch.startLocation)
                    val nextCheck = functionGenerationContext.basicBlock("catchCheck", catch.endLocation)
                    functionGenerationContext.condBr(isInstance, body, nextCheck)
                    functionGenerationContext.appendingTo(body) { genCatchBlock() }
                    functionGenerationContext.positionAtEnd(nextCheck)
                }
            }
            outerContext.exceptionHandler.genThrow(functionGenerationContext, exception)
        }
    }

    internal inner class ReturnableBlockScope(
            val returnableBlock: IrReturnableBlock,
            val resultSlot: LLVMValueRef?,
    ) : InnerScopeImpl() {

        var bbExit: LLVMBasicBlockRef? = null
        var resultPhi: LLVMValueRef? = null

        private fun getExit(): LLVMBasicBlockRef {
            val location = returnableBlock.statements.lastOrNull()?.let { location(it.endOffset) }
            if (bbExit == null) bbExit = functionGenerationContext.basicBlock("returnable_block_exit", location)
            return bbExit!!
        }

        private fun getResult(): LLVMValueRef {
            if (resultPhi == null) {
                val bbCurrent = functionGenerationContext.currentBlock
                functionGenerationContext.positionAtEnd(getExit())
                resultPhi = functionGenerationContext.phi(returnableBlock.type.toLLVMType(llvm))
                functionGenerationContext.positionAtEnd(bbCurrent)
            }
            return resultPhi!!
        }

        override fun genReturn(target: IrSymbolOwner, value: LLVMValueRef?) {
            if (target != returnableBlock) { super.genReturn(target, value); return }
            functionGenerationContext.br(getExit())
            if (!returnableBlock.type.isUnit()) functionGenerationContext.assignPhis(getResult() to value!!)
        }

        override fun getReturnSlot(target: IrSymbolOwner): LLVMValueRef? =
                if (target == returnableBlock) resultSlot else super.getReturnSlot(target)

        override fun returnableBlockScope(): CodeContext = this
    }

    internal open inner class FileScope(private val file: IrFile?, val fileEntry: IrFileEntry) : InnerScopeImpl() {
        override fun fileScope(): CodeContext = this

        override fun location(offset: Int) = scope()?.let {
            val [line, column] = fileEntry.lineAndColumn(offset)
            LocationInfo(it, line, column)
        }

        @Suppress("UNCHECKED_CAST")
        private val scope by lazy {
            if (!context.shouldContainLocationDebugInfo()) return@lazy null
            fileEntry.diFileScope() as DIScopeOpaqueRef?
        }

        override fun scope() = scope

        override fun wrapException(e: Exception) = NativeCodeGeneratorException.wrap(e, file)
    }

    internal inner class ClassScope(val clazz: IrClass) : InnerScopeImpl() {
        val isExported get() = clazz.isExported()
        var offsetInBits = 0L
        val members = mutableListOf<DIDerivedTypeRef>()

        @Suppress("UNCHECKED_CAST")
        val scope = if (isExported && context.shouldContainDebugInfo()) debugInfo.objHeaderPointerType else null

        override fun classScope(): CodeContext = this
        override fun wrapException(e: Exception) = NativeCodeGeneratorException.wrap(e, clazz)
    }

    internal inner class InlinedBlockScope(val inlinedBlock: IrInlinedFunctionBlock)
        : FileScope(file = null, inlinedBlock.inlinedFunctionFileEntry) {

        private val inlineFunctionScope: DIScopeOpaqueRef? by lazy {
            val owner = inlinedBlock.inlinedFunctionSymbol?.owner
            require(owner == null || owner is IrSimpleFunction) {
                "Inline constructors should've been lowered: ${owner?.render()}"
            }
            if (owner != null && owner.startOffset != UNDEFINED_OFFSET) {
                @Suppress("USELESS_CAST")
                (owner as IrSimpleFunction).scope(fileEntry.line(inlinedBlock.inlinedFunctionStartOffset))
            } else {
                @Suppress("UNCHECKED_CAST")
                debugInfo.diFunctionScope(
                        inlinedBlock.inlinedFunctionFileEntry,
                        name = "<inlined-lambda>",
                        linkageName = "<inlined-lambda>",
                        inlinedBlock.inlinedFunctionFileEntry.line(inlinedBlock.inlinedFunctionStartOffset),
                        debugInfo.subroutineType(debugInfo.llvmTargetData, listOf(inlinedBlock.type)),
                        nodebug = false,
                        isTransparentStepping = false,
                ) as DIScopeOpaqueRef
            }
        }

        override fun location(offset: Int): LocationInfo? {
            val diScope = inlineFunctionScope ?: return null
            val inlinedAt = outerContext.location(inlinedBlock.startOffset) ?: return null
            val [line, column] = fileEntry.lineAndColumn(offset)
            return LocationInfo(diScope, line, column, inlinedAt)
        }

        override fun scope(): DIScopeOpaqueRef? =
                inlineFunctionScope.takeIf { context.shouldContainLocationDebugInfo() && inlinedBlock.startOffset != UNDEFINED_OFFSET }

        override fun wrapException(e: Exception): NativeCodeGeneratorException =
                NativeCodeGeneratorException.wrap(e, inlinedBlock.inlinedFunctionSymbol?.owner)
    }

    internal inner class SuspendableExpressionScope(val resumePoints: MutableList<LLVMBasicBlockRef>) : InnerScopeImpl() {
        override fun addResumePoint(bbLabel: LLVMBasicBlockRef): Int {
            val result = resumePoints.size
            resumePoints.add(bbLabel)
            return result
        }
    }

    internal inner class SuspensionPointScope(
            val suspensionPointId: IrVariable,
            val bbResume: LLVMBasicBlockRef,
            val bbResumeId: Int,
    ) : InnerScopeImpl() {
        override fun genGetValue(value: IrValueDeclaration, resultSlot: LLVMValueRef?): LLVMValueRef {
            if (value == suspensionPointId) return functionGenerationContext.blockAddress(bbResume)
            return super.genGetValue(value, resultSlot)
        }
    }

    // =========================================================================
    // Helpers

    fun fileEntry(): IrFileEntry = (current.fileScope() as FileScope).fileEntry

    inline fun <R> usingFileScope(fileEntry: IrFileEntry?, block: () -> R): R {
        val fileScope = if (fileEntry != null && fileEntry != fileEntry()) FileScope(null, fileEntry) else null
        return using(fileScope, block)
    }

    fun continuationBlock(type: IrType, locationInfo: LocationInfo?, code: (ContinuationBlock) -> Unit = {}): ContinuationBlock {
        val entry = functionGenerationContext.basicBlock("continuation_block", locationInfo)
        functionGenerationContext.appendingTo(entry) {
            val valuePhi = if (type.isUnit()) null else functionGenerationContext.phi(type.toLLVMType(llvm))
            val result = ContinuationBlock(entry, valuePhi)
            code(result)
            return result
        }
    }

    fun bindParameters(function: IrSimpleFunction?): Map<IrValueParameter, LLVMValueRef> {
        if (function == null) return emptyMap()
        return function.parameters.mapIndexed { i, irParameter ->
            val parameter = codegen.param(function, i)
            assert(irParameter.type.toLLVMType(llvm) == parameter.type)
            irParameter to parameter
        }.toMap()
    }

    fun debugInfoIfNeeded(function: IrSimpleFunction?, element: IrElement): VariableDebugLocation? {
        if (function == null || !element.needDebugInfo(context) || current.scope() == null) return null
        val locationInfo = current.location(element.startOffset) ?: return null
        val location = codegen.generateLocationInfo(locationInfo)
        val file = fileEntry().diFileScope()
        return when (element) {
            is IrVariable -> if (shouldGenerateDebugInfo(element)) debugInfoLocalVariableLocation(
                    builder       = debugInfo.builder,
                    functionScope = locationInfo.scope,
                    diType        = with(debugInfo) { element.type.diType(codegen.llvmTargetData) },
                    name          = element.debugNameConversion(),
                    file          = file,
                    line          = locationInfo.line,
                    location      = location) else null
            is IrValueParameter -> debugInfoParameterLocation(
                    builder       = debugInfo.builder,
                    functionScope = locationInfo.scope,
                    diType        = with(debugInfo) { element.type.diType(codegen.llvmTargetData) },
                    name          = element.debugNameConversion(),
                    argNo         = function.parameters.indexOf(element) + 1,
                    file          = file,
                    line          = locationInfo.line,
                    location      = location)
            else -> throw Error("Unsupported element type: ${ir2string(element)}")
        }
    }

    fun shouldGenerateDebugInfo(variable: IrVariable) = when (variable.origin) {
        IrDeclarationOrigin.FOR_LOOP_IMPLICIT_VARIABLE,
        IrDeclarationOrigin.FOR_LOOP_ITERATOR,
        IrDeclarationOrigin.IR_TEMPORARY_VARIABLE -> false
        else -> true
    }

    /** Convenience overload that creates a block, positions at it, runs [code], and returns the block. */
    fun FunctionGenerationContext.basicBlock(name: String, locationInfo: LocationInfo?, code: () -> Unit) =
            basicBlock(name, locationInfo).apply { appendingTo(this) { code() } }

    private var irFunctionSavedScope: Pair<IrSimpleFunction, DIScopeOpaqueRef?>? = null

    internal fun IrSimpleFunction.scope(): DIScopeOpaqueRef? =
            if (startOffset != UNDEFINED_OFFSET) {
                if (irFunctionSavedScope != null && this == irFunctionSavedScope!!.first)
                    irFunctionSavedScope!!.second
                else
                    scope(fileEntry().line(startOffset)).also { irFunctionSavedScope = Pair(this, it) }
            } else null

    @Suppress("UNCHECKED_CAST")
    private fun IrSimpleFunction.scope(startLine: Int): DIScopeOpaqueRef? {
        if (!context.shouldContainLocationDebugInfo()) return null
        val functionLlvmValue = when {
            isReifiedInline -> null
            codegen.isExternal(this) && !KonanBinaryInterface.isExported(this) -> null
            isSuspend -> this.getOrCreateFunctionWithContinuationStub(context).let { codegen.llvmFunctionOrNull(it) }
            else -> codegen.llvmFunctionOrNull(this)
        }
        return with(debugInfo) {
            val f = this@scope
            val nodebug = f.originalConstructor != null && f.parentAsClass.isSubclassOf(context.irBuiltIns.throwableClass.owner)
            if (functionLlvmValue != null) {
                subprograms.getOrPut(functionLlvmValue) {
                    val isTransparentStepping = generationState.config.enableDebugTransparentStepping &&
                            f.hasAnnotation(KonanFqNames.transparentForDebugger)
                    diFunctionScope(fileEntry(), functionLlvmValue.name!!, startLine, nodebug, isTransparentStepping).also {
                        if (!this@scope.isInline) functionLlvmValue.addDebugInfoSubprogram(it)
                    }
                } as DIScopeOpaqueRef
            } else {
                inlinedSubprograms.getOrPut(this@scope) {
                    diFunctionScope(fileEntry(), "<inlined-out:$name>", startLine, nodebug)
                } as DIScopeOpaqueRef
            }
        }
    }

    private fun IrSimpleFunction.returnsUnit() = returnType.isUnit().also {
        require(!isSuspend) { "Suspend functions should be lowered out at this point" }
    }

    // =========================================================================
    // Extension properties/functions used by scope classes

    val IrElement.startLocation: LocationInfo?
        get() = if (!context.shouldContainLocationDebugInfo()) null else current.location(startOffset)

    val IrElement.endLocation: LocationInfo?
        get() = if (!context.shouldContainLocationDebugInfo()) null else current.location(endOffset)

    fun IrFileEntry.diFileScope() = with(debugInfo) { diFileScope() }

    /** Converts certain `<this>` names to IDE-friendly names for the debugger. */
    fun IrValueDeclaration.debugNameConversion(): Name {
        if (name == Name.special("<this>")) {
            return when (origin) {
                IrDeclarationOrigin.IR_TEMPORARY_VARIABLE_FOR_INLINED_EXTENSION_RECEIVER ->
                    Name.identifier("__this")
                else -> Name.identifier("_this")
            }
        }
        return name
    }

    fun IrElement.startLine() = fileEntry().line(startOffset)

    val ContinuationBlock.value: LLVMValueRef
        get() = this.valuePhi ?: codegen.theUnitInstanceRef.llvm

    fun FunctionGenerationContext.jump(target: ContinuationBlock, value: LLVMValueRef?) {
        br(target.block)
        if (target.valuePhi != null) assignPhis(target.valuePhi to value!!)
    }
}
