package ai.jetbrains.koog

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.annotation.ExperimentalAgentsApi
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.chathistory.aws.AgentcoreChatHistoryProvider
import ai.koog.agents.features.longtermmemory.aws.AgentcoreLongTermStrategyType
import ai.koog.agents.features.longtermmemory.aws.AgentcoreNamespace
import ai.koog.agents.features.longtermmemory.aws.AgentcoreSearchStorage
import ai.koog.agents.features.longtermmemory.aws.AgentcoreSimilaritySearchStrategy
import ai.koog.agents.longtermmemory.feature.LongTermMemory
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.executor.clients.bedrock.BedrockRegions
import ai.koog.prompt.executor.llms.all.simpleBedrockExecutor
import aws.sdk.kotlin.runtime.auth.credentials.EnvironmentCredentialsProvider
import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalAgentsApi::class)
object KoogAgentService {
    private val logger = LoggerFactory.getLogger("KoogAgentService")

    private val bedrockRegion = BedrockRegions.US_EAST_1

    private const val AGENT_NAME = "memory-agent"
    private const val DEFAULT_SESSION_ID = "DEFAULT"
    private const val DEFAULT_ACTOR_ID = "default"

    private val isAgentRunning = AtomicBoolean(false)

    fun isAgentRunning(): Boolean = isAgentRunning.get()

    suspend fun createAndRunAgent(userPrompt: String, userSessionId: String?, agentcoreMemoryId: String, agentcoreMemoryStrategyId: String): String {
        isAgentRunning.set(true)

        val agentConfig = AIAgentConfig(
            prompt = prompt("Generic Prompt") {
                system("You are a helpful assistant.")
            },
            model = BedrockModels.AmazonNovaMicro,
            maxAgentIterations = 10
        )

        val agentcoreClient = BedrockAgentCoreClient {
            region = bedrockRegion.regionCode
            credentialsProvider = EnvironmentCredentialsProvider()
        }

        val agent = AIAgent(
            id = AGENT_NAME,
            promptExecutor = simpleBedrockExecutor(System.getenv("AWS_ACCESS_KEY_ID"), System.getenv("AWS_SECRET_ACCESS_KEY")),//for local testing
            strategy = singleRunStrategy(),
            agentConfig = agentConfig,
            toolRegistry = ToolRegistry.EMPTY,
        ) {
            install(ChatMemory) {
                chatHistoryProvider = AgentcoreChatHistoryProvider(agentcoreClient, agentcoreMemoryId)
                windowSize(20)
            }
            install(LongTermMemory) { //fixme: must be used together with ChatMemory
                retrieval {
                    storage = AgentcoreSearchStorage(agentcoreClient, agentcoreMemoryId, agentcoreMemoryStrategyId)
                    searchStrategy = AgentcoreSimilaritySearchStrategy(AgentcoreLongTermStrategyType.SEMANTIC)
                    namespace = AgentcoreNamespace.actorScoped(agentcoreMemoryStrategyId, DEFAULT_ACTOR_ID) //fixme: warn users about absence of namespace validation
//                    promptAugmenter = UserPromptAugmenter()// fixme: select proper prompt augmenter depending on the strategy
                }
            }
        }

        return try {
            agent.run(userPrompt, "$DEFAULT_ACTOR_ID:$DEFAULT_SESSION_ID")
        } catch (e: Exception) {
            logger.error("Error trying to run agent: ${e.message}", e)
            throw e
        } finally {
//            awsCredentialsProvider.close()
            isAgentRunning.set(false)
        }
    }

//    private fun notSoSimpleBedrockExecutor(credentialsProvider: CredentialsProvider): SingleLLMPromptExecutor {
//        val bedrockSettings = BedrockClientSettings(
//            region = bedrockRegion.regionCode,
//            maxRetries = 3
//        )
//
//        return SingleLLMPromptExecutor(
//            BedrockLLMClient(
//                identityProvider = credentialsProvider,
//                settings = bedrockSettings,
//            )
//        )
//    }
}