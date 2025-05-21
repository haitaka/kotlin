/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.optimizations

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.render
import kotlin.collections.plus
import kotlin.collections.toSet

private fun IrDeclaration.nameOrNull() = if (this is IrDeclarationWithName) name.asString() else null

private fun IrExpression.renderName() = when (this) {
    is IrGetValue -> symbol.owner.nameOrNull()
    is IrSetValue -> symbol.owner.nameOrNull()
    else -> null
} ?: this.toString()

sealed interface TypePredicate {
    fun meet(other: TypePredicate): TypePredicate
    fun join(other: TypePredicate): TypePredicate

    // TODO AnyType(value)?
    data object AnyType : TypePredicate {
        override fun meet(other: TypePredicate): TypePredicate = AnyType
        override fun join(other: TypePredicate): TypePredicate = other
    }
    data object NoValue : TypePredicate {
        override fun meet(other: TypePredicate): TypePredicate = other
        override fun join(other: TypePredicate): TypePredicate = NoValue
    }

    sealed interface Term : TypePredicate {
        val value: IrExpression

        override fun meet(other: TypePredicate): TypePredicate = when (other) {
            is Term -> if (other == this) this else Disjunction(this, other)
            else -> other.meet(this)
        }
        override fun join(other: TypePredicate): TypePredicate = when (other) {
            this -> this
            is Term -> if (other == this) this else Conjunction(this, other)
            else -> other.meet(this)
        }
    }
    // TODO handle anihilation of (A(x) & !A(x))
    data class IsType(override val value: IrExpression, val type: IrType) : Term {
        override fun toString() = "(${value.renderName()} is ${type.render()})"
    }
    data class IsNotType(override val value: IrExpression, val type: IrType) : Term {
        override fun toString() = "(${value.renderName()} !is ${type.render()})"
    }

    data class Conjunction(val terms: Set<Term>) : TypePredicate {
        constructor(vararg terms: Term) : this(terms.toSet())

        override fun toString() = terms.joinToString(" & ", "(", ")") { it.toString() }

        override fun meet(other: TypePredicate): TypePredicate = when (other) {
            is Term -> Disjunction(this, Conjunction(other))
            is Conjunction -> if (other == this) this else Disjunction(this, other) // TODO careful here
            else -> other.meet(this)
        }
        override fun join(other: TypePredicate): TypePredicate = when (other) {
            is Term -> Conjunction(terms + other)
            is Conjunction -> if (other == this) this else Conjunction(terms + other.terms)
            else -> other.meet(this)
        }
    }
    data class Disjunction(val disjuncts: Set<Conjunction>) : TypePredicate {
        constructor(vararg disjuncts: Conjunction) : this(disjuncts.toSet())
        constructor(disjuncts: Set<Term>, marker: Int) : this(disjuncts.map { Conjunction(setOf(it)) }.toSet())
        constructor(vararg disjuncts: Term) : this(disjuncts.toSet(), 42)

        override fun toString() = disjuncts.joinToString(" | ", "(", ")") { it.toString() }

        override fun meet(other: TypePredicate): TypePredicate = when (other) {
            is Term -> Disjunction(disjuncts + Conjunction(other)) // TODO careful here
            is Conjunction -> Disjunction(disjuncts + other) // TODO careful here
            is Disjunction -> Disjunction(disjuncts + other.disjuncts) // TODO careful here
            else -> other.meet(this)
        }

        override fun join(other: TypePredicate): TypePredicate = when (other) {
            is Term -> Disjunction(disjuncts.map { it.join(other) as Conjunction }.toSet()) // TODO careful here
            is Conjunction -> Disjunction(disjuncts.map { it.join(other) as Conjunction }.toSet()) // TODO careful here
            is Disjunction -> {
                // TODO very careful here!
                val newDisjuncts = mutableSetOf<Conjunction>()
                for (thisDisjunct in disjuncts) {
                    for (otherDisjunct in other.disjuncts) {
                        newDisjuncts += Conjunction(thisDisjunct.terms + otherDisjunct.terms)
                    }
                }
                Disjunction(newDisjuncts)
            }
            else -> other.meet(this)
        }
    }
}

object TypeAnalysisLattice : Semilattice<TypePredicate> {
    override val top: TypePredicate = TypePredicate.NoValue

    override fun meet(x: TypePredicate, y: TypePredicate): TypePredicate = x.meet(y).also { println("$x meet(V) $y = $it") }
    fun join(x: TypePredicate, y: TypePredicate): TypePredicate = x.join(y).also { println("$x meet(&) $y = $it") }
}

//fun TypePredicate.Conjunction.onlyFor(value: IrExpression) = TypePredicate.Conjunction(terms.filter { it.value == value }.toSet())
//fun TypePredicate.Disjunction.onlyFor(value: IrExpression) = TypePredicate.Disjunction(disjuncts.map { it.onlyFor(value) }.toSet())
//
//fun TypePredicate.onlyFor(value: IrExpression) = when (this) {
//    TypePredicate.AnyType, TypePredicate.NoValue -> TypePredicate.NoValue
//    is TypePredicate.Term -> if (this.value == value) this else TypePredicate.NoValue
//    is TypePredicate.Conjunction -> TypePredicate.Conjunction(this.terms.filter<TypePredicate.Term> { it.value == value }.toSet<TypePredicate.Term>())
//    is TypePredicate.Disjunction -> TypePredicate.Disjunction(this.disjuncts.map<TypePredicate.Conjunction, TypePredicate.Conjunction> { it.onlyFor(value) }.toSet<TypePredicate.Conjunction>())
//}

fun TypePredicate.Term.invert() = when (this) {
    is TypePredicate.IsType -> TypePredicate.IsNotType(value, type)
    is TypePredicate.IsNotType -> TypePredicate.IsType(value, type)
}

class ProductSemilatticeLattice<T1, T2>(val first: Semilattice<T1>, val second: Semilattice<T2>) : Semilattice<Pair<T1, T2>> {
    override val top: Pair<T1, T2> = first.top to second.top
    override fun meet(x: Pair<T1, T2>, y: Pair<T1, T2>): Pair<T1, T2> =
            first.meet(x.first, y.first) to second.meet(x.second, y.second)

}

class TypeFilterCollector : ForwardDataFlowAnalysis<Pair<TypePredicate, TypePredicate>>(ProductSemilatticeLattice(TypeAnalysisLattice, TypeAnalysisLattice)) {

}

class TypeAnalysis : ForwardDataFlowAnalysis<TypePredicate>(TypeAnalysisLattice) {

    override fun visitTypeOperator(expression: IrTypeOperatorCall, data: TypePredicate): TypePredicate {
        return when (expression.operator) {
            IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.CAST -> {
                data.meet(TypePredicate.IsType(expression.argument, expression.typeOperand))
            }
            IrTypeOperator.SAFE_CAST ->
                // FIXME actually more complex
                data.meet(TypePredicate.IsType(expression.argument, expression.typeOperand.makeNullable()))
            else -> data
        }
    }

    private fun IrExpression.asTypeFilter() = when ((this as? IrTypeOperatorCall)?.operator) {
        IrTypeOperator.INSTANCEOF -> TypePredicate.IsType(this.argument, this.typeOperand)
        IrTypeOperator.NOT_INSTANCEOF -> TypePredicate.IsNotType(this.argument, this.typeOperand)
        else -> null
    }

    override fun visitBranch(branch: IrBranch, data: TypePredicate): TypePredicate {
        val result = super.visitBranch(branch, data)
        val typeFilter = branch.condition.asTypeFilter() ?: return result
        // TODO inverted filter on else branch
        return result.join(typeFilter)
    }

    // TODO handle loop conditions

    // TODO support aliases?
//    override fun visitVariable(declaration: IrVariable, data: TypePredicate): TypePredicate {
//        val initializer = declaration.initializer
//        val resultPredicate = if (initializer == null) data else setVariable(declaration, initializer, data)
//        return VisitorResult(resultPredicate, null)
//    }
//
//    override fun visitSetValue(expression: IrSetValue, data: TypePredicate): TypePredicate {
//        val variable = expression.symbol.owner as? IrVariable ?: error("Unexpected set to ${expression.symbol.owner.render()}")
//        return VisitorResult(setVariable(variable, expression.value, data), null)
//    }
//
//    override fun visitGetValue(expression: IrGetValue, data: TypePredicate): TypePredicate {
//        return VisitorResult(data, variableAliases[expression.symbol.owner] ?: expression.symbol.owner)
//    }

    // TODO properties?
//    override fun visitCall(expression: IrCall, data: Predicate): VisitorResult {
//        val callee = expression.symbol.owner
//        val correspondingProperty = callee.correspondingPropertySymbol?.owner
//        val backingField = correspondingProperty?.backingField
//        return if (backingField != null
//                && !correspondingProperty.isVar
//                && callee == correspondingProperty.getter
//                && isSimpleGetter(callee, backingField)
//        ) {
//            val receiverResult = expression.dispatchReceiver?.accept(this, data)
//            val phantomVariable = if (receiverResult == null) {
//                topLevelPropertyPhantomVariables.getOrPut(correspondingProperty) {
//                    createVariable("<ROOT>.${correspondingProperty.name}", backingField.type)
//                }
//            } else {
//                receiverResult.variable?.let { receiver ->
//                    instancePropertyPhantomVariables.getOrPut(Pair(receiver, correspondingProperty)) {
//                        createVariable("${receiver.name}.${correspondingProperty.name}", backingField.type)
//                    }
//                }
//            }
//
//            VisitorResult(receiverResult?.predicate ?: Predicate.Empty, phantomVariable)
//        } else {
//            super.visitCall(expression, data)
//        }
//    }
}

internal class CastsOptimization(val context: Context) : BodyLoweringPass {
    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (container is IrFunction && container.name.toString() == "test") {
//            val result = irBody.accept(TypeAnalysis(), TypePredicate.NoValue)
//            println(result)
            //println(container.name.toString() + " : ")
            println(container.render())
            val reachingDefs = collectReachingDefinitions(container, true)
            for ((read, defs) in reachingDefs) {
                println("${read.render()} : ${defs.joinToString { it.render() }}")
            }
        }
    }

}
