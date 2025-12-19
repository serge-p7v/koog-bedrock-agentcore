package ai.jetbrains.koog

import ai.jetbrains.koog.ShortTermMemoryConverter.checkpointToPayload
import ai.jetbrains.koog.ShortTermMemoryConverter.payloadToCheckpoint
import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import ai.koog.agents.snapshot.providers.filters.AgentCheckpointPredicateFilter
import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.CreateEventRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.ListEventsRequest
import aws.smithy.kotlin.runtime.time.Instant
import aws.smithy.kotlin.runtime.time.fromEpochMilliseconds
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

class AgentcoreStorageProvider(
    val client: BedrockAgentCoreClient,
    val agentcoreMemoryId: String,
    val currentSessionId: String
) : PersistenceStorageProvider<AgentCheckpointPredicateFilter> {

    override suspend fun getCheckpoints(agentId: String, filter: AgentCheckpointPredicateFilter?): List<AgentCheckpointData> {
        logger.info { "Getting checkpoints for agent $agentId" }

        val request = ListEventsRequest {
            memoryId = agentcoreMemoryId
            actorId = agentId
            sessionId = currentSessionId
            includePayloads = true
//            this.filter = null
        }

        //TODO: consider listEventsPaginated
        return client.listEvents(request).events
            .map { payloadToCheckpoint(it.payload) }
            .filter { filter?.check(it) ?: true } //TODO: consider using the filter in ListEventsRequest
    }

    override suspend fun saveCheckpoint(agentId: String, agentCheckpointData: AgentCheckpointData) {
        logger.info { "Saving checkpoint for agent $agentId" }

        val request = CreateEventRequest {
            clientToken = UUID.randomUUID().toString()
            memoryId = agentcoreMemoryId
            actorId = agentId
            sessionId = currentSessionId
            payload = checkpointToPayload(agentCheckpointData)
//            metadata = emptyMap()//TODO: some part of agentCheckpointData can be stored in the metadata field
            eventTimestamp = Instant.fromEpochMilliseconds(agentCheckpointData.createdAt.toEpochMilliseconds())
        }

        client.createEvent(request)
    }

    override suspend fun getLatestCheckpoint(agentId: String, filter: AgentCheckpointPredicateFilter?): AgentCheckpointData? {
        return getCheckpoints(agentId, filter).maxByOrNull { it.createdAt }
    }
}