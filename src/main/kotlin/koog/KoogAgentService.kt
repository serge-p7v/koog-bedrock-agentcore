package ai.jetbrains.koog

import ai.jetbrains.MMDSCredentialsProvider
import ai.jetbrains.koog.chathistory.AgentcoreChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.ToolSelectionStrategy
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.memory.feature.nodes.nodeLoadAllFactsFromMemory
import ai.koog.agents.memory.model.MemorySubject
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.bedrock.BedrockClientSettings
import ai.koog.prompt.executor.clients.bedrock.BedrockLLMClient
import ai.koog.prompt.executor.clients.bedrock.BedrockModels
import ai.koog.prompt.executor.clients.bedrock.BedrockRegions
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.llms.all.simpleBedrockExecutor
import ai.koog.prompt.message.Message
import aws.sdk.kotlin.runtime.auth.credentials.EnvironmentCredentialsProvider
import aws.sdk.kotlin.services.bedrockagentcore.BedrockAgentCoreClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

object KoogAgentService {
    private val logger = LoggerFactory.getLogger("KoogAgentService")

    private val bedrockRegion = BedrockRegions.US_EAST_1

    private const val AGENT_NAME = "memory-agent"
    private const val DEFAULT_SESSION_ID = "DEFAULT"

    private val isAgentRunning = AtomicBoolean(false)

    fun isAgentRunning(): Boolean = isAgentRunning.get()

    suspend fun createAndRunAgent(userPrompt: String, userSessionId: String?, agentcoreMemoryId: String, agentcoreMemoryStrategyId: String): String {
        isAgentRunning.set(true)

        val agentConfig = AIAgentConfig(
            prompt = prompt("Generic Prompt") {
                system("You are a helpful assistant.")
            },
            model = BedrockModels.AmazonNovaMicro,//TODO: tool calling does not work due to https://github.com/JetBrains/koog/issues/1209
            maxAgentIterations = 10
        )

        val agentStrategy = strategy<String, String>("memory-loading-llm", toolSelectionStrategy = ToolSelectionStrategy.NONE) {
            val loadAll by nodeLoadAllFactsFromMemory<String>(
                name = "loadMemoryNode",
                subjects = listOf(MemorySubject.Everything)
            )

            val llmCall by nodeLLMRequest("llmCall")

            edge(nodeStart forwardTo loadAll)
            edge(loadAll forwardTo llmCall)
            edge(llmCall forwardTo nodeFinish transformed { it.content })
        }

//        val awsCredentialsProvider = MMDSCredentialsProvider()
        val awsCredentialsProvider = EnvironmentCredentialsProvider()//for local testing

        val agentcoreClient = BedrockAgentCoreClient {
            region = bedrockRegion.regionCode
            credentialsProvider = awsCredentialsProvider
        }

        val agent = AIAgent(
            id = AGENT_NAME,
//            promptExecutor = notSoSimpleBedrockExecutor(awsCredentialsProvider),
            promptExecutor = simpleBedrockExecutor(System.getenv("AWS_ACCESS_KEY_ID"), System.getenv("AWS_SECRET_ACCESS_KEY")),//for local testing
            strategy = singleRunStrategy(),
            agentConfig = agentConfig,
            toolRegistry = ToolRegistry.EMPTY,
        ) {
            install(ChatMemory) {
                chatHistoryProvider = AgentcoreChatHistoryProvider(agentcoreClient, agentcoreMemoryId)
                windowSize(20)
                filterMessages { it is Message.User || it is Message.Assistant }
            }
        }

        return try {
            agent.run(userPrompt, "myActorId:mySessionId") //FIXME: set desired actorId and sessionId
        } catch (e: Exception) {
            logger.error("Error trying to run agent: ${e.message}", e)
            throw e
        } finally {
//            awsCredentialsProvider.close()
            isAgentRunning.set(false)
        }
    }

    private fun notSoSimpleBedrockExecutor(credentialsProvider: CredentialsProvider): SingleLLMPromptExecutor {
        val bedrockSettings = BedrockClientSettings(
            region = bedrockRegion.regionCode,
            maxRetries = 3
        )

        return SingleLLMPromptExecutor(
            BedrockLLMClient(
                identityProvider = credentialsProvider,
                settings = bedrockSettings,
            )
        )
    }
}