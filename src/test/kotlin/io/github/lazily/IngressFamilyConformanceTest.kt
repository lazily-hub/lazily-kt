package io.github.lazily

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The transport-agnostic ingress contract (`#designimplementtransport`), replayed
 * against **every flavor lazily-kt ships** — with a ledger that is *enforced* rather
 * than advisory.
 *
 * lazily-kt ships all three: [IngressCell] / [ThreadSafeIngressCell] /
 * [AsyncIngressCell], matching the three coverage rows and the contract
 * `lazily-spec/docs/transport-ingress.md` declares REQUIRED of every binding ×
 * every flavor.
 *
 * The flavor axis lives in the **runner**, not the corpus: the fixtures carry a
 * `model` field naming the primitive and no execution-model field, and one
 * [IngressModel] interface replays the same JSON against each shell. Nothing in the
 * interface is async-coloured, which is the finding rather than an oversight — an
 * admission decision is a function of the fence, the watermark, the reorder buffer,
 * and the observed clock, so there is nothing to await and no `settle` step
 * anywhere below.
 *
 * Three things keep this suite from reporting green while testing nothing — each one
 * a failure mode this family of suites has actually shipped:
 *
 * * [`unshipped flavors are really absent`][unshippedFlavorsAreReallyAbsent] greps
 *   `src/main/kotlin` for each flavor's type declaration, in **both** directions. A
 *   ledger row marked shipped whose type does not exist fails; a type that exists
 *   while its row says unshipped fails and names the runner to extend. The ledger
 *   cannot rot, because the filesystem enforces it.
 * * Every replay returns its step count, and every flavor asserts that count is
 *   non-zero and equal to the corpus total. An absence guard proves the fixtures
 *   exist on disk; only a positive count proves this binary opened them.
 * * `invalidates` is asserted in **both** directions through a cache-validity probe
 *   per reader kind. A step expecting `false` fails if the shell invalidated anyway,
 *   so over-invalidation is as visible as under-. Receipts are asserted **per
 *   channel**, never by receipt count: a stale cache recomputes to the right count,
 *   so a count-only gate reports green.
 *
 * Every gate below was mutation-checked; the tail of this file lists the seven probes
 * and what each one turned red.
 */
class IngressFamilyConformanceTest {
    private enum class Flavor { Sync, ThreadSafe, Async }

    /**
     * Every fixture the ingress corpus ships. Named explicitly rather than globbed: a
     * fixture added to the corpus and not to this list is a *missing replay*, and the
     * coverage guard is what should notice, not a silently shorter run.
     */
    private val fixtures =
        listOf(
            "ingress_ordered_delivery.json",
            "ingress_reorder_and_duplication.json",
            "ingress_reorder_window_overflow.json",
            "ingress_disconnect_replay.json",
            "ingress_backpressure.json",
            "ingress_generation_handoff.json",
            "ingress_freshness_and_retry.json",
        )

    /** One ledger row per (primitive, flavor) pair this binding claims. */
    private data class LedgerRow(val flavor: String, val typeName: String, val shipped: Boolean)

    private val ledger =
        listOf(
            LedgerRow("single-threaded", "IngressCell", shipped = true),
            LedgerRow("thread-safe", "ThreadSafeIngressCell", shipped = true),
            LedgerRow("async", "AsyncIngressCell", shipped = true),
        )

    // -- The flavor-neutral model ------------------------------------------

    /**
     * What every ingress flavor must be able to do for the corpus to replay against
     * it. The reader-kind probes (`*IsValid`) are the whole reason this is an
     * interface rather than three copies of the runner: `invalidates` is a claim
     * about the *graph*, and only the shell can answer it.
     */
    private interface IngressModel : AutoCloseable {
        fun openScope(key: String, generation: Long)
        fun admit(envelope: IngressEnvelope<String, Long>): IngressAdmission
        fun suspendScope(key: String): ReplayRequest?
        fun reconnect(key: String, generation: Long): ReplayRequest
        fun closeScope(key: String)
        fun fail(key: String, error: IngressError)
        fun tick(now: Long)
        fun drain(key: String): Long?

        /**
         * Reactive reads; each also materializes its reader's cache, which is what
         * makes the next step's validity probe meaningful.
         */
        fun value(key: String): Long?
        fun readiness(key: String): IngressReadiness
        fun authority(key: String): IngressAuthority?
        fun retry(key: String): IngressRetry?
        fun acceptedLen(): Int
        fun droppedLen(): Int
        fun errorsLen(): Int
        fun schedule(): IngressSchedule

        /** `false` when the reader is invalidated — what `invalidates: true` means. */
        fun valueIsValid(key: String): Boolean
        fun readinessIsValid(key: String): Boolean
        fun authorityIsValid(key: String): Boolean
        fun retryIsValid(key: String): Boolean
        fun acceptedIsValid(): Boolean
        fun droppedIsValid(): Boolean
        fun errorsIsValid(): Boolean

        fun view(key: String): IngressScopeView?
    }

    private class SyncIngressModel(
        policy: IngressPolicy,
        merge: MergePolicy<Long>,
        transport: IngressTransportKind,
        pollInterval: Long,
    ) : IngressModel {
        private val ctx = Context()
        private val cell = IngressCell<String, Long>(ctx, policy, merge, transport, pollInterval)

        override fun openScope(key: String, generation: Long) = cell.open(key, generation)
        override fun admit(envelope: IngressEnvelope<String, Long>) = cell.admit(envelope)
        override fun suspendScope(key: String) = cell.suspend(key)
        override fun reconnect(key: String, generation: Long) = cell.reconnect(key, generation)
        override fun closeScope(key: String) = cell.close(key)
        override fun fail(key: String, error: IngressError) = cell.fail(key, error)
        override fun tick(now: Long) = cell.tick(now)
        override fun drain(key: String) = cell.drain(key)

        override fun value(key: String) = cell.value(key)
        override fun readiness(key: String) = cell.readiness(key)
        override fun authority(key: String) = cell.authority(key)
        override fun retry(key: String) = cell.retry(key)
        override fun acceptedLen() = cell.accepted().size
        override fun droppedLen() = cell.dropped().size
        override fun errorsLen() = cell.errors().size
        override fun schedule() = cell.schedule()

        override fun valueIsValid(key: String) = ctx.isSet(cell.readers(key).value)
        override fun readinessIsValid(key: String) = ctx.isSet(cell.readers(key).readiness)
        override fun authorityIsValid(key: String) = ctx.isSet(cell.readers(key).authority)
        override fun retryIsValid(key: String) = ctx.isSet(cell.readers(key).retry)
        override fun acceptedIsValid() = ctx.isSet(cell.acceptedHandle())
        override fun droppedIsValid() = ctx.isSet(cell.droppedHandle())
        override fun errorsIsValid() = ctx.isSet(cell.errorsHandle())

        override fun view(key: String) = cell.view(key)
        override fun close() = Unit
    }

    private class ThreadSafeIngressModel(
        policy: IngressPolicy,
        merge: MergePolicy<Long>,
        transport: IngressTransportKind,
        pollInterval: Long,
    ) : IngressModel {
        private val ctx = ThreadSafeContext()
        private val cell =
            ThreadSafeIngressCell<String, Long>(ctx, policy, merge, transport, pollInterval)

        override fun openScope(key: String, generation: Long) = cell.open(key, generation)
        override fun admit(envelope: IngressEnvelope<String, Long>) = cell.admit(envelope)
        override fun suspendScope(key: String) = cell.suspend(key)
        override fun reconnect(key: String, generation: Long) = cell.reconnect(key, generation)
        override fun closeScope(key: String) = cell.close(key)
        override fun fail(key: String, error: IngressError) = cell.fail(key, error)
        override fun tick(now: Long) = cell.tick(now)
        override fun drain(key: String) = cell.drain(key)

        override fun value(key: String) = cell.value(key)
        override fun readiness(key: String) = cell.readiness(key)
        override fun authority(key: String) = cell.authority(key)
        override fun retry(key: String) = cell.retry(key)
        override fun acceptedLen() = cell.accepted().size
        override fun droppedLen() = cell.dropped().size
        override fun errorsLen() = cell.errors().size
        override fun schedule() = cell.schedule()

        override fun valueIsValid(key: String) = ctx.isSet(cell.readers(key).value)
        override fun readinessIsValid(key: String) = ctx.isSet(cell.readers(key).readiness)
        override fun authorityIsValid(key: String) = ctx.isSet(cell.readers(key).authority)
        override fun retryIsValid(key: String) = ctx.isSet(cell.readers(key).retry)
        override fun acceptedIsValid() = ctx.isSet(cell.acceptedHandle())
        override fun droppedIsValid() = ctx.isSet(cell.droppedHandle())
        override fun errorsIsValid() = ctx.isSet(cell.errorsHandle())

        override fun view(key: String) = cell.view(key)
        override fun close() = Unit
    }

    private class AsyncIngressModel(
        policy: IngressPolicy,
        merge: MergePolicy<Long>,
        transport: IngressTransportKind,
        pollInterval: Long,
    ) : IngressModel {
        private val ctx = AsyncContext()
        private val cell =
            AsyncIngressCell<String, Long>(ctx, policy, merge, transport, pollInterval)

        override fun openScope(key: String, generation: Long) = cell.open(key, generation)
        override fun admit(envelope: IngressEnvelope<String, Long>) = cell.admit(envelope)
        override fun suspendScope(key: String) = cell.suspend(key)
        override fun reconnect(key: String, generation: Long) = cell.reconnect(key, generation)
        override fun closeScope(key: String) = cell.close(key)
        override fun fail(key: String, error: IngressError) = cell.fail(key, error)
        override fun tick(now: Long) = cell.tick(now)
        override fun drain(key: String) = cell.drain(key)

        override fun value(key: String) = cell.value(key)
        override fun readiness(key: String) = cell.readiness(key)
        override fun authority(key: String) = cell.authority(key)
        override fun retry(key: String) = cell.retry(key)
        override fun acceptedLen() = cell.accepted().size
        override fun droppedLen() = cell.dropped().size
        override fun errorsLen() = cell.errors().size
        override fun schedule() = cell.schedule()

        override fun valueIsValid(key: String) = ctx.isSet(cell.readers(key).value)
        override fun readinessIsValid(key: String) = ctx.isSet(cell.readers(key).readiness)
        override fun authorityIsValid(key: String) = ctx.isSet(cell.readers(key).authority)
        override fun retryIsValid(key: String) = ctx.isSet(cell.readers(key).retry)
        override fun acceptedIsValid() = ctx.isSet(cell.acceptedHandle())
        override fun droppedIsValid() = ctx.isSet(cell.droppedHandle())
        override fun errorsIsValid() = ctx.isSet(cell.errorsHandle())

        override fun view(key: String) = cell.view(key)
        override fun close() = ctx.close()
    }

    private fun model(
        flavor: Flavor,
        policy: IngressPolicy,
        merge: MergePolicy<Long>,
        transport: IngressTransportKind,
        pollInterval: Long,
    ): IngressModel =
        when (flavor) {
            Flavor.Sync -> SyncIngressModel(policy, merge, transport, pollInterval)
            Flavor.ThreadSafe -> ThreadSafeIngressModel(policy, merge, transport, pollInterval)
            Flavor.Async -> AsyncIngressModel(policy, merge, transport, pollInterval)
        }

    // -- Fixture decoding --------------------------------------------------

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(ConformanceFixtures.read("ingress/$name")).jsonObject

    private fun overflowOf(text: String) =
        when (text) {
            "block" -> Overflow.Block
            "drop_newest" -> Overflow.DropNewest
            "drop_oldest" -> Overflow.DropOldest
            "conflate" -> Overflow.Conflate
            "spill" -> Overflow.Spill
            else -> error("unknown overflow `$text`")
        }

    private fun transportOf(text: String) =
        when (text) {
            "event_channel" -> IngressTransportKind.EventChannel
            "rpc_triggered" -> IngressTransportKind.RpcTriggered
            "bounded_polling" -> IngressTransportKind.BoundedPolling
            else -> error("unknown transport `$text`")
        }

    private fun mergeOf(text: String): MergePolicy<Long> =
        when (text) {
            "sum" -> sum()
            "keep_latest" -> keepLatest()
            else -> error("unknown merge `$text`")
        }

    private fun errorOf(text: String) =
        when (text) {
            "transport_closed" -> IngressError.TransportClosed
            "decode_failed" -> IngressError.DecodeFailed
            "authority_lost" -> IngressError.AuthorityLost
            else -> error("unknown error `$text`")
        }

    private fun dropReasonOf(text: String) =
        when (text) {
            "stale_generation" -> IngressDropReason.StaleGeneration
            "duplicate_sequence" -> IngressDropReason.DuplicateSequence
            "duplicate_buffered" -> IngressDropReason.DuplicateBuffered
            "reorder_window_overflow" -> IngressDropReason.ReorderWindowOverflow
            "expired" -> IngressDropReason.Expired
            "backpressure" -> IngressDropReason.Backpressure
            "scope_closed" -> IngressDropReason.ScopeClosed
            else -> error("unknown drop reason `$text`")
        }

    private fun lifecycleOf(text: String) =
        when (text) {
            "opening" -> IngressLifecycle.Opening
            "live" -> IngressLifecycle.Live
            "suspended" -> IngressLifecycle.Suspended
            "closed" -> IngressLifecycle.Closed
            else -> error("unknown lifecycle `$text`")
        }

    private fun readinessOf(text: String) =
        when (text) {
            "unknown" -> IngressReadiness.Unknown
            "warming" -> IngressReadiness.Warming
            "ready" -> IngressReadiness.Ready
            "stale" -> IngressReadiness.Stale
            "suspended" -> IngressReadiness.Suspended
            "closed" -> IngressReadiness.Closed
            else -> error("unknown readiness `$text`")
        }

    private fun policyOf(raw: JsonObject) =
        IngressPolicy(
            reorderWindow = raw.getValue("reorder_window").jsonPrimitive.int,
            freshnessHorizon = raw.getValue("freshness_horizon").jsonPrimitive.long,
            highWater = raw.getValue("high_water").jsonPrimitive.long,
            overflow = overflowOf(raw.getValue("overflow").jsonPrimitive.content),
            receiptCapacity = raw.getValue("receipt_capacity").jsonPrimitive.int,
            retryBase = raw.getValue("retry_base").jsonPrimitive.long,
            retryCeiling = raw.getValue("retry_ceiling").jsonPrimitive.long,
        )

    private fun expectedAdmission(raw: JsonObject): IngressAdmission =
        when (val kind = raw.getValue("admission").jsonPrimitive.content) {
            "accepted" ->
                IngressAdmission.Accepted(raw.getValue("delivered_through").jsonPrimitive.long)
            "conflated" ->
                IngressAdmission.Conflated(raw.getValue("delivered_through").jsonPrimitive.long)
            "buffered" -> IngressAdmission.Buffered(raw.getValue("gap_from").jsonPrimitive.long)
            "generation_handoff" ->
                IngressAdmission.GenerationHandoff(
                    raw.getValue("from").jsonPrimitive.long,
                    raw.getValue("to").jsonPrimitive.long,
                )
            "dropped" ->
                IngressAdmission.Dropped(
                    dropReasonOf(raw.getValue("reason").jsonPrimitive.content),
                )
            "blocked" -> IngressAdmission.Blocked
            else -> error("unknown admission `$kind`")
        }

    private fun expectedReplay(raw: kotlinx.serialization.json.JsonElement?): ReplayRequest? {
        val obj = raw as? JsonObject ?: return null
        return ReplayRequest(
            obj.getValue("generation").jsonPrimitive.long,
            obj.getValue("from_sequence").jsonPrimitive.long,
        )
    }

    /** Cache-validity snapshot of every reader kind the fixture can speak about. */
    private data class ValiditySnapshot(
        val scopes: Map<String, List<Boolean>>,
        val receipts: List<Boolean>,
    )

    private fun snapshot(model: IngressModel, keys: List<String>) =
        ValiditySnapshot(
            keys.associateWith {
                listOf(
                    model.valueIsValid(it),
                    model.readinessIsValid(it),
                    model.authorityIsValid(it),
                    model.retryIsValid(it),
                )
            },
            listOf(model.acceptedIsValid(), model.droppedIsValid(), model.errorsIsValid()),
        )

    /**
     * Read every reader kind, so the caches are warm and the next step's validity
     * probe measures *that step's* invalidation and nothing else.
     */
    private fun materialize(model: IngressModel, keys: List<String>) {
        for (key in keys) {
            model.value(key)
            model.readiness(key)
            model.authority(key)
            model.retry(key)
        }
        model.acceptedLen()
        model.droppedLen()
        model.errorsLen()
        model.schedule()
    }

    /**
     * Replay one fixture against one flavor. Returns the number of steps executed, so
     * a caller can prove this binary actually opened the corpus.
     */
    private fun replay(name: String, flavor: Flavor): Int {
        val json = fixture(name)
        assertEquals(
            "IngressCell",
            json.getValue("model").jsonPrimitive.content,
            "$name: fixture model",
        )
        val steps = json.getValue("steps").jsonArray
        assertTrue(steps.isNotEmpty(), "$flavor $name has no steps")

        // Every key the fixture ever mentions, so a reader exists (and is probed) from
        // the first step — an absent reader would silently pass a `false` invalidation
        // expectation.
        val keys = LinkedHashSet<String>()
        for (raw in steps) {
            val step = raw.jsonObject
            (step.getValue("op").jsonObject["key"])?.let { keys += it.jsonPrimitive.content }
            step.getValue("expected").jsonObject["scopes"]?.jsonObject?.keys?.let { keys += it }
        }
        val keyList = keys.toList()

        model(
            flavor,
            policyOf(json.getValue("policy").jsonObject),
            mergeOf(json.getValue("merge").jsonPrimitive.content),
            transportOf(json.getValue("transport").jsonPrimitive.content),
            json.getValue("poll_interval").jsonPrimitive.long,
        ).use { model ->
            materialize(model, keyList)
            steps.forEachIndexed { index, raw ->
                val step = raw.jsonObject
                val op = step.getValue("op").jsonObject
                val opType = op.getValue("type").jsonPrimitive.content
                val where = "$flavor $name step $index ($opType)"
                val before = snapshot(model, keyList)

                when (opType) {
                    "admit" -> {
                        val admission =
                            model.admit(
                                IngressEnvelope(
                                    op.getValue("key").jsonPrimitive.content,
                                    op.getValue("generation").jsonPrimitive.long,
                                    op.getValue("sequence").jsonPrimitive.long,
                                    op.getValue("stamped_at").jsonPrimitive.long,
                                    op.getValue("payload").jsonPrimitive.long,
                                ),
                            )
                        step["returns"]?.let {
                            assertEquals(
                                expectedAdmission(it.jsonObject),
                                admission,
                                "$where: admission",
                            )
                        }
                    }
                    "open" ->
                        model.openScope(
                            op.getValue("key").jsonPrimitive.content,
                            op.getValue("generation").jsonPrimitive.long,
                        )
                    "drain" -> {
                        val drained = model.drain(op.getValue("key").jsonPrimitive.content)
                        step["returns"]?.let {
                            assertEquals(
                                it.jsonObject.getValue("drained").jsonPrimitive.longOrNull,
                                drained,
                                "$where: drained value",
                            )
                        }
                    }
                    "suspend" -> {
                        val request = model.suspendScope(op.getValue("key").jsonPrimitive.content)
                        step["returns"]?.let {
                            assertEquals(
                                expectedReplay(it.jsonObject["replay"]),
                                request,
                                "$where: replay request",
                            )
                        }
                    }
                    "reconnect" -> {
                        val request =
                            model.reconnect(
                                op.getValue("key").jsonPrimitive.content,
                                op.getValue("generation").jsonPrimitive.long,
                            )
                        step["returns"]?.let {
                            assertEquals(
                                expectedReplay(it.jsonObject["replay"]),
                                request,
                                "$where: replay request",
                            )
                        }
                    }
                    "close" -> model.closeScope(op.getValue("key").jsonPrimitive.content)
                    "fail" ->
                        model.fail(
                            op.getValue("key").jsonPrimitive.content,
                            errorOf(op.getValue("error").jsonPrimitive.content),
                        )
                    "tick" -> model.tick(op.getValue("now").jsonPrimitive.long)
                    else -> error("$where: unknown op `$opType`")
                }

                val after = snapshot(model, keyList)
                assertState(model, step.getValue("expected").jsonObject, where)
                assertInvalidation(step.getValue("expected").jsonObject, before, after, where)
                materialize(model, keyList)
            }
            return steps.size
        }
    }

    private fun assertState(model: IngressModel, expected: JsonObject, where: String) {
        for ((key, rawWant) in expected.getValue("scopes").jsonObject) {
            val want = rawWant.jsonObject
            val view = model.view(key) ?: error("$where: scope $key absent")
            assertEquals(
                lifecycleOf(want.getValue("lifecycle").jsonPrimitive.content),
                view.lifecycle,
                "$where: $key lifecycle",
            )
            assertEquals(
                want.getValue("generation").jsonPrimitive.long,
                view.generation,
                "$where: $key generation",
            )
            assertEquals(
                want.getValue("delivered_through").jsonPrimitive.longOrNull,
                view.deliveredThrough,
                "$where: $key watermark",
            )
            assertEquals(
                want.getValue("buffered").jsonPrimitive.int,
                view.buffered,
                "$where: $key buffered",
            )
            assertEquals(
                want.getValue("consecutive_errors").jsonPrimitive.int,
                view.consecutiveErrors,
                "$where: $key consecutive errors",
            )
            assertEquals(
                want.getValue("window").jsonPrimitive.longOrNull,
                model.value(key),
                "$where: $key window",
            )
            assertEquals(
                readinessOf(want.getValue("readiness").jsonPrimitive.content),
                model.readiness(key),
                "$where: $key readiness",
            )
            val wantAuthority = want.getValue("authority") as? JsonObject
            assertEquals(
                wantAuthority?.let {
                    IngressAuthority(
                        it.getValue("generation").jsonPrimitive.long,
                        it.getValue("delivered_through").jsonPrimitive.longOrNull,
                        it.getValue("stamped_at").jsonPrimitive.long,
                    )
                },
                model.authority(key),
                "$where: $key authority",
            )
            val wantRetry = want.getValue("retry") as? JsonObject
            assertEquals(
                wantRetry?.let {
                    IngressRetry(
                        it.getValue("attempt").jsonPrimitive.int,
                        it.getValue("backoff").jsonPrimitive.long,
                        it.getValue("resume_from").jsonPrimitive.long,
                    )
                },
                model.retry(key),
                "$where: $key retry",
            )
        }

        val receipts = expected.getValue("receipts").jsonObject
        assertEquals(
            receipts.getValue("accepted").jsonPrimitive.int,
            model.acceptedLen(),
            "$where: accepted receipts",
        )
        assertEquals(
            receipts.getValue("dropped").jsonPrimitive.int,
            model.droppedLen(),
            "$where: dropped receipts",
        )
        assertEquals(
            receipts.getValue("error").jsonPrimitive.int,
            model.errorsLen(),
            "$where: error receipts",
        )
    }

    /**
     * Assert `invalidates` in both directions. `true` means the reader's cache went
     * from valid to invalid across the op; `false` means it stayed valid.
     */
    private fun assertInvalidation(
        expected: JsonObject,
        before: ValiditySnapshot,
        after: ValiditySnapshot,
        where: String,
    ) {
        val kinds = listOf("value", "readiness", "authority", "retry")
        val want = expected.getValue("invalidates").jsonObject
        for ((key, rawScope) in want.getValue("scopes").jsonObject) {
            val wantScope = rawScope.jsonObject
            val beforeScope = requireNotNull(before.scopes[key]) { "$where: unprobed key $key" }
            val afterScope = requireNotNull(after.scopes[key]) { "$where: unprobed key $key" }
            kinds.forEachIndexed { slot, kind ->
                val invalidated = beforeScope[slot] && !afterScope[slot]
                assertEquals(
                    wantScope.getValue(kind).jsonPrimitive.boolean,
                    invalidated,
                    "$where: $key.$kind invalidation " +
                        "(was valid=${beforeScope[slot]}, now valid=${afterScope[slot]})",
                )
            }
        }
        listOf("accepted", "dropped", "error").forEachIndexed { slot, channel ->
            val invalidated = before.receipts[slot] && !after.receipts[slot]
            assertEquals(
                want.getValue("receipts").jsonObject.getValue(channel).jsonPrimitive.boolean,
                invalidated,
                "$where: receipts.$channel invalidation",
            )
        }
    }

    private fun expectedStepTotal(): Int =
        fixtures.sumOf { fixture(it).getValue("steps").jsonArray.size }

    // -- The gates ---------------------------------------------------------

    @Test
    fun `corpus is present and non-trivial`() {
        ConformanceFixtures.requireRoot()
        for (name in fixtures) {
            assertTrue(
                Files.exists(ConformanceFixtures.path("ingress/$name")),
                "missing canonical ingress fixture $name",
            )
        }
        val total = expectedStepTotal()
        assertTrue(
            total >= 30,
            "the ingress corpus replays only $total steps; that is not the named schedule set",
        )
    }

    @Test
    fun `every flavor replays the whole ingress corpus`() {
        val total = expectedStepTotal()
        for (flavor in Flavor.entries) {
            val steps = fixtures.sumOf { replay(it, flavor) }
            assertTrue(steps > 0, "$flavor replayed zero ingress steps")
            assertEquals(total, steps, "every corpus step must run against $flavor")
        }
    }

    /** The ledger cannot rot: the filesystem enforces it, in both directions. */
    @Test
    fun unshippedFlavorsAreReallyAbsent() {
        val sources = StringBuilder()
        Files.walk(Path.of("src/main/kotlin")).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }
                .forEach { sources.append(Files.readString(it)).append('\n') }
        }
        val text = sources.toString()
        for (row in ledger) {
            // `class X<` — the declaration, not a KDoc mention.
            val defined = text.contains("class ${row.typeName}<")
            assertEquals(
                row.shipped,
                defined,
                "ledger row `${row.flavor}` claims shipped=${row.shipped} but " +
                    "`class ${row.typeName}` defined=$defined; fix the ledger or extend " +
                    "IngressFamilyConformanceTest",
            )
        }
    }

    @Test
    fun `ledger is not all skips`() {
        assertEquals(3, ledger.size, "one row per flavor this family defines")
        assertEquals(3, Flavor.entries.size, "one runner flavor per ledger row")
        assertTrue(
            ledger.any { it.shipped },
            "a ledger of nothing-shipped is not coverage",
        )
    }

    /**
     * The corpus asserts negative invalidation, so the probe itself must be able to
     * fail. This pins the probe on every flavor: reading warms the cache, an op that
     * dirties the reader clears it, and one that does not leaves it warm.
     */
    @Test
    fun `the invalidation probe discriminates on every flavor`() {
        for (flavor in Flavor.entries) {
            model(flavor, IngressPolicy(), sum(), IngressTransportKind.EventChannel, 25).use {
                val key = "alpha"
                it.value(key)
                assertTrue(it.valueIsValid(key), "$flavor: reading warms the cache")

                it.admit(IngressEnvelope(key, 1, 0, 0, 1))
                assertFalse(
                    it.valueIsValid(key),
                    "$flavor: a delivery must invalidate the value reader",
                )

                it.value(key)
                it.admit(IngressEnvelope(key, 1, 5, 0, 1))
                assertTrue(
                    it.valueIsValid(key),
                    "$flavor: a buffered envelope must NOT invalidate the value reader",
                )
            }
        }
    }

    /**
     * A stale cache recomputes to the right receipt *count*, so the count-only gate
     * that this suite deliberately does not rely on would report green. Pin that the
     * per-channel probe discriminates: a fenced drop clears `dropped` only, and an
     * error clears `error` only.
     */
    @Test
    fun `receipt channels invalidate independently on every flavor`() {
        for (flavor in Flavor.entries) {
            model(flavor, IngressPolicy(), sum(), IngressTransportKind.EventChannel, 25).use {
                it.admit(IngressEnvelope("alpha", 2, 0, 0, 1))
                it.acceptedLen(); it.droppedLen(); it.errorsLen()

                it.admit(IngressEnvelope("alpha", 1, 0, 0, 9))
                assertTrue(it.acceptedIsValid(), "$flavor: a drop must not clear accepted")
                assertFalse(it.droppedIsValid(), "$flavor: a drop must clear dropped")
                assertTrue(it.errorsIsValid(), "$flavor: a drop must not clear error")

                it.acceptedLen(); it.droppedLen(); it.errorsLen()
                it.fail("alpha", IngressError.DecodeFailed)
                assertTrue(it.acceptedIsValid(), "$flavor: an error must not clear accepted")
                assertTrue(it.droppedIsValid(), "$flavor: an error must not clear dropped")
                assertFalse(it.errorsIsValid(), "$flavor: an error must clear error")
            }
        }
    }
}

// Mutation-check record (`#designimplementtransport`). Each deliberate defect was
// introduced, `make check` run, and the defect reverted with an mtime bump — a
// restore that preserves mtime lets Gradle reuse the MUTATED class files and reports
// a false green. All seven were killed; see the report in the session log.
//
// * fence checked after dedupe → `ingress_generation_handoff` fails (reports
//   `duplicate_sequence` where the corpus expects `stale_generation`).
// * handoff keeps the superseded window → `ingress_generation_handoff` fails on the
//   window value.
// * `Buffered` marks every reader dirty → the `invalidates: false` steps in
//   `ingress_reorder_and_duplication` fail, in all three flavors, and the probe
//   discriminator fails too.
// * `tick` marks readiness unconditionally → `ingress_freshness_and_retry` fails on
//   the in-horizon tick.
// * `Block` advances the watermark → `ingress_backpressure`'s final step reports a
//   duplicate instead of an accept.
// * the thread-safe `apply` invalidates one root at a time instead of handing every
//   root to one `invalidateSlots` → the frontier-walk gate in `IngressTest` fails
//   (three effect runs, one of them `new value, old authority`, for one admission).
// * the error-receipt channel is never cleared → the replay fails on
//   `invalidates.receipts.error`. This one is the reason `invalidates` is asserted
//   per channel rather than by receipt COUNT: a stale cache recomputes to the right
//   count, so a count-only gate would have called it green.
