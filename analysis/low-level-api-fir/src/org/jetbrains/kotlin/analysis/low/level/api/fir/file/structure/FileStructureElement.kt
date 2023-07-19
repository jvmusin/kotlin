/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirModuleResolveComponents
import org.jetbrains.kotlin.analysis.low.level.api.fir.diagnostics.ClassDiagnosticRetriever
import org.jetbrains.kotlin.analysis.low.level.api.fir.diagnostics.FileDiagnosticRetriever
import org.jetbrains.kotlin.analysis.low.level.api.fir.diagnostics.FileStructureElementDiagnostics
import org.jetbrains.kotlin.analysis.low.level.api.fir.diagnostics.SingleNonLocalDeclarationDiagnosticRetriever
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.correspondingProperty
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirErrorConstructor
import org.jetbrains.kotlin.fir.declarations.impl.FirPrimaryConstructor
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.lazyResolveToPhase
import org.jetbrains.kotlin.psi.*

internal sealed class FileStructureElement(val firFile: FirFile, protected val moduleComponents: LLFirModuleResolveComponents) {
    abstract val psi: KtAnnotated
    abstract val mappings: KtToFirMapping
    abstract val diagnostics: FileStructureElementDiagnostics
}

internal class KtToFirMapping(firElement: FirElement, recorder: FirElementsRecorder) {
    private val mapping = FirElementsRecorder.recordElementsFrom(firElement, recorder)

    fun getElement(ktElement: KtElement): FirElement? {
        return mapping[ktElement]
    }

    fun getFirOfClosestParent(element: KtElement): FirElement? {
        var current: PsiElement? = element
        while (current != null && current !is KtFile) {
            if (current is KtElement) {
                getElement(current)?.let { return it }
            }
            current = current.parent
        }
        return null
    }
}

internal sealed class ReanalyzableStructureElement<KT : KtDeclaration, S : FirBasedSymbol<*>>(
    firFile: FirFile,
    val firSymbol: S,
    moduleComponents: LLFirModuleResolveComponents,
) : FileStructureElement(firFile, moduleComponents) {
    abstract override val psi: KtDeclaration
    abstract val timestamp: Long

    /**
     * Recreate [mappings] and [diagnostics]
     */
    abstract fun reanalyze(): ReanalyzableStructureElement<KT, S>

    internal fun inPlaceInBlockInvalidation() {
        if (firSymbol.fir.resolvePhase == FirResolvePhase.BODY_RESOLVE) {
            /**
             * In-place invalidation is allowed only inside write action in exceptional cases.
             * The right way to invalidate a declaration is [invalidateAfterInBlockModification]
             */
            ApplicationManager.getApplication().assertIsWriteThread()
            doInBlockInvalidation()
        }
    }

    protected abstract fun doInBlockInvalidation()

    fun isUpToDate(): Boolean = psi.getModificationStamp() == timestamp

    override val diagnostics = FileStructureElementDiagnostics(
        firFile,
        SingleNonLocalDeclarationDiagnosticRetriever(firSymbol.fir),
        moduleComponents,
    )

    companion object {
        val recorder = FirElementsRecorder()
    }
}

internal class ReanalyzableFunctionStructureElement(
    firFile: FirFile,
    override val psi: KtNamedFunction,
    firSymbol: FirNamedFunctionSymbol,
    moduleComponents: LLFirModuleResolveComponents,
) : ReanalyzableStructureElement<KtNamedFunction, FirNamedFunctionSymbol>(firFile, firSymbol, moduleComponents) {
    override val timestamp: Long = psi.modificationStamp

    override val mappings = KtToFirMapping(firSymbol.fir, recorder)

    override fun doInBlockInvalidation() {
        firSymbol.fir.inBodyInvalidation()
    }

    override fun reanalyze(): ReanalyzableFunctionStructureElement {
        inPlaceInBlockInvalidation()

        firSymbol.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        return ReanalyzableFunctionStructureElement(
            firFile,
            psi,
            firSymbol,
            moduleComponents,
        )
    }
}

internal class ReanalyzablePropertyStructureElement(
    firFile: FirFile,
    override val psi: KtProperty,
    firSymbol: FirPropertySymbol,
    moduleComponents: LLFirModuleResolveComponents,
) : ReanalyzableStructureElement<KtProperty, FirPropertySymbol>(firFile, firSymbol, moduleComponents) {
    override val timestamp: Long = psi.modificationStamp
    private val getterTimestamp: Long? = psi.getter?.modificationStamp
    private val setterTimestamp: Long? = psi.setter?.modificationStamp

    override val mappings = KtToFirMapping(firSymbol.fir, recorder)

    override fun doInBlockInvalidation() {
        val property = firSymbol.fir
        if (psi.getter?.modificationStamp != getterTimestamp) {
            property.getter?.inBodyInvalidation()
        }

        if (psi.setter?.modificationStamp != setterTimestamp) {
            property.setter?.inBodyInvalidation()
        }

        /**
         * We should always invalidate property initializer
         * because we can't separate an initialization modification
         * from accessor modification if they're happening at the same time.
         * Example:
         * ```kotlin
         * val i: Int = 4
         *     get() {
         *         return field.also { foo() }
         *     }
         * ```
         * If between 2 [reanalyze] we will change 4 to 5 and `foo()` to `boo()` then we can't say
         * by [timestamp] was initializer changed or not
         */
        property.inBodyInvalidation()
    }

    override fun reanalyze(): ReanalyzablePropertyStructureElement {
        inPlaceInBlockInvalidation()

        firSymbol.lazyResolveToPhase(FirResolvePhase.BODY_RESOLVE)
        return ReanalyzablePropertyStructureElement(
            firFile,
            psi,
            firSymbol,
            moduleComponents,
        )
    }
}

internal sealed class NonReanalyzableDeclarationStructureElement(
    firFile: FirFile,
    moduleComponents: LLFirModuleResolveComponents,
) : FileStructureElement(firFile, moduleComponents)

internal class NonReanalyzableClassDeclarationStructureElement(
    firFile: FirFile,
    val fir: FirRegularClass,
    override val psi: KtClassOrObject,
    moduleComponents: LLFirModuleResolveComponents,
) : NonReanalyzableDeclarationStructureElement(firFile, moduleComponents) {

    override val mappings = KtToFirMapping(fir, Recorder())

    override val diagnostics = FileStructureElementDiagnostics(
        firFile,
        ClassDiagnosticRetriever(fir),
        moduleComponents,
    )

    private inner class Recorder : FirElementsRecorder() {
        override fun visitProperty(property: FirProperty, data: MutableMap<KtElement, FirElement>) {
            if (property.source?.kind == KtFakeSourceElementKind.PropertyFromParameter) {
                super.visitProperty(property, data)
            }
        }

        override fun visitSimpleFunction(simpleFunction: FirSimpleFunction, data: MutableMap<KtElement, FirElement>) {
        }

        override fun visitConstructor(constructor: FirConstructor, data: MutableMap<KtElement, FirElement>) {
            if (
                (constructor is FirPrimaryConstructor || constructor is FirErrorConstructor) &&
                constructor.source?.kind == KtFakeSourceElementKind.ImplicitConstructor
            ) {
                NonReanalyzableNonClassDeclarationStructureElement.Recorder.visitConstructor(constructor, data)
            }
        }

        override fun visitAnonymousInitializer(anonymousInitializer: FirAnonymousInitializer, data: MutableMap<KtElement, FirElement>) {
        }

        override fun visitRegularClass(regularClass: FirRegularClass, data: MutableMap<KtElement, FirElement>) {
            if (regularClass != fir) return
            super.visitRegularClass(regularClass, data)
        }

        override fun visitTypeAlias(typeAlias: FirTypeAlias, data: MutableMap<KtElement, FirElement>) {
        }
    }
}

internal class NonReanalyzableNonClassDeclarationStructureElement(
    firFile: FirFile,
    val fir: FirDeclaration,
    override val psi: KtDeclaration,
    moduleComponents: LLFirModuleResolveComponents,
) : NonReanalyzableDeclarationStructureElement(firFile, moduleComponents) {

    override val mappings = KtToFirMapping(fir, Recorder)

    override val diagnostics = FileStructureElementDiagnostics(
        firFile,
        SingleNonLocalDeclarationDiagnosticRetriever(fir),
        moduleComponents,
    )

    internal object Recorder : FirElementsRecorder() {
        override fun visitConstructor(constructor: FirConstructor, data: MutableMap<KtElement, FirElement>) {
            if (constructor is FirPrimaryConstructor) {
                constructor.valueParameters.forEach { parameter ->
                    parameter.correspondingProperty?.let { property ->
                        visitProperty(property, data)
                    }
                }
            }

            super.visitConstructor(constructor, data)
        }
    }
}

internal class DanglingTopLevelModifierListStructureElement(
    firFile: FirFile,
    val fir: FirDeclaration,
    moduleComponents: LLFirModuleResolveComponents,
    override val psi: KtAnnotated,
) :
    FileStructureElement(firFile, moduleComponents) {
    override val mappings = KtToFirMapping(fir, FirElementsRecorder())

    override val diagnostics = FileStructureElementDiagnostics(firFile, SingleNonLocalDeclarationDiagnosticRetriever(fir), moduleComponents)
}

internal class RootStructureElement(
    firFile: FirFile,
    override val psi: KtFile,
    moduleComponents: LLFirModuleResolveComponents,
) : FileStructureElement(firFile, moduleComponents) {
    override val mappings = KtToFirMapping(firFile, recorder)

    override val diagnostics =
        FileStructureElementDiagnostics(firFile, FileDiagnosticRetriever, moduleComponents)

    companion object {
        private val recorder = object : FirElementsRecorder() {
            override fun visitElement(element: FirElement, data: MutableMap<KtElement, FirElement>) {
                if (element !is FirDeclaration || element is FirFile) {
                    super.visitElement(element, data)
                }
            }
        }
    }
}
