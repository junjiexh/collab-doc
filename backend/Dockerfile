# Stage 1: Build the yrs-bridge Rust native library
FROM rust:1.85-slim AS rust-builder

WORKDIR /build
COPY yrs-bridge/ ./yrs-bridge/
RUN cd yrs-bridge && cargo build --release

# Stage 2: Build the Spring Boot JAR
FROM eclipse-temurin:25-jdk AS java-builder

WORKDIR /build
COPY backend/gradlew ./
COPY backend/gradle/ ./gradle/
COPY backend/build.gradle.kts ./
COPY backend/settings.gradle.kts ./
COPY backend/src/ ./src/
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

# Stage 3: Runtime
FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=rust-builder /build/yrs-bridge/target/release/libyrs_bridge.so /app/libyrs_bridge.so
COPY --from=java-builder /build/build/libs/*.jar /app/app.jar

ENV YRS_BRIDGE_LIB_PATH=/app/libyrs_bridge.so

EXPOSE 8080

CMD ["java", "--enable-preview", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/app.jar"]
