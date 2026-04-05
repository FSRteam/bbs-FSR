FROM eclipse-temurin:21-jdk

WORKDIR /workspace

COPY gradlew /workspace/gradlew
COPY gradle/wrapper /workspace/gradle/wrapper

# Download Gradle wrapper distribution into /root/.gradle/wrapper.
# Keep this layer successful even when remote network is flaky.
RUN chmod +x /workspace/gradlew && \
    /workspace/gradlew --version \
      -Dorg.gradle.internal.repository.max.tentatives=4 \
      -Dorg.gradle.internal.repository.initial.backoff=1000 \
      -Dorg.gradle.internal.http.connectionTimeout=120000 \
      -Dorg.gradle.internal.http.socketTimeout=180000 || true
