/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.gcfuzzing.fuzzer

import org.jetbrains.kotlin.konan.test.gcfuzzing.dsl.Body
import org.jetbrains.kotlin.konan.test.gcfuzzing.dsl.BodyStatement
import org.jetbrains.kotlin.konan.test.gcfuzzing.dsl.BodyWithReturn
import org.jetbrains.kotlin.konan.test.gcfuzzing.dsl.Definition
import org.jetbrains.kotlin.konan.test.gcfuzzing.dsl.Field
import org.jetbrains.kotlin.konan.test.gcfuzzing.dsl.LoadExpression
import org.jetbrains.kotlin.konan.test.gcfuzzing.dsl.Parameter
import org.jetbrains.kotlin.konan.test.gcfuzzing.dsl.Program
import org.jetbrains.kotlin.konan.test.gcfuzzing.dsl.TargetLanguage
import org.jetbrains.kotlin.utils.addToStdlib.zipWithNulls
import kotlin.random.Random

fun cross(seed: UInt, first: ProgramWithId, second: ProgramWithId): ProgramWithId {
    val crosser = Crosser(seed.toInt())
    val crossed = crosser.cross(first.program, second.program)
    val id = ProgramId.Cross(seed, first.id, second.id)
    return ProgramWithId(crossed, id)
}

private class Crosser(seed: Int) {
    val random = Random(seed)

    fun cross(a: Program, b: Program): Program = Program(
        cross(a.definitions, b.definitions),
        cross(a.mainBody, b.mainBody),
    )

    private inline fun <reified T> cross(a: List<T>, b: List<T>): List<T> = crossLists(a, b)

    private inline fun <reified T> crossLists(a: List<T>, b: List<T>): List<T> = a.zipWithNulls(b).map {
        when {
            it.first == null -> it.second!!
            it.second == null -> it.first!!
            else -> {
                val first = it.first!!
                val second = it.second!!
                when (first) {
                    is Field -> cross(first, second as Field) as T
                    is Parameter -> cross(first, second as Parameter) as T
                    is BodyStatement -> cross(first, second as BodyStatement) as T
                    is Definition.Class -> cross(first, second as Definition.Class) as T
                    is Definition.Function -> cross(first, second as Definition.Function) as T
                    is Definition.Global -> cross(first, second as Definition.Global) as T
                    else -> error("Should not reach here: $first $second")
                }
            }
        }
    }

    @JvmName("crossDefinitions")
    private fun cross(a: List<Definition>, b: List<Definition>): List<Definition> {
        val classes = crossLists(a.filterIsInstance<Definition.Class>(), b.filterIsInstance<Definition.Class>())
        val functions = crossLists(a.filterIsInstance<Definition.Function>(), b.filterIsInstance<Definition.Function>())
        val globals = crossLists(a.filterIsInstance<Definition.Global>(), b.filterIsInstance<Definition.Global>())
        return classes + functions + globals
    }

    private fun cross(a: TargetLanguage, b: TargetLanguage): TargetLanguage = random.choose(a, b)
    private fun cross(a: Field, b: Field): Field = a // TODO
    private fun cross(a: Parameter, b: Parameter): Parameter = a // TODO

    private fun cross(a: Definition.Class, b: Definition.Class): Definition.Class = Definition.Class(
        cross(a.targetLanguage, b.targetLanguage),
        cross(a.fields, b.fields)
    )

    private fun cross(a: Definition.Global, b: Definition.Global): Definition.Global = Definition.Global(
        cross(a.targetLanguage, b.targetLanguage),
        cross(a.field, b.field)
    )

    private fun cross(a: Definition.Function, b: Definition.Function): Definition.Function = Definition.Function(
        cross(a.targetLanguage, b.targetLanguage),
        cross(a.parameters, b.parameters),
        cross(a.body, b.body),
    )

    private fun cross(a: BodyWithReturn, b: BodyWithReturn): BodyWithReturn = BodyWithReturn(
        cross(a.body, b.body),
        cross(a.returnExpression, b.returnExpression),
    )

    private fun cross(a: Body, b: Body): Body = Body(cross(a.statements, b.statements))

    // TODO more sophisticated statement crosser?
    // TODO adjust symbol id's
    private fun cross(a: BodyStatement, b: BodyStatement): BodyStatement = random.choose(a, b)

    private fun cross(a: LoadExpression, b: LoadExpression): LoadExpression = random.choose(a, b)
}