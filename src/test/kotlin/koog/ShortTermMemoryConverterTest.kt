package ai.jetbrains.koog

import ai.jetbrains.koog.ShortTermMemoryConverter.checkpointToPayload
import ai.jetbrains.koog.ShortTermMemoryConverter.payloadToCheckpoint
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.prompt.dsl.prompt
import aws.sdk.kotlin.services.bedrockagentcore.model.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals

class ShortTermMemoryConverterTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Test
    fun testConversion() {
        val checkpoint = AgentCheckpointData(
            checkpointId = "test-checkpoint",
            createdAt = Clock.System.now(),
            nodeId = "test-node",
            lastInput = JsonPrimitive("test-input"),
            messageHistory = emptyList(),
            version = 1,
            properties = emptyMap()
        )
        
        // Mock payload that contains both Blob and Conversational
        val blobPayload = checkpointToPayload(checkpoint)[0] as PayloadType.Blob
        val convPayload1 = PayloadType.Conversational(Conversational {
            role = Role.User
            content = Content.Text("Hello")
        })
        val convPayload2 = PayloadType.Conversational(Conversational {
            role = Role.Assistant
            content = Content.Text("Hi!")
        })
        
        val payload = listOf(blobPayload, convPayload1, convPayload2)
        
        // We will test that we can extract the values from the payloads
        val blobPayloadStr = payload.filterIsInstance<PayloadType.Blob>().joinToString(separator = "") { it.value.asString() }
        val baseCheckpointFromBlob = json.decodeFromString<AgentCheckpointData>(blobPayloadStr)
        assertEquals(checkpoint.checkpointId, baseCheckpointFromBlob.checkpointId)
        
        val convs = payload.filterIsInstance<PayloadType.Conversational>()
        assertEquals(2, convs.size)
        assertEquals(Role.User, convs[0].value.role)
        assertEquals("Hello", (convs[0].value.content as Content.Text).value)
        assertEquals(Role.Assistant, convs[1].value.role)
        assertEquals("Hi!", (convs[1].value.content as Content.Text).value)
    }

    @Test
    fun testEmptyHistoryConversion() {
        val checkpoint = AgentCheckpointData(
            checkpointId = "test-checkpoint-empty",
            createdAt = Clock.System.now(),
            nodeId = "test-node",
            lastInput = JsonPrimitive("test-input"),
            messageHistory = emptyList(),
            version = 1,
            properties = emptyMap()
        )
        
        val payload = checkpointToPayload(checkpoint)
        
        // Should have 1 Blob and 0 Conversational payloads
        assertEquals(1, payload.size)
        assertEquals(1, payload.filterIsInstance<PayloadType.Blob>().size)
        assertEquals(0, payload.filterIsInstance<PayloadType.Conversational>().size)

        val reconstructed = payloadToCheckpoint(payload)

        assertEquals(checkpoint.checkpointId, reconstructed.checkpointId)
        assertEquals(0, reconstructed.messageHistory.size)
    }

    @Test
    fun testFullRoundTripWithHistory() {
        val checkpoint = AgentCheckpointData(
            checkpointId = "test-checkpoint-roundtrip",
            createdAt = Clock.System.now(),
            nodeId = "test-node",
            lastInput = JsonPrimitive("test-input"),
            messageHistory = listOf(
                prompt("p1") { user("Hello from user") }.messages[0],
                prompt("p2") { assistant("Hello from assistant") }.messages[0]
            ),
            version = 1,
            properties = emptyMap()
        )

        val payload = checkpointToPayload(checkpoint)
        
        // Should have 1 Blob and 2 Conversational payloads
        assertEquals(3, payload.size)
        assertEquals(1, payload.filterIsInstance<PayloadType.Blob>().size)
        assertEquals(2, payload.filterIsInstance<PayloadType.Conversational>().size)

        val reconstructed = payloadToCheckpoint(payload)

        assertEquals(checkpoint.checkpointId, reconstructed.checkpointId)
        assertEquals(2, reconstructed.messageHistory.size)
        assertEquals("User", reconstructed.messageHistory[0].role.name)
        assertEquals("Hello from user", reconstructed.messageHistory[0].content)
        assertEquals("Assistant", reconstructed.messageHistory[1].role.name)
        assertEquals("Hello from assistant", reconstructed.messageHistory[1].content)
    }
}
