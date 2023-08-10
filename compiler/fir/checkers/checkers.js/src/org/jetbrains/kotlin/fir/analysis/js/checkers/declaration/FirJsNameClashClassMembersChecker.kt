/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.js.checkers.declaration

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.unsubstitutedScope
import org.jetbrains.kotlin.fir.analysis.diagnostics.js.FirJsErrors
import org.jetbrains.kotlin.fir.analysis.js.checkers.*
import org.jetbrains.kotlin.fir.analysis.js.checkers.StableJavaScriptSymbolName
import org.jetbrains.kotlin.fir.analysis.js.checkers.getStableNameInJavaScript
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirClassLikeDeclaration
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.scopes.*
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.popLast

object FirJsNameClashClassMembersChecker : FirClassChecker() {
    private fun FirTypeScope.collectOverriddenLeaves(classMember: FirCallableDeclaration): Set<FirCallableSymbol<*>> {
        val processedSymbols = hashSetOf(classMember.symbol)
        val toProcessSymbols = mutableListOf(classMember.symbol)
        val leaves = mutableSetOf<FirCallableSymbol<*>>()
        while (toProcessSymbols.isNotEmpty()) {
            val processingSymbol = toProcessSymbols.popLast()
            val overriddenMembers = getDirectOverriddenMembers(processingSymbol, true)
            for (overriddenMember in overriddenMembers) {
                if (processedSymbols.add(overriddenMember)) {
                    toProcessSymbols.add(overriddenMember)
                }
            }
            if (overriddenMembers.isEmpty()) {
                leaves.add(processingSymbol)
            }
        }
        return leaves
    }

    private fun MutableSet<StableJavaScriptSymbolName>.addStableJavaScriptName(
        targetSymbol: FirCallableSymbol<*>?,
        overriddenSymbol: FirCallableSymbol<*>?,
        context: CheckerContext,
    ) {
        if (targetSymbol != null && (targetSymbol as? FirConstructorSymbol)?.isPrimary != true) {
            val stableName = overriddenSymbol?.getStableNameInJavaScript(context.session) ?: return
            add(stableName.copy(symbol = targetSymbol))
        }
    }

    private fun MutableSet<StableJavaScriptSymbolName>.addAllStableJavaScriptNames(
        targetSymbol: FirCallableSymbol<*>,
        overriddenSymbol: FirCallableSymbol<*>,
        context: CheckerContext,
    ) {
        addStableJavaScriptName(targetSymbol, overriddenSymbol, context)
        if (targetSymbol is FirPropertySymbol && overriddenSymbol is FirPropertySymbol) {
            addStableJavaScriptName(targetSymbol.getterSymbol, overriddenSymbol.getterSymbol, context)
            addStableJavaScriptName(targetSymbol.setterSymbol, overriddenSymbol.setterSymbol, context)
        }
    }

    private fun FirClass.collectStableJavaScriptNamesForMembers(context: CheckerContext): Set<StableJavaScriptSymbolName> {
        val scope = symbol.unsubstitutedScope(context)

        val allCallableMembers = hashSetOf<FirCallableSymbol<*>>()
        allCallableMembers.addAll(scope.collectAllFunctions())
        allCallableMembers.addAll(scope.collectAllProperties())

        return buildSet {
            for (classMember in declarations) {
                when (classMember) {
                    is FirClassLikeDeclaration -> {
                        addIfNotNull(classMember.symbol.getStableNameInJavaScript(context.session))
                    }
                    is FirCallableDeclaration -> {
                        when {
                            !allCallableMembers.remove(classMember.symbol) -> continue
                            !classMember.symbol.isPresentInGeneratedCode(context) -> continue
                        }
                        val overriddenLeaves = scope.collectOverriddenLeaves(classMember)
                        for (symbol in overriddenLeaves) {
                            addAllStableJavaScriptNames(classMember.symbol, symbol, context)
                        }
                    }
                    else -> {}
                }
            }
            for (inheritedMemberSymbol in allCallableMembers) {
                if (!inheritedMemberSymbol.isAbstract) {
                    addAllStableJavaScriptNames(inheritedMemberSymbol, inheritedMemberSymbol, context)
                }
            }
        }
    }

    override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
        val memberStableNames = declaration.collectStableJavaScriptNamesForMembers(context)
        val membersGroupedByName = memberStableNames.groupBy { it.name }

        for ((name, symbols) in membersGroupedByName.entries) {
            for (symbol in symbols) {
                val clashedSymbols = symbols.mapNotNull { other ->
                    other.takeIf { symbol.isClashedWith(it, context) }?.symbol
                }
                if (clashedSymbols.isNotEmpty()) {
                    val source = symbol.symbol.source ?: declaration.source
                    reporter.reportOn(source, FirJsErrors.JS_NAME_CLASH, name, clashedSymbols, context)
                }
            }
        }
    }
}
