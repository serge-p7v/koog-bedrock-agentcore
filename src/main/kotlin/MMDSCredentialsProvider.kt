package ai.jetbrains

import aws.smithy.kotlin.runtime.auth.awscredentials.CloseableCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.time.Instant
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
import io.ktor.http.path
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Taken from https://github.com/aws/aws-sdk-kotlin/issues/1743
 * Thank you AlbertoSH and ianbotsf!
 */
class MMDSCredentialsProvider() : CloseableCredentialsProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient {

        install(ContentNegotiation) {
            json(json)
        }

        defaultRequest {
            url {
                protocol = URLProtocol.HTTP
                host = "169.254.169.254"
                path("/latest/meta-data/iam/security-credentials/")
            }
        }
    }

    override suspend fun resolve(attributes: aws.smithy.kotlin.runtime.collections.Attributes): Credentials {
        if (System.getenv("AWS_EXECUTION_ENV") == "AWS_BedrockAgentCore_Runtime") {

            val credentials = getCredentials()

            return Credentials(
                accessKeyId = credentials.accessKeyId,
                secretAccessKey = credentials.secretAccessKey,
                sessionToken = credentials.sessionToken,
                expiration = Instant.fromIso8601(credentials.expiration),
            )
        } else {
            error("MMDSCredentialsProvider can only be used when running in AgentCore Runtime")
        }
    }

    private suspend fun getCredentials(): MMDSCredentialsResponse {
        val response = httpClient.get {}

        return if (response.status.isSuccess()) {
            val asJson = response.body<JsonObject>()

            val executionRole = asJson["execution_role"]
                ?.jsonPrimitive
                ?.content
                ?: error("MMDS response does not contain execution_role field")

            json.decodeFromString<MMDSCredentialsResponse>(executionRole)
        } else {
            error("MMDS call returned status code ${response.status.value}")
        }
    }

    override fun close() {
        httpClient.close()
    }

    @Serializable
    private data class MMDSCredentialsResponse(
        @SerialName("AccessKeyId") val accessKeyId: String,
        @SerialName("SecretAccessKey") val secretAccessKey: String,
        @SerialName("Token") val sessionToken: String,
        @SerialName("Expiration") val expiration: String,
    )
}