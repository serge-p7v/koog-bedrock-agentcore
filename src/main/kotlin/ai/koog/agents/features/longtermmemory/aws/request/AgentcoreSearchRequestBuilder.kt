package ai.koog.agents.features.longtermmemory.aws.request

import ai.koog.agents.features.longtermmemory.aws.AgentcoreLongTermStrategyType
import ai.koog.rag.base.storage.search.SearchRequest
import ai.koog.rag.base.storage.search.SimilaritySearchRequest

/**
 * Builder for creating the appropriate [SearchRequest] based on [ai.koog.agents.features.longtermmemory.aws.AgentcoreLongTermStrategyType].
 *
 * Designed to be convenient for both Kotlin and Java users. Java users can chain setter calls:
 * ```java
 * SearchRequest request = new AgentcoreSearchRequestBuilder(AgentcoreLongTermStrategyType.SEMANTIC)
 *     .withLimit(20)
 *     .withOffset(0)
 *     .withQueryText("find relevant memories")
 *     .withMinScore(0.7)
 *     .build();
 * ```
 *
 * - For [ai.koog.agents.features.longtermmemory.aws.AgentcoreLongTermStrategyType.USER_PREFERENCE], produces a [ListingSearchRequest].
 * - For all other strategy types, produces a [SimilaritySearchRequest].
 *
 * @property strategyType the memory strategy type that determines which request type is built
 */
public class AgentcoreSearchRequestBuilder(
    public val strategyType: AgentcoreLongTermStrategyType,
) {
    /** Maximum number of results to return. */
    public var limit: Int = 10
        private set

    /** Number of results to skip for pagination. */
    public var offset: Int = 0
        private set

    /** Text query used for similarity search. Ignored for [AgentcoreLongTermStrategyType.USER_PREFERENCE]. */
    public var queryText: String = ""
        private set

    /** Optional minimum similarity score threshold. Ignored for [AgentcoreLongTermStrategyType.USER_PREFERENCE]. */
    public var minScore: Double? = null
        private set

    /**
     * Sets the maximum number of results to return.
     *
     * @param limit maximum number of results
     * @return this builder instance for chaining
     */
    public fun withLimit(limit: Int): AgentcoreSearchRequestBuilder = apply { this.limit = limit }

    /**
     * Sets the number of results to skip for pagination.
     *
     * @param offset number of results to skip
     * @return this builder instance for chaining
     */
    public fun withOffset(offset: Int): AgentcoreSearchRequestBuilder = apply { this.offset = offset }

    /**
     * Sets the text query used for similarity search.
     * Ignored for [AgentcoreLongTermStrategyType.USER_PREFERENCE].
     *
     * @param queryText the search query text
     * @return this builder instance for chaining
     */
    public fun withQueryText(queryText: String): AgentcoreSearchRequestBuilder = apply { this.queryText = queryText }

    /**
     * Sets the minimum similarity score threshold.
     * Ignored for [AgentcoreLongTermStrategyType.USER_PREFERENCE].
     *
     * @param minScore minimum score threshold, or null to disable filtering
     * @return this builder instance for chaining
     */
    public fun withMinScore(minScore: Double?): AgentcoreSearchRequestBuilder = apply { this.minScore = minScore }

    /**
     * Builds the appropriate [SearchRequest] for the configured strategy type.
     *
     * @return [ListingSearchRequest] if [strategyType] is [AgentcoreLongTermStrategyType.USER_PREFERENCE],
     *         [SimilaritySearchRequest] otherwise.
     */
    public fun build(): SearchRequest = when (strategyType) {
        AgentcoreLongTermStrategyType.USER_PREFERENCE -> ListingSearchRequest(
            limit = limit,
            offset = offset,
        )

        AgentcoreLongTermStrategyType.SEMANTIC,
        AgentcoreLongTermStrategyType.SUMMARY,
        AgentcoreLongTermStrategyType.EPISODIC -> SimilaritySearchRequest(
            queryText = queryText,
            limit = limit,
            offset = offset,
            minScore = minScore,
        )
    }
}
