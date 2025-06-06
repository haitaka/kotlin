/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.test.blackbox

import org.jetbrains.kotlin.cli.common.arguments.allowTestsOnlyLanguageFeatures
import org.jetbrains.kotlin.konan.test.blackbox.support.NativeSimpleTestSupport
import org.jetbrains.kotlin.konan.test.blackbox.support.TestCase
import org.jetbrains.kotlin.konan.test.blackbox.support.runner.AbstractRunner
import org.jetbrains.kotlin.konan.test.blackbox.support.runner.RunResult
import org.jetbrains.kotlin.konan.test.blackbox.support.runner.SimpleTestRunProvider
import org.jetbrains.kotlin.konan.test.blackbox.support.runner.TestExecutable
import org.jetbrains.kotlin.konan.test.blackbox.support.runner.TestRunners.createProperTestRunner
import org.jetbrains.kotlin.konan.test.blackbox.support.settings.SimpleTestRunSettings
import org.jetbrains.kotlin.konan.test.blackbox.support.util.prependPackageName
import org.jetbrains.kotlin.test.backend.handlers.UpdateTestDataSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(NativeSimpleTestSupport::class, UpdateTestDataSupport::class)
abstract class AbstractNativeSimpleTest {
    lateinit var testRunSettings: SimpleTestRunSettings
    internal lateinit var testRunProvider: SimpleTestRunProvider

    @BeforeEach
    fun setup() {
        allowTestsOnlyLanguageFeatures()
    }

    fun runExecutableAndVerify(testCase: TestCase, executable: TestExecutable) {
        val testRun = testRunProvider.getTestRun(testCase, executable)
        val testRunner = createProperTestRunner(testRun, testRunSettings)
        testRunner.run()
    }

    fun runExecutableAndVerifyPreserveResult(testCase: TestCase, executable: TestExecutable): RunResult {
        val testRun = testRunProvider.getTestRun(testCase, executable)
        val testRunner = createProperTestRunner(testRun, testRunSettings) as AbstractRunner
        return testRunner.run()
    }

    fun runExecutableAndVerifyPreserveResultDynamic(testCase: TestCase, executable: TestExecutable): DynamicTest {
        val testRun = testRunProvider.getTestRun(testCase, executable)
        val displayName = testRun.displayName + testCase.id.toString() + "nonempty"// TODO .prependPackageName(ownPackageSegment)
        val testRunner = createProperTestRunner(testRun, testRunSettings) as AbstractRunner
        return dynamicTest(displayName) { testRunner.run() }!!
    }
}
