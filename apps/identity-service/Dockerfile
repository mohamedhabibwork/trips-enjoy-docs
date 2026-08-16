FROM gradle:9.5.1-jdk21 AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew --no-daemon dependencies
COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre-jammy
RUN useradd --system --create-home --uid 10001 identity
USER identity
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
