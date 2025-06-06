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
import kotlin.random.Random

fun mutate(seed: UInt, origin: ProgramWithId): ProgramWithId {
    val mutator = Mutator(seed.toInt())
    val mutated = mutator.mutate(origin.program)
    val id = ProgramId.Mutation(seed, origin.id)
    return ProgramWithId(mutated, id)
}

private class Mutator(seed: Int = 0) {
    val random = Random(seed)
    val generator = Generator(seed)

    val mutationChance = 0.01

    private fun <T> T.orGenerate(generator: () -> T): T = if (random.nextDouble() <= mutationChance) generator() else this

    fun mutate(program: Program): Program =
        Program(
            definitions = mutate(program.definitions),
            mainBody = mutate(program.mainBody),
        )

    @JvmName("mutateDefinitions")
    fun mutate(definitions: List<Definition>): List<Definition> = definitions.map {
       when (it) {
           is Definition.Class -> mutate(it)
           is Definition.Function -> mutate(it)
           is Definition.Global -> mutate(it)
       }
    }

    fun mutate(cls: Definition.Class): Definition.Class =
        Definition.Class(
            mutate(cls.targetLanguage),
            mutate(cls.fields)
        )

    fun mutate(function: Definition.Function): Definition.Function =
        Definition.Function(
            mutate(function.targetLanguage),
            mutate(function.parameters),
            mutate(function.body),
        )

    fun mutate(global: Definition.Global): Definition.Global = global

    fun mutate(lang: TargetLanguage) = lang.orGenerate { generator.genLanguage() }

    fun mutate(bodyWithReturn: BodyWithReturn): BodyWithReturn =
        BodyWithReturn(
            mutate(bodyWithReturn.body),
            LoadExpression.Default
        )

    fun mutate(body: Body): Body =
        Body(mutate(body.statements))

    fun mutate(statements: List<BodyStatement>) =
        statements.map { mutate(it) }

    fun mutate(statement: BodyStatement): BodyStatement = statement.orGenerate { generator.genStatement() }

    @JvmName("mutateFields")
    fun mutate(fields: List<Field>) = fields.orGenerate { generator.genFields() }

    @JvmName("mutateParameters")
    fun mutate(parameters: List<Parameter>) = parameters.orGenerate { generator.genParameters() }
}
