/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import org.gradle.api.logging.LogLevel
import org.gradle.internal.os.OperatingSystem
import org.gradle.testkit.runner.BuildResult
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.commonizer.CommonizerTarget
import org.jetbrains.kotlin.gradle.testbase.*
import org.jetbrains.kotlin.gradle.util.replaceText
import org.jetbrains.kotlin.gradle.util.reportSourceSetCommonizerDependencies
import org.jetbrains.kotlin.incremental.testingUtils.assertEqualDirectories
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.assertTrue
import kotlin.test.fail


@DisplayName("K/N tests for commonizer")
@NativeGradlePluginTests
open class CommonizerIT : KGPBaseTest() {

    companion object {
        private const val commonizerOutput = "Preparing commonized Kotlin/Native libraries"
    }

    @DisplayName("Commonize native distribution with Ios Linux and Windows")
    @GradleTest
    fun testCommonizeNativeDistributionWithIosLinuxWindows(gradleVersion: GradleVersion) {
        nativeProject("commonizeNativeDistributionWithIosLinuxWindows", gradleVersion) {

            build(":cleanNativeDistributionCommonization")

            build("commonize", "-Pkotlin.mpp.enableNativeDistributionCommonizationCache=false") {
                assertTasksExecuted(":commonizeNativeDistribution")
                assertOutputContains(commonizerOutput)
            }

            build("commonize", "-Pkotlin.mpp.enableNativeDistributionCommonizationCache=true") {
                assertTasksUpToDate(":commonizeNativeDistribution")
                assertNativeDistributionCommonizationCacheHit()
                assertOutputContains("Native Distribution Commonization: All available targets are commonized already")
                assertOutputContains("Native Distribution Commonization: Lock acquired")
                assertOutputContains("Native Distribution Commonization: Lock released")
                assertOutputDoesNotContain(commonizerOutput)
            }

            build("commonize", "-Pkotlin.mpp.enableNativeDistributionCommonizationCache=false") {
                assertTasksExecuted(":commonizeNativeDistribution")
                assertOutputContains("Native Distribution Commonization: Cache disabled")
                assertOutputContains(commonizerOutput)
            }
        }
    }

    @DisplayName("Commonize Curl Interop UP-TO-DATE check")
    @GradleTest
    fun testCommonizeCurlInteropUTDCheck(gradleVersion: GradleVersion) {
        nativeProject("commonizeCurlInterop", gradleVersion) {

            configureCommonizerTargets()

            build(":commonize") {
                assertTasksExecuted(":commonizeNativeDistribution")
                assertTasksExecuted(":cinteropCurlTargetA")
                assertTasksExecuted(":cinteropCurlTargetB")
                assertTasksExecuted(":commonizeCInterop")
            }

            build(":commonize") {
                assertNativeDistributionCommonizationCacheHit()
                assertTasksUpToDate(":cinteropCurlTargetA")
                assertTasksUpToDate(":cinteropCurlTargetB")
                assertTasksUpToDate(":commonizeCInterop")
            }

            buildGradleKts.replaceText("curl", "curl2")
            build(":commonize") {
                assertNativeDistributionCommonizationCacheHit()
                assertTasksExecuted(":cinteropCurl2TargetA")
                assertTasksExecuted(":cinteropCurl2TargetB")
                assertTasksExecuted(":commonizeCInterop")
            }

            buildGradleKts.modify {
                it.lineSequence().filter { "curl" !in it }.joinToString("\n")
            }
            build(":commonize") {
                assertNativeDistributionCommonizationCacheHit()
                assertTasksNotExecuted(":cinteropCurlTargetA")
                assertTasksNotExecuted(":cinteropCurlTargetB")
            }
        }
    }

    @DisplayName("Commonize Curl Interop feature flag")
    @GradleTest
    fun testCommonizeCurlInteropFeatureFlag(gradleVersion: GradleVersion) {
        nativeProject("commonizeCurlInterop", gradleVersion) {

            configureCommonizerTargets()

            // Remove feature flag from gradle.properties
            gradleProperties.modify {
                it.lineSequence().filter { "enableCInteropCommonization" !in it }.joinToString("\n")
            }

            build(":commonize") {
                assertTasksExecuted(":commonizeNativeDistribution")
                assertTasksNotExecuted(":cinteropCurlTargetA")
                assertTasksNotExecuted(":cinteropCurlTargetB")
                assertTasksNotExecuted(":commonizeCInterop")
            }

            build(":commonize", "-Pkotlin.mpp.enableCInteropCommonization=true") {
                assertNativeDistributionCommonizationCacheHit()
                assertTasksExecuted(":cinteropCurlTargetA")
                assertTasksExecuted(":cinteropCurlTargetB")
                assertTasksExecuted(":commonizeCInterop")
            }

            build(":commonize", "-Pkotlin.mpp.enableCInteropCommonization=false") {
                assertNativeDistributionCommonizationCacheHit()
                assertTasksNotExecuted(":cinteropCurlTargetA")
                assertTasksNotExecuted(":cinteropCurlTargetB")
                assertTasksNotExecuted(":commonizeCInterop")
            }
        }
    }


    @DisplayName("Commonize Curl Interop copy CommonizeCInterop for Ide")
    @GradleTest
    fun testCommonizeCurlInteropcopyCommonizeCInteropForIde(
        gradleVersion: GradleVersion,
        @TempDir tempDir: Path,
    ) {
        nativeProject("commonizeCurlInterop", gradleVersion, buildOptions = defaultBuildOptions.copy(kotlinUserHome = null)) {

            configureCommonizerTargets()

            fun expectedOutputDirectoryForIde(): Path = tempDir
                .resolve("projects-1")
                .findInPath("commonizer") ?: throw IllegalStateException("Failed to find 'commonizer' directory!")

            val expectedOutputDirectoryForBuild = projectPath.resolve("build/classes/kotlin/commonizer")

            build(":copyCommonizeCInteropForIde", "-Pkotlin.user.home=${tempDir.absolutePathString()}") {
                assertTasksExecuted(":cinteropCurlTargetB")
                assertTasksExecuted(":commonizeCInterop")

                val commonizerIdeOutput = expectedOutputDirectoryForIde()
                assertDirectoryExists(commonizerIdeOutput, "Missing output directory for IDE")
                assertDirectoryExists(expectedOutputDirectoryForBuild, "Missing output directory for build")
                assertEqualDirectories(expectedOutputDirectoryForBuild.toFile(), commonizerIdeOutput.toFile(), false)
            }

            build(":clean", "-Pkotlin.user.home=${tempDir.absolutePathString()}") {
                assertDirectoryExists(expectedOutputDirectoryForIde(), "Expected ide output directory to survive cleaning")
                assertFileNotExists(expectedOutputDirectoryForBuild, "Expected output directory for build to be cleaned")
            }
        }
    }

    @DisplayName("Commonize Curl Interop compilation")
    @GradleTest
    fun testCommonizeCurlInteropCopyCompilation(gradleVersion: GradleVersion) {
        nativeProject("commonizeCurlInterop", gradleVersion) {

            configureCommonizerTargets()

            build(":compileNativeMainKotlinMetadata") {
                assertTasksExecuted(":commonizeNativeDistribution")
                assertTasksExecuted(":cinteropCurlTargetA")
                assertTasksExecuted(":cinteropCurlTargetB")
                assertTasksExecuted(":commonizeCInterop")
            }

            // targetA will be macos
            build(":compileTestKotlinTargetA")

            //targetB will be linuxArm64
            build(":compileTestKotlinTargetB")
        }
    }

    @DisplayName("Commonize SQL Lite Interop")
    @GradleTest
    fun testCommonizeSQLiteInterop(gradleVersion: GradleVersion) {
        nativeProject("commonizeSQLiteInterop", gradleVersion) {

            configureCommonizerTargets()

            build(":commonize") {
                assertTasksExecuted(":cinteropSqliteTargetA")
                assertTasksExecuted(":cinteropSqliteTargetB")
                assertTasksExecuted(":commonizeCInterop")
            }
        }
    }

    @DisplayName("Commonize SQL Lite and Curl Interop")
    @GradleTest
    fun testCommonizeSQLiteAndCurlInterop(gradleVersion: GradleVersion) {
        nativeProject("commonizeSQLiteAndCurlInterop", gradleVersion) {

            configureCommonizerTargets()

            build(":commonize") {
                assertTasksExecuted(":cinteropSqliteTargetA")
                assertTasksExecuted(":cinteropSqliteTargetB")
                assertTasksExecuted(":cinteropCurlTargetA")
                assertTasksExecuted(":cinteropCurlTargetB")
                assertTasksExecuted(":commonizeCInterop")
            }

            build(":compileNativeMainKotlinMetadata") {
                assertTasksUpToDate(":cinteropSqliteTargetA")
                assertTasksUpToDate(":cinteropSqliteTargetB")
                assertTasksUpToDate(":cinteropCurlTargetA")
                assertTasksUpToDate(":cinteropCurlTargetB")
                assertTasksUpToDate(":commonizeCInterop")
            }
        }
    }

    @DisplayName("Commonize Interop using posix APIs")
    @GradleTest
    fun testCommonizeInteropUsingPosixAPIs(gradleVersion: GradleVersion) {
        nativeProject("commonizeInteropUsingPosixApis", gradleVersion) {

            configureCommonizerTargets()

            build(":commonizeCInterop") {
                assertTasksExecuted(":cinteropWithPosixTargetA")
                assertTasksExecuted(":cinteropWithPosixTargetB")
                assertTasksExecuted(":commonizeNativeDistribution")
                assertTasksExecuted(":commonizeCInterop")
            }

            build(":compileNativeMainKotlinMetadata") {
                assertTasksUpToDate(":cinteropWithPosixTargetA")
                assertTasksUpToDate(":cinteropWithPosixTargetB")
                assertNativeDistributionCommonizationCacheHit()
                assertTasksUpToDate(":commonizeCInterop")
            }
        }
    }

    @DisplayName("KT-46234 intermediate source set with only one native target")
    @GradleTest
    fun testIntermediateSourceSetWithOnlyOneNativeTarget(gradleVersion: GradleVersion) {
        testSingleNativePlatform("commonize-kt-46234-singleNativeTarget", gradleVersion)
    }

    @DisplayName("KT-46142 standalone native source set")
    @GradleTest
    fun testStandaloneNativeSourceSet(gradleVersion: GradleVersion) {
        testSingleNativePlatform("commonize-kt-46142-singleNativeTarget", gradleVersion)
    }

    @DisplayName("KT-46248 single supported native target dependency propagation")
    @GradleTest
    fun testSingleSupportedNativeTargetDependencyPropagation(gradleVersion: GradleVersion) {
        val posixDependencyRegex = Regex(""".*Dependency:.*[pP]osix""")
        val dummyCInteropDependencyRegex = Regex(""".*Dependency:.*cinterop-dummy.*""")
        nativeProject("commonize-kt-46248-singleNativeTargetPropagation", gradleVersion) {
            build(":p1:listNativeMainDependencies") {
                assertOutputContains(posixDependencyRegex)
                assertOutputContains(dummyCInteropDependencyRegex)
            }

            build(":p1:listNativeMainParentDependencies") {
                assertOutputContains(posixDependencyRegex)
                assertOutputContains(dummyCInteropDependencyRegex)
            }

            build(":p1:listCommonMainDependencies") {
                assertOutputDoesNotContain(posixDependencyRegex)
                assertOutputDoesNotContain(dummyCInteropDependencyRegex)
            }

            build("assemble") {
                assertTasksExecuted(":p1:compileCommonMainKotlinMetadata")
                assertTasksExecuted(":p1:compileKotlinNativePlatform")
            }
        }
    }

    @DisplayName("KT-46248 single supported native target dependency propagation - cinterop")
    @GradleTest
    fun testSingleSupportedNativeTargetDependencyPropagationCInterop(gradleVersion: GradleVersion) {
        val nativeMainContainsCInteropDependencyRegex = Regex(""".*Dependency:.*cinterop-dummy.*""")
        nativeProject("commonize-kt-47523-singleNativeTargetPropagation-cinterop", gradleVersion) {
            build("listNativePlatformMainDependencies") {
                assertOutputDoesNotContain(nativeMainContainsCInteropDependencyRegex)
            }

            build("listNativeMainDependencies") {
                assertOutputContains(nativeMainContainsCInteropDependencyRegex)
            }

            build("listCommonMainDependencies") {
                assertOutputContains(nativeMainContainsCInteropDependencyRegex)
            }

            build("assemble")
        }
    }

    @DisplayName("KT-48856 single native target dependency propagation - test source set - cinterop")
    @GradleTest
    fun testSingleSupportedNativeTargetDependencyPropagationTestSourceSetCInterop(gradleVersion: GradleVersion) {
        val nativeMainContainsCInteropDependencyRegex = Regex(""".*Dependency:.*cinterop-sampleInterop.*""")
        nativeProject("commonize-kt-48856-singleNativeTargetPropagation-testSourceSet", gradleVersion) {
            build("listNativeTestDependencies") {
                assertOutputContains(
                    nativeMainContainsCInteropDependencyRegex,
                    "Expected sourceSet 'nativeTest' to list cinterop dependency"
                )
            }

            build("assemble")
        }
    }

    @DisplayName("KT-46856 filename too long - all native targets configured")
    @GradleTest
    fun testFilenameTooLongAllNativeTargetsConfigured(gradleVersion: GradleVersion) {
        nativeProject("commonize-kt-46856-all-targets", gradleVersion) {
            build(":commonize")
        }
    }

    @DisplayName("Multiple cinterops with test source sets and compilations - test source sets depending on main")
    @GradleTest
    fun testMultipleCinteropsWithTestSourceSetsAndCompilationsTestSourceSetsDependingOnMain(gradleVersion: GradleVersion) {
        `test multiple cinterops with test source sets and compilations`(gradleVersion, true)
    }

    @DisplayName("Multiple cinterops with test source sets and compilations")
    @GradleTest
    fun testMultipleCinteropsWithTestSourceSetsAndCompilations(gradleVersion: GradleVersion) {
        `test multiple cinterops with test source sets and compilations`(gradleVersion, false)
    }

    @DisplayName("KT-49735 two kotlin targets with same konanTarget")
    @GradleTest
    fun testTwoKotlinTargetsWithSameKonanTarget(gradleVersion: GradleVersion) {
        nativeProject("commonize-kt-49735-twoKotlinTargets-oneKonanTarget", gradleVersion) {
            build(":assemble") {
                assertTasksExecuted(":compileCommonMainKotlinMetadata")
            }
        }
    }

    @DisplayName("KT-48118 c-interops available in commonMain")
    @GradleTest
    fun testCInteropsAvailableInCommonMain(gradleVersion: GradleVersion) {
        nativeProject("commonize-kt-48118-c-interop-in-common-main", gradleVersion) {
            reportSourceSetCommonizerDependencies {
                val upperMain = getCommonizerDependencies("upperMain")
                upperMain.withoutNativeDistributionDependencies().assertDependencyFilesMatches(".*cinterop-dummy")
                upperMain.onlyNativeDistributionDependencies().assertNotEmpty()

                val commonMain = getCommonizerDependencies("commonMain")
                commonMain.withoutNativeDistributionDependencies().assertDependencyFilesMatches(".*cinterop-dummy")
                commonMain.onlyNativeDistributionDependencies().assertNotEmpty()
            }

            build(":compileCommonMainKotlinMetadata")

            build(":compileUpperMainKotlinMetadata")
        }
    }

    @DisplayName("KT-47641 commonizing c-interops does not depend on any source compilation")
    @GradleTest
    fun testCInteropsDoesNotDependOnAnySourceCompilation(gradleVersion: GradleVersion) {
        nativeProject(
            "commonize-kt-47641-cinterops-compilation-dependency",
            gradleVersion,
            buildOptions = defaultBuildOptions.copy(logLevel = LogLevel.DEBUG)
        ) {
            build(
                "commonizeCInterop",
                /* See: https://youtrack.jetbrains.com/issue/KT-59596/CInterop-commonization-depends-on-compile-tasks */
                "-Pkotlin.mpp.applyDefaultHierarchyTemplate=false"
            ) {
                assertTasksExecuted(":p1:commonizeCInterop")
                assertTasksExecuted(":p2:commonizeCInterop")

                assertAnyTaskHasBeenExecuted(findTasksByPattern(":p1:cinteropTestWithPosix.*".toRegex()))
                assertAnyTaskHasBeenExecuted(findTasksByPattern(":p2:cinteropTestWithPosix.*".toRegex()))
                assertAnyTaskHasBeenExecuted(findTasksByPattern(":p2:cinteropTestWithPosixP2.*".toRegex()))

                /* Make sure that we correctly reference any compile tasks in this test (test is useless otherwise) */
                assertOutputContains("Register task :p1.*compile.*".toRegex())
                assertOutputContains("Register task :p2.*compile.*".toRegex())

                /* CInterops *shall not* require any compilation */
                assertOutputDoesNotContain("(Executing actions for task|Executing task) ':p0.*compile.*'".toRegex())
                assertOutputDoesNotContain("(Executing actions for task|Executing task) ':p1.*compile.*'".toRegex())
                assertOutputDoesNotContain("(Executing actions for task|Executing task) ':p2.*compile.*'".toRegex())
            }
        }
    }

    @DisplayName("KT-48138 commonizing c-interops when nativeTest and nativeMain have different targets")
    @GradleTest
    fun testCommonizingCInteropsWhenNativeTestAndNativeMainHaveDifferentTargets(gradleVersion: GradleVersion) {
        nativeProject("commonize-kt-48138-nativeMain-nativeTest-different-targets", gradleVersion) {
            reportSourceSetCommonizerDependencies {
                val nativeMain = getCommonizerDependencies("nativeMain")
                nativeMain.withoutNativeDistributionDependencies().assertDependencyFilesMatches(".*cinterop-dummy")
                nativeMain.onlyNativeDistributionDependencies().assertNotEmpty()
                nativeMain.assertTargetOnAllDependencies(CommonizerTarget(LINUX_X64, LINUX_ARM64, MINGW_X64))

                val nativeTest = getCommonizerDependencies("nativeTest")
                nativeTest.onlyNativeDistributionDependencies().assertNotEmpty()
                nativeTest.withoutNativeDistributionDependencies().assertDependencyFilesMatches(".*cinterop-dummy")
                nativeTest.assertTargetOnAllDependencies(CommonizerTarget(LINUX_X64, LINUX_ARM64))
            }
        }
    }

    @DisplayName("KT-50847 missing cinterop in supported target")
    @GradleTest
    fun testMissingCinteropInSupportedTarget(gradleVersion: GradleVersion) {
        nativeProject("commonize-kt-50847-cinterop-missing-in-supported-target", gradleVersion) {
            build(":compileCommonMainKotlinMetadata", "-PdisableTargetNumber=1") {
                assertTasksSkipped(":cinteropSimpleTarget1")
                assertTasksExecuted(":cinteropSimpleTarget2")
                assertTasksExecuted(":commonizeCInterop")
            }

            build(":compileCommonMainKotlinMetadata", "-PdisableTargetNumber=2") {
                assertTasksSkipped(":cinteropSimpleTarget2")
                assertTasksExecuted(":cinteropSimpleTarget1")
                assertTasksExecuted(":commonizeCInterop")
            }
        }
    }

    @DisplayName("KT-52243 cinterop caching")
    @GradleTest
    fun testCInteropCaching(gradleVersion: GradleVersion) {
        nativeProject("commonizeCurlInterop", gradleVersion) {

            configureCommonizerTargets()

            val localBuildCacheDir = projectPath.resolve("local-build-cache-dir").also { assertTrue(it.toFile().mkdirs()) }
            enableLocalBuildCache(localBuildCacheDir)

            build(":commonize", buildOptions = defaultBuildOptions.copy(buildCacheEnabled = true)) {
                assertTasksExecuted(":cinteropCurlTargetA", ":cinteropCurlTargetB")
            }
            build(":clean") {}
            build(":commonize", buildOptions = defaultBuildOptions.copy(buildCacheEnabled = true)) {
                assertTasksFromCache(":cinteropCurlTargetA", ":cinteropCurlTargetB")
            }
        }
    }

    @DisplayName("KT-51517 commonization with transitive cinterop")
    @GradleTest
    fun testCommonizationWithTransitiveCinterop(gradleVersion: GradleVersion) {
        nativeProject("commonize-kt-51517-transitive-cinterop", gradleVersion) {
            build(":app:assemble") {
                assertTasksExecuted(":lib:transformCommonMainCInteropDependenciesMetadata")
                assertTasksExecuted(":lib:commonizeCInterop")
                assertTasksExecuted(":lib:compileCommonMainKotlinMetadata")
                assertTasksExecuted(":app:commonizeCInterop")
                assertTasksExecuted(":app:compileNativeMainKotlinMetadata")
            }
        }
    }

    @DisplayName("KT-57796 commonization with two cinterop commonizer groups`")
    @GradleTest
    fun testCommonizationWithTwoCInteropCommonizerGroups(gradleVersion: GradleVersion) {
        nativeProject("commonize-kt-57796-twoCInteropCommonizerGroups", gradleVersion) {
            build(":app:commonizeCIntero") {
                assertTasksExecuted(":lib:transformCommonMainCInteropDependenciesMetadata")
                assertTasksExecuted(":lib:commonizeCInterop")
                assertTasksExecuted(":app:transformCommonMainCInteropDependenciesMetadata")
                assertTasksExecuted(":app:commonizeCInterop")
            }
        }
    }

    @DisplayName("KT-57796 commonization with two cinterop commonizer groups`")
    @GradleTest
    fun testCommonizationWithLibraryContainingTwoRoots(gradleVersion: GradleVersion) {
        project("commonize-kt-56729-consume-library-with-two-roots", gradleVersion) {
            build("publish")

            build(":consumer:assemble") {
                assertTasksExecuted(":consumer:compileCommonMainKotlinMetadata")
                assertOutputDoesNotContain("Duplicated libraries:")
                assertOutputDoesNotContain("w: duplicate library name")
            }
        }
    }

    @DisplayName("KT-58223 test commonized libraries for IDE can be stored in different data dir")
    @GradleTest
    fun testCommonizedLibrariesForIDECanBeStoredInDifferentDir(
        gradleVersion: GradleVersion,
        @TempDir tempDir: Path,
    ) {
        nativeProject("commonizeCurlInterop", gradleVersion, buildOptions = defaultBuildOptions.copy(kotlinUserHome = null)) {
            gradleProperties.append("kotlin.user.home=${tempDir.absolutePathString()}")

            configureCommonizerTargets()

            fun expectedOutputDirectoryForIde(): Path = tempDir
                .resolve("projects-1")
                .findInPath("commonizer") ?: throw IllegalStateException("Failed to find 'commonizer' directory!")

            build(":copyCommonizeCInteropForIde") {
                assertTasksExecuted(":cinteropCurlTargetB")
                assertTasksExecuted(":commonizeCInterop")

                assertDirectoryExists(expectedOutputDirectoryForIde(), "Missing output directory for IDE")
            }

            build(":clean") {
                assertDirectoryExists(expectedOutputDirectoryForIde(), "Expected ide output directory to survive cleaning")
            }
        }
    }


    private fun `test multiple cinterops with test source sets and compilations`(
        gradleVersion: GradleVersion,
        testSourceSetsDependingOnMain: Boolean,
    ) {
        nativeProject("commonizeMultipleCInteropsWithTests", gradleVersion) {

            val isMac = HostManager.hostIsMac

            fun BuildResult.assertTestSourceSetsDependingOnMainParameter() {
                val message = "testSourceSetsDependingOnMain is set"
                if (testSourceSetsDependingOnMain) assertOutputContains(message) else assertOutputDoesNotContain(message)
            }

            val testSourceSetsDependingOnMainParameterOption = defaultBuildOptions.copy(
                freeArgs = listOf("-PtestSourceSetsDependingOnMain=$testSourceSetsDependingOnMain")
            )

            reportSourceSetCommonizerDependencies(options = testSourceSetsDependingOnMainParameterOption) {
                it.assertTestSourceSetsDependingOnMainParameter()

                /* this source sets are also shared with a jvm target */
                getCommonizerDependencies("commonMain").assertEmpty()
                getCommonizerDependencies("commonTest").assertEmpty()

                getCommonizerDependencies("nativeMain").withoutNativeDistributionDependencies().apply {
                    assertDependencyFilesMatches(".*nativeHelper")
                    assertTargetOnAllDependencies(
                        CommonizerTarget(IOS_X64, IOS_ARM64, LINUX_X64, LINUX_ARM64, MACOS_X64, MINGW_X64)
                    )
                }

                getCommonizerDependencies("nativeTest").withoutNativeDistributionDependencies().apply {
                    assertDependencyFilesMatches(".*nativeHelper", ".*nativeTestHelper")
                    assertTargetOnAllDependencies(
                        CommonizerTarget(IOS_X64, IOS_ARM64, LINUX_X64, LINUX_ARM64, MACOS_X64, MINGW_X64)
                    )
                }

                getCommonizerDependencies("unixMain").withoutNativeDistributionDependencies().apply {
                    assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper")
                    assertTargetOnAllDependencies(CommonizerTarget(IOS_X64, IOS_ARM64, LINUX_X64, LINUX_ARM64, MACOS_X64))
                }

                getCommonizerDependencies("unixTest").withoutNativeDistributionDependencies().apply {
                    assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper", ".*nativeTestHelper")
                    assertTargetOnAllDependencies(CommonizerTarget(IOS_X64, IOS_ARM64, LINUX_X64, LINUX_ARM64, MACOS_X64))
                }

                getCommonizerDependencies("linuxMain").withoutNativeDistributionDependencies().apply {
                    assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper")
                    assertTargetOnAllDependencies(CommonizerTarget(LINUX_X64, LINUX_ARM64))
                }

                getCommonizerDependencies("linuxTest").withoutNativeDistributionDependencies().apply {
                    assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper", ".*nativeTestHelper")
                    assertTargetOnAllDependencies(CommonizerTarget(LINUX_X64, LINUX_ARM64))
                }

                getCommonizerDependencies("linuxX64Main").assertEmpty()
                getCommonizerDependencies("linuxArm64Main").assertEmpty()
                getCommonizerDependencies("linuxX64Test").assertEmpty()
                getCommonizerDependencies("linuxArm64Test").assertEmpty()

                if (isMac) {
                    getCommonizerDependencies("appleMain").withoutNativeDistributionDependencies().apply {
                        assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper", ".*appleHelper")
                        assertTargetOnAllDependencies(CommonizerTarget(IOS_X64, IOS_ARM64, MACOS_X64))
                    }

                    getCommonizerDependencies("appleTest").withoutNativeDistributionDependencies().apply {
                        assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper", ".*appleHelper", ".*nativeTestHelper")
                        assertTargetOnAllDependencies(CommonizerTarget(IOS_X64, IOS_ARM64, MACOS_X64))
                    }

                    getCommonizerDependencies("iosMain").withoutNativeDistributionDependencies().apply {
                        assertDependencyFilesMatches(".*nativeHelper", ".*unixHelper", ".*appleHelper")
                        assertTargetOnAllDependencies(CommonizerTarget(IOS_X64, IOS_ARM64))
                    }

                    getCommonizerDependencies("iosTest").withoutNativeDistributionDependencies().apply {
                        assertDependencyFilesMatches(
                            ".*nativeHelper", ".*unixHelper", ".*appleHelper", ".*nativeTestHelper", ".*iosTestHelper"
                        )
                        assertTargetOnAllDependencies(CommonizerTarget(IOS_X64, IOS_ARM64))
                    }

                    getCommonizerDependencies("macosMain").assertEmpty()
                    getCommonizerDependencies("macosTest").assertEmpty()
                    getCommonizerDependencies("iosX64Main").assertEmpty()
                    getCommonizerDependencies("iosX64Test").assertEmpty()
                    getCommonizerDependencies("iosArm64Main").assertEmpty()
                    getCommonizerDependencies("iosArm64Test").assertEmpty()
                }

                getCommonizerDependencies("windowsX64Main").assertEmpty()
                getCommonizerDependencies("windowsX64Test").assertEmpty()
            }

            build(":assemble", buildOptions = testSourceSetsDependingOnMainParameterOption) {
                assertTestSourceSetsDependingOnMainParameter()
                assertTasksUpToDate(":commonizeNativeDistribution")
                assertOutputContains("Native Distribution Commonization: Cache hit")
                assertTasksUpToDate(":commonizeCInterop")
            }

            build(":compileNativeMainKotlinMetadata", buildOptions = testSourceSetsDependingOnMainParameterOption) {
                assertTestSourceSetsDependingOnMainParameter()
            }

            build(":compileUnixMainKotlinMetadata", buildOptions = testSourceSetsDependingOnMainParameterOption) {
                assertTestSourceSetsDependingOnMainParameter()
            }

            build(":compileLinuxMainKotlinMetadata", buildOptions = testSourceSetsDependingOnMainParameterOption) {
                assertTestSourceSetsDependingOnMainParameter()
            }

            if (isMac) {
                build(":compileAppleMainKotlinMetadata", buildOptions = testSourceSetsDependingOnMainParameterOption) {
                    assertTestSourceSetsDependingOnMainParameter()
                }

                build(":compileIosMainKotlinMetadata", buildOptions = testSourceSetsDependingOnMainParameterOption) {
                    assertTestSourceSetsDependingOnMainParameter()
                }
            }
        }
    }

    private fun testSingleNativePlatform(projectName: String, gradleVersion: GradleVersion) {

        val posixInIntransitiveMetadataConfigurationRegex = Regex(""".*intransitiveMetadataConfiguration:.*([pP])osix""")

        nativeProject(projectName, gradleVersion) {
            build(":p1:listNativePlatformMainDependencies", "-Pkotlin.mpp.enableIntransitiveMetadataConfiguration=false") {
                assertOutputDoesNotContain(posixInIntransitiveMetadataConfigurationRegex)
            }

            build(":p1:listNativePlatformMainDependencies", "-Pkotlin.mpp.enableIntransitiveMetadataConfiguration=true") {
                assertOutputContains(posixInIntransitiveMetadataConfigurationRegex)
            }

            build("assemble")
        }

    }

    private fun TestProject.configureCommonizerTargets() {
        buildGradleKts.replaceText("<targetA>", CommonizableTargets.targetA.value)
        buildGradleKts.replaceText("<targetB>", CommonizableTargets.targetB.value)
    }

    private fun BuildResult.assertNativeDistributionCommonizationCacheHit() {
        assertOutputContains("Native Distribution Commonization: Cache hit")
    }
}

private data class TargetSubstitution(val value: String) {
    override fun toString(): String = value
}

private object CommonizableTargets {
    private val os = OperatingSystem.current()

    val targetA = when {
        os.isMacOsX -> TargetSubstitution("macosX64")
        os.isLinux -> TargetSubstitution("linuxX64")
        os.isWindows -> TargetSubstitution("mingwX64")
        else -> fail("Unsupported os: ${os.name}")
    }

    val targetB = when {
        os.isMacOsX -> TargetSubstitution("linuxX64")
        os.isLinux -> TargetSubstitution("linuxArm64")
        os.isWindows -> TargetSubstitution("linuxX64")
        else -> fail("Unsupported os: ${os.name}")
    }
}
