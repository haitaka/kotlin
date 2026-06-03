/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.llvm

import org.jetbrains.kotlin.backend.konan.KonanFqNames
import org.jetbrains.kotlin.backend.konan.ir.isBuiltInOperator
import org.jetbrains.kotlin.backend.konan.ir.isComparisonFunction
import org.jetbrains.kotlin.backend.konan.ir.isTypedIntrinsic
import org.jetbrains.kotlin.backend.konan.lower.StaticInitializersOrigins
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isDoubleOrFloatWithoutNullability
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isOverridable
import org.jetbrains.kotlin.ir.util.isReal

/**
 * Decisions about *what* an IR construct should be translated into, kept separate from *how* it is
 * emitted. Code here only reads IR and makes semantic choices; it does not touch the LLVM-C API
 * (`llvm.*`), so the emission layer maps the returned descriptions onto LLVM itself.
 */

internal fun IrSimpleFunction.shouldGenerateBody(): Boolean = modality != Modality.ABSTRACT && !isExternal

/**
 * Whether this call is dispatched virtually (as opposed to a direct/static call): it has no
 * `super` qualifier and targets an overridable function.
 */
internal fun IrCall.isVirtualCall(): Boolean = superQualifierSymbol?.owner == null && symbol.owner.isOverridable

/** Whether this field is `@Volatile`. */
internal fun IrField.isVolatileField(): Boolean = hasAnnotation(KonanFqNames.volatile)

//-------------------------------------------------------------------------//
// Builtin operator classification
//-------------------------------------------------------------------------//

/** A relational comparison operator. */
internal enum class ComparisonOp { GT, GE, LT, LE }

/**
 * How a [ComparisonOp] should be carried out. Determined from the IR operand types (the only
 * basis available before emission): floating-point operands compare as [FLOATING], `Char`
 * operands as [UNSIGNED], everything else as [SIGNED].
 */
internal enum class ComparisonKind { SIGNED, UNSIGNED, FLOATING }

/**
 * Description of a call to one of [IrBuiltIns]' operator functions (the calls for which
 * `IrSimpleFunction.isBuiltInOperator` holds).
 */
internal sealed interface BuiltinOperatorKind {
    /** Reference equality (`===`): `irBuiltIns.eqeqeqSymbol`. */
    object ReferenceEquality : BuiltinOperatorKind

    /** Boolean negation (`!`): `irBuiltIns.booleanNotSymbol`. */
    object BooleanNot : BuiltinOperatorKind

    /** A relational comparison (`<`, `<=`, `>`, `>=`). */
    data class Comparison(val op: ComparisonOp, val kind: ComparisonKind) : BuiltinOperatorKind

    /** A throwing `IllegalArgumentException` builtin: `irBuiltIns.illegalArgumentExceptionSymbol`. */
    object IllegalArgumentExceptionThrow : BuiltinOperatorKind

    /** Not (yet) handled; [debugName] is the operator function name for diagnostics. */
    data class Unsupported(val debugName: String) : BuiltinOperatorKind
}

internal fun classifyBuiltinOperator(call: IrCall, irBuiltIns: IrBuiltIns): BuiltinOperatorKind {
    val functionSymbol = call.symbol
    val function = functionSymbol.owner
    return when (functionSymbol) {
        irBuiltIns.eqeqeqSymbol -> BuiltinOperatorKind.ReferenceEquality
        irBuiltIns.booleanNotSymbol -> BuiltinOperatorKind.BooleanNot
        else -> {
            val op = when {
                functionSymbol.isComparisonFunction(irBuiltIns.greaterFunByOperandType) -> ComparisonOp.GT
                functionSymbol.isComparisonFunction(irBuiltIns.greaterOrEqualFunByOperandType) -> ComparisonOp.GE
                functionSymbol.isComparisonFunction(irBuiltIns.lessFunByOperandType) -> ComparisonOp.LT
                functionSymbol.isComparisonFunction(irBuiltIns.lessOrEqualFunByOperandType) -> ComparisonOp.LE
                functionSymbol == irBuiltIns.illegalArgumentExceptionSymbol -> return BuiltinOperatorKind.IllegalArgumentExceptionThrow
                else -> return BuiltinOperatorKind.Unsupported(function.name.toString())
            }
            // Note: floating-point detection is on the IR operand type; for builtin comparison
            // operands (always primitive numerics) this matches the emitted LLVM value type.
            val kind = when {
                call.arguments[0]!!.type.isDoubleOrFloatWithoutNullability() -> ComparisonKind.FLOATING
                function.parameters[0].type.isChar() -> ComparisonKind.UNSIGNED
                else -> ComparisonKind.SIGNED
            }
            BuiltinOperatorKind.Comparison(op, kind)
        }
    }
}

//-------------------------------------------------------------------------//
// Call classification
//-------------------------------------------------------------------------//

/**
 * Description of how an [IrCall] should be dispatched. Mirrors the routing order: typed intrinsic,
 * builtin operator, static initializer, fake override, then an ordinary [Direct] call (virtual or
 * not).
 */
internal sealed interface CallKind {
    /** A typed intrinsic (`function.isTypedIntrinsic`). */
    object Intrinsic : CallKind

    /** A builtin operator (`function.isBuiltInOperator`); classify further with [classifyBuiltinOperator]. */
    object BuiltinOperator : CallKind

    /** A file global-state initializer call. */
    object StaticGlobalInitializer : CallKind

    /** A file thread-local-state initializer call. */
    object StaticThreadLocalInitializer : CallKind

    /** A standalone thread-local-state initializer call. */
    object StaticStandaloneThreadLocalInitializer : CallKind

    /** A call whose callee is a fake override (`!function.isReal`). */
    object FakeOverride : CallKind

    /** An ordinary call; [isVirtual] iff it dispatches virtually (see [isVirtualCall]). */
    data class Direct(val isVirtual: Boolean) : CallKind
}

internal fun classifyCall(call: IrCall): CallKind {
    val function = call.symbol.owner
    return when {
        function.isTypedIntrinsic -> CallKind.Intrinsic
        function.isBuiltInOperator -> CallKind.BuiltinOperator
        function.origin == StaticInitializersOrigins.STATIC_GLOBAL_INITIALIZER -> CallKind.StaticGlobalInitializer
        function.origin == StaticInitializersOrigins.STATIC_THREAD_LOCAL_INITIALIZER -> CallKind.StaticThreadLocalInitializer
        function.origin == StaticInitializersOrigins.STATIC_STANDALONE_THREAD_LOCAL_INITIALIZER -> CallKind.StaticStandaloneThreadLocalInitializer
        !function.isReal -> CallKind.FakeOverride
        else -> CallKind.Direct(isVirtual = call.isVirtualCall())
    }
}
