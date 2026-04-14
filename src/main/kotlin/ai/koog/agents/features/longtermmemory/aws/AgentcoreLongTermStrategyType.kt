package ai.koog.agents.features.longtermmemory.aws

/**
 * AgentCore Memory built-in strategies
 */
public enum class AgentcoreLongTermStrategyType {
    /**
     * Designed to identify and extract key pieces of factual information and contextual knowledge from conversational data.
     * Default namespace: /strategies/{memoryStrategyId}/actors/{actorId}/
     */
    SEMANTIC,

    /**
     * Designed to automatically identify and extract user preferences, choices, and styles from conversational data.
     * Default namespace: /strategies/{memoryStrategyId}/actors/{actorId}/
     */
    USER_PREFERENCE,

    /**
     * Responsible for generating condensed, real-time summaries of conversations within a single session.
     * Default namespace: /strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}/
     */
    SUMMARY,

    /**
     * Captures meaningful slices of user and system interaction
     * so applications can recall context in a way that feels focused and relevant.
     * Default namespace for extracted memories: /strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}/
     * Default namespace for reflection: /strategies/{memoryStrategyId}/actors/{actorId}/
     */
    EPISODIC
}
