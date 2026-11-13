
# 1. Build stage(Multi-stage Build 방식)
# Java 21 JDK로 Spring Boot 실행 JAR를 빌드하고,
# 두 번째 단계에서는 JRE만 포함된 이미지로 애플리케이션을 실행
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew .
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
COPY src ./src

RUN chmod +x ./gradlew

RUN ./gradlew bootJar --no-daemon \
    && find build/libs -maxdepth 1 \
       -name "*.jar" ! -name "*-plain.jar" \
       -exec cp {} /app/app.jar \;

# 2. Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/app.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s \
  --start-period=60s --retries=3 \
  CMD ["java", "-cp", "/app/app.jar", "HealthCheck"]

ENTRYPOINT ["java", "-jar", "app.jar"]
