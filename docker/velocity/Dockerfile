FROM eclipse-temurin:21-jdk

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY gradle/ /app/gradle/
COPY gradlew /app/gradlew
COPY gradlew.bat /app/gradlew.bat
COPY build.gradle.kts /app/build.gradle.kts
COPY settings.gradle.kts /app/settings.gradle.kts
COPY src/ /app/src/
COPY README.md /app/README.md
COPY docker/velocity/ /app/docker/velocity/

ENV GRADLE_OPTS="-Dorg.gradle.daemon=false"

CMD ["sh", "/app/docker/velocity/run-shadow.sh"]
