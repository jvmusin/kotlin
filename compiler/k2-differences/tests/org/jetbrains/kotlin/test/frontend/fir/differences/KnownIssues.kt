/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.frontend.fir.differences

import java.io.File

class IssueInfo(val id: String, val numberInProject: Long)

val MISSING_DIAGNOSTICS_UMBRELLA = IssueInfo("25-4537231", 59443)
val DISAPPEARED_DIAGNOSTICS_UMBRELLA = IssueInfo("25-4612474", 59870)
val INTRODUCED_DIAGNOSTICS_UMBRELLA = IssueInfo("25-4612482", 59871)

private const val INSERTION_MARKER = "//<::>"

const val NEW_ISSUE_STATE = "Open"
const val NEW_ISSUE_PRIORITY = "Minor"
val NEW_ISSUE_TARGET_VERSIONS = listOf("2.0-M1")
val NEW_ISSUE_SUBSYSTEMS = listOf("Frontend. Checkers")

fun buildIssueCustomFields(
    state: String = NEW_ISSUE_STATE,
    priority: String = NEW_ISSUE_PRIORITY,
    targetVersions: List<String> = NEW_ISSUE_TARGET_VERSIONS,
    subsystems: List<String> = NEW_ISSUE_SUBSYSTEMS,
) = "customFields" to listOf(
    mapOf(
        "name" to "State",
        "\$type" to "StateIssueCustomField",
        "value" to mapOf(
            "name" to state,
        ),
    ),
    mapOf(
        "name" to "Priority",
        "\$type" to "SingleEnumIssueCustomField",
        "value" to mapOf(
            "name" to priority,
        ),
    ),
    mapOf(
        "name" to "Target versions",
        "\$type" to "MultiVersionIssueCustomField",
        "value" to targetVersions.map {
            mapOf("name" to it)
        },
    ),
    mapOf(
        "name" to "Subsystems",
        "\$type" to "MultiOwnedIssueCustomField",
        "value" to subsystems.map {
            mapOf("name" to it)
        },
    ),
)

private fun createNewKotlinIssue(summary: String, description: String, tags: List<String>): IssueInfo {
    val result = postJson(
        "https://youtrack.jetbrains.com/api/issues?fields=id,numberInProject",
        API_HEADERS,
        mapOf(
            "project" to mapOf(
                "id" to KOTLIN_PROJECT_ID,
            ),
            "summary" to summary,
            "tags" to tags.map { mapOf("id" to it) },
            "description" to description,
            // For some reason, while creating an issue only the target
            // versions are set, and the issue state and the priority
            // are left undefined.
            buildIssueCustomFields()
        ),
    ).also(::println)

    val id = "\"id\":\"([^\"]*)\"".toRegex().find(result)?.groupValues?.last()
        ?: error("No `id` found in the issue creation response")
    val numberInProject = "\"numberInProject\":(\\d+)".toRegex().find(result)?.groupValues?.last()?.toLong()
        ?: error("No `id` found in the issue creation response")

    postJson(
        "https://youtrack.jetbrains.com/api/issues/KT-$numberInProject?fields=customFields(name,value(name))",
        API_HEADERS,
        mapOf(
            buildIssueCustomFields(),
        ),
    ).also(::println)

    return IssueInfo(id, numberInProject)
}

private operator fun File.div(name: String) = child(name)

private fun insertIssueInfoIntoKnownIssuesSourceFile(diagnostic: String, issueInfo: IssueInfo, targetMap: String) {
    val targetFile = projectDirectory / "compiler" / "k2-differences" / "tests" /
            "org" / "jetbrains" / "kotlin" / "test" / "frontend" / "fir" / "differences" / "KnownIssues.kt"

    if (!targetFile.exists()) {
        error("The script expects there is a ${targetFile.path} file containing the lists of knownDiagnostics")
    }

    val text = targetFile.readText()
    val insertionPoint = INSERTION_MARKER + targetMap

    val match = "^(\\s+)$INSERTION_MARKER".toRegex(RegexOption.MULTILINE).find(text)
        ?: error("The ${targetFile.path} file does not contain the insertion point for $targetMap: expected to see `$insertionPoint`")
    val indent = match.groupValues.last()
    val entry = "\"$diagnostic\" to IssueInfo(\"${issueInfo.id}\", ${issueInfo.numberInProject}),"

    targetFile.writeText(text.replace(insertionPoint, entry + System.lineSeparator() + indent + insertionPoint))
}

private fun createMissingIssueFor(diagnostic: String, description: String, tags: List<String>) =
    createNewKotlinIssue("K2: Missing $diagnostic", description, tags).also {
        mutableKnownMissingDiagnostics[diagnostic] = it
        insertIssueInfoIntoKnownIssuesSourceFile(diagnostic, it, "knownMissingDiagnostics")
    }

private fun createDisappearedIssueFor(diagnostic: String, description: String, tags: List<String>) =
    createNewKotlinIssue("K2: Disappeared $diagnostic", description, tags).also {
        mutableKnownDisappearedDiagnostics[diagnostic] = it
        insertIssueInfoIntoKnownIssuesSourceFile(diagnostic, it, "knownDisappearedDiagnostics")
    }

private fun createIntroducedIssueFor(diagnostic: String, description: String, tags: List<String>) =
    createNewKotlinIssue("K2: Introduced $diagnostic", description, tags).also {
        mutableKnownIntroducedDiagnostics[diagnostic] = it
        insertIssueInfoIntoKnownIssuesSourceFile(diagnostic, it, "knownIntroducedDiagnostics")
    }

private fun generateMissingDiagnosticsIssues(containmentStatistics: DiagnosticsStatistics) {
    status.doneSilently("Generating issues for missing diagnostics")

    val newMissingDiagnostics = containmentStatistics.extractDisappearances()
        .filterKeys { it !in k2KnownErrors && it !in knownMissingDiagnostics }

    for ((name, files) in newMissingDiagnostics) {
        val issueInfo = createMissingIssueFor(
            diagnostic = name,
            description = "This diagnostic is backed up by ${files.size} tests, but is missing in K2 (see the reports in KT-58630).",
            tags = listOf(Tags.K2, Tags.K1_RED_K2_GREEN),
        )

        issueInfo.makeSubtaskOf(MISSING_DIAGNOSTICS_UMBRELLA.numberInProject)
    }

    status.doneSilently("${newMissingDiagnostics.size} new missing issues created")
}

private fun generateDisappearedDiagnosticsIssues(containmentStatistics: DiagnosticsStatistics) {
    status.doneSilently("Generating issues for disappeared diagnostics")

    val newDisappearedDiagnostics = containmentStatistics.extractDisappearances()
        .filterKeys { it in k2KnownErrors && it !in knownDisappearedDiagnostics }

    for ((name, files) in newDisappearedDiagnostics) {
        val description = "this diagnostic was present in K1, but disappeared"

        val issueInfo = createDisappearedIssueFor(
            diagnostic = name,
            description = "There are ${files.size} tests, where $description (see the reports in KT-58630).",
            tags = listOf(Tags.K2, Tags.K1_RED_K2_GREEN),
        )

        issueInfo.makeSubtaskOf(DISAPPEARED_DIAGNOSTICS_UMBRELLA.numberInProject)
    }

    status.doneSilently("${newDisappearedDiagnostics.size} new disappeared issues created")
}

private fun generateIntroducedDiagnosticsIssues(containmentStatistics: DiagnosticsStatistics) {
    status.doneSilently("Generating issues for introduced diagnostics")

    val newIntroducedDiagnostics = containmentStatistics.extractIntroductions()
        .filterKeys { it !in knownIntroducedDiagnostics }

    for ((name, files) in newIntroducedDiagnostics) {
        val description = "this diagnostic was introduced in K2"

        val issueInfo = createIntroducedIssueFor(
            diagnostic = name,
            description = "There are ${files.size} tests, where $description (see the reports in KT-58630).",
            tags = listOf(Tags.K2, Tags.K2_POTENTIAL_BREAKING_CHANGE),
        )

        issueInfo.makeSubtaskOf(INTRODUCED_DIAGNOSTICS_UMBRELLA.numberInProject)

        knownDisappearedDiagnostics[name]?.let { disappearedIssue ->
            issueInfo.relateTo(disappearedIssue.numberInProject)
        }
    }

    status.doneSilently("${newIntroducedDiagnostics.size} new introduced issues created")
}

fun generateMissingIssues(containmentStatistics: DiagnosticsStatistics) {
    generateMissingDiagnosticsIssues(containmentStatistics)
    generateDisappearedDiagnosticsIssues(containmentStatistics)
    generateIntroducedDiagnosticsIssues(containmentStatistics)
}

private val mutableKnownMissingDiagnostics = mutableMapOf(
    "MIXING_SUSPEND_AND_NON_SUSPEND_SUPERTYPES" to IssueInfo("25-4536950", 59367),
    "SUBTYPING_BETWEEN_CONTEXT_RECEIVERS" to IssueInfo("25-4536951", 59368),
    "BUILDER_INFERENCE_STUB_RECEIVER" to IssueInfo("25-4536952", 59369),
    "JS_NAME_CLASH" to IssueInfo("25-4536953", 59370),
    "MISSING_DEPENDENCY_CLASS" to IssueInfo("25-4536954", 59371),
    "SELF_CALL_IN_NESTED_OBJECT_CONSTRUCTOR_ERROR" to IssueInfo("25-4536955", 59372),
    "INVISIBLE_MEMBER" to IssueInfo("25-4536956", 59373),
    "COMPARE_TO_TYPE_MISMATCH" to IssueInfo("25-4536957", 59374),
    "SUPER_CALL_FROM_PUBLIC_INLINE_ERROR" to IssueInfo("25-4536958", 59375),
    "TYPECHECKER_HAS_RUN_INTO_RECURSIVE_PROBLEM_ERROR" to IssueInfo("25-4536960", 59376),
    "CALL_TO_JS_MODULE_WITHOUT_MODULE_SYSTEM" to IssueInfo("25-4536961", 59377),
    "FINITE_BOUNDS_VIOLATION" to IssueInfo("25-4536962", 59378),
    "MIXING_NAMED_AND_POSITIONED_ARGUMENTS" to IssueInfo("25-4536963", 59379),
    "CALL_TO_JS_NON_MODULE_WITH_MODULE_SYSTEM" to IssueInfo("25-4536965", 59381),
    "PROTECTED_CONSTRUCTOR_NOT_IN_SUPER_CALL" to IssueInfo("25-4536966", 59382),
    "ENUM_ENTRY_SHOULD_BE_INITIALIZED" to IssueInfo("25-4536967", 59383),
    "DYNAMIC_NOT_ALLOWED" to IssueInfo("25-4536968", 59384),
    "NON_VARARG_SPREAD_ERROR" to IssueInfo("25-4536969", 59385),
    "CONSTANT_EXPECTED_TYPE_MISMATCH" to IssueInfo("25-4536970", 59386),
    "NO_CONSTRUCTOR" to IssueInfo("25-4536971", 59387),
    "JSCODE_ERROR" to IssueInfo("25-4536972", 59388),
    "AMBIGUOUS_LABEL" to IssueInfo("25-4536973", 59389),
    "BUILDER_INFERENCE_MULTI_LAMBDA_RESTRICTION" to IssueInfo("25-4536974", 59390),
    "JS_BUILTIN_NAME_CLASH" to IssueInfo("25-4536975", 59391),
    "NAME_CONTAINS_ILLEGAL_CHARS" to IssueInfo("25-4536976", 59392),
    "TYPE_ARGUMENTS_FOR_OUTER_CLASS_WHEN_NESTED_REFERENCED" to IssueInfo("25-4536977", 59393),
    "EXPECTED_PARAMETERS_NUMBER_MISMATCH" to IssueInfo("25-4536978", 59394),
    "PROGRESSIONS_CHANGING_RESOLVE_ERROR" to IssueInfo("25-4536979", 59395),
    "MODIFIER_LIST_NOT_ALLOWED" to IssueInfo("25-4536980", 59396),
    "RESERVED_SYNTAX_IN_CALLABLE_REFERENCE_LHS" to IssueInfo("25-4536981", 59397),
    "NOT_SUPPORTED_INLINE_PARAMETER_IN_INLINE_PARAMETER_DEFAULT_VALUE" to IssueInfo("25-4536982", 59398),
    "JSCODE_NO_JAVASCRIPT_PRODUCED" to IssueInfo("25-4536983", 59399),
    "CANNOT_INFER_VISIBILITY" to IssueInfo("25-4536984", 59400),
    "ADAPTED_CALLABLE_REFERENCE_AGAINST_REFLECTION_TYPE" to IssueInfo("25-4536985", 59401),
    "EXPANSIVE_INHERITANCE" to IssueInfo("25-4536986", 59402),
    "SUPER_CANT_BE_EXTENSION_RECEIVER" to IssueInfo("25-4536987", 59403),
    "EXPECT_TYPE_IN_WHEN_WITHOUT_ELSE" to IssueInfo("25-4536988", 59404),
    "INFERRED_TYPE_VARIABLE_INTO_EMPTY_INTERSECTION_WARNING" to IssueInfo("25-4536989", 59405),
    "PROPERTY_DELEGATION_BY_DYNAMIC" to IssueInfo("25-4536990", 59406),
    "MISSING_CONSTRUCTOR_KEYWORD" to IssueInfo("25-4536991", 59407),
    "MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES" to IssueInfo("25-4536992", 59408),
    "DEFAULT_VALUE_NOT_ALLOWED_IN_OVERRIDE" to IssueInfo("25-4536993", 59409),
    "TYPEALIAS_EXPANDED_TO_MALFORMED_TYPE" to IssueInfo("25-4536994", 59410),
    "ENUM_CLASS_CONSTRUCTOR_CALL" to IssueInfo("25-4536995", 59411),
    "EXPECTED_PARAMETER_TYPE_MISMATCH" to IssueInfo("25-4536996", 59412),
    "VALUE_CLASS_CANNOT_HAVE_CONTEXT_RECEIVERS" to IssueInfo("25-4536997", 59413),
    "PROTECTED_CONSTRUCTOR_CALL_FROM_PUBLIC_INLINE_ERROR" to IssueInfo("25-4536998", 59414),
    "DATA_CLASS_OVERRIDE_DEFAULT_VALUES_ERROR" to IssueInfo("25-4537000", 59415),
    "EXTERNAL_INTERFACE_AS_REIFIED_TYPE_ARGUMENT" to IssueInfo("25-4537001", 59416),
    "CALL_FROM_UMD_MUST_BE_JS_MODULE_AND_JS_NON_MODULE" to IssueInfo("25-4537002", 59417),
    "DUPLICATE_PARAMETER_NAME_IN_FUNCTION_TYPE" to IssueInfo("25-4537003", 59418),
    "MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES_WHEN_NO_EXPLICIT_OVERRIDE" to IssueInfo("25-4537004", 59419),
    "ABBREVIATED_NOTHING_PROPERTY_TYPE" to IssueInfo("25-4537005", 59420),
    "CONTEXT_RECEIVERS_WITH_BACKING_FIELD" to IssueInfo("25-4537006", 59421),
    "NON_SOURCE_ANNOTATION_ON_INLINED_LAMBDA_EXPRESSION" to IssueInfo("25-4537007", 59422),
    "FORBIDDEN_BINARY_MOD_AS_REM" to IssueInfo("25-4537008", 59423),
    "TYPE_MISMATCH_DUE_TO_TYPE_PROJECTIONS" to IssueInfo("25-4537009", 59424),
    "JS_FAKE_NAME_CLASH" to IssueInfo("25-4537010", 59425),
    "RECEIVER_TYPE_MISMATCH" to IssueInfo("25-4537011", 59426),
    "EQUALS_MISSING" to IssueInfo("25-4537012", 59427),
    "ABBREVIATED_NOTHING_RETURN_TYPE" to IssueInfo("25-4537014", 59429),
    "CALLABLE_REFERENCE_RESOLUTION_AMBIGUITY" to IssueInfo("25-4537015", 59430),
    "EXPLICIT_BACKING_FIELDS_UNSUPPORTED" to IssueInfo("25-4537016", 59431),
    "INACCESSIBLE_OUTER_CLASS_EXPRESSION" to IssueInfo("25-4537017", 59432),
    "NESTED_CLASS_ACCESSED_VIA_INSTANCE_REFERENCE" to IssueInfo("25-4537018", 59433),
    "DECLARATION_IN_ILLEGAL_CONTEXT" to IssueInfo("25-4537019", 59434),
    "JSCODE_ARGUMENT_SHOULD_BE_CONSTANT" to IssueInfo("25-4537020", 59435),
    "NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY_MIGRATION" to IssueInfo("25-4537021", 59436),
    "UPPER_BOUND_VIOLATION_IN_CONSTRAINT" to IssueInfo("25-4537022", 59437),
    "OVERLOAD_RESOLUTION_AMBIGUITY_BECAUSE_OF_STUB_TYPES" to IssueInfo("25-4537023", 59438),
    "STUB_TYPE_IN_RECEIVER_CAUSES_AMBIGUITY" to IssueInfo("25-4537024", 59439),
    "UNSUPPORTED_REFERENCES_TO_VARIABLES_AND_PARAMETERS" to IssueInfo("25-4612527", 59905),
    "UNSUPPORTED_SEALED_FUN_INTERFACE" to IssueInfo("25-4612580", 59957),
    "UNSUPPORTED_CLASS_LITERALS_WITH_EMPTY_LHS" to IssueInfo("25-4612594", 59971),
    "UNSUPPORTED_SEALED_WHEN" to IssueInfo("25-4612614", 59990),
    "UNSUPPORTED_INHERITANCE_FROM_JAVA_MEMBER_REFERENCING_KOTLIN_FUNCTION" to IssueInfo("25-4612624", 60000),
    "UNSUPPORTED_SUSPEND_TEST" to IssueInfo("25-4612626", 60002),
    //<::>knownMissingDiagnostics
)

val obsoleteIssues = listOf(
    "NON_VARARG_SPREAD_ERROR",
    "PROTECTED_CONSTRUCTOR_CALL_FROM_PUBLIC_INLINE_ERROR",
    "FORBIDDEN_BINARY_MOD_AS_REM",
    "TYPE_MISMATCH_DUE_TO_TYPE_PROJECTIONS",
    "EXPLICIT_BACKING_FIELDS_UNSUPPORTED",
    "INACCESSIBLE_OUTER_CLASS_EXPRESSION",
    "NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY_MIGRATION",
    "SUPER_CANT_BE_EXTENSION_RECEIVER",
)

val knownMissingDiagnostics: Map<String, IssueInfo> get() = mutableKnownMissingDiagnostics

private val mutableKnownDisappearedDiagnostics = mutableMapOf(
    "TYPE_MISMATCH" to IssueInfo("25-4612487", 59872),
    "UNSAFE_CALL" to IssueInfo("25-4612491", 59873),
    "UNRESOLVED_REFERENCE" to IssueInfo("25-4612492", 59874),
    "UNRESOLVED_REFERENCE_WRONG_RECEIVER" to IssueInfo("25-4612493", 59875),
    "NEW_INFERENCE_NO_INFORMATION_FOR_PARAMETER" to IssueInfo("25-4612494", 59876),
    "OVERLOAD_RESOLUTION_AMBIGUITY" to IssueInfo("25-4612495", 59877),
    "NO_ELSE_IN_WHEN" to IssueInfo("25-4612496", 59878),
    "SMARTCAST_IMPOSSIBLE" to IssueInfo("25-4612497", 59879),
    "CONFLICTING_OVERLOADS" to IssueInfo("25-4612498", 59880),
    "UNSUPPORTED" to IssueInfo("25-4612499", 59881),
    "CANNOT_INFER_PARAMETER_TYPE" to IssueInfo("25-4612500", 59882),
    "INVALID_IF_AS_EXPRESSION" to IssueInfo("25-4612501", 59883),
    "NON_LOCAL_RETURN_NOT_ALLOWED" to IssueInfo("25-4612502", 59884),
    "UNINITIALIZED_VARIABLE" to IssueInfo("25-4612507", 59885),
    "ERROR_IN_CONTRACT_DESCRIPTION" to IssueInfo("25-4612508", 59886),
    "ACTUAL_MISSING" to IssueInfo("25-4612509", 59887),
    "CONFLICTING_JVM_DECLARATIONS" to IssueInfo("25-4612510", 59888),
    "UNSAFE_IMPLICIT_INVOKE_CALL" to IssueInfo("25-4612511", 59889),
    "CONST_VAL_WITH_NON_CONST_INITIALIZER" to IssueInfo("25-4612512", 59890),
    "EQUALITY_NOT_APPLICABLE" to IssueInfo("25-4612513", 59891),
    "VAL_REASSIGNMENT" to IssueInfo("25-4612514", 59892),
    "WRONG_NUMBER_OF_TYPE_ARGUMENTS" to IssueInfo("25-4612515", 59893),
    "ANNOTATION_ARGUMENT_MUST_BE_CONST" to IssueInfo("25-4612516", 59894),
    "INCOMPATIBLE_TYPES" to IssueInfo("25-4612517", 59895),
    "WRONG_ANNOTATION_TARGET" to IssueInfo("25-4612518", 59896),
    "PACKAGE_OR_CLASSIFIER_REDECLARATION" to IssueInfo("25-4612519", 59897),
    "REDECLARATION" to IssueInfo("25-4612520", 59898),
    "EXPECTED_DECLARATION_WITH_BODY" to IssueInfo("25-4612521", 59899),
    "NESTED_CLASS_NOT_ALLOWED" to IssueInfo("25-4612522", 59900),
    "API_NOT_AVAILABLE" to IssueInfo("25-4612523", 59901),
    "NOTHING_TO_OVERRIDE" to IssueInfo("25-4612524", 59902),
    "DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE" to IssueInfo("25-4612525", 59903),
    "NONE_APPLICABLE" to IssueInfo("25-4612526", 59904),
    "CAPTURED_VAL_INITIALIZATION" to IssueInfo("25-4612528", 59906),
    "RETURN_TYPE_MISMATCH" to IssueInfo("25-4612529", 59907),
    "RECURSIVE_TYPEALIAS_EXPANSION" to IssueInfo("25-4612530", 59908),
    "DEPRECATION_ERROR" to IssueInfo("25-4612531", 59909),
    "INCOMPATIBLE_ENUM_COMPARISON_ERROR" to IssueInfo("25-4612532", 59910),
    "NO_VALUE_FOR_PARAMETER" to IssueInfo("25-4612533", 59911),
    "MANY_IMPL_MEMBER_NOT_IMPLEMENTED" to IssueInfo("25-4612534", 59912),
    "UNSUPPORTED_FEATURE" to IssueInfo("25-4612535", 59913),
    "RETURN_NOT_ALLOWED" to IssueInfo("25-4612536", 59914),
    "TOO_MANY_ARGUMENTS" to IssueInfo("25-4612537", 59915),
    "REPEATED_ANNOTATION" to IssueInfo("25-4612538", 59916),
    "UNSAFE_OPERATOR_CALL" to IssueInfo("25-4612539", 59917),
    "ACTUAL_WITHOUT_EXPECT" to IssueInfo("25-4612540", 59918),
    "UNINITIALIZED_ENUM_COMPANION" to IssueInfo("25-4612541", 59919),
    "EXPOSED_FUNCTION_RETURN_TYPE" to IssueInfo("25-4612542", 59920),
    "NULL_FOR_NONNULL_TYPE" to IssueInfo("25-4612543", 59921),
    "CANNOT_CHECK_FOR_ERASED" to IssueInfo("25-4612544", 59922),
    "ABSTRACT_MEMBER_NOT_IMPLEMENTED" to IssueInfo("25-4612545", 59923),
    "FUNCTION_EXPECTED" to IssueInfo("25-4612546", 59924),
    "VIRTUAL_MEMBER_HIDDEN" to IssueInfo("25-4612547", 59925),
    "ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED" to IssueInfo("25-4612548", 59926),
    "INVISIBLE_REFERENCE" to IssueInfo("25-4612549", 59927),
    "UPPER_BOUND_VIOLATED" to IssueInfo("25-4612550", 59928),
    "NO_THIS" to IssueInfo("25-4612552", 59929),
    "COMPONENT_FUNCTION_RETURN_TYPE_MISMATCH" to IssueInfo("25-4612553", 59930),
    "CLASS_LITERAL_LHS_NOT_A_CLASS" to IssueInfo("25-4612554", 59931),
    "AMBIGUOUS_ANONYMOUS_TYPE_INFERRED" to IssueInfo("25-4612555", 59932),
    "USAGE_IS_NOT_INLINABLE" to IssueInfo("25-4612556", 59933),
    "MUST_BE_INITIALIZED_OR_BE_ABSTRACT" to IssueInfo("25-4612557", 59934),
    "PROPERTY_WITH_NO_TYPE_NO_INITIALIZER" to IssueInfo("25-4612558", 59935),
    "ARGUMENT_PASSED_TWICE" to IssueInfo("25-4612559", 59936),
    "EXPECTED_PRIVATE_DECLARATION" to IssueInfo("25-4612560", 59937),
    "AMBIGUOUS_ACTUALS" to IssueInfo("25-4612561", 59938),
    "EXPECTED_CLASS_CONSTRUCTOR_DELEGATION_CALL" to IssueInfo("25-4612562", 59939),
    "ACTUAL_ANNOTATION_CONFLICTING_DEFAULT_ARGUMENT_VALUE" to IssueInfo("25-4612563", 59940),
    "COMPONENT_FUNCTION_MISSING" to IssueInfo("25-4612564", 59941),
    "ANNOTATION_PARAMETER_DEFAULT_VALUE_MUST_BE_CONSTANT" to IssueInfo("25-4612565", 59942),
    "OPERATOR_MODIFIER_REQUIRED" to IssueInfo("25-4612566", 59943),
    "NON_MEMBER_FUNCTION_NO_BODY" to IssueInfo("25-4612567", 59944),
    "ANONYMOUS_FUNCTION_WITH_NAME" to IssueInfo("25-4612568", 59945),
    "BREAK_OR_CONTINUE_OUTSIDE_A_LOOP" to IssueInfo("25-4612569", 59946),
    "EXPOSED_PROPERTY_TYPE" to IssueInfo("25-4612570", 59947),
    "ILLEGAL_SUSPEND_FUNCTION_CALL" to IssueInfo("25-4612571", 59948),
    "DEPRECATED_PARCELER" to IssueInfo("25-4612572", 59949),
    "ILLEGAL_ESCAPE" to IssueInfo("25-4612573", 59950),
    "NO_TYPE_ARGUMENTS_ON_RHS" to IssueInfo("25-4612574", 59951),
    "EXPOSED_PROPERTY_TYPE_IN_CONSTRUCTOR_ERROR" to IssueInfo("25-4612575", 59952),
    "NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY" to IssueInfo("25-4612576", 59953),
    "REPEATED_MODIFIER" to IssueInfo("25-4612577", 59954),
    "INCOMPATIBLE_MODIFIERS" to IssueInfo("25-4612578", 59955),
    "INAPPLICABLE_OPERATOR_MODIFIER" to IssueInfo("25-4612579", 59956),
    "VARARG_OUTSIDE_PARENTHESES" to IssueInfo("25-4612581", 59958),
    "NO_RECEIVER_ALLOWED" to IssueInfo("25-4612582", 59959),
    "INVISIBLE_SETTER" to IssueInfo("25-4612583", 59960),
    "SUPERTYPE_APPEARS_TWICE" to IssueInfo("25-4612584", 59961),
    "TYPE_VARIANCE_CONFLICT_IN_EXPANDED_TYPE" to IssueInfo("25-4612585", 59962),
    "CONFLICTING_PROJECTION_IN_TYPEALIAS_EXPANSION" to IssueInfo("25-4612586", 59963),
    "NO_COMPANION_OBJECT" to IssueInfo("25-4612587", 59964),
    "CANNOT_ALL_UNDER_IMPORT_FROM_SINGLETON" to IssueInfo("25-4612588", 59965),
    "DELEGATE_SPECIAL_FUNCTION_MISSING" to IssueInfo("25-4612589", 59966),
    "UNINITIALIZED_ENUM_ENTRY" to IssueInfo("25-4612590", 59967),
    "MUST_BE_INITIALIZED" to IssueInfo("25-4612591", 59968),
    "UNSUPPORTED_CONTEXTUAL_DECLARATION_CALL" to IssueInfo("25-4612592", 59969),
    "NULLABLE_TYPE_IN_CLASS_LITERAL_LHS" to IssueInfo("25-4612593", 59970),
    "EXPRESSION_EXPECTED_PACKAGE_FOUND" to IssueInfo("25-4612596", 59972),
    "INAPPLICABLE_LATEINIT_MODIFIER" to IssueInfo("25-4612597", 59973),
    "INAPPLICABLE_INFIX_MODIFIER" to IssueInfo("25-4612598", 59974),
    "CYCLIC_INHERITANCE_HIERARCHY" to IssueInfo("25-4612599", 59975),
    "FUNCTION_CALL_EXPECTED" to IssueInfo("25-4612600", 59976),
    "NO_ACTUAL_FOR_EXPECT" to IssueInfo("25-4612601", 59977),
    "EXPECTED_ENUM_ENTRY_WITH_BODY" to IssueInfo("25-4612602", 59978),
    "SUPERTYPE_INITIALIZED_IN_EXPECTED_CLASS" to IssueInfo("25-4612603", 59979),
    "EXPECTED_ENUM_CONSTRUCTOR" to IssueInfo("25-4612604", 59980),
    "RESOLUTION_TO_CLASSIFIER" to IssueInfo("25-4612605", 59981),
    "EXPECTED_CLASS_CONSTRUCTOR_PROPERTY_PARAMETER" to IssueInfo("25-4612606", 59982),
    "IMPLEMENTATION_BY_DELEGATION_IN_EXPECT_CLASS" to IssueInfo("25-4612607", 59983),
    "INFERRED_TYPE_VARIABLE_INTO_EMPTY_INTERSECTION_ERROR" to IssueInfo("25-4612608", 59984),
    "UNDERSCORE_USAGE_WITHOUT_BACKTICKS" to IssueInfo("25-4612609", 59985),
    "ITERATOR_MISSING" to IssueInfo("25-4612610", 59986),
    "REIFIED_TYPE_FORBIDDEN_SUBSTITUTION" to IssueInfo("25-4612611", 59987),
    "TYPE_ARGUMENTS_NOT_ALLOWED" to IssueInfo("25-4612612", 59988),
    "ELSE_MISPLACED_IN_WHEN" to IssueInfo("25-4612613", 59989),
    "FORBIDDEN_VARARG_PARAMETER_TYPE" to IssueInfo("25-4612615", 59991),
    "KCLASS_WITH_NULLABLE_TYPE_PARAMETER_IN_SIGNATURE" to IssueInfo("25-4612616", 59992),
    "INCORRECT_LEFT_COMPONENT_OF_INTERSECTION" to IssueInfo("25-4612617", 59993),
    "DATA_CLASS_WITHOUT_PARAMETERS" to IssueInfo("25-4612618", 59994),
    "EXTERNAL_ENUM_ENTRY_WITH_BODY" to IssueInfo("25-4612619", 59995),
    "INVALID_CHARACTERS" to IssueInfo("25-4612620", 59996),
    "OPT_IN_USAGE_ERROR" to IssueInfo("25-4612621", 59997),
    "OPT_IN_MARKER_CAN_ONLY_BE_USED_AS_ANNOTATION_OR_ARGUMENT_IN_OPT_IN" to IssueInfo("25-4612622", 59998),
    "NO_SET_METHOD" to IssueInfo("25-4612623", 59999),
    "TYPE_INFERENCE_ONLY_INPUT_TYPES_ERROR" to IssueInfo("25-4612625", 60001),
    "INVALID_CHARACTERS_NATIVE_ERROR" to IssueInfo("25-4612627", 60003),
    "CONTRACT_NOT_ALLOWED" to IssueInfo("25-4612628", 60004),
    "UNSAFE_INFIX_CALL" to IssueInfo("25-4612629", 60005),
    "EXPRESSION_EXPECTED" to IssueInfo("25-4612630", 60006),
    "ACCIDENTAL_OVERRIDE" to IssueInfo("25-4536964", 59380),
    "CONFLICTING_INHERITED_JVM_DECLARATIONS" to IssueInfo("25-4537013", 59428),
    "NO_CONSTRUCTOR" to IssueInfo("25-4702764", 60683),
    "SUPERTYPE_INITIALIZED_IN_INTERFACE" to IssueInfo("25-4702766", 60684),
    "DEPRECATION" to IssueInfo("25-4702763", 60682),
    //<::>knownDisappearedDiagnostics
)

val knownDisappearedDiagnostics: Map<String, IssueInfo> get() = mutableKnownDisappearedDiagnostics

private val mutableKnownIntroducedDiagnostics = mutableMapOf(
    "ARGUMENT_TYPE_MISMATCH" to IssueInfo("25-4612681", 60007),
    "INCOMPATIBLE_MATCHING" to IssueInfo("25-4612682", 60008),
    "INITIALIZER_TYPE_MISMATCH" to IssueInfo("25-4612683", 60009),
    "INAPPLICABLE_CANDIDATE" to IssueInfo("25-4612684", 60010),
    "TYPECHECKER_HAS_RUN_INTO_RECURSIVE_PROBLEM" to IssueInfo("25-4612685", 60011),
    "ASSIGNMENT_TYPE_MISMATCH" to IssueInfo("25-4612686", 60012),
    "TYPE_VARIANCE_CONFLICT_ERROR" to IssueInfo("25-4612687", 60013),
    "WRONG_MODIFIER_TARGET" to IssueInfo("25-4612688", 60014),
    "DECLARATION_CANT_BE_INLINED" to IssueInfo("25-4612689", 60015),
    "PRIVATE_CLASS_MEMBER_FROM_INLINE" to IssueInfo("25-4612690", 60016),
    "ANNOTATION_IN_WHERE_CLAUSE_ERROR" to IssueInfo("25-4612691", 60017),
    "RETURN_TYPE_MISMATCH_ON_OVERRIDE" to IssueInfo("25-4612692", 60018),
    "PARCELER_TYPE_INCOMPATIBLE" to IssueInfo("25-4612693", 60019),
    "NOARG_ON_INNER_CLASS_ERROR" to IssueInfo("25-4612694", 60020),
    "NOARG_ON_LOCAL_CLASS_ERROR" to IssueInfo("25-4612695", 60021),
    "VAR_IMPLEMENTED_BY_INHERITED_VAL_ERROR" to IssueInfo("25-4612696", 60022),
    "CONFLICTING_INHERITED_MEMBERS" to IssueInfo("25-4612697", 60023),
    "NOT_A_SUPERTYPE" to IssueInfo("25-4612698", 60024),
    "PROJECTION_IN_IMMEDIATE_ARGUMENT_TO_SUPERTYPE" to IssueInfo("25-4612699", 60025),
    "EXPOSED_TYPEALIAS_EXPANDED_TYPE" to IssueInfo("25-4612700", 60026),
    "UNRESOLVED_LABEL" to IssueInfo("25-4612701", 60027),
    "INAPPLICABLE_TARGET_ON_PROPERTY" to IssueInfo("25-4612702", 60028),
    "VAL_REASSIGNMENT_VIA_BACKING_FIELD_ERROR" to IssueInfo("25-4612703", 60029),
    "CYCLIC_CONSTRUCTOR_DELEGATION_CALL" to IssueInfo("25-4612704", 60030),
    "NO_ACTUAL_CLASS_MEMBER_FOR_EXPECTED_CLASS" to IssueInfo("25-4612705", 60031),
    "EXPECT_CLASS_AS_FUNCTION" to IssueInfo("25-4612706", 60032),
    "ABSTRACT_SUPER_CALL" to IssueInfo("25-4612707", 60033),
    "NO_GET_METHOD" to IssueInfo("25-4612708", 60034),
    "INFERENCE_UNSUCCESSFUL_FORK" to IssueInfo("25-4612709", 60035),
    "NOT_A_LOOP_LABEL" to IssueInfo("25-4612710", 60036),
    "NOT_A_FUNCTION_LABEL" to IssueInfo("25-4612711", 60037),
    "SUPERTYPE_IS_EXTENSION_FUNCTION_TYPE" to IssueInfo("25-4612712", 60038),
    "NON_PUBLIC_CALL_FROM_PUBLIC_INLINE" to IssueInfo("25-4612713", 60039),
    "PROTECTED_CALL_FROM_PUBLIC_INLINE_ERROR" to IssueInfo("25-4612714", 60040),
    "RECURSION_IN_INLINE" to IssueInfo("25-4612715", 60041),
    "PRIMARY_CONSTRUCTOR_REQUIRED_FOR_DATA_CLASS" to IssueInfo("25-4612716", 60042),
    "PROPERTY_AS_OPERATOR" to IssueInfo("25-4612717", 60043),
    "PROPERTY_INITIALIZER_NO_BACKING_FIELD" to IssueInfo("25-4612718", 60044),
    "AMBIGUOUS_ANNOTATION_ARGUMENT" to IssueInfo("25-4612719", 60045),
    "PLUGIN_ANNOTATION_AMBIGUITY" to IssueInfo("25-4612720", 60046),
    "ASSIGN_OPERATOR_AMBIGUITY" to IssueInfo("25-4612721", 60047),
    "MISSING_EXCEPTION_IN_THROWS_ON_SUSPEND" to IssueInfo("25-4612722", 60048),
    "RETURN_IN_FUNCTION_WITH_EXPRESSION_BODY" to IssueInfo("25-4612723", 60049),
    "UNRESOLVED_REFERENCE" to IssueInfo("25-4613504", 60056),
    "UNSAFE_CALL" to IssueInfo("25-4613505", 60057),
    "OVERLOAD_RESOLUTION_AMBIGUITY" to IssueInfo("25-4613506", 60058),
    "VAL_REASSIGNMENT" to IssueInfo("25-4613507", 60059),
    "UNRESOLVED_REFERENCE_WRONG_RECEIVER" to IssueInfo("25-4613508", 60060),
    "NEW_INFERENCE_NO_INFORMATION_FOR_PARAMETER" to IssueInfo("25-4613509", 60061),
    "UNINITIALIZED_VARIABLE" to IssueInfo("25-4613510", 60062),
    "USAGE_IS_NOT_INLINABLE" to IssueInfo("25-4613511", 60063),
    "NONE_APPLICABLE" to IssueInfo("25-4613513", 60064),
    "UPPER_BOUND_VIOLATED" to IssueInfo("25-4613514", 60065),
    "INFERRED_TYPE_VARIABLE_INTO_EMPTY_INTERSECTION_ERROR" to IssueInfo("25-4613515", 60066),
    "RETURN_TYPE_MISMATCH" to IssueInfo("25-4613516", 60067),
    "CONFLICTING_OVERLOADS" to IssueInfo("25-4613517", 60068),
    "CANNOT_INFER_PARAMETER_TYPE" to IssueInfo("25-4613519", 60069),
    "WRONG_ANNOTATION_TARGET" to IssueInfo("25-4613520", 60070),
    "CONFLICTING_JVM_DECLARATIONS" to IssueInfo("25-4613521", 60071),
    "UNSAFE_IMPLICIT_INVOKE_CALL" to IssueInfo("25-4613523", 60072),
    "UNSUPPORTED" to IssueInfo("25-4613524", 60073),
    "INVALID_IF_AS_EXPRESSION" to IssueInfo("25-4613525", 60074),
    "ACTUAL_WITHOUT_EXPECT" to IssueInfo("25-4613526", 60075),
    "NO_ACTUAL_FOR_EXPECT" to IssueInfo("25-4613527", 60076),
    "TYPE_MISMATCH" to IssueInfo("25-4613528", 60077),
    "PACKAGE_OR_CLASSIFIER_REDECLARATION" to IssueInfo("25-4613529", 60078),
    "ABSTRACT_MEMBER_NOT_IMPLEMENTED" to IssueInfo("25-4613530", 60079),
    "INVISIBLE_SETTER" to IssueInfo("25-4613531", 60080),
    "OPT_IN_USAGE_ERROR" to IssueInfo("25-4613532", 60081),
    "NO_ELSE_IN_WHEN" to IssueInfo("25-4613533", 60082),
    "INVISIBLE_REFERENCE" to IssueInfo("25-4613534", 60083),
    "DEPRECATION_ERROR" to IssueInfo("25-4613535", 60084),
    "NO_VALUE_FOR_PARAMETER" to IssueInfo("25-4613536", 60085),
    "AMBIGUOUS_ANONYMOUS_TYPE_INFERRED" to IssueInfo("25-4613537", 60086),
    "SMARTCAST_IMPOSSIBLE" to IssueInfo("25-4613538", 60087),
    "UNINITIALIZED_ENUM_COMPANION" to IssueInfo("25-4613539", 60088),
    "ERROR_IN_CONTRACT_DESCRIPTION" to IssueInfo("25-4613540", 60089),
    "DEPRECATED_PARCELER" to IssueInfo("25-4613541", 60090),
    "REDECLARATION" to IssueInfo("25-4613542", 60091),
    "EXPOSED_PROPERTY_TYPE_IN_CONSTRUCTOR_ERROR" to IssueInfo("25-4613543", 60092),
    "RECURSIVE_TYPEALIAS_EXPANSION" to IssueInfo("25-4613544", 60093),
    "NO_COMPANION_OBJECT" to IssueInfo("25-4613545", 60094),
    "INCOMPATIBLE_TYPES" to IssueInfo("25-4613546", 60095),
    "API_NOT_AVAILABLE" to IssueInfo("25-4613547", 60096),
    "NOTHING_TO_OVERRIDE" to IssueInfo("25-4613548", 60097),
    "DELEGATE_SPECIAL_FUNCTION_NONE_APPLICABLE" to IssueInfo("25-4613549", 60098),
    "UNINITIALIZED_ENUM_ENTRY" to IssueInfo("25-4613550", 60099),
    "MUST_BE_INITIALIZED" to IssueInfo("25-4613551", 60100),
    "TOO_MANY_ARGUMENTS" to IssueInfo("25-4613552", 60101),
    "REPEATED_ANNOTATION" to IssueInfo("25-4613555", 60102),
    "UNSAFE_OPERATOR_CALL" to IssueInfo("25-4613558", 60103),
    "FUNCTION_CALL_EXPECTED" to IssueInfo("25-4613559", 60104),
    "UNDERSCORE_USAGE_WITHOUT_BACKTICKS" to IssueInfo("25-4613560", 60105),
    "REIFIED_TYPE_FORBIDDEN_SUBSTITUTION" to IssueInfo("25-4613561", 60106),
    "OPERATOR_MODIFIER_REQUIRED" to IssueInfo("25-4613562", 60107),
    "EXTERNAL_ENUM_ENTRY_WITH_BODY" to IssueInfo("25-4613563", 60108),
    "ACCIDENTAL_OVERRIDE" to IssueInfo("25-4614433", 60114),
    "RETURN_NOT_ALLOWED" to IssueInfo("25-4702767", 60685),
    "BREAK_OR_CONTINUE_JUMPS_ACROSS_FUNCTION_BOUNDARY" to IssueInfo("25-4702768", 60686),
    "UNEXPECTED_SAFE_CALL" to IssueInfo("25-4702769", 60687),
    "SYNTAX" to IssueInfo("25-4702771", 60688),
    "DEPRECATION" to IssueInfo("25-4702813", 60689),
    //<::>knownIntroducedDiagnostics
)

val knownIntroducedDiagnostics: Map<String, IssueInfo> get() = mutableKnownIntroducedDiagnostics
