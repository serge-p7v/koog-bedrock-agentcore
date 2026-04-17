package ai.koog.agents.features.longtermmemory.aws

/**
 * Builds AgentCore memory namespace strings.
 *
 * Two scopes are supported:
 * - [actorScoped] — `/strategies/{memoryStrategyId}/actors/{actorId}/`
 * - [sessionScoped] — `/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}/`
 *
 * Usage:
 * ```kotlin
 * val actorNs = AgentcoreNamespace.actorScoped("myStrategy", "alice")
 * // "/strategies/myStrategy/actors/alice/"
 *
 * val sessionNs = AgentcoreNamespace.sessionScoped("myStrategy", "alice", "session-1")
 * // "/strategies/myStrategy/actors/alice/sessions/session-1/"
 * ```
 */
public object AgentcoreNamespace {

    /**
     * Returns an actor-scoped namespace: `/strategies/{memoryStrategyId}/actors/{actorId}/`
     */
    public fun actorScoped(memoryStrategyId: String, actorId: String): String {
        require(memoryStrategyId.isNotBlank()) { "memoryStrategyId must not be blank" }
        require(actorId.isNotBlank()) { "actorId must not be blank" }
        return "/strategies/$memoryStrategyId/actors/$actorId/"
    }

    /**
     * Returns a session-scoped namespace: `/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}/`
     */
    public fun sessionScoped(memoryStrategyId: String, actorId: String, sessionId: String): String {
        require(memoryStrategyId.isNotBlank()) { "memoryStrategyId must not be blank" }
        require(actorId.isNotBlank()) { "actorId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        return "/strategies/$memoryStrategyId/actors/$actorId/sessions/$sessionId/"
    }
}
