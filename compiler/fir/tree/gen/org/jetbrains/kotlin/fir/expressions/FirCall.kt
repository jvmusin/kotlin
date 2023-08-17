/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.expressions

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirElementInterface
import org.jetbrains.kotlin.fir.visitors.*
import org.jetbrains.kotlin.fir.FirElement

/*
 * This file was generated automatically
 * DO NOT MODIFY IT MANUALLY
 */

sealed interface FirCall : FirStatement {
    override val source: KtSourceElement?
    override val annotations: List<FirAnnotation>
    val argumentList: FirArgumentList


    override fun replaceAnnotations(newAnnotations: List<FirAnnotation>)

    fun replaceArgumentList(newArgumentList: FirArgumentList)

    override fun <D> transformAnnotations(transformer: FirTransformer<D>, data: D): FirCall
}
