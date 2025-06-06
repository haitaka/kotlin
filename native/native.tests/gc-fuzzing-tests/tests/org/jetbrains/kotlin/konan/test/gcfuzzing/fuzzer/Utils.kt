/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.gcfuzzing.fuzzer

import kotlin.math.roundToInt
import kotlin.random.Random

interface Distribution<T> {
    fun next(random: Random): T

    companion object {
        fun <T> uniformal(vararg discreteValues: T): Distribution<T> = object : Distribution<T> {
            override fun next(random: Random): T {
                val idx = random.nextInt(0, discreteValues.size)
                return discreteValues[idx]
            }
        }
        inline fun <reified T: Enum<T>> uniformal(): Distribution<T> = uniformal(*enumValues<T>())

        inline fun <reified T> weighted(vararg variants: Pair<T, Int>): Distribution<T> = uniformal(*(variants.map { it.first }.toTypedArray()))

        fun uniformalBetween(from: Int, until: Int): Distribution<Int> = CumulativeFun.uniformal(
            0.0 to from,
            1.0 to until
        )
    }
}

fun interface CumulativeFun<T> : Distribution<T> {
    fun cdf(p: Double): T

    override fun next(random: Random): T {
        val p = random.nextDouble(0.0, 1.0)
        return cdf(p)
    }

    companion object {
        fun uniformal(vararg anchors: Pair<Double, Int>): CumulativeFun<Int> = object : CumulativeFun<Int> {
            override fun cdf(p: Double): Int {
                val sortedAnchors = anchors.sortedBy { it.first }
                val greaterIndex = sortedAnchors.indexOfFirst { it.first > p }
                val from = sortedAnchors[greaterIndex - 1].second
                val to = sortedAnchors[greaterIndex].second
                return (from + p * (to - from)).roundToInt()
            }
        }
    }
}

fun <T> Random.choose(vararg options: T): T = choose(options.toList())
fun <T> Random.choose(options: List<T>): T = options[nextInt(options.size)]
