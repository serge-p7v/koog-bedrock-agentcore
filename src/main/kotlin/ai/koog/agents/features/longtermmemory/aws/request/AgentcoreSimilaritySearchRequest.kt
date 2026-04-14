package ai.koog.agents.features.longtermmemory.aws.request

import ai.koog.rag.base.storage.search.HasFilterExpression
import ai.koog.rag.base.storage.search.HasScoreThreshold
import ai.koog.rag.base.storage.search.HasTextQuery
import ai.koog.rag.base.storage.search.SearchRequest

/**
 * TODO: unused, keep it
 */
public data class AgentcoreSimilaritySearchRequest(
    override val queryText: String,
    override val limit: Int = 10,
    override val offset: Int = 0,
    override val minScore: Double? = null,
    override val filterExpression: String? = null,
    val memoryStrategyId: String? = null,
) : SearchRequest, HasTextQuery, HasScoreThreshold, HasFilterExpression
