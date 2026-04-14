package ai.jetbrains.koog

import ai.koog.agents.longtermmemory.retrieval.SearchStrategy
import ai.koog.rag.base.storage.search.SimilaritySearchRequest

class AgentcoreSearchStrategyImpl: SearchStrategy {
    override fun create(query: String): SimilaritySearchRequest {
        TODO("Not yet implemented")
    }

}
