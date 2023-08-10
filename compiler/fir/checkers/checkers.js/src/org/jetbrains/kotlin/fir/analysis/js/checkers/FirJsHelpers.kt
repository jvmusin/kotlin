/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:OptIn(SymbolInternals::class)

package org.jetbrains.kotlin.fir.analysis.js.checkers

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.*
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.*
import org.jetbrains.kotlin.fir.declarations.utils.isActual
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isExternal
import org.jetbrains.kotlin.fir.isSubstitutionOrIntersectionOverride
import org.jetbrains.kotlin.fir.resolve.providers.firProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.isAny
import org.jetbrains.kotlin.fir.types.isNullableAny
import org.jetbrains.kotlin.fir.types.toSymbol
import org.jetbrains.kotlin.js.PredefinedAnnotation
import org.jetbrains.kotlin.js.common.isES5IdentifierPart
import org.jetbrains.kotlin.js.common.isES5IdentifierStart
import org.jetbrains.kotlin.name.JsStandardClassIds

private val FirBasedSymbol<*>.isExternal
    get() = when (this) {
        is FirCallableSymbol<*> -> isExternal
        is FirClassSymbol<*> -> isExternal
        else -> false
    }

fun FirBasedSymbol<*>.isEffectivelyExternal(session: FirSession): Boolean {
    if (fir is FirMemberDeclaration && isExternal) return true

    if (this is FirPropertyAccessorSymbol) {
        val property = propertySymbol
        if (property.isEffectivelyExternal(session)) return true
    }

    if (this is FirPropertySymbol) {
        if (getterSymbol?.isExternal == true && (!isVar || setterSymbol?.isExternal == true)) {
            return true
        }
    }

    return getContainingClassSymbol(session)?.isEffectivelyExternal(session) == true
}

fun FirBasedSymbol<*>.isEffectivelyExternalMember(session: FirSession): Boolean {
    return fir is FirMemberDeclaration && isEffectivelyExternal(session)
}

fun FirBasedSymbol<*>.isEffectivelyExternal(context: CheckerContext) = isEffectivelyExternal(context.session)

fun FirFunctionSymbol<*>.isOverridingExternalWithOptionalParams(context: CheckerContext): Boolean {
    if (!isSubstitutionOrIntersectionOverride && modality == Modality.ABSTRACT) return false

    val overridden = (this as? FirNamedFunctionSymbol)?.directOverriddenFunctions(context) ?: return false

    for (overriddenFunction in overridden.filter { it.isEffectivelyExternal(context) }) {
        if (overriddenFunction.valueParameterSymbols.any { it.hasDefaultValue }) return true
    }

    return false
}

fun FirBasedSymbol<*>.getJsName(session: FirSession): String? {
    return getAnnotationStringParameter(JsStandardClassIds.Annotations.JsName, session)
}

fun sanitizeName(name: String): String {
    if (name.isEmpty()) return "_"

    val first = name.first().let { if (it.isES5IdentifierStart()) it else '_' }
    return first.toString() + name.drop(1).map { if (it.isES5IdentifierPart()) it else '_' }.joinToString("")
}

fun FirBasedSymbol<*>.isNativeObject(session: FirSession): Boolean {
    if (hasAnnotationOrInsideAnnotatedClass(JsStandardClassIds.Annotations.JsNative, session) || isEffectivelyExternal(session)) {
        return true
    }

    if (this is FirPropertyAccessorSymbol) {
        val property = propertySymbol
        return property.hasAnnotationOrInsideAnnotatedClass(JsStandardClassIds.Annotations.JsNative, session)
    }

    return false
}

fun FirBasedSymbol<*>.isNativeInterface(session: FirSession): Boolean {
    return isNativeObject(session) && (fir as? FirClass)?.isInterface == true
}

fun FirBasedSymbol<*>.isLibraryObject(session: FirSession): Boolean {
    return hasAnnotationOrInsideAnnotatedClass(JsStandardClassIds.Annotations.JsLibrary, session)
}

private val FirBasedSymbol<*>.isExpect
    get() = when (this) {
        is FirCallableSymbol<*> -> isExpect
        is FirClassSymbol<*> -> isExpect
        else -> false
    }

private val FirBasedSymbol<*>.isActual
    get() = when (this) {
        is FirCallableSymbol<*> -> isActual
        is FirClassSymbol<*> -> isActual
        else -> false
    }

fun FirBasedSymbol<*>.isPredefinedObject(session: FirSession): Boolean {
    if (fir is FirMemberDeclaration && isExpect) return true
    if (isEffectivelyExternalMember(session)) return true

    for (annotation in PredefinedAnnotation.values()) {
        if (hasAnnotationOrInsideAnnotatedClass(annotation.classId, session)) {
            return true
        }
    }

    return false
}

fun FirBasedSymbol<*>.isExportedObject(session: FirSession): Boolean {
    val declaration = fir

    if (declaration is FirMemberDeclaration) {
        val visibility = declaration.visibility
        if (visibility != Visibilities.Public && visibility != Visibilities.Protected) {
            return false
        }
    }

    return when {
        hasAnnotationOrInsideAnnotatedClass(JsStandardClassIds.Annotations.JsExportIgnore, session) -> false
        hasAnnotationOrInsideAnnotatedClass(JsStandardClassIds.Annotations.JsExport, session) -> true
        else -> getContainingFile(session)?.hasAnnotation(JsStandardClassIds.Annotations.JsExport, session) == true
    }
}

internal fun FirBasedSymbol<*>.getContainingFile(session: FirSession): FirFile? {
    return when (this) {
        is FirCallableSymbol<*> -> session.firProvider.getFirCallableContainerFile(this)
        is FirClassLikeSymbol<*> -> session.firProvider.getFirClassifierContainerFileIfAny(this)
        else -> return null
    }
}

fun FirBasedSymbol<*>.isNativeObject(context: CheckerContext) = isNativeObject(context.session)

fun FirBasedSymbol<*>.isNativeInterface(context: CheckerContext) = isNativeInterface(context.session)

fun FirBasedSymbol<*>.isPredefinedObject(context: CheckerContext) = isPredefinedObject(context.session)

fun FirBasedSymbol<*>.isExportedObject(context: CheckerContext) = isExportedObject(context.session)

fun FirBasedSymbol<*>.isLibraryObject(context: CheckerContext) = isLibraryObject(context.session)

fun FirBasedSymbol<*>.isPresentInGeneratedCode(context: CheckerContext) = !isNativeObject(context) && !isLibraryObject(context)

internal fun FirClass.superClassNotAny(session: FirSession) = superConeTypes
    .filterNot { it.isAny || it.isNullableAny }
    .find { it.toSymbol(session)?.classKind == ClassKind.CLASS }

internal fun getRootClassLikeSymbolOrSelf(symbol: FirBasedSymbol<*>, session: FirSession): FirBasedSymbol<*> {
    return symbol.getContainingClassSymbol(session)?.let { getRootClassLikeSymbolOrSelf(it, session) } ?: symbol
}

internal data class StableJavaScriptSymbolName(val name: String, val canBeMangled: Boolean, val symbol: FirBasedSymbol<*>)

internal fun FirBasedSymbol<*>.getStableNameInJavaScript(session: FirSession): StableJavaScriptSymbolName? {
    val jsName = getJsName(session)
    if (jsName != null) {
        return StableJavaScriptSymbolName(jsName, false, this)
    }

    if (this is FirPropertyAccessorSymbol) {
        return null
    }

    val hasStableNameInJavaScript = when {
        isEffectivelyExternal(session) -> true
        isExportedObject(session) -> true
        else -> false
    }

    // TODO: rethink in KT-60554
    val hasPublicName = when (this) {
        is FirClassLikeSymbol -> !isLocal
        is FirCallableSymbol -> {
            val parentClass = getContainingClassSymbol(session)
            if (parentClass != null) {
                when (visibility) {
                    is Visibilities.Public -> true
                    is Visibilities.Protected -> !parentClass.isFinal && parentClass.visibility.isPublicAPI
                    else -> false
                }
            } else {
                !callableId.isLocal && effectiveVisibility.publicApi
            }
        }
        else -> false
    }

    if (hasPublicName || hasStableNameInJavaScript) {
        val name = (fir as? FirMemberDeclaration)?.nameOrSpecialName?.identifierOrNullIfSpecial
        if (name != null) {
            return StableJavaScriptSymbolName(name, !hasStableNameInJavaScript, this)
        }
    }
    return null
}

private fun FirBasedSymbol<*>.doesJavaScriptManglingChangeName(): Boolean {
    return when (this) {
        is FirFunctionSymbol<*> -> valueParameterSymbols.isNotEmpty() || isExtension
        is FirPropertySymbol -> isExtension
        is FirClassLikeSymbol<*> -> classKind != ClassKind.OBJECT
        else -> false
    }
}

private fun shouldClashBeCaughtByCommonFrontendCheck(lhs: FirBasedSymbol<*>, rhs: FirBasedSymbol<*>): Boolean {
    return when (lhs) {
        is FirFunctionSymbol<*> -> rhs is FirFunctionSymbol<*>
        is FirPropertySymbol -> rhs is FirPropertySymbol || (rhs as? FirClassLikeSymbol<*>)?.classKind == ClassKind.OBJECT
        is FirClassLikeSymbol<*> -> rhs is FirPropertySymbol && lhs.classKind == ClassKind.OBJECT
        else -> false
    }
}

internal fun StableJavaScriptSymbolName.isClashedWith(other: StableJavaScriptSymbolName, context: CheckerContext): Boolean {
    return when {
        name != other.name -> false
        symbol === other.symbol -> false
        symbol.isActual != other.symbol.isActual -> false
        symbol.isExpect != other.symbol.isExpect -> false
        !symbol.isPresentInGeneratedCode(context) && !other.symbol.isPresentInGeneratedCode(context) -> false
        canBeMangled && symbol.doesJavaScriptManglingChangeName() -> false
        other.canBeMangled && other.symbol.doesJavaScriptManglingChangeName() -> false
        canBeMangled && other.canBeMangled && shouldClashBeCaughtByCommonFrontendCheck(symbol, other.symbol) -> false
        else -> true
    }
}
