package ai.jetbrains.koog.chathistory

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.Content
import aws.sdk.kotlin.services.bedrockagentcore.model.Conversational
import aws.sdk.kotlin.services.bedrockagentcore.model.CreateEventRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.CreateEventResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.Event
import aws.sdk.kotlin.services.bedrockagentcore.model.ListEventsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.ListEventsResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.PayloadType
import aws.sdk.kotlin.services.bedrockagentcore.model.Role
import aws.smithy.kotlin.runtime.time.Instant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.ExperimentalTime

class AgentcoreChatHistoryProviderTest {

    private val client = mockk<BedrockAgentCoreClient>(relaxed = true)

    private fun makeEvent(
        eventId: String,
        payloads: List<PayloadType>,
        actorId: String = "actor",
        sessionId: String = "session",
        memoryId: String = "mem-1",
        eventTimestamp: Instant? = Instant.now()
    ): Event = Event {
        this.eventId = eventId
        this.actorId = actorId
        this.sessionId = sessionId
        this.memoryId = memoryId
        this.eventTimestamp = eventTimestamp
        this.payload = payloads
    }

    private fun conversationalPayload(role: Role, text: String): PayloadType.Conversational {
        return PayloadType.Conversational(Conversational {
            this.role = role
            this.content = Content.Text(text)
        })
    }

    // --- Config validation ---

    @Test
    fun testBlankMemoryIdThrows() {
        assertFailsWith<AgentcoreMemoryException.ConfigurationException> {
            AgentcoreChatHistoryProvider(client, memoryId = "")
        }
        assertFailsWith<AgentcoreMemoryException.ConfigurationException> {
            AgentcoreChatHistoryProvider(client, memoryId = "   ")
        }
    }

    // --- store saves only new conversational messages (delta) ---

    @Test
    fun testStoreSavesAllConversationalMessages() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // No existing events
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = emptyList()
            nextToken = null
        }

        val requestSlot = mutableListOf<CreateEventRequest>()
        coEvery { client.createEvent(capture(requestSlot)) } returns CreateEventResponse {
            event = makeEvent("evt-1", emptyList())
        }

        provider.store(
            "actor:session", listOf(
                Message.User("Hello", RequestMetaInfo.Empty),
                Message.Assistant("Hi!", ResponseMetaInfo.Empty)
            )
        )

        assertEquals(1, requestSlot.size)
        val savedPayloads = requestSlot[0].payload!!
        assertEquals(2, savedPayloads.size)
        val first = savedPayloads[0] as PayloadType.Conversational
        assertEquals(Role.User, first.value.role)
        assertEquals("Hello", (first.value.content as Content.Text).value)
        val second = savedPayloads[1] as PayloadType.Conversational
        assertEquals(Role.Assistant, second.value.role)
        assertEquals("Hi!", (second.value.content as Content.Text).value)
    }

    @Test
    fun testStoreCallsListEventsToFetchExisting() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = emptyList()
            nextToken = null
        }
        coEvery { client.createEvent(any<CreateEventRequest>()) } returns CreateEventResponse {
            event = makeEvent("evt-1", emptyList())
        }

        provider.store(
            "actor:session", listOf(
                Message.User("Hello", RequestMetaInfo.Empty),
                Message.Assistant("Hi!", ResponseMetaInfo.Empty)
            )
        )

        coVerify(atLeast = 1) { client.listEvents(any<ListEventsRequest>()) }
        coVerify(exactly = 1) { client.createEvent(any<CreateEventRequest>()) }
    }

    @Test
    fun testStoreEmptyMessagesDoesNothing() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        provider.store("actor:session", emptyList())

        coVerify(exactly = 0) { client.createEvent(any<CreateEventRequest>()) }
    }

    // --- store ignores non-conversational messages ---

    @Test
    fun testStoreIgnoresNonConversationalMessages() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = emptyList()
            nextToken = null
        }

        val requestSlot = mutableListOf<CreateEventRequest>()
        coEvery { client.createEvent(capture(requestSlot)) } returns CreateEventResponse {
            event = makeEvent("evt-1", emptyList())
        }

        val messages = listOf(
            Message.System("system prompt", RequestMetaInfo.Empty),
            Message.User("Hello", RequestMetaInfo.Empty),
            Message.Tool.Call(id = "1", tool = "t", content = "{}", metaInfo = ResponseMetaInfo.Empty),
            Message.Assistant("Hi!", ResponseMetaInfo.Empty)
        )

        provider.store("actor:session", messages)

        coVerify(exactly = 1) { client.createEvent(any<CreateEventRequest>()) }
        val savedPayloads = requestSlot[0].payload!!
        assertEquals(2, savedPayloads.size)
    }

    @Test
    fun testStoreOnlyNonConversationalMessagesDoesNothing() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = emptyList()
            nextToken = null
        }

        val messages = listOf(
            Message.System("system prompt", RequestMetaInfo.Empty),
            Message.Tool.Call(id = "1", tool = "t", content = "{}", metaInfo = ResponseMetaInfo.Empty)
        )

        provider.store("actor:session", messages)

        coVerify(exactly = 0) { client.createEvent(any<CreateEventRequest>()) }
    }

    @Test
    fun testStoreFailsOnUnknownRolesWhenConfigured() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1", ignoreUnknownRoles = false)

        val messages = listOf(
            Message.System("system prompt", RequestMetaInfo.Empty)
        )

        assertFailsWith<IllegalStateException> {
            provider.store("actor:session", messages)
        }
    }

    @Test
    fun testStoreOnlyStoresNewMessages() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // Existing event with one message
        val existingEvent = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "Hello")
            )
        )
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(existingEvent)
            nextToken = null
        }

        val requestSlot = mutableListOf<CreateEventRequest>()
        coEvery { client.createEvent(capture(requestSlot)) } returns CreateEventResponse {
            event = makeEvent("evt-2", emptyList())
        }

        // Store 2 messages where first already exists
        provider.store(
            "actor:session", listOf(
                Message.User("Hello", RequestMetaInfo.Empty),
                Message.Assistant("Hi!", ResponseMetaInfo.Empty)
            )
        )

        assertEquals(1, requestSlot.size)
        val savedPayloads = requestSlot[0].payload!!
        assertEquals(1, savedPayloads.size)
        val first = savedPayloads[0] as PayloadType.Conversational
        assertEquals(Role.Assistant, first.value.role)
        assertEquals("Hi!", (first.value.content as Content.Text).value)
    }

    @Test
    fun testStoreSkipsWhenAllMessagesAlreadyExist() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        val existingEvent = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "Hello"),
                conversationalPayload(Role.Assistant, "Hi!")
            )
        )
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(existingEvent)
            nextToken = null
        }

        provider.store(
            "actor:session", listOf(
                Message.User("Hello", RequestMetaInfo.Empty),
                Message.Assistant("Hi!", ResponseMetaInfo.Empty)
            )
        )

        coVerify(exactly = 0) { client.createEvent(any<CreateEventRequest>()) }
    }

    // --- store: delta detection with mixed message types ---

    @Test
    fun testStoreDeltaWithMixedSystemAndConversationalMessages() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // Existing: one User message already persisted
        val existingEvent = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "Hello")
            )
        )
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(existingEvent)
            nextToken = null
        }

        val requestSlot = mutableListOf<CreateEventRequest>()
        coEvery { client.createEvent(capture(requestSlot)) } returns CreateEventResponse {
            event = makeEvent("evt-2", emptyList())
        }

        // Incoming: system + existing User + new Assistant
        // The system message should be filtered out, and only the new Assistant should be saved
        provider.store(
            "actor:session", listOf(
                Message.System("system prompt", RequestMetaInfo.Empty),
                Message.User("Hello", RequestMetaInfo.Empty),
                Message.Assistant("Hi!", ResponseMetaInfo.Empty)
            )
        )

        assertEquals(1, requestSlot.size)
        val savedPayloads = requestSlot[0].payload!!
        assertEquals(1, savedPayloads.size)
        val first = savedPayloads[0] as PayloadType.Conversational
        assertEquals(Role.Assistant, first.value.role)
        assertEquals("Hi!", (first.value.content as Content.Text).value)
    }

    // --- store: suffix-overlap with no overlap saves all incoming ---

    @Test
    fun testStoreNoOverlapSavesAllIncoming() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // Existing: "Hello" from User
        val existingEvent = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "Hello")
            )
        )
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(existingEvent)
            nextToken = null
        }

        val requestSlot = mutableListOf<CreateEventRequest>()
        coEvery { client.createEvent(capture(requestSlot)) } returns CreateEventResponse {
            event = makeEvent("evt-2", emptyList())
        }

        // Incoming: completely different messages — no overlap, all saved
        provider.store(
            "actor:session", listOf(
                Message.User("Different greeting", RequestMetaInfo.Empty),
                Message.Assistant("Hi!", ResponseMetaInfo.Empty)
            )
        )

        assertEquals(1, requestSlot.size)
        val savedPayloads = requestSlot[0].payload!!
        assertEquals(2, savedPayloads.size)
    }

    // --- load: loadAllEvents=true (default) fetches all events ---

    @Test
    fun testLoadReturnsAllMessages() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        val event = makeEvent(
            "evt-42", listOf(
                conversationalPayload(Role.User, "Hello"),
                conversationalPayload(Role.Assistant, "Hi!")
            )
        )

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(event)
            nextToken = null
        }

        val messages = provider.load("actor:session")

        assertEquals(2, messages.size)
        assertIs<Message.User>(messages[0])
        assertEquals("Hello", messages[0].content)
        assertIs<Message.Assistant>(messages[1])
        assertEquals("Hi!", messages[1].content)
    }

    @Test
    fun testLoadReturnsEmptyWhenNoEvents() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = emptyList()
            nextToken = null
        }

        val messages = provider.load("actor:session")
        assertEquals(0, messages.size)
    }

    @Test
    fun testLoadReturnsMessagesWithEventIdMetadata() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        val event = makeEvent(
            "evt-42", listOf(
                conversationalPayload(Role.User, "Hello"),
                conversationalPayload(Role.Assistant, "Hi!")
            )
        )

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(event)
            nextToken = null
        }

        val messages = provider.load("actor:session")

        assertEquals(2, messages.size)
        assertEquals("evt-42", AgentcoreMessageConverter.getEventId(messages[0]))
        assertEquals("evt-42", AgentcoreMessageConverter.getEventId(messages[1]))
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun testLoadUsesEventTimestampNotNow() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        val pastTimestamp = Instant.fromEpochSeconds(1000000L, 0)
        val event = makeEvent(
            "evt-1",
            listOf(conversationalPayload(Role.User, "Hello")),
            eventTimestamp = pastTimestamp
        )

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(event)
            nextToken = null
        }

        val messages = provider.load("actor:session")
        assertEquals(1, messages.size)

        val expectedKotlinInstant = AgentcoreChatHistoryProvider.smithyInstantToKotlin(pastTimestamp)
        assertEquals(expectedKotlinInstant, messages[0].metaInfo.timestamp)
    }

    // --- load: loadAllEvents=true ---

    @Test
    fun testLoadAllEventsReversesToChronological() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        val newestEvent = makeEvent(
            "evt-2", listOf(
                conversationalPayload(Role.Assistant, "response")
            )
        )
        val oldestEvent = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "question")
            )
        )

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(newestEvent, oldestEvent)
            nextToken = null
        }

        val messages = provider.load("actor:session")

        assertEquals(2, messages.size)
        assertIs<Message.User>(messages[0])
        assertEquals("question", messages[0].content)
        assertIs<Message.Assistant>(messages[1])
        assertEquals("response", messages[1].content)
    }

    @Test
    fun testLoadAllEventsPaginatesThroughAllPages() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1", pageSize = 1)

        val event2 = makeEvent("evt-2", listOf(conversationalPayload(Role.Assistant, "page1-msg")))
        val event1 = makeEvent("evt-1", listOf(conversationalPayload(Role.User, "page2-msg")))

        var callCount = 0
        coEvery { client.listEvents(any<ListEventsRequest>()) } answers {
            callCount++
            if (callCount == 1) {
                ListEventsResponse {
                    events = listOf(event2)
                    nextToken = "token-2"
                }
            } else {
                ListEventsResponse {
                    events = listOf(event1)
                    nextToken = null
                }
            }
        }

        val messages = provider.load("actor:session")

        assertEquals(2, messages.size)
        assertEquals("page2-msg", messages[0].content)
        assertEquals("page1-msg", messages[1].content)
        coVerify(exactly = 2) { client.listEvents(any<ListEventsRequest>()) }
    }

    @Test
    fun testLoadAllEventsRespectsEventsLimit() = runTest {
        val provider =
            AgentcoreChatHistoryProvider(client, memoryId = "mem-1", totalEventsLimit = 1)

        val event1 = makeEvent("evt-1", listOf(conversationalPayload(Role.User, "msg1")))
        val event2 = makeEvent("evt-2", listOf(conversationalPayload(Role.Assistant, "msg2")))

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(event1, event2)
            nextToken = "more"
        }

        val messages = provider.load("actor:session")

        assertEquals(1, messages.size)
    }

    // --- Unknown-role handling ---

    @Test
    fun testLoadIgnoresUnknownRolesByDefault() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        val event = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "hello"),
                conversationalPayload(Role.Tool, "tool output"),
                conversationalPayload(Role.Assistant, "hi")
            )
        )

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(event)
            nextToken = null
        }

        val messages = provider.load("actor:session")

        assertEquals(2, messages.size)
        assertIs<Message.User>(messages[0])
        assertIs<Message.Assistant>(messages[1])
    }

    @Test
    fun testLoadFailsOnUnknownRolesWhenConfigured() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1", ignoreUnknownRoles = false)

        val event = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.Tool, "tool output")
            )
        )

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(event)
            nextToken = null
        }

        assertFailsWith<IllegalStateException> {
            provider.load("actor:session")
        }
    }

    // --- Exception wrapping ---

    @Test
    fun testStoreWrapsAwsSdkExceptions() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = emptyList()
            nextToken = null
        }
        coEvery { client.createEvent(any<CreateEventRequest>()) } throws
                aws.smithy.kotlin.runtime.ServiceException("AWS error")

        assertFailsWith<AgentcoreMemoryException.StorageException> {
            provider.store("actor:session", listOf(Message.User("hi", RequestMetaInfo.Empty)))
        }
    }

    @Test
    fun testLoadWrapsAwsSdkExceptions() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        coEvery { client.listEvents(any<ListEventsRequest>()) } throws
                aws.smithy.kotlin.runtime.ServiceException("AWS error")

        assertFailsWith<AgentcoreMemoryException.RetrievalException> {
            provider.load("actor:session")
        }
    }

    // --- Default session ---

    @Test
    fun testCustomDefaultSession() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1", defaultSession = "my-session")

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = emptyList()
            nextToken = null
        }

        provider.load("myActor")

        coVerify {
            client.listEvents(match<ListEventsRequest> {
                it.sessionId == "my-session" && it.actorId == "myActor"
            })
        }
    }

    // --- Non-text conversational content handling ---

    @Test
    fun testLoadSkipsNonTextContentByDefault() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        val event = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "hello"),
                conversationalPayload(Role.Assistant, "hi")
            )
        )

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(event)
            nextToken = null
        }

        val messages = provider.load("actor:session")
        assertEquals(2, messages.size)
    }

    // --- Suffix-overlap tests ---

    @OptIn(ExperimentalTime::class)
    private fun userMsgWithEventId(text: String, eventId: String): Message.User {
        return Message.User(
            text,
            RequestMetaInfo(
                timestamp = kotlin.time.Clock.System.now(),
                metadata = JsonObject(mapOf(EVENT_ID_METADATA_KEY to JsonPrimitive(eventId)))
            )
        )
    }

    @OptIn(ExperimentalTime::class)
    private fun assistantMsgWithEventId(text: String, eventId: String): Message.Assistant {
        return Message.Assistant(
            text,
            ResponseMetaInfo(
                timestamp = kotlin.time.Clock.System.now(),
                metadata = JsonObject(mapOf(EVENT_ID_METADATA_KEY to JsonPrimitive(eventId)))
            )
        )
    }

    @Test
    fun testStoreWindowedHistory_RemoteHas100_LocalHasLast20Plus1New() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // Remote has 100 messages (50 user/assistant pairs)
        val remotePayloads = (1..50).flatMap { i ->
            listOf(
                conversationalPayload(Role.User, "user-$i"),
                conversationalPayload(Role.Assistant, "assistant-$i")
            )
        }
        // Split into events of 10 payloads each
        val remoteEvents = remotePayloads.chunked(10).mapIndexed { idx, chunk ->
            makeEvent("evt-${idx + 1}", chunk)
        }

        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = remoteEvents.reversed() // AgentCore returns newest-first
            nextToken = null
        }

        val requestSlot = mutableListOf<CreateEventRequest>()
        coEvery { client.createEvent(capture(requestSlot)) } returns CreateEventResponse {
            event = makeEvent("evt-new", emptyList())
        }

        // Local window: last 20 messages (user-41..user-50, assistant-41..assistant-50) + 1 new
        // Remote events are chunked by 10 payloads: evt-1 has indices 0-9, evt-2 has 10-19, etc.
        // user-41 is at flat index 80, assistant-50 is at flat index 99
        // So indices 80-89 -> evt-9, indices 90-99 -> evt-10
        val localWindow = (41..50).flatMap { i ->
            // flat index of user-i is (i-1)*2, of assistant-i is (i-1)*2+1
            val userEventIdx = ((i - 1) * 2) / 10 + 1
            val assistantEventIdx = ((i - 1) * 2 + 1) / 10 + 1
            listOf(
                userMsgWithEventId("user-$i", "evt-$userEventIdx"),
                assistantMsgWithEventId("assistant-$i", "evt-$assistantEventIdx")
            )
        } + listOf(Message.User("new-question", RequestMetaInfo.Empty))

        provider.store("actor:session", localWindow)

        assertEquals(1, requestSlot.size)
        val savedPayloads = requestSlot[0].payload!!
        assertEquals(1, savedPayloads.size)
        val saved = savedPayloads[0] as PayloadType.Conversational
        assertEquals("new-question", (saved.value.content as Content.Text).value)
    }

    @Test
    fun testStoreRepeatedCallAfterSuccessfulSave_NothingSavedSecondTime() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // After first save, remote now has both messages
        val remoteEvent = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "Hello"),
                conversationalPayload(Role.Assistant, "Hi!")
            )
        )
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(remoteEvent)
            nextToken = null
        }

        // Same immutable local list (without eventIds, simulating no mutation)
        provider.store(
            "actor:session", listOf(
                Message.User("Hello", RequestMetaInfo.Empty),
                Message.Assistant("Hi!", ResponseMetaInfo.Empty)
            )
        )

        // Nothing should be saved — full overlap by content
        coVerify(exactly = 0) { client.createEvent(any<CreateEventRequest>()) }
    }

    @Test
    fun testStoreAllNewMessages_NoPersistedOverlap() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // Remote has old messages that don't overlap with local window at all
        val remoteEvent = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "old-msg-1"),
                conversationalPayload(Role.Assistant, "old-msg-2")
            )
        )
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(remoteEvent)
            nextToken = null
        }

        val requestSlot = mutableListOf<CreateEventRequest>()
        coEvery { client.createEvent(capture(requestSlot)) } returns CreateEventResponse {
            event = makeEvent("evt-2", emptyList())
        }

        // Local window has only new unsaved messages (truncated away all persisted ones)
        provider.store(
            "actor:session", listOf(
                Message.User("brand-new-1", RequestMetaInfo.Empty),
                Message.Assistant("brand-new-2", ResponseMetaInfo.Empty)
            )
        )

        assertEquals(1, requestSlot.size)
        val savedPayloads = requestSlot[0].payload!!
        assertEquals(2, savedPayloads.size)
    }

    @Test
    fun testStorePersistedWithEventIdFollowedByNewMessages() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // Remote has 2 messages
        val remoteEvent = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "Hello"),
                conversationalPayload(Role.Assistant, "Hi!")
            )
        )
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(remoteEvent)
            nextToken = null
        }

        val requestSlot = mutableListOf<CreateEventRequest>()
        coEvery { client.createEvent(capture(requestSlot)) } returns CreateEventResponse {
            event = makeEvent("evt-2", emptyList())
        }

        // Local: loaded messages with eventId + new messages without eventId
        provider.store(
            "actor:session", listOf(
                userMsgWithEventId("Hello", "evt-1"),
                assistantMsgWithEventId("Hi!", "evt-1"),
                Message.User("Follow-up question", RequestMetaInfo.Empty)
            )
        )

        assertEquals(1, requestSlot.size)
        val savedPayloads = requestSlot[0].payload!!
        assertEquals(1, savedPayloads.size)
        val saved = savedPayloads[0] as PayloadType.Conversational
        assertEquals("Follow-up question", (saved.value.content as Content.Text).value)
    }

    @Test
    fun testStoreThrowsWhenEventIdAfterNonEventId() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // Message with eventId appears after one without — inconsistent ordering
        assertFailsWith<AgentcoreMemoryException.StorageException> {
            provider.store(
                "actor:session", listOf(
                    Message.User("new message", RequestMetaInfo.Empty),
                    assistantMsgWithEventId("persisted message", "evt-1")
                )
            )
        }

        // Should fail before any API calls
        coVerify(exactly = 0) { client.listEvents(any<ListEventsRequest>()) }
        coVerify(exactly = 0) { client.createEvent(any<CreateEventRequest>()) }
    }

    @Test
    fun testStoreDuplicateContentInHistory_MaxOverlapChosen() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // Remote has repeated pattern: "ping" / "pong" / "ping" / "pong"
        val remoteEvent = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "ping"),
                conversationalPayload(Role.Assistant, "pong"),
                conversationalPayload(Role.User, "ping"),
                conversationalPayload(Role.Assistant, "pong")
            )
        )
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(remoteEvent)
            nextToken = null
        }

        val requestSlot = mutableListOf<CreateEventRequest>()
        coEvery { client.createEvent(capture(requestSlot)) } returns CreateEventResponse {
            event = makeEvent("evt-2", emptyList())
        }

        // Local window: last 2 of remote ("ping", "pong") + 1 new
        // Maximum overlap should be 2 (matching the last "ping"/"pong" pair)
        provider.store(
            "actor:session", listOf(
                Message.User("ping", RequestMetaInfo.Empty),
                Message.Assistant("pong", ResponseMetaInfo.Empty),
                Message.User("new-question", RequestMetaInfo.Empty)
            )
        )

        assertEquals(1, requestSlot.size)
        val savedPayloads = requestSlot[0].payload!!
        assertEquals(1, savedPayloads.size)
        val saved = savedPayloads[0] as PayloadType.Conversational
        assertEquals("new-question", (saved.value.content as Content.Text).value)
    }

    // --- Issue fix: overlap==0 with eventId-bearing incoming throws ---

    @Test
    fun testStoreThrowsWhenZeroOverlapButIncomingHasEventId() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // Remote has messages that don't match incoming at all
        val remoteEvent = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "remote-only-msg"),
                conversationalPayload(Role.Assistant, "remote-only-reply")
            )
        )
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(remoteEvent)
            nextToken = null
        }

        // Incoming has eventId-bearing messages that don't overlap with remote —
        // this means local and remote have diverged
        assertFailsWith<AgentcoreMemoryException.StorageException> {
            provider.store(
                "actor:session", listOf(
                    userMsgWithEventId("completely-different", "evt-99"),
                    Message.User("new msg", RequestMetaInfo.Empty)
                )
            )
        }
    }

    @Test
    fun testStoreZeroOverlapWithoutEventId_SavesAll() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // Remote has messages
        val remoteEvent = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "remote-only-msg")
            )
        )
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(remoteEvent)
            nextToken = null
        }

        val requestSlot = mutableListOf<CreateEventRequest>()
        coEvery { client.createEvent(capture(requestSlot)) } returns CreateEventResponse {
            event = makeEvent("evt-2", emptyList())
        }

        // Incoming has NO eventId — zero overlap is safe, all new messages saved
        provider.store(
            "actor:session", listOf(
                Message.User("brand-new", RequestMetaInfo.Empty),
                Message.Assistant("brand-new-reply", ResponseMetaInfo.Empty)
            )
        )

        assertEquals(1, requestSlot.size)
        assertEquals(2, requestSlot[0].payload!!.size)
    }

    // --- Issue fix: multi-payload same eventId matched by eventId+role+content ---

    @Test
    fun testStoreMultiPayloadSameEventId_MatchesByContent() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        // Remote: one event with 2 payloads sharing the same eventId
        val remoteEvent = makeEvent(
            "evt-1", listOf(
                conversationalPayload(Role.User, "Hello"),
                conversationalPayload(Role.Assistant, "Hi!")
            )
        )
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(remoteEvent)
            nextToken = null
        }

        val requestSlot = mutableListOf<CreateEventRequest>()
        coEvery { client.createEvent(capture(requestSlot)) } returns CreateEventResponse {
            event = makeEvent("evt-2", emptyList())
        }

        // Incoming: both messages have same eventId (as loaded), plus a new one
        // With eventId-only matching, both would match the first persisted entry — wrong.
        // With eventId+role+content matching, each matches its correct counterpart.
        provider.store(
            "actor:session", listOf(
                userMsgWithEventId("Hello", "evt-1"),
                assistantMsgWithEventId("Hi!", "evt-1"),
                Message.User("New question", RequestMetaInfo.Empty)
            )
        )

        assertEquals(1, requestSlot.size)
        val savedPayloads = requestSlot[0].payload!!
        assertEquals(1, savedPayloads.size)
        val saved = savedPayloads[0] as PayloadType.Conversational
        assertEquals("New question", (saved.value.content as Content.Text).value)
    }

}
