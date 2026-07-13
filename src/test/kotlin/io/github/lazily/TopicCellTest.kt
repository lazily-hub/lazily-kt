package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopicCellTest {
    @Test
    fun broadcastDeliveryAndCursorIsolation() {
        val ctx = Context()
        val topic = TopicCell<String>(ctx)
        assertEquals(TopicSubscribeOutcome.Created, topic.subscribe("alpha", TopicDurability.Durable))
        assertEquals(TopicSubscribeOutcome.Created, topic.subscribe("beta", TopicDurability.Durable))

        assertEquals(0, topic.publish("a"))
        assertEquals(listOf("a"), topic.readStream("alpha"))
        assertEquals(listOf("a"), topic.readStream("beta"))
        assertEquals("a", topic.advance("alpha"))
        assertTrue(topic.readStream("alpha").isEmpty())
        assertEquals(listOf("a"), topic.readStream("beta"))

        topic.publish("b")
        assertEquals(listOf("b"), topic.readStream("alpha"))
        assertEquals(listOf("a", "b"), topic.readStream("beta"))
        assertEquals("a", topic.advance("beta"))
        assertEquals(listOf("b"), topic.readStream("alpha"))
        assertEquals(listOf("b"), topic.readStream("beta"))
    }

    @Test
    fun durableRestartReplayAndSlowestCursorGc() {
        val ctx = Context()
        val topic =
            TopicCell(
                ctx,
                TopicSnapshot(
                    elements = listOf("a", "b", "c"),
                    subscriptions =
                        mapOf(
                            "fast" to TopicSubscriptionSnapshot(3, TopicDurability.Durable, true),
                            "slow" to TopicSubscriptionSnapshot(0, TopicDurability.Durable, true),
                        ),
                ),
            )

        assertTrue(topic.disconnect("slow"))
        topic.publish("d")
        val restored = TopicCell(ctx, topic.snapshot())
        assertEquals(0, restored.subscription("slow")?.cursor)
        assertEquals(TopicSubscribeOutcome.Reconnected, restored.reconnect("slow"))
        assertEquals(listOf("a", "b", "c", "d"), restored.readStream("slow"))
        assertEquals(0, restored.gc())
        assertEquals("a", restored.advance("slow"))
        assertEquals("b", restored.advance("slow"))
        assertEquals(2, restored.gc())
        assertEquals(2, restored.baseOffset())
        assertEquals(listOf("c", "d"), restored.elements())
        assertEquals(listOf("d"), restored.readStream("fast"))
        assertEquals(listOf("c", "d"), restored.readStream("slow"))
    }

    @Test
    fun ephemeralLifecycleStartsAtTailAndNeverHoldsGc() {
        val ctx = Context()
        val topic = TopicCell<String>(ctx)
        topic.publish("old")
        topic.subscribe("ephemeral", TopicDurability.Ephemeral)
        assertTrue(topic.readStream("ephemeral").isEmpty())
        topic.publish("live")
        assertEquals("live", topic.advance("ephemeral"))
        assertTrue(topic.disconnect("ephemeral"))
        assertNull(topic.subscription("ephemeral"))

        topic.publish("missed")
        topic.subscribe("ephemeral", TopicDurability.Ephemeral)
        assertTrue(topic.readStream("ephemeral").isEmpty())
        assertEquals(3, topic.gc())
        assertEquals(3, topic.baseOffset())
        assertTrue(topic.elements().isEmpty())
    }

    @Test
    fun perSubscriberReaderInvalidationIsIndependent() {
        val ctx = Context()
        val topic = TopicCell<Int>(ctx)
        topic.subscribe("alpha", TopicDurability.Durable)
        topic.subscribe("beta", TopicDurability.Durable)
        topic.publish(1)

        val alpha = topic.readerHandle("alpha")!!
        val beta = topic.readerHandle("beta")!!
        assertEquals(listOf(1), topic.readStream("alpha"))
        assertEquals(listOf(1), topic.readStream("beta"))
        assertTrue(ctx.isSet(alpha))
        assertTrue(ctx.isSet(beta))

        assertEquals(1, topic.advance("alpha"))
        assertFalse(ctx.isSet(alpha))
        assertTrue(ctx.isSet(beta))
        assertEquals(listOf(1), topic.readStream("beta"))

        topic.publish(2)
        assertFalse(ctx.isSet(alpha))
        assertFalse(ctx.isSet(beta))
    }

    @Test
    fun tailAndOfflineAdvanceAreNoOps() {
        val ctx = Context()
        val topic = TopicCell<String>(ctx)
        topic.subscribe("worker", TopicDurability.Durable)
        topic.publish("a")

        assertEquals("a", topic.advance("worker"))
        assertNull(topic.advance("worker"))
        assertEquals(1, topic.subscription("worker")?.cursor)

        assertTrue(topic.disconnect("worker"))
        topic.publish("b")
        assertTrue(topic.readStream("worker").isEmpty())
        assertNull(topic.advance("worker"))
        assertEquals(1, topic.subscription("worker")?.cursor)

        assertEquals(TopicSubscribeOutcome.Reconnected, topic.reconnect("worker"))
        assertEquals(listOf("b"), topic.readStream("worker"))
        assertEquals(1, topic.gc())
        assertEquals(1, topic.baseOffset())
        assertEquals(1, topic.subscription("worker")?.cursor)
    }

    @Test
    fun snapshotRejectsDisconnectedEphemeralSubscription() {
        assertFailsWith<IllegalArgumentException> {
            TopicCell(
                Context(),
                TopicSnapshot(
                    subscriptions =
                        mapOf(
                            "viewer" to
                                TopicSubscriptionSnapshot(
                                    cursor = 0,
                                    durability = TopicDurability.Ephemeral,
                                    connected = false,
                                ),
                        ),
                ),
            )
        }
    }
}
