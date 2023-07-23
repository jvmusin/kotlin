/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.generators.builtins.numbers.primitives

import org.jetbrains.kotlin.generators.builtins.PrimitiveType
import java.io.PrintWriter

class JsPrimitivesGenerator(writer: PrintWriter) : BasePrimitivesGenerator(writer) {
    override fun PrimitiveType.shouldGenerate(): Boolean {
        return this != PrimitiveType.LONG
    }

    override fun FileBuilder.modifyGeneratedFile() {
        suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY")
        suppress("UNUSED_PARAMETER")
    }

    override fun PropertyBuilder.modifyGeneratedCompanionObjectProperty(thisKind: PrimitiveType) {
        if (this.name in setOf("POSITIVE_INFINITY", "NEGATIVE_INFINITY", "NaN")) {
            annotations += "Suppress(\"DIVISION_BY_ZERO\")"
        }
    }

    override fun MethodBuilder.modifyGeneratedRangeTo(thisKind: PrimitiveType, otherKind: PrimitiveType, opReturnType: PrimitiveType) {
        noBody()
    }

    override fun ClassBuilder.generateAdditionalMethods(thisKind: PrimitiveType) {
        method {
            signature {
                isOverride = true
                methodName = "hashCode"
                returnType = PrimitiveType.INT.capitalized
            }
        }
    }
}

class JsLongGenerator(writer: PrintWriter) : BasePrimitivesGenerator(writer) {
    override fun PrimitiveType.shouldGenerate(): Boolean {
        return this == PrimitiveType.LONG
    }

    override fun FileBuilder.modifyGeneratedFile() {
        suppress("NOTHING_TO_INLINE")
    }

    override fun ClassBuilder.modifyGeneratedClass(thisKind: PrimitiveType) {
        primaryConstructor {
            visibility = MethodVisibility.INTERNAL
            parameter {
                name = "internal val low"
                type = PrimitiveType.INT.capitalized
            }
            parameter {
                name = "internal val high"
                type = PrimitiveType.INT.capitalized
            }
        }
    }

    override fun MethodBuilder.modifyGeneratedCompareTo(thisKind: PrimitiveType, otherKind: PrimitiveType) {
        modifySignature { isInline = otherKind != PrimitiveType.LONG }
        val body = when (otherKind) {
            PrimitiveType.LONG -> "compare(other)"
            PrimitiveType.FLOAT -> "toFloat().compareTo(other)"
            PrimitiveType.DOUBLE -> "toDouble().compareTo(other)"
            else -> "compareTo(other.toLong())"
        }
        body.addAsSingleLineBody()
    }

    override fun MethodBuilder.modifyGeneratedBinaryOperation(thisKind: PrimitiveType, otherKind: PrimitiveType) {
        modifySignature { isInline = otherKind != PrimitiveType.LONG }
        val body = when (otherKind) {
            PrimitiveType.LONG -> {
                val specialNameForLong = when (methodName) {
                    "plus" -> "add"
                    "minus" -> "subtract"
                    "times" -> "multiply"
                    "div" -> "divide"
                    "rem" -> "modulo"
                    else -> throw IllegalArgumentException("Unsupported binary operation: $methodName")
                }
                "$specialNameForLong(other)"
            }
            PrimitiveType.FLOAT -> "toFloat().${this.methodName}(other)"
            PrimitiveType.DOUBLE -> "toDouble().${this.methodName}(other)"
            else -> "${this.methodName}(other.toLong())"
        }
        body.addAsSingleLineBody()
    }

    override fun MethodBuilder.modifyGeneratedUnaryOperation(thisKind: PrimitiveType) {
        when (methodName) {
            "inc", "dec" -> {
                val sign = if (methodName == "dec") "-" else "+"
                "this $sign 1L".addAsSingleLineBody(bodyOnNewLine = false)
            }
            "unaryPlus" -> {
                modifySignature { isInline = true }
                "this".addAsSingleLineBody(bodyOnNewLine = false)
            }
            "unaryMinus" -> "inv() + 1L".addAsSingleLineBody(bodyOnNewLine = false)
        }
    }

    override fun MethodBuilder.modifyGeneratedRangeTo(thisKind: PrimitiveType) {
        val body = if (parameterType == thisKind.capitalized) {
            "LongRange(this, other)"
        } else {
            "rangeTo(other.toLong())"
        }
        body.addAsSingleLineBody(bodyOnNewLine = false)
    }

    override fun MethodBuilder.modifyGeneratedRangeUntil(thisKind: PrimitiveType) {
        "this until other".addAsSingleLineBody(bodyOnNewLine = false)
    }

    override fun MethodBuilder.modifyGeneratedBitShiftOperators(thisKind: PrimitiveType) {
        when (methodName) {
            "shl" -> "shiftLeft($parameterName)".addAsSingleLineBody(bodyOnNewLine = false)
            "shr" -> "shiftRight($parameterName)".addAsSingleLineBody(bodyOnNewLine = false)
            "ushr" -> "shiftRightUnsigned($parameterName)".addAsSingleLineBody(bodyOnNewLine = false)
        }
    }

    override fun MethodBuilder.modifyGeneratedBitwiseOperators(thisKind: PrimitiveType) {
        val body = when (methodName) {
            "inv" -> "Long(low.inv(), high.inv())"
            else -> "Long(low $methodName other.low, high $methodName other.high)"
        }
        body.addAsSingleLineBody(bodyOnNewLine = false)
    }

    override fun MethodBuilder.modifyGeneratedConversions(thisKind: PrimitiveType) {
        val body = when (val returnTypeAsPrimitive = PrimitiveType.valueOf(returnType.uppercase())) {
            PrimitiveType.BYTE -> "low.toByte()"
            PrimitiveType.CHAR -> "low.toChar()"
            PrimitiveType.SHORT -> "low.toShort()"
            PrimitiveType.INT -> "low"
            PrimitiveType.LONG -> "this"
            PrimitiveType.FLOAT -> "toDouble().toFloat()"
            PrimitiveType.DOUBLE -> "toNumber()"
            else -> throw IllegalArgumentException("Unsupported type $returnTypeAsPrimitive for Long conversion")
        }
        body.addAsSingleLineBody(bodyOnNewLine = false)
    }

    override fun MethodBuilder.modifyGeneratedEquals(thisKind: PrimitiveType) {
        modifySignature { visibility = null }
        "other is Long && equalsLong(other)".addAsSingleLineBody(bodyOnNewLine = false)
    }

    override fun MethodBuilder.modifyGeneratedToString(thisKind: PrimitiveType) {
        modifySignature { visibility = null }
        "this.toStringImpl(radix = 10)".addAsSingleLineBody(bodyOnNewLine = false)
    }

    override fun ClassBuilder.generateAdditionalMethods(thisKind: PrimitiveType) {
        method {
            signature {
                visibility = null
                isOverride = true
                methodName = "hashCode"
                returnType = PrimitiveType.INT.capitalized
            }

            "hashCode(this)".addAsSingleLineBody(bodyOnNewLine = false)
        }

        method {
            val doc = """
                This method is used by JavaScript to convert objects of type Long to primitives.
                This is essential for the JavaScript interop.
                JavaScript functions that expect `number` are imported in Kotlin as expecting `kotlin.Number`
                (in our standard library, and also in user projects if they use Dukat for generating external declarations).
                Because `kotlin.Number` is a supertype of `Long` too, there has to be a way for JS to know how to handle Longs.
                See KT-50202
            """.trimIndent()
            appendDoc(doc)
            annotations += "JsName(\"valueOf\")"
            signature {
                visibility = MethodVisibility.INTERNAL
                methodName = "valueOf"
                returnType = PrimitiveType.DOUBLE.capitalized
            }
            "toDouble()".addAsSingleLineBody(bodyOnNewLine = false)
        }
    }
}
