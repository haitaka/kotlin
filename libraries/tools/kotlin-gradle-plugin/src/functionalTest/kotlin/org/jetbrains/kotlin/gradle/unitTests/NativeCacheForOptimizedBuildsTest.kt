/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this file is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:OptIn(KotlinNativeCacheApi::class)

package org.jetbrains.kotlin.gradle.unitTests

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.NativeCacheKind
import org.jetbrains.kotlin.gradle.internal.properties.NativeProperties
import org.jetbrains.kotlin.gradle.plugin.extraProperties
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import org.jetbrains.kotlin.gradle.unitTests.utils.MockKonanHomeExtension
import org.jetbrains.kotlin.gradle.util.buildProjectWithMPP
import org.jetbrains.kotlin.gradle.util.kotlin
import org.jetbrains.kotlin.gradle.utils.NativeCompilerDownloader
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.tooling.core.KotlinToolingVersion
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeCacheForOptimizedBuildsTest {

    @JvmField
    @RegisterExtension
    val mockKonan = MockKonanHomeExtension()

    @Test
    fun `test native cache for optimized builds is disabled by default`() {
        Assumptions.assumeTrue(!HostManager.hostIsMingw) // No cacheable targets on Windows
        with(mppProjectWithFakeKonan(mockKonan)) {
            kotlin {
                cacheableTarget().binaries.staticLib()
            }

            evaluate()

            assertEquals(false, enableNativeCacheForOptimizedBuilds(linkDebugTaskName).get())
            assertEquals(false, enableNativeCacheForOptimizedBuilds(linkReleaseTaskName).get())
            assertNull(binaryOptions(linkDebugTaskName).get()[ENABLE_RELEASE_BINARY_CACHE])
            assertNull(binaryOptions(linkReleaseTaskName).get()[ENABLE_RELEASE_BINARY_CACHE])
        }
    }

    @Test
    fun `test enable native cache for optimized builds for particular buildType`() {
        Assumptions.assumeTrue(!HostManager.hostIsMingw) // No cacheable targets on Windows
        with(mppProjectWithFakeKonan(mockKonan)) {
            kotlin {
                cacheableTarget().binaries.staticLib {
                    if (buildType == NativeBuildType.RELEASE) {
                        enableNativeCacheForOptimizedBuilds = true
                    }
                }
            }

            evaluate()

            assertEquals(true, enableNativeCacheForOptimizedBuilds(linkReleaseTaskName).get())
            assertEquals("true", binaryOptions(linkReleaseTaskName).get()[ENABLE_RELEASE_BINARY_CACHE])
            assertEquals(false, enableNativeCacheForOptimizedBuilds(linkDebugTaskName).get())
            assertNull(binaryOptions(linkDebugTaskName).get()[ENABLE_RELEASE_BINARY_CACHE])
        }
    }

    @Test
    fun `test global property enables native cache for optimized builds`() {
        Assumptions.assumeTrue(!HostManager.hostIsMingw) // No cacheable targets on Windows
        with(
            mppProjectWithFakeKonan(
                mockKonan,
                preApplyCode = {
                    extraProperties.set("kotlin.internal.native.enableReleaseBinaryCache", "true")
                }
            )
        ) {
            kotlin {
                cacheableTarget().binaries.staticLib()
            }

            evaluate()

            assertEquals(true, enableNativeCacheForOptimizedBuilds(linkReleaseTaskName).get())
            assertEquals("true", binaryOptions(linkReleaseTaskName).get()[ENABLE_RELEASE_BINARY_CACHE])
            // The compiler option is injected only for binaries linked with optimizations.
            assertEquals(true, enableNativeCacheForOptimizedBuilds(linkDebugTaskName).get())
            assertNull(binaryOptions(linkDebugTaskName).get()[ENABLE_RELEASE_BINARY_CACHE])
        }
    }

    @Test
    fun `test disableNativeCache takes precedence over native cache for optimized builds`() {
        Assumptions.assumeTrue(!HostManager.hostIsMingw) // No cacheable targets on Windows
        with(mppProjectWithFakeKonan(mockKonan, nativeVersion = disableCacheVersionString)) {
            kotlin {
                cacheableTarget().binaries.staticLib {
                    if (buildType == NativeBuildType.RELEASE) {
                        disableNativeCache(
                            currentVersionForDisableCache,
                            "Disabled for tests",
                            URI("https://kotlinlang.org")
                        )
                        enableNativeCacheForOptimizedBuilds = true
                    }
                }
            }

            evaluate()

            assertEquals(NativeCacheKind.NONE, konanCacheKind(linkReleaseTaskName).get())
            assertEquals(true, enableNativeCacheForOptimizedBuilds(linkReleaseTaskName).get())
            assertNull(binaryOptions(linkReleaseTaskName).get()[ENABLE_RELEASE_BINARY_CACHE])
        }
    }

    companion object {
        private const val ENABLE_RELEASE_BINARY_CACHE = "enableReleaseBinaryCache"

        private val linkDebugTaskName: String
            get() = if (HostManager.hostIsMac) "linkDebugStaticMacosArm64" else "linkDebugStaticLinuxX64"

        private val linkReleaseTaskName: String
            get() = if (HostManager.hostIsMac) "linkReleaseStaticMacosArm64" else "linkReleaseStaticLinuxX64"
    }
}

private fun KotlinMultiplatformExtension.cacheableTarget(): KotlinNativeTarget =
    if (HostManager.hostIsMac) macosArm64() else linuxX64()

private fun ProjectInternal.enableNativeCacheForOptimizedBuilds(linkTask: String): Provider<Boolean> =
    tasks.named(linkTask, KotlinNativeLink::class.java).map { it.enableNativeCacheForOptimizedBuilds }

private fun ProjectInternal.binaryOptions(linkTask: String): Provider<Map<String, String>> =
    tasks.named(linkTask, KotlinNativeLink::class.java).map { it.binaryOptions }

private fun ProjectInternal.konanCacheKind(linkTask: String): Provider<NativeCacheKind> =
    tasks.named(linkTask, KotlinNativeLink::class.java).flatMap { it.konanCacheKind }

private fun mppProjectWithFakeKonan(
    fakeKonanRule: MockKonanHomeExtension,
    copyKonanProperties: Boolean = true,
    nativeVersion: String? = null,
    projectBuilder: ProjectBuilder.() -> Unit = { },
    preApplyCode: Project.() -> Unit = {},
    code: Project.() -> Unit = {},
) = buildProjectWithMPP(
    preApplyCode = {
        fakeKonanRule.setup(copyKonanProperties)

        project.extraProperties.set(
            NativeProperties.NATIVE_HOME.name,
            fakeKonanRule.konanHome.absolutePath
        )

        if (nativeVersion != null) {
            project.extraProperties.set("kotlin.native.version", nativeVersion)
        }

        preApplyCode()
    },
    projectBuilder = projectBuilder,
    code = code
)

/**
 * Selects the appropriate [DisableCacheInKotlinVersion] for the default native version.
 *
 * [KotlinNativeLink.disableCache] performs an exact major.minor.patch comparison between the native version
 * and the [DisableCacheInKotlinVersion]. Snapshot versions (e.g. `2.4.255-SNAPSHOT`) have a different patch
 * number than release versions (e.g. `2.4.0`), so the match would fail.
 * To avoid this, the test project's `kotlin.native.version` is set to the exact version string
 * of the selected [DisableCacheInKotlinVersion].
 */
private val currentVersionForDisableCache: DisableCacheInKotlinVersion by lazy {
    val allInstances: List<DisableCacheInKotlinVersion> =
        DisableCacheInKotlinVersion::class.sealedSubclasses.mapNotNull { it.objectInstance }
    val nativeToolingVersion = KotlinToolingVersion(NativeCompilerDownloader.DEFAULT_KONAN_VERSION)

    allInstances.last { releaseVersion ->
        releaseVersion.major < nativeToolingVersion.major ||
                (releaseVersion.major == nativeToolingVersion.major && releaseVersion.minor < nativeToolingVersion.minor) ||
                (releaseVersion.major == nativeToolingVersion.major && releaseVersion.minor == nativeToolingVersion.minor && releaseVersion.patch <= nativeToolingVersion.patch)
    }
}

private val disableCacheVersionString: String
    get() = "${currentVersionForDisableCache.major}.${currentVersionForDisableCache.minor}.${currentVersionForDisableCache.patch}"
