package ai.jetbrains.koog.chathistory

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.CreateEventRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.Event
import aws.sdk.kotlin.services.bedrockagentcore.model.ListEventsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.PayloadType
import aws.sdk.kotlin.services.bedrockagentcore.model.Role
import aws.smithy.kotlin.runtime.SdkBaseException
import aws.smithy.kotlin.runtime.time.Instant
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.ExperimentalTime

/**
 * A [ChatHistoryProvider] implementation backed by Amazon Bedrock AgentCore Memory.
 *
 * This provider stores and retrieves **conversational** history only ([Message.User] and
 * [Message.Assistant]) using the AgentCore `createEvent` and `listEvents` APIs.
 * Non-conversational message types (System, Tool, Reasoning, attachments) are intentionally
 * outside the scope of this provider and are silently filtered out.
 *
 * Key behaviors:
 * - **Suffix-overlap delta tracking**: [store] filters incoming messages to conversational types,
 *   fetches the full persisted history, and finds the maximum overlap between the tail of
 *   persisted messages and the head of incoming messages. Only the non-overlapping suffix
 *   of incoming messages is saved. This correctly handles bounded `windowSize` where the
 *   in-memory list is a recent tail of the conversation plus new unsaved messages.
 * - **eventId-priority matching**: When matching incoming messages against persisted ones,
 *   messages with an `agentcore.eventId` in metadata are matched by eventId. Messages
 *   without eventId fall back to (role, content) matching.
 * - **Full-history loading by default**: [load] fetches all events (paginated) and returns them
 *   in chronological order. When [loadAllEvents] is `false`, only the most recent event is
 *   returned.
 * - **Loaded messages carry eventId**: Messages returned by [load] have the AgentCore
 *   eventId attached in their metadata, and use the event's original timestamp.
 * - **Configurable non-conversational handling**: controlled by [ignoreUnknownRoles].
 * - **Plain text only**: Only conversational messages with plain-text content are supported.
 *   Messages with attachments or non-text parts are rejected or skipped based on [ignoreUnknownRoles].
 *
 * @param client The Bedrock AgentCore client used for API calls.
 * @param memoryId The AgentCore memory identifier (must not be blank).
 * @param defaultSession Session ID used when conversationId has no session component.
 * @param pageSize Maximum number of events per page when listing events.
 * @param totalEventsLimit Optional cap on the total number of events to fetch.
 * This limit is **not** applied during [store] reconciliation,
 *   which always fetches the full persisted history for correct delta detection.
 * @param ignoreUnknownRoles If `true`, non-conversational message/role types are silently skipped.
 *   If `false`, they cause an [IllegalStateException].
 * @throws AgentcoreMemoryException.ConfigurationException if [memoryId] is blank.
 */
public class AgentcoreChatHistoryProvider(
    public val client: BedrockAgentCoreClient,
    public val memoryId: String,
    defaultSession: String = AgentcoreConversationIdParser.DEFAULT_SESSION,
    public val pageSize: Int = DEFAULT_PAGE_SIZE,
    public val totalEventsLimit: Int? = null,
    public val ignoreUnknownRoles: Boolean = true,
) : ChatHistoryProvider {

    private val conversationIdParser = AgentcoreConversationIdParser(defaultSession)

    private val logger = LoggerFactory.getLogger("AgentcoreChatHistoryProvider")

    init {
        if (memoryId.isBlank()) {
            throw AgentcoreMemoryException.ConfigurationException("memoryId cannot be null or empty")
        }
    }

    override suspend fun store(
        conversationId: String,
        messages: List<Message>
    ) {
        val (actorId, sessionId) = conversationIdParser.parse(conversationId)

        if (messages.isEmpty()) return

        // Filter incoming messages to conversational types and build HistoryEntry list.
        val incomingEntries = messages.mapNotNull { msg ->
            AgentcoreMessageConverter.messageToPayload(msg, ignoreUnknownRoles)?.let { payload ->
                HistoryEntry(
                    role = when (msg) {
                        is Message.User -> Role.User.value
                        is Message.Assistant -> Role.Assistant.value
                        else -> msg::class.simpleName ?: "UNKNOWN"
                    },
                    content = msg.content,
                    eventId = AgentcoreMessageConverter.getEventId(msg),
                    payload = payload
                )
            }
        }
        if (incomingEntries.isEmpty()) return

        // Validate ordering: eventId-bearing messages must come before non-eventId messages.
        validateIncomingOrdering(incomingEntries)

        try {
            // Fetch full persisted history (ignoring totalEventsLimit for correct delta detection)
            val existingEvents = fetchAllEventsForStore(actorId, sessionId)
            val persistedEntries = eventsToHistoryEntries(existingEvents)

            // Find suffix-prefix overlap: max k where last k persisted == first k incoming
            val overlap = suffixPrefixOverlap(persistedEntries, incomingEntries)

            // If overlap is 0 but incoming contains eventId-bearing messages, the local
            // and remote histories disagree — saving everything would duplicate persisted messages.
            if (overlap == 0 && persistedEntries.isNotEmpty() && incomingEntries.any { it.eventId != null }) {
                throw AgentcoreMemoryException.StorageException(
                    "Zero overlap between persisted history (${persistedEntries.size} entries) " +
                            "and incoming window that contains eventId-bearing messages. " +
                            "This indicates local and remote histories have diverged."
                )
            }

            val deltaPayloads = incomingEntries.drop(overlap).mapNotNull { it.payload }
            if (deltaPayloads.isEmpty()) return

            val request = CreateEventRequest {
                clientToken = UUID.randomUUID().toString()
                this.memoryId = this@AgentcoreChatHistoryProvider.memoryId
                this.actorId = actorId
                this.sessionId = sessionId
                payload = deltaPayloads
                eventTimestamp = Instant.now()
            }

            logger.info("Sending request payload $deltaPayloads")
            val response = client.createEvent(request)
            logger.info("Created event ${response.event}")
        } catch (e: AgentcoreMemoryException.StorageException) {
            throw e
        } catch (e: SdkBaseException) {
            throw AgentcoreMemoryException.StorageException(
                "Failed to save messages for conversation: $conversationId",
                e
            )
        }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun load(conversationId: String): List<Message> {
        val (actorId, sessionId) = conversationIdParser.parse(conversationId)

        val events: List<Event> = fetchAllEventsForLoad(actorId, sessionId)

        logger.info("Loaded ${events.flatMap { it.payload }} payloads")

        return eventsToMessages(events)
    }

    private fun eventsToMessages(events: List<Event>): List<Message> {
        return events.flatMap { event ->
            val eventId = event.eventId
            val eventTimestamp = smithyInstantToKotlin(event.eventTimestamp)
            event.payload.mapNotNull { payload ->
                when (payload) {
                    is PayloadType.Conversational -> {
                        AgentcoreMessageConverter.conversationalToMessage(
                            payload.value,
                            eventId = eventId,
                            timestamp = eventTimestamp,
                            ignoreUnknownRoles = ignoreUnknownRoles
                        )
                    }

                    else -> {
                        if (ignoreUnknownRoles) {
                            null
                        } else {
                            throw IllegalStateException(
                                "Unsupported payload type: ${payload::class.simpleName}"
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Converts persisted events to [HistoryEntry] list in chronological order for overlap comparison.
     */
    private fun eventsToHistoryEntries(events: List<Event>): List<HistoryEntry> {
        return events.flatMap { event ->
            val eventId = event.eventId
            event.payload.mapNotNull { payload ->
                when (payload) {
                    is PayloadType.Conversational -> {
                        val role = payload.value.role.value
                        val content =
                            (payload.value.content as? aws.sdk.kotlin.services.bedrockagentcore.model.Content.Text)?.value
                        if (role.isBlank() || content.isNullOrBlank()) {
                            if (ignoreUnknownRoles) {
                                return@mapNotNull null
                            } else {
                                throw IllegalStateException(
                                    "Malformed conversational payload: role=$role, content type=${payload.value.content?.let { it::class.simpleName }}"
                                )
                            }
                        }
                        HistoryEntry(role, content, eventId)
                    }

                    else -> {
                        if (ignoreUnknownRoles) {
                            null
                        } else {
                            throw IllegalStateException(
                                "Unsupported payload type: ${payload::class.simpleName}"
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Validates that incoming entries have correct ordering: all messages with eventId
     * must appear before any message without eventId. A violation indicates an inconsistent
     * local history.
     */
    private fun validateIncomingOrdering(entries: List<HistoryEntry>) {
        var seenWithoutEventId = false
        for (entry in entries) {
            if (entry.eventId == null) {
                seenWithoutEventId = true
            } else if (seenWithoutEventId) {
                throw AgentcoreMemoryException.StorageException(
                    "Inconsistent incoming history: message with eventId '${entry.eventId}' " +
                            "appears after a message without eventId. " +
                            "Persisted messages must precede new unsaved messages."
                )
            }
        }
    }

    /**
     * Matches an incoming entry against a persisted entry.
     * If the incoming entry has an eventId, matches by eventId + role + content
     * (because multiple payloads in the same AgentCore event share the same eventId).
     * Otherwise, falls back to (role, content) matching.
     */
    private fun matches(incoming: HistoryEntry, persisted: HistoryEntry): Boolean {
        val incomingEventId = incoming.eventId
        return if (incomingEventId != null) {
            // Match by eventId + role + content because multiple payloads in the same
            // AgentCore event share the same eventId.
            incomingEventId == persisted.eventId && incoming.role == persisted.role && incoming.content == persisted.content
        } else {
            incoming.role == persisted.role && incoming.content == persisted.content
        }
    }

    /**
     * Finds the maximum k such that the last k entries of [persisted] match
     * the first k entries of [incoming]. This is the correct overlap model
     * for bounded windowSize where the in-memory list is a suffix of the conversation.
     */
    private fun suffixPrefixOverlap(
        persisted: List<HistoryEntry>,
        incoming: List<HistoryEntry>
    ): Int {
        val max = minOf(persisted.size, incoming.size)
        for (k in max downTo 0) {
            val persistedTail = persisted.takeLast(k)
            val incomingHead = incoming.take(k)
            if (persistedTail.indices.all { i -> matches(incomingHead[i], persistedTail[i]) }) {
                return k
            }
        }
        return 0
    }

    /**
     * Fetches all events for store reconciliation, ignoring [totalEventsLimit].
     * This ensures delta detection is always sound.
     */
    private suspend fun fetchAllEventsForStore(actorId: String, sessionId: String): List<Event> {
        return fetchEvents(actorId, sessionId, eventsLimit = null)
    }

    /**
     * Fetches events for [load], honoring [totalEventsLimit].
     */
    private suspend fun fetchAllEventsForLoad(actorId: String, sessionId: String): List<Event> {
        return fetchEvents(actorId, sessionId, eventsLimit = totalEventsLimit)
    }

    /**
     * Fetches all events for the given actor/session, paging through results
     * and reversing to chronological order (AgentCore returns newest-first).
     *
     * @param eventsLimit Optional cap on the total number of events to fetch.
     *   When `null`, all events are fetched.
     */
    private suspend fun fetchEvents(actorId: String, sessionId: String, eventsLimit: Int?): List<Event> {
        val allEvents = mutableListOf<Event>()
        var nextToken: String? = null
        val requestPageSize = if (eventsLimit != null) {
            minOf(pageSize, eventsLimit)
        } else {
            pageSize
        }

        try {
            do {
                val request = ListEventsRequest {
                    this.memoryId = this@AgentcoreChatHistoryProvider.memoryId
                    this.actorId = actorId
                    this.sessionId = sessionId
                    includePayloads = true
                    maxResults = requestPageSize
                    if (nextToken != null) {
                        this.nextToken = nextToken
                    }
                }

                val response = client.listEvents(request)
                allEvents.addAll(response.events)
                nextToken = response.nextToken

                if (eventsLimit != null && allEvents.size >= eventsLimit) {
                    val limited = allEvents.take(eventsLimit).toMutableList()
                    limited.reverse()
                    return limited
                }
            } while (nextToken != null)

            // AgentCore returns events in descending order (newest first),
            // reverse to chronological order for LLM context
            allEvents.reverse()
            return allEvents
        } catch (e: SdkBaseException) {
            throw AgentcoreMemoryException.RetrievalException(
                "Failed to fetch events for actor: $actorId, session: $sessionId",
                e
            )
        }
    }

    /**
     * Normalized representation of a message for overlap comparison.
     * Carries role, content, optional eventId (for identity matching),
     * and optional payload (for building the delta to save).
     */
    private data class HistoryEntry(
        val role: String,
        val content: String,
        val eventId: String? = null,
        val payload: PayloadType.Conversational? = null
    )

    public companion object {
        /**
         * Default page size for listing events.
         */
        public const val DEFAULT_PAGE_SIZE: Int = 100

        /**
         * Converts a Smithy [Instant] to a Kotlin [kotlin.time.Instant].
         */
        @OptIn(ExperimentalTime::class)
        internal fun smithyInstantToKotlin(smithyInstant: Instant): kotlin.time.Instant {
            return kotlin.time.Instant.fromEpochSeconds(
                smithyInstant.epochSeconds,
                smithyInstant.nanosecondsOfSecond.toLong()
            )
        }

    }
}
