package ai.jetbrains

import ai.jetbrains.koog.KoogAgentService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

// ============================================================================
// Models
// ============================================================================

/**
 * Enum representing the health status of the AgentCore service.
 */
enum class PingStatus(val value: String) {
    HEALTHY("Healthy"),//System is ready to accept new work
    HEALTHY_BUSY("HealthyBusy"),//System is operational but currently busy with async tasks
    UNHEALTHY("Unhealthy");

    override fun toString(): String = value
}

/**
 * Response data class for AgentCore ping status.
 *
 * @param status the current status enum (HEALTHY, HEALTHY_BUSY, UNHEALTHY)
 * @param httpStatus the HTTP status code to return with the response
 * @param timeOfLastUpdate timestamp in seconds when the status last changed
 */
data class AgentCorePingResponse(
    val status: PingStatus,
    val httpStatus: HttpStatusCode,
    val timeOfLastUpdate: Long
)

/**
 * Serializable response for the /ping endpoint.
 */
@Serializable
data class PingResponse(
    val status: String,
    val time_of_last_update: Long
)

// ============================================================================
// Context
// ============================================================================

/**
 * Constants for well-known AgentCore HTTP headers.
 *
 * This object provides constants for headers commonly used in Amazon Bedrock AgentCore
 * requests, organized by functional groups for easy discovery and usage.
 */
object AgentCoreHeaders {
    // Core AgentCore Headers
    const val SESSION_ID = "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id"
    const val USER_ID = "X-Amzn-Bedrock-AgentCore-Runtime-User-Id"
    const val CUSTOM_HEADER_PREFIX = "X-Amzn-Bedrock-AgentCore-Runtime-Custom-"

    // Authentication & Authorization
    const val AUTHORIZATION = "Authorization"
    const val WORKLOAD_ACCESS_TOKEN = "workloadaccesstoken"
    const val WORKLOAD_ACCESS_TOKEN_RUNTIME = "x-amzn-bedrock-agentcore-runtime-workload-accesstoken"
    const val GUEST_AUTH = "x-aws-guest-auth"

    // AWS Infrastructure
    const val REQUEST_ID = "x-amzn-requestid"
    const val TRACE_ID = "x-amzn-trace-id"
    const val BAGGAGE = "baggage"

    // Proxy Information
    const val PROXY_IP = "x-aws-proxy-ip"
    const val PROXY_PORT = "x-aws-proxy-port"
}

/**
 * Context object containing HTTP headers from AgentCore invocation requests.
 *
 * This class provides read-only access to HTTP headers passed to the AgentCore
 * /invocations endpoint.
 */
class AgentCoreContext(private val headers: Headers) {

    /**
     * Gets all HTTP headers from the AgentCore request.
     * @return the HTTP headers
     */
    fun getHeaders(): Headers = headers

    /**
     * Gets the value of a specific HTTP header from the AgentCore request.
     * @param headerName the name of the header to retrieve
     * @return the header value, or null if the header is not found
     */
    fun getHeader(headerName: String?): String? {
        if (headerName == null) {
            return null
        }
        return headers[headerName]
    }
}

// ============================================================================
// Exception
// ============================================================================

/**
 * Exception thrown when there are issues with AgentCore method invocation, such as
 * multiple methods annotated with @AgentCoreInvocation or unsupported method signatures.
 */
class AgentCoreInvocationException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

// ============================================================================
// Services
// ============================================================================

/**
 * Service interface for providing ping status.
 */
interface AgentCorePingService {
    fun getPingStatus(): AgentCorePingResponse
}


class KoogAgentCorePingService : AgentCorePingService {
    private val startTime = System.currentTimeMillis() / 1000

    override fun getPingStatus(): AgentCorePingResponse {
        return if (KoogAgentService.isAgentRunning()) {
            AgentCorePingResponse(
                status = PingStatus.HEALTHY_BUSY,
                httpStatus = HttpStatusCode.OK,
                timeOfLastUpdate = startTime
            )
        } else {
            AgentCorePingResponse(
                status = PingStatus.HEALTHY,
                httpStatus = HttpStatusCode.OK,
                timeOfLastUpdate = startTime
            )
        }
    }
}

/**
 * Interface for invoking agent methods.
 */
interface AgentCoreMethodInvoker {
    suspend fun invokeAgentMethod(request: String, headers: Headers): String
}


class KoogAgentCoreMethodInvoker(val agentcoreMemoryId: String, val agentcoreMemoryStrategyId: String) : AgentCoreMethodInvoker {
    override suspend fun invokeAgentMethod(request: String, headers: Headers): String {
        val agentCoreContext = AgentCoreContext(headers)
        return try {
            KoogAgentService.createAndRunAgent(
                userPrompt = request,
                userSessionId = agentCoreContext.getHeader(AgentCoreHeaders.SESSION_ID),
                agentcoreMemoryId = agentcoreMemoryId,
                agentcoreMemoryStrategyId = agentcoreMemoryStrategyId
            )
        } catch (e: Exception) {
            throw AgentCoreInvocationException("Error trying to invoke AgentCoreInvocation method: ${e.message}", e)
        }
    }
}

// ============================================================================
// Routing
// ============================================================================

private val logger = LoggerFactory.getLogger("AgentCoreRouting")

fun Application.configureRouting() {
    val agentcoreMemoryId = environment.config.property("ktor.agentcore.memoryId").getString()
    val agentcoreMemoryStrategyId = environment.config.property("ktor.agentcore.memoryStrategyId").getString()

    // Initialize services
    val pingService: AgentCorePingService = KoogAgentCorePingService()
    val methodInvoker: AgentCoreMethodInvoker = KoogAgentCoreMethodInvoker(agentcoreMemoryId, agentcoreMemoryStrategyId)

    install(SSE)

    routing {
        // ====================================================================
        // AgentCore Ping Controller - GET /ping
        // ====================================================================
        get("/ping") {
            val pingStatus = pingService.getPingStatus()

            val response = PingResponse(
                status = pingStatus.status.toString(),
                time_of_last_update = pingStatus.timeOfLastUpdate
            )

            call.response.status(pingStatus.httpStatus)
            call.respond(response)
        }

        // ====================================================================
        // AgentCore Invocations Controller - POST /invocations
        // ====================================================================
        post("/invocations") {
            val contentType = call.request.contentType()
            val headers = call.request.headers

            try {
                val request: String = when {
                    contentType.match(ContentType.Application.Json) -> {
                        call.receiveText()
                    }
                    contentType.match(ContentType.Text.Plain) -> {
                        call.receiveText()
                    }
                    else -> {
                        call.receiveText()
                    }
                }

                val result = methodInvoker.invokeAgentMethod(request, headers)
                //By the way, this is how Koog can be invoked if bedrock is enabled via configureFrameworks
                //val result = aiAgent(singleRunStrategy(), model = BedrockModels.AmazonNovaMicro, request)

                call.respondText(result, contentType)
            } catch (e: AgentCoreInvocationException) {
                logger.error("Error trying to invoke AgentCoreInvocation method: ${e.message}", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            }
        }
    }
}
