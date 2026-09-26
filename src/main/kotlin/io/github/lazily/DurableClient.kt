package io.github.lazily

const val DURABLE_CLIENT_PROTOCOL_VERSION: Long = 1

enum class DurableCapabilityTier { Core, Client, DurableHost, DistributedHost, AcceleratedHost }
data class DurableTierDeclaration(
    val core: Boolean = true,
    val client: Boolean = true,
    val durableHost: Boolean = false,
    val distributedHost: Boolean = false,
    val acceleratedHost: Boolean = false,
)

/** Exact durable-envelope-v1 shape; owner authority and broker metadata are excluded. */
data class DurableEnvelope(
    val protocolVersion: Long = DURABLE_CLIENT_PROTOCOL_VERSION,
    val messageId: String,
    val schemaVersion: Long,
    val codecVersion: Long,
    val payload: ByteArray,
) {
    fun validate(): EnvelopeValidation =
        when {
            protocolVersion != DURABLE_CLIENT_PROTOCOL_VERSION -> EnvelopeValidation.UnsupportedProtocolVersion
            messageId.isEmpty() -> EnvelopeValidation.InvalidMessageId
            schemaVersion !in 1..UInt.MAX_VALUE.toLong() -> EnvelopeValidation.InvalidSchemaVersion
            codecVersion !in 1..UInt.MAX_VALUE.toLong() -> EnvelopeValidation.InvalidCodecVersion
            else -> EnvelopeValidation.Accepted
        }

    fun sameContent(other: DurableEnvelope): Boolean =
        protocolVersion == other.protocolVersion &&
            schemaVersion == other.schemaVersion &&
            codecVersion == other.codecVersion &&
            payload.contentEquals(other.payload)

    override fun equals(other: Any?): Boolean =
        other is DurableEnvelope && messageId == other.messageId && sameContent(other)

    override fun hashCode(): Int =
        31 * (
            31 * (
                31 * (31 * protocolVersion.hashCode() + messageId.hashCode()) +
                    schemaVersion.hashCode()
                ) + codecVersion.hashCode()
            ) + payload.contentHashCode()
}

enum class EnvelopeValidation {
    Accepted,
    UnsupportedProtocolVersion,
    InvalidMessageId,
    InvalidSchemaVersion,
    InvalidCodecVersion,
}

enum class DeliveryClassification { First, Duplicate, Conflict }
class DurableDeduplicator {
    private val seen = mutableMapOf<String, DurableEnvelope>()
    fun classify(envelope: DurableEnvelope): DeliveryClassification {
        val prior = seen[envelope.messageId]
        if (prior == null) {
            seen[envelope.messageId] = envelope.copy(payload = envelope.payload.copyOf())
            return DeliveryClassification.First
        }
        return if (prior.sameContent(envelope)) DeliveryClassification.Duplicate else DeliveryClassification.Conflict
    }
}

class DurableObservationOrder {
    private val observed = mutableListOf<String>()
    fun observe(envelope: DurableEnvelope) {
        observed += envelope.messageId
    }
    val messageIds: List<String> get() = observed.toList()
    val ownerOrderInferred: Boolean get() = false
}

/** NATS PubAck; never evidence of a durable-owner commit. */
data class BrokerPubAck(val stream: String, val sequence: Long, val duplicate: Boolean = false)

enum class DurableReceiptOutcome { Committed, Duplicate, Conflict, Rejected }
data class DurableHostReceipt(
    val protocolVersion: Long = DURABLE_CLIENT_PROTOCOL_VERSION,
    val receiptId: String,
    val messageId: String,
    val outcome: DurableReceiptOutcome,
    val ownerPosition: Long,
) {
    val transportAckEquivalent: Boolean get() = false
}

enum class ProjectionCompleteness { CompleteHistory, LatestStateOnly }
data class ProjectionFingerprint(
    val projectionId: String,
    val sourcePosition: Long,
    val fingerprint: String,
    val completeness: ProjectionCompleteness = ProjectionCompleteness.CompleteHistory,
) {
    fun equivalentTo(other: ProjectionFingerprint): Boolean =
        projectionId == other.projectionId &&
            sourcePosition == other.sourcePosition &&
            fingerprint == other.fingerprint &&
            completeness == other.completeness
    val mayAuthorizeTransition: Boolean get() = false
}

enum class ProjectionDeliveryClassification { Buffered, Applied, Duplicate, Conflict }

class AdvisoryProjectionOrder {
    private var appliedThrough = 0L
    private val pending = mutableMapOf<Long, String>()
    private val applied = mutableMapOf<Long, String>()
    private val appliedLog = mutableListOf<Long>()

    fun observe(sourcePosition: Long, fingerprint: String): ProjectionDeliveryClassification {
        applied[sourcePosition]?.let {
            return if (it == fingerprint) ProjectionDeliveryClassification.Duplicate else ProjectionDeliveryClassification.Conflict
        }
        pending[sourcePosition]?.let {
            return if (it == fingerprint) ProjectionDeliveryClassification.Duplicate else ProjectionDeliveryClassification.Conflict
        }
        if (sourcePosition > appliedThrough + 1) {
            pending[sourcePosition] = fingerprint
            return ProjectionDeliveryClassification.Buffered
        }
        if (sourcePosition <= appliedThrough) return ProjectionDeliveryClassification.Conflict
        applyOne(sourcePosition, fingerprint)
        while (true) {
            val nextPosition = appliedThrough + 1
            val next = pending.remove(nextPosition) ?: break
            applyOne(nextPosition, next)
        }
        return ProjectionDeliveryClassification.Applied
    }

    private fun applyOne(position: Long, fingerprint: String) {
        appliedThrough = position
        applied[position] = fingerprint
        appliedLog += position
    }

    val appliedPositions: List<Long> get() = appliedLog.toList()
    val brokerOrderAuthoritative: Boolean get() = false
    val mayAuthorizeTransition: Boolean get() = false
}

interface NatsDurableClientTransport {
    fun publish(subject: String, envelope: DurableEnvelope): BrokerPubAck
    fun subscribe(subject: String, handler: (DurableEnvelope) -> Unit): AutoCloseable
}

data class DurableDecodeResult<T : Any>(
    val validation: EnvelopeValidation,
    val classification: DeliveryClassification?,
    val value: T?,
)

/** Typed codec facade over a host-injected NATS-compatible transport. */
class DurableClient<TIngress : Any, TProjection : Any>(
    private val transport: NatsDurableClientTransport,
    private val encode: (TIngress) -> ByteArray,
    private val decode: (ByteArray) -> TProjection,
) {
    val deduplicator = DurableDeduplicator()
    val observationOrder = DurableObservationOrder()
    val mayAuthorizeTransition: Boolean get() = false

    fun publish(
        subject: String,
        messageId: String,
        schemaVersion: Long,
        codecVersion: Long,
        value: TIngress,
    ): BrokerPubAck {
        val envelope = DurableEnvelope(
            messageId = messageId,
            schemaVersion = schemaVersion,
            codecVersion = codecVersion,
            payload = encode(value),
        )
        require(envelope.validate() == EnvelopeValidation.Accepted) { "invalid durable envelope" }
        return transport.publish(subject, envelope)
    }

    /** Validates the envelope before invoking the payload decoder. */
    fun decode(envelope: DurableEnvelope): DurableDecodeResult<TProjection> {
        val validation = envelope.validate()
        if (validation != EnvelopeValidation.Accepted) return DurableDecodeResult(validation, null, null)
        observationOrder.observe(envelope)
        val classification = deduplicator.classify(envelope)
        if (classification != DeliveryClassification.First) {
            return DurableDecodeResult(validation, classification, null)
        }
        return DurableDecodeResult(EnvelopeValidation.Accepted, classification, decode(envelope.payload))
    }

    fun subscribe(subject: String): AutoCloseable = transport.subscribe(subject) { decode(it) }
}
