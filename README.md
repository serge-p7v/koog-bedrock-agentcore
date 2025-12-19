# koog-bedrock-agentcore
The purpose of this repository is to assist in development of Amazon Bedrock Agentcore support in Koog framework. The project is not intended for production usage.
This project was created using the [Ktor Project Generator](https://start.ktor.io).

## Prerequisites

- A computer with arm64 architecture (e.g. Apple M1)
- Java 21 or higher
- Docker
- Gradle 8.14
- AWS credentials to try Agent Memory on Bedrock Agentcore Memory and to deploy the application to Agentcore Runtime
- Created Agentcore Memory: short-term and long-term memory with a "Semantic memory" built-in extraction strategy

## Running the application locally to test Agentcore Memory

1. Set your Agentcore Memory settings in `application.yaml`: memoryId and memoryStrategyId (make sure to copy these ids after a successful creation)
2. Set your AWS credentials: `export AWS_ACCESS_KEY_ID=your_aws_key_id` and `export AWS_SECRET_ACCESS_KEY=your_aws_secret_key`
3. Switch to the following executor in the KoogAgentService
```kotlin
//            promptExecutor = simpleBedrockExecutor(System.getenv("AWS_ACCESS_KEY_ID"), System.getenv("AWS_SECRET_ACCESS_KEY")),//for local testing
```
4. Switch to `EnvironmentCredentialsProvider()` in KoogAgentService
5. Run: `./gradlew run`

Application starts on `http://localhost:8080`.


### Usage example

Send a POST request to interact with the agent:

**Endpoint:** `POST http://localhost:8080/invocations`

**Content-Type:** `application/json`

**Request 1:**
```json
{
  "prompt": "My favorite band is Radiohead"
}
```

**Sample response 1:**
```json
{
  "response": "That's awesome! Radiohead is an incredibly talented band with a unique sound and a huge following.<...>"
}
```

**Note**: it might be better to repeat the request just to make sure it is extracted from short-term memory to long-term memory.

**Request 2:**
```json
{
  "prompt": "What is the user's favorite band?"
}
```

**Sample response 2:**
```json
{
  "response": "Based on the information you provided, the user's favorite band is Radiohead.<...>"
}
```

## Deploying the application to Agentcore Runtime

1. Set your AWS credentials: `export AWS_ACCESS_KEY_ID=your_aws_key_id` and `export AWS_SECRET_ACCESS_KEY=your_aws_secret_key`
2. Build the application
3. Customize the file `dockerize.sh` with your AWS account ID
4. Run the commands from `dockerize.sh` one by one
5. In the AWS console in Agentcore Runtime, click "Host agent" and choose "Import docker image from Amazon ECR" with the image that you have just uploaded.
6. In the AWS console in Agentcore Runtime, invoke the agent in "Agent sandbox"

## Features

Here's a list of features included in this project:

| Name                                                                   | Description                                                                        |
| ------------------------------------------------------------------------|------------------------------------------------------------------------------------ |
| [Routing](https://start.ktor.io/p/routing)                             | Provides a structured routing DSL                                                  |
| [Server-Sent Events (SSE)](https://start.ktor.io/p/sse)                | Support for server push events                                                     |
| [Koog](https://start.ktor.io/p/koog)                                   | Integrate LLMs and build AI Agents with Koog framework                             |
| [Content Negotiation](https://start.ktor.io/p/content-negotiation)     | Provides automatic content conversion according to Content-Type and Accept headers |
| [kotlinx.serialization](https://start.ktor.io/p/kotlinx-serialization) | Handles JSON serialization using kotlinx.serialization library                     |

## Building & Running

To build or run the project, use one of the following tasks:

| Task                                    | Description                                                          |
| -----------------------------------------|---------------------------------------------------------------------- |
| `./gradlew test`                        | Run the tests                                                        |
| `./gradlew build`                       | Build everything                                                     |
| `./gradlew buildFatJar`                 | Build an executable JAR of the server with all dependencies included |
| `./gradlew buildImage`                  | Build the docker image to use with the fat JAR                       |
| `./gradlew publishImageToLocalRegistry` | Publish the docker image locally                                     |
| `./gradlew run`                         | Run the server                                                       |
| `./gradlew runDocker`                   | Run using the local docker image                                     |

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

