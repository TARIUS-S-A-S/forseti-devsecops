# Forseti Backend — multi-stage build
# Stage 1: build con Maven oficial (reproducible)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache de deps (capa que cambia poco)
COPY pom.xml ./
RUN mvn -B -e dependency:go-offline

# Código fuente y build
COPY src ./src
RUN mvn -B -e clean package -DskipTests && \
    cp target/forseti-backend.jar app.jar

# Stage 2: runtime mínimo + tzdata
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# tzdata + curl + usuario no-root
RUN apk add --no-cache curl tzdata && \
    addgroup -S forseti && adduser -S -G forseti forseti

ENV TZ=UTC

COPY --from=build --chown=forseti:forseti /build/app.jar app.jar

USER forseti

# JVM tuning para 1GB RAM VPS + force IPv4 (Brevo whitelist)
ENV JAVA_TOOL_OPTIONS="\
    -Xms256m -Xmx512m \
    -XX:+UseG1GC \
    -XX:+UseStringDeduplication \
    -XX:+AlwaysActAsServerClassMachine \
    -Djava.net.preferIPv4Stack=true"

ENV SPRING_PROFILES_ACTIVE=prod
ENV SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_TOOL_OPTIONS -jar app.jar"]
