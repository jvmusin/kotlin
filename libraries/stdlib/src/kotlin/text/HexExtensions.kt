/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.text

private const val LOWER_CASE_HEX_DIGITS = "0123456789abcdef"
private const val UPPER_CASE_HEX_DIGITS = "0123456789ABCDEF"

// case-insensitive parsing
private val HEX_DIGITS_TO_DECIMAL = IntArray(256) { -1 }.apply {
    LOWER_CASE_HEX_DIGITS.forEachIndexed { index, char -> this[char.code] = index }
    UPPER_CASE_HEX_DIGITS.forEachIndexed { index, char -> this[char.code] = index }
}

// -------------------------- format and parse ByteArray --------------------------

/**
 * Formats bytes in this array using the specified [format].
 *
 * Note that only [HexFormat.upperCase] and [HexFormat.BytesHexFormat] affect formatting.
 *
 * @param format the [HexFormat] to use for formatting, [HexFormat.Default] by default.
 *
 * @throws IllegalArgumentException if the result length is more than [String] maximum capacity.
 */
@ExperimentalStdlibApi
@SinceKotlin("1.9")
public fun ByteArray.toHexString(format: HexFormat = HexFormat.Default): String = toHexString(0, size, format)

/**
 * Formats bytes in this array using the specified [HexFormat].
 *
 * Note that only [HexFormat.upperCase] and [HexFormat.BytesHexFormat] affect formatting.
 *
 * @param startIndex the beginning (inclusive) of the subrange to format, 0 by default.
 * @param endIndex the end (exclusive) of the subrange to format, size of this array by default.
 * @param format the [HexFormat] to use for formatting, [HexFormat.Default] by default.
 *
 * @throws IndexOutOfBoundsException when [startIndex] or [endIndex] is out of range of this array indices.
 * @throws IllegalArgumentException when `startIndex > endIndex`.
 * @throws IllegalArgumentException if the result length is more than [String] maximum capacity.
 */
@ExperimentalStdlibApi
@SinceKotlin("1.9")
public fun ByteArray.toHexString(
    startIndex: Int = 0,
    endIndex: Int = size,
    format: HexFormat = HexFormat.Default
): String {
    AbstractList.checkBoundsIndexes(startIndex, endIndex, size)

    if (startIndex == endIndex) {
        return ""
    }

    val digits = if (format.upperCase) UPPER_CASE_HEX_DIGITS else LOWER_CASE_HEX_DIGITS

    val bytesFormat = format.bytes
    val bytesPerLine = bytesFormat.bytesPerLine
    val bytesPerGroup = bytesFormat.bytesPerGroup
    val bytePrefix = bytesFormat.bytePrefix
    val byteSuffix = bytesFormat.byteSuffix
    val byteSeparator = bytesFormat.byteSeparator
    val groupSeparator = bytesFormat.groupSeparator

    // Optimize for formats with unspecified bytesPerLine and bytesPerGroup
    if (bytesPerLine == Int.MAX_VALUE && bytesPerGroup == Int.MAX_VALUE) {
        return toHexString(startIndex, endIndex, bytePrefix, byteSuffix, byteSeparator, digits)
    }

    val formatLength = formattedStringLength(
        numberOfBytes = endIndex - startIndex,
        bytesPerLine,
        bytesPerGroup,
        groupSeparator.length,
        byteSeparator.length,
        bytePrefix.length,
        byteSuffix.length
    )

    var indexInLine = 0
    var indexInGroup = 0

    val charArray = CharArray(formatLength)
    var charIndex = 0

    for (i in startIndex until endIndex) {
        if (indexInLine == bytesPerLine) {
            charArray[charIndex++] = '\n'
            indexInLine = 0
            indexInGroup = 0
        } else if (indexInGroup == bytesPerGroup) {
            charIndex = groupSeparator.toCharArrayIfNotEmpty(charArray, charIndex)
            indexInGroup = 0
        }
        if (indexInGroup != 0) {
            charIndex = byteSeparator.toCharArrayIfNotEmpty(charArray, charIndex)
        }

        charIndex = formatByteAt(i, bytePrefix, byteSuffix, digits, charArray, charIndex)

        indexInGroup += 1
        indexInLine += 1
    }

    check(charIndex == formatLength)
    return charArray.concatToString()
}

private fun ByteArray.toHexString(
    startIndex: Int,
    endIndex: Int,
    bytePrefix: String,
    byteSuffix: String,
    byteSeparator: String,
    digits: String
): String {
    val numberOfBytes = endIndex - startIndex
    val byteSeparatorLength = byteSeparator.length
    val bytePrefixLength = bytePrefix.length
    val byteSuffixLength = byteSuffix.length
    val charsPerByte = 2L + bytePrefixLength + byteSuffixLength + byteSeparatorLength
    val formatLength = numberOfBytes * charsPerByte - byteSeparatorLength
    val charArray = CharArray(checkFormatLength(formatLength))

    var charIndex = 0

    if (bytePrefixLength == 0 && byteSuffixLength == 0) {
        if (byteSeparatorLength == 0) {
            for (i in startIndex until endIndex) {
                charIndex = formatByteAt(i, digits, charArray, charIndex)
            }
            return charArray.concatToString()
        } else if (byteSeparatorLength == 1) {
            val byteSeparatorChar = byteSeparator[0]
            charIndex = formatByteAt(startIndex, digits, charArray, charIndex)
            for (i in startIndex + 1 until endIndex) {
                charArray[charIndex++] = byteSeparatorChar
                charIndex = formatByteAt(i, digits, charArray, charIndex)
            }
            return charArray.concatToString()
        }
    }

    charIndex = formatByteAt(startIndex, bytePrefix, byteSuffix, digits, charArray, charIndex)
    for (i in startIndex + 1 until endIndex) {
        charIndex = byteSeparator.toCharArrayIfNotEmpty(charArray, charIndex)
        charIndex = formatByteAt(i, bytePrefix, byteSuffix, digits, charArray, charIndex)
    }

    return charArray.concatToString()
}

private fun ByteArray.formatByteAt(
    index: Int,
    bytePrefix: String,
    byteSuffix: String,
    digits: String,
    destination: CharArray,
    destinationOffset: Int
): Int {
    var i = bytePrefix.toCharArrayIfNotEmpty(destination, destinationOffset)
    i = formatByteAt(index, digits, destination, i)
    return byteSuffix.toCharArrayIfNotEmpty(destination, i)
}

@Suppress("NOTHING_TO_INLINE")
private inline fun ByteArray.formatByteAt(index: Int, digits: String, destination: CharArray, destinationOffset: Int): Int {
    val byte = this[index].toInt() and 0xFF
    val high = byte shr 4
    val low = byte and 0xF
    destination[destinationOffset] = digits[high]
    destination[destinationOffset + 1] = digits[low]
    return destinationOffset + 2
}

// Declared internal for testing
internal fun formattedStringLength(
    numberOfBytes: Int,
    bytesPerLine: Int,
    bytesPerGroup: Int,
    groupSeparatorLength: Int,
    byteSeparatorLength: Int,
    bytePrefixLength: Int,
    byteSuffixLength: Int
): Int {
    require(numberOfBytes > 0)
    // By contract bytesPerLine and bytesPerGroup are > 0

    val lineSeparators = (numberOfBytes - 1) / bytesPerLine
    val groupSeparators = run {
        val groupSeparatorsPerLine = (bytesPerLine - 1) / bytesPerGroup
        val bytesInLastLine = (numberOfBytes % bytesPerLine).let { if (it == 0) bytesPerLine else it }
        val groupSeparatorsInLastLine = (bytesInLastLine - 1) / bytesPerGroup
        lineSeparators * groupSeparatorsPerLine + groupSeparatorsInLastLine
    }
    val byteSeparators = numberOfBytes - 1 - lineSeparators - groupSeparators

    // The max formatLength is achieved when
    // numberOfBytes, bytePrefix/Suffix/Separator.length = Int.MAX_VALUE.
    // The result is 3 * Int.MAX_VALUE * Int.MAX_VALUE + Int.MAX_VALUE,
    // which is > Long.MAX_VALUE, but < ULong.MAX_VALUE.

    val formatLength: Long = lineSeparators.toLong() /* * lineSeparator.length = 1 */ +
            groupSeparators.toLong() * groupSeparatorLength.toLong() +
            byteSeparators.toLong() * byteSeparatorLength.toLong() +
            numberOfBytes.toLong() * (bytePrefixLength.toLong() + 2L + byteSuffixLength.toLong())

    return checkFormatLength(formatLength)
}

private fun checkFormatLength(formatLength: Long): Int {
    if (formatLength !in 0..Int.MAX_VALUE) {
        // TODO: Common OutOfMemoryError?
        throw IllegalArgumentException("The resulting string length is too big: ${formatLength.toULong()}")
    }
    return formatLength.toInt()
}

/**
 * Parses bytes from this string using the specified [HexFormat].
 *
 * Note that only [HexFormat.BytesHexFormat] affects parsing,
 * and parsing is performed in case-insensitive manner.
 * Also, any of the char sequences CRLF, LF and CR is considered a valid line separator.
 *
 * @param format the [HexFormat] to use for parsing, [HexFormat.Default] by default.
 *
 * @throws IllegalArgumentException if this string does not comply with the specified [format].
 */
@ExperimentalStdlibApi
@SinceKotlin("1.9")
public fun String.hexToByteArray(format: HexFormat = HexFormat.Default): ByteArray = hexToByteArray(0, length, format)

/**
 * Parses bytes from this string using the specified [HexFormat].
 *
 * Note that only [HexFormat.BytesHexFormat] affects parsing,
 * and parsing is performed in case-insensitive manner.
 * Also, any of the char sequences CRLF, LF and CR is considered a valid line separator.
 *
 * @param startIndex the beginning (inclusive) of the substring to parse, 0 by default.
 * @param endIndex the end (exclusive) of the substring to parse, length of this string by default.
 * @param format the [HexFormat] to use for parsing, [HexFormat.Default] by default.
 *
 * @throws IndexOutOfBoundsException when [startIndex] or [endIndex] is out of range of this string indices.
 * @throws IllegalArgumentException when `startIndex > endIndex`.
 * @throws IllegalArgumentException if the substring does not comply with the specified [format].
 */
@ExperimentalStdlibApi
//@SinceKotlin("1.9")
private fun String.hexToByteArray(
    startIndex: Int = 0,
    endIndex: Int = length,
    format: HexFormat = HexFormat.Default
): ByteArray {
    AbstractList.checkBoundsIndexes(startIndex, endIndex, length)

    if (startIndex == endIndex) {
        return byteArrayOf()
    }

    val bytesFormat = format.bytes
    val bytesPerLine = bytesFormat.bytesPerLine
    val bytesPerGroup = bytesFormat.bytesPerGroup
    val bytePrefix = bytesFormat.bytePrefix
    val byteSuffix = bytesFormat.byteSuffix
    val byteSeparator = bytesFormat.byteSeparator
    val groupSeparator = bytesFormat.groupSeparator

    // Optimize for formats with unspecified bytesPerLine and bytesPerGroup
    if (bytesPerLine == Int.MAX_VALUE && bytesPerGroup == Int.MAX_VALUE) {
        hexToByteArray(startIndex, endIndex, bytePrefix, byteSuffix, byteSeparator)?.let { return it }
    }

    val resultCapacity = parsedByteArrayMaxSize(
        stringLength = endIndex - startIndex,
        bytesPerLine,
        bytesPerGroup,
        groupSeparator.length,
        byteSeparator.length,
        bytePrefix.length,
        byteSuffix.length
    )
    val result = ByteArray(resultCapacity)

    var i = startIndex
    var byteIndex = 0
    var indexInLine = 0
    var indexInGroup = 0

    while (i < endIndex) {
        if (indexInLine == bytesPerLine) {
            i = checkNewLineAt(i, endIndex)
            indexInLine = 0
            indexInGroup = 0
        } else if (indexInGroup == bytesPerGroup) {
            i = checkContainsAt(groupSeparator, i, endIndex, "group separator")
            indexInGroup = 0
        } else if (indexInGroup != 0) {
            i = checkContainsAt(byteSeparator, i, endIndex, "byte separator")
        }
        indexInLine += 1
        indexInGroup += 1

        i = checkContainsAt(bytePrefix, i, endIndex, "byte prefix")
        if (endIndex - 2 < i) {
            throwInvalidNumberOfDigits(i, endIndex, maxDigits = 2, requireMaxLength = true)
        }
        result[byteIndex++] = parseByteAt(i)
        i = checkContainsAt(byteSuffix, i + 2, endIndex, "byte suffix")
    }

    return if (byteIndex == result.size) result else result.copyOf(byteIndex)
}

private fun String.hexToByteArray(
    startIndex: Int,
    endIndex: Int,
    bytePrefix: String,
    byteSuffix: String,
    byteSeparator: String
): ByteArray? {
    val numberOfChars = (endIndex - startIndex).toLong()
    val byteSeparatorLength = byteSeparator.length
    val bytePrefixLength = bytePrefix.length
    val byteSuffixLength = byteSuffix.length
    val charsPerByte = 2L + bytePrefixLength + byteSuffixLength + byteSeparatorLength
    val numberOfBytes = ((numberOfChars + byteSeparatorLength) / charsPerByte).toInt()

    // Go to the default implementation when the string length doesn't match
    if (numberOfBytes * charsPerByte - byteSeparatorLength != numberOfChars) {
        return null
    }

    val result = ByteArray(numberOfBytes)
    var i = startIndex

    if (bytePrefixLength == 0 && byteSuffixLength == 0) {
        if (byteSeparatorLength == 0) {
            for (index in 0 until numberOfBytes) {
                result[index] = parseByteAt(i)
                i += 2
            }
            return result
        } else if (byteSeparatorLength == 1) {
            val byteSeparatorChar = byteSeparator[0]
            result[0] = parseByteAt(i)
            i += 2
            for (index in 1 until numberOfBytes) {
                if (this[i] != byteSeparatorChar) {
                    checkContainsAt(byteSeparator, i, endIndex, "byte separator")
                }
                result[index] = parseByteAt(i + 1)
                i += 3
            }
            return result
        }
    }

    i = checkContainsAt(bytePrefix, i, endIndex, "byte prefix")
    val between = byteSuffix + byteSeparator + bytePrefix
    for (index in 0 until numberOfBytes - 1) {
        result[index] = parseByteAt(i)
        i = checkContainsAt(between, i + 2, endIndex, "byte suffix + byte separator + byte prefix")
    }
    result[numberOfBytes - 1] = parseByteAt(i)
    checkContainsAt(byteSuffix, i + 2, endIndex, "byte suffix")

    return result
}

private fun String.parseByteAt(index: Int): Byte {
    val high = decimalFromHexDigitAt(index)
    val low = decimalFromHexDigitAt(index + 1)
    return ((high shl 4) or low).toByte()
}

// Declared internal for testing
internal fun parsedByteArrayMaxSize(
    stringLength: Int,
    bytesPerLine: Int,
    bytesPerGroup: Int,
    groupSeparatorLength: Int,
    byteSeparatorLength: Int,
    bytePrefixLength: Int,
    byteSuffixLength: Int
): Int {
    require(stringLength > 0)
    // By contract bytesPerLine and bytesPerGroup are > 0

    // The max charsPerSet is achieved when
    // bytesPerLine/Group, bytePrefix/Suffix/SeparatorLength = Int.MAX_VALUE.
    // The result is 3 * Int.MAX_VALUE * Int.MAX_VALUE + Int.MAX_VALUE,
    // which is > Long.MAX_VALUE, but < ULong.MAX_VALUE.

    val charsPerByte = bytePrefixLength + 2L + byteSuffixLength

    val charsPerGroup = charsPerSet(charsPerByte, bytesPerGroup, byteSeparatorLength)

    val charsPerLine = if (bytesPerLine <= bytesPerGroup) {
        charsPerSet(charsPerByte, bytesPerLine, byteSeparatorLength)
    } else {
        val groupsPerLine = bytesPerLine / bytesPerGroup
        var result = charsPerSet(charsPerGroup, groupsPerLine, groupSeparatorLength)
        val bytesPerLastGroupInLine = bytesPerLine % bytesPerGroup
        if (bytesPerLastGroupInLine != 0) {
            result += groupSeparatorLength
            result += charsPerSet(charsPerByte, bytesPerLastGroupInLine, byteSeparatorLength)
        }
        result
    }

    var numberOfChars = stringLength.toLong()

    // assume one-character line separator to maximize size
    val wholeLines = wholeElementsPerSet(numberOfChars, charsPerLine, 1)
    numberOfChars -= wholeLines * (charsPerLine + 1)

    val wholeGroupsInLastLine = wholeElementsPerSet(numberOfChars, charsPerGroup, groupSeparatorLength)
    numberOfChars -= wholeGroupsInLastLine * (charsPerGroup + groupSeparatorLength)

    val wholeBytesInLastGroup = wholeElementsPerSet(numberOfChars, charsPerByte, byteSeparatorLength)
    numberOfChars -= wholeBytesInLastGroup * (charsPerByte + byteSeparatorLength)

    // If numberOfChars is bigger than zero here:
    //   * CRLF might have been used as line separator
    //   * or there are dangling characters at the end of string
    // Anyhow, have a spare capacity to let parsing continue.
    // In case of dangling characters it will throw later on with a correct message.
    val spare = if (numberOfChars > 0L) 1 else 0

    // The number of parsed bytes will always fit into Int, each parsed byte consumes at least 2 chars of the input string.
    return ((wholeLines * bytesPerLine) + (wholeGroupsInLastLine * bytesPerGroup) + wholeBytesInLastGroup + spare).toInt()
}

private fun charsPerSet(charsPerElement: Long, elementsPerSet: Int, elementSeparatorLength: Int): Long {
    require(elementsPerSet > 0)
    return (charsPerElement * elementsPerSet) + (elementSeparatorLength * (elementsPerSet - 1L))
}

private fun wholeElementsPerSet(charsPerSet: Long, charsPerElement: Long, elementSeparatorLength: Int): Long {
    return if (charsPerSet <= 0 || charsPerElement <= 0) 0
    else (charsPerSet + elementSeparatorLength) / (charsPerElement + elementSeparatorLength)
}

private fun String.checkNewLineAt(index: Int, endIndex: Int): Int {
    return if (this[index] == '\r') {
        if (index + 1 < endIndex && this[index + 1] == '\n') index + 2 else index + 1
    } else if (this[index] == '\n') {
        index + 1
    } else {
        throw NumberFormatException("Expected a new line at index $index, but was ${this[index]}")
    }
}

// -------------------------- format and parse Byte --------------------------

/**
 * Formats this `Byte` value using the specified [format].
 *
 * Note that only [HexFormat.upperCase] and [HexFormat.NumberHexFormat] affect formatting.
 *
 * @param format the [HexFormat] to use for formatting, [HexFormat.Default] by default.
 */
@ExperimentalStdlibApi
@SinceKotlin("1.9")
public fun Byte.toHexString(format: HexFormat = HexFormat.Default): String {
    val prefix = format.number.prefix
    val suffix = format.number.suffix
    val removeLeadingZeros = format.number.removeLeadingZeros

    // Optimize for the default format
    if (prefix.isEmpty() && suffix.isEmpty() && !removeLeadingZeros) {
        val charArray = CharArray(2)
        val digits = if (format.upperCase) UPPER_CASE_HEX_DIGITS else LOWER_CASE_HEX_DIGITS
        val value = this.toInt()
        charArray[0] = digits[(value shr 4) and 0xF]
        charArray[1] = digits[value and 0xF]
        return charArray.concatToString()
    }

    return toLong().toHexStringImpl(format, bits = 8)
}

/**
 * Parses a `Byte` value from this string using the specified [format].
 *
 * Note that only [HexFormat.NumberHexFormat] affects parsing,
 * and parsing is performed in case-insensitive manner.
 *
 * @param format the [HexFormat] to use for parsing, [HexFormat.Default] by default.
 *
 * @throws IllegalArgumentException if this string does not comply with the specified [format].
 */
@ExperimentalStdlibApi
@SinceKotlin("1.9")
public fun String.hexToByte(format: HexFormat = HexFormat.Default): Byte = hexToByte(0, length, format)

/**
 * Parses a `Byte` value from this string using the specified [format].
 *
 * Note that only [HexFormat.NumberHexFormat] affects parsing,
 * and parsing is performed in case-insensitive manner.
 *
 * @param startIndex the beginning (inclusive) of the substring to parse, 0 by default.
 * @param endIndex the end (exclusive) of the substring to parse, length of this string by default.
 * @param format the [HexFormat] to use for parsing, [HexFormat.Default] by default.
 *
 * @throws IndexOutOfBoundsException when [startIndex] or [endIndex] is out of range of this string indices.
 * @throws IllegalArgumentException when `startIndex > endIndex`.
 * @throws IllegalArgumentException if the substring does not comply with the specified [format].
 */
@ExperimentalStdlibApi
//@SinceKotlin("1.9")
private fun String.hexToByte(startIndex: Int = 0, endIndex: Int = length, format: HexFormat = HexFormat.Default): Byte =
    hexToLongImpl(startIndex, endIndex, format, maxDigits = 2).toByte()

// -------------------------- format and parse Short --------------------------

/**
 * Formats this `Short` value using the specified [format].
 *
 * Note that only [HexFormat.upperCase] and [HexFormat.NumberHexFormat] affect formatting.
 *
 * @param format the [HexFormat] to use for formatting, [HexFormat.Default] by default.
 */
@ExperimentalStdlibApi
@SinceKotlin("1.9")
public fun Short.toHexString(format: HexFormat = HexFormat.Default): String {
    val prefix = format.number.prefix
    val suffix = format.number.suffix
    val removeLeadingZeros = format.number.removeLeadingZeros

    // Optimize for the default format
    if (prefix.isEmpty() && suffix.isEmpty() && !removeLeadingZeros) {
        val charArray = CharArray(4)
        val digits = if (format.upperCase) UPPER_CASE_HEX_DIGITS else LOWER_CASE_HEX_DIGITS
        val value = this.toInt()
        charArray[0] = digits[(value shr 12) and 0xF]
        charArray[1] = digits[(value shr 8) and 0xF]
        charArray[2] = digits[(value shr 4) and 0xF]
        charArray[3] = digits[value and 0xF]
        return charArray.concatToString()
    }

    return toLong().toHexStringImpl(format, bits = 16)
}

/**
 * Parses a `Short` value from this string using the specified [format].
 *
 * Note that only [HexFormat.NumberHexFormat] affects parsing,
 * and parsing is performed in case-insensitive manner.
 *
 * @param format the [HexFormat] to use for parsing, [HexFormat.Default] by default.
 *
 * @throws IllegalArgumentException if this string does not comply with the specified [format].
 */
@ExperimentalStdlibApi
@SinceKotlin("1.9")
public fun String.hexToShort(format: HexFormat = HexFormat.Default): Short = hexToShort(0, length, format)

/**
 * Parses a `Short` value from this string using the specified [format].
 *
 * Note that only [HexFormat.NumberHexFormat] affects parsing,
 * and parsing is performed in case-insensitive manner.
 *
 * @param startIndex the beginning (inclusive) of the substring to parse, 0 by default.
 * @param endIndex the end (exclusive) of the substring to parse, length of this string by default.
 * @param format the [HexFormat] to use for parsing, [HexFormat.Default] by default.
 *
 * @throws IndexOutOfBoundsException when [startIndex] or [endIndex] is out of range of this string indices.
 * @throws IllegalArgumentException when `startIndex > endIndex`.
 * @throws IllegalArgumentException if the substring does not comply with the specified [format].
 */
@ExperimentalStdlibApi
//@SinceKotlin("1.9")
private fun String.hexToShort(startIndex: Int = 0, endIndex: Int = length, format: HexFormat = HexFormat.Default): Short =
    hexToLongImpl(startIndex, endIndex, format, maxDigits = 4).toShort()

// -------------------------- format and parse Int --------------------------

/**
 * Formats this `Int` value using the specified [format].
 *
 * Note that only [HexFormat.upperCase] and [HexFormat.NumberHexFormat] affect formatting.
 *
 * @param format the [HexFormat] to use for formatting, [HexFormat.Default] by default.
 */
@ExperimentalStdlibApi
@SinceKotlin("1.9")
public fun Int.toHexString(format: HexFormat = HexFormat.Default): String {
    val prefix = format.number.prefix
    val suffix = format.number.suffix
    val removeLeadingZeros = format.number.removeLeadingZeros

    // Optimize for the default format
    if (prefix.isEmpty() && suffix.isEmpty() && !removeLeadingZeros) {
        val charArray = CharArray(8)
        val digits = if (format.upperCase) UPPER_CASE_HEX_DIGITS else LOWER_CASE_HEX_DIGITS
        val value = this
        charArray[0] = digits[(value shr 28) and 0xF]
        charArray[1] = digits[(value shr 24) and 0xF]
        charArray[2] = digits[(value shr 20) and 0xF]
        charArray[3] = digits[(value shr 16) and 0xF]
        charArray[4] = digits[(value shr 12) and 0xF]
        charArray[5] = digits[(value shr 8) and 0xF]
        charArray[6] = digits[(value shr 4) and 0xF]
        charArray[7] = digits[value and 0xF]
        return charArray.concatToString()
    }

    return toLong().toHexStringImpl(format, bits = 32)
}

/**
 * Parses an `Int` value from this string using the specified [format].
 *
 * Note that only [HexFormat.NumberHexFormat] affects parsing,
 * and parsing is performed in case-insensitive manner.
 *
 * @param format the [HexFormat] to use for parsing, [HexFormat.Default] by default.
 *
 * @throws IllegalArgumentException if this string does not comply with the specified [format].
 */
@ExperimentalStdlibApi
@SinceKotlin("1.9")
public fun String.hexToInt(format: HexFormat = HexFormat.Default): Int = hexToInt(0, length, format)

/**
 * Parses an `Int` value from this string using the specified [format].
 *
 * Note that only [HexFormat.NumberHexFormat] affects parsing,
 * and parsing is performed in case-insensitive manner.
 *
 * @param startIndex the beginning (inclusive) of the substring to parse, 0 by default.
 * @param endIndex the end (exclusive) of the substring to parse, length of this string by default.
 * @param format the [HexFormat] to use for parsing, [HexFormat.Default] by default.
 *
 * @throws IndexOutOfBoundsException when [startIndex] or [endIndex] is out of range of this string indices.
 * @throws IllegalArgumentException when `startIndex > endIndex`.
 * @throws IllegalArgumentException if the substring does not comply with the specified [format].
 */
@ExperimentalStdlibApi
//@SinceKotlin("1.9")
private fun String.hexToInt(startIndex: Int = 0, endIndex: Int = length, format: HexFormat = HexFormat.Default): Int =
    hexToLongImpl(startIndex, endIndex, format, maxDigits = 8).toInt()

// -------------------------- format and parse Long --------------------------

/**
 * Formats this `Long` value using the specified [format].
 *
 * Note that only [HexFormat.upperCase] and [HexFormat.NumberHexFormat] affect formatting.
 *
 * @param format the [HexFormat] to use for formatting, [HexFormat.Default] by default.
 */
@ExperimentalStdlibApi
@SinceKotlin("1.9")
public fun Long.toHexString(format: HexFormat = HexFormat.Default): String {
    val prefix = format.number.prefix
    val suffix = format.number.suffix
    val removeLeadingZeros = format.number.removeLeadingZeros

    // Optimize for the default format
    if (prefix.isEmpty() && suffix.isEmpty() && !removeLeadingZeros) {
        val charArray = CharArray(16)
        val digits = if (format.upperCase) UPPER_CASE_HEX_DIGITS else LOWER_CASE_HEX_DIGITS
        val value = this
        charArray[0] = digits[((value shr 60) and 0xF).toInt()]
        charArray[1] = digits[((value shr 56) and 0xF).toInt()]
        charArray[2] = digits[((value shr 52) and 0xF).toInt()]
        charArray[3] = digits[((value shr 48) and 0xF).toInt()]
        charArray[4] = digits[((value shr 44) and 0xF).toInt()]
        charArray[5] = digits[((value shr 40) and 0xF).toInt()]
        charArray[6] = digits[((value shr 36) and 0xF).toInt()]
        charArray[7] = digits[((value shr 32) and 0xF).toInt()]
        charArray[8] = digits[((value shr 28) and 0xF).toInt()]
        charArray[9] = digits[((value shr 24) and 0xF).toInt()]
        charArray[10] = digits[((value shr 20) and 0xF).toInt()]
        charArray[11] = digits[((value shr 16) and 0xF).toInt()]
        charArray[12] = digits[((value shr 12) and 0xF).toInt()]
        charArray[13] = digits[((value shr 8) and 0xF).toInt()]
        charArray[14] = digits[((value shr 4) and 0xF).toInt()]
        charArray[15] = digits[(value and 0xF).toInt()]
        return charArray.concatToString()
    }

    return toHexStringImpl(format, bits = 64)
}

/**
 * Parses a `Long` value from this string using the specified [format].
 *
 * Note that only [HexFormat.NumberHexFormat] affects parsing,
 * and parsing is performed in case-insensitive manner.
 *
 * @param format the [HexFormat] to use for parsing, [HexFormat.Default] by default.
 *
 * @throws IllegalArgumentException if this string does not comply with the specified [format].
 */
@ExperimentalStdlibApi
@SinceKotlin("1.9")
public fun String.hexToLong(format: HexFormat = HexFormat.Default): Long = hexToLong(0, length, format)

/**
 * Parses a `Long` value from this string using the specified [format].
 *
 * Note that only [HexFormat.NumberHexFormat] affects parsing,
 * and parsing is performed in case-insensitive manner.
 *
 * @param startIndex the beginning (inclusive) of the substring to parse, 0 by default.
 * @param endIndex the end (exclusive) of the substring to parse, length of this string by default.
 * @param format the [HexFormat] to use for parsing, [HexFormat.Default] by default.
 *
 * @throws IndexOutOfBoundsException when [startIndex] or [endIndex] is out of range of this string indices.
 * @throws IllegalArgumentException when `startIndex > endIndex`.
 * @throws IllegalArgumentException if the substring does not comply with the specified [format].
 */
@ExperimentalStdlibApi
//@SinceKotlin("1.9")
private fun String.hexToLong(startIndex: Int = 0, endIndex: Int = length, format: HexFormat = HexFormat.Default): Long =
    hexToLongImpl(startIndex, endIndex, format, maxDigits = 16)

// -------------------------- private format and parse functions --------------------------

@ExperimentalStdlibApi
private fun Long.toHexStringImpl(format: HexFormat, bits: Int): String {
    require(bits and 0x3 == 0)

    val digits = if (format.upperCase) UPPER_CASE_HEX_DIGITS else LOWER_CASE_HEX_DIGITS
    val value = this
    val numberOfHexDigits = bits shr 2

    val prefix = format.number.prefix
    val suffix = format.number.suffix
    var removeZeros = format.number.removeLeadingZeros

    // Optimize for the default format
    if (prefix.isEmpty() && suffix.isEmpty() && !removeZeros) {
        val charArray = CharArray(numberOfHexDigits)
        var shift = bits - 4
        for (i in 0 until numberOfHexDigits) {
            val decimal = ((value shr shift) and 0xF).toInt()
            charArray[i] = digits[decimal]
            shift -= 4
        }
        return charArray.concatToString()
    }

    val formatLength = prefix.length.toLong() + numberOfHexDigits + suffix.length
    val charArray = CharArray(checkFormatLength(formatLength))

    var i = prefix.toCharArrayIfNotEmpty(charArray, 0)

    var shift = bits
    repeat(numberOfHexDigits) {
        shift -= 4
        val decimal = ((value shr shift) and 0xF).toInt()
        removeZeros = removeZeros && decimal == 0 && shift > 0
        if (!removeZeros) {
            charArray[i++] = digits[decimal]
        }
    }

    i = suffix.toCharArrayIfNotEmpty(charArray, i)

    return if (i == charArray.size) charArray.concatToString() else charArray.concatToString(endIndex = i)
}

private fun String.toCharArrayIfNotEmpty(destination: CharArray, destinationOffset: Int): Int {
    when (length) {
        0 -> { /* do nothing */ }
        1 -> destination[destinationOffset] = this[0]
        else -> toCharArray(destination, destinationOffset)
    }
    return destinationOffset + length
}

@ExperimentalStdlibApi
private fun String.hexToLongImpl(startIndex: Int = 0, endIndex: Int = length, format: HexFormat, maxDigits: Int): Long {
    AbstractList.checkBoundsIndexes(startIndex, endIndex, length)

    val prefix = format.number.prefix
    val suffix = format.number.suffix

    if (endIndex - startIndex - prefix.length <= suffix.length) {
        throw NumberFormatException(
            "Expected a hexadecimal number with prefix \"$prefix\" and suffix \"$suffix\", but was ${substring(startIndex, endIndex)}"
        )
    }

    val digitsStartIndex = checkContainsAt(prefix, startIndex, endIndex, "prefix")
    val digitsEndIndex = endIndex - suffix.length
    checkContainsAt(suffix, digitsEndIndex, endIndex, "suffix")

    if (digitsEndIndex - digitsStartIndex > maxDigits) {
        throwInvalidNumberOfDigits(digitsStartIndex, digitsEndIndex, maxDigits, requireMaxLength = false)
    }

    var result = 0L
    for (i in digitsStartIndex until digitsEndIndex) {
        result = (result shl 4) or decimalFromHexDigitAt(i).toLong()
    }
    return result
}

@Suppress("NOTHING_TO_INLINE")
private inline fun String.checkContainsAt(part: String, index: Int, endIndex: Int, partName: String): Int {
    val partLength = part.length
    if (partLength == 0) return index
    val end = index + partLength
    if (regionMatches(index, part, 0, partLength, ignoreCase = true)) {
        return end
    }
    throw NumberFormatException(
        "Expected $partName \"$part\" at index $index, but was ${this.substring(index, end.coerceAtMost(endIndex))}"
    )
}

private fun String.throwInvalidNumberOfDigits(startIndex: Int, endIndex: Int, maxDigits: Int, requireMaxLength: Boolean) {
    val specifier = if (requireMaxLength) "exactly" else "at most"
    val substring = substring(startIndex, endIndex)
    throw NumberFormatException(
        "Expected $specifier $maxDigits hexadecimal digits at index $startIndex, but was $substring of length ${endIndex - startIndex}"
    )
}

@Suppress("NOTHING_TO_INLINE")
private inline fun String.decimalFromHexDigitAt(index: Int): Int {
    val code = this[index].code
    if (code ushr 8 == 0 && HEX_DIGITS_TO_DECIMAL[code] >= 0) {
        return HEX_DIGITS_TO_DECIMAL[code]
    }
    throw NumberFormatException("Expected a hexadecimal digit at index $index, but was ${this[index]}")
}
