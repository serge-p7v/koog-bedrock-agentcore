package ai.koog.agents.features.longtermmemory.aws

import ai.koog.agents.features.longtermmemory.aws.request.ListingSearchRequest
import ai.koog.rag.base.TextDocument
import ai.koog.rag.base.storage.SearchStorage
import ai.koog.rag.base.storage.search.SearchRequest
import ai.koog.rag.base.storage.search.SearchResult
import ai.koog.rag.base.storage.search.SimilaritySearchRequest
import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.sdk.kotlin.services.bedrockagentcore.model.ListMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.MemoryRecordSummary
import aws.sdk.kotlin.services.bedrockagentcore.model.RetrieveMemoryRecordsRequest
import aws.sdk.kotlin.services.bedrockagentcore.model.SearchCriteria
import org.slf4j.LoggerFactory

/**
 * A [SearchStorage] implementation backed by AWS Bedrock AgentCore memory.
 *
 * Supports two search strategies:
 * - [SimilaritySearchRequest]: performs semantic similarity search using the Bedrock `RetrieveMemoryRecords` API.
 * - [ListingSearchRequest]: lists memory records using the Bedrock `ListMemoryRecords` API.
 *
 * @param client the [BedrockAgentCoreClient] used to communicate with the AWS Bedrock AgentCore service.
 * @param agentcoreMemoryId the identifier of the AgentCore memory store to search.
 * @param agentcoreMemoryStrategyId the identifier of the memory strategy applied during search and listing.
 */
public class AgentcoreSearchStorage(
    public val client: BedrockAgentCoreClient,
    public val agentcoreMemoryId: String,
    public val agentcoreMemoryStrategyId: String, // TODO: should be passed in AgentcoreSimilaritySearchRequest and AgentcoreListingSearchRequest
) : SearchStorage<TextDocument, SearchRequest> {

    private val logger = LoggerFactory.getLogger("AgentcoreSearchStorage")

    override suspend fun search(
        request: SearchRequest,
        namespace: String?
    ): List<SearchResult<TextDocument>> {
        try {
            val summaries = when (request) {
                is SimilaritySearchRequest -> {
                    retrieveMemoryRecords(request.limit, request.queryText, namespace)
                        .map { AgentcoreMemoryRecordConverter.memoryRecordSummaryToSearchResult(it) }
                        .filter { it.score.value >= (request.minScore ?: 0.0) }
                }
                is ListingSearchRequest -> {
                    listMemoryRecords(request.limit, namespace)
                        .map { AgentcoreMemoryRecordConverter.memoryRecordSummaryToSearchResult(it) }
                }
                else -> {
                    throw IllegalArgumentException("Unsupported search request type: ${request::class.simpleName}")
                }
            }

            return summaries
        } catch (e: Exception) {
            throw AgentcoreMemoryException.RetrieveException(
                "Failed to search memory records: memoryId=$agentcoreMemoryId, namespace=$namespace",
                e
            )
        }
    }

    private suspend fun retrieveMemoryRecords(topK: Int, searchQuery: String?, namespace: String?): List<MemoryRecordSummary> {
        val request = RetrieveMemoryRecordsRequest {
            memoryId = agentcoreMemoryId
            this.namespace = namespace
            searchCriteria = SearchCriteria {
                memoryStrategyId = agentcoreMemoryStrategyId
//                metadataFilters = null // TODO: pass the filterExpression to metadataFilters
                this.searchQuery = searchQuery
                this.topK = topK
            }
        }

        logger.debug("Retrieving memory records for searchQuery $searchQuery and namespace $namespace")
        val memoryRecordSummaries = client.retrieveMemoryRecords(request).memoryRecordSummaries
        logger.debug("Retrieved ${memoryRecordSummaries.size} memory records")

        return memoryRecordSummaries
    }

    private suspend fun listMemoryRecords(maxResults: Int?, namespace: String?): List<MemoryRecordSummary> {
        val request = ListMemoryRecordsRequest {
            memoryId = agentcoreMemoryId
            memoryStrategyId = agentcoreMemoryStrategyId
            this.namespace = namespace
            this.maxResults = maxResults
        }

        logger.debug("Listing memory records for namespace $namespace")
        val memoryRecordSummaries = client.listMemoryRecords(request).memoryRecordSummaries
        logger.debug("Listed ${memoryRecordSummaries.size} memory records")

        return memoryRecordSummaries
    }
}
