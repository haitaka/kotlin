/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.gcfuzzing.fuzzer

import org.jetbrains.kotlin.konan.test.gcfuzzing.dsl.Program
import kotlin.random.*

class FuzzerSession {
    val program = mutableMapOf<ProgramId, Program>()
    // TODO move out
    val score = mutableMapOf<ProgramId, Int>()

    fun getProgram(id: ProgramId): Program = program.getOrPut(id) {
        // FIXME remove
        fun Program.withId(id: ProgramId) = ProgramWithId(this, id)

        val result = when (id) {
            is ProgramId.Individual -> generate(id.seed)
            is ProgramId.Mutation -> mutate(
                id.seed,
                getProgram(id.origin).withId(id.origin)
            )
            is ProgramId.Cross -> cross(
                id.seed,
                getProgram(id.first).withId(id.first),
                getProgram(id.second).withId(id.second)
            )
        }
        require(result.id == id)
        result.program
    }
}

fun FuzzerSession.runSelection(
    seed: Int = 0,
    populationSize: Int = 10,
    numGenerations: Int = 30,
    select: FuzzerSession.(List<ProgramId>, Int) -> List<ProgramId> = { generation, size ->
        generation.sortedBy { score[it]!! }.takeLast(size)
    },
    evaluate: FuzzerSession.(ProgramId) -> Int,
) {
    val random = Random(seed)
    val numNewcomers = populationSize / 5
    val numSurvivors = populationSize / 2

    val initial = List(populationSize) { ProgramId.Individual(random.nextUInt()) }

    var generation: List<ProgramId> = initial
    repeat(numGenerations) { genNum ->
        generation.withIndex().map { (idx, p) ->
            val score = evaluate(p)
            println("Generation $genNum individual $idx prog ${p} score $score")
            this.score[p] = score
        }
        val survivors = select(generation, numSurvivors)
        val children = List(populationSize - numNewcomers) {
            val first = random.choose(survivors)
            val second = random.choose(survivors)
            val crossed = ProgramId.Cross(random.nextUInt(), first, second)
            ProgramId.Mutation(random.nextUInt(), crossed)
        }
        val newcomers = List(2) { ProgramId.Individual(random.nextUInt()) }
        val newGeneration = children + newcomers
        generation = newGeneration
    }
    for ((p, s) in score.entries.sortedBy { it.value }) {
        println("$p : $s")
    }
}
