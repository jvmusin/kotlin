/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm

import org.jetbrains.kotlin.backend.common.serialization.DeclarationTable2
import org.jetbrains.kotlin.backend.common.serialization.StringSignatureClashTracker
import org.jetbrains.kotlin.backend.jvm.lower.getFileClassInfo
import org.jetbrains.kotlin.backend.jvm.serialization.JvmIrSerializerSession
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmSerializeIrMode
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.util.IrMessageLogger
import org.jetbrains.kotlin.ir.util.StringSignature
import org.jetbrains.kotlin.name.FqName

class JvmIrSerializerImpl(private val configuration: CompilerConfiguration) : JvmIrSerializer {

    private val globalTable = mutableMapOf<IrDeclaration, StringSignature>()
//    private val declarationTable = DeclarationTable(JvmGlobalDeclarationTable())

    override fun serializeIrFile(irFile: IrFile): ByteArray? {
        val fileClassFqName = irFile.getFileClassInfo().fileClassFqName
        return makeSerializerSession(fileClassFqName).serializeJvmIrFile(irFile)?.toByteArray()
    }

    override fun serializeTopLevelIrClass(irClass: IrClass): ByteArray? {
        assert(irClass.parent is IrFile)
        val fileClassFqName = (irClass.parent as IrFile).getFileClassInfo().fileClassFqName
        return makeSerializerSession(fileClassFqName).serializeTopLevelClass(irClass)?.toByteArray()
    }

    private fun makeSerializerSession(fileClassFqName: FqName) =
        JvmIrSerializerSession(
            configuration.get(IrMessageLogger.IR_MESSAGE_LOGGER) ?: IrMessageLogger.None,
            DeclarationTable2(globalTable, StringSignatureClashTracker.DEFAULT_TRACKER),
            mutableMapOf(),
            configuration.get(JVMConfigurationKeys.SERIALIZE_IR) ?: JvmSerializeIrMode.NONE,
            fileClassFqName
        )
}