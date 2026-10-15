package io.github.lazily

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

private val reportJson = Json { prettyPrint = true }

private fun requiredEnvironment(name: String): String =
    requireNotNull(System.getenv(name)?.takeIf(String::isNotEmpty)) { "$name is required" }

private fun readObject(path: Path): JsonObject =
    reportJson.parseToJsonElement(Files.readString(path)).jsonObject

private fun strings(values: Iterable<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

private fun JsonObject.requiredString(name: String): String =
    requireNotNull(this[name]) { "missing $name" }.jsonPrimitive.content

private fun JsonObject.requiredArray(name: String): JsonArray =
    requireNotNull(this[name]) { "missing $name" }.jsonArray

private fun jsonPointerToken(value: String): String = value.replace("~", "~0").replace("/", "~1")

private val negativeControlFamilies =
    listOf(
        "cleanup_order",
        "computes_of",
        "dependencies_of",
        "dependents_of",
        "error",
        "observed_by",
        "observed_count",
        "read",
        "readable",
        "scope_owned_count",
        "value",
    )

/**
 * Shell-free Core evidence entrypoint used by `scripts/core-conformance.json`.
 *
 * It runs the production reactive-graph replay and its negative mutation probes
 * in-process. Only after both return does it write a report, so a failed or
 * cached replay cannot leave fresh-looking qualification evidence behind.
 */
fun main() {
    val bindingId = requiredEnvironment("LAZILY_CORE_BINDING_ID")
    val profilePath = Path.of(requiredEnvironment("LAZILY_CORE_PROFILE_PATH")).toAbsolutePath().normalize()
    val reportPath = Path.of(requiredEnvironment("LAZILY_CORE_REPORT_PATH")).toAbsolutePath().normalize()
    val reportsRoot = Path.of(requiredEnvironment("LAZILY_CORE_REPORTS_ROOT")).toAbsolutePath().normalize()
    val specRoot = Path.of(requiredEnvironment("LAZILY_CORE_SPEC_ROOT")).toAbsolutePath().normalize()

    require(bindingId == "kotlin") { "Core report binding must be kotlin, got $bindingId" }
    require(reportPath.startsWith(reportsRoot)) { "LAZILY_CORE_REPORT_PATH must be beneath LAZILY_CORE_REPORTS_ROOT" }

    val profile = readObject(profilePath)
    val profileId = profile.requiredString("profile_id")
    val binding =
        profile.requiredArray("bindings")
            .map(JsonElement::jsonObject)
            .single { it.requiredString("id") == bindingId }
    val requiredPaths = binding.requiredArray("required_fixtures").map { it.jsonPrimitive.content }
    val fixtureDefinitions =
        profile.requiredArray("required_fixtures")
            .map(JsonElement::jsonObject)
            .associateBy { it.requiredString("path") }

    val declaration = readObject(Path.of("scripts/core-conformance.json").toAbsolutePath().normalize())
    require(declaration.requiredString("profile_id") == profileId) { "declaration profile_id drifted" }
    require(declaration.requiredString("binding_id") == bindingId) { "declaration binding_id drifted" }
    require(declaration.requiredArray("fixtureless_claims").isEmpty()) {
        "Kotlin must not claim the C ABI without building a native export and invoking its symbol"
    }

    val runner = ReactiveGraphConformanceTest()
    runner.executableStepExpectationFamiliesRejectFixtureValueMutations()
    runner.reactiveGraphConformance()

    val observedCoreSkips =
        runner.verifiedRuntimeSkipFixtures()
            .filterKeys { "reactive-graph/$it" in requiredPaths }
            .keys
            .map { "reactive-graph/$it" }
            .toSet()
    val declaredSkips = declaration.requiredArray("runtime_skips").map(JsonElement::jsonObject)
    val declaredSkipFixtures = declaredSkips.map { it.requiredString("fixture") }.toSet()
    require(observedCoreSkips == declaredSkipFixtures) {
        "Core runtime skip declaration differs from the skip set proven by the replay: " +
            "observed=$observedCoreSkips declared=$declaredSkipFixtures"
    }
    declaredSkips.forEach { skip ->
        require(skip.requiredString("scenario") == "*") {
            "a whole-fixture Kotlin Core skip must use scenario=*"
        }
    }

    val routesByFixture =
        readObject(specRoot.resolve("schemas/assertion-blocks.json"))
            .requiredArray("x-fixture-routes")
            .map(JsonElement::jsonObject)
            .filter { it.requiredString("fixture") in requiredPaths }
            .groupBy({ it.requiredString("fixture") }, { it.requiredString("pointer") })

    val evidenceRelativePath = "$bindingId/assertion-evidence.json"
    val evidencePath = reportsRoot.resolve(evidenceRelativePath)
    val claims = linkedMapOf<String, JsonElement>()
    for (fixture in requiredPaths.filterNot(observedCoreSkips::contains)) {
        for (route in routesByFixture[fixture].orEmpty()) {
            val key = "$fixture#$route"
            claims[key] =
                JsonObject(
                    linkedMapOf(
                        "status" to JsonPrimitive("consumed-with-family-negative-controls"),
                        "consumed" to JsonPrimitive(true),
                        "fixture" to JsonPrimitive(fixture),
                        "assertion_block" to JsonPrimitive(route),
                        "execution_flavors" to
                            strings(listOf("single_threaded", "thread_safe", "async")),
                        "evidence" to
                            JsonPrimitive(
                                if (route == "/expected") {
                                    "The exact fixture-level expected block was consumed and compared for " +
                                        "Context, ThreadSafeContext, and AsyncContext. Its comparisons share the " +
                                        "production expectation-key evaluator whose eleven representative family " +
                                        "mutations were actively rejected; no per-route mutation is claimed."
                                } else {
                                    "The exact step expectation-site closure was consumed for Context, " +
                                        "ThreadSafeContext, and AsyncContext. Eleven representative mutations, " +
                                        "one per executable expectation-key family in the shared production " +
                                        "evaluator, were actively rejected; no per-route mutation is claimed."
                                },
                            ),
                    ),
                )
        }
    }
    val evidence =
        JsonObject(
            linkedMapOf(
                "schema_version" to JsonPrimitive(1),
                "profile_id" to JsonPrimitive(profileId),
                "binding_id" to JsonPrimitive(bindingId),
                "test" to
                    JsonPrimitive(
                        "ReactiveGraphConformanceTest.reactiveGraphConformance + " +
                            "executableStepExpectationFamiliesRejectFixtureValueMutations",
                    ),
                "negative_control_scope" to JsonPrimitive("shared production evaluator expectation-key families"),
                "negative_control_families" to strings(negativeControlFamilies),
                "claims" to JsonObject(claims),
            ),
        )
    Files.createDirectories(evidencePath.parent)
    Files.writeString(evidencePath, reportJson.encodeToString(JsonElement.serializer(), evidence) + "\n")

    val fixtures =
        requiredPaths.map { path ->
            val definition = requireNotNull(fixtureDefinitions[path]) { "profile omits fixture definition $path" }
            val skipped = path in observedCoreSkips
            val blocks =
                if (skipped) {
                    emptyList()
                } else {
                    routesByFixture[path].orEmpty().map { route ->
                        val claimKey = "$path#$route"
                        JsonObject(
                            linkedMapOf(
                                "id" to JsonPrimitive(route),
                                "consumed" to JsonPrimitive(true),
                                "discriminability_evidence" to
                                    JsonPrimitive(
                                        "$evidenceRelativePath#/claims/${jsonPointerToken(claimKey)}",
                                    ),
                            ),
                        )
                    }
                }
            JsonObject(
                linkedMapOf(
                    "path" to JsonPrimitive(path),
                    "scenario_ids" to definition.requiredArray("scenario_ids"),
                    "proof_classes" to definition.requiredArray("proof_classes"),
                    "assertion_blocks" to JsonArray(blocks),
                    "status" to JsonPrimitive(if (skipped) "skipped" else "passed"),
                ),
            )
        }

    val report =
        JsonObject(
            linkedMapOf(
                "schema_version" to JsonPrimitive(1),
                "profile_id" to JsonPrimitive(profileId),
                "binding_id" to JsonPrimitive(bindingId),
                "fixture_corpus_digest" to
                    JsonPrimitive(
                        requireNotNull(profile["fixture_corpus"]) { "missing fixture_corpus" }
                            .jsonObject
                            .requiredString("digest"),
                    ),
                "fixtures" to JsonArray(fixtures),
                "runtime_skips" to declaration.requiredArray("runtime_skips"),
                "fixtureless_claims" to JsonArray(emptyList()),
            ),
        )
    Files.createDirectories(reportPath.parent)
    Files.writeString(reportPath, reportJson.encodeToString(JsonElement.serializer(), report) + "\n")
}
