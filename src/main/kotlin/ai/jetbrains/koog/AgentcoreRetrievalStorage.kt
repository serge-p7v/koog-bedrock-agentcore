package ai.jetbrains.ai.jetbrains.koog

import ai.koog.agents.longtermmemory.model.MemoryRecord
import ai.koog.agents.longtermmemory.retrieval.RetrievalStorage
import ai.koog.agents.longtermmemory.retrieval.SearchRequest
import ai.koog.agents.longtermmemory.retrieval.SearchResult
import ai.koog.agents.longtermmemory.retrieval.SimilaritySearchRequest
import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.MemoryRecordSummary
import aws.sdk.kotlin.services.bedrockagentcore.model.RetrieveMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.SearchCriteria
import kotlin.time.Instant

class AgentcoreRetrievalStorage(val client: BedrockAgentCoreClient, val agentcoreMemoryId: String, val agentcoreMemoryStrategyId: String): RetrievalStorage {

    override suspend fun search(
        request: SearchRequest,
        namespace: String? //fixme: always null, because RetrievalSettingsBuilder does not accept it and it's not passed
    ): List<SearchResult> {
        val similarityRequest = request as SimilaritySearchRequest

        val request = RetrieveMemoryRecordsRequest {
            memoryId = agentcoreMemoryId
            this.namespace = namespace
            searchCriteria = SearchCriteria {
                memoryStrategyId = agentcoreMemoryStrategyId
//                metadataFilters = null
                searchQuery = similarityRequest.query
                topK = similarityRequest.limit
            }
        }

        return client.retrieveMemoryRecords(request).memoryRecordSummaries
            .map { memoryRecordSummaryToSearchResult(it) }
    }

    private fun memoryRecordSummaryToSearchResult(memoryRecordSummary: MemoryRecordSummary): SearchResult {
        return SearchResult(
            MemoryRecord(memoryRecordSummary.content?.asText() ?: "", memoryRecordSummary.memoryRecordId,
                mapMetadata(memoryRecordSummary.metadata), mapTimestamp(memoryRecordSummary.createdAt)),
            memoryRecordSummary.score ?: 0.0
        )
    }

    private fun mapTimestamp(createdAt: aws.smithy.kotlin.runtime.time.Instant?): Instant? {
        return createdAt?.let { Instant.fromEpochSeconds(it.epochSeconds) }
    }

    private fun mapMetadata(agentcoreMetadata: kotlin.collections.Map<String, aws.sdk.kotlin.services.bedrockagentcore.model.MetadataValue>?): kotlinx.serialization.json.JsonObject? {
        if (agentcoreMetadata.isNullOrEmpty()) return null
        return kotlinx.serialization.json.JsonObject(
            agentcoreMetadata.mapValues { (_, v) -> kotlinx.serialization.json.JsonPrimitive(v.asStringValueOrNull()) }
        )
    }

}
