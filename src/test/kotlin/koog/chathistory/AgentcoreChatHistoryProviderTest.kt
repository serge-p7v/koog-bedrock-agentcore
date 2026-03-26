package ai.jetbrains.koog.chathistory

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.Content
import aws.sdk.kotlin.services.bedrockagentcore.model.Conversational
import aws.sdk.kotlin.services.bedrockagentcore.model.CreateEventRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.CreateEventResponse
import aws.sdk.kotlin.services.bedrockagentcore.model.DeleteEventRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.DeleteEventResponse
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    // --- store always saves all conversational messages ---

    @Test
    fun testStoreSavesAllConversationalMessages() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        val requestSlot = mutableListOf<CreateEventRequest>()
        coEvery { client.createEvent(capture(requestSlot)) } returns CreateEventResponse {
            event = makeEvent("evt-1", emptyList())
        }

        provider.store("actor:session", listOf(
            Message.User("Hello", RequestMetaInfo.Empty),
            Message.Assistant("Hi!", ResponseMetaInfo.Empty)
        ))

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
    fun testStoreDoesNotCallListEvents() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1")

        coEvery { client.createEvent(any<CreateEventRequest>()) } returns CreateEventResponse {
            event = makeEvent("evt-1", emptyList())
        }

        provider.store("actor:session", listOf(
            Message.User("Hello", RequestMetaInfo.Empty),
            Message.Assistant("Hi!", ResponseMetaInfo.Empty)
        ))

        coVerify(exactly = 0) { client.listEvents(any<ListEventsRequest>()) }
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

    // --- deleteBeforeStore ---

    @Test
    fun testStoreWithDeleteBeforeStoreDeletesExistingEvents() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1", deleteBeforeStore = true)

        val existingEvent = makeEvent("evt-old", emptyList())
        coEvery { client.listEvents(any<ListEventsRequest>()) } returns ListEventsResponse {
            events = listOf(existingEvent)
            nextToken = null
        }
        coEvery { client.deleteEvent(any<DeleteEventRequest>()) } returns DeleteEventResponse {
            eventId = "evt-old"
        }
        coEvery { client.createEvent(any<CreateEventRequest>()) } returns CreateEventResponse {
            event = makeEvent("evt-new", emptyList())
        }

        provider.store("actor:session", listOf(Message.User("Hello", RequestMetaInfo.Empty)))

        coVerify(exactly = 1) { client.listEvents(any<ListEventsRequest>()) }
        coVerify(exactly = 1) { client.deleteEvent(match<DeleteEventRequest> { it.eventId == "evt-old" }) }
        coVerify(exactly = 1) { client.createEvent(any<CreateEventRequest>()) }
    }

    @Test
    fun testStoreWithoutDeleteBeforeStoreDoesNotDelete() = runTest {
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1", deleteBeforeStore = false)

        coEvery { client.createEvent(any<CreateEventRequest>()) } returns CreateEventResponse {
            event = makeEvent("evt-1", emptyList())
        }

        provider.store("actor:session", listOf(Message.User("Hello", RequestMetaInfo.Empty)))

        coVerify(exactly = 0) { client.listEvents(any<ListEventsRequest>()) }
        coVerify(exactly = 0) { client.deleteEvent(any<DeleteEventRequest>()) }
        coVerify(exactly = 1) { client.createEvent(any<CreateEventRequest>()) }
    }

    // --- load: default (last event only) ---

    @Test
    fun testLoadReturnsMessagesFromLastEventOnly() = runTest {
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

        // Verify maxResults=1 was used
        coVerify {
            client.listEvents(match<ListEventsRequest> { it.maxResults == 1 })
        }
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
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1", loadAllEvents = true)

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
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1", pageSize = 1, loadAllEvents = true)

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
        val provider = AgentcoreChatHistoryProvider(client, memoryId = "mem-1", totalEventsLimit = 1, loadAllEvents = true)

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
}
