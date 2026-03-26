package ai.jetbrains.koog.chathistory

/**
 * Base exception for AgentCore Memory operations.
 *
 * Wraps AWS SDK failures so that callers of [AgentcoreChatHistoryProvider]
 * do not need to depend on AWS-specific exception types.
 */
public open class AgentcoreMemoryException : RuntimeException {
    /**
     * FIXME
     */
    public constructor(message: String) : super(message)

    /**
     * FIXME
     */
    public constructor(message: String, cause: Throwable) : super(message, cause)

    /**
     * Thrown when a memory retrieval operation fails.
     */
    public class RetrievalException(message: String, cause: Throwable) :
        AgentcoreMemoryException(message, cause)

    /**
     * Thrown when a memory storage operation fails.
     */
    public class StorageException : AgentcoreMemoryException {
        public constructor(message: String, cause: Throwable) : super(message, cause)
        public constructor(message: String) : super(message)
    }

    /**
     * Thrown when memory configuration is invalid.
     */
    public class ConfigurationException(message: String) :
        AgentcoreMemoryException(message)
}
