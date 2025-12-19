package ai.jetbrains

import ai.koog.ktor.Koog
import ai.koog.ktor.bedrock
import ai.koog.prompt.executor.clients.bedrock.BedrockRegions
import aws.sdk.kotlin.runtime.auth.credentials.EnvironmentCredentialsProvider
import io.ktor.server.application.*

fun Application.configureFrameworks() {
//    install(Koog) {
//        llm {
//            google(apiKey = System.getenv("GOOGLE_API_KEY") ?: error("GOOGLE_API_KEY environment variable is not set"))
//            bedrock {//TODO: find out why it takes 15 seconds to start the app with bedrock config
//                credentialsProvider = EnvironmentCredentialsProvider()
//                region = BedrockRegions.US_EAST_1.regionCode
//            }
//        }
//    }
}
