/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.jvm.checkers.expression

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirQualifiedAccessExpressionChecker
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors
import org.jetbrains.kotlin.fir.declarations.utils.sourceElement
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.toRegularClassSymbol
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource

object FirIncompatibleClassExpressionChecker : FirQualifiedAccessExpressionChecker() {
    override fun check(expression: FirQualifiedAccessExpression, context: CheckerContext, reporter: DiagnosticReporter) {
        val symbol = expression.calleeReference.toResolvedCallableSymbol() ?: return

        checkType(symbol.resolvedReturnType, expression, context, reporter)
        // checkType(symbol.receiverParameter?.typeRef?.coneType, expression, context, reporter) // TODO: ensure there's a test
        // TODO: value parameters
    }

    internal fun checkType(type: ConeKotlinType?, element: FirElement, context: CheckerContext, reporter: DiagnosticReporter) {
        val classSymbol = type?.toRegularClassSymbol(context.session)
        if (classSymbol != null) {
            checkClass(classSymbol, element, context, reporter)
        }
    }

    private fun checkClass(klass: FirClassSymbol<*>, element: FirElement, context: CheckerContext, reporter: DiagnosticReporter) {
        val source = klass.sourceElement
        if (source is DeserializedContainerSource) {
            val incompatibility = source.incompatibility
            if (incompatibility != null) {
                reporter.reportOn(element.source, FirJvmErrors.INCOMPATIBLE_CLASS, source.presentableString, incompatibility, context)
            }
        }
    }
}
