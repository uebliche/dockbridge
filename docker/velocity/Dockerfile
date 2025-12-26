FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY gradle/ /app/gradle/
COPY gradlew /app/gradlew
COPY gradlew.bat /app/gradlew.bat
COPY build.gradle.kts /app/build.gradle.kts
COPY settings.gradle.kts /app/settings.gradle.kts
COPY src/ /app/src/
COPY README.md /app/README.md
COPY docker/velocity/velocity.toml /app/docker/velocity/velocity.toml

RUN mkdir -p /app/run/velocity \
    && cp /app/docker/velocity/velocity.toml /app/run/velocity/velocity.toml \
    && cp /app/docker/velocity/velocity.toml /app/run/velocity.toml

ENV GRADLE_OPTS="-Dorg.gradle.daemon=false"

CMD ["./gradlew", "runVelocity", "--no-daemon", "--stacktrace"]
