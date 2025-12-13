FROM eclipse-temurin:21-jre-alpine

ARG LIMBO_JAR_URL="https://ci.loohpjames.com/job/Limbo/lastSuccessfulBuild/artifact/target/Limbo-0.7.16-ALPHA-1.21.10.jar"

RUN apk add --no-cache curl \
    && mkdir -p /opt/limbo \
    && curl -fL "$LIMBO_JAR_URL" -o /opt/limbo/Limbo.jar

COPY server.properties /opt/limbo/server.properties

WORKDIR /opt/limbo

EXPOSE 25565

ENV JAVA_OPTS="-Xms256M -Xmx512M"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/limbo/Limbo.jar"]
