/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.optimizations

import org.jetbrains.kotlin.backend.common.ir.isUnconditional
import org.jetbrains.kotlin.backend.common.*
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.ir.isAny
import org.jetbrains.kotlin.backend.konan.ir.superClasses
import org.jetbrains.kotlin.backend.konan.isNativePrimitiveType
import org.jetbrains.kotlin.backend.konan.optimizations.DevirtualizationAnalysis.PossiblyCoercedValue
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.withNullability
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.types.model.withNullability
import kotlin.collections.plus

sealed interface TypeApprox
object NoValue : TypeApprox
data class TypePoint(val type: IrType) : TypeApprox {
    init {
        require(!type.isInterface())
    }

    override fun toString(): String = "TypePoint(${type.render()})"
}
data class TypeCone(val type: IrType) : TypeApprox {
    override fun toString(): String = "TypeCone(${type.render()})"
}

data class TypeFilter(val sym: IrValueSymbol, val thenFilter: TypeApprox, val elseFilter: TypeApprox)

interface SemilatticeWithBottom<T> : Semilattice<T> {
    val bottom: T
}

interface Lattice<T> : SemilatticeWithBottom<T> {
    fun join(x: T, y: T): T
    fun joinAll(values: List<T>): T = values.reduce { x, y -> join(x, y) }
}

class ClassHierarchy(irModule: IrModuleFragment) {
    val declaredClasses: Set<IrClassSymbol> = run {
        val buf = mutableSetOf<IrClassSymbol>()
        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitClass(declaration: IrClass) {
                buf += declaration.symbol
                super.visitClass(declaration)
            }
        })
        buf.toSet()
    }
    val visibleClasses = run {
        val buf = declaredClasses.toMutableSet()
        for (cls in declaredClasses) {
            buf += cls.owner.superClasses
        }
        buf.toSet()
    }
    // FIXME
    val subClasses = mutableMapOf<IrClassSymbol, MutableSet<IrClassSymbol>>()
    init {
        for (cls in visibleClasses) {
            for (superClass in cls.owner.superClasses) {
                val s = subClasses.getOrPut(superClass) { mutableSetOf<IrClassSymbol>() }
                s += setOf(cls)
            }
        }
    }
}

internal class LocalTypeAnalysis(val context: Context) : ModuleLoweringPass {
    private val throwClassCastException = context.symbols.throwClassCastException
    override fun lower(irModule: IrModuleFragment) {
        val cha = ClassHierarchy(irModule)

        println("CHA:")
        println("visibleClasses: " + cha.visibleClasses.map { it.owner.name })
        println("subClasses:")
        for ((cls, subs) in cha.subClasses) {
            println("  ${cls.owner.name} -> " + subs.map { it.owner.name })
        }

        val typeSystem = context.typeSystem
        fun lcaNonNull(a: IrClass, b: IrClass): IrClass {
            if (a.isAny() || b.isAny()) return a
            if (a.defaultType in b.superTypes) return a
            val sa = a.superClass ?: return typeSystem.anyType().getClass()!!
            return lcaNonNull(sa, b)
        }
        fun lca(a: IrType, b: IrType): IrType {
            val hasNull = a.isNullable() || b.isNullable()
            val ca = a.getClass() ?: return typeSystem.anyType().withNullability(hasNull)
            val cb = b.getClass() ?: return typeSystem.anyType().withNullability(hasNull)
            return lcaNonNull(ca, cb).defaultType.withNullability(hasNull)
        }
        fun join(a: IrType, b: IrType): IrType? {
            val lca = lca(a, b)
            if (lca == a) return a
            if (lca == b) return b
            if (a.isInterface()) return a
            if (b.isInterface()) return b
            return null
        }
        operator fun TypeCone.contains(other: TypeApprox): Boolean = when (other) {
            NoValue -> true
            is TypePoint -> this.type.getClass()?.symbol?.let {
                val otherClsSym = other.type.getClass()?.symbol ?: return@let false
                val subclasses = cha.subClasses[it] ?: return@let false
                otherClsSym in subclasses
            } ?: false
            is TypeCone -> this.type.getClass()?.symbol?.let {
                val otherClsSym = other.type.getClass()?.symbol ?: return@let false
                val subclasses = cha.subClasses[it] ?: return@let false
                otherClsSym in subclasses
            } ?: false
        }
        operator fun TypePoint.contains(other: TypeApprox): Boolean = when (other) {
            NoValue -> true
            is TypePoint -> join(this.type, other.type) == this.type // FIXME?
            is TypeCone -> false
        }
        operator fun TypeApprox.contains(other: TypeApprox): Boolean = when (this) {
            NoValue -> false
            is TypePoint -> this.contains(other)
            is TypeCone -> this.contains(other)
        }

        fun TypeApprox.allTypes(): List<IrType> = when (this) {
            NoValue -> listOf()
            is TypePoint -> listOf(this.type)
            is TypeCone -> this.type.getClass()?.let {
                if (it.isNativePrimitiveType()) return listOf(type)
                val subClasses = cha.subClasses[it.symbol]?.toList() ?: listOf()
                val subTypes = subClasses.map { it.owner.defaultType }
                subTypes + listOf(it.defaultType)
            } ?: listOf(this.type)
        }

        val taLattice = object : Lattice<TypeApprox> {
            override val top: TypeApprox = NoValue
            override val bottom: TypeApprox = TypeCone(typeSystem.anyType().withNullability(true))
            override fun meet(x: TypeApprox, y: TypeApprox): TypeApprox {
                if (x == y) return x
                return when (x) {
                    NoValue -> y
                    is TypePoint -> when (y) {
                        NoValue -> x
                        is TypePoint -> TypeCone(lca(x.type, y.type))
                        is TypeCone -> if (x in y) y else TypeCone(lca(x.type, y.type))
                    }
                    is TypeCone -> when (y) {
                        NoValue -> x
                        is TypePoint -> TypeCone(lca(x.type, y.type))
                        is TypeCone -> if (x in y) y else TypeCone(lca(x.type, y.type))
                    }
                }
            }
            override fun join(x: TypeApprox, y: TypeApprox): TypeApprox {
                if (x == y) return x
                return when (x) {
                    NoValue -> NoValue
                    is TypePoint -> when (y) {
                        NoValue -> NoValue
                        is TypePoint -> join(x.type, y.type)?.let { TypePoint(it) } ?: NoValue
                        is TypeCone -> if (x in y) x else NoValue
                    }
                    is TypeCone -> when (y) {
                        NoValue -> NoValue
                        is TypePoint -> if (y in x) y else NoValue
                        is TypeCone -> join(x.type, y.type)?.let { TypeCone(it) } ?: NoValue
                    }
                }
            }
        }

        val valuesLattice = object : Lattice<Map<IrValueSymbol, TypeApprox>> {
            override val top: Map<IrValueSymbol, TypeApprox> = emptyMap()
            override val bottom: Map<IrValueSymbol, TypeApprox> get() = TODO()
            override fun meet(x: Map<IrValueSymbol, TypeApprox>, y: Map<IrValueSymbol, TypeApprox>): Map<IrValueSymbol, TypeApprox> {
                return (x.keys + y.keys).associateWith {
                    taLattice.meet(
                            x[it] ?: taLattice.top,
                            y[it] ?: taLattice.top
                    )
                }
            }
            override fun join(x: Map<IrValueSymbol, TypeApprox>, y: Map<IrValueSymbol, TypeApprox>): Map<IrValueSymbol, TypeApprox> {
                // FIXME???
                return (x.keys + y.keys).associateWith {
                    taLattice.join(
                            x[it] ?: taLattice.top,
                            y[it] ?: taLattice.top
                    )
                }
            }
        }

        fun IrExpression.matchAsFilter(): TypeFilter? {
            this as? IrTypeOperatorCall ?: return null
            val arg = argument as? IrValueAccessExpression ?: return null
            if (this.operator == IrTypeOperator.INSTANCEOF) {
                // TODO can do better in else
                return TypeFilter(arg.symbol, TypeCone(this.typeOperand), taLattice.bottom)
            }
            if (this.operator == IrTypeOperator.NOT_INSTANCEOF) {
                // TODO can do better in else
                return TypeFilter(arg.symbol, taLattice.bottom, TypeCone(this.typeOperand))
            }
            return null
        }

        irModule.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitFunction(declaration: IrFunction) {
                super.visitFunction(declaration)

                if (true || declaration.name.asString().contains("haitest")) {
                    val currentApprox = mutableMapOf<IrValueSymbol, TypeApprox>()

                    fun approx(expr: IrExpression): TypeApprox {
                        fun default() = TypeCone(expr.type)
                        return when (expr) {
                            is IrValueAccessExpression -> currentApprox[expr.symbol] ?: default()
                            is IrConstructorCall -> TypePoint(expr.type)
                            is IrContainerExpression -> (expr.statements.lastOrNull() as? IrExpression)?.let { approx(it) } ?: default()
                            is IrWhen -> {
                                val branchTypes = expr.branches.map { approx(it.result) }
                                taLattice.joinAll(branchTypes)
                            }
                            // TODO can ther be anything else?
                            else -> default()
                        }
                    }

                    fun print(x: Map<IrValueSymbol, TypeApprox>) {
                        for ((sym, approx) in x) {
                            println("${sym.owner.name} = $approx")
                        }
                    }

                    val initial = when (declaration) {
                        else -> {
                            declaration.parameters.associate {
                                (it.symbol as IrValueSymbol) to TypeCone(it.type)
                            }
                        }
                    }

                    val redundantCasts = mutableMapOf<IrTypeOperatorCall, Boolean>()
                    val devirtualizableFun = mutableMapOf<IrCall, IrSimpleFunction>()

                    val typeAnalysis = object : ForwardDataFlowAnalysis<Map<IrValueSymbol, TypeApprox>>(valuesLattice) {

                        override fun visitFunction(declaration: IrFunction, data: Map<IrValueSymbol, TypeApprox>): Map<IrValueSymbol, TypeApprox> {
                            return data;
                        }

                        override fun visitValueParameter(declaration: IrValueParameter, data: Map<IrValueSymbol, TypeApprox>): Map<IrValueSymbol, TypeApprox> {
                            return super.visitValueParameter(declaration, data) + mapOf(
                                    declaration.symbol to TypeCone(declaration.type)
                            )
                        }

                        override fun visitVariable(declaration: IrVariable, data: Map<IrValueSymbol, TypeApprox>): Map<IrValueSymbol, TypeApprox> {
                            return super.visitVariable(declaration, data) + mapOf(
                                    declaration.symbol to (declaration.initializer?.let { approx(it) } ?: TypeCone(declaration.type))
                            )
                        }

                        override fun visitSetValue(expression: IrSetValue, data: Map<IrValueSymbol, TypeApprox>): Map<IrValueSymbol, TypeApprox> {
                            val input = super.visitSetValue(expression, data)
                            return valuesLattice.meet(input, mapOf(expression.symbol to approx(expression.value)))
                        }

                        override fun visitTypeOperator(expression: IrTypeOperatorCall, data: Map<IrValueSymbol, TypeApprox>): Map<IrValueSymbol, TypeApprox> {
                            val input = super.visitTypeOperator(expression, data)
                            val arg = expression.argument as? IrValueAccessExpression ?: return input
                            val sym = arg.symbol
                            if (expression.operator == IrTypeOperator.CAST) {
                                val taBefore = input[sym] ?: approx(arg)
                                val taAfter = taLattice.join(taBefore, TypeCone(expression.typeOperand))
                                if (taAfter == NoValue) {
                                    redundantCasts[expression] = false
                                } else if (taBefore == taAfter) {
                                    redundantCasts[expression] = true
                                }
                                return input + mapOf(sym to taAfter)
                            }
                            // TODO remove instanceof and others
                            return input
                        }

                        override fun visitCall(expression: IrCall, data: Map<IrValueSymbol, TypeApprox>): Map<IrValueSymbol, TypeApprox> {
                            println("visitCall ${expression.render()}")
                            // FIXME do not process the actual call AOT
                            val input = super.visitCall(expression, data)
                            if (!expression.isVirtualCall) return input
                            //println(expression.dispatchReceiver)
                            // TODO support (x as T).foo()
                            val receiver = expression.dispatchReceiver as? IrValueAccessExpression ?: return input
                            val receiverTA = input[receiver.symbol] ?: return input
                            val possibleTypes = receiverTA.allTypes()
                            println("    possibleTypes: ${possibleTypes.map { it.render() }}")
                            val overrides = possibleTypes.mapNotNull { x ->
                                x.getClass()?.let {
                                    it.declarations.filterIsInstance<IrSimpleFunction>().find {
                                        it.overrides(expression.symbol.owner) && it.body != null // FIXME abstract?????
                                    }
                                }
                            }
                            println("    overrides: ${overrides.map {it.symbol}}")
                            println("    in $receiverTA")
                            if (overrides.size == 1) {
                                devirtualizableFun[expression] = overrides.single()
                            }
                            return input
                        }

                        override fun visitWhen(expression: IrWhen, data: Map<IrValueSymbol, TypeApprox>): Map<IrValueSymbol, TypeApprox> {
//                    print(data)
                            val conditions = expression.branches.map { it.condition }
                            val conditionFilters = conditions.map { it.matchAsFilter() }
                            val conditionResults = conditions.fold(listOf<Map<IrValueSymbol, TypeApprox>>()) { acc, next ->
                                val prev = if (acc.isEmpty()) data else acc.last()
                                acc + listOf(next.accept(this, prev))
                            }
//                    println("conditionResults:")
//                    conditionResults.forEach { print(it) }
                            val branchInputs = conditionResults.zip(conditionFilters) { res, filter ->
                                // FIXME valuesLattice.join(res, filter ?: lattice.bottom)
                                filter?.let {
                                    valuesLattice.join(res, mapOf(it.sym to it.thenFilter))
                                } ?: res
                            }
//                    println("branchInputs:")
//                    branchInputs.forEach { print(it) }
                            val branchResults = branchInputs.zip(expression.branches) { input, branch ->
                                branch.result.accept(this, input)
                            }
//                    println("branchResults:")
//                    branchResults.forEach { print(it) }
                            val elseResult = if (!expression.branches.last().isUnconditional()) conditionResults.last() else lattice.top
//                    println("elseReuslt:")
//                    print(elseResult)
                            return lattice.meetAll(branchResults + elseResult)
                        }

                        override fun visitWhileLoop(loop: IrWhileLoop, data: Map<IrValueSymbol, TypeApprox>): Map<IrValueSymbol, TypeApprox> {
                            val condResult = loop.condition.accept(this, data)
                            val condFilter = loop.condition.matchAsFilter()

                            val bodyInput = condFilter?.let { valuesLattice.join(condResult, mapOf(it.sym to it.thenFilter)) } ?: condResult
                            loop.body?.accept(this, bodyInput)

                            val exitData = condFilter?.let { valuesLattice.join(condResult, mapOf(it.sym to it.elseFilter)) } ?: condResult
                            return exitData
                        }

                        override fun visitDoWhileLoop(loop: IrDoWhileLoop, data: Map<IrValueSymbol, TypeApprox>): Map<IrValueSymbol, TypeApprox> {
                            val bodyResult = loop.body?.accept(this, data) ?: data
                            val condResult = loop.condition.accept(this, bodyResult)
                            val condFilter = loop.condition.matchAsFilter()
                            val exitData = condFilter?.let { valuesLattice.join(condResult, mapOf(it.sym to it.elseFilter)) } ?: condResult
                            return exitData
                        }
                    }

                    val res = declaration.body?.accept(typeAnalysis, initial)

                    println(declaration.name)
                    print(res)
                    println()
                    for ((cast, passes) in redundantCasts) {
                        println("Redundant cast: ${cast.render()} $passes")
                    }
                    for ((call, override) in devirtualizableFun) {
                        println("Devirt fun: ${call.render()} -> ${override.symbol}")
                    }
                    val irBuilder = context.createIrBuilder(declaration.symbol)
                    declaration.transformChildrenVoid(object : IrElementTransformerVoid() {
                        override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
                            expression.transformChildrenVoid()
                            val passes = redundantCasts[expression] ?: return expression
                            return when (expression.operator) {
                                IrTypeOperator.INSTANCEOF -> irBuilder.at(expression).irBoolean(passes) as IrExpression
                                IrTypeOperator.CAST -> if (passes) {
                                    irBuilder.at(expression).irBlock(origin = STATEMENT_ORIGIN_NO_CAST_NEEDED) {
                                        +irImplicitCast(expression.argument, expression.typeOperand)
                                    } as IrExpression
                                } else {
                                    irBuilder.at(expression).irCall(throwClassCastException).apply {
                                        val typeOperandClass = expression.typeOperand.erasedUpperBound
                                        val typeOperandClassReference = IrClassReferenceImpl(
                                                startOffset, endOffset,
                                                context.symbols.nativePtrType,
                                                typeOperandClass.symbol,
                                                typeOperandClass.defaultType
                                        )
                                        arguments[0] = expression.argument
                                        arguments[1] = typeOperandClassReference
                                    } as IrExpression
                                }
                                else -> expression
                            }
                        }

                        override fun visitCall(expression: IrCall): IrExpression {
                            expression.transformChildrenVoid()
                            val devirtualized = devirtualizableFun[expression] ?: return expression

                            val replacement = IrCallImpl(
                                    expression.startOffset, expression.endOffset,
                                    devirtualized.returnType,
                                    devirtualized.symbol,
                                    devirtualized.typeParameters.size,
                                    expression.origin,
                                    devirtualized.parentAsClass.symbol
                            )
                            assert(devirtualized.parameters.size == expression.arguments.size) {
                                "Incorrect number of arguments: expected [${devirtualized.parameters.size}] but was [${expression.arguments.size}]\n" +
                                        devirtualized.dump()
                            }
                            expression.arguments.forEachIndexed { index, argument ->
                                replacement.arguments[index] = argument
                            }
                            return replacement//.implicitCastIfNeededTo(actualType)
                        }
                    })
                }
            }
        })
    }
}
