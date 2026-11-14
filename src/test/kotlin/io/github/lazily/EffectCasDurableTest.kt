package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Durable hydration of the effect-CAS core (`#lzeffectcasdurable`), ported from
 * lazily-rs `tests/effect_cas_durable.rs`. The spec requires crash recovery to
 * hydrate the retained intent, generation, cursor, logical retry time, and
 * receipts before the runtime becomes live, then reconnect at a newer generation
 * and replay the SAME effect identity. Not fixture-backed: the canonical corpus
 * pins the live transitions only.
 */
class EffectCasDurableTest {
    private fun intent(
        key: String,
        epoch: Long,
        expected: Long,
        steps: List<Long>,
    ) = EffectIntent(EffectIdentity("$key#$epoch"), epoch, key, expected, EffectPlan(steps))

    private fun EffectCasCore<String, Long, Long, Long, String>.claimed(
        key: String,
        generation: Long,
    ) = (claim(key, generation, 0).result as EffectCasClaim.Claimed).attempt

    /** A process boundary: only the durable image survives. */
    private fun restart(core: EffectCasCore<String, Long, Long, Long, String>) = EffectCasCore.hydrate(core.durableState())

    @Test
    fun `hydrate is lossless for every retained field`() {
        val core = EffectCasCore<String, Long, Long, Long, String>(1)
        core.upsertDesired(intent("doc", 1, 10, listOf(100, 200)))
        val step0 = core.claimed("doc", 1)
        core.recordApplied("doc", step0.identity, 1000, 11)
        val step1 = core.claimed("doc", 1)
        core.upsertDesired(intent("doc", 2, 11, listOf(300)))
        core.upsertDesired(intent("other", 1, 0, listOf(1)))
        val retrying = core.claimed("other", 1)
        core.recordRetryable("other", retrying.identity, "busy", 42)

        val restored = restart(core)
        assertEquals(core.durableState(), restored.durableState())
        assertEquals(core.snapshot(), restored.snapshot())
        assertEquals(1, restored.generation)
        assertEquals(
            EffectCasApplied.Superseded,
            restored.recordApplied("doc", step1.identity, 2000, 12).result,
            "a newer desired epoch stops the old plan after hydration too",
        )
        assertEquals(EffectCasClaim.Waiting(42), restored.claim("other", 1, 41).result, "the logical retry time survives")
    }

    @Test
    fun `crash replay reconnects and resumes the same identity and cursor`() {
        val writer = EffectCasCore<String, Long, Long, Long, String>(1)
        writer.upsertDesired(intent("doc", 7, 10, listOf(100, 200, 300)))
        val step0 = writer.claimed("doc", 1)
        writer.recordApplied("doc", step0.identity, 1000, 11)
        val crashed = writer.claimed("doc", 1)
        assertEquals(1, crashed.identity.step)

        val successor = restart(writer)
        assertEquals(EffectCasReconnect.Advanced(2, 1, 0), successor.reconnect(2).result)
        val resumed = successor.claimed("doc", 2)
        assertEquals(crashed.identity.effect, resumed.identity.effect, "no new identity")
        assertEquals(7, resumed.identity.epoch)
        assertEquals(1, resumed.identity.step, "the receipted step is not repeated")
        assertEquals(crashed.identity.attempt + 1, resumed.identity.attempt)
        assertEquals(11, resumed.expected, "the cursor expects the prior receipt's authority")
        assertEquals(listOf(1000L), resumed.priorReceipts.map { it.receipt })

        val before = successor.durableState()
        assertEquals(
            EffectCasApplied.StaleGeneration(2),
            successor.recordApplied("doc", crashed.identity, 2000, 12).result,
        )
        assertEquals(before, successor.durableState(), "the crashed actor's late receipt changes nothing")
    }

    @Test
    fun `a corrupt image is refused, never repaired`() {
        val core = EffectCasCore<String, Long, Long, Long, String>(3)
        core.upsertDesired(intent("doc", 1, 0, listOf(1, 2)))
        core.claimed("doc", 3)
        val image = core.durableState()
        val key = image.keys.single()
        val inflight = requireNotNull(key.inflight)

        fun refused(state: EffectCasDurableState<String, Long, Long, Long, String>) =
            assertFailsWith<EffectCasHydrateException> { EffectCasCore.hydrate(state) }.error

        assertEquals(EffectCasHydrateError.DuplicateKey, refused(image.copy(keys = listOf(key, key))))
        assertEquals(EffectCasHydrateError.KeyMismatch, refused(image.copy(keys = listOf(key.copy(key = "elsewhere")))))
        assertEquals(EffectCasHydrateError.FutureGeneration, refused(image.copy(generation = 2)))
        val wrongAttempt = inflight.copy(attempt = inflight.attempt.copy(expected = 99))
        assertEquals(EffectCasHydrateError.AttemptMismatch, refused(image.copy(keys = listOf(key.copy(inflight = wrongAttempt)))))
        val overrun = inflight.copy(retained = inflight.retained.copy(nextStep = 2))
        assertEquals(EffectCasHydrateError.CursorOutOfRange, refused(image.copy(keys = listOf(key.copy(inflight = overrun)))))
    }
}
