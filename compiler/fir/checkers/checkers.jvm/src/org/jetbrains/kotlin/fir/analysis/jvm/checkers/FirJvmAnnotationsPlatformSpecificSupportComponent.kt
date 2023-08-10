/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.jvm.checkers

import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.getAnnotationRetention
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.AnnotationsPosition
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.declarations.FirAnnotationsPlatformSpecificSupportComponent
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.name.JvmNames
import org.jetbrains.kotlin.name.StandardClassIds

object FirJvmAnnotationsPlatformSpecificSupportComponent : FirAnnotationsPlatformSpecificSupportComponent() {
    override val requiredAnnotationsWithArguments = setOf(
        StandardClassIds.Annotations.Deprecated,
        StandardClassIds.Annotations.Target,
        JvmNames.Annotations.Target,
    )

    override val requiredAnnotations = requiredAnnotationsWithArguments + setOf(
        JvmNames.Annotations.Deprecated,
        StandardClassIds.Annotations.DeprecatedSinceKotlin,
        StandardClassIds.Annotations.SinceKotlin,
        StandardClassIds.Annotations.WasExperimental,
        JvmNames.Annotations.JvmRecord,
    )

    override val volatileAnnotations = setOf(
        StandardClassIds.Annotations.Volatile,
        JvmNames.Annotations.JvmVolatile,
    )

    override val deprecationAnnotationsWithOverridesPropagation = mapOf(
        StandardClassIds.Annotations.Deprecated to true,
        JvmNames.Annotations.Deprecated to false,
        StandardClassIds.Annotations.SinceKotlin to true,
    )

    override fun symbolContainsRepeatableAnnotation(symbol: FirClassLikeSymbol<*>, session: FirSession): Boolean {
        if (symbol.getAnnotationByClassId(StandardClassIds.Annotations.Repeatable, session) != null) return true
        if (symbol.getAnnotationByClassId(JvmNames.Annotations.Repeatable, session) != null ||
            symbol.getAnnotationByClassId(JvmNames.Annotations.JvmRepeatable, session) != null
        ) {
            return session.languageVersionSettings.supportsFeature(LanguageFeature.RepeatableAnnotations) ||
                    symbol.getAnnotationRetention(session) == AnnotationRetention.SOURCE && symbol.origin is FirDeclarationOrigin.Java
        }
        return false
    }

    override fun extractBackingFieldAnnotationsFromProperty(
        property: FirProperty,
        session: FirSession,
        propertyAnnotations: List<FirAnnotation>,
        backingFieldAnnotations: List<FirAnnotation>,
    ): AnnotationsPosition? {
        if (propertyAnnotations.isEmpty() || property.backingField == null) return null

        val (newBackingFieldAnnotations, newPropertyAnnotations) = propertyAnnotations.partition {
            it.toAnnotationClassIdSafe(session) == JvmNames.Annotations.Deprecated
        }

        if (newBackingFieldAnnotations.isEmpty()) return null
        return AnnotationsPosition(
            propertyAnnotations = newPropertyAnnotations,
            backingFieldAnnotations = backingFieldAnnotations + newBackingFieldAnnotations,
        )
    }
}
