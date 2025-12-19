package ai.jetbrains.koog

import ai.koog.agents.memory.model.*
import ai.koog.agents.memory.providers.AgentMemoryProvider
import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.BatchCreateMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.ListMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.MemoryContent
import aws.sdk.kotlin.services.bedrockagentcore.model.MemoryRecordCreateInput
import aws.sdk.kotlin.services.bedrockagentcore.model.MemoryRecordSummary
import aws.sdk.kotlin.services.bedrockagentcore.model.RetrieveMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.SearchCriteria
import aws.smithy.kotlin.runtime.time.Instant
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * A very basic implementation of AgentMemoryProvider.
 * It does not use memory subjects nor memory scopes yet.
 */
class AgentcoreMemoryProvider(val client: BedrockAgentCoreClient, val agentcoreMemoryId: String, val agentcoreMemoryStrategyId: String, agentId: String) : AgentMemoryProvider {

//    Examples of namespaces for different memory strategies:
//    - Summarization: /strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}
//    - Semantic memory: /strategies/{memoryStrategyId}/actors/{actorId}
//    - User preferences: /strategies/{memoryStrategyId}/actors/{actorId}
    private val agentcoreNamespace = "/strategies/$agentcoreMemoryStrategyId/actors/$agentId"

    override suspend fun save(fact: Fact, subject: MemorySubject, scope: MemoryScope) {
        //For demo purposes we don't save any facts to the long-term memory directly,
        // because we expect it to be filled automatically from the short-term memory by Agentcore.

//        val factValue = when (fact) {
//            is SingleFact -> fact.value
//            is MultipleFacts -> fact.values.joinToString("\n")
//        }
//
//        client.batchCreateMemoryRecords(BatchCreateMemoryRecordsRequest {
//            clientToken = null
//            memoryId = agentcoreMemoryId
//            records = listOf(MemoryRecordCreateInput {
//                requestIdentifier = UUID.randomUUID().toString()
//                memoryStrategyId = agentcoreMemoryStrategyId
//                namespaces = listOf(agentcoreNamespace)
//                content = MemoryContent.Text(factValue)
//                timestamp = Instant.now()
//            })
//        })
    }

    /**
     * Retrieves facts associated with a specific concept.
     */
    override suspend fun load(concept: Concept, subject: MemorySubject, scope: MemoryScope): List<Fact> {
        return loadByDescription(concept.description, subject, scope)
    }

    /**
     * Retrieves all facts within a specific context.
     */
    override suspend fun loadAll(subject: MemorySubject, scope: MemoryScope): List<Fact> {
        logger.info { "Getting facts for subject $subject and scope $scope" }

        val request = ListMemoryRecordsRequest {
            memoryId = agentcoreMemoryId
            namespace = agentcoreNamespace
            memoryStrategyId = agentcoreMemoryStrategyId
        }

        return client.listMemoryRecords(request).memoryRecordSummaries
            .map { memoryRecordSummaryToFact(it) }
    }

    /**
     * Performs semantic search across stored facts.
     */
    override suspend fun loadByDescription(description: String, subject: MemorySubject, scope: MemoryScope): List<Fact> {
        logger.info { "Getting facts for description $description, subject $subject and scope $scope" }

        val request = RetrieveMemoryRecordsRequest {
            memoryId = agentcoreMemoryId
            namespace = agentcoreNamespace
            searchCriteria = SearchCriteria {
                memoryStrategyId = agentcoreMemoryStrategyId
//                metadataFilters = null
                searchQuery = description
//                topK = 10
            }
        }

        return client.retrieveMemoryRecords(request).memoryRecordSummaries
            .map { memoryRecordSummaryToFact(it) }
    }

    private fun memoryRecordSummaryToFact(memoryRecordSummary: MemoryRecordSummary): Fact {
        return SingleFact(
            //TODO: perhaps the summary.content should store whole facts with concepts in them and not only the facts' values
            concept = Concept(keyword = "everything", description = "universal concept", factType = FactType.SINGLE),
            value = memoryRecordSummary.content?.asText() ?: "",
            timestamp = memoryRecordSummary.createdAt.epochSeconds
        )
    }
}