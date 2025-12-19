FROM amazoncorretto:21


COPY build/libs/koog-bedrock-agentcore-all.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]