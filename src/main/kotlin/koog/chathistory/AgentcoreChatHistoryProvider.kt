package ai.jetbrains.koog.chathistory

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.CreateEventRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.DeleteEventRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.Event
import aws.sdk.kotlin.services.bedrockagentcore.model.ListEventsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.PayloadType
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
 * - **No delta tracking**: [store] always saves all conversational messages as a new event.
 *   When [deleteBeforeStore] is `true`, existing events for the actor/session are deleted
 *   before creating the new event, preventing snapshot accumulation.
 * - **Last-event loading by default**: [load] fetches only the most recent event (by
 *   eventTimestamp) for the actor/session. When [loadAllEvents] is `true`, all events are
 *   fetched with pagination and returned in chronological order.
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
 * @param totalEventsLimit Optional cap on the total number of events to fetch (only used when [loadAllEvents] is `true`).
 * @param ignoreUnknownRoles If `true`, non-conversational message/role types are silently skipped.
 *   If `false`, they cause an [IllegalStateException].
 * @param deleteBeforeStore If `true`, all existing events for the actor/session are deleted
 *   before storing new messages. Defaults to `false`.
 * @param loadAllEvents If `true`, [load] fetches all events (paginated) and returns them in
 *   chronological order. If `false` (default), only the most recent event is returned.
 * @throws AgentcoreMemoryException.ConfigurationException if [memoryId] is blank.
 */
public class AgentcoreChatHistoryProvider(
    public val client: BedrockAgentCoreClient,
    public val memoryId: String,
    defaultSession: String = AgentcoreConversationIdParser.Companion.DEFAULT_SESSION,
    public val pageSize: Int = DEFAULT_PAGE_SIZE,
    public val totalEventsLimit: Int? = null,
    public val ignoreUnknownRoles: Boolean = true,
    public val deleteBeforeStore: Boolean = false,
    public val loadAllEvents: Boolean = false
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

        // Convert all messages to payloads, filtering non-conversational types.
        // When ignoreUnknownRoles is false, messageToPayload will throw IllegalStateException
        // for non-conversational messages.
        val payloads = messages.mapNotNull {
            AgentcoreMessageConverter.messageToPayload(it, ignoreUnknownRoles)
        }
        if (payloads.isEmpty()) return

        try {
            if (deleteBeforeStore) {
                deleteAllEvents(actorId, sessionId)
            }

            val request = CreateEventRequest {
                clientToken = UUID.randomUUID().toString()
                this.memoryId = this@AgentcoreChatHistoryProvider.memoryId
                this.actorId = actorId
                this.sessionId = sessionId
                payload = payloads
                eventTimestamp = Instant.now()
            }

            logger.info("Sending request payload $payloads")
            val response = client.createEvent(request)
            logger.info("Created event ${response.event}")
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

        val events = if (loadAllEvents) {
            fetchAllEvents(actorId, sessionId)
        } else {
            fetchLastEvent(actorId, sessionId)?.let { listOf(it) } ?: emptyList()
        }

        logger.info("Loaded ${events.flatMap { it.payload }} payloads")

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
     * Fetches the most recent event for the given actor/session.
     * AgentCore returns events in descending order (newest first), so we fetch 1.
     */
    private suspend fun fetchLastEvent(actorId: String, sessionId: String): Event? {
        try {
            val request = ListEventsRequest {
                this.memoryId = this@AgentcoreChatHistoryProvider.memoryId
                this.actorId = actorId
                this.sessionId = sessionId
                includePayloads = true
                maxResults = 1
            }

            val response = client.listEvents(request)
            return response.events.firstOrNull()
        } catch (e: SdkBaseException) {
            throw AgentcoreMemoryException.RetrievalException(
                "Failed to fetch events for actor: $actorId, session: $sessionId",
                e
            )
        }
    }

    /**
     * Fetches all events for the given actor/session, paging through results
     * and reversing to chronological order (AgentCore returns newest-first).
     */
    private suspend fun fetchAllEvents(actorId: String, sessionId: String): List<Event> {
        val allEvents = mutableListOf<Event>()
        var nextToken: String? = null
        val requestPageSize = if (totalEventsLimit != null) {
            minOf(pageSize, totalEventsLimit)
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

                if (totalEventsLimit != null && allEvents.size >= totalEventsLimit) {
                    val limited = allEvents.take(totalEventsLimit).toMutableList()
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
     * Deletes all events for the given actor/session.
     * Lists events (without payloads) and deletes each one.
     */
    private suspend fun deleteAllEvents(actorId: String, sessionId: String) {
        var nextToken: String? = null

        do {
            val listRequest = ListEventsRequest {
                this.memoryId = this@AgentcoreChatHistoryProvider.memoryId
                this.actorId = actorId
                this.sessionId = sessionId
                includePayloads = false
                maxResults = pageSize
                if (nextToken != null) {
                    this.nextToken = nextToken
                }
            }

            val response = client.listEvents(listRequest)
            response.events.forEach { event ->
                client.deleteEvent(DeleteEventRequest {
                    this.memoryId = this@AgentcoreChatHistoryProvider.memoryId
                    this.actorId = actorId
                    this.sessionId = sessionId
                    this.eventId = event.eventId
                })
            }
            nextToken = response.nextToken
        } while (nextToken != null)
    }

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
