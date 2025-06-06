/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.gcfuzzing.fuzzer

import org.jetbrains.kotlin.konan.test.gcfuzzing.dsl.Program

sealed class ProgramId {
    private val repr by lazy { toString() }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProgramId) return false
        return repr == other.repr
    }
    override fun hashCode(): Int = repr.hashCode()

    class Individual(val seed: UInt) : ProgramId() {
        override fun toString(): String = "I$seed"
    }

    class Mutation(val seed: UInt, val origin: ProgramId) : ProgramId() {
        override fun toString(): String = "M$origin"
    }

    class Cross(val seed: UInt, val first: ProgramId, val second: ProgramId) : ProgramId() {
        override fun toString(): String = "C$first$second"
    }

    companion object {
        fun fromString(str: String): ProgramId? {
            fun tokenize(str: String): List<String> {
                val regex = Regex("([IMC]|\\d+|[^IMC\\d]+)")
                return regex.findAll(str).map { it.value }.toList()
            }

            val tokens = tokenize(str).iterator()
            fun nextToken() = if (tokens.hasNext()) tokens.next() else null
            fun nextUInt() = nextToken()?.toUIntOrNull()
            fun nextPorgramId(): ProgramId? = when (nextToken()) {
                "I" -> {
                    val seed = nextUInt() ?: return null
                    Individual(seed)
                }
                "M" -> {
                    val seed = nextUInt() ?: return null
                    val origin = nextPorgramId() ?: return null
                    Mutation(seed, origin)
                }
                "C" -> {
                    val seed = nextUInt() ?: return null
                    val first = nextPorgramId() ?: return null
                    val second = nextPorgramId() ?: return null
                    Cross(seed, first, second)
                }
                else -> null
            }
            return nextPorgramId()
        }
    }
}

data class ProgramWithId(val program: Program, val id: ProgramId) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProgramWithId) return false
        return id == other.id
    }
    override fun hashCode(): Int = id.hashCode()
}
