# Stage 1: Build
FROM gradle:8.12-jdk21 AS builder
WORKDIR /app
COPY . .
RUN gradle :api:bootJar --no-daemon

# Stage 2: Run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /app/api/build/libs/*.jar app.jar

# Create data directory
RUN mkdir -p /app/data

EXPOSE 8080 9090

ENTRYPOINT ["java", "-jar", "app.jar"]
