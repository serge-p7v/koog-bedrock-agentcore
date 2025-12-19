package ai.jetbrains.koog

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import aws.sdk.kotlin.services.bedrockagentcore.model.Content
import aws.sdk.kotlin.services.bedrockagentcore.model.Conversational
import aws.sdk.kotlin.services.bedrockagentcore.model.PayloadType
import aws.sdk.kotlin.services.bedrockagentcore.model.Role
import aws.smithy.kotlin.runtime.content.Document
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

object ShortTermMemoryConverter {
    private val json = Json { prettyPrint = true }

    //Although it's possible to save AgentCheckpointData as a whole PayloadType.Blob, only conversational payload is being transferred to the long-term memory.
    //That's why AgentCheckpointData is being split into two: messageHistory goes to the conversational part, everything else goes to the blob part.

    fun payloadToCheckpoint(payload: List<PayloadType>): AgentCheckpointData {
        val blobPayload = payload.filterIsInstance<PayloadType.Blob>().joinToString(separator = "") { it.value.asString() }
        val baseCheckpoint = json.decodeFromString<AgentCheckpointData>(blobPayload)

        val conversationalMessages = payload.filterIsInstance<PayloadType.Conversational>()
            .mapNotNull { convPayload: PayloadType.Conversational -> conversationalToMessage(convPayload.value) }

        return baseCheckpoint.copy(messageHistory = baseCheckpoint.messageHistory + conversationalMessages)
    }

    fun checkpointToPayload(checkpoint: AgentCheckpointData): List<PayloadType> {
        val baseCheckpoint = checkpoint.copy(messageHistory = emptyList())
        val checkpointString = json.encodeToString(AgentCheckpointData.serializer(), baseCheckpoint)

        val payloadList = mutableListOf<PayloadType>()
        payloadList.add(PayloadType.Blob(Document.String(checkpointString)))

        val conversationals = checkpoint.messageHistory
            .mapNotNull { messageToConversational(it) }
            .map { PayloadType.Conversational(it) }

        payloadList.addAll(conversationals)

        return payloadList
    }

    private fun conversationalToMessage(conversational: Conversational): Message? {
        val content = (conversational.content as? Content.Text)?.value ?: ""
        return when (conversational.role) {
            Role.User -> Message.User(content, RequestMetaInfo.create(Clock.System))
            Role.Assistant -> Message.Assistant(content, finishReason = null, metaInfo = ResponseMetaInfo.create(Clock.System))
            Role.Tool -> null//TODO: use Message.Tool()
            Role.Other -> null//?
            else -> null//SdkUnknown can be used
        }
    }

    private fun messageToConversational(message: Message): Conversational? {
        val role = when (message.role) {
            Message.Role.User -> Role.User
            Message.Role.Assistant -> Role.Assistant
            Message.Role.Tool -> null//Role.Tool
            Message.Role.System -> null//Role.Other or SdkUnknown
            Message.Role.Reasoning -> null//Role.Other or SdkUnknown
        } ?: return null

        val conversational = Conversational {
            this.role = role
            this.content = Content.Text(message.content)
        }

        return conversational
    }
}