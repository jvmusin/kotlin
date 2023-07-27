/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.generators.builtins.numbers.primitives

import org.jetbrains.kotlin.generators.builtins.PrimitiveType
import java.io.PrintWriter

class JsPrimitivesGenerator(writer: PrintWriter) : BasePrimitivesGenerator(writer) {
    override fun FileBuilder.modifyGeneratedFile() {
        suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY")
        suppress("UNUSED_PARAMETER")
        suppress("NOTHING_TO_INLINE")
    }

    override fun PropertyBuilder.modifyGeneratedCompanionObjectProperty(thisKind: PrimitiveType) {
        if (this.name in setOf("POSITIVE_INFINITY", "NEGATIVE_INFINITY", "NaN")) {
            annotations += "Suppress(\"DIVISION_BY_ZERO\")"
        }
    }

    override fun ClassBuilder.modifyGeneratedClass(thisKind: PrimitiveType) {
        if (thisKind != PrimitiveType.LONG) return
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
        if (thisKind != PrimitiveType.LONG) return
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
        if (thisKind != PrimitiveType.LONG) return
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
            else -> "this${thisKind.castToIfNecessary(otherKind)}.${methodName}(other${otherKind.castToIfNecessary(thisKind)})"
        }
        body.addAsSingleLineBody()
    }

    override fun MethodBuilder.modifyGeneratedUnaryOperation(thisKind: PrimitiveType) {
        if (thisKind != PrimitiveType.LONG) return
        modifySignature { isInline = methodName == "unaryPlus" }
        val body = when (methodName) {
            "inc" -> "this + 1L"
            "dec" -> "this - 1L"
            "unaryMinus" -> "this.inv() + 1L"
            "unaryPlus" -> "this"
            else -> error(methodName)
        }
        body.addAsSingleLineBody(bodyOnNewLine = false)
    }

    override fun MethodBuilder.modifyGeneratedRangeTo(thisKind: PrimitiveType, otherKind: PrimitiveType, opReturnType: PrimitiveType) {
        noBody()
        if (thisKind != PrimitiveType.LONG) return

        val body = if (thisKind == otherKind) "LongRange(this, other)" else "rangeTo(other.toLong())"
        body.addAsSingleLineBody(bodyOnNewLine = true)
    }

    override fun MethodBuilder.modifyGeneratedBitShiftOperators(thisKind: PrimitiveType) {
        if (thisKind != PrimitiveType.LONG) return
        when (methodName) {
            "shl" -> "shiftLeft($parameterName)".addAsSingleLineBody(bodyOnNewLine = false)
            "shr" -> "shiftRight($parameterName)".addAsSingleLineBody(bodyOnNewLine = false)
            "ushr" -> "shiftRightUnsigned($parameterName)".addAsSingleLineBody(bodyOnNewLine = false)
        }
    }

    override fun MethodBuilder.modifyGeneratedBitwiseOperators(thisKind: PrimitiveType) {
        if (thisKind != PrimitiveType.LONG) return
        val body = when (methodName) {
            "inv" -> "Long(low.inv(), high.inv())"
            else -> "Long(low $methodName other.low, high $methodName other.high)"
        }
        body.addAsSingleLineBody(bodyOnNewLine = false)
    }

    override fun MethodBuilder.modifyGeneratedConversions(thisKind: PrimitiveType) {
        if (thisKind != PrimitiveType.LONG) return
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
        if (thisKind != PrimitiveType.LONG) return
        modifySignature { visibility = null }
        "other is Long && equalsLong(other)".addAsSingleLineBody(bodyOnNewLine = false)
    }

    override fun MethodBuilder.modifyGeneratedToString(thisKind: PrimitiveType) {
        if (thisKind != PrimitiveType.LONG) return
        modifySignature { visibility = null }
        "this.toStringImpl(radix = 10)".addAsSingleLineBody(bodyOnNewLine = false)
    }

    override fun ClassBuilder.generateAdditionalMethods(thisKind: PrimitiveType) {
        method {
            signature {
                visibility = if (thisKind != PrimitiveType.LONG) MethodVisibility.PUBLIC else null
                isOverride = true
                methodName = "hashCode"
                returnType = PrimitiveType.INT.capitalized
            }

            if (thisKind == PrimitiveType.LONG) {
                "hashCode(this)".addAsSingleLineBody(bodyOnNewLine = false)
            }
        }

        if (thisKind != PrimitiveType.LONG) return
        method {
            additionalDoc = """
                This method is used by JavaScript to convert objects of type Long to primitives.
                This is essential for the JavaScript interop.
                JavaScript functions that expect `number` are imported in Kotlin as expecting `kotlin.Number`
                (in our standard library, and also in user projects if they use Dukat for generating external declarations).
                Because `kotlin.Number` is a supertype of `Long` too, there has to be a way for JS to know how to handle Longs.
                See KT-50202
            """.trimIndent()
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
