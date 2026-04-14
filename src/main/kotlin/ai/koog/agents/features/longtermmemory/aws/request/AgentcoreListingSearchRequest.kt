package ai.koog.agents.features.longtermmemory.aws.request

import ai.koog.rag.base.storage.search.SearchRequest

/**
 * TODO: unused, keep it
 */
public data class AgentcoreListingSearchRequest(
    override val limit: Int = 10,
    override val offset: Int = 0,
    val memoryStrategyId: String? = null,
) : SearchRequest
