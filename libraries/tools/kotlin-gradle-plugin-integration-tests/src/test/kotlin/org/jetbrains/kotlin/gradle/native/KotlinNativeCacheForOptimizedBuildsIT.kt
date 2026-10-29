/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this file is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:OptIn(KotlinNativeCacheApi::class)

package org.jetbrains.kotlin.gradle.native

import org.gradle.kotlin.dsl.kotlin
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.diagnostics.KotlinToolingDiagnostics
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.testbase.*
import org.jetbrains.kotlin.gradle.uklibs.applyMultiplatform
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.testFederation.MustRunOnChangesInGradle
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.condition.OS
import java.net.URI

@DisplayName("Kotlin/Native cache for optimized builds tests")
@NativeGradlePluginTests
@MustRunOnChangesInGradle
internal class KotlinNativeCacheForOptimizedBuildsIT : KGPBaseTest() {

    @GradleTest
    @OsCondition(supportedOn = [OS.MAC, OS.LINUX], enabledOnCI = [OS.LINUX])
    fun testOptimizedBinaryLinkPassesReleaseCacheArgs(
        gradleVersion: GradleVersion,
    ) {
        val targetName = if (HostManager.hostIsMac) "macosArm64" else "linuxX64"
        setupCacheForOptimizedBuildsTest(
            gradleVersion,
            targetProvider = { if (HostManager.hostIsMac) macosArm64() else linuxX64() }
        ) {
            build(
                getLinkStaticTaskName(targetName, NativeBuildType.DEBUG),
                getLinkStaticTaskName(targetName, NativeBuildType.RELEASE)
            ) {
                extractNativeTasksCommandLineArgumentsFromOutput(getLinkStaticTaskName(targetName, NativeBuildType.RELEASE)) {
                    assertCommandLineArgumentsContain(
                        "-Xbinary=enableReleaseBinaryCache=true",
                        "-Xauto-cache-from=${getGradleUserHome()}",
                    )
                }
                extractNativeTasksCommandLineArgumentsFromOutput(getLinkStaticTaskName(targetName, NativeBuildType.DEBUG)) {
                    assertCommandLineArgumentsDoNotContain("-Xbinary=enableReleaseBinaryCache=true")
                    assertCommandLineArgumentsContain("-Xauto-cache-from=${getGradleUserHome()}")
                }
                assertNoDiagnostic(KotlinToolingDiagnostics.NativeCacheConflictingConfigurationDiagnostic)
                assertNoDiagnostic(KotlinToolingDiagnostics.NativeCacheUnsupportedTargetDiagnostic)
            }
        }
    }

    @GradleTest
    @OsCondition(supportedOn = [OS.MAC, OS.LINUX], enabledOnCI = [OS.LINUX])
    fun testConflictingCacheConfigurationDiagnostic(
        gradleVersion: GradleVersion,
    ) {
        val targetName = if (HostManager.hostIsMac) "macosArm64" else "linuxX64"
        project("empty", gradleVersion) {
            plugins {
                kotlin("multiplatform")
            }
            buildScriptInjection {
                project.applyMultiplatform {
                    val target = if (HostManager.hostIsMac) macosArm64() else linuxX64()

                    target.binaries.staticLib {
                        if (buildType == NativeBuildType.RELEASE) {
                            disableNativeCache(
                                latestDisableCacheInKotlinVersion(),
                                "Disabled for integration testing",
                                URI("https://kotlinlang.org")
                            )
                            enableNativeCacheForOptimizedBuilds = true
                        }
                    }
                    sourceSets.commonMain.get().compileStubSourceWithSourceSetName()
                }
            }

            build(getLinkStaticTaskName(targetName, NativeBuildType.RELEASE)) {
                assertHasDiagnostic(KotlinToolingDiagnostics.NativeCacheConflictingConfigurationDiagnostic)
            }
        }
    }

    @GradleTest
    @OsCondition(supportedOn = [OS.MAC, OS.LINUX, OS.WINDOWS], enabledOnCI = [OS.LINUX, OS.WINDOWS])
    fun testCacheForOptimizedBuildsNotSupportedForTarget(
        gradleVersion: GradleVersion,
    ) {
        setupCacheForOptimizedBuildsTest(gradleVersion, targetProvider = { mingwX64() }) {
            build(getLinkStaticTaskName("mingwX64", NativeBuildType.RELEASE)) {
                assertHasDiagnostic(KotlinToolingDiagnostics.NativeCacheUnsupportedTargetDiagnostic)
                assertNoDiagnostic(KotlinToolingDiagnostics.NativeCacheConflictingConfigurationDiagnostic)
            }
        }
    }
}

private fun getLinkStaticTaskName(targetName: String, buildType: NativeBuildType): String =
    lowerCamelCaseName(
        ":link${buildType.name.lowercase().replaceFirstChar { it.uppercaseChar() }}Static",
        targetName
    )

@OptIn(KotlinNativeCacheApi::class)
private fun KGPBaseTest.setupCacheForOptimizedBuildsTest(
    gradleVersion: GradleVersion,
    targetProvider: KotlinMultiplatformExtension.() -> KotlinNativeTarget,
    testBlock: TestProject.() -> Unit
) {
    project("empty", gradleVersion) {
        plugins {
            kotlin("multiplatform")
        }
        buildScriptInjection {
            project.applyMultiplatform {
                val target = targetProvider()

                target.binaries.staticLib {
                    if (buildType == NativeBuildType.RELEASE) {
                        enableNativeCacheForOptimizedBuilds = true
                    }
                }
                sourceSets.commonMain.get().compileStubSourceWithSourceSetName()
            }
        }
        testBlock()
    }
}

/**
 * The newest constant of [DisableCacheInKotlinVersion], resolved reflectively.
 *
 * The generated constants follow a rolling cycle where N-1 is deprecated, N-2 is a deprecation error and N-3 is dropped, so a literal
 * constant makes this test fail to compile on every Kotlin version bump. The newest one is always available and never deprecated.
 */
@OptIn(KotlinNativeCacheApi::class)
private fun latestDisableCacheInKotlinVersion(): DisableCacheInKotlinVersion =
    DisableCacheInKotlinVersion::class.java.declaredClasses
        .mapNotNull { nested -> nested.fields.singleOrNull { it.name == "INSTANCE" }?.get(null) }
        .filterIsInstance<DisableCacheInKotlinVersion>()
        .maxOrNull() ?: error("No DisableCacheInKotlinVersion constants found")
