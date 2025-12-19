package ai.jetbrains

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ApplicationTest {

    @Test
    fun testPing() = testApplication {
        environment {
            config = MapApplicationConfig(
                "ktor.agentcore.memoryId" to "memory_123",
                "ktor.agentcore.memoryStrategyId" to "semantic_builtin_456"
            )
        }
        application {
            module()
        }
        client.get("/ping").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertContains(bodyAsText(), "Healthy")
        }
    }

}
