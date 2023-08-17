/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.ic

import org.jetbrains.kotlin.backend.common.serialization.cityHash64
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.*
import org.jetbrains.kotlin.protobuf.CodedInputStream
import org.jetbrains.kotlin.protobuf.CodedOutputStream
import org.jetbrains.kotlin.utils.addToStdlib.butIf
import org.jetbrains.kotlin.utils.addToStdlib.runIf
import java.io.File

typealias CachedTestFunctionsWithTheirPackage = Map<String, List<String>>

class JsPerFileCache(private val moduleArtifacts: List<ModuleArtifact>) : JsMultiArtifactCache<JsPerFileCache.CachedFileInfo>() {
    companion object {
        private const val JS_MODULE_HEADER = "js.module.header.bin"
        private const val CACHED_FILE_JS = "file.js"
        private const val CACHED_EXPORT_FILE_JS = "file.export.js"
        private const val CACHED_FILE_JS_MAP = "file.js.map"
        private const val CACHED_FILE_D_TS = "file.d.ts"
    }

    sealed class CachedFileInfo(val moduleArtifact: ModuleArtifact, moduleHeader: JsIrModuleHeader?) : CacheInfo {
        var crossFileReferencesHash: ICHash = ICHash()
        final override lateinit var jsIrHeader: JsIrModuleHeader

        init {
            if (moduleHeader != null) jsIrHeader = moduleHeader
        }


        sealed class SerializableCachedFileInfo(
            moduleArtifact: ModuleArtifact,
            val fileArtifact: SrcFileArtifact,
            moduleHeader: JsIrModuleHeader?
        ) : CachedFileInfo(moduleArtifact, moduleHeader) {
            fun getArtifactWithName(name: String): File? = moduleArtifact.artifactsDir?.let { File(it, "$filePrefix.$name") }
            private val filePrefix by lazy(LazyThreadSafetyMode.NONE) { fileArtifact.srcFilePath.run { "${substringAfterLast('/')}.${cityHash64()}" } }
        }

        class MainFileCachedInfo(moduleArtifact: ModuleArtifact, fileArtifact: SrcFileArtifact, moduleHeader: JsIrModuleHeader? = null) :
            SerializableCachedFileInfo(moduleArtifact, fileArtifact, moduleHeader) {
            var testFunction: String? = null
            var suiteFunction: String? = null
            var exportFileCachedInfo: ExportFileCachedInfo? = null

            val jsFileArtifact by lazy(LazyThreadSafetyMode.NONE) { getArtifactWithName(CACHED_FILE_JS) }
            val moduleHeaderArtifact by lazy(LazyThreadSafetyMode.NONE) { getArtifactWithName(JS_MODULE_HEADER) }
            val sourceMapFileArtifact by lazy(LazyThreadSafetyMode.NONE) { getArtifactWithName(CACHED_FILE_JS_MAP) }
        }

        class ExportFileCachedInfo(
            moduleArtifact: ModuleArtifact,
            fileArtifact: SrcFileArtifact,
            moduleHeader: JsIrModuleHeader? = null,
            var tsDeclarationsHash: Long? = null
        ) : SerializableCachedFileInfo(moduleArtifact, fileArtifact, moduleHeader) {
            val jsFileArtifact by lazy(LazyThreadSafetyMode.NONE) { getArtifactWithName(CACHED_EXPORT_FILE_JS) }
            val dtsFileArtifact by lazy(LazyThreadSafetyMode.NONE) { getArtifactWithName(CACHED_FILE_D_TS) }
        }

        class ModuleProxyFileCachedInfo(moduleArtifact: ModuleArtifact, moduleHeader: JsIrModuleHeader? = null) :
            CachedFileInfo(moduleArtifact, moduleHeader) {
            var suiteFunction: String? = null
            var packagesToItsTestFunctions: CachedTestFunctionsWithTheirPackage? = null
            val testFunctionsHash: ICHash? get() = packagesToItsTestFunctions?.testFunctionsHashForIC()

            val jsFileArtifact by lazy(LazyThreadSafetyMode.NONE) { getArtifactWithName(CACHED_FILE_JS) }
            val dtsFileArtifact by lazy(LazyThreadSafetyMode.NONE) { getArtifactWithName(CACHED_FILE_D_TS) }
            val moduleHeaderArtifact by lazy(LazyThreadSafetyMode.NONE) { getArtifactWithName(JS_MODULE_HEADER) }

            private fun getArtifactWithName(name: String): File? = moduleArtifact.artifactsDir?.let { File(it, "entry.$name") }
        }
    }

    private val headerToCachedInfo = hashMapOf<JsIrModuleHeader, CachedFileInfo>()
    private val moduleFragmentToExternalName = ModuleFragmentToExternalName(emptyMap())

    private fun JsIrProgramFragment.getMainFragmentExternalName(moduleArtifact: ModuleArtifact) =
        moduleFragmentToExternalName.getExternalNameFor(name, packageFqn, moduleArtifact.moduleExternalName)

    private fun JsIrProgramFragment.getExportFragmentExternalName(moduleArtifact: ModuleArtifact) =
        moduleFragmentToExternalName.getExternalNameForExporterFile(name, packageFqn, moduleArtifact.moduleExternalName)

    private fun JsIrProgramFragment.asIrModuleHeader(moduleName: String, reexportedIn: String? = null): JsIrModuleHeader {
        return JsIrModuleHeader(
            moduleName = moduleName,
            externalModuleName = moduleName,
            definitions = definitions,
            nameBindings = nameBindings.mapValues { v -> v.value.toString() },
            optionalCrossModuleImports = optionalCrossModuleImports,
            reexportedInModuleWithName = reexportedIn,
            associatedModule = JsIrModule(moduleName, moduleName, listOf(this), reexportedIn)
        )
    }

    private fun List<JsIrProgramFragment>.loadJsIrModuleHeaders(moduleArtifact: ModuleArtifact) =
        LoadedJsIrModuleHeaders(
            mainFragment.run { asIrModuleHeader(getMainFragmentExternalName(moduleArtifact)) },
            exportFragment?.run { asIrModuleHeader(mainFragment.getExportFragmentExternalName(moduleArtifact), moduleArtifact.moduleExternalName) },
        )

    private fun <T : CachedFileInfo> CodedInputStream.loadSingleCachedFileInfo(cachedFileInfo: T): T = cachedFileInfo.also {
        val moduleName = readString()
        var reexportedIn: String? = null

        it.crossFileReferencesHash = ICHash.fromProtoStream(this)

        when (it) {
            is CachedFileInfo.MainFileCachedInfo -> {
                it.testFunction = runIf(readBool()) { readString() }
                it.suiteFunction = runIf(readBool()) { readString() }
            }
            is CachedFileInfo.ExportFileCachedInfo -> {
                it.tsDeclarationsHash = runIf(readBool()) { readInt64() }
                reexportedIn = cachedFileInfo.moduleArtifact.moduleExternalName
            }
            is CachedFileInfo.ModuleProxyFileCachedInfo -> {
                it.suiteFunction = ifTrue { readString() }
                it.packagesToItsTestFunctions = loadTestFunctions()
            }
        }

        val (definitions, nameBindings, optionalCrossModuleImports) = fetchJsIrModuleHeaderNames()

        it.jsIrHeader = JsIrModuleHeader(
            moduleName = moduleName,
            externalModuleName = moduleName,
            definitions = definitions,
            nameBindings = nameBindings,
            optionalCrossModuleImports = optionalCrossModuleImports,
            reexportedInModuleWithName = reexportedIn,
            associatedModule = null,
        )
    }

    private fun CodedInputStream.loadTestFunctions() = buildMap {
        repeat(readInt32()) {
            put(readString(), buildList {
                repeat(readInt32()) { add(readString()) }
            })
        }
    }

    private fun <T> CachedFileInfo.MainFileCachedInfo.readModuleHeaderCache(f: CodedInputStream.() -> T): T? =
        moduleHeaderArtifact?.useCodedInputIfExists(f)

    private fun ModuleArtifact.fetchFileInfoFor(fileArtifact: SrcFileArtifact): List<CachedFileInfo>? {
        val mainFileCachedFileInfo = CachedFileInfo.MainFileCachedInfo(this, fileArtifact)

        return mainFileCachedFileInfo.readModuleHeaderCache {
            mainFileCachedFileInfo.run {
                exportFileCachedInfo = fetchFileInfoForExportedPart(this)
                loadSingleCachedFileInfo(this)
                listOfNotNull(exportFileCachedInfo, this)
            }
        }
    }

    private fun ModuleArtifact.fetchModuleProxyFileInfo(): CachedFileInfo.ModuleProxyFileCachedInfo? {
        val mainFileCachedFileInfo = CachedFileInfo.ModuleProxyFileCachedInfo(this)
        return mainFileCachedFileInfo.moduleHeaderArtifact?.useCodedInputIfExists {
            loadSingleCachedFileInfo(mainFileCachedFileInfo)
        }
    }

    private fun CodedInputStream.fetchFileInfoForExportedPart(mainCachedFileInfo: CachedFileInfo.MainFileCachedInfo): CachedFileInfo.ExportFileCachedInfo? {
        return ifTrue {
            loadSingleCachedFileInfo(
                CachedFileInfo.ExportFileCachedInfo(mainCachedFileInfo.moduleArtifact, mainCachedFileInfo.fileArtifact)
            )
        }
    }

    private fun CodedOutputStream.commitSingleFileInfo(cachedFileInfo: CachedFileInfo.SerializableCachedFileInfo) {
        writeStringNoTag(cachedFileInfo.jsIrHeader.externalModuleName)
        cachedFileInfo.crossFileReferencesHash.toProtoStream(this)
        when (cachedFileInfo) {
            is CachedFileInfo.MainFileCachedInfo -> {
                ifNotNull(cachedFileInfo.testFunction, ::writeStringNoTag)
                ifNotNull(cachedFileInfo.suiteFunction, ::writeStringNoTag)
            }
            is CachedFileInfo.ExportFileCachedInfo -> ifNotNull(cachedFileInfo.tsDeclarationsHash, ::writeInt64NoTag)
        }
        commitJsIrModuleHeaderNames(cachedFileInfo.jsIrHeader)
    }

    private fun CodedOutputStream.writeTestFunctions(cachedTestFunctionsWithTheirPackage: CachedTestFunctionsWithTheirPackage) {
        writeInt32NoTag(cachedTestFunctionsWithTheirPackage.size)
        cachedTestFunctionsWithTheirPackage.forEach { (key, value) ->
            writeStringNoTag(key)
            writeInt32NoTag(value.size)
            value.forEach(::writeStringNoTag)
        }
    }

    private fun CachedFileInfo.commitFileInfo() = when (this) {
        is CachedFileInfo.MainFileCachedInfo -> {
            moduleHeaderArtifact?.useCodedOutput {
                ifNotNull(exportFileCachedInfo) { commitSingleFileInfo(it) }
                commitSingleFileInfo(this@commitFileInfo)
            }
        }
        is CachedFileInfo.ModuleProxyFileCachedInfo -> {
            moduleHeaderArtifact?.useCodedOutput {
                writeStringNoTag(jsIrHeader.externalModuleName)
                crossFileReferencesHash.toProtoStream(this)
                ifNotNull(suiteFunction, ::writeStringNoTag)
                ifNotNull(packagesToItsTestFunctions) { writeTestFunctions(it) }
                commitJsIrModuleHeaderNames(jsIrHeader)
            }
        }
        is CachedFileInfo.ExportFileCachedInfo -> {}
    }

    private fun ModuleArtifact.generateModuleProxyFileCachedInfo(
        suiteFunction: String?,
        cachedTestFunctionsWithTheirPackage: CachedTestFunctionsWithTheirPackage?
    ): CachedFileInfo {
        return CachedFileInfo.ModuleProxyFileCachedInfo(
            this,
            generateProxyIrModuleWith(
                moduleExternalName,
                moduleExternalName,
                suiteFunction,
                cachedTestFunctionsWithTheirPackage
            ).makeModuleHeader()
        ).also {
            it.suiteFunction = suiteFunction
            it.packagesToItsTestFunctions = cachedTestFunctionsWithTheirPackage
        }
    }

    private fun ModuleArtifact.loadFileInfoFor(fileArtifact: SrcFileArtifact): List<CachedFileInfo> {
        val programFragments = fileArtifact.loadJsIrFragments()
        val headers = programFragments.loadJsIrModuleHeaders(this)
        val mainFragment = programFragments.mainFragment

        val mainCachedFileInfo = CachedFileInfo.MainFileCachedInfo(this, fileArtifact, headers.mainHeader).apply {
            testFunction = mainFragment.testFunction
            suiteFunction = mainFragment.suiteFunction

            mainFragment.testFunction = null
            mainFragment.suiteFunction = null
        }

        if (headers.exportHeader != null) {
            val tsDeclarationsHash = programFragments.exportFragment?.dts?.raw?.cityHash64()
            val cachedExportFileInfo = mainCachedFileInfo.readModuleHeaderCache { fetchFileInfoForExportedPart(mainCachedFileInfo) }
            mainCachedFileInfo.exportFileCachedInfo = if (cachedExportFileInfo?.tsDeclarationsHash != tsDeclarationsHash) {
                CachedFileInfo.ExportFileCachedInfo(
                    this,
                    fileArtifact,
                    headers.exportHeader,
                    tsDeclarationsHash,
                )
            } else {
                cachedExportFileInfo
            }
        }

        return listOfNotNull(mainCachedFileInfo.exportFileCachedInfo, mainCachedFileInfo)
    }

    private val CachedFileInfo.cachedFiles: CachedFileArtifacts?
        get() = when (this) {
            is CachedFileInfo.MainFileCachedInfo -> jsFileArtifact?.let { CachedFileArtifacts(it, sourceMapFileArtifact, null) }
            is CachedFileInfo.ExportFileCachedInfo -> jsFileArtifact?.let { CachedFileArtifacts(it, null, dtsFileArtifact) }
            is CachedFileInfo.ModuleProxyFileCachedInfo -> jsFileArtifact?.let { CachedFileArtifacts(it, null, dtsFileArtifact) }
        }

    override fun getMainModuleAndDependencies(cacheInfo: List<CachedFileInfo>) = null to cacheInfo

    override fun fetchCompiledJsCodeForNullCacheInfo() = PerFileEntryPointCompilationOutput()

    override fun fetchCompiledJsCode(cacheInfo: CachedFileInfo) =
        cacheInfo.cachedFiles?.let { (jsCodeFile, sourceMapFile, tsDeclarationsFile) ->
            jsCodeFile.ifExists { this }
                ?.let { CompilationOutputsCached(it, sourceMapFile?.ifExists { this }, tsDeclarationsFile?.ifExists { this }) }
        }

    override fun commitCompiledJsCode(cacheInfo: CachedFileInfo, compilationOutputs: CompilationOutputsBuilt) =
        cacheInfo.cachedFiles?.let { (jsCodeFile, jsMapFile, tsDeclarationsFile) ->
            tsDeclarationsFile?.writeIfNotNull(compilationOutputs.tsDefinitions?.raw)
            compilationOutputs.writeJsCodeIntoModuleCache(jsCodeFile, jsMapFile)
        } ?: compilationOutputs

    override fun loadJsIrModule(cacheInfo: CachedFileInfo): JsIrModule =
        when (cacheInfo) {
            is CachedFileInfo.ModuleProxyFileCachedInfo -> generateProxyIrModuleWith(
                cacheInfo.jsIrHeader.externalModuleName,
                cacheInfo.jsIrHeader.externalModuleName,
                cacheInfo.suiteFunction,
                cacheInfo.packagesToItsTestFunctions
            )
            is CachedFileInfo.SerializableCachedFileInfo -> {
                val fragments = cacheInfo.fileArtifact.loadJsIrFragments().also {
                    it.mainFragment.testFunction = null
                    it.mainFragment.suiteFunction = null
                }
                val isExportFileCachedInfo = cacheInfo is CachedFileInfo.ExportFileCachedInfo
                JsIrModule(
                    cacheInfo.jsIrHeader.moduleName,
                    cacheInfo.jsIrHeader.externalModuleName,
                    listOf(if (isExportFileCachedInfo) fragments.exportFragment!! else fragments.mainFragment),
                    runIf(isExportFileCachedInfo) { cacheInfo.moduleArtifact.moduleSafeName }
                )
            }
        }

    override fun loadProgramHeadersFromCache(): List<CachedFileInfo> {
        return moduleArtifacts
            .flatMap { moduleArtifact ->
                var hasFileWithJsExportedDeclaration = false
                var suiteFunction: String? = null
                val testFunctions = mutableMapOf<String, MutableList<String>>()

                moduleArtifact.fileArtifacts
                    .flatMap { srcFileArtifact ->
                        val cachedFileInfo = if (srcFileArtifact.isModified())
                            moduleArtifact.loadFileInfoFor(srcFileArtifact)
                        else
                            moduleArtifact.fetchFileInfoFor(srcFileArtifact) ?: moduleArtifact.loadFileInfoFor(srcFileArtifact)

                        if (!hasFileWithJsExportedDeclaration && cachedFileInfo.hasExportFile()) {
                            hasFileWithJsExportedDeclaration = true
                        }

                        val mainFileInfo = cachedFileInfo.last() as? CachedFileInfo.MainFileCachedInfo
                        val testFunction = mainFileInfo?.testFunction

                        if (testFunction != null) {
                            val packageFqn = mainFileInfo.packageFqn
                            val testFunctionsList = testFunctions.getOrPut(packageFqn) { mutableListOf() }
                            testFunctionsList.add(testFunction)
                            suiteFunction = mainFileInfo.suiteFunction
                        }

                        cachedFileInfo
                    }
                    .butIf(hasFileWithJsExportedDeclaration || suiteFunction != null) { fileInfoList ->
                        val fetchedProxyFileInfo = moduleArtifact.fetchModuleProxyFileInfo()
                        val proxyFileCachedInfo = if (
                            suiteFunction != fetchedProxyFileInfo?.suiteFunction ||
                            fetchedProxyFileInfo?.testFunctionsHash != testFunctions.testFunctionsHashForIC()
                        ) {
                            moduleArtifact.generateModuleProxyFileCachedInfo(suiteFunction, testFunctions.takeIf { it.isNotEmpty() })
                        } else fetchedProxyFileInfo
                        fileInfoList.plus(proxyFileCachedInfo)
                    }
            }
            .onEach { headerToCachedInfo[it.jsIrHeader] = it }
    }

    override fun loadRequiredJsIrModules(crossModuleReferences: Map<JsIrModuleHeader, CrossModuleReferences>) {
        for ((header, references) in crossModuleReferences) {
            val cachedInfo = headerToCachedInfo[header] ?: notFoundIcError("artifact for module ${header.moduleName}")

            val actualCrossModuleHash = references.crossModuleReferencesHashForIC()

            if (header.associatedModule == null && cachedInfo.crossFileReferencesHash != actualCrossModuleHash) {
                header.associatedModule = loadJsIrModule(cachedInfo)
            }

            header.associatedModule?.let {
                cachedInfo.crossFileReferencesHash = actualCrossModuleHash
                cachedInfo.commitFileInfo()
            }
        }
    }

    private data class CachedFileArtifacts(val jsCodeFile: File, val sourceMapFile: File?, val tsDeclarationsFile: File?)
    private data class LoadedJsIrModuleHeaders(val mainHeader: JsIrModuleHeader, val exportHeader: JsIrModuleHeader?)

    private fun List<JsPerFileCache.CachedFileInfo>.hasExportFile(): Boolean = size > 1
    private val CachedFileInfo.packageFqn: String get() = moduleFragmentToExternalName.getPackageFqn(jsIrHeader.moduleName)
}