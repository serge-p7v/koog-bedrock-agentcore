package ai.koog.agents.features.longtermmemory.aws

import ai.koog.agents.longtermmemory.retrieval.SearchStrategy
import ai.koog.rag.base.storage.search.SimilaritySearchRequest


public class AgentcoreSimilaritySearchStrategy(
    public val strategyType: AgentcoreLongTermStrategyType,
    public val limit: Int = 10,
    public val offset: Int = 0,
    public val minScore: Double? = null,
) : SearchStrategy {
    override fun create(query: String): SimilaritySearchRequest = when (strategyType) {
//        AgentcoreLongTermStrategyType.USER_PREFERENCE -> ListingSearchRequest(
//            limit = limit,
//            offset = offset,
//        )
        AgentcoreLongTermStrategyType.USER_PREFERENCE,
        AgentcoreLongTermStrategyType.SEMANTIC,
        AgentcoreLongTermStrategyType.SUMMARY,
        AgentcoreLongTermStrategyType.EPISODIC -> SimilaritySearchRequest(
            queryText = query,
            limit = limit,
            offset = offset,
            minScore = minScore,
        )
    }
}
