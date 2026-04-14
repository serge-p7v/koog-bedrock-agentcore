package ai.koog.agents.features.longtermmemory.aws

/**
 * Builder for constructing AgentCore memory namespace strings.
 *
 * Supported patterns:
 * - `/strategies/{memoryStrategyId}/` — strategy scope (only [memoryStrategyId] provided)
 * - `/strategies/{memoryStrategyId}/actors/{actorId}/` — actor scope ([memoryStrategyId] and [actorId] provided)
 * - `/strategies/{memoryStrategyId}/actors/{actorId}/sessions/{sessionId}/` — session scope (all three provided)
 *
 * [memoryStrategyId] is required. [actorId] is required when [sessionId] is provided.
 *
 * Usage:
 * ```kotlin
 * val ns = AgentcoreNamespaceStringBuilder(memoryStrategyId = "myStrategy")
 *     .withActorId("alice")
 *     .withSessionId("session-1")
 *     .build()
 * // "/strategies/myStrategy/actors/alice/sessions/session-1/"
 * ```
 */
public class AgentcoreNamespaceStringBuilder(private val memoryStrategyId: String) {

    init {
        require(memoryStrategyId.isNotBlank()) { "memoryStrategyId must not be blank" }
    }

    private var actorId: String? = null
    private var sessionId: String? = null

    /**
     * Sets the actor ID, producing an actor-scope namespace.
     */
    public fun withActorId(actorId: String): AgentcoreNamespaceStringBuilder {
        require(actorId.isNotBlank()) { "actorId must not be blank" }
        this.actorId = actorId
        return this
    }

    /**
     * Sets the session ID, producing a session-scope namespace.
     * Requires [actorId] to be set first via [withActorId].
     */
    public fun withSessionId(sessionId: String): AgentcoreNamespaceStringBuilder {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        this.sessionId = sessionId
        return this
    }

    /**
     * Builds the namespace string.
     *
     * @return The namespace string matching one of the documented patterns.
     * @throws IllegalArgumentException if [sessionId] is set without [actorId].
     */
    public fun build(): String {
        val actor = actorId
        val session = sessionId

        if (session != null) {
            require(actor != null) { "actorId is required when sessionId is provided" }
            return "/strategies/$memoryStrategyId/actors/$actor/sessions/$session/"
        }

        if (actor != null) {
            return "/strategies/$memoryStrategyId/actors/$actor/"
        }

        return "/strategies/$memoryStrategyId/"
    }
}
