package ai.koog.agents.features.longtermmemory.aws.request

import ai.koog.rag.base.storage.search.SearchRequest

/**
 * A [SearchRequest] that retrieves memory records by listing rather than by semantic similarity.
 *
 * Used with [AgentcoreSearchStorage] to fetch records via the Bedrock AgentCore `ListMemoryRecords` API.
 *
 * @param limit the maximum number of records to return.
 * @param offset the number of records to skip before returning results.
 */
public data class ListingSearchRequest(
    override val limit: Int = 10,
    override val offset: Int = 0,
) : SearchRequest
