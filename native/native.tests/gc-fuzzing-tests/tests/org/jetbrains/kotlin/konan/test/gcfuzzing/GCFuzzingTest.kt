/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.gcfuzzing

import org.jetbrains.kotlin.konan.test.blackbox.AbstractNativeSimpleTest
import org.jetbrains.kotlin.konan.test.gcfuzzing.fuzzer.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class GCFuzzingTest : AbstractNativeSimpleTest() {

    @TestFactory
    fun executeSingle(): Collection<DynamicTest> {
        return listOfNotNull(System.getProperty("gcfuzzing.id"))
            .mapNotNull {
                ProgramId.fromString(it)
            }
            .mapNotNull {
                DynamicTest.dynamicTest("$it") {
                    execute(it)
                }
            }
    }


    @TestFactory
    fun simpleFuzz(): Collection<DynamicTest> {
        val steps = 100

        with(SimpleFuzzer(0)) {
            return List(steps) {
                DynamicTest.dynamicTest("step $it") {
                    runNextStep()
                }
            }
        }
    }

}