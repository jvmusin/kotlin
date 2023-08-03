/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import com.intellij.testFramework.TestDataPath
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

@Tag("swift-export")
@TestDataPath("\$PROJECT_ROOT")
class SwiftExportTest : AbstractNativeSwiftExportTest() {

    @Test
    fun trivial() {
        val testDir = File("native/native.tests/testData/SwiftExport/trivial")
        runTest(testDir.absolutePath)
    }

    @Test
    fun primitiveTypeFunctions() {
        val testDir = File("native/native.tests/testData/SwiftExport/primitive_type_functions")
        runTest(testDir.absolutePath)
    }

    @Test
    fun bridgedTypes() {
        val testDir = File("native/native.tests/testData/SwiftExport/bridged_types")
        runTest(testDir.absolutePath)
    }
}